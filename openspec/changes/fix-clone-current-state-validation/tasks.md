## 1. Code Fix

- [ ] 1.1 Update `validateConfiguration()` to check for `CLONE_CURRENT_STATE + parallel` (any execution strategy), not just RULE_BY_RULE
- [ ] 1.2 Update error message to clearly explain that CLONE_CURRENT_STATE requires sequential execution

## 2. Test Coverage

- [ ] 2.1 Add test case verifying `CLONE_CURRENT_STATE + ELEMENT_BY_ELEMENT + parallel` throws at build time
- [ ] 2.2 Add test case verifying `CLONE_CURRENT_STATE + RULE_BY_RULE + parallel` throws at build time
- [ ] 2.3 Add test case verifying `CLONE_CURRENT_STATE + parallel(false)` works for both execution strategies
- [ ] 2.4 Run existing tests to verify no regression
