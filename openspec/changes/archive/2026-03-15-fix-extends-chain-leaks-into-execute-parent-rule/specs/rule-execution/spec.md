## MODIFIED Requirements

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
