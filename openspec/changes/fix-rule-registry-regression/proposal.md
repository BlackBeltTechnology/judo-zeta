# Proposal: Fix Rule Registry Regression (Version 131508)

## Problem Statement

After the `optimize-rule-registry-performance` change (commit `0cd0bc0`), version 131508 exhibits regressions:

1. **eType: null (28+ occurrences)**: Operations have `eType: null` instead of expected return types
2. **Missing parameters**: Operations have 0 parameters instead of expected count
3. **Zero Metrics**: All transformation metrics show 0

## Root Cause: CONFIRMED

### Bug Location

`TransformationContext.equivalent(EObject source, Class<T> targetType)` at line 1057

### The Issue

The XMI ID lookup is **inside** the lazy rule loop:

```java
public <T extends EObject> T equivalent(EObject source, Class<T> targetType) {
    // Check cache first
    T cached = resolutionCache.getEquivalent(source, targetType);
    if (cached != null) return cached;

    // Try to find and execute a matching LAZY rule
    List<TransformRuleDescriptor> rules = transformationRegistry.getLazyRulesForType(source.getClass());

    for (TransformRuleDescriptor rule : rules) {  // ← ONLY ITERATES LAZY RULES
        // ...
        if (useStructuredIds) {
            // XMI ID lookup is HERE, inside the loop!
            T existingByXmiId = findByXmiId(structuredId, targetType);
            if (existingByXmiId != null) return existingByXmiId;
        }
        // ... execute lazy rule
    }

    return null;  // ← Returns null if no lazy rules!
}
```

When there are **no lazy rules** for the source type, the loop never executes, and:
- XMI ID lookup is **never performed**
- `equivalent()` returns `null`

### Failing Scenario (Confirmed by Test)

1. **Eager rule A** transforms `EOperation` → target `EOperation`
   - Sets `eType` via `ctx.equivalent(returnType, EClassifier.class)`
2. **Eager rule B** transforms `EDataType` → target `EDataType` (the return type)
3. **NO lazy rule** exists for `EDataType`

In parallel execution:
1. Thread 1 starts Rule A for `EOperation`
2. Thread 2 starts Rule B for `EDataType`
3. Thread 1 calls `equivalent(returnType, EDataType.class)`
4. `equivalent()` checks resolution cache - **MISS** (Thread 2 not done)
5. `equivalent()` looks for LAZY rules for `EDataType` - **NONE EXIST**
6. The lazy rule loop **never executes**
7. XMI ID lookup is **skipped**
8. `equivalent()` returns `null` → **eType: null!**

## Test Results

### Phase 0.5: Separate Eager Rules (Bug Reproduction)

| Test | Result | Details |
|------|--------|---------|
| `equivalentWithSeparateEagerRulesNoLazyRule` | **FAIL** | equivalent() returns null |
| `separateEagerRulesConsistent` (10 runs) | **FAIL** | All 10 runs return null |
| `manyOperationsWithSeparateReturnTypes` | **FAIL** | 20/20 null results |

This confirms the root cause: **XMI ID lookup is inside the lazy rule loop.**

### Previous Tests (Pass but don't trigger the bug)

The Phase 0.1-0.4 tests pass because they use scenarios where:
- Both elements are transformed by the **same greedy rule**
- Or there **is a lazy rule** for the looked-up type

## Proposed Fix

Move the XMI ID lookup **before** the lazy rule loop:

```java
public <T extends EObject> T equivalent(EObject source, Class<T> targetType) {
    // Check cache first
    T cached = resolutionCache.getEquivalent(source, targetType);
    if (cached != null) return cached;

    // NEW: Check XMI ID BEFORE lazy rule loop
    // This handles targets created by EAGER rules
    if (useStructuredIds && transformationRegistry != null) {
        // Try all matching rules (eager or lazy) for XMI ID lookup
        for (TransformRuleDescriptor rule : transformationRegistry.getRulesForSource(source)) {
            if (targetType.isAssignableFrom(rule.getTargetType())) {
                String structuredId = generateStructuredId(source, rule.getName());
                T existingByXmiId = findByXmiId(structuredId, targetType);
                if (existingByXmiId != null) {
                    resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
                    return existingByXmiId;
                }
            }
        }
    }

    // Then try lazy rules (existing logic)
    List<TransformRuleDescriptor> rules = transformationRegistry.getLazyRulesForType(source.getClass());
    for (TransformRuleDescriptor rule : rules) {
        // ... existing lazy rule execution logic
    }

    return null;
}
```

## Impact

- **Fixes**: eType: null when target created by separate eager rule
- **Fixes**: Missing parameters (same root cause)
- **Performance**: Minimal - XMI ID lookup is O(1) via `pendingXmiIdIndex`
- **Backwards compatible**: No behavior change for existing working scenarios

## Files to Modify

1. `TransformationContext.java` - Move XMI ID lookup before lazy rule loop
2. `RuleRegistryRegressionTest.java` - Tests already created (Phase 0.5)

## Validation

1. Phase 0.5 tests should pass after fix
2. All 500+ existing tests should continue to pass
3. Production transformation should produce correct eType values
