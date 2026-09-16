## Why

The parallel version of rule-by-rule execution does not set `currentGreedyPassRuleName` while the sequential version does. This breaks ETL-compatible lazy rule execution semantics, causing incorrect behavior when greedy rules call `equivalent()` during their own greedy pass.

## What Changes

- Add `context.setCurrentGreedyPassRuleName(rule.getName())` call in `transformRuleByRuleParallel()` before processing each rule
- Add corresponding `context.clearCurrentGreedyPassRuleName()` in the finally block after rule completes
- Ensure thread-safe handling since parallel chunks execute concurrently

## Capabilities

### New Capabilities
None - this is a bug fix for existing functionality.

### Modified Capabilities
- `rule-by-rule-execution`: Clarify that `currentGreedyPassRuleName` MUST be set in both sequential and parallel rule-by-rule execution

## Impact

- **Affected code**: `TransformationExecutor.transformRuleByRuleParallel()` method
- **Behavior change**: Lazy rule invocation from within greedy rules during parallel rule-by-rule will now correctly detect same-rule lookups
- **Risk**: Low - the fix adds missing state tracking that should have been there from the start
- **Dependencies**: None - uses existing `TransformationContext` APIs
