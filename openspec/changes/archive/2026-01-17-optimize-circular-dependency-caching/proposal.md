## Why

The current circular dependency fix for `@Lazy` rules adds overhead to hot paths that already consume 89% of transformation time. The fix enables utility methods to trigger `@Lazy` rules via `equivalentDiscriminated()`, but does so by adding a separate `executingLazyRules` map and additional conditional checks in the cache lookup path.

**Performance Impact:**
- Creates new `RuleCacheKey` objects for every `@Lazy` rule's `createTarget()` call
- Adds map operations (`putIfAbsent`, `get`) to already-slow cache path
- Adds conditional branches in `equivalentDiscriminated()` called 39,282 times

**Current Bottleneck (from analysis):**
- Cache operations: 4,060ms (89.3% of total)
- Guard evaluations: 4,003ms (88.1% of total)
- 93.1% of cache time is unaccounted (object allocation, map operations)

## What Changes

- Integrate early caching into existing `ResolutionCache` structure instead of separate map
- Use rule ordinal (int) instead of String-based `RuleCacheKey` to avoid object allocation
- Lazy-initialize circular dependency tracking only when recursion is actually detected
- Apply guard evaluation reduction (Priority 1 from bottleneck analysis)

## Impact

- Affected specs: `rule-execution`
- Affected code:
  - `TransformationContext.java`: `createTargetInPackage()`, `equivalentDiscriminated()`
  - `ResolutionCache.java`: Add in-progress tracking support
  - `TransformRuleDescriptor.java`: Add rule ordinal for O(1) lookup
  - `TransformationRegistry.java`: Assign ordinals at registration time
