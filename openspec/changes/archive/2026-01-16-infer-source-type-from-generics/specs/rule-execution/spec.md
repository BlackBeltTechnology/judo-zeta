## ADDED Requirements

### Requirement: Automatic Source Type Inference from Generic Parameters

The `TransformationRegistry` SHALL automatically infer the source type from the first generic parameter of `TransformFunction<S, T>` when no explicit source type is specified via `@Transform` annotation or `sourceTypes` attribute.

This enables type-based rule filtering without requiring annotation changes to existing rules.

#### Scenario: Source type inferred from TransformFunction generic parameter

- **GIVEN** a transformation rule method returning `TransformFunction<EntityType, EClass>`
- **AND** no `@Transform` annotation is present
- **AND** no `sourceTypes` attribute is specified
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `EntityType`
- **AND** the rule SHALL only be evaluated for elements assignable to `EntityType`

#### Scenario: Explicit @Transform annotation takes priority over inference

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** the method has `@Transform(type = SpecificType.class)`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `SpecificType` (from annotation)
- **AND** the generic parameter `EObject` SHALL be ignored

#### Scenario: Explicit sourceTypes attribute takes priority over inference

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** the annotation specifies `@TransformRule(sourceTypes = {SpecificType.class})`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `SpecificType` (from attribute)
- **AND** the generic parameter `EObject` SHALL be ignored

#### Scenario: Raw EObject generic parameter falls back to default

- **GIVEN** a transformation rule method returning `TransformFunction<EObject, EClass>`
- **AND** no explicit source type is specified
- **AND** the `@TransformationContext` has `source = DefaultSource.class`
- **WHEN** the rule is registered
- **THEN** the rule's source type SHALL be `DefaultSource` (from class-level default)
- **BECAUSE** inferring `EObject` provides no filtering benefit

### Requirement: Greedy Rules with Inferred Supertype Match All Subtypes

When a `@Greedy` rule's source type is inferred as a supertype, the type-based filtering SHALL include all elements of that type and its subtypes, matching the behavior of the `appliesTo()` method.

#### Scenario: Greedy rule with inferred supertype matches subtype elements

- **GIVEN** a transformation rule returning `TransformFunction<NamedElement, EClass>`
- **AND** the rule is annotated with `@Greedy`
- **AND** `EntityType` is a subtype of `NamedElement`
- **WHEN** transformation processes an `EntityType` element
- **THEN** the rule SHALL be included in the applicable rules for that element
- **AND** the rule's `appliesTo()` method SHALL return `true`

#### Scenario: Non-greedy rule with inferred type matches only exact type

- **GIVEN** a transformation rule returning `TransformFunction<EntityType, EClass>`
- **AND** the rule is NOT annotated with `@Greedy`
- **WHEN** transformation processes an `EntityTypeSubclass` element (subtype)
- **THEN** the rule SHALL NOT match the element
- **BECAUSE** non-greedy rules use type-of semantics (exact match only)

### Requirement: Behavioral Equivalence with appliesTo()

The type-based rule filtering with inferred source types MUST produce identical results to the runtime `appliesTo()` check. No behavioral changes are permitted.

#### Scenario: Type filtering matches appliesTo for all element types

- **GIVEN** a set of registered rules with inferred source types
- **AND** a collection of elements with various types
- **FOR** each rule
- **THEN** the set of elements passing type filtering SHALL equal the set of elements where `appliesTo()` returns `true`
- **AND** no element that would pass `appliesTo()` is excluded by type filtering
- **AND** no element that would fail `appliesTo()` is included by type filtering
