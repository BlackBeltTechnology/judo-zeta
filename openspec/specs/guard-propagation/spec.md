# guard-propagation Specification

## Purpose

This specification defines how guard conditions are evaluated and cached during transformation rule execution. It covers:
- Per-rule guard rejection caching for performance optimization
- Guard propagation through `@Extends` inheritance chains
- The separation between automatic guard evaluation (via `@Extends`) and manual rule invocation (via `executeParentRule()`)
- Thread-safety requirements for parallel transformation execution
## Requirements
### Requirement: Per-Rule Guard Rejection Cache

Each transformation rule MUST maintain its own cache of rejected source elements.

#### Scenario: Guard rejection is cached per rule

**Given** a rule with a guard that returns `false` for element X
**When** the guard is first evaluated for element X
**Then** element X is added to the rule's rejected set
**And** subsequent `evaluateGuard(X)` calls return `false` without re-evaluating the guard

#### Scenario: Rejection cache is per-rule (isolated)

**Given** RuleA with guard that rejects element X
**And** RuleB with guard that accepts element X
**When** RuleA's guard is evaluated for X (rejected)
**And** RuleB's guard is evaluated for X
**Then** RuleA's rejected set contains X
**And** RuleB's rejected set does NOT contain X
**And** RuleB's guard returns `true`

#### Scenario: Rejection cache is thread-safe

**Given** parallel transformation with multiple threads
**And** multiple rules evaluating guards for different elements
**When** guards evaluate concurrently
**Then** all rejections are recorded correctly without data races
**And** no duplicate entries in rejected sets

---

### Requirement: Guard Evaluation Optimization

Guard evaluation MUST check the rejection cache before executing the guard logic.

#### Scenario: Cached rejection avoids guard execution

**Given** a rule with an expensive guard method
**And** element X was previously rejected by this rule
**When** `evaluateGuard(X)` is called again
**Then** the guard method is NOT invoked
**And** `false` is returned immediately

#### Scenario: First evaluation executes guard

**Given** a rule with a guard method
**And** element X was never evaluated by this rule
**When** `evaluateGuard(X)` is called
**Then** the guard method IS invoked
**And** result is cached if `false`

---

### Requirement: Rejection Cache Reset

The rejection cache MUST be cleared when the executor is reset for reuse.

#### Scenario: Executor reset clears all rejection caches

**Given** a transformation that recorded guard rejections
**When** the executor is reset for a new transformation
**Then** all rules' rejected sets are cleared
**And** the new transformation starts with empty rejection caches

#### Scenario: Multiple transformations with executor reuse

**Given** an executor that ran a transformation with rejections
**When** the executor is reset and runs a second transformation
**Then** the second transformation evaluates all guards fresh
**And** previous rejections do not affect the new transformation

---

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

### Requirement: executeParentRule Does NOT Evaluate Guards

When `ctx.executeParentRule(ruleName, source)` is called explicitly, the parent rule's guard SHALL NOT be evaluated. Guard evaluation for `@Extends` inheritance chains is handled separately in `TransformRuleDescriptor.execute()` before `executeParentRule()` is called.

**Rationale**: This design separates concerns:
- `@Extends` annotation: automatic guard propagation via `TransformRuleDescriptor.execute()`
- `executeParentRule()`: direct rule invocation for manual control (caller responsible for guards)

#### Scenario: executeParentRule bypasses guards

**Given** a parent rule with `@Guard(method = "isSpecial")`
**And** explicit call to `ctx.executeParentRule("ParentRule", source)`
**And** source element that would fail the parent's guard
**When** executeParentRule is invoked
**Then** the parent's guard is NOT evaluated
**And** the parent rule IS executed regardless
**And** the method returns the target element (not null)

#### Scenario: Caller responsible for guard checking

**Given** a parent rule with `@Guard(method = "isSpecial")`
**And** a caller that needs guard-like behavior
**When** the caller wants to invoke the parent rule with guard checking
**Then** the caller MUST evaluate the guard manually before calling executeParentRule
**And** executeParentRule itself will not check guards

#### Scenario: @Extends handles guards automatically

**Given** a child rule with `@Extends("ParentRule")`
**And** ParentRule has a guard
**When** the child rule is executed via normal transformation flow
**Then** `TransformRuleDescriptor.execute()` evaluates all parent guards BEFORE calling executeParentRule
**And** if any parent guard rejects, the child rule returns null
**And** executeParentRule is never called for rejected elements

---

## Cross-Reference

- **rule-execution spec**: Defines the authoritative behavior for `executeParentRule()` guard handling
- **parallel-transformation spec**: Thread-safety requirements for guard rejection caching

