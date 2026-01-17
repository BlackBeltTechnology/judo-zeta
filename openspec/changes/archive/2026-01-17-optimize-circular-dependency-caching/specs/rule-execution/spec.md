## ADDED Requirements

### Requirement: Circular Dependency Handling for Discriminated Calls

The transformation framework SHALL support circular dependencies when `equivalentDiscriminated()` is called with different discriminators for the same source and rule. When Rule A (discriminator d1) calls Rule B, and Rule B calls back to Rule A (discriminator d2), the framework SHALL clone the in-progress target from d1 instead of returning null.

#### Scenario: Circular discriminated call uses in-progress target

- **GIVEN** Rule A is executing with source S and discriminator "d1"
- **AND** Rule A calls Rule B during its execution
- **WHEN** Rule B calls `equivalentDiscriminated(S, targetType, "A", "d2")`
- **THEN** the framework SHALL return a cloned copy of Rule A's in-progress target
- **AND** the clone SHALL have a discriminated XMI ID based on "d2"

#### Scenario: Non-discriminated circular call returns null

- **GIVEN** Rule A is executing with source S
- **AND** Rule A calls Rule B during its execution
- **WHEN** Rule B calls `equivalent(S, targetType, "A")` without discriminator
- **THEN** the framework SHALL return null to break the cycle

### Requirement: Rule Ordinal Assignment

Each transformation rule SHALL be assigned a unique integer ordinal at registration time. The ordinal SHALL be used for O(1) array-indexed cache lookups instead of String-based map lookups.

#### Scenario: Ordinal assigned at registration

- **GIVEN** a TransformationRegistry with no registered rules
- **WHEN** a rule "RuleA" is registered
- **THEN** RuleA SHALL be assigned ordinal 0
- **WHEN** a rule "RuleB" is registered
- **THEN** RuleB SHALL be assigned ordinal 1

#### Scenario: Ordinal used for cache lookup

- **GIVEN** a transformation with 100 registered rules
- **WHEN** cache lookup is performed for a source and rule
- **THEN** lookup SHALL use array indexing with rule ordinal
- **AND** lookup time SHALL be O(1) regardless of rule count

### Requirement: Guard Evaluation Caching

The transformation framework SHALL cache guard evaluation results to avoid redundant evaluations. When a guard has been evaluated for a source-rule combination, subsequent lookups SHALL use the cached result.

#### Scenario: Guard result cached on first evaluation

- **GIVEN** a source S and rule R with a guard predicate
- **WHEN** guard is evaluated for S and R
- **THEN** the result SHALL be cached
- **WHEN** cache lookup is performed for S and R again
- **THEN** the cached guard result SHALL be used
- **AND** the guard predicate SHALL NOT be re-evaluated

#### Scenario: Rejected sources skip guard evaluation

- **GIVEN** a source S that was rejected by rule R's guard
- **WHEN** `equivalent(S, targetType, "R")` is called
- **THEN** the rejection cache SHALL be checked first
- **AND** the method SHALL return null without evaluating the guard

### Requirement: Guard Purity Contract

Guard predicates MUST be pure functions with no observable side effects. The framework MAY cache guard results and skip re-evaluation, so guards that depend on mutable state or produce side effects will exhibit undefined behavior.

#### Scenario: Guard evaluated once per source-rule combination

- **GIVEN** a rule R with a guard that increments a counter (violating purity)
- **WHEN** `equivalent(S, targetType, "R")` is called twice for the same source S
- **THEN** the guard MAY be evaluated only once (cached)
- **AND** the counter MAY be incremented only once (not twice)

#### Scenario: Guard depending on transformation phase fails

- **GIVEN** a rule R with a guard that checks `ctx.getCurrentPhase() == GREEDY`
- **WHEN** guard is evaluated during greedy phase and cached
- **AND** `equivalent(S, targetType, "R")` is called during lazy phase
- **THEN** the cached result (from greedy phase) MAY be returned
- **AND** the guard SHALL NOT be re-evaluated with updated phase

### Requirement: Deterministic Transformation Output

The transformation framework SHALL produce byte-identical XMI output for the same input model across multiple executions. Rule ordinal assignment, cache iteration order, and all internal state SHALL be deterministic.

#### Scenario: Same input produces identical XMI output

- **GIVEN** an input model M
- **WHEN** transformation is executed twice with identical configuration
- **THEN** the XMI output SHALL be byte-identical
- **AND** all XMI IDs SHALL be identical

#### Scenario: Registration order does not affect output

- **GIVEN** rules A, B, C registered in order A, B, C
- **AND** transformation produces output O1
- **WHEN** rules are registered in order C, A, B
- **AND** transformation is executed again
- **THEN** the output SHALL be identical to O1

### Requirement: In-Progress Target Cleanup

In-progress targets used for circular dependency handling SHALL be cleared when rule execution completes, whether successful or exceptional. Stale in-progress targets SHALL NOT be visible to subsequent transformations.

#### Scenario: In-progress cleared on successful completion

- **GIVEN** Rule A marks target T as in-progress
- **WHEN** Rule A completes successfully
- **THEN** T SHALL be moved from in-progress to completed cache
- **AND** in-progress tracking for (source, A) SHALL be cleared

#### Scenario: In-progress cleared on exception

- **GIVEN** Rule A marks target T as in-progress
- **WHEN** Rule A throws an exception during execution
- **THEN** in-progress tracking for (source, A) SHALL be cleared
- **AND** T SHALL NOT remain in any cache

#### Scenario: Separate transformations have isolated state

- **GIVEN** Transformation T1 executes and marks targets as in-progress
- **WHEN** Transformation T2 starts on a different executor
- **THEN** T2 SHALL NOT see any in-progress state from T1
- **AND** T2 SHALL have fresh cache state
