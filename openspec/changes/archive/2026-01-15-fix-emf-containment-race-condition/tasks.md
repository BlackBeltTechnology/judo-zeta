# Tasks: Fix EMF Containment Race Condition

## Status: COMPLETED

### Implementation Summary

Implemented **Option A: Apply pending values before cloning** to fix the incompatibility between deferred writes and `EcoreUtil.copy()`.

**Solution details:**
1. Added `applyPendingValues()` method to `DeferredEObject.ProxyMarker` interface
2. Implemented `doApplyPendingValues()` to flush pending values to the delegate
3. Added `unwrapWithPendingValues()` static helper method
4. Updated `equivalentDiscriminated()` to use `unwrapWithPendingValues()` before cloning
5. Re-enabled deferred writes in `transformWithStaging()`

**Test results:** All 575 tests pass, including `EquivalentDiscriminatedRaceTest.testCrossEntityReferenceRace` which previously failed.

### Problem Summary (Resolved)

The original issue was that enabling deferred writes caused test failures because:

1. **Proxy wrapping breaks EcoreUtil.copy()**: When deferred writes are enabled, `createTarget()` returns a proxy wrapper. When `equivalentDiscriminated()` clones the object using `EcoreUtil.copy()`, it gets a copy of the DELEGATE (unwrapped object) which doesn't have the pending values applied.

2. **Pending values not visible**: The proxy stores pending set/unset operations in a `pendingValues` map for read-after-write consistency. But when we unwrap and copy, the delegate doesn't have these values applied yet.

**The fix:** Call `applyPendingValues()` before `EcoreUtil.copy()` to flush pending values from the proxy to the delegate, ensuring clones have the correct data.

## Completed Task List

### Phase 0: Create Failing Tests (TDD)

- [x] **0.1** `ContainmentRaceConditionTest` class exists
- [x] **0.2** Test infrastructure exists - `EquivalentDiscriminatedRaceTest` covers the scenario
- [x] **0.3** Stress tests exist and pass
- [x] **0.4** Determinism tests exist and pass

### Phase 1: Enable Deferred Writes Integration

- [x] **1.1** Enable deferred writes in `transformWithStaging()` - COMPLETED
- [x] **1.2** `commitDeferredOperations()` already implemented
- [x] **1.3** Deferred writes enabled by default for parallel transformations

### Phase 2: Option A Implementation

- [x] **2.1** Added `applyPendingValues()` to `ProxyMarker` interface
- [x] **2.2** Implemented `doApplyPendingValues()` in `DeferredEObject`
- [x] **2.3** Added `unwrapWithPendingValues()` static helper method
- [x] **2.4** Updated `equivalentDiscriminated()` to use `unwrapWithPendingValues()`

### Phase 3: Validation

- [x] **3.1** All 575 tests pass with deferred writes enabled
- [x] **3.2** `EquivalentDiscriminatedRaceTest.testCrossEntityReferenceRace` passes
- [x] **3.3** Parallel transformation correctly applies deferred operations

## Files Modified

1. `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/deferred/DeferredEObject.java`
   - Added `applyPendingValues()` to `ProxyMarker` interface
   - Added `doApplyPendingValues()` implementation
   - Added `unwrapWithPendingValues()` static helper

2. `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`
   - Changed `DeferredEObject.unwrap()` to `DeferredEObject.unwrapWithPendingValues()` in `equivalentDiscriminated()`

3. `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`
   - Enabled deferred writes in `transformWithStaging()`
   - Added `commitDeferredOperations()` before staging commit
   - Added cleanup in finally block
