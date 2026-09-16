# rule-execution Specification Delta

## ADDED Requirements

### Requirement: Discriminated Lazy Rule Execution Skips Original Resource Addition

When a `@Lazy` rule is executed via `equivalentDiscriminated()`, the framework SHALL NOT add the original target element to `Resource.contents`. Only the discriminated clone SHALL be added to the resource.

#### Scenario: Lazy rule via equivalentDiscriminated does not add original

**Given** a @Lazy rule "CreateActionDefinition" that calls `ctx.addToResource(target)`
**And** the rule is called via `equivalentDiscriminated(source, EDataType.class, "CreateActionDefinition", "button/form1")`
**When** the rule executes
**Then** the original target SHALL NOT be added to Resource.contents
**And** the discriminated clone SHALL be added to Resource.contents
**And** Resource.contents SHALL contain exactly 1 ActionDefinition (the clone)

#### Scenario: Direct lazy rule execution still adds to resource

**Given** a @Lazy rule "CreateActionDefinition" that calls `ctx.addToResource(target)`
**And** the rule is called via `equivalent(source, EDataType.class, "CreateActionDefinition")` without discriminator
**When** the rule executes
**Then** the target SHALL be added to Resource.contents
**Because** non-discriminated calls do not clone, so the original IS the final result

#### Scenario: Multiple discriminators create one clone each

**Given** a @Lazy rule "CreateActionDefinition"
**And** Button calls `equivalentDiscriminated(source, ..., "button/form1")`
**And** Action calls `equivalentDiscriminated(source, ..., "action/form1")`
**When** both calls complete
**Then** Resource.contents SHALL contain exactly 2 ActionDefinitions
**And** both SHALL be clones with different discriminated XMI IDs
**And** no orphan original SHALL exist at resource root

#### Scenario: Same discriminator returns cached clone

**Given** a @Lazy rule "CreateActionDefinition"
**And** Button calls `equivalentDiscriminated(source, ..., "shared/form1")`
**And** Action calls `equivalentDiscriminated(source, ..., "shared/form1")` with SAME discriminator
**When** both calls complete
**Then** Resource.contents SHALL contain exactly 1 ActionDefinition
**And** both Button and Action SHALL reference the same clone instance

### Requirement: No Orphan Original Elements from Discriminated Calls

When a lazy rule is called via `equivalentDiscriminated()`, the **original** target element (created by the rule's `createTarget()`) SHALL NOT remain at `Resource.contents` root. Only the discriminated clone(s) SHALL be added to the resource.

#### Scenario: Original template is not added during discriminated execution

**Given** a @Lazy rule that calls `ctx.addToResource(target)` and `ctx.createTarget()`
**When** the rule is executed via `equivalentDiscriminated()`
**Then** the original target (created by `createTarget()`) SHALL NOT appear at Resource.contents root
**And** only the discriminated clone SHALL appear at Resource.contents root
**Because** the original serves as a template for cloning and should not persist

### Requirement: Different Discriminators Create Separate Instances (Framework Behavior)

When multiple callers invoke `equivalentDiscriminated()` on the same source and rule with **different discriminators**, the framework SHALL create separate clone instances for each unique discriminator. This is the EXPECTED framework behavior.

#### Scenario: Different discriminators create distinct clones

**Given** a @Lazy rule "ActionDefinitionRule"
**And** Button calls `equivalentDiscriminated(source, ..., "button/form1")`
**And** Action calls `equivalentDiscriminated(source, ..., "action/form1")`
**When** both calls complete
**Then** Resource.contents SHALL contain exactly 2 ActionDefinitions (Clone A and Clone B)
**And** Clone A and Clone B SHALL be different instances
**And** Clone A SHALL have XMI ID containing "button/form1"
**And** Clone B SHALL have XMI ID containing "action/form1"

#### Scenario: Orphan clone when caller uses only non-containment reference

**Given** Clone B is created for Action's discriminator "action/form1"
**And** Action adds Clone B to a non-containment reference only
**When** transformation completes
**Then** Clone B SHALL remain at Resource.contents root with eContainer() == null
**Because** non-containment references do not set the container
**Note** This orphan is caused by discriminator mismatch in transformation code, not a framework bug

### Requirement: Discriminator Mismatch Creates Orphan Clones (Known Limitation)

When transformation code uses **different discriminators** for the same logical element from different contexts (e.g., Button context vs Action context), the framework SHALL create orphan clones. This is a **transformation code design issue**, not a framework bug. Transformation code MUST use consistent discriminators to avoid orphans.

#### Scenario: Real-world discriminator mismatch example

**Given** TransferObjectTableTransformations calls helper with discriminator "tot/xyz/tableOps"
**And** AccessTableTransformations calls same helper with discriminator "access/xyz/tableOps"
**And** both are transforming the same source OperationForm
**When** transformation completes
**Then** 2 ActionDefinition clones are created
**And** 1 clone is contained by Button (no orphan)
**And** 1 clone is only referenced by Action (orphan at root)
**Resolution** Transformation code should use the SAME discriminator for the same logical element

### Requirement: addToResource Behavior During Discriminated Execution

The `TransformationContext.addToResource(element)` method SHALL check whether the current execution context is a discriminated lazy rule invocation. If the context is discriminated, the method SHALL skip adding the element to `Resource.contents` and return silently.

#### Scenario: addToResource is no-op during discriminated execution

**Given** the current thread is executing a lazy rule via `equivalentDiscriminated()`
**When** the rule calls `ctx.addToResource(element)`
**Then** the element SHALL NOT be added to any resource
**And** the method SHALL return without error
**And** a debug log SHALL indicate the skip reason

#### Scenario: addToResource works normally outside discriminated execution

**Given** the current thread is executing a rule via `equivalent()` (no discriminator)
**Or** the current thread is executing a @Greedy rule
**When** the rule calls `ctx.addToResource(element)`
**Then** the element SHALL be added to the target resource
**And** normal addToResource behavior applies
