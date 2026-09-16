# activity-based-greedy Specification Delta

## Purpose

Adds activity-based processing mode for `@Greedy @Lazy` rules, matching Epsilon ETL's implicit filtering behavior where only elements referenced via `equivalent()` are processed.

## ADDED Requirements

### Requirement: @ActivityBased Annotation

The framework MUST provide an `@ActivityBased` annotation that enables activity-based processing for `@Greedy @Lazy` rules.

#### Scenario: Activity-based rule skips unreferenced elements

**Given** a rule with `@Greedy @Lazy @ActivityBased` annotations
**And** a source model with 10 elements of the matching type
**And** only 5 elements are referenced via `equivalent()` during transformation
**When** the transformation executes
**Then** only the 5 referenced elements produce target elements
**And** the 5 unreferenced elements are not processed

#### Scenario: Activity-based annotation requires @Greedy and @Lazy

**Given** a rule with `@ActivityBased` but without `@Greedy` and `@Lazy`
**When** the transformation is registered
**Then** a warning is logged
**And** the rule executes with standard behavior (annotation has no effect)

---

### Requirement: ETL Compatibility Mode

The framework MUST provide an `etlCompatibilityMode` setting on `TransformationExecutor` that automatically enables activity-based processing for all `@Greedy @Lazy` rules.

#### Scenario: ETL compatibility mode enables activity-based for all @Greedy @Lazy rules

**Given** a transformation executor with `etlCompatibilityMode(true)`
**And** rules with `@Greedy @Lazy` (without `@ActivityBased`)
**When** the transformation executes
**Then** all `@Greedy @Lazy` rules use activity-based processing
**And** only activated elements are processed

#### Scenario: ETL compatibility mode does not affect non-lazy rules

**Given** a transformation executor with `etlCompatibilityMode(true)`
**And** a rule with `@Greedy` but without `@Lazy`
**When** the transformation executes
**Then** the rule processes ALL matching elements (standard greedy behavior)

#### Scenario: Per-rule @ActivityBased works without etlCompatibilityMode

**Given** a transformation executor without `etlCompatibilityMode`
**And** a rule with `@Greedy @Lazy @ActivityBased`
**When** the transformation executes
**Then** the rule uses activity-based processing
**And** only activated elements are processed

---

### Requirement: Activation Tracking

The framework MUST track which source elements are "activated" via `equivalent()` calls during transformation.

#### Scenario: equivalent() activates source element for activity-based rule

**Given** a rule "RuleA" with `@Greedy @Lazy @ActivityBased`
**And** another rule "RuleB" that calls `ctx.equivalent(source, TargetType.class)`
**When** "RuleB" executes and calls `equivalent()` with a source element
**Then** the source element is recorded as activated for "RuleA"
**And** "RuleA" will process this element in Phase 2

#### Scenario: Named equivalent() activates source element

**Given** a rule "CreateMetadata" with `@Greedy @Lazy @ActivityBased`
**And** another rule that calls `ctx.equivalent(source, "CreateMetadata")`
**When** the calling rule executes
**Then** the source element is activated for "CreateMetadata"

#### Scenario: equivalentDiscriminated() activates source element

**Given** a rule with `@Greedy @Lazy @ActivityBased`
**And** another rule that calls `ctx.equivalentDiscriminated(source, type, discriminator)`
**When** the calling rule executes
**Then** the source element is activated for the matching rule

---

### Requirement: Two-Phase Execution

The framework MUST execute activity-based rules in a separate phase after standard eager rules.

#### Scenario: Phase 1 skips activity-based rules

**Given** a rule with `@Greedy @Lazy @ActivityBased`
**And** standard eager rules that run in Phase 1
**When** Phase 1 executes
**Then** the activity-based rule is not executed
**And** activations are recorded from standard rules' `equivalent()` calls

#### Scenario: Phase 2 processes activated elements

**Given** a rule "RuleA" with `@Greedy @Lazy @ActivityBased`
**And** 3 source elements were activated during Phase 1
**When** Phase 2 executes
**Then** "RuleA" processes exactly the 3 activated elements
**And** guards are evaluated for each activated element
**And** elements failing guards produce no target

#### Scenario: Phase 2 handles late activations

**Given** an activity-based rule "RuleA" that calls `equivalent()` to another activity-based rule "RuleB"
**When** Phase 2 executes "RuleA"
**And** "RuleA" activates elements for "RuleB"
**Then** "RuleB" processes the newly activated elements
**And** execution continues until no new activations occur

---

### Requirement: Guards Apply to Activated Elements

Guards MUST still be evaluated for elements activated via `equivalent()`.

#### Scenario: Guard filters activated elements

**Given** a rule with `@Greedy @Lazy @ActivityBased @Guard(method = "myGuard")`
**And** 5 elements activated via `equivalent()`
**And** the guard returns `false` for 2 of them
**When** Phase 2 executes
**Then** only 3 elements produce targets
**And** the 2 failing guard are skipped

---

### Requirement: Caching Applies Normally

Activity-based rules MUST cache their results like any other rule.

#### Scenario: Cached results returned for repeated equivalent() calls

**Given** a rule "RuleA" with `@Greedy @Lazy @ActivityBased`
**And** `equivalent(source, "RuleA")` is called multiple times for the same source
**When** Phase 2 executes
**Then** "RuleA" executes only once for that source
**And** subsequent calls return the cached result

#### Scenario: Pre-cached elements not re-executed

**Given** a rule "RuleA" with `@Greedy @Lazy @ActivityBased`
**And** an element was already transformed by "RuleA" (in cache)
**And** the same element is activated again via `equivalent()`
**When** Phase 2 executes
**Then** "RuleA" does not re-execute for that element
**And** the existing cached result is used

---

### Requirement: Standard @Greedy Behavior Unchanged

Standard `@Greedy` rules (without `@ActivityBased`) MUST continue to process ALL matching elements.

#### Scenario: @Greedy without @ActivityBased processes all elements

**Given** a rule with `@Greedy` but without `@ActivityBased`
**And** a source model with 10 elements of the matching type
**And** only 5 elements are referenced via `equivalent()`
**When** the transformation executes
**Then** all 10 elements produce target elements
**And** behavior is unchanged from current implementation

---

## Cross-References

- **etl-patterns**: This capability extends the existing `@Greedy` and `@Lazy` annotations
- **Epsilon ETL**: The `@ActivityBased` mode matches Epsilon's implicit `@greedy @lazy` filtering behavior
