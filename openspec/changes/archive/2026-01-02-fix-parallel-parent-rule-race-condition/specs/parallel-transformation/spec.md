# Spec Delta: parallel-transformation

## ADDED Requirements

### Requirement: Atomic Parent Rule Execution

The `executeParentRule()` method MUST use atomic get-or-create operations to prevent duplicate parent rule execution during parallel transformation.

#### Scenario: Concurrent executeParentRule() returns same instance

**Given** parallel transformation with multiple worker threads
**And** child rule "ChildA" extends parent rule "ParentRule"
**And** child rule "ChildB" also extends parent rule "ParentRule"
**When** Thread A calls `executeParentRule("ParentRule", source)` for ChildA
**And** Thread B calls `executeParentRule("ParentRule", source)` for ChildB simultaneously
**Then** only one parent rule execution occurs
**And** both threads receive the same target element instance
**And** the target is cached for future lookups

#### Scenario: Inheritance state preserved during atomic execution

**Given** parallel transformation is in progress
**And** a child rule provides a pre-created target via `executeParentRule(name, source, target)`
**When** multiple threads call `executeParentRule()` for the same parent
**Then** the first thread's pre-created target is used
**And** subsequent threads receive the same cached result
**And** inheritance state (preCreatedTarget, inInheritanceExecution) is correctly managed

### Requirement: Atomic Inheritance Chain Execution

The `@Extends` inheritance chain execution MUST use atomic operations to prevent duplicate parent execution in multi-level inheritance.

#### Scenario: Multi-level inheritance executes each parent once

**Given** rule "GrandChild" extends "Child" extends "Parent"
**And** parallel transformation is processing element E
**When** multiple threads execute GrandChild rule for element E
**Then** Parent rule executes exactly once for E
**And** Child rule executes exactly once for E
**And** GrandChild rule executes exactly once for E
**And** all rules share the same pre-created target

#### Scenario: Diamond inheritance handles correctly

**Given** rule "D" extends both "B" and "C"
**And** rules "B" and "C" both extend "A"
**When** rule D executes for element E
**Then** rule A executes exactly once
**And** all paths through the diamond share the same target from A
