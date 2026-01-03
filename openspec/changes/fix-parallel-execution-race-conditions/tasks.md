# Tasks: Fix Parallel Execution Race Conditions

## Phase 1: Immediate Fixes (Short-Term)

- [x] ~~**1.1 Add global lock for cross-thread cache access**~~
  - **NOT NEEDED** - Current implementation already has proper locking:
    - `equivalent()` uses `getOrCreate()` with per-key locking
    - `equivalentDiscriminated()` fixed in Task 1.3.2
  - *Status:* SKIPPED

- [ ] **1.2a Reproduce EMF containment race condition with tests** *(PREREQUISITE)*

  **Goal:** Create a failing test that reproduces the Tatami project issue before implementing any fix.

  **Test scenario:**
  - Enable `autoAddRootElements=true`
  - Create parent and child elements in parallel rules
  - Child created first, staged as root element
  - Parent sets containment reference to child in different thread
  - Verify: orphaned elements appear as root elements in resource

  **Test file:** `transformation-core/src/test/java/.../ContainmentRaceConditionTest.java`

  **Expected result:** Test should FAIL initially, proving the race condition exists.

  - *Dependency:* None
  - *Blocking:* Task 1.2b (fix cannot proceed until reproduction confirmed)

- [ ] **1.2b Defer EMF Containment to Commit Phase** *(CRITICAL)*

  **Problem:** EMF containment operations are NOT thread-safe. When `autoAddRootElements=true`:
  1. Every element created via `createTarget()` is staged with `isRootElement=true`
  2. During parallel execution, containment assignments like `parent.setIcon(icon)` race with other threads
  3. EMF's bidirectional reference updates are corrupted
  4. Icons lose their container reference (`eContainer() == null`)
  5. During commit, orphaned Icons are added to the resource as root elements

  **Solution: Defer containment to commit phase**
  - Stage containment operations instead of executing immediately
  - Execute all containment assignments in single-threaded commit phase
  - *Pros:* No synchronization needed during parallel phase
  - *Cons:* More complex implementation, requires tracking deferred operations

  **Implementation:**
  - Create `DeferredContainment` class to record pending containment operations
  - Add `deferContainment(parent, featureName, child)` API to TransformationContext
  - Execute deferred containments in single-threaded commit phase
  - Re-check `eContainer()` at commit time to avoid duplicates

  - *Dependency:* **1.2a** (must reproduce issue first)
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java`
  - *Related:* Esm2UiZetaTransformation.java:272 (`autoAddRootElements=true`)
  - *Note:* If 1.2a cannot reproduce the issue, this task and proposal will be updated accordingly

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

- [ ] **2.1 Verify fixes don't break existing tests**
  - Run all 600+ tests in sequential mode
  - Run all tests in parallel mode
  - Verify no performance regression > 20%
  - *Dependency:* 1.2a, 1.2b

- [ ] **2.2 Run full test suite**
  - All tests pass
  - No regressions in sequential mode
  - Parallel mode produces identical results
  - *Dependency:* 2.1

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
