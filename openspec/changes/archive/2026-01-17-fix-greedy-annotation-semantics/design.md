# Design: Fix Greedy Annotation Semantics

## Background

Epsilon ETL and Zeta transformation frameworks use different terminology for rule matching semantics. This design clarifies the correct semantics and provides a fix for Zeta's incorrect implementation.

## ETL Semantics Reference

### From Epsilon ETL Documentation

> "To be applicable on a particular element, the element must have a **type-of relationship** with the type defined in the rule's sourceParameter (**or a kind-of relationship if the rule is annotated as @greedy**)"

| Relationship | ETL Meaning | Java Equivalent | Example |
|--------------|-------------|-----------------|---------|
| **type-of** | Element is source type **or subtype** | `sourceType.isAssignableFrom(elementType)` | `Rule(A)` matches `B` if `B extends A` |
| **kind-of** | Element is exactly the source type | `sourceType.equals(elementType)` | `Rule(A)` matches only `A`, not `B` |

### ETL Rule Matching Behavior

```etl
// Rule for A - matches A AND all subclasses
rule transformA(A a) { ... }

// Rule for B - matches B only (unless B extends A with @greedy)
rule transformB(B b) { ... }
```

When transforming element of type `B`:
- `transformA` is applicable (B is-a A, type-of relationship)
- `transformB` is applicable (B is exactly B)

## Current Zeta Implementation (INCORRECT)

```java
public boolean appliesTo(EObject source) {
    if (isGreedy || isLazy) {
        // Uses isInstance() - matches subtypes (WRONG for ETL kind-of)
        return sourceType.isInstance(source);
    } else {
        // Uses equals() - exact match only (WRONG for ETL type-of)
        return sourceType.equals(source.getClass());
    }
}
```

## Proposed Zeta Implementation (CORRECT)

```java
public boolean appliesTo(EObject source) {
    if (isGreedy || isLazy) {
        // Kind-of semantics: exact type match only (matches ETL @greedy)
        return sourceType.equals(source.getClass());
    } else {
        // Type-of semantics: matches source type and all subtypes
        return sourceType.isAssignableFrom(source.getClass());
    }
}
```

## Semantic Comparison Table

| Framework | Annotation | Source B for Rule(A) | Source B for Rule(B) |
|-----------|------------|----------------------|----------------------|
| **ETL** | Default | ✓ matches | ✓ matches |
| **ETL** | @greedy | ✗ skipped | ✓ matches |
| **Zeta (current)** | Default | ✗ skipped | ✓ matches |
| **Zeta (current)** | @greedy | ✓ matches | ✓ matches |
| **Zeta (proposed)** | Default | ✓ matches | ✓ matches |
| **Zeta (proposed)** | @greedy | ✗ skipped | ✓ matches |

## Migration Strategy

### Option 1: Breaking Change (Recommended)
- Change semantics immediately
- Provide migration guide
- Deprecation warning in logs

### Option 2: Deprecation with Dual Semantics
- Add new methods with correct semantics
- Mark old methods as deprecated
- Users migrate at their pace

**Recommendation**: Option 1 with 2-version deprecation period.

## Affected Components

1. `TransformRuleDescriptor.appliesTo()` - core matching logic
2. `Greedy.java` annotation - Javadoc correction
3. `TransformationRegistry.computeRulesForSource()` - rule discovery
4. All transformation tests - may need updates

## Testing Strategy

### New Test Cases

```java
@Test
void ruleForSuperclassMatchesSubclass() {
    // Given: Rule1 for A.class, Rule2 for B.class (B extends A)
    // When: Transforming B instance
    // Then: Both rules should be applicable (type-of semantics)
}

@Test
void greedyRuleForSuperclassDoesNotMatchSubclass() {
    // Given: Greedy Rule1 for A.class, Rule2 for B.class
    // When: Transforming B instance
    // Then: Only Rule2 is applicable (kind-of semantics)
}
```

## Open Questions

1. Should we provide a migration flag to use old (incorrect) semantics?
2. How long should the deprecation period be?
3. Should we rename the annotation to be clearer?
