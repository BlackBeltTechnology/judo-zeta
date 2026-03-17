## Why

When a rule decorated with `@Extends` calls `executeParentRule(name, source, null)` for a *different* source element than the one being transformed, the `preCreatedTarget` ThreadLocal from the active inheritance chain leaks into the child rule invocation. `createTarget()` then returns the wrong pre-created object because it only checks `targetType.isInstance(preCreated)` — not whether the pre-created object belongs to the current call. This causes silent data corruption in consumer transformations where the model contains a `TransferObjectType` defined *after* its referencing `ActorType`.

## What Changes

- Fix `TransformationContext.executeParentRule()`: broaden the inheritance-state reset condition so non-lazy parent rules called with `target=null` also clear `preCreatedTarget` before executing.
- Add a dedicated failing test (`ExtendsPrecreatedTargetLeakTest`) that reproduces the bug using an `ActorType → TransferObjectType` inheritance scenario.

## Capabilities

### New Capabilities
- `extends-execute-parent-isolation`: Guarantee that `executeParentRule` calls made inside an `@Extends` inheritance chain do not inherit the chain's `preCreatedTarget` when invoked for a different source element.

### Modified Capabilities
- `rule-execution`: The guard/create-target contract during `executeParentRule` is tightened — callers passing `target=null` always get a fresh target regardless of active inheritance state.

## Impact

- **`TransformationContext.java`**: one-line condition change in `executeParentRule()` (remove `&& parentRule.isLazy()` from the inheritance-clear branch).
- **New test file**: `ExtendsPrecreatedTargetLeakTest.java` in `transformation-core/src/test/`.
- No public API changes; no breaking changes to annotation semantics.
