# clone-current-state Specification Delta

This delta spec modifies the existing `clone-current-state` specification to remove the sequential-only constraint and clarify parallel mode behavior.

## REMOVED Requirements

### Requirement: CLONE_CURRENT_STATE Requires Sequential Execution

**Reason**: This requirement is being removed as part of enabling parallel CLONE_CURRENT_STATE support. The version tracking mechanism with Phaser coordination now ensures deterministic results in parallel mode.

**Migration**: No migration needed. Existing code using `CLONE_CURRENT_STATE + sequential` continues to work identically. Code that previously threw `IllegalStateException` with `CLONE_CURRENT_STATE + parallel` will now work correctly.

## ADDED Requirements

### Requirement: CLONE_CURRENT_STATE Supports Parallel Execution

When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE` and parallel execution is enabled, the framework SHALL use version tracking with Phaser coordination to ensure deterministic mutation propagation.

#### Scenario: Parallel mode with CLONE_CURRENT_STATE succeeds

- **GIVEN** executor configured with `CLONE_CURRENT_STATE` strategy and `parallel(true)`
- **WHEN** `TransformationExecutor.build()` is called
- **THEN** the executor SHALL build successfully (no exception)
- **AND** `equivalentDiscriminated()` SHALL work correctly during transformation

#### Scenario: Parallel results match sequential semantics

- **GIVEN** a transformation with `CLONE_CURRENT_STATE` strategy
- **AND** multiple `equivalentDiscriminated()` calls for the same `(source, ruleName)` with different discriminators
- **WHEN** the transformation executes in parallel mode
- **THEN** mutation propagation SHALL match sequential mode behavior
- **AND** results SHALL be deterministic across multiple runs
- **AND** all mutations SHALL be visible to subsequent callers

## MODIFIED Requirements

### Requirement: CLONE_CURRENT_STATE First Caller Gets Original

When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, the **first** call to `equivalentDiscriminated()` for a given `(source, ruleName)` pair SHALL return the original object. **In parallel mode, the first caller receives a `PhaseAwareEObject` proxy that wraps the original and tracks phase completion.**

#### Scenario: First caller in parallel mode receives proxy

- **GIVEN** strategy is `CLONE_CURRENT_STATE` and `parallel(true)`
- **AND** this is the first call for `(source, ruleName)`
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discA")` is called
- **THEN** the returned object SHALL be a `PhaseAwareEObject` proxy
- **AND** the proxy SHALL delegate all operations to the underlying original
- **AND** the proxy's `toString()` SHALL indicate it is a proxy
- **AND** mutations to the proxy SHALL affect the underlying original

#### Scenario: First caller's mutations are visible on the original (unchanged)

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call returns original (or proxy wrapping original)
- **WHEN** the caller mutates the Action's name to "base::rel1"
- **THEN** the original object's name SHALL be "base::rel1"
- **AND** in parallel mode, subsequent callers SHALL see this mutation after phase completion

### Requirement: CLONE_CURRENT_STATE Subsequent Callers Clone Current State

When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, subsequent calls to `equivalentDiscriminated()` for the same `(source, ruleName)` pair with a **different** discriminator SHALL return a clone of the original's **current (mutated) state**. **In parallel mode, subsequent callers wait for previous phase to complete before cloning.**

#### Scenario: Subsequent caller waits for phase completion in parallel mode

- **GIVEN** strategy is `CLONE_CURRENT_STATE` and `parallel(true)`
- **AND** first call with "discA" is in progress
- **AND** the first caller has not yet completed their rule
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discB")` is called
- **THEN** the second caller SHALL block in `awaitAdvanceInterruptibly()`
- **AND** SHALL NOT clone until the first caller's rule completes
- **AND** once unblocked, the clone SHALL contain the first caller's mutations

#### Scenario: Second caller gets clone with first caller's mutations (unchanged)

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call with "discA" returned original, which was mutated to name "base::rel1"
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discB")` is called
- **THEN** the returned clone SHALL have name "base::rel1" (inheriting first caller's mutation)
- **AND** the returned clone SHALL be a different instance from the original
- **AND** the clone's XMI ID SHALL contain "discB"
