# Spec: Greedy Target Lookup

**Capability**: greedy-target-lookup
**Status**: Active
**Parent Spec**: parallel-transformation
**Relation**: Extends existing XMI ID lookup requirements

## ADDED Requirements

### Requirement: Greedy Rule Targets Are Findable Via equivalent()

Targets created by `@Greedy` rules MUST be findable via `ctx.equivalent()` calls from other rules in the same greedy pass.

#### Scenario: Greedy rule target found by another greedy rule

**Given** RuleA with `@Greedy @Primary @Lazy` transforms Class to ClassType
**And** RuleB with `@Greedy` transforms RelationFeature to RelationType
**And** RuleB calls `ctx.equivalent(source.getTarget(), CLASS_TYPE)`
**When** the greedy pass executes RuleA first (creates ClassType targets)
**And** then executes RuleB (needs to find ClassType for RelationType.target)
**Then** `ctx.equivalent()` returns the ClassType target created by RuleA
**And** RelationType.target is correctly set (not null)

#### Scenario: Multiple greedy rules with cross-references

**Given** transformation with 3 greedy rules: RuleA, RuleB, RuleC
**And** RuleB depends on RuleA's output
**And** RuleC depends on RuleB's output
**When** the transformation executes
**Then** RuleA's targets are findable by RuleB
**And** RuleB's targets are findable by RuleC
**And** all cross-references are correctly resolved

#### Scenario: Greedy target lookup works in sequential mode

**Given** greedy rules with cross-references
**And** transformation runs in sequential mode (parallel=false)
**When** RuleB calls `ctx.equivalent()` to find RuleA's target
**Then** the target is found and returned (not null)

#### Scenario: Greedy target lookup works in parallel mode

**Given** greedy rules with cross-references
**And** transformation runs in parallel mode (parallel=true)
**And** element count exceeds parallel threshold
**When** RuleB calls `ctx.equivalent()` to find RuleA's target
**Then** the target is found and returned (not null)

---

### Requirement: XMI ID Lookup for Eager Greedy Rule Targets

The `equivalent()` method MUST perform XMI ID lookup for targets created by eager (non-lazy) greedy rules.

#### Scenario: equivalent() finds eager rule target via XMI ID

**Given** an EAGER rule (without @Lazy) that creates a target
**And** the target has a structured XMI ID (e.g., "Customer/(esm/_abc123)/ClassType")
**When** another rule calls `ctx.equivalent(source, "ClassType")`
**Then** XMI ID lookup finds the target
**And** the target is returned (not null)

#### Scenario: Eager target ID is available immediately

**Given** an eager rule creates a target via `ctx.createTarget()`
**When** the target's XMI ID is queried immediately after creation
**Then** the ID is available and stable
**And** equivalent() can find the target using this ID

#### Scenario: XMI ID index is updated synchronously on target creation

**Given** an eager rule creates a target
**When** the target is added to the resolution cache
**Then** the XMI ID index is updated synchronously
**And** subsequent equivalent() calls can find the target

---

### Requirement: Target Caching Before ID Generation

Targets MUST be findable via XMI ID lookup even if their IDs are generated after cache insertion.

#### Scenario: Target found via ID even if ID generated later

**Given** a target is created and cached
**And** the XMI ID is generated after some processing
**When** equivalent() is called for this target
**Then** the XMI ID lookup finds the target
**And** the target is returned correctly

#### Scenario: Structured ID format enables lookup

**Given** useStructuredIds is enabled (default)
**And** a target is created for source element S by rule R
**When** equivalent(S, R) is called
**Then** structured ID is generated: `<source-name>/(<alias>/<source-id>)/<rule-name>`
**And** lookup by ID finds the target

---

## MODIFIED Requirements

### Requirement: XMI ID Lookup for All Rule Types (parallel-transformation spec)

The `equivalent()` method SHALL check XMI ID lookup for ALL rule types (eager, lazy, and greedy), not just lazy rules. Targets created by any rule type MUST be findable via XMI ID lookup by subsequent `equivalent()` calls.

#### Original Text:

> The `equivalent()` method MUST check XMI ID lookup for ALL rule types (eager and lazy), not just lazy rules.

#### Clarified Text:

The `equivalent()` method SHALL check XMI ID lookup for ALL rule types, including:
- Lazy rules (as originally specified)
- Eager rules (explicitly clarified)
- Greedy rules with @Primary (explicitly clarified)

Targets created by any rule type MUST be findable via XMI ID lookup by subsequent `equivalent()` calls, regardless of whether the creating rule has already completed or is currently executing.

#### Scenario: Eager greedy rule target found via XMI ID (NEW)

**Given** RuleA with `@Greedy @Primary @Lazy` creates ClassType targets
**And** RuleB with `@Greedy` calls `ctx.equivalent(source.getTarget(), CLASS_TYPE)`
**When** RuleB executes after RuleA has created the targets
**Then** XMI ID lookup finds the ClassType target
**And** the target is returned (not null)

#### Scenario: Eager target found before rule completion (NEW)

**Given** RuleA and RuleB are both @Greedy eager rules
**And** RuleA creates target for element E1
**And** RuleB needs to find E1's target via equivalent()
**When** RuleB calls equivalent() after RuleA has started but before RuleA completes
**Then** once RuleA completes, the target is findable
**And** RuleB receives the correct target
