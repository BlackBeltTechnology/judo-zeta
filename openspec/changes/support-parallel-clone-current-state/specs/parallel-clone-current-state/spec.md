# parallel-clone-current-state Specification

This specification defines support for `CLONE_CURRENT_STATE` discrimination strategy in parallel transformation mode.

## Purpose

Enable ETL-compatible `equivalentDiscriminated()` semantics with mutation propagation when parallel execution is enabled. This allows large-scale transformations to benefit from parallel performance while maintaining correct behavioral semantics.

## Requirements

### Requirement: Version Tracking for Parallel CLONE_CURRENT_STATE

When `CLONE_CURRENT_STATE` strategy is used with parallel execution (deferred writes enabled), the framework SHALL track versions of original objects and coordinate mutation phases using `java.util.concurrent.Pher`.

#### Scenario: Version tracking ensures deterministic mutations

- **GIVEN** executor configured with `CLONE_CURRENT_STATE` strategy and `parallel(true)`
- **AND** RuleA calls `equivalentDiscriminated(source, Action.class, "RuleA", "rel1")`
- **AND** RuleA calls `equivalentDiscriminated(source, Action.class, "RuleA", "rel2")`
- **WHEN** both calls execute in parallel threads
- **THEN** the first call to complete SHALL receive the original object
- **AND** the second call SHALL wait for the first to complete before cloning
- **AND** the second call's clone SHALL include mutations from the first call
- **AND** results SHALL be deterministic across multiple runs

#### Scenario: Phase-aware proxy signals completion

- **GIVEN** a first caller receives a `PhaseAwareEObject` proxy wrapping the original
- **AND** the caller mutates the object during rule execution
- **WHEN** the rule completes
- **THEN** the framework SHALL call `signalPhaseComplete()` on the proxy
- **AND** the Phaser SHALL advance to the next phase
- **AND** waiting subsequent callers SHALL be unblocked

#### Scenario: Only one first caller wins the race

- **GIVEN** executor with `CLONE_CURRENT_STATE` and `parallel(true)`
- **AND** two threads simultaneously call `equivalentDiscriminated()` for the same `(source, ruleName)` with different discriminators
- **WHEN** both calls execute
- **THEN** exactly ONE thread SHALL win the first-call race (via atomic `putIfAbsent` in OriginalTracker)
- **AND** the winner SHALL receive the original object and set its XMI ID
- **AND** the loser SHALL become a subsequent caller
- **AND** the loser SHALL wait for the winner's phase to complete
- **AND** the loser SHALL clone from the original (with winner's mutations)
- **AND** the loser's clone SHALL have a different XMI ID than the original
- **AND** subsequent callers SHALL see all mutations from the first caller

#### Scenario: Subsequent caller waits for first caller phase

- **GIVEN** a first caller phase is in progress (version 0)
- **AND** a second thread calls `equivalentDiscriminated()` with a different discriminator
- **WHEN** the second call executes
- **THEN** the second thread SHALL register with the Phaser
- **AND** the second thread SHALL block in `awaitAdvanceInterruptibly()` until phase 0 completes
- **AND** once phase 0 completes, the second thread SHALL clone from the original
- **AND** the clone SHALL contain all mutations from the first caller
- **AND** the clone's XMI ID SHALL be different from the original's XMI ID

### Requirement: Timeout Prevents Indefinite Blocking

The framework SHALL use a timeout when waiting for phase completion to prevent deadlock if a phase never completes.

#### Scenario: Timeout with fallback to current state

- **GIVEN** a subsequent caller is waiting for previous phase to complete
- **AND** the previous phase does not complete within timeout period
- **WHEN** timeout occurs
- **THEN** the framework SHALL log a warning message
- **AND** the caller SHALL clone from the current state of the original
- **AND** execution SHALL continue (not throw exception)

#### Scenario: Normal execution completes before timeout

- **GIVEN** a subsequent caller is waiting for previous phase
- **AND** the previous phase completes within 1 second
- **WHEN** the phase completes
- **THEN** the subsequent caller SHALL be unblocked immediately
- **AND** no timeout warning SHALL be logged

### Requirement: VersionedOriginalRegistry Lifecycle

The `VersionedOriginalRegistry` SHALL track versioned originals for the duration of a transformation and be cleared on executor reset.

#### Scenario: Registry cleared on reset

- **GIVEN** a transformation with tracked versioned originals
- **WHEN** `TransformationExecutor.reset()` is called
- **THEN** `VersionedOriginalRegistry.clear()` SHALL be called
- **AND** all tracked entries SHALL be removed
- **AND** the next transformation SHALL start with an empty registry

#### Scenario: Registry persists within transformation

- **GIVEN** a transformation with multiple rules calling `equivalentDiscriminated()`
- **WHEN** multiple rules execute
- **THEN** `VersionedOriginalRegistry` SHALL retain entries across rule executions
- **AND** version tracking SHALL work across the entire transformation

### Requirement: Integration with Existing Infrastructure

The parallel CLONE_CURRENT_STATE implementation SHALL integrate with existing `OriginalTracker` and discrimination cache.

#### Scenario: OriginalTracker still used for first-call detection

- **GIVEN** `CLONE_CURRENT_STATE + parallel` mode
- **WHEN** `equivalentDiscriminated()` is called
- **THEN** `OriginalTracker.checkAndRegisterFirstCall()` SHALL still be used
- **AND** the result SHALL determine whether the caller is "first" or "subsequent"
- **AND** `VersionedOriginalRegistry` SHALL use the same `OriginalKey` for consistency

#### Scenario: Discriminated cache contains PhaseAwareObject for first callers

- **GIVEN** a first caller receives a `PhaseAwareEObject` proxy
- **WHEN** the caller's result is cached
- **THEN** `resolutionCache.addDiscriminatedMapping()` SHALL cache the proxy
- **AND** subsequent calls with the same discriminator SHALL return the cached proxy
- **AND** the proxy SHALL delegate to the underlying original for all operations

### Requirement: Sequential Mode Unchanged

When `CLONE_CURRENT_STATE` is used with sequential execution (parallel=false), the implementation SHALL NOT use version tracking or Phaser coordination.

#### Scenario: Sequential mode uses original implementation

- **GIVEN** executor with `CLONE_CURRENT_STATE` and `parallel(false)`
- **WHEN** `equivalentDiscriminated()` is called
- **THEN** the implementation SHALL use the existing sequential code path
- **AND** no `VersionedOriginalRegistry` calls SHALL occur
- **AND** no `PhaseAwareEObject` proxies SHALL be created
- **AND** behavior SHALL be identical to pre-change implementation

### Requirement: @Extends Inheritance Compatibility

When a child rule with `@Extends` executes, all `PhaseAwareEObject` instances created during the entire inheritance chain (including parent rules) SHALL be signaled only after the child rule completes.

#### Scenario: Parent rule creates PhaseAwareEObject, child still running

- **GIVEN** a child rule with `@Extends("ParentRule")`
- **AND** ParentRule calls `equivalentDiscriminated()` returning `PhaseAwareEObject #1`
- **AND** ParentRule completes but child's transform function is still running
- **WHEN** child's transform function completes
- **THEN** `signalAllPhaseAwareObjects()` SHALL signal BOTH `#1` (from parent) and any from child
- **AND** the phase SHALL not be signaled prematurely after parent completes

#### Scenario: Multiple @Extends levels

- **GIVEN** GrandChildRule `@Extends(ChildRule)` and ChildRule `@Extends(ParentRule)`
- **AND** each level creates `PhaseAwareEObject` instances
- **WHEN** GrandChildRule completes
- **THEN** all `PhaseAwareEObject` instances from all three levels SHALL be signaled
- **AND** signaling SHALL happen in the finally block of the outermost rule execution

### Requirement: RULE_BY_RULE Parallel Compatibility

When `RULE_BY_RULE` execution strategy is used with `parallel(true)` and `CLONE_CURRENT_STATE`, the phase coordination SHALL align with the per-rule barriers.

#### Scenario: Phase signaling aligns with rule barriers

- **GIVEN** executor with `RULE_BY_RULE + parallel(true) + CLONE_CURRENT_STATE`
- **AND** Rule A processes elements E1, E2, E3 in parallel chunks
- **WHEN** each chunk completes for Rule A
- **THEN** each chunk's finally block SHALL call `signalAllPhaseAwareObjects()`
- **AND** the barrier between Rule A and Rule B SHALL ensure all phases complete
- **AND** Rule B SHALL see all mutations from Rule A

#### Scenario: Cross-rule equivalentDiscriminated() sees previous rule's results

- **GIVEN** Rule A created `PhaseAwareEObject` for `(S1, "LazyB", "disc1")`
- **AND** Rule A's barrier completed (all phases signaled)
- **WHEN** Rule B calls `equivalentDiscriminated(S1, Action.class, "LazyB", "disc1")`
- **THEN** the cached result from Rule A SHALL be returned
- **AND** no new phase coordination SHALL be needed (already complete)

### Requirement: Recursive equivalentDiscriminated() Detection

The framework SHALL detect recursive `equivalentDiscriminated()` calls for the same `(source, ruleName, discriminator)` tuple to prevent infinite loops.

#### Scenario: Recursive call returns cached result

- **GIVEN** Rule A calls `equivalentDiscriminated(S1, "LazyB", "disc1")`
- **AND** LazyB's transform function calls `equivalentDiscriminated(S1, "LazyB", "disc1")` (same tuple)
- **WHEN** the recursive call is detected
- **THEN** the framework SHALL return the cached result if available
- **OR** return null if no cached result exists
- **AND** no new `PhaseAwareEObject` SHALL be created
- **AND** no phase coordination SHALL occur

### Requirement: Nested equivalentDiscriminated() Call Compatibility

When `equivalentDiscriminated()` triggers a lazy rule that itself calls `equivalentDiscriminated()`, the phase signaling SHALL work correctly with nested LIFO completion order.

#### Scenario: Nested lazy rule execution

- **GIVEN** RuleA calls `equivalentDiscriminated(S1, "LazyB", "disc1")`
- **AND** LazyB's transform function calls `equivalentDiscriminated(S2, "LazyC", "disc2")`
- **WHEN** LazyC completes
- **THEN** LazyC's `PhaseAwareEObject` SHALL be signaled and cleared
- **WHEN** LazyB completes
- **THEN** LazyB's `PhaseAwareEObject` SHALL be signaled and cleared
- **WHEN** RuleA completes
- **THEN** no remaining `PhaseAwareEObject` instances SHALL exist in ThreadLocal set

### Requirement: ThreadLocal Tracking for Multiple PhaseAwareObject Instances

The framework SHALL use a ThreadLocal Set to track all `PhaseAwareEObject` instances created during a single rule execution, enabling correct signaling for complex scenarios.

#### Scenario: Multiple discriminators in same rule

- **GIVEN** a rule calls `equivalentDiscriminated()` twice with different discriminators
- **AND** both calls return `PhaseAwareEObject` instances
- **WHEN** the rule completes
- **THEN** `signalAllPhaseAwareObjects()` SHALL iterate over all instances in the ThreadLocal set
- **AND** each `PhaseAwareEObject` SHALL have `signalPhaseComplete()` called
- **AND** the set SHALL be cleared after signaling

#### Scenario: ThreadLocal set uses identity comparison

- **GIVEN** multiple `PhaseAwareEObject` instances wrapping the same original
- **WHEN** stored in the ThreadLocal set
- **THEN** the set SHALL use identity-based comparison (not equals())
- **AND** duplicate entries for the same proxy instance SHALL be prevented
