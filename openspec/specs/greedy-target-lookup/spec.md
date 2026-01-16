# greedy-target-lookup Specification

## Purpose
TBD - created by archiving change fix-greedy-rule-target-lookup. Update Purpose after archive.
## Requirements
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

### Requirement: XMI ID Lookup Includes Greedy Rules

The `equivalent()` method SHALL check XMI ID lookup for ALL rule types, including greedy rules with @Primary annotation. This extends the existing XMI ID lookup behavior to ensure greedy rule targets are findable.

#### Scenario: Eager greedy rule target found via XMI ID

**Given** RuleA with `@Greedy @Primary @Lazy` creates ClassType targets
**And** RuleB with `@Greedy` calls `ctx.equivalent(source.getTarget(), CLASS_TYPE)`
**When** RuleB executes after RuleA has created the targets
**Then** XMI ID lookup finds the ClassType target
**And** the target is returned (not null)

#### Scenario: Eager target found before rule completion

**Given** RuleA and RuleB are both @Greedy eager rules
**And** RuleA creates target for element E1
**And** RuleB needs to find E1's target via equivalent()
**When** RuleB calls equivalent() after RuleA has started but before RuleA completes
**Then** once RuleA completes, the target is findable
**And** RuleB receives the correct target

---

### Requirement: Cross-Source-Type Lazy Rule Invocation (JNG-6349)

When `ctx.equivalent(source, "RuleName")` is called with a source object of type A to invoke a rule declared for source type B, the rule MUST execute if the source is an instance of type B. The rule lookup SHALL be global across all registered transformation classes.

#### Scenario: Cross-source-type equivalent() call succeeds

**Given** TransformationClassA with source type `RelationFeature`
**And** TransformationClassB with `@Lazy @Greedy` rule "RuleB" for source type `TransferObjectTable`
**And** Both transformation classes are registered
**When** A rule in TransformationClassA calls `ctx.equivalent(table, "RuleB")`
**Where** `table` is a `TransferObjectTable` instance
**Then** RuleB executes immediately
**And** The created target is returned (not null)
**And** The target is cached for subsequent calls

#### Scenario: Cross-source-type lookup is registration order independent

**Given** TransformationClassA registered BEFORE TransformationClassB
**And** TransformationClassA's rule calls `ctx.equivalent(source, "RuleBFromClassB")`
**When** The transformation executes
**Then** The equivalent() call succeeds (not null)
**And** Same result occurs if registration order is reversed

#### Scenario: Cross-source-type lookup with @Greedy rule

**Given** RuleA in ClassA has `@Greedy` on source type A
**And** RuleB in ClassB has `@Lazy @Greedy` on source type B
**And** RuleA calls `ctx.equivalent(instanceOfB, "RuleB")`
**When** RuleA executes during greedy pass
**And** ClassB's greedy pass has NOT yet started
**Then** RuleB executes immediately via `executeLazyRuleImmediately()`
**And** The target is returned (not null)
**And** RuleB is NOT re-executed when ClassB's greedy pass runs

#### Scenario: appliesTo() check passes for valid cross-type source

**Given** RuleB declared with source type B
**And** RuleB has `@Greedy` annotation
**And** `instanceOfB` is an instance of type B
**When** `rule.appliesTo(instanceOfB)` is called
**Then** Returns true (not false)
**And** `executeLazyRuleImmediately()` proceeds to execute the rule

#### Scenario: Global rule registry lookup

**Given** Multiple transformation classes with different source types
**And** Each class has rules with unique names
**When** `ctx.equivalent(source, "SomeRuleName")` is called
**Then** The rule is found in the global registry (not scoped to current transformation class)
**And** The rule executes if `appliesTo(source)` returns true

---

### Requirement: Idempotent Caching for Greedy Rule Targets

Greedy rule targets MUST follow the same idempotent caching semantics as all other rules. The calling context (which rule invoked equivalent()) does NOT affect cache lookup.

#### Scenario: Multiple callers get same greedy rule target

**Given** RuleA with `@Greedy` creates target T for source S
**And** RuleB calls `ctx.equivalent(S, "RuleA")`
**And** RuleC also calls `ctx.equivalent(S, "RuleA")`
**When** both calls execute
**Then** both receive the same target instance T (identity check)
**And** RuleA executes exactly once

#### Scenario: Greedy target cache key excludes calling context

**Given** a greedy rule target is cached
**When** equivalent() is called from different calling rules
**Then** the cache key is `(source, ruleName)` only
**And** the calling rule does NOT affect cache lookup
**And** compound XMI IDs are NOT generated

**Note**: This is intentional idempotent behavior. ETL's context-dependent caching (which produces compound IDs) is a bug that Zeta does not replicate. See `etl-patterns` spec for details.

