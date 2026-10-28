# guard-propagation Spec Delta

## ADDED Requirements

### Requirement: Parent Guard Evaluation in @Extends Chain

When a child rule uses `@Extends` to invoke parent rules, each parent's guard MUST be evaluated before the parent rule executes.

#### Scenario: Parent guard is evaluated via @Extends and child aborts on rejection

**Given** a parent rule with `@Abstract` and `@Guard(method = "isSpecial")`
**And** a child rule with `@Extends("ParentRule")` and no guard
**And** a source element "Regular_Item" that fails the parent's guard
**When** the child rule matches the source element
**Then** the parent's guard `isSpecial` is evaluated BEFORE executing child
**And** the parent's guard returns `false` (rejects "Regular_Item")
**And** the child rule is NOT executed (aborts due to parent rejection)
**And** the parent rule is NOT executed
**And** no target element is created

#### Scenario: Parent guard passes via @Extends

**Given** a parent rule with `@Abstract` and `@Guard(method = "isSpecial")`
**And** a child rule with `@Extends("ParentRule")` and no guard
**And** a source element "Special_Item" that passes the parent's guard
**When** the child rule is executed
**Then** the parent's guard `isSpecial` is evaluated
**And** the parent's guard returns `true` (accepts "Special_Item")
**And** the parent rule IS executed
**And** the parent rule populates the shared target element

#### Scenario: No duplicate elements when parent guard rejects

**Given** a parent rule with `@Primary`, `@Abstract`, and `@Guard(method = "isSpecial")`
**And** a child rule with `@Primary` and `@Extends("ParentRule")`
**And** source elements: "Special_Item" (passes guard), "Regular_Item" (fails guard)
**When** transformation is executed
**Then** only one target element is created (from "Special_Item")
**And** no target element is created from "Regular_Item"
**And** no duplicate elements exist in the target model

#### Scenario: Guard rejection caching works via @Extends

**Given** a parent rule with guard that was previously rejected for element X
**And** a child rule with `@Extends("ParentRule")`
**When** the child rule is executed for element X
**Then** the parent's guard cache is checked
**And** the parent's guard method is NOT re-evaluated (cached rejection)
**And** the child rule is aborted immediately

#### Scenario: Multiple parents - any rejection aborts child

**Given** a child rule with `@Extends({"Rule1", "Rule2"})`
**And** Rule1 with a guard that accepts element X
**And** Rule2 with a guard that rejects element X
**When** the child rule matches element X
**Then** Rule1's guard is evaluated (passes)
**And** Rule2's guard is evaluated (rejects)
**And** the child rule is NOT executed
**And** neither parent rule is executed
**And** no target element is created

---

### Requirement: Explicit executeParentRule Guard Evaluation

When `ctx.executeParentRule(ruleName, source)` is called explicitly, the parent rule's guard MUST be evaluated before execution.

#### Scenario: executeParentRule checks guard

**Given** a parent rule with `@Guard(method = "isSpecial")`
**And** explicit call to `ctx.executeParentRule("ParentRule", source)`
**And** source element fails the parent's guard
**When** executeParentRule is invoked
**Then** the parent's guard is evaluated
**And** the method returns `null` (parent guard rejected)
**And** the parent rule is NOT executed

#### Scenario: executeParentRule executes when guard passes

**Given** a parent rule with `@Guard(method = "isSpecial")`
**And** explicit call to `ctx.executeParentRule("ParentRule", source)`
**And** source element passes the parent's guard
**When** executeParentRule is invoked
**Then** the parent's guard is evaluated
**And** the parent rule IS executed
**And** the method returns the target element
