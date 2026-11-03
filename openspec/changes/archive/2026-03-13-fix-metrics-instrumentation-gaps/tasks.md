## 1. Fix missing counters in RULE_BY_RULE path

- [x] 1.1 Add `recordRuleIteration()` in `TransformationExecutor.executeRuleForSource()` at entry
- [x] 1.2 Add `recordRuleExecution(ruleName)` in `executeRuleForSource()` when rule produces non-null result
- [x] 1.3 Add `recordRuleIteration()` in `transformRuleByRule()` inner loop for type-check/alias-check skips (covered by 1.1 — executeRuleForSource is already called only for matching elements)

## 2. Fix double cache miss counting

- [x] 2.1 Fix `equivalent(source, ruleName)` double-counting: remove spurious `recordEquivalentCacheHit()` at line ~2039 that fires after a miss was already recorded for activity-based rules

## 3. Fix guard time and cache breakdown in report

- [x] 3.1 Change guard eval time percentage in `getReport()` from `% of greedyRuleNanos` to `% of cacheGetOrCreateNanos`
- [x] 3.2 Update cache exclusive time calculation: subtract both `greedyRuleNanos` and `guardEvaluationNanos` from `cacheGetOrCreateNanos`

## 4. Tests

- [x] 4.1 Add test verifying `ruleIterations > 0` and `ruleExecutions > 0` after RULE_BY_RULE transformation
- [x] 4.2 Add test verifying `equivalentCacheHits + equivalentCacheMisses <= equivalentCalls` after a transformation with equivalent() calls
