# rule-execution Specification

## Purpose
TBD - created by archiving change fix-execute-parent-rule-guard-regression. Update Purpose after archive.
## Requirements
### Requirement: executeParentRule MUST NOT evaluate guards

The `executeParentRule()` method in `TransformationContext` SHALL NOT evaluate the target rule's guard. Guard evaluation for `@Extends` inheritance chains is handled separately in `TransformRuleDescriptor.execute()`.

When called with `target=null` from within an active `@Extends` chain, `executeParentRule()` SHALL additionally clear the active inheritance state (`preCreatedTarget` and `inInheritanceExecution`) before invoking the rule, regardless of whether the rule is lazy or non-lazy.

#### Scenario: Direct rule invocation via executeParentRule bypasses guards

**Given** a transformation context with a lazy rule that has a guard
**When** `executeParentRule()` is called with a source element that would fail the guard
**Then** the rule is executed regardless of guard result
**And** the target element is returned (not null)

#### Scenario: Extends inheritance still evaluates parent guards

**Given** a child rule with `@Extends("ParentRule")` annotation
**And** ParentRule has a guard that rejects certain elements
**When** the child rule is executed on an element rejected by ParentRule's guard
**Then** the child rule returns null (execution aborted)
**Because** guard evaluation happens in `TransformRuleDescriptor.execute()`, not `executeParentRule()`

#### Scenario: executeParentRule with null target inside active @Extends chain clears inheritance state

**Given** a rule is executing inside an `@Extends` chain with `inInheritanceExecution=true`
**And** `preCreatedTarget` is set to an object of type ActorTarget (a subtype of BaseTarget)
**When** `executeParentRule("CreateBaseRule", differentSource, null)` is called
**And** `CreateBaseRule` is a non-lazy rule
**Then** the inheritance state SHALL be cleared before `CreateBaseRule` executes
**And** `createTarget(BaseTarget.class)` inside `CreateBaseRule` SHALL return a fresh BaseTarget
**And** the outer `@Extends` chain's `preCreatedTarget` SHALL be restored after `CreateBaseRule` returns

### Requirement: Automatic Source Type Inference from Generic Parameters

The `TransformationRegistry` SHALL automatically infer the source type from the first generic parameter of `TransformFunction<S, T>` when no explicit source type is specified via `@Transform` annotation or `sourceTypes` attribute.

This enables type-based rule filtering without requiring annotation changes to existing rules.

#### Scenario: Source type inferred from TransformFunction generic parameter

- **GIVEN** a transformation rule method returning `TransformFunction<EntityType, EClass>`
- **AND** no `@Transform` annotation is present
- **AND** no `sourceTypes` attribute is specified
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `EntityType`
- **AND** the rule SHALL only be evaluated for elements assignable to `EntityType`

#### Scenario: Explicit @Transform annotation takes priority over inference

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** the method has `@Transform(type = SpecificType.class)`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `SpecificType` (from annotation)
- **AND** the generic parameter `EObject` SHALL be ignored

#### Scenario: Explicit sourceTypes attribute takes priority over inference

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** the annotation specifies `@TransformRule(sourceTypes = {SpecificType.class})`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `SpecificType` (from attribute)
- **AND** the generic parameter `EObject` SHALL be ignored

#### Scenario: Raw EObject generic parameter falls back to default

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** no explicit source type is specified
- **AND** the `@TransformationContext` has `source = DefaultSource.class`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `DefaultSource` (from class-level default)
- **BECAUSE** inferring `EObject` provides no filtering benefit

### Requirement: Greedy Rules with Inferred Supertype Match All Subtypes

When a `@Greedy` rule's source type is inferred as a supertype, the type-based filtering SHALL include all elements of that type and its subtypes, matching the behavior of the `appliesTo()` method.

#### Scenario: Greedy rule with inferred supertype matches subtype elements

- **GIVEN** a transformation rule returning `TransformFunction<NamedElement, EClass>`
- **AND** the rule is annotated with `@Greedy`
- **AND** `EntityType` is a subtype of `NamedElement`
- **WHEN** transformation processes an `EntityType` element
- **THEN** the rule SHALL be included in the applicable rules for that element
- **AND** the rule's `appliesTo()` method SHALL return `true`

#### Scenario: Non-greedy rule with inferred type matches only exact type

- **GIVEN** a transformation rule returning `TransformFunction<EntityType, EClass>`
- **AND** the rule is NOT annotated with `@Greedy`
- **WHEN** transformation processes an `EntityTypeSubclass` element (subtype)
- **THEN** the rule SHALL NOT match the element
- **BECAUSE** non-greedy rules use type-of semantics (exact match only)

### Requirement: Behavioral Equivalence with appliesTo()

The type-based rule filtering with inferred source types MUST produce identical results to the runtime `appliesTo()` check. No behavioral changes are permitted.

#### Scenario: Type filtering matches appliesTo for all element types

- **GIVEN** a set of registered rules with inferred source types
- **AND** a collection of elements with various types
- **FOR** each rule
- **THEN** the set of elements passing type filtering SHALL equal the set of elements where `appliesTo()` returns `true`
- **AND** no element that would pass `appliesTo()` is excluded by type filtering
- **AND** no element that would fail `appliesTo()` is included by type filtering

### Requirement: XMI ID Index Cleanup on ID Change

When an element's XMI ID is changed via `setElementId()`, the old ID MUST be removed from the XMI ID index to prevent stale entries from causing incorrect lookups.

#### Scenario: Stale index entry removed when ID changes

**Given** an element with auto-generated XMI ID "old-id"
**And** `pendingXmiIdIndex["old-id"]` points to the element
**When** `setElementId(element, "new-id")` is called
**Then** `pendingXmiIdIndex["old-id"]` is removed
**And** `pendingXmiIdIndex["new-id"]` points to the element
**And** `findByXmiId("old-id", ...)` returns null
**And** `findByXmiId("new-id", ...)` returns the element

#### Scenario: Same ID does not cause unnecessary removal

**Given** an element with XMI ID "same-id"
**When** `setElementId(element, "same-id")` is called (same ID)
**Then** `pendingXmiIdIndex["same-id"]` still points to the element
**Because** no change occurred, no cleanup needed

### Requirement: XMI ID Context Isolation in Nested Rule Execution

When a rule is triggered via `ctx.equivalent()` from within another rule's execution, the XMI ID generation MUST use the **triggered rule's context**, not the calling rule's context.

#### Scenario: Nested lazy rule uses its own context for XMI ID

**Given** a parent rule "ParentRule" creating targets of type ParentTarget
**And** a child rule "ChildRule" creating targets of type ChildTarget
**And** ParentRule's transform function calls `ctx.equivalent(childSource, "ChildRule")`
**When** ChildRule executes and calls `ctx.createTarget()`
**Then** the generated XMI ID contains "ChildRule" as the rule name
**And** the generated XMI ID uses `childSource` as the source reference
**And** the XMI ID does NOT contain "ParentRule"

#### Scenario: @Greedy rule triggered from parent uses its own context

**Given** a parent rule "RelationFeatureView" creating PageDefinition targets
**And** a @Lazy @Greedy child rule "ActionRule" creating Action targets
**And** RelationFeatureView calls `ctx.equivalent(actionSource, "ActionRule")`
**When** ActionRule executes and creates an Action target
**Then** the Action's XMI ID is `(actionSource)/ActionRule`
**And** the Action's XMI ID does NOT reference "RelationFeatureView"
**Because** the child rule's context must be used for its targets

### Requirement: currentExecutingRule Must Be Set Before createTarget

The `currentExecutingRule` ThreadLocal MUST be set to the correct rule descriptor BEFORE any call to `createTarget()` can generate an XMI ID.

#### Scenario: Rule context is set before transform function executes

**Given** a rule "TestRule" is about to execute
**When** `TransformRuleDescriptor.execute()` is called
**Then** `context.setCurrentExecutingRule(this)` is called BEFORE `getFunction().transform()`
**And** any `createTarget()` call within the transform function uses "TestRule" for XMI ID

#### Scenario: Context is restored after nested rule completes

**Given** ParentRule is executing with `currentExecutingRule = ParentRule`
**And** ParentRule triggers ChildRule via `ctx.equivalent()`
**When** ChildRule completes execution
**Then** `currentExecutingRule` is restored to ParentRule
**And** subsequent `createTarget()` calls in ParentRule use "ParentRule" for XMI ID

### Requirement: Circular Dependency Handling for Discriminated Calls

The transformation framework SHALL support circular dependencies when `equivalentDiscriminated()` is called with different discriminators for the same source and rule. When Rule A (discriminator d1) calls Rule B, and Rule B calls back to Rule A (discriminator d2), the framework SHALL clone the in-progress target from d1 instead of returning null.

#### Scenario: Circular discriminated call uses in-progress target

- **GIVEN** Rule A is executing with source S and discriminator "d1"
- **AND** Rule A calls Rule B during its execution
- **WHEN** Rule B calls `equivalentDiscriminated(S, targetType, "A", "d2")`
- **THEN** the framework SHALL return a cloned copy of Rule A's in-progress target
- **AND** the clone SHALL have a discriminated XMI ID based on "d2"

#### Scenario: Non-discriminated circular call returns null

- **GIVEN** Rule A is executing with source S
- **AND** Rule A calls Rule B during its execution
- **WHEN** Rule B calls `equivalent(S, targetType, "A")` without discriminator
- **THEN** the framework SHALL return null to break the cycle

### Requirement: Rule Ordinal Assignment

Each transformation rule SHALL be assigned a unique integer ordinal at registration time. The ordinal SHALL be used for O(1) array-indexed cache lookups instead of String-based map lookups.

#### Scenario: Ordinal assigned at registration

- **GIVEN** a TransformationRegistry with no registered rules
- **WHEN** a rule "RuleA" is registered
- **THEN** RuleA SHALL be assigned ordinal 0
- **WHEN** a rule "RuleB" is registered
- **THEN** RuleB SHALL be assigned ordinal 1

#### Scenario: Ordinal used for cache lookup

- **GIVEN** a transformation with 100 registered rules
- **WHEN** cache lookup is performed for a source and rule
- **THEN** lookup SHALL use array indexing with rule ordinal
- **AND** lookup time SHALL be O(1) regardless of rule count

### Requirement: Guard Evaluation Caching

The transformation framework SHALL cache guard evaluation results to avoid redundant evaluations. When a guard has been evaluated for a source-rule combination, subsequent lookups SHALL use the cached result.

#### Scenario: Guard result cached on first evaluation

- **GIVEN** a source S and rule R with a guard predicate
- **WHEN** guard is evaluated for S and R
- **THEN** the result SHALL be cached
- **WHEN** cache lookup is performed for S and R again
- **THEN** the cached guard result SHALL be used
- **AND** the guard predicate SHALL NOT be re-evaluated

#### Scenario: Rejected sources skip guard evaluation

- **GIVEN** a source S that was rejected by rule R's guard
- **WHEN** `equivalent(S, targetType, "R")` is called
- **THEN** the rejection cache SHALL be checked first
- **AND** the method SHALL return null without evaluating the guard

### Requirement: Guard Purity Contract

Guard predicates MUST be pure functions with no observable side effects. The framework MAY cache guard results and skip re-evaluation, so guards that depend on mutable state or produce side effects will exhibit undefined behavior.

#### Scenario: Guard evaluated once per source-rule combination

- **GIVEN** a rule R with a guard that increments a counter (violating purity)
- **WHEN** `equivalent(S, targetType, "R")` is called twice for the same source S
- **THEN** the guard MAY be evaluated only once (cached)
- **AND** the counter MAY be incremented only once (not twice)

#### Scenario: Guard depending on transformation phase fails

- **GIVEN** a rule R with a guard that checks `ctx.getCurrentPhase() == GREEDY`
- **WHEN** guard is evaluated during greedy phase and cached
- **AND** `equivalent(S, targetType, "R")` is called during lazy phase
- **THEN** the cached result (from greedy phase) MAY be returned
- **AND** the guard SHALL NOT be re-evaluated with updated phase

### Requirement: Deterministic Transformation Output

The transformation framework SHALL produce byte-identical XMI output for the same input model across multiple executions **with the same `ExecutionStrategy`**. Rule ordinal assignment, cache iteration order, and all internal state SHALL be deterministic. Different execution strategies (`ELEMENT_BY_ELEMENT` vs `RULE_BY_RULE`) MAY produce different output because the rule-source execution order differs.

#### Scenario: Same input produces identical XMI output

- **GIVEN** an input model M
- **AND** the same `ExecutionStrategy` is used
- **WHEN** transformation is executed twice with identical configuration
- **THEN** the XMI output SHALL be byte-identical
- **AND** all XMI IDs SHALL be identical

#### Scenario: Registration order does not affect output

- **GIVEN** rules A, B, C registered in order A, B, C
- **AND** transformation produces output O1
- **WHEN** rules are registered in order C, A, B
- **AND** transformation is executed again with same strategy
- **THEN** the output SHALL be identical to O1

#### Scenario: Different strategies may produce different output

- **GIVEN** an input model M
- **WHEN** transformation is executed with `ELEMENT_BY_ELEMENT`
- **AND** transformation is executed again with `RULE_BY_RULE`
- **THEN** the outputs MAY differ
- **BECAUSE** the execution order of rule-source pairs differs between strategies
- **AND** rules that depend on cross-element outputs from earlier rules will produce different results

### Requirement: In-Progress Target Cleanup

In-progress targets used for circular dependency handling SHALL be cleared when rule execution completes, whether successful or exceptional. Stale in-progress targets SHALL NOT be visible to subsequent transformations.

#### Scenario: In-progress cleared on successful completion

- **GIVEN** Rule A marks target T as in-progress
- **WHEN** Rule A completes successfully
- **THEN** T SHALL be moved from in-progress to completed cache
- **AND** in-progress tracking for (source, A) SHALL be cleared

#### Scenario: In-progress cleared on exception

- **GIVEN** Rule A marks target T as in-progress
- **WHEN** Rule A throws an exception during execution
- **THEN** in-progress tracking for (source, A) SHALL be cleared
- **AND** T SHALL NOT remain in any cache

#### Scenario: Separate transformations have isolated state

- **GIVEN** Transformation T1 executes and marks targets as in-progress
- **WHEN** Transformation T2 starts on a different executor
- **THEN** T2 SHALL NOT see any in-progress state from T1
- **AND** T2 SHALL have fresh cache state

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

### Requirement: createTarget with Explicit ID Parameter

The `TransformationContext` SHALL provide a `createTarget(Class<T> targetType, String customId)` overload that creates a target element with the specified ID already set. This prevents race conditions where other rules might read an initial ID before a custom ID is set.

#### Scenario: Create target with custom ID prevents race condition

**Given** a rule that needs to create a target with a custom ID
**And** the rule will call other rules that may read the target's ID
**When** the rule calls `ctx.createTarget(EPackage.class, "custom/id")`
**Then** the target element SHALL have ID "custom/id" immediately
**And** any rule called via `equivalent()` SHALL see "custom/id" when calling `getElementId()`
**And** no race condition SHALL occur

#### Scenario: createTarget with null customId uses auto-generated ID

**Given** a rule calls `ctx.createTarget(EPackage.class, null)`
**When** the target is created
**Then** the target SHALL receive an auto-generated structured ID
**And** the behavior SHALL be identical to `createTarget(EPackage.class)`

### Requirement: ID Immutability After External Read

When a rule OTHER than the creating rule reads an element's ID via `getElementId()`, the ID SHALL become immutable. Any subsequent call to `setElementId()` for that element SHALL throw an `IllegalStateException`.

#### Scenario: setElementId after external read throws exception

**Given** Rule A creates a target via `createTarget()`
**And** Rule A calls `equivalent()` which triggers Rule B
**And** Rule B calls `getElementId(targetA)` to read the ID
**When** Rule A calls `setElementId(targetA, "custom/id")` after Rule B completes
**Then** an `IllegalStateException` SHALL be thrown
**And** the exception message SHALL indicate that the ID was already read by another rule
**And** the exception message SHALL suggest using `createTarget(type, customId)` instead

#### Scenario: setElementId before external read succeeds

**Given** Rule A creates a target via `createTarget()`
**And** Rule A calls `setElementId(target, "custom/id")` immediately
**When** Rule A later calls `equivalent()` which triggers Rule B
**And** Rule B calls `getElementId(target)`
**Then** Rule B SHALL receive "custom/id"
**And** no exception SHALL be thrown

#### Scenario: Same rule can change ID multiple times

**Given** Rule A creates a target via `createTarget()`
**And** Rule A has not called `equivalent()` or any other method that triggers another rule
**When** Rule A calls `setElementId(target, "id1")` then `setElementId(target, "id2")`
**Then** both calls SHALL succeed
**And** the final ID SHALL be "id2"
**Because** no external rule has read the ID yet

### Requirement: External Read Tracking

The `TransformationContext` SHALL track which elements have had their IDs read by rules other than the creating rule. This tracking is used to enforce ID immutability after external read.

#### Scenario: External read is tracked per element

**Given** Rule A creates targets T1 and T2
**And** Rule B reads the ID of T1 only
**When** Rule A calls `setElementId(T1, "new-id")`
**Then** an `IllegalStateException` SHALL be thrown for T1
**When** Rule A calls `setElementId(T2, "new-id")`
**Then** the call SHALL succeed for T2
**Because** T2's ID was not read externally

#### Scenario: External read tracking is thread-safe

**Given** multiple rules executing in parallel
**When** they read and modify element IDs concurrently
**Then** the external read tracking SHALL be thread-safe
**And** no race conditions SHALL occur in the tracking mechanism itself

### Requirement: Clear Error Messages for ID Race Condition

When `setElementId()` throws an `IllegalStateException` due to external read, the error message SHALL clearly explain the problem and provide guidance on how to fix it.

#### Scenario: Error message provides actionable guidance

**Given** `setElementId()` is called after external read
**When** the exception is thrown
**Then** the message SHALL include:
  - The element that was affected
  - Which rule read the ID
  - The suggestion to use `createTarget(type, customId)` instead
  - Example of the correct pattern

### Requirement: Rule Invocation Stack for Context-Aware Resolution

The `TransformationContext` SHALL maintain a thread-local stack of rule invocations (`RuleInvocation` records) that tracks the nested rule execution context. This enables context-aware discriminator resolution based on the call chain.

#### Scenario: Rule invocation pushed on execute

**Given** a rule "ParentRule" is executing
**When** `TransformRuleDescriptor.execute()` is called
**Then** a `RuleInvocation` SHALL be pushed onto the thread-local stack
**And** the invocation SHALL contain the rule name, source, and discriminator (if any)
**And** `ctx.getRuleInvocationChain()` SHALL include the invocation at index 0

#### Scenario: Rule invocation popped on completion

**Given** a rule "ChildRule" has pushed its invocation
**When** `ChildRule.execute()` completes (normally or exceptionally)
**Then** the invocation SHALL be popped from the stack in the finally block
**And** the stack SHALL return to its previous state

#### Scenario: Nested rules maintain proper LIFO order

**Given** ParentRule is executing (invocation at index 0)
**And** ParentRule calls ChildRule via `ctx.equivalent()`
**When** ChildRule is executing
**Then** `ctx.getRuleInvocationChain()` SHALL return [ChildRule, ParentRule]
**And** ChildRule SHALL be at index 0 (most recent)
**When** ChildRule completes
**Then** `ctx.getRuleInvocationChain()` SHALL return [ParentRule]

#### Scenario: Stack operations are thread-isolated

**Given** Thread A executes RuleA with source SA
**And** Thread B executes RuleB with source SB concurrently
**When** both threads access `ctx.getRuleInvocationChain()`
**Then** Thread A SHALL see only RuleA invocation
**And** Thread B SHALL see only RuleB invocation
**Because** the stack is thread-local

### Requirement: Context-Aware Discriminator Resolution via DiscriminatorResolver

The `TransformationContext` SHALL support a configurable `DiscriminatorResolver` that can infer discriminator values and cache modes based on the current rule invocation call chain.

#### Scenario: Resolver called when discriminator is null

**Given** a `DiscriminatorResolver` is configured via `ctx.setDiscriminatorResolver()`
**And** a rule calls `equivalentDiscriminated(source, targetType, ruleName, null)`
**When** the framework processes the call
**Then** `resolver.resolve(source, ruleName, callChain)` SHALL be called
**And** the returned `Resolution` SHALL determine the discriminator and cache mode

#### Scenario: Explicit discriminator bypasses resolver

**Given** a `DiscriminatorResolver` is configured
**When** a rule calls `equivalentDiscriminated(source, targetType, ruleName, "explicit-disc")`
**Then** the resolver SHALL NOT be called
**And** "explicit-disc" SHALL be used as the discriminator

#### Scenario: Resolver can switch cache modes based on call chain

**Given** a `DiscriminatorResolver` that returns different `Resolution` objects
**When** the resolver inspects the call chain and finds "ButtonGroupRule"
**Then** it MAY return `Resolution.discriminatorOnlyCache(disc)`
**When** the resolver does NOT find "ButtonGroupRule" in the chain
**Then** it MAY return `Resolution.sourceBasedCache(disc)`

### Requirement: Discriminator-Only Cache Mode (ETL-Compatible)

The `equivalentDiscriminated()` method SHALL support a discriminator-only cache mode where the cache key is `(ruleName, discriminator)` only, ignoring source object identity. This enables ETL-compatible semantics where different source objects sharing the same discriminator share the same target instance.

#### Scenario: Discriminator-only cache shares target across different sources

**Given** a resolver returns `Resolution.discriminatorOnlyCache("shared-disc")`
**And** sourceA and sourceB are different objects
**When** `equivalentDiscriminated(sourceA, Type.class, "Rule", null)` returns targetX
**And** `equivalentDiscriminated(sourceB, Type.class, "Rule", null)` is called
**Then** the same targetX SHALL be returned (shared)
**Because** both resolve to same (ruleName="Rule", discriminator="shared-disc") key

#### Scenario: Source-based cache creates separate targets per source

**Given** a resolver returns `Resolution.sourceBasedCache("same-disc")`
**And** sourceA and sourceB are different objects
**When** `equivalentDiscriminated(sourceA, Type.class, "Rule", null)` returns targetA
**And** `equivalentDiscriminated(sourceB, Type.class, "Rule", null)` returns targetB
**Then** targetA and targetB SHALL be different instances
**Because** source-based cache key is (source, ruleName, discriminator)

#### Scenario: Discriminator-only cache is thread-safe

**Given** multiple threads call `equivalentDiscriminated()` concurrently
**And** all resolve to same discriminator-only cache key
**When** the first thread creates the target
**Then** subsequent threads SHALL receive the same cached target
**And** no duplicate targets SHALL be created

### Requirement: Resolution Record Structure

The `DiscriminatorResolver.Resolution` record SHALL contain the discriminator value and a boolean flag indicating whether to use discriminator-only caching.

#### Scenario: Resolution.sourceBasedCache factory method

**When** `Resolution.sourceBasedCache("my-disc")` is called
**Then** a Resolution SHALL be returned with discriminator="my-disc"
**And** `useDiscriminatorOnlyCache()` SHALL return false

#### Scenario: Resolution.discriminatorOnlyCache factory method

**When** `Resolution.discriminatorOnlyCache("shared-disc")` is called
**Then** a Resolution SHALL be returned with discriminator="shared-disc"
**And** `useDiscriminatorOnlyCache()` SHALL return true

### Requirement: Backward Compatibility with Deprecated resolveDiscriminator

The `DiscriminatorResolver` interface SHALL maintain backward compatibility with implementations that only override the deprecated `resolveDiscriminator()` method.

#### Scenario: Default resolve() wraps deprecated method

**Given** an implementation that only overrides `resolveDiscriminator()` (via lambda)
**When** `resolve()` is called
**Then** the default implementation SHALL call `resolveDiscriminator()`
**And** wrap the result in `Resolution.sourceBasedCache(disc)` if non-null
**And** return null if `resolveDiscriminator()` returns null

### Requirement: No Orphan Elements in Discriminator-Only Cache Mode

When `equivalentDiscriminated()` is called with discriminator-only cache mode enabled, the framework SHALL NOT create orphan "original" elements. Instead of creating an original and cloning it, the framework SHALL use the original directly with the discriminated ID.

#### Scenario: Original element is used directly without cloning

**Given** a DiscriminatorResolver returning `Resolution.discriminatorOnlyCache(disc)`
**When** `equivalentDiscriminated(source, targetType, ruleName, null)` is called
**And** the rule executes and creates a target element
**Then** the target element SHALL receive the discriminated XMI ID directly
**And** NO clone SHALL be created
**And** the target element SHALL be returned
**And** no orphan "original" SHALL remain at Resource.contents

#### Scenario: addToResource works normally in discriminator-only mode

**Given** a DiscriminatorResolver returning `Resolution.discriminatorOnlyCache(disc)`
**When** `equivalentDiscriminated()` triggers a lazy rule
**Then** `inDiscriminatedExecution` flag SHALL NOT be set to true
**And** the rule's `addToResource()` calls SHALL work normally
**Because** we're using the original directly, not cloning

#### Scenario: Source-based cache still clones (unchanged behavior)

**Given** a DiscriminatorResolver returning `Resolution.sourceBasedCache(disc)`
**When** `equivalentDiscriminated(source, targetType, ruleName, null)` is called
**Then** the original element SHALL be created first
**And** a clone SHALL be created with the discriminated ID
**And** the clone SHALL be added to Resource.contents
**And** the original SHALL NOT be added (due to `inDiscriminatedExecution` flag)

#### Scenario: Multiple sources share element in discriminator-only mode

**Given** source1 and source2 are different objects
**And** both resolve to same discriminator via discriminator-only cache
**When** `equivalentDiscriminated(source1, ...)` returns element1
**And** `equivalentDiscriminated(source2, ...)` is called
**Then** element1 SHALL be returned (same instance)
**And** no second element SHALL be created
**And** no orphans SHALL exist

### Requirement: Public Element ID Resolution API

The `TransformationContext` SHALL expose a public `getElementId(EObject)` method that returns the element's XMI ID, checking pending (deferred) IDs before committed IDs. This enables transformation code to build discriminators using target element IDs reliably.

#### Scenario: Pending ID takes precedence over committed ID

**Given** an element with a committed XMI ID "committed-id" in the resource
**And** the element has a pending ID "pending-id" in `pendingXmiIds`
**When** `ctx.getElementId(element)` is called
**Then** the result SHALL be "pending-id"
**Because** pending IDs represent the current transformation state

#### Scenario: Committed ID returned when no pending ID exists

**Given** an element with a committed XMI ID "committed-id" in the resource
**And** the element has no pending ID in `pendingXmiIds`
**When** `ctx.getElementId(element)` is called
**Then** the result SHALL be "committed-id"

#### Scenario: UUID generated for elements without ID

**Given** an element without any XMI ID
**And** staging is enabled via `ctx.enableStaging()`
**When** `ctx.getElementId(element)` is called
**Then** a UUID SHALL be generated starting with underscore (e.g., "_abc123...")
**And** the UUID SHALL be cached in `pendingXmiIds` for consistency
**And** subsequent calls SHALL return the same UUID

### Requirement: ID Resolution Order

The `getElementId(EObject)` method SHALL resolve IDs in the following order of precedence:

1. **Pending IDs**: Check `pendingXmiIds` map first (highest priority)
2. **Resource URI Fragment**: Check `resource.getURIFragment(element)` excluding path-based fragments
3. **Structural Feature**: Check for "id" structural feature on the element's EClass
4. **Generated UUID**: Generate and cache a new UUID (lowest priority)

#### Scenario: ID resolution follows defined order

**Given** an element with ID sources at multiple levels
**When** `ctx.getElementId(element)` is called
**Then** the method SHALL return the ID from the highest-priority available source
**And** the order of priority SHALL be: pending > resource > feature > generated

### Requirement: Thread-Safe ID Resolution

The `getElementId(EObject)` method SHALL be thread-safe for concurrent access. Multiple threads calling `getElementId()` for the same element SHALL always receive the same result.

#### Scenario: Concurrent getElementId calls return consistent ID

**Given** multiple threads calling `ctx.getElementId(element)` concurrently
**And** the element has no existing ID
**When** all threads complete
**Then** all threads SHALL have received the same generated UUID
**And** no race conditions SHALL cause duplicate or inconsistent IDs

### Requirement: Discriminator Construction with Target Element IDs

Transformation code SHALL be able to use `ctx.getElementId(targetElement)` to build discriminators that include target element IDs, even when those IDs are deferred (not yet committed to XMI resource).

#### Scenario: Building discriminator with deferred target element ID

**Given** a target element created during transformation
**And** the element's ID is stored in `pendingXmiIds` (deferred)
**When** transformation code calls `ctx.getElementId(targetElement)`
**Then** the pending ID SHALL be returned (not null)
**And** the ID can be used to build a discriminator string
**And** `ctx.equivalentDiscriminated(source, type, rule, discriminator)` SHALL work correctly

#### Scenario: Standard XMIResource.getID fails for deferred elements

**Given** a target element with a deferred ID in `pendingXmiIds`
**When** `XMIResource.getID(element)` is called directly
**Then** the result SHALL be null
**Because** deferred IDs are not yet committed to the XMI resource
**Therefore** transformation code MUST use `ctx.getElementId()` instead

### Requirement: Metrics counters SHALL be consistent across execution strategies

TransformationMetrics counters (`ruleIterations`, `ruleExecutions`, `guardEvaluations`) SHALL be incremented for every rule iteration, execution, and guard evaluation regardless of execution strategy (ELEMENT_BY_ELEMENT or RULE_BY_RULE).

#### Scenario: RULE_BY_RULE increments ruleIterations
- **WHEN** a transformation runs with `ExecutionStrategy.RULE_BY_RULE`
- **AND** the executor iterates over source elements for a rule
- **THEN** `ruleIterations` SHALL be incremented for each source element checked

#### Scenario: RULE_BY_RULE increments ruleExecutions
- **WHEN** a rule executes successfully (non-null result) during a RULE_BY_RULE greedy pass
- **THEN** `ruleExecutions` SHALL be incremented

#### Scenario: ELEMENT_BY_ELEMENT and RULE_BY_RULE produce comparable counter values
- **WHEN** the same transformation runs with both strategies on identical input
- **THEN** `ruleExecutions` SHALL be equal
- **AND** `guardEvaluations` SHALL be equal

### Requirement: equivalent() cache hit and miss counts SHALL sum to total calls

The sum of `equivalentCacheHits` and `equivalentCacheMisses` SHALL NOT exceed `equivalentCalls`. Each call to `equivalent()` SHALL record exactly one of: cache hit OR cache miss (not both, and not multiple misses).

#### Scenario: No double-counting of cache misses in equivalent(source, ruleName)
- **WHEN** `equivalent(source, ruleName)` is called
- **AND** the result is not cached and not rejected
- **THEN** exactly one `recordEquivalentCacheMiss()` SHALL be recorded (not two)

#### Scenario: Rejection cache check does not record additional cache miss
- **WHEN** `equivalent(source, ruleName)` is called
- **AND** the source is rejected for that rule
- **THEN** exactly one `recordEquivalentCacheMiss()` SHALL be recorded

### Requirement: Guard evaluation time SHALL be reported relative to correct parent

Guard evaluation time in the metrics report SHALL be shown as a percentage of `cacheGetOrCreate` time (its actual timing parent), not as a percentage of `greedyRuleNanos` (which excludes guard time).

#### Scenario: Guard time percentage does not exceed 100% of parent
- **WHEN** a metrics report is generated
- **THEN** the guard evaluation time percentage SHALL NOT exceed 100% of its reported parent metric

### Requirement: Cache time breakdown SHALL account for all sub-components

The cache time breakdown in the metrics report SHALL subtract both `greedyRuleNanos` and `guardEvaluationNanos` from `cacheGetOrCreateNanos` when computing exclusive cache overhead time.

#### Scenario: Cache UNACCOUNTED is minimized
- **WHEN** a metrics report is generated
- **THEN** the cache exclusive time SHALL equal `cacheGetOrCreate - greedyRule - guardEvaluation` (approximately)
- **AND** the unaccounted percentage SHALL be significantly less than 78%
