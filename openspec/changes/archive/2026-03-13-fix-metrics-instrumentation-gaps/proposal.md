## Why

`TransformationMetrics.getReport()` produces inconsistent numbers when used with `RULE_BY_RULE` + `CLONE_CURRENT_STATE` execution paths. Cache hits + misses exceed total equivalent() calls, guard eval time exceeds its parent operation, `ruleExecutions` and `ruleIterations` are zero while `greedyRuleExecutions` is non-zero, and 78% of cache time is unaccounted. The root cause is that the RULE_BY_RULE executor path (`executeRuleForSource`) uses `recordGreedyRuleExecution()` and `recordGuardEvaluation()` but never calls `recordRuleExecution()`, `recordRuleIteration()`, or `recordGetRulesForSourceCall()`. Additionally, `equivalent(source, ruleName)` double-records cache misses (both on rejection check and on fall-through).

## What Changes

- Add missing `recordRuleIteration()` calls in `TransformationExecutor.executeRuleForSource()` and `transformRuleByRule()`
- Add missing `recordRuleExecution()` call in `executeRuleForSource()` when a rule produces a result
- Fix `equivalent(source, ruleName)` recording `recordEquivalentCacheMiss()` twice (once for rejection check at line 2017, and again unconditionally at line 2021)
- Fix guard evaluation time reporting: guard time is a subset of `cacheGetOrCreate` time, but the report shows it as a percentage of `greedyRuleNanos` which doesn't include guard time — show guard time correctly relative to its actual parent (`cacheGetOrCreate` or total transform time)
- Account for `cacheGetOrCreate` time breakdown in the report to eliminate the 78% unaccounted gap

## Capabilities

### New Capabilities

### Modified Capabilities
- `rule-execution`: Add requirement that metrics counters SHALL be incremented consistently across all execution strategies

## Impact

- `TransformationExecutor.java` — add missing `recordRuleIteration()`/`recordRuleExecution()` calls in RULE_BY_RULE path
- `TransformationContext.java` — fix double `recordEquivalentCacheMiss()` in `equivalent(source, ruleName)`
- `TransformationMetrics.java` — fix guard eval time reporting relative to correct parent; improve cache time breakdown
