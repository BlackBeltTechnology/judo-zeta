# extends-execute-parent-isolation Specification (Delta)

## New Requirement: Pre-created target return increments ruleInstanceCounters during @Extends chain

During `@Extends` inheritance chain execution, when `createTargetInPackage()` returns the pre-created target early (because it is type-compatible), the framework SHALL increment the `ruleInstanceCounters` as if a normal `createTarget()` call had been made. This ensures subsequent `createTarget()` calls in the same parent rule generate unique IDs.

### Scenario: Parent rule creates additional element after getting pre-created target
- **GIVEN** a child rule "ChildRule" with `@Extends("ParentRule")`
- **AND** the framework has pre-created a target of type TargetType
- **WHEN** "ParentRule" calls `ctx.createTarget(TargetType.class, source, "ParentRule")` (gets pre-created target, overwrites ID)
- **AND** "ParentRule" then calls `ctx.createTarget(OtherType.class)` (creates new element)
- **THEN** the second element's auto-generated XMI ID SHALL NOT equal the pre-created target's overwritten ID
- **AND** the second element SHALL get a suffixed ID like `(source)/ParentRule/OtherType`
- **BECAUSE** the pre-created target return incremented the counter, so the second call sees `totalCallsForRule=1` and appends a type suffix

### Scenario: Parent rule's createTarget(Class, source, "ParentRule") correctly overwrites child ID
- **GIVEN** a child rule "ChildRule" with `@Extends("ParentRule")`
- **AND** the framework has pre-created a target with XMI ID `prefix/(source)/ChildRule`
- **WHEN** "ParentRule" executes and calls `ctx.createTarget(TargetType.class, source, "ParentRule")`
- **THEN** the returned instance SHALL be the pre-created target
- **AND** its XMI ID SHALL be changed to `prefix/(source)/ParentRule`
- **BECAUSE** in ETL semantics, the parent rule's explicit custom ID takes precedence

### Scenario: Non-inheritance createTarget is unaffected
- **GIVEN** a rule that does NOT use `@Extends`
- **WHEN** the rule calls `ctx.createTarget(TargetType.class, "custom-id")`
- **THEN** a new instance SHALL be created with XMI ID "custom-id"
- **BECAUSE** `inInheritanceExecution` is false, so the counter-increment logic does not activate
