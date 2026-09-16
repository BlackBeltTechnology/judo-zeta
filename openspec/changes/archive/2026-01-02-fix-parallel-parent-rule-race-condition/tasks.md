# Tasks: Fix Parallel Parent Rule Race Condition

## Phase 1: Reproduce the Problem with Failing Tests

- [x] Add new nested test class to `ParallelFeatureCombinationTest.java`
  - Create `ParentRuleRaceConditionTests` nested class
  - Use minimal mock transformation rules for concurrency testing
  - Use `CountDownLatch` to maximize thread collision probability (50 threads)

- [x] Test: Concurrent executeParentRule() creates duplicates (should FAIL before fix)
  - Create 50 threads calling `executeParentRule("ParentRule", sameSource)` simultaneously
  - Count how many times the parent rule's transform function is invoked
  - Assert invocation count == 1 (will fail with current code, proving the race condition)
  - Track created target instances - assert only 1 unique instance exists

- [x] Test: Concurrent executeParentRule() with pre-created target (should FAIL before fix)
  - Create 50 threads calling `executeParentRule("ParentRule", sameSource, preCreatedTarget)`
  - Assert parent rule executes exactly once
  - Verify ETL-compatible behavior

- [x] Test: Concurrent @Extends inheritance creates duplicates (should FAIL before fix)
  - Create transformation with ChildRule @Extends ParentRule
  - Execute ChildRule from 50 threads for same source element
  - Count parent rule executions - assert == 1
  - Verify all threads receive identical target instance

- [x] Test: Multi-level inheritance chain race condition (should FAIL before fix)
  - Create GrandChild @Extends Child @Extends Parent chain
  - Execute GrandChild from 50 threads
  - Assert each level executes exactly once per source

- [x] Verify `equivalent()` method locking is correct
  - Review existing locking in `equivalent()` (lines 942-995)
  - Add test to verify concurrent `equivalent()` calls work correctly
  - Confirm no race condition in lazy rule execution path

- [x] Verify tests fail with current implementation
  - Run new tests, confirm they expose the race condition
  - Document expected vs actual behavior in test output

## Phase 2: Implementation

- [x] Update `TransformationContext.executeParentRule()` to use atomic `getOrCreate()`
  - Move cache check inside `getOrCreate()` supplier
  - Preserve inheritance state save/restore inside the supplier
  - Handle lazy rule context reset inside the supplier
  - Ensure ThreadLocal state is correctly managed

- [x] Update `TransformRuleDescriptor.executeParentRulesInChain()` to use atomic `getOrCreate()`
  - Replace `getByRule()` + `execute()` + `addMapping()` with `getOrCreate()`
  - Ensure idempotency is still achieved via cache

## Phase 3: Verify Fix

- [x] Verify Phase 1 tests now pass
  - All concurrent `executeParentRule()` tests pass
  - All `@Extends` inheritance tests pass
  - Multi-level chain tests pass

- [ ] Additional integration tests
  - Large model parallel transformation (22,000+ elements)
  - Multiple parallel runs produce identical results
  - Sequential vs parallel produces identical element counts

## Phase 4: Regression Tests

- [x] Run existing transformation test suite
  - All unit tests pass (439 tests)
  - All integration tests pass
  - GuardRejectionCacheTest passes (verifies rejection cache cleared on reset)

- [ ] Verify sequential mode unchanged
  - Sequential transformation produces same results
  - No performance regression

## Dependencies

- Phase 1 must complete before Phase 2 (tests prove the problem exists)
- Phase 2 must complete before Phase 3 (fix must be in place)
- Phase 3 must complete before Phase 4 (new tests must pass first)
