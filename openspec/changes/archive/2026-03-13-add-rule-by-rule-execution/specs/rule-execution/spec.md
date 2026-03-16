## MODIFIED Requirements

### Requirement: Deterministic Transformation Output

The transformation framework SHALL produce byte-identical XMI output for the same input model across multiple executions **with the same `ExecutionStrategy`**. Rule ordinal assignment, cache iteration order, and all internal state SHALL be deterministic. Different execution strategies (`ELEMENT_BY_ELEMENT` vs `RULE_BY_RULE`) MAY produce different output because the rule-source execution order differs.

#### Scenario: Same input produces identical XMI output

- **GIVEN** an input model M
- **AND** the same `ExecutionStrategy` is used
- **WHEN** transformation is executed twice with identical configuration
- **THEN** the XMI output SHALL be byte-identical
- **AND** all XMI IDs SHALL be identical

#### Scenario: Registration order does not affect output

- **GIVEN** rules A, B, C registered in order A, B, C
- **AND** transformation produces output O1
- **WHEN** rules are registered in order C, A, B
- **AND** transformation is executed again with same strategy
- **THEN** the output SHALL be identical to O1

#### Scenario: Different strategies may produce different output

- **GIVEN** an input model M
- **WHEN** transformation is executed with `ELEMENT_BY_ELEMENT`
- **AND** transformation is executed again with `RULE_BY_RULE`
- **THEN** the outputs MAY differ
- **BECAUSE** the execution order of rule-source pairs differs between strategies
- **AND** rules that depend on cross-element outputs from earlier rules will produce different results
