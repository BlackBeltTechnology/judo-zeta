# Tasks: Fix EMF Containment Race Condition

## Phase 0: Create Failing Tests (TDD)

**Goal**: Create tests that FAIL before the fix, confirming the bug exists.

- [ ] **0.1** Create `ContainmentRaceConditionTest` class
  - Location: `transformation-core/src/test/java/.../ContainmentRaceConditionTest.java`
  - Use JUnit 5 with `@Nested` classes for organization

- [ ] **0.2** Create test that reproduces the NPE
  - Create transformation with flat structure (few parents, many children)
  - Multiple threads adding to same parent's containment
  - **Expected: Test FAILS with `NullPointerException: preparedResult is null`**
  - Mark test with `@DisplayName` explaining the bug

- [ ] **0.3** Create stress test for containment contention
  - 100 elements added to 5 shared parents concurrently
  - Repeat transformation 10 times to catch intermittent failures
  - **Expected: Test FAILS with corruption or NPE**

- [ ] **0.4** Create determinism test
  - Run same transformation 5 times in parallel mode
  - Compare element counts between runs
  - **Expected: Test FAILS (element count varies or NPE)**

- [ ] **0.5** Verify all Phase 0 tests FAIL
  - Run `mvn test -Dtest=ContainmentRaceConditionTest`
  - Document failure messages in test comments
  - **All tests must fail before proceeding to Phase 1**

## Phase 1: Enable Deferred Writes Integration

- [ ] **1.1** Modify `TransformationExecutor.transformWithStaging()`
  - Call `context.enableDeferredWrites()` before parallel phase
  - Call `context.disableDeferredWrites()` in finally block

- [ ] **1.2** Add `applyDeferredOperations()` to TransformationContext
  - Drain and sort operations from queue
  - Apply operations single-threaded
  - Clear queue after application

- [ ] **1.3** Update `transformWithStaging()` execution order
  - Phase 1: Enable staging + deferred writes
  - Phase 2: Transform parallel
  - Phase 3: Apply deferred operations (NEW)
  - Phase 4: Commit staged elements

## Phase 2: OperationQueue Enhancements

- [ ] **2.1** Add `drainSorted()` method to OperationQueue
  - Drain all operations to list
  - Sort by sequence number
  - Return sorted list

- [ ] **2.2** Verify operation sequence assignment
  - Ensure `nextSequence()` is atomic
  - Verify sequence numbers are unique across threads

## Phase 3: Validation

- [ ] **3.1** Verify Phase 0 tests now PASS
  - NPE reproduction test should pass
  - Stress test should show 0% corruption rate
  - Determinism test should show consistent results
  - **All Phase 0 tests must pass**

- [ ] **3.2** Run full test suite
  - `mvn test` - All existing tests must pass
  - Verify no performance regression

- [ ] **3.3** Add ASM2RDBMS-style integration test
  - Create model with RdbmsTable/RdbmsField structure
  - Verify parallel transformation succeeds
  - This test should PASS (added after fix)

## Phase 4: Documentation

- [ ] **4.1** Update test comments with fix verification
  - Change "Expected: FAIL" to "Fixed: PASS"
  - Document the fix applied

## Dependencies

```
Phase 0 (Tests FAIL) → Phase 1-2 (Implementation) → Phase 3 (Tests PASS)
                                                  → Phase 4 (Documentation)
```

- **Phase 0 must complete first** - Tests must fail before implementation
- Tasks 1.1-1.3 can be done in parallel
- Tasks 2.1-2.2 can be done in parallel with Phase 1
- Phase 3 depends on Phase 1 and 2 completion
