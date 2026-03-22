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

### Requirement: Pre-created target return increments ruleInstanceCounters during @Extends chain

During `@Extends` inheritance chain execution, when `createTargetInPackage()` returns the pre-created target early (because it is type-compatible), the framework SHALL increment the `ruleInstanceCounters` as if a normal `createTarget()` call had been made. This ensures subsequent `createTarget()` calls in the same parent rule generate unique IDs.

#### Scenario: Parent rule creates additional element after getting pre-created target
- **GIVEN** a child rule "ChildRule" with `@Extends("ParentRule")`
- **AND** the framework has pre-created a target of type TargetType
- **WHEN** "ParentRule" calls `ctx.createTarget(TargetType.class, source, "ParentRule")` (gets pre-created target, overwrites ID)
- **AND** "ParentRule" then calls `ctx.createTarget(OtherType.class)` (creates new element)
- **THEN** the second element's auto-generated XMI ID SHALL NOT equal the pre-created target's overwritten ID
- **AND** the second element SHALL get a suffixed ID like `(source)/ParentRule/OtherType`
- **BECAUSE** the pre-created target return incremented the counter, so the second call sees `totalCallsForRule=1` and appends a type suffix

#### Scenario: Parent rule's createTarget(Class, source, "ParentRule") correctly overwrites child ID
- **GIVEN** a child rule "ChildRule" with `@Extends("ParentRule")`
- **AND** the framework has pre-created a target with XMI ID `prefix/(source)/ChildRule`
- **WHEN** "ParentRule" executes and calls `ctx.createTarget(TargetType.class, source, "ParentRule")`
- **THEN** the returned instance SHALL be the pre-created target
- **AND** its XMI ID SHALL be changed to `prefix/(source)/ParentRule`
- **BECAUSE** in ETL semantics, the parent rule's explicit custom ID takes precedence

#### Scenario: Non-inheritance createTarget is unaffected
- **GIVEN** a rule that does NOT use `@Extends`
- **WHEN** the rule calls `ctx.createTarget(TargetType.class, "custom-id")`
- **THEN** a new instance SHALL be created with XMI ID "custom-id"
- **BECAUSE** `inInheritanceExecution` is false, so the counter-increment logic does not activate
