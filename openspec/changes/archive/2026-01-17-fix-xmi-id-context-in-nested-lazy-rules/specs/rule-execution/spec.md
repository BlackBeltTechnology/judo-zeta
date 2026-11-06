# rule-execution Spec Delta

## ADDED Requirements

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
