## 1. Code Fix

- [ ] 1.1 Add `context.setCurrentGreedyPassRuleName(rule.getName())` call in `transformRuleByRuleParallel()` before the parallel chunking loop (matches line 972 in sequential version)
- [ ] 1.2 Wrap rule processing in try-finally block with `context.clearCurrentGreedyPassRuleName()` in finally clause

## 2. Test Coverage

- [ ] 2.1 Add test case verifying same-rule lookup behavior during parallel rule-by-rule execution (greedy rule calling `equivalent()` with its own rule name returns null for uncached elements)
- [ ] 2.2 Run existing tests to verify no regression
