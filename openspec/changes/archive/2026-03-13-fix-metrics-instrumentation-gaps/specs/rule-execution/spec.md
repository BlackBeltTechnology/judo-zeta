## ADDED Requirements

### Requirement: Metrics counters SHALL be consistent across execution strategies

TransformationMetrics counters (`ruleIterations`, `ruleExecutions`, `guardEvaluations`) SHALL be incremented for every rule iteration, execution, and guard evaluation regardless of execution strategy (ELEMENT_BY_ELEMENT or RULE_BY_RULE).

#### Scenario: RULE_BY_RULE increments ruleIterations
- **WHEN** a transformation runs with `ExecutionStrategy.RULE_BY_RULE`
- **AND** the executor iterates over source elements for a rule
- **THEN** `ruleIterations` SHALL be incremented for each source element checked

#### Scenario: RULE_BY_RULE increments ruleExecutions
- **WHEN** a rule executes successfully (non-null result) during a RULE_BY_RULE greedy pass
- **THEN** `ruleExecutions` SHALL be incremented

#### Scenario: ELEMENT_BY_ELEMENT and RULE_BY_RULE produce comparable counter values
- **WHEN** the same transformation runs with both strategies on identical input
- **THEN** `ruleExecutions` SHALL be equal
- **AND** `guardEvaluations` SHALL be equal

### Requirement: equivalent() cache hit and miss counts SHALL sum to total calls

The sum of `equivalentCacheHits` and `equivalentCacheMisses` SHALL NOT exceed `equivalentCalls`. Each call to `equivalent()` SHALL record exactly one of: cache hit OR cache miss (not both, and not multiple misses).

#### Scenario: No double-counting of cache misses in equivalent(source, ruleName)
- **WHEN** `equivalent(source, ruleName)` is called
- **AND** the result is not cached and not rejected
- **THEN** exactly one `recordEquivalentCacheMiss()` SHALL be recorded (not two)

#### Scenario: Rejection cache check does not record additional cache miss
- **WHEN** `equivalent(source, ruleName)` is called
- **AND** the source is rejected for that rule
- **THEN** exactly one `recordEquivalentCacheMiss()` SHALL be recorded

### Requirement: Guard evaluation time SHALL be reported relative to correct parent

Guard evaluation time in the metrics report SHALL be shown as a percentage of `cacheGetOrCreate` time (its actual timing parent), not as a percentage of `greedyRuleNanos` (which excludes guard time).

#### Scenario: Guard time percentage does not exceed 100% of parent
- **WHEN** a metrics report is generated
- **THEN** the guard evaluation time percentage SHALL NOT exceed 100% of its reported parent metric

### Requirement: Cache time breakdown SHALL account for all sub-components

The cache time breakdown in the metrics report SHALL subtract both `greedyRuleNanos` and `guardEvaluationNanos` from `cacheGetOrCreateNanos` when computing exclusive cache overhead time.

#### Scenario: Cache UNACCOUNTED is minimized
- **WHEN** a metrics report is generated
- **THEN** the cache exclusive time SHALL equal `cacheGetOrCreate - greedyRule - guardEvaluation` (approximately)
- **AND** the unaccounted percentage SHALL be significantly less than 78%
