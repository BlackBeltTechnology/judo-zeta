# Proposal: Fix Guard Cache Key Abstraction

## Why

Guard evaluation results are cached per `(rule, source)` pair, inherited from ETL where each rule owns its guard inline. In Zeta, multiple rules can share the same guard method via `@Guard(method = "...")`, making `(rule, source)` the wrong cache key — the same guard is re-evaluated redundantly for each rule that references it. Performance benchmarks show seconds spent in guard evaluation with thousands of redundant calls. The correct key is `(guardMethod, source)` since guards are pure functions of source elements and immutable context attributes.

## What Changes

- Add a guard-method-level result cache in `TransformationContext`, keyed by `(java.lang.reflect.Method, EObject)`, caching both `true` and `false` results
- Remove the per-rule `TransformRuleDescriptor.rejected` set — it caches at the wrong granularity and is redundant with `ElementResolutionCache.rejectedKeys`
- Modify `TransformRuleDescriptor.evaluateGuard()` to use the new guard method cache instead of the per-rule rejected set
- Keep `ElementResolutionCache.rejectedKeys` unchanged — it serves a different purpose (caching "getOrCreate returned null" per rule, not guard results)

## Capabilities

### New Capabilities
- `guard-method-cache`: Guard evaluation result caching keyed by `(guardMethod, source)` shared across all rules that reference the same guard method

### Modified Capabilities
- `rule-execution`: Guard evaluation in `TransformRuleDescriptor.evaluateGuard()` changes from per-rule rejection caching to guard-method-level result caching

## Impact

- **TransformRuleDescriptor**: Remove `rejected` field, `wasRejected()`, `recordRejection()`, `clearRejected()`. Modify `evaluateGuard()` to use guard method cache.
- **TransformationContext**: Add guard result cache field and accessor methods.
- **TransformationRegistry**: Remove `clearAllRejectedSets()` (no longer needed — guard cache lives in context which is per-transformation).
- **ElementResolutionCache**: No changes — `rejectedKeys` remains for overall result caching.
- **Tests**: `GuardRejectionCacheTest` needs updates to test method-level caching instead of per-rule caching. Existing integration tests should pass without changes (behavior is the same, only cache granularity changes).
