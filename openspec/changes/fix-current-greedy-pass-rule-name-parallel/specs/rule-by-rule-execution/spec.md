# rule-by-rule-execution Specification Delta

This delta spec modifies the existing `rule-by-rule-execution` specification to clarify that `currentGreedyPassRuleName` MUST be set in both sequential and parallel rule-by-rule execution.

## MODIFIED Requirements

### Requirement: Parallel Rule-By-Rule with Per-Rule Barriers

When `ExecutionStrategy` is `RULE_BY_RULE` and `parallel(true)` is configured, the executor SHALL parallelize source element processing WITHIN each rule while maintaining sequential rule ordering. A barrier between rules SHALL ensure all parallel chunks for Rule A complete and deferred operations are committed before Rule B begins. **The executor SHALL set `currentGreedyPassRuleName` before processing each rule and clear it after completion, enabling proper same-rule lookup detection during parallel execution.**

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

#### Scenario: currentGreedyPassRuleName is set before each rule in parallel mode

- **GIVEN** strategy is `RULE_BY_RULE` and `parallel(true)`
- **AND** RuleA is a greedy rule that calls `equivalent(source, "RuleA")` during its execution
- **WHEN** RuleA executes in parallel mode
- **THEN** `context.setCurrentGreedyPassRuleName("RuleA")` SHALL be called before RuleA's parallel chunks start
- **AND** same-rule lookup detection SHALL work correctly
- **AND** `equivalent()` SHALL return null for uncached same-rule lookups (ETL semantics)
- **AND** `context.clearCurrentGreedyPassRuleName()` SHALL be called after RuleA completes

#### Scenario: Sequential mode also works with RULE_BY_RULE

- **GIVEN** `ExecutionStrategy.RULE_BY_RULE` is configured
- **AND** `parallel(false)` (sequential mode)
- **WHEN** transformation executes
- **THEN** each rule SHALL process all matching elements sequentially
- **AND** `currentGreedyPassRuleName` SHALL be set and cleared for each rule
- **AND** behavior SHALL match ELEMENT_BY_ELEMENT in output correctness
