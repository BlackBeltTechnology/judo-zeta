# rule-matching Specification

## Purpose

Defines how transformation rules are matched to source elements, ensuring Epsilon ETL-compatible type matching semantics. This specification corrects the @Greedy annotation semantics to properly implement type-of and kind-of relationships.

## Requirements

## ADDED Requirements

### Requirement: Non-Greedy Rules Use Type-of Semantics

Non-greedy rules (the default) SHALL match source elements where the element's type has a **type-of relationship** with the rule's declared source type. This means the element's type is either:
- Exactly the declared source type, OR
- A subtype of the declared source type

#### Scenario: Non-greedy rule for superclass matches subclass instance

**Given** a transformation with:
- `RuleA` with `@Transform(source = A.class, target = TargetA.class)`
- `RuleB` with `@Transform(source = B.class, target = TargetB.class)` (where `B extends A`)
- A source element of type `B`

**When** the transformation is executed

**Then** `RuleA` is applicable to the `B` element
**And** `RuleB` is applicable to the `B` element
**Because** `B` has a type-of relationship with `A` (B is-a A)

#### Scenario: Non-greedy rule for interface matches implementing class

**Given** a transformation with a rule declared for `EClassifier` interface
**And** a source element of type `EClassImpl`

**When** `appliesTo()` is called on the element

**Then** the rule is applicable
**Because** `EClassImpl` implements `EClassifier`

#### Scenario: Non-greedy rule for abstract class matches concrete subclass

**Given** a transformation with:
- Rule declared for abstract class `Vehicle`
- Source element of type `Car` which extends `Vehicle`

**When** the transformation is executed

**Then** the rule is applicable to the `Car` element
**Because** `Car` is-a `Vehicle`

---

### Requirement: Greedy Rules Use Kind-of Semantics

Greedy rules (annotated with `@Greedy`) SHALL match source elements where the element's type has a **kind-of relationship** with the rule's declared source type. This means the element's type must be exactly the declared source type (not a subtype).

#### Scenario: Greedy rule for superclass does NOT match subclass

**Given** a transformation with:
- `GreedyRuleA` with `@Greedy @Transform(source = A.class, target = TargetA.class)`
- A source element of type `B` (where `B extends A`)

**When** the transformation is executed

**Then** `GreedyRuleA` is NOT applicable to the `B` element
**Because** greedy rules use kind-of semantics (exact type match only)

#### Scenario: Greedy rule matches exact type

**Given** a transformation with:
- `GreedyRuleA` with `@Greedy @Transform(source = A.class, target = TargetA.class)`
- A source element of type `A`

**When** the transformation is executed

**Then** `GreedyRuleA` is applicable to the `A` element
**Because** the element's type exactly matches the declared source type

---

### Requirement: Lazy Rules Use Type-of Semantics

Lazy rules (annotated with `@Lazy`) SHALL use the same matching semantics as non-greedy rules (type-of relationship, matching subtypes).

#### Scenario: Lazy rule for superclass matches subclass

**Given** a transformation with:
- `LazyRuleA` with `@Lazy @Transform(source = A.class, target = TargetA.class)`
- A source element of type `B` (where `B extends A`)

**When** `equivalent(source, "LazyRuleA")` is called

**Then** the rule is applicable
**Because** lazy rules use type-of semantics

---

### Requirement: Implementation Uses Correct Java Semantics

The `appliesTo()` method in `TransformRuleDescriptor` SHALL use the correct Java reflection methods:

| Annotation | Java Method | Semantics |
|------------|-------------|-----------|
| Non-greedy | `sourceType.isAssignableFrom(source.getClass())` | type-of |
| @Greedy | `sourceType.equals(source.getClass())` | kind-of |
| @Lazy | `sourceType.isAssignableFrom(source.getClass())` | type-of |

#### Scenario: Non-greedy appliesTo uses isAssignableFrom

**Given** a rule with source type `A.class`
**And** a source element of type `B.class` where `B extends A`

**When** `appliesTo(element)` is called

**Then** the method returns `true`
**Because** `A.isAssignableFrom(B) == true`

#### Scenario: Greedy appliesTo uses equals

**Given** a greedy rule with source type `A.class`
**And** a source element of type `B.class` where `B extends A`

**When** `appliesTo(element)` is called

**Then** the method returns `false`
**Because** `A.equals(B) == false`

---

### Requirement: Annotation Documentation Correct

The `@Greedy` annotation Javadoc SHALL correctly describe the semantics:

#### Scenario: Greedy Javadoc describes kind-of semantics

**Given** the `@Greedy` annotation definition

**When** the Javadoc is read

**Then** it states that greedy rules use kind-of semantics (exact type match)
**And** it states that non-greedy rules use type-of semantics (subtype match)

---

## Cross-Reference

- Related capability: `activity-based-greedy` - Depends on correct greedy semantics for lazy rule triggering
