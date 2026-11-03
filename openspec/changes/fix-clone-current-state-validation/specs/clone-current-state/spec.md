# clone-current-state Specification Delta

This delta spec modifies the existing `clone-current-state` specification to clarify that CLONE_CURRENT_STATE is incompatible with ANY parallel mode.

## MODIFIED Requirements

### Requirement: CLONE_CURRENT_STATE Requires Sequential Execution

The `CLONE_CURRENT_STATE` discrimination strategy requires deterministic element processing order to correctly track state changes across `equivalentDiscriminated()` calls. **The executor SHALL NOT allow parallel execution with CLONE_CURRENT_STATE for ANY execution strategy (ELEMENT_BY_ELEMENT or RULE_BY_RULE).**

#### Scenario: CLONE_CURRENT_STATE + ELEMENT_BY_ELEMENT + parallel throws at build time

- **GIVEN** executor configured with `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE`
- **AND** `parallel(true)` is configured
- **AND** `ExecutionStrategy.ELEMENT_BY_ELEMENT` (default)
- **WHEN** `TransformationExecutor.build()` is called
- **THEN** `IllegalStateException` SHALL be thrown immediately
- **AND** error message SHALL explain that CLONE_CURRENT_STATE requires sequential execution

#### Scenario: CLONE_CURRENT_STATE + RULE_BY_RULE + parallel throws at build time

- **GIVEN** executor configured with `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE`
- **AND** `parallel(true)` is configured
- **AND** `ExecutionStrategy.RULE_BY_RULE`
- **WHEN** `TransformationExecutor.build()` is called
- **THEN** `IllegalStateException` SHALL be thrown immediately
- **AND** error message SHALL explain that CLONE_CURRENT_STATE requires deterministic element order within each rule

#### Scenario: CLONE_CURRENT_STATE + sequential (parallel=false) works

- **GIVEN** executor configured with `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE`
- **AND** `parallel(false)` (sequential execution)
- **WHEN** `TransformationExecutor.build()` is called
- **THEN** executor SHALL build successfully
- **AND** `equivalentDiscriminated()` SHALL work correctly with state tracking
