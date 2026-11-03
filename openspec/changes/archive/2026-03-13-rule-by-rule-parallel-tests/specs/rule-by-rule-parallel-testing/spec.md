## ADDED Requirements

### Requirement: Basic RULE_BY_RULE Parallel Execution

The test suite SHALL verify that RULE_BY_RULE strategy with parallel mode enabled transforms all source elements correctly across multiple rules, with each rule processing elements in parallel chunks and a barrier between rules.

#### Scenario: All elements transformed with RULE_BY_RULE parallel
- **WHEN** a transformation with 3+ rules runs in RULE_BY_RULE strategy with parallelThreshold(1)
- **THEN** every source element SHALL have a corresponding target element for each applicable rule
- **AND** the total number of created targets SHALL equal the sequential RULE_BY_RULE result

#### Scenario: Rules execute in registration order with parallel chunks
- **WHEN** RULE_BY_RULE parallel executes rules A, B, C (registered in that order)
- **THEN** all elements for Rule A complete before Rule B starts
- **AND** all elements for Rule B complete before Rule C starts
- **AND** within each rule, elements are processed in parallel chunks

### Requirement: Cross-Rule Equivalent Resolution in Parallel

The test suite SHALL verify that `equivalent()` calls in Rule B correctly find targets created by Rule A, after the incremental commit barrier between rules.

#### Scenario: Rule B resolves equivalent from Rule A in parallel mode
- **WHEN** Rule A creates targets for all source elements (parallel chunks)
- **AND** incremental commit runs after Rule A completes
- **AND** Rule B calls `ctx.equivalent(source, targetType, "RuleA")` in parallel chunks
- **THEN** every call SHALL return the target created by Rule A (not null)

#### Scenario: Concurrent equivalent calls within same rule do not interfere
- **WHEN** multiple parallel chunks of Rule B call `equivalent()` for different source elements simultaneously
- **THEN** each call SHALL return the correct target for its specific source element
- **AND** no cross-contamination between chunks SHALL occur

### Requirement: Incremental Commit Visibility Between Rules

The test suite SHALL verify that `commitDeferredOperationsIncremental()` makes Rule A's results visible to Rule B in RULE_BY_RULE parallel mode.

#### Scenario: Deferred operations committed between rules
- **WHEN** Rule A sets cross-references via deferred operations
- **AND** the barrier between Rule A and Rule B commits deferred operations
- **THEN** Rule B SHALL see the cross-references set by Rule A

#### Scenario: Target model contains all elements after all rules complete
- **WHEN** all rules have completed in RULE_BY_RULE parallel
- **THEN** the target model SHALL contain all elements from all rules
- **AND** no elements SHALL be missing due to uncommitted staging

### Requirement: Guard Evaluation in RULE_BY_RULE Parallel

The test suite SHALL verify that guard predicates evaluate correctly when rule elements are processed in parallel chunks.

#### Scenario: Guards reject elements correctly in parallel chunks
- **WHEN** a rule has a guard that rejects 50% of elements
- **AND** elements are processed in parallel chunks
- **THEN** the same elements SHALL be rejected as in sequential mode
- **AND** rejected elements SHALL have no corresponding target

#### Scenario: Guard rejection caching works in parallel RULE_BY_RULE
- **WHEN** Rule B calls `equivalent()` for an element rejected by Rule A's guard
- **THEN** the cached rejection SHALL be returned (null) without re-evaluating the guard

### Requirement: Greedy Rules in RULE_BY_RULE Parallel

The test suite SHALL verify that @Greedy rules process all applicable source types correctly when elements are processed in parallel chunks.

#### Scenario: Greedy rule processes all source types in parallel
- **WHEN** a @Greedy rule applies to both EClass and EAttribute elements
- **AND** elements are processed in parallel chunks
- **THEN** all EClass AND all EAttribute elements SHALL be transformed
- **AND** the count SHALL match sequential greedy execution

### Requirement: Lazy Rules in RULE_BY_RULE Parallel

The test suite SHALL verify that @Lazy rules triggered via `equivalent()` during parallel execution work correctly.

#### Scenario: Lazy rule triggered by parallel chunk
- **WHEN** an eager rule's parallel chunk calls `ctx.equivalent()` which triggers a @Lazy rule
- **THEN** the lazy rule SHALL execute and return the created target
- **AND** subsequent calls for the same source SHALL return the cached target

#### Scenario: Concurrent lazy rule triggers for same source
- **WHEN** two parallel chunks simultaneously trigger the same @Lazy rule for the same source
- **THEN** only one execution SHALL occur
- **AND** both chunks SHALL receive the same target instance

### Requirement: @Extends Inheritance in RULE_BY_RULE Parallel

The test suite SHALL verify that child rules using @Extends correctly find parent rule results in RULE_BY_RULE parallel mode.

#### Scenario: Child rule finds parent equivalent in parallel
- **WHEN** parent rule processes elements in parallel (Rule A, iteration 1)
- **AND** incremental commit occurs
- **AND** child rule with @Extends("RuleA") processes elements in parallel (iteration 2)
- **THEN** the child rule SHALL find the parent's target via equivalent()

### Requirement: Stress Test for RULE_BY_RULE Parallel

The test suite SHALL include high-element-count stress tests to verify RULE_BY_RULE parallel handles large datasets without data loss or corruption.

#### Scenario: 1000+ elements with multiple rules in parallel
- **WHEN** 1000+ source elements are transformed with 5+ rules in RULE_BY_RULE parallel
- **THEN** all expected targets SHALL be created
- **AND** no ConcurrentModificationException or data corruption SHALL occur

#### Scenario: Repeated execution produces consistent results
- **WHEN** the same transformation runs 10 times with RULE_BY_RULE parallel
- **THEN** the target count SHALL be identical across all runs
- **AND** no intermittent failures SHALL occur

### Requirement: CLONE_CURRENT_STATE Rejection with RULE_BY_RULE Parallel

The test suite SHALL verify that the invalid combination of CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is properly rejected.

#### Scenario: IllegalStateException for invalid combination
- **WHEN** a transformation is configured with CLONE_CURRENT_STATE, RULE_BY_RULE, and parallel enabled
- **THEN** an IllegalStateException SHALL be thrown during configuration validation
- **AND** the message SHALL explain why this combination is invalid

### Requirement: Deferred Operations Ordering in RULE_BY_RULE Parallel

The test suite SHALL verify that deferred operations (cross-references) set during parallel chunks survive incremental commit and produce correct results.

#### Scenario: Cross-references set in parallel chunks are committed correctly
- **WHEN** Rule A's parallel chunks set cross-references via deferred operations
- **AND** incremental commit runs between Rule A and Rule B
- **THEN** all cross-references SHALL be present in the target model
- **AND** Rule B SHALL be able to read cross-references set by Rule A
