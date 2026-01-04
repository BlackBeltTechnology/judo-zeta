# Bugfix Specification Delta

## ADDED Requirements

### Requirement: Regression test for missing operations

A test SHALL exist that verifies all expected operations are created during transformation.

#### Scenario: All eager rules execute for same source type

**Given** a transformation with multiple eager rules for the same source type
**And** all rules should execute (no guards rejecting)
**When** the transformation runs
**Then** all rules execute and create their target elements
**And** no rules are incorrectly skipped by the pre-filtered index

### Requirement: Regression test for eType: null

A test SHALL exist that verifies `equivalent()` correctly sets return types.

#### Scenario: equivalent() sets operation return type

**Given** an operation whose eType is set via `equivalent(returnType, EClass.class)`
**And** a lazy rule exists to transform the return type
**When** the transformation runs
**Then** `equivalent()` returns the transformed type (not null)
**And** the operation's eType is properly set

### Requirement: Regression test for metrics

A test SHALL exist that verifies transformation metrics are properly recorded.

#### Scenario: Lazy rule metrics are non-zero

**Given** a transformation with lazy rules
**And** metrics are enabled
**When** `equivalent()` is called and executes lazy rules
**Then** rule iteration count > 0
**And** guard evaluation count > 0
**And** rule execution count > 0

## MODIFIED Requirements

### Requirement: Pre-filtered rule indexes MUST return correct rules

The `getEagerRulesForType()` and `getLazyRulesForType()` methods SHALL return the same rules that would be matched by the original `getRulesForSource()` method with inline filtering.

#### Scenario: Eager rules match for EMF implementation classes

**Given** a rule registered with source type `EntityType.class` (interface)
**And** an EMF object of runtime type `EntityTypeImpl` (implementation)
**When** `getEagerRulesForType(EntityTypeImpl.class)` is called
**Then** the rule is included in the returned list
**And** the same behavior as `getRulesForSource()` with `!isLazy && !isMultiSource && !isAbstract` filtering

#### Scenario: Lazy rules match for EMF implementation classes

**Given** a lazy rule registered with source type `EntityType.class` (interface)
**And** an EMF object of runtime type `EntityTypeImpl` (implementation)
**When** `getLazyRulesForType(EntityTypeImpl.class)` is called
**Then** the rule is included in the returned list
**And** the same behavior as `getRulesForSource()` with `isLazy && !isAbstract` filtering

### Requirement: appliesTo() check MUST be consistent

The `appliesTo()` runtime check in the optimized code paths SHALL produce the same results as the original inline filtering logic.

#### Scenario: Non-greedy rule type matching

**Given** a non-greedy rule with source type `EClass`
**And** an EMF object of type `EClass` (not a subtype)
**When** `appliesTo(source)` is called
**Then** it returns true (exact type match)

#### Scenario: Greedy rule type matching

**Given** a greedy rule with source type `EClassifier`
**And** an EMF object of type `EClass` (subtype of EClassifier)
**When** `appliesTo(source)` is called
**Then** it returns true (kind-of match)

### Requirement: Transformation metrics MUST be recorded

All transformation metrics SHALL be properly recorded during execution.

#### Scenario: Lazy rule metrics recorded

**Given** a transformation with lazy rules
**When** `equivalent()` is called and executes a lazy rule
**Then** `recordRuleIteration()` is called at least once
**And** `recordGuardEvaluation()` is called at least once
**And** `recordRuleExecution()` is called at least once
