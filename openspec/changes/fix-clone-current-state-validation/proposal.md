## Why

The `validateConfiguration()` method only rejects `CLONE_CURRENT_STATE + RULE_BY_RULE + parallel` combinations, but `equivalentDiscriminated()` throws at runtime for ANY `CLONE_CURRENT_STATE + deferredWritesEnabled` combination. Since ELEMENT_BY_ELEMENT + parallel also enables deferred writes, users get confusing runtime errors instead of fail-fast validation.

## What Changes

- Extend `validateConfiguration()` to reject `CLONE_CURRENT_STATE + ELEMENT_BY_ELEMENT + parallel` combinations
- Provide clear error messages explaining why the combination is unsupported
- **BREAKING**: Configurations that previously threw at runtime will now fail fast at executor build time

## Capabilities

### New Capabilities
None - this is a validation improvement.

### Modified Capabilities
- `clone-current-state`: Clarify that CLONE_CURRENT_STATE is incompatible with ANY parallel mode (not just RULE_BY_RULE)
- `parallel-transformation`: Clarify that parallel mode enables deferred writes for both execution strategies

## Impact

- **Affected code**: `TransformationExecutor.validateConfiguration()` method
- **Behavior change**: Earlier failure detection (build time vs runtime)
- **Risk**: None - fails fast for configurations that would already fail at runtime
- **Dependencies**: None - uses existing validation infrastructure
