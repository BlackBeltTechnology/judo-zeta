## Context

`TransformationMetrics` has two separate counter families:
- **`ruleExecutions`/`ruleIterations`**: incremented inside `TransformationContext`'s lazy equivalent() paths (on-demand rule execution)
- **`greedyRuleExecutions`/`guardEvaluations`**: incremented inside `TransformationExecutor.executeRuleForSource()` (greedy pass)

The RULE_BY_RULE strategy exclusively uses `executeRuleForSource()`, so `ruleExecutions` and `ruleIterations` stay at zero. The report shows both counters side-by-side, making it look broken.

Timer nesting: `cacheGetOrCreate` contains guard eval + rule execution, but the report subtracts only `greedyRuleNanos` (not guard time), leaving 78% unaccounted.

## Goals / Non-Goals

**Goals:**
- All metrics counters increment consistently regardless of execution strategy
- Cache hits + misses = total equivalent() calls (eliminate double-counting)
- Guard eval time displays correctly relative to its actual parent
- Cache time breakdown accounts for all sub-components

**Non-Goals:**
- Adding new metrics categories
- Changing the report format layout
- Performance optimization of metrics collection

## Decisions

**1. Add `recordRuleIteration()` + `recordRuleExecution()` to `executeRuleForSource()`**

`executeRuleForSource()` is the shared path for both element-by-element and rule-by-rule greedy passes. Add:
- `recordRuleIteration()` at entry (before guard eval)
- `recordRuleExecution(ruleName)` when rule produces a non-null result

This makes the counters consistent across all strategies. The `greedyRuleExecutions` counter remains as a separate greedy-specific counter.

**2. Fix double `recordEquivalentCacheMiss()` in `equivalent(source, ruleName)`**

Lines 2016-2021: when `isRejected()` returns false, execution falls through to line 2021 which records a second cache miss. Fix: use `else` to make the miss recording mutually exclusive with the rejection check.

**3. Fix guard time reporting**

Change the report to show guard eval time as percentage of `cacheGetOrCreate` (its actual parent) instead of `greedyRuleNanos` (which excludes guard time). Also subtract `guardEvaluationNanos` from the cache exclusive calculation.

**4. Account for guard time in cache breakdown**

Line 519: `cacheExclusiveMs = Math.max(0, cacheGetOrCreateMs - greedyMs)` should be:
`cacheExclusiveMs = Math.max(0, cacheGetOrCreateMs - greedyMs - guardEvalMs)`

## Risks / Trade-offs

- [Existing reports will show different numbers] → Intentional — numbers will now be correct
- [Tests may need updating if they assert specific counter values] → Check `ExecutionStrategyTest` for metrics assertions
