# Performance Specification Delta

## MODIFIED Requirements

### Requirement: Rule matching MUST use pre-computed type index

Rule matching for eager and lazy rules SHALL use a pre-computed index keyed by source EClass, reducing lookup complexity from O(r) to O(1) where r is the total number of rules.

#### Scenario: Eager rule lookup uses type index

**Given** a transformation with 50 rules registered
**And** a source element of type EntityType
**When** `executeEagerRulesFor()` is called
**Then** the rule registry returns only rules applicable to EntityType
**And** no iteration over all 50 rules occurs
**And** returned rules are pre-filtered for: !isLazy, !isMultiSource, !isAbstract

#### Scenario: Lazy rule lookup uses type index

**Given** a transformation with 50 rules registered
**And** a source element of type EntityType
**When** `equivalent(source, TargetType.class)` is called
**Then** the rule registry returns only lazy rules applicable to EntityType and TargetType
**And** no iteration over all 50 rules occurs

### Requirement: Rule applicability checks MUST be pre-computed at registration

Static rule properties (isLazy, isMultiSource, isAbstract, appliesTo type) SHALL be evaluated once at registration time, not per-element during transformation.

#### Scenario: Rule index is built at registration

**Given** a rule with sourceType=EntityType and isLazy=false
**When** the rule is registered
**Then** the rule is added to the eager rule index for EntityType
**And** the rule is added to the eager rule index for all subtypes of EntityType

#### Scenario: Dynamic checks remain at runtime

**Given** a rule with isEagerExecutable=true
**And** ETL compatibility mode may be enabled
**When** `executeEagerRulesFor()` is called
**Then** `isEffectivelyActivityBased()` is checked at runtime (depends on config)
**And** `isFromExpectedAlias()` is checked at runtime (depends on element location)

### Requirement: Performance improvement MUST be measurable

Transformations with >10,000 elements SHALL show >50% reduction in rule loop overhead compared to pre-optimization baseline.

#### Scenario: Large model performance improvement

**Given** a transformation with 22,000 source elements
**And** 50 registered rules
**And** metrics are enabled
**When** the transformation completes
**Then** "Rule matching" metric is <20% of pre-optimization baseline
**And** "Rule loop (exclusive)" metric is <30% of pre-optimization baseline
