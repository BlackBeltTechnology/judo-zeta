## Context

The Zeta `TransformationContext.equivalent()` uses identity-based caching (`IdentityHashMap` in sequential mode). When consuming transformations have source models where the same logical element exists as multiple Java object instances (e.g., extension/proxy copies), the identity-based lookup fails. The current workaround in `judo-tatami-base` uses `resolveTypeByName()` which searches the target resource set — but this is empty during rule execution because `addToResource()` runs in `postProcess()`.

The framework needs a convenience method to search cached targets by EMF name as a fallback.

## Goals / Non-Goals

**Goals:**
- Provide `findCachedTargetByName(name, targetType)` on `TransformationContext` to search all cached transformation targets by `eClass().getName()`
- Add `findByName(name, targetType)` to `ElementResolutionCache` for the underlying search
- Reproduce the identity-mismatch scenario with tests
- Ensure the fix works in both sequential and parallel modes

**Non-Goals:**
- Changing the behavior of `equivalent()` itself — identity-based caching is correct by design
- Fixing consuming transformations (`judo-tatami-base`) — they can adopt the new API separately
- Adding name-based indexing to the cache (overkill for a fallback path)

## Decisions

### Decision 1: Linear scan over all cache entries vs. name-based index

**Chosen:** Linear scan of `ruleCache` values.

**Rationale:** This is a fallback path for proxy/copy edge cases. The number of cached targets is typically in the thousands, making linear scan fast enough. A name-based index would add memory overhead and complexity to `addMapping()` on the hot path for every transformation, not just the edge case.

**Alternative considered:** `Map<String, List<EObject>>` index by name — rejected due to hot-path overhead.

### Decision 2: Method on TransformationContext vs. ElementResolutionCache

**Chosen:** Both — `ElementResolutionCache.findByName()` does the search, `TransformationContext.findCachedTargetByName()` delegates.

**Rationale:** Keeps cache logic in the cache class (single responsibility), while providing the convenient API on the context that rules interact with.

### Decision 3: Return first match vs. all matches

**Chosen:** Return first match (single `T`), with a separate `findAllCachedTargetsByName()` returning `List<T>` if needed later.

**Rationale:** The consuming use case (`resolveTypeByName`) expects a single result. Type names are unique within a well-formed model.

## Risks / Trade-offs

- **[Linear scan performance]** → Acceptable for fallback path; O(n) where n = total cached targets. Typical n < 10,000. If profiling shows issues, can add name index later.
- **[Name collisions]** → Different source packages could produce targets with the same name. First-match semantics could return the wrong one. → Mitigation: callers should use `equivalent()` as primary path; `findCachedTargetByName` is the fallback.
- **[Thread safety]** → In parallel mode, `ruleCache` is `ConcurrentHashMap`. Iterating it during concurrent writes is safe (weakly consistent). No additional synchronization needed.
