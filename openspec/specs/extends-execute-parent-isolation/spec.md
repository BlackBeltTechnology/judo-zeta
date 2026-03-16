# extends-execute-parent-isolation Specification

## Purpose
Governs the isolation guarantee for `executeParentRule` calls made inside an active `@Extends` inheritance chain. Ensures that calling `executeParentRule(name, source, null)` for a different source element does not inherit the outer chain's `preCreatedTarget`.

## Requirements

### Requirement: executeParentRule with null target clears active inheritance state
When `executeParentRule(name, source, null)` is called from within an active `@Extends` inheritance chain, the framework SHALL clear the active `preCreatedTarget` and `inInheritanceExecution` flag before executing the named rule, regardless of whether the named rule is lazy or non-lazy.

#### Scenario: Non-lazy parent rule invoked inside @Extends chain gets fresh target
- **WHEN** a rule decorated with `@Extends` is executing with `inInheritanceExecution=true` and a `preCreatedTarget` set to an object of type ActorTarget (a subtype of BaseTarget)
- **AND** the rule calls `executeParentRule("CreateBaseRule", differentSource, null)`
- **AND** `CreateBaseRule` is a non-lazy rule whose target type is BaseTarget
- **THEN** `createTarget(BaseTarget.class)` inside `CreateBaseRule` SHALL create a new BaseTarget instance
- **AND** the returned target SHALL NOT be the pre-created ActorTarget from the outer chain

#### Scenario: Lazy parent rule invoked inside @Extends chain gets fresh target
- **WHEN** a rule decorated with `@Extends` is executing with `inInheritanceExecution=true` and a `preCreatedTarget` set
- **AND** the rule calls `executeParentRule("CreateLazyRule", differentSource, null)`
- **AND** `CreateLazyRule` is a lazy rule
- **THEN** `createTarget()` inside `CreateLazyRule` SHALL create a new target instance
- **AND** the pre-created target from the outer chain SHALL NOT leak into the inner execution

#### Scenario: Normal @Extends chain is unaffected
- **WHEN** the framework executes an `@Extends` chain automatically (framework-driven, not a manual `executeParentRule` call)
- **THEN** `preCreatedTarget` SHALL be shared correctly across the inheritance stack
- **AND** all rules in the chain SHALL receive the same pre-created target instance
- **BECAUSE** the fix only affects the `target==null` path within `executeParentRule`, not framework-managed `@Extends` invocations
