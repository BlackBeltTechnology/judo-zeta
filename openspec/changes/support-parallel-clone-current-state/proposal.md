## Why

The `CLONE_CURRENT_STATE` discrimination strategy is currently incompatible with parallel execution, forcing users to choose between ETL-compatible semantics and parallel performance. This limitation blocks adoption for large-scale transformations that need both features.

## What Changes

- Implement **version tracking with Phaser coordination** to support `CLONE_CURRENT_STATE` in parallel mode
- Add `VersionedOriginalRegistry` to track mutation phases per `(source, ruleName)` pair
- Wrap first-caller originals with `PhaseAwareEObject` proxy that signals phase completion
- Subsequent callers wait for previous phase to complete before cloning, ensuring deterministic results
- Remove the runtime check that rejects `CLONE_CURRENT_STATE + deferredWritesEnabled`

## Capabilities

### New Capabilities
- `parallel-clone-current-state`: Enables CLONE_CURRENT_STATE discrimination strategy in parallel transformation mode

### Modified Capabilities
- `clone-current-state`: Remove "requires sequential execution" constraint, add version tracking semantics
- `parallel-transformation`: Clarify that all discrimination strategies now work in parallel mode

## Impact

- **Affected code**: `TransformationContext`, `TransformationExecutor`, new `VersionedOriginalRegistry` class
- **Behavior change**: `CLONE_CURRENT_STATE + parallel` now works instead of throwing `IllegalStateException`
- **Performance**: Minimal overhead for non-CLONE_CURRENT_STATE cases; Phaser coordination adds ~1-2ms per discriminated call in parallel mode
- **Risk**: Medium - introduces new coordination mechanism but isolates changes to CLONE_CURRENT_STATE path
- **Dependencies**: Java 21+ (uses `java.util.concurrent.Pher` with modern features)
