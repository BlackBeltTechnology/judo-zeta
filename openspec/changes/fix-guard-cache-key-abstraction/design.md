# Design: Fix Guard Cache Key Abstraction

## Context

Guard evaluation in Zeta uses two caching layers, both keyed by `(rule, source)`:

1. **`TransformRuleDescriptor.rejected`** — a `Set<EObject>` per rule. When `evaluateGuard()` returns false, the source is added. Subsequent calls for the same `(rule, source)` short-circuit to false.
2. **`ElementResolutionCache.rejectedKeys`** — a `Map<EObject, Set<String>>` per `(source, ruleName)`. When `getOrCreate()` returns null (guard rejected OR rule returned null), the pair is marked rejected.

Both are keyed by rule identity, not guard identity. This was correct in ETL where guards are inline per-rule. In Zeta, guards are shared Java methods referenced by `@Guard(method = "...")`. Multiple rules can reference the same guard method, but each rule maintains its own rejected set — causing redundant guard evaluations.

In tatami-client's esm2ui transformation: 119 guarded rules, 18 guard methods shared by 2+ rules (e.g., `accessViewGuard` shared by 6 rules, `hasIcon` shared by 11). Performance benchmarks show seconds spent in guard evaluation.

## Goals / Non-Goals

**Goals:**
- Cache guard results by `(guardMethod, source)` so shared guards evaluate once per source element
- Remove the redundant per-rule `rejected` set from `TransformRuleDescriptor`
- Maintain thread-safety for parallel execution
- Preserve all existing behavior (guard-rejected elements still rejected, same semantics)

**Non-Goals:**
- Multi-source guard caching (rare/nonexistent in real usage, complex tuple keys)
- Cross-transformation caching (guard cache lives in context, scoped to one `transform()` call)
- Caching guard sub-expressions (e.g., `isGenerated()` called inside different guards — that's a code-level concern in downstream projects)

## Decisions

### Decision 1: Cache key is `(java.lang.reflect.Method, EObject)`

**Rationale:** When two rules use `@Guard(method = "isNotAbstract")` in the same `@TransformationContext` class, they resolve to the same `java.lang.reflect.Method` object. Using `Method` as key guarantees identity-correct matching without string comparison. Guards in different classes with the same name correctly get different cache entries (different `Method` objects).

**Alternative considered:** Key by method name string — rejected because same-name guards in different classes could have different implementations.

**Alternative considered:** Key by `(Class, methodName)` — works but `Method` object is simpler and already available on `TransformRuleDescriptor.guardMethod`.

### Decision 2: Cache lives in `TransformationContext`

**Rationale:** The context is per-transformation-run and shared across all rules. Guard results are deterministic within a run (source model is read-only, context attributes are immutable after setup). Placing the cache here means:
- Automatic scoping — no stale results between `transform()` calls (new context = new cache)
- Shared across rules — the whole point of method-level caching
- Thread-safe access via `ConcurrentHashMap` (same pattern as existing caches)

**Alternative considered:** Separate `GuardResultCache` class — rejected for simplicity. A field and two methods on `TransformationContext` suffice.

### Decision 3: Cache both `true` and `false` results

**Rationale:** The current per-rule cache only caches rejections (false). But for shared guards, caching positive results (true) is equally important — e.g., 6 rules sharing `accessViewGuard`, if it passes for a source, 5 subsequent calls can skip evaluation.

### Decision 4: Remove `TransformRuleDescriptor.rejected`

**Rationale:** With guard results cached at the method level, the per-rule rejected set is redundant. Its only remaining use cases:
- `evaluateGuard()` self-check → replaced by guard method cache
- `@Extends` parent guard check (line 737) → uses `evaluateGuard()` which now checks method cache
- `equivalent()` lazy resolution → uses `evaluateGuard()` which now checks method cache

`ElementResolutionCache.rejectedKeys` remains — it caches "getOrCreate returned null" (which can mean guard rejection OR rule returning null), keyed by `(source, ruleName)`. This is a different concern.

### Decision 5: Functional-interface guards use the factory method as cache key

For new-style guards that return a `TransformGuard` lambda, the `guardMethod` field on `TransformRuleDescriptor` is the factory method (e.g., `isNotAbstract()` that returns a lambda). Two rules referencing the same factory method get the same `Method` object → same cache key. This works correctly because the factory is called once and the lambda is cached — the guard evaluation uses the cached lambda, and the cache key is the factory method.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Context-dependent guards reading mutable context state | Context attributes are set at creation and immutable during `transform()`. Source model is read-only. Guard results are deterministic within a run. |
| Memory overhead of caching all `(method, source)` pairs | Same order as current `rejected` sets but shared instead of duplicated per-rule. Net memory may decrease since N per-rule sets collapse to 1 shared map. |
| `TransformRuleDescriptor.rejected` removal breaks API | `wasRejected()`, `recordRejection()`, `clearRejected()` are internal API (package-private or used only by executor/registry). No public API break. |
| Greedy passes need fresh guard evaluation | Guards are deterministic within a run — source model doesn't change between passes. Cached result is still correct. |
