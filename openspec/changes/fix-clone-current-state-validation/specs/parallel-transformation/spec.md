# parallel-transformation Specification Delta

This delta spec modifies the existing `parallel-transformation` specification to clarify validation behavior when combining parallel mode with incompatible discrimination strategies.

## MODIFIED Requirements

### Requirement: Configuration Validation for Incompatible Combinations

The executor SHALL validate configuration at build time and throw `IllegalStateException` for incompatible combinations. **Validation SHALL reject CLONE_CURRENT_STATE with parallel mode regardless of execution strategy.**

#### Scenario: CLONE_CURRENT_STATE + parallel (any strategy) rejected

- **GIVEN** executor configured with `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE`
- **AND** `parallel(true)` is configured
- **WHEN** `TransformationExecutor.build()` is called
- **THEN** `IllegalStateException` SHALL be thrown
- **AND** error message SHALL explain that parallel execution is incompatible with CLONE_CURRENT_STATE
