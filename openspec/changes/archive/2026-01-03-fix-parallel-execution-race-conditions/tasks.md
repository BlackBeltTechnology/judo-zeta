# Tasks: Fix Parallel Execution Race Conditions

## Phase 1: Immediate Fixes (Short-Term)

- [x] ~~**1.1 Add global lock for cross-thread cache access**~~
  - **NOT NEEDED** - Current implementation already has proper locking:
    - `equivalent()` uses `getOrCreate()` with per-key locking
    - `equivalentDiscriminated()` fixed in Task 1.3.2
  - *Status:* SKIPPED

- [x] **1.2a Reproduce EMF containment race condition with tests** *(COMPLETE)*

  **Goal:** Create a failing test that reproduces the autoAddRootElements issue.

  **DISCOVERY:** Investigation revealed two distinct issues:

  **Issue 1: Sequential Mode Bug (NOT a race condition)**
  - With `autoAddRootElements=true`, `createTarget()` adds elements to Resource.contents immediately
  - When element is later added to containment, EMF does NOT remove it from Resource.contents
  - This is documented EMF behavior (see `EmfContainmentBehaviorTest.java`)
  - **Result:** Sequential mode creates duplicate root elements (100% failure rate)

  **Issue 2: Parallel Mode Works Correctly**
  - Parallel mode uses staging with commit phase
  - Commit phase checks `eContainer() == null` before adding to Resource.contents
  - **Result:** Parallel mode correctly excludes contained elements from root

  **Test files created:**
  - `ContainmentRaceConditionTest.java` - Bug reproduction tests
  - `EmfContainmentBehaviorTest.java` - Documents EMF containment behavior

  **Test results:**
  - Sequential mode: 2000 root elements (1000 packages + 1000 orphaned classes) - BUG
  - Parallel mode: 1000 root elements (1000 packages only) - CORRECT

  **Conclusion:** The original Tatami issue may be a different problem than what was documented in the proposal. The parallel mode commit phase correctly handles autoAddRootElements. The sequential mode is broken.

  - *Status:* ✅ COMPLETE - Bug reproduced and characterized
  - *Dependency:* None

- [x] **1.2b Fix Sequential Mode autoAddRootElements Bug** *(COMPLETE)*

  **Problem:** In sequential mode with `autoAddRootElements=true`:
  1. `createTarget()` immediately adds element to Resource.contents
  2. When element is later added to containment (e.g., `pkg.getEClassifiers().add(cls)`), EMF sets `eContainer` but does NOT remove from Resource.contents
  3. Result: Element appears in both Resource.contents AND inside its container
  4. This is standard EMF behavior - Resource.contents is not automatically cleaned up

  **Note:** Parallel mode works correctly because the commit phase has:
  ```java
  if (element.isRootElement && obj.eContainer() == null) {
      targetResource.getContents().add(obj);
  }
  ```

  **Solution Implemented: Cleanup phase approach**

  Added `cleanupContainedRootElements()` method to `TransformationContext` that removes any elements from `Resource.contents` that have `eContainer() != null`. This is called after sequential transformation completes when `autoAddRootElements=true`.

  This approach:
  - Preserves existing immediate-add behavior for backward compatibility
  - Mirrors parallel mode's commit phase check but as a cleanup phase
  - Does not require deferred staging in sequential mode

  **Changes Made:**
  - Added `TransformationContext.cleanupContainedRootElements()` method (lines 2053-2086)
  - Updated both `TransformationExecutor.transform()` methods to call cleanup after sequential transformation

  **Test Results:**
  - `ContainmentRaceConditionTest` - All 23 tests pass
  - Sequential mode: 100 packages, 0 orphaned classes (FIXED)
  - Parallel mode: 100 packages, 0 orphaned classes (unchanged)
  - All 607 transformation-core tests pass

  - *Dependency:* **1.2a** (completed)
  - *Location:* `TransformationContext.java`, `TransformationExecutor.java`
  - *Status:* ✅ COMPLETE

- [x] **1.3 Add stress tests for high contention scenarios**
  - Created `ParallelRaceConditionStressTest.java`
  - Tests with 6 eager rules calling `equivalent()` on same 5000 sources
  - Tests cross-rule reference chains (A→B→C)
  - Tests @Extends under parallel execution
  - **Result:** All tests pass - `equivalent()` has proper locking
  - *Location:* `transformation-core/src/test/java/.../ParallelRaceConditionStressTest.java`

- [x] **1.3.1 Reproduce equivalentDiscriminated() race condition**
  - Created `EquivalentDiscriminatedRaceTest.java`
  - **BUG CONFIRMED**: `equivalentDiscriminated()` lacks locking
  - Cross-entity reference test: 500 referrers × 6 discriminators = 3000 calls on same source
  - Expected: 6 unique clones (cached), Actual: 7-17 clones (1-11 extra per run)
  - 18/30 runs failed (60% failure rate)
  - `equivalent(source, Class)` tests **pass** (proper locking exists)
  - *Location:* `transformation-core/src/test/java/.../EquivalentDiscriminatedRaceTest.java`

- [x] **1.3.2 Add locking to equivalentDiscriminated()**
  - Added `DiscriminatedCacheKey` class for per-key locking
  - Added `discriminatedLocks` ConcurrentHashMap for thread-safe lock management
  - Implemented double-checked locking pattern around clone creation
  - **Result:** All 30 test runs pass with exactly 6 clones (0 duplicates)
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java` (lines 1403-1475)

## Phase 2: Validation

- [x] **2.1 Verify fixes don't break existing tests** *(COMPLETE)*
  - Run all 600+ tests in sequential mode: ✅ 607 tests pass
  - StagingInfrastructureTest: ✅ 19 tests pass (no regressions)
  - ContainmentRaceConditionTest: ✅ 23 tests pass
  - *Dependency:* 1.2a, 1.2b
  - *Status:* ✅ COMPLETE

- [x] **2.2 Run full test suite** *(COMPLETE)*
  - All 607 tests pass (11 conditionally skipped)
  - No regressions in sequential mode
  - Parallel mode produces identical results to sequential mode
  - *Dependency:* 2.1
  - *Status:* ✅ COMPLETE

---

## Future Work (Separate Proposal)

> **Note:** The Thread-Isolated Architecture has been moved to a separate proposal: `implement-thread-isolated-parallel-architecture`
>
> This proposal focuses on immediate correctness fixes. The long-term architectural improvements will be addressed separately after these fixes are validated.

## Verification Criteria

1. Sequential and parallel modes produce identical element counts
2. All containment relationships are correct
3. No NPE during EMF iteration
4. No duplicate elements in collections
5. All 600+ tests pass
