## Context

`TransformationContext.executeParentRule()` is used in two distinct situations:

1. **`@Extends` inheritance chain** — the framework calls it automatically to build the full inheritance stack. At this point `inInheritanceExecution=true` and `preCreatedTarget` is set to the object being constructed by the outer (child) rule.
2. **Manual `executeParentRule(name, source, null)` call** — a rule explicitly requests that another rule be executed for a (potentially different) source element, expecting a freshly created target returned.

When case 2 is invoked while case 1 is still active (e.g., `CreateMappedActorType` calls `executeParentRule("CreateMappedTransferObjectType", principal, null)` where `principal` is a different source element), the `preCreatedTarget` from case 1 leaks into the child invocation.

`createTarget(MappedTransferObjectType.class)` checks `targetType.isInstance(preCreatedTarget)`. Because `MappedActorType IS-A MappedTransferObjectType`, the check passes and the method returns the outer rule's partially-constructed `MappedActorType` instead of creating a new `MappedTransferObjectType`. This corrupts the model silently.

The existing guard at `TransformationContext.executeParentRule()`:

```java
} else if (wasInInheritance && previousPreCreated != null && parentRule.isLazy()) {
    clearPreCreatedTarget();
    setInInheritanceExecution(false);
}
```

only protects lazy parent rules. Non-lazy rules (the common case for `CreateMappedTransferObjectType`-style rules) are left unprotected.

## Goals / Non-Goals

**Goals:**
- Ensure `executeParentRule(name, source, null)` always executes in a clean inheritance state, regardless of whether the calling context is inside an `@Extends` chain.
- Provide a reproducible failing test before the fix (TDD).
- Minimal, surgical change — one condition change in one method.

**Non-Goals:**
- Changing `@Extends` inheritance semantics in any other way.
- Altering how `preCreatedTarget` is set or cleared in the normal inheritance chain setup.
- Addressing any other path through `executeParentRule` (e.g., when `target != null` is passed, the pre-created target is intentionally passed in and must NOT be cleared).

## Decisions

### Decision: Remove `&& parentRule.isLazy()` from the clear-inheritance branch

**Rationale:** The condition `wasInInheritance && previousPreCreated != null` already correctly identifies: "we were in an inheritance chain but we are now executing a parent rule that was called with `target=null` (i.e., not passed a pre-created target)." Adding `&& parentRule.isLazy()` is an over-constraint — whether the rule is lazy or not is irrelevant to whether the inheritance state should be reset.

**Alternative considered:** Clear `preCreatedTarget` unconditionally at the start of `executeParentRule`. Rejected because when `target != null` is passed, the pre-created target IS the target parameter itself and should remain set.

**Alternative considered:** Introduce a separate flag `inManualParentRuleCall` to distinguish the two contexts. Rejected — unnecessary complexity for a one-condition fix.

### Decision: Clear condition only when `target == null`

The existing branch is already inside the `target == null` code path in `executeParentRule`, so no additional `target == null` guard is needed. The fix is solely removing `&& parentRule.isLazy()`.

## Risks / Trade-offs

- **Risk**: Some consumer currently relies on non-lazy `executeParentRule` calls inheriting `preCreatedTarget` from an active inheritance chain (perhaps intentionally). → **Mitigation**: This would be a correctness accident, not intentional design. The fix aligns with the documented contract: `executeParentRule(name, source, null)` should create a fresh target.
- **Trade-off**: The fix is invisible to most callers — only affects the narrow case where `executeParentRule(name, differentSource, null)` is called from within an active `@Extends` chain where the pre-created type is a supertype of the requested type.

## Migration Plan

1. Write failing test `ExtendsPrecreatedTargetLeakTest` (red).
2. Apply one-line fix in `TransformationContext.executeParentRule()`.
3. Verify test turns green; run full test suite.
4. No migration steps required — purely additive fix.
