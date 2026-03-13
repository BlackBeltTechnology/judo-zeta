## ADDED Requirements

### Requirement: EquivalentDiscriminatedStrategy Configuration

The `TransformationContext` SHALL support a configurable `EquivalentDiscriminatedStrategy` that controls how `equivalentDiscriminated()` creates clones. The default strategy SHALL be `CLONE_PRISTINE` (existing behavior). An alternative `CLONE_CURRENT_STATE` strategy SHALL replicate ETL's mutation-propagation semantics.

#### Scenario: Default strategy is CLONE_PRISTINE

- **WHEN** a `TransformationContext` is created with no explicit strategy
- **THEN** `getEquivalentDiscriminatedStrategy()` SHALL return `CLONE_PRISTINE`
- **AND** `equivalentDiscriminated()` behavior SHALL be unchanged from existing behavior

#### Scenario: Strategy can be set via setter

- **WHEN** `ctx.setEquivalentDiscriminatedStrategy(CLONE_CURRENT_STATE)` is called
- **THEN** `ctx.getEquivalentDiscriminatedStrategy()` SHALL return `CLONE_CURRENT_STATE`
- **AND** subsequent `equivalentDiscriminated()` calls SHALL use clone-current-state semantics

### Requirement: CLONE_PRISTINE Strategy Behavior

When `EquivalentDiscriminatedStrategy` is `CLONE_PRISTINE`, `equivalentDiscriminated()` SHALL always clone from the pristine (unmodified) original. Every discriminated call for the same `(source, ruleName)` SHALL produce an independent clone with no mutation inheritance.

#### Scenario: All clones are pristine copies

- **GIVEN** strategy is `CLONE_PRISTINE`
- **AND** a @Lazy rule "ActionRule" produces an Action with name "base"
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discA")` returns cloneA
- **AND** cloneA's name is mutated to "base::rel1"
- **AND** `equivalentDiscriminated(source, Action.class, "ActionRule", "discB")` returns cloneB
- **THEN** cloneB's name SHALL be "base" (pristine, no mutation from cloneA)

### Requirement: CLONE_CURRENT_STATE First Caller Gets Original

When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, the **first** call to `equivalentDiscriminated()` for a given `(source, ruleName)` pair SHALL return the original object directly without cloning. The original's XMI ID SHALL be updated to the discriminated ID.

#### Scenario: First discriminated call returns original object

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** a @Lazy rule "ActionRule" produces original Action with name "base"
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discA")` is called for the first time
- **THEN** the returned object SHALL be the same instance as the original (identity equality)
- **AND** the returned object's XMI ID SHALL contain "discA"

#### Scenario: First caller's mutations are visible on the original

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call returns original Action with name "base"
- **WHEN** the caller mutates the Action's name to "base::rel1"
- **THEN** the original object's name SHALL be "base::rel1"

### Requirement: CLONE_CURRENT_STATE Subsequent Callers Clone Current State

When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, subsequent calls to `equivalentDiscriminated()` for the same `(source, ruleName)` pair with a **different** discriminator SHALL return a clone of the original's **current (mutated) state**.

#### Scenario: Second caller gets clone with first caller's mutations

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call with "discA" returned original, which was mutated to name "base::rel1"
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discB")` is called
- **THEN** the returned clone SHALL have name "base::rel1" (inheriting first caller's mutation)
- **AND** the returned clone SHALL be a different instance from the original
- **AND** the clone's XMI ID SHALL contain "discB"

#### Scenario: Third caller gets clone with accumulated mutations

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call with "discA" returned original, mutated to "base::rel1"
- **AND** second call with "discB" returned clone (name "base::rel1"), caller mutates original to "base::rel1::rel2"
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discC")` is called
- **THEN** the returned clone SHALL have name "base::rel1::rel2" (accumulated mutations)
- **AND** mutations propagate because only the first caller holds a reference to the original

#### Scenario: Same discriminator returns cached result

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** first call with "discA" returned the original
- **WHEN** `equivalentDiscriminated(source, Action.class, "ActionRule", "discA")` is called again
- **THEN** the same cached object SHALL be returned
- **AND** no new clone SHALL be created

### Requirement: CLONE_CURRENT_STATE Requires Sequential Execution

The `CLONE_CURRENT_STATE` strategy SHALL NOT be used with parallel execution (deferred writes enabled). The framework SHALL throw `IllegalStateException` if both are active simultaneously.

#### Scenario: Fail-fast when parallel mode is active

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** deferred writes are enabled (parallel execution mode)
- **WHEN** `equivalentDiscriminated()` is called with a non-null discriminator
- **THEN** an `IllegalStateException` SHALL be thrown
- **AND** the message SHALL indicate that CLONE_CURRENT_STATE requires sequential execution

#### Scenario: Sequential mode allows CLONE_CURRENT_STATE

- **GIVEN** strategy is `CLONE_CURRENT_STATE`
- **AND** deferred writes are NOT enabled (sequential execution mode)
- **WHEN** `equivalentDiscriminated()` is called
- **THEN** the call SHALL proceed normally without exception

### Requirement: OriginalTracker First-Call Registration

The framework SHALL track which `(source, ruleName)` pair received the first `equivalentDiscriminated()` call using an `OriginalTracker`. Registration SHALL be atomic to handle concurrent access safely.

#### Scenario: First call is registered atomically

- **GIVEN** an `OriginalTracker` with no prior registrations
- **WHEN** `checkAndRegisterFirstCall(source, "ActionRule", "discA")` is called
- **THEN** `isFirstCall()` SHALL return true
- **AND** `firstDiscriminator` SHALL be "discA"

#### Scenario: Subsequent call detects prior registration

- **GIVEN** `checkAndRegisterFirstCall(source, "ActionRule", "discA")` was already called
- **WHEN** `checkAndRegisterFirstCall(source, "ActionRule", "discB")` is called
- **THEN** `isFirstCall()` SHALL return false
- **AND** `firstDiscriminator` SHALL be "discA" (the originally registered discriminator)

#### Scenario: Different source or rule is independent

- **GIVEN** `checkAndRegisterFirstCall(sourceA, "ActionRule", "discA")` was called
- **WHEN** `checkAndRegisterFirstCall(sourceB, "ActionRule", "discX")` is called
- **THEN** `isFirstCall()` SHALL return true
- **BECAUSE** different source = different key

#### Scenario: Tracker is cleared on reset

- **GIVEN** an `OriginalTracker` with prior registrations
- **WHEN** `clear()` is called
- **THEN** all registrations SHALL be removed
- **AND** subsequent `checkAndRegisterFirstCall()` calls SHALL return `isFirstCall() = true`
