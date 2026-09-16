## Why

RULE_BY_RULE execution strategy has a dedicated parallel code path (`transformRuleByRuleParallel()`) but zero test coverage for it. All existing parallel tests (`ParallelStressTest`, `ParallelRaceConditionStressTest`, `ParallelSafetyTest`) use ELEMENT_BY_ELEMENT only. This leaves potential race conditions, cross-rule visibility issues, and incremental commit bugs undetected.

## What Changes

- Add comprehensive test suite for RULE_BY_RULE + parallel execution
- Cover cross-rule `equivalent()` resolution during parallel chunks
- Cover incremental commit visibility between rules
- Cover guard evaluation, greedy rules, lazy rules, and @Extends in parallel RULE_BY_RULE mode
- Add stress tests and race condition detection for RULE_BY_RULE parallel
- Verify the CLONE_CURRENT_STATE + RULE_BY_RULE + parallel rejection

## Capabilities

### New Capabilities
- `rule-by-rule-parallel-testing`: Test coverage for RULE_BY_RULE strategy with parallel execution, including cross-rule interactions, incremental commits, and thread-safety verification

### Modified Capabilities
- `parallel-transformation`: Add RULE_BY_RULE parallel test scenarios alongside existing ELEMENT_BY_ELEMENT coverage

## Impact

- Test files only — no production code changes expected
- `transformation-core/src/test/java/` — new test class(es)
- May reveal bugs in `TransformationExecutor.transformRuleByRuleParallel()` or `TransformationContext.commitDeferredOperationsIncremental()` that need fixing
