## Why

When a child rule with `@Extends` runs, the framework pre-creates a target and sets a structured XMI ID based on the **child** rule name (e.g., `.../TabularReferenceFieldLink`). Then the parent rule executes and calls `createTarget(Class, source, parentRuleName)` which internally calls `createTarget(Class, String customId)`. This method:

1. Calls `createTarget(Class)` — returns the pre-created target (correct, via inheritance mode)
2. Calls `setElementIdInternal(instance, customId)` — **overwrites** the child's ID with the parent's ID (e.g., `.../TabularLink`)

This ID overwrite causes two problems:
- The pre-created target loses its correct child-based ID
- Any subsequent `createTarget()` call within the parent rule (e.g., creating an inline Icon) gets an auto-generated ID that collides with the now-overwritten ID on the pre-created target

**Observed in production:** The Zeta ESM-to-UI transformation produces 50 UI model differences vs ETL in the northwind model, with 15 XMI ID collisions logged:
```
XMI ID COLLISION DETECTED: ID 'InternalUser/(esm/_bkLKwDSTEeuqJdxygIlDEw)/TabularLink'
  is being reassigned from Link to Icon
```

## What Changes

- `TransformationContext.createTarget(Class<T>, String customId)` must detect when the returned instance is the pre-created target from an `@Extends` inheritance chain and **skip the custom ID override** in that case

## Capabilities

### New Capabilities
None - this is a bug fix for existing functionality.

### Modified Capabilities
- `extends-execute-parent-isolation`: Add requirement that `createTarget(Class, String)` must preserve the pre-created target's XMI ID during `@Extends` chain execution

## Impact

- **Affected code**: `TransformationContext.createTarget(Class<T>, String customId)` method (lines 1343-1353)
- **Behavior change**: During `@Extends` inheritance, parent rules calling `createTarget(Class, source, parentRuleName)` will no longer overwrite the child's XMI ID on the shared pre-created target. The pre-created target retains the ID set by `executeWithInheritance()`.
- **Risk**: Low — analysis of all `@Extends` usages across judo-tatami, judo-tatami-client, and judo-zeta confirms no existing parent rule relies on overwriting the child's ID. Abstract parents either (a) don't call `createTarget()` at all, or (b) look up cached targets.
- **Dependencies**: None — uses existing `TransformationContext` state (`inInheritanceExecution`, `preCreatedTarget`)
