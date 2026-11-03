## MODIFIED Requirements

### Requirement: executeParentRule MUST NOT evaluate guards

The `executeParentRule()` method in `TransformationContext` SHALL NOT evaluate the target rule's guard. Guard evaluation for `@Extends` inheritance chains is handled separately in `TransformRuleDescriptor.execute()`.

#### Scenario: Direct rule invocation via executeParentRule bypasses guards

**Given** a transformation context with a lazy rule that has a guard
**When** `executeParentRule()` is called with a source element that would fail the guard
**Then** the rule is executed regardless of guard result
**And** the target element is returned (not null)

#### Scenario: Extends inheritance evaluates parent guards using guard method cache

**Given** a child rule with `@Extends("ParentRule")` annotation
**And** ParentRule has a guard method `isValid`
**When** the child rule is executed on a source element
**Then** `parentRule.evaluateGuard(source, context)` SHALL check the guard method cache for `(isValid, source)`
**And** if a cached result exists, the guard method SHALL NOT be re-invoked
