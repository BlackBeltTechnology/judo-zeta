# Tasks: Fix Proxy Unwrapping After Transformation

## Status: Implemented

## Phase 1: Implementation

- [x] **1.1** Add `unwrapAllProxiesInModel()` call in `TransformationExecutor.transformWithStaging()`
  - Location: After `commitStagedElements()`, before finally block
  - Added as Phase 4 with metrics tracking via `addProxyUnwrapNanos()`

- [x] **1.2** Add cache proxy unwrapping in `ElementResolutionCache`
  - Added `unwrapAllProxies()` method to unwrap all cached targets
  - Unwraps `ruleCache`, `typeCache`, `primaryCache`, and `discriminatedCache`
  - Critical: cached proxies returned by `equivalent()` would cause ClassCastException

- [x] **1.3** Update `unwrapAllProxiesInModel()` to also unwrap cache
  - Phase 1: Unwrap all proxies in the resolution cache
  - Phase 2: Unwrap proxies in the target resource
  - Ensures `equivalent()` returns real EMF objects, not proxies

## Phase 2: Unit Tests

- [x] **2.1** Add test verifying no proxies remain after parallel transformation
  - Created `ProxyUnwrapAfterTransformTest.java`
  - Tests `testNoProxiesAfterParallelTransform()` - 50 sources with containment
  - Tests `testEMFGetEAllOperationsWorks()` - verifies EOperationImpl casting

- [x] **2.2** Add stress test with many containment proxies
  - Test `testStressManyContainmentProxies()` - 100 sources with 5 operations each
  - Verified all 500 operations are real objects, not proxies

## Phase 3: Integration Testing

- [x] **3.1** Run existing parallel transformation tests
  - All tests pass (BUILD SUCCESS)
  - No proxy ClassCastException errors

- [x] **3.2** Verify with PSM2ASM-like patterns
  - Tests iterate `getEAllOperations()` without ClassCastException
  - Tests verify objects can be cast to `EOperationImpl`

## Files Changed

- `TransformationExecutor.java` - Added Phase 4 proxy unwrap call
- `TransformationMetrics.java` - Added `proxyUnwrapNanos` field and methods
- `TransformationContext.java` - Updated `unwrapAllProxiesInModel()` to also unwrap cache
- `ElementResolutionCache.java` - Added `unwrapAllProxies()` method
- `ProxyUnwrapAfterTransformTest.java` - New test file (3 tests)

## Acceptance Criteria

1. [x] No proxy objects (`$ProxyN`) remain in model after transformation
2. [x] All EMF internal iterators work correctly (getEAllOperations, etc.)
3. [x] All existing tests pass
4. [x] No ClassCastException when accessing model elements
5. [x] `equivalent()` returns real EMF objects, not cached proxies

## Dependencies

- None (self-contained change in judo-zeta)

## Parallelizable Work

- Phase 1 and Phase 2 can be done in parallel
- Phase 3 depends on Phase 1

## Root Cause Analysis (Updated)

The original fix in Phase 1.1 was incomplete. The issue was:

1. `createTarget()` returns proxies when `deferredWritesEnabled` is true
2. Rules return these proxies as their result
3. `addMapping()` stores proxies directly in the cache
4. `unwrapAllProxiesInModel()` only unwrapped proxies in the resource, not the cache
5. After transformation, if `equivalent()` is called (e.g., in postProcess), it returns cached proxies
6. These proxies end up in containment references, causing ClassCastException

The fix (Phase 1.2 and 1.3) ensures the cache is also unwrapped, so `equivalent()` returns real EMF objects.
