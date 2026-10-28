## MODIFIED Requirements

### Requirement: Discriminated Lazy Rule Execution Skips Original Resource Addition

When a `@Lazy` rule is executed via `equivalentDiscriminated()`, the framework SHALL NOT add the original target element to `Resource.contents`. Only the discriminated clone SHALL be added to the resource.

**Exception**: When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, the `inDiscriminatedExecution` flag SHALL NOT be set during rule execution. The original SHALL be added to the resource normally because the first caller receives the original directly (no clone is created for the first call).

#### Scenario: Lazy rule via equivalentDiscriminated does not add original

**Given** a @Lazy rule "CreateActionDefinition" that calls `ctx.addToResource(target)`
**And** the rule is called via `equivalentDiscriminated(source, EDataType.class, "CreateActionDefinition", "button/form1")`
**And** the strategy is `CLONE_PRISTINE`
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
**And** the strategy is `CLONE_PRISTINE`
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

#### Scenario: CLONE_CURRENT_STATE allows original to be added to resource

**Given** a @Lazy rule "CreateActionDefinition" that calls `ctx.addToResource(target)`
**And** the strategy is `CLONE_CURRENT_STATE`
**And** the rule is called via `equivalentDiscriminated(source, EDataType.class, "CreateActionDefinition", "discA")`
**When** the rule executes
**Then** `inDiscriminatedExecution` flag SHALL NOT be set to true
**And** the original target SHALL be added to Resource.contents via `addToResource()`
**And** the first caller SHALL receive the original with discriminated XMI ID
**Because** CLONE_CURRENT_STATE returns the original directly to the first caller — suppressing addToResource would orphan it

### Requirement: addToResource Behavior During Discriminated Execution

The `TransformationContext.addToResource(element)` method SHALL check whether the current execution context is a discriminated lazy rule invocation. If the context is discriminated, the method SHALL skip adding the element to `Resource.contents` and return silently.

**Exception**: When `EquivalentDiscriminatedStrategy` is `CLONE_CURRENT_STATE`, the `inDiscriminatedExecution` flag is NOT set, so `addToResource()` SHALL work normally regardless of whether the call originated from `equivalentDiscriminated()`.

#### Scenario: addToResource is no-op during discriminated execution

**Given** the current thread is executing a lazy rule via `equivalentDiscriminated()`
**And** the strategy is `CLONE_PRISTINE`
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

#### Scenario: addToResource works normally with CLONE_CURRENT_STATE strategy

**Given** the current thread is executing a lazy rule via `equivalentDiscriminated()`
**And** the strategy is `CLONE_CURRENT_STATE`
**When** the rule calls `ctx.addToResource(element)`
**Then** the element SHALL be added to the target resource
**Because** `inDiscriminatedExecution` is not set when CLONE_CURRENT_STATE is active
