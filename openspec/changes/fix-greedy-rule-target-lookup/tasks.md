# Tasks: fix-greedy-rule-target-lookup

## Phase 1: Analysis

- [x] 1.1 Identify root cause: lazy rule activation only returns null
- [x] 1.2 Confirm fix approach: execute lazy rules immediately for explicit calls

## Phase 2: Implementation

- [x] 2.1 Modify `equivalent(EObject source, String ruleName)` to execute lazy rules immediately
- [x] 2.2 Add helper method `executeLazyRuleImmediately()`
- [x] 2.3 Run transformation-core tests to verify no regressions

## Phase 3: Testing

- [ ] 3.1 Add unit test for cross-rule target lookup pattern
- [ ] 3.2 Run Esm2UiExternalModelTest to verify 208 dataElements
- [ ] 3.3 Run full test suite for regressions

## Phase 4: Validation

- [ ] 4.1 Verify sequential mode produces correct results
- [ ] 4.2 Verify parallel mode produces identical results

## Dependencies

- None - can proceed independently

## Parallelization

- Tasks 2.1-2.3 can be done sequentially
- Tasks 3.1-3.3 depend on Phase 2 completion
