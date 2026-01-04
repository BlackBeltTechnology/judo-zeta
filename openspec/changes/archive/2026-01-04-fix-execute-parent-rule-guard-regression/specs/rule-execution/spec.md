# Rule Execution Specification Delta

## ADDED Requirements

### Requirement: executeParentRule MUST NOT evaluate guards

The `executeParentRule()` method in `TransformationContext` SHALL NOT evaluate the target rule's guard. Guard evaluation for `@Extends` inheritance chains is handled separately in `TransformRuleDescriptor.execute()`.

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
