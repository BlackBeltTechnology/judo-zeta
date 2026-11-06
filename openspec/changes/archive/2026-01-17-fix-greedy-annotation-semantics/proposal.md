# Proposal: Fix Greedy Annotation Semantics to Match Epsilon ETL

**Change ID:** fix-greedy-annotation-semantics
**Status:** REJECTED
**Created:** 2026-01-07
**Updated:** 2026-01-17

## Summary

~~The `@Greedy` annotation and `appliesTo()` method in `TransformRuleDescriptor` have incorrect semantics that don't match Epsilon ETL behavior.~~

**UPDATE (2026-01-17): This proposal is REJECTED. After careful analysis, the current implementation IS CORRECT and matches ETL semantics. The proposal was based on a misinterpretation of terminology.**

## Analysis: Current Implementation is CORRECT

### Current Behavior (VERIFIED CORRECT)

| Annotation | Matching Logic | Behavior |
|------------|----------------|----------|
| Non-greedy (default) | Exact type match | Rule(A) matches only A, not subtypes |
| @Greedy | Subtype match (`isInstance()`) | Rule(A) matches A and all subtypes of A |

### ETL Terminology (Correctly Understood)

In standard OOP and ETL:
- **"type-of"** = exact type match (element IS this type)
- **"kind-of"** = is-a relationship (element IS-A subtype of this type)

### Evidence Supporting Current Implementation

1. **`@Greedy` annotation Javadoc** (zeta-annotations/Greedy.java):
   ```
   Without @Greedy (type-of semantics - default):
   Rule matches ONLY elements whose type is exactly the declared source type.

   With @Greedy (kind-of semantics):
   Rule matches elements whose type is the declared source type OR any subtype.
   ```

2. **ETLSemanticsTest** (verified tests pass):
   - `NonGreedyTypeMatchingTests`: Non-greedy rule for EClass does NOT match EDataType ✓
   - `GreedyTypeMatchingTests`: Greedy rule for EClassifier matches BOTH EClass AND EDataType ✓

3. **All 740 tests pass** with current implementation

### Why This Proposal Was Wrong

The original proposal stated:
> "type-of" = Element's type is source type **or subtype** (B is-a A)
> "kind-of" = Element is exactly the source type (B is exactly B)

This is **INVERTED** from standard OOP terminology:
- In OOP, "is-a" or "kind-of" means inheritance relationship (subtype matching)
- In OOP, "type-of" means exact type identity

The proposal confused the terminology and proposed to invert correct behavior.

## Current Code Analysis

```java
// TransformRuleDescriptor.appliesTo() - CORRECT IMPLEMENTATION
public boolean appliesTo(EObject source) {
    if (isGreedy) {
        // Greedy: matches sourceType and all subtypes
        return sourceType.isInstance(source);  // CORRECT
    } else {
        // Non-greedy: matches ONLY the exact declared type
        // ... exact match logic ...  // CORRECT
    }
}
```

## Recommendation

**REJECT and ARCHIVE this proposal.**

The current implementation is correct and ETL-compatible:
- Non-greedy (default) = exact type match
- @Greedy = subtype match

### Optional Minor Improvement

The comment in `appliesTo()` could be clarified to avoid future confusion:

```java
if (isGreedy) {
    // @Greedy: Matches this source type AND all subtypes (polymorphic matching)
    // Example: Rule(EClassifier) matches EClass, EDataType, EEnum, etc.
    return sourceType.isInstance(source);
} else {
    // Default (non-greedy): Matches ONLY the exact declared type (monomorphic matching)
    // Example: Rule(EClass) matches only EClass, not EDataType
    ...
}
```

## Risks

None - no code changes needed.

## Related Capabilities

- `activity-based-greedy` - Depends on greedy semantics (currently correct)
- `rule-execution` - Core rule matching behavior (currently correct)
