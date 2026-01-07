# Tasks: fix-greedy-rule-target-lookup

## Phase 1: Analysis

- [x] 1.1 Identify root cause: lazy rule activation only returns null
- [x] 1.2 Confirm fix approach: execute lazy rules immediately for explicit calls

## Phase 2: Implementation

- [x] 2.1 Modify `equivalent(EObject source, String ruleName)` to execute lazy rules immediately
- [x] 2.2 Add helper method `executeLazyRuleImmediately()`
- [x] 2.3 Run transformation-core tests to verify no regressions

## Phase 3: Testing

- [x] 3.1 Add unit test for cross-rule target lookup pattern
  - Created `GreedyLazyCrossRuleLookupTest.java` with:
    - Sequential mode test
    - Parallel mode test
    - Stress test (repeated 3x)
    - Edge case tests (non-existent rule, caching)
- [ ] 3.2 Run Esm2UiExternalModelTest to verify 208 dataElements
- [x] 3.3 Run full test suite for regressions
  - All transformation-core tests pass (BUILD SUCCESS)

## Phase 4: Validation

- [x] 4.1 Verify sequential mode produces correct results
  - Test: `testCrossRuleLookupSequential()` - 10 ClassTypes, 10 RelationTypes with targets, 0 null targets
- [x] 4.2 Verify parallel mode produces identical results
  - Test: `testCrossRuleLookupParallel()` - 50 ClassTypes, 50 RelationTypes with targets, 0 null targets

## Dependencies

- None - can proceed independently

## Parallelization

- Tasks 2.1-2.3 can be done sequentially
- Tasks 3.1-3.3 depend on Phase 2 completion
