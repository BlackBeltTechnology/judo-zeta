# Proposal: Fix Greedy Annotation Semantics to Match Epsilon ETL

## Summary

The `@Greedy` annotation and `appliesTo()` method in `TransformRuleDescriptor` have incorrect semantics that don't match Epsilon ETL behavior. The current implementation uses `equals()` for non-greedy rules (should use `isAssignableFrom()` for type-of semantics), and the annotation documentation is inverted.

## Problem Statement

### Current Zeta Behavior (INCORRECT)

| Annotation | Matching Logic | Example: Rule(A) for source B |
|------------|----------------|-------------------------------|
| Non-greedy | `equals()` - exact match | ✗ B is skipped |
| Greedy | `isInstance()` - subtype match | ✓ B matches |

### ETL Documentation (CORRECT)

> "To be applicable on a particular element, the element must have a **type-of relationship** with the type defined in the rule's sourceParameter (**or a kind-of relationship if the rule is annotated as @greedy**)"

| Relationship | Meaning |
|--------------|---------|
| **type-of** | Element's type is source type **or subtype** (B is-a A) |
| **kind-of** | Element is exactly the source type (B is exactly B) |

### ETL Expected Behavior

| Annotation | Matching Logic | Example: Rule(A) for source B |
|------------|----------------|-------------------------------|
| Default (type-of) | `isAssignableFrom()` - subtype match | ✓ B matches (B is-a A) |
| @greedy (kind-of) | `equals()` - exact match | ✗ B is skipped |

## Root Cause

1. Line 377-378 in `TransformRuleDescriptor.appliesTo()` uses `equals()` instead of `isAssignableFrom()` for non-greedy rules
2. Line 350 uses `isInstance()` for greedy rules (should be exact match)
3. Annotation Javadoc in `Greedy.java` has inverted definitions

## Impact

- Zeta transformations don't match ETL behavior for class inheritance scenarios
- Users expecting ETL semantics will get unexpected results
- Rules for superclasses don't match subclass instances (opposite of ETL)

## Proposed Solution

### 1. Fix `appliesTo()` Method

```java
// Non-greedy (default): type-of semantics - matches sourceType and all subtypes
return sourceType.isAssignableFrom(source.getClass());

// Greedy: kind-of semantics - matches ONLY the exact declared type
return sourceType.equals(source.getClass());
```

### 2. Fix Interface Handling

Use `isAssignableFrom()` semantics for both concrete classes and interfaces.

### 3. Fix Annotation Documentation

Update `Greedy.java` javadoc to correctly describe the semantics.

### 4. Immediate Change

Change immediately with updated documentation and tests (no deprecation period).

## Related Capabilities

- `activity-based-greedy` - Depends on correct greedy semantics
- `rule-execution` - Core rule matching behavior

## Risks

- **Breaking Change**: Users may rely on current behavior
- **Migration Path**: Need deprecation period and clear migration guide
