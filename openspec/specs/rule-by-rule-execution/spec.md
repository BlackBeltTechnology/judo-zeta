# rule-by-rule-execution Specification

## Purpose
TBD

## Requirements

### Requirement: ExecutionStrategy Configuration

The `TransformationExecutor` SHALL support a configurable `ExecutionStrategy` that controls the loop nesting order for eager rule execution. The default strategy SHALL be `ELEMENT_BY_ELEMENT` (existing behavior). An alternative `RULE_BY_RULE` strategy SHALL process all matching source elements per rule before advancing to the next rule.

#### Scenario: Default strategy is ELEMENT_BY_ELEMENT

- **WHEN** a `TransformationExecutor` is built with no explicit strategy
- **THEN** the executor SHALL use `ELEMENT_BY_ELEMENT` strategy
- **AND** the execution behavior SHALL be unchanged from existing behavior

#### Scenario: Strategy configurable via builder

- **WHEN** `TransformationExecutor.builder().executionStrategy(ExecutionStrategy.RULE_BY_RULE)` is called
- **THEN** the executor SHALL use rule-by-rule execution for eager rules
- **AND** `transformRuleByRule()` SHALL be invoked instead of `transformSequential()`

### Requirement: Rule-By-Rule Execution Order

When `ExecutionStrategy` is `RULE_BY_RULE`, the executor SHALL iterate over eager rules in registration order (outer loop), and for each rule iterate over all source elements (inner loop). This matches ETL's module import ordering where each rule processes all matching elements before the next rule begins.

#### Scenario: Rules execute in registration order

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** rules are registered in order: RuleA, RuleB, RuleC
- **AND** source elements are [E1, E2, E3]
- **WHEN** transformation executes
- **THEN** all applicable elements SHALL be processed by RuleA first
- **AND** then all applicable elements SHALL be processed by RuleB
- **AND** then all applicable elements SHALL be processed by RuleC

#### Scenario: Source elements maintain collection order within each rule

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** source elements are ordered [E1, E2, E3]
- **WHEN** RuleA processes its matching elements
- **THEN** elements SHALL be visited in the same order as the source collection
- **AND** E1 SHALL be processed before E2, and E2 before E3

#### Scenario: Non-matching elements are skipped per rule

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** RuleA applies to `EntityType` and RuleB applies to `RelationType`
- **AND** source elements are [entity1, relation1, entity2]
- **WHEN** transformation executes
- **THEN** RuleA SHALL process [entity1, entity2] (skipping relation1)
- **AND** RuleB SHALL process [relation1] (skipping entities)

### Requirement: Rule-By-Rule Produces Same Cached Results

When `ExecutionStrategy` is `RULE_BY_RULE`, each rule-source execution SHALL use the same `ElementResolutionCache.getOrCreate()` pattern as element-by-element execution. The cache ensures idempotency — if a lazy rule has already created a target for a source, the cached result is returned.

#### Scenario: Cache prevents duplicate execution

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** RuleA triggers `equivalent(source, "RuleB")` during execution
- **AND** RuleB has already executed for that source (earlier in rule order)
- **WHEN** `equivalent()` resolves
- **THEN** the cached result from RuleB's earlier execution SHALL be returned
- **AND** RuleB SHALL NOT execute again for that source

#### Scenario: Lazy rules triggered during rule-by-rule execution

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** RuleA calls `equivalent(source, "LazyRule")` during execution
- **AND** LazyRule has not been triggered yet
- **WHEN** `equivalent()` resolves
- **THEN** LazyRule SHALL execute on-demand (same as element-by-element)
- **AND** the result SHALL be cached for subsequent lookups

### Requirement: Parallel Rule-By-Rule with Per-Rule Barriers

When `ExecutionStrategy` is `RULE_BY_RULE` and `parallel(true)` is configured, the executor SHALL parallelize source element processing WITHIN each rule while maintaining sequential rule ordering. A barrier between rules SHALL ensure all parallel chunks for Rule A complete and deferred operations are committed before Rule B begins.

#### Scenario: Parallel chunks within a rule

- **GIVEN** strategy is `RULE_BY_RULE` and `parallel(true)`
- **AND** RuleA applies to 100 source elements
- **WHEN** transformation executes
- **THEN** the 100 elements SHALL be distributed across parallel chunks
- **AND** all chunks for RuleA SHALL complete before RuleB begins

#### Scenario: Deferred operations committed between rules

- **GIVEN** strategy is `RULE_BY_RULE` and `parallel(true)`
- **AND** RuleA sets properties on targets via deferred writes
- **WHEN** RuleA's parallel phase completes
- **THEN** deferred operations from RuleA SHALL be committed before RuleB starts
- **AND** RuleB SHALL see RuleA's materialized property values
- **BECAUSE** without inter-rule commit, RuleB would see proxy/deferred state

#### Scenario: Sequential mode also works with RULE_BY_RULE

- **GIVEN** `ExecutionStrategy.RULE_BY_RULE` is configured
- **AND** `parallel(false)` is configured
- **WHEN** `transform()` is called
- **THEN** the transformation SHALL proceed sequentially without exception

### Requirement: Parallel Rule-By-Rule Output Determinism

When `ExecutionStrategy` is `RULE_BY_RULE` with `parallel(true)`, the XMI output SHALL be byte-identical to the output produced with `parallel(false)` for the same input model and configuration. The per-rule barrier and deferred operation replay order SHALL ensure deterministic results.

#### Scenario: Parallel output matches sequential output

- **GIVEN** an input model M with N source elements
- **WHEN** transformation is executed with `RULE_BY_RULE` + `parallel(false)`
- **AND** transformation is executed again with `RULE_BY_RULE` + `parallel(true)`
- **THEN** the XMI outputs SHALL be byte-identical
- **AND** all XMI IDs SHALL be identical
- **AND** all element ordering SHALL be identical

#### Scenario: Stress test with many elements

- **GIVEN** an input model with 100+ source elements of mixed types
- **AND** multiple eager rules registered in specific order
- **WHEN** transformation executes with `RULE_BY_RULE` + `parallel(true)` repeated 5 times
- **THEN** all 5 outputs SHALL be byte-identical
- **AND** no race conditions SHALL produce duplicate targets

### Requirement: CLONE_CURRENT_STATE Incompatible with Parallel Rule-By-Rule

The combination of `CLONE_CURRENT_STATE` + `RULE_BY_RULE` + `parallel(true)` SHALL throw `IllegalStateException`. Mutation-propagation semantics require deterministic element processing order within each rule, which parallel chunking does not guarantee.

#### Scenario: Fail-fast on CLONE_CURRENT_STATE + parallel rule-by-rule

- **GIVEN** `ExecutionStrategy.RULE_BY_RULE` is configured
- **AND** `parallel(true)` is configured
- **AND** `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE` is configured
- **WHEN** `transform()` is called
- **THEN** an `IllegalStateException` SHALL be thrown
- **AND** the message SHALL indicate that CLONE_CURRENT_STATE requires sequential execution within each rule

#### Scenario: CLONE_CURRENT_STATE + sequential rule-by-rule is allowed

- **GIVEN** `ExecutionStrategy.RULE_BY_RULE` is configured
- **AND** `parallel(false)` is configured
- **AND** `EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE` is configured
- **WHEN** `transform()` is called
- **THEN** the transformation SHALL proceed normally

### Requirement: Lazy Rule Thread Safety in Parallel Rule-By-Rule

When parallel chunks trigger the same lazy rule for the same source concurrently, the `ElementResolutionCache` SHALL ensure only one execution occurs. All concurrent callers SHALL receive the same cached result.

#### Scenario: Concurrent lazy rule access returns same result

- **GIVEN** strategy is `RULE_BY_RULE` and `parallel(true)`
- **AND** multiple parallel chunks call `equivalent(source, "LazyRule")` concurrently
- **WHEN** the first chunk creates the lazy rule target
- **THEN** subsequent chunks SHALL receive the cached result
- **AND** the lazy rule SHALL NOT execute more than once for that source

### Requirement: Ordered Eager Rule Access

The `TransformationRegistry` SHALL provide a method to retrieve all eager rules in registration order, suitable for rule-by-rule iteration. The method SHALL filter out lazy, abstract, and multi-source rules.

#### Scenario: getOrderedEagerRules returns rules in registration order

- **GIVEN** classes are registered in order: ClassA (containing RuleA1, RuleA2), ClassB (containing RuleB1)
- **WHEN** `registry.getOrderedEagerRules()` is called
- **THEN** the result SHALL be [RuleA1, RuleA2, RuleB1]
- **AND** lazy rules SHALL be excluded
- **AND** abstract rules SHALL be excluded
- **AND** multi-source rules SHALL be excluded

#### Scenario: Activity-based rules excluded from ordered eager rules

- **GIVEN** a rule annotated with `@ActivityBased`
- **WHEN** `registry.getOrderedEagerRules()` is called
- **THEN** the activity-based rule SHALL NOT be in the result
- **BECAUSE** activity-based rules execute in Phase 2, not Phase 1

### Requirement: Activity-Based Phase 2 Unchanged

The Phase 2 activity-based rule execution SHALL remain unchanged regardless of the `ExecutionStrategy`. Activity-based rules execute after all eager rules complete, using the fixpoint activation loop.

#### Scenario: Activity-based rules execute after rule-by-rule Phase 1

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** Phase 1 completes all eager rules in rule order
- **WHEN** Phase 2 begins
- **THEN** activity-based rules SHALL execute using the existing fixpoint activation loop
- **AND** only elements activated via `equivalent()` during Phase 1 SHALL be processed

### Requirement: Guard and Error Handling Identical

When `ExecutionStrategy` is `RULE_BY_RULE`, guard evaluation, error handling, and fail-fast behavior SHALL be identical to `ELEMENT_BY_ELEMENT`. The same `executeRuleForSource()` logic SHALL be used for both strategies.

#### Scenario: Guard rejection works in rule-by-rule mode

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** RuleA has a guard that rejects element E2
- **WHEN** RuleA processes elements [E1, E2, E3]
- **THEN** E2 SHALL be skipped (guard rejected)
- **AND** E1 and E3 SHALL be executed normally

#### Scenario: Fail-fast on error in rule-by-rule mode

- **GIVEN** strategy is `RULE_BY_RULE`
- **AND** RuleA throws an exception while processing E2
- **WHEN** the error is captured
- **THEN** processing SHALL stop (fail-fast)
- **AND** subsequent rules and elements SHALL NOT be processed
