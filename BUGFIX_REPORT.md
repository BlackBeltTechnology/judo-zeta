# Zeta Framework Bug Fix Report: ElementResolutionCache Type Lookup

## Bug Summary

The `ElementResolutionCache.getEquivalent()` method fails to find cached targets when the requested type is a supertype of the actual cached target type.

## Affected Component

`hu.blackbelt.judo.zeta.transformation.core.ElementResolutionCache`

## Problem Description

### Scenario

1. A transformation rule creates a target of type `EDataType` and caches it via `addMapping(source, ruleName, target, isPrimary)`
2. The cache stores the target under the key derived from `target.eClass().getName()` which returns `"EDataType"`
3. Later, another rule calls `ctx.equivalent(source, EClassifier.class)` to retrieve the cached target
4. The lookup uses `getTypeName(EClassifier.class)` which returns `"EClassifier"`
5. Since `"EDataType" != "EClassifier"`, the cache lookup fails even though `EDataType` extends `EClassifier`

### Consequence

When `getEquivalent()` returns `null`, the `TransformationContext.equivalent()` method attempts to re-execute matching rules, causing:
- **Duplicate target creation**: The same source element gets transformed multiple times
- **Duplicate elements in output model**: Multiple identical elements are created
- **Incorrect transformation results**: The output model contains extra elements

### Code Flow

```
TransformationContext.equivalent(source, EClassifier.class)
  -> ElementResolutionCache.getEquivalent(source, EClassifier.class)
      -> typeName = getTypeName(EClassifier.class) = "EClassifier"
      -> typeCache.get(source).get("EClassifier") = null  // MISS! Cache has "EDataType"
  -> Cache miss triggers rule re-execution
  -> Duplicate target created
```

## Root Cause

The `getEquivalent()` method only performs exact string matching on type names:

```java
public <T extends EObject> T getEquivalent(EObject source, Class<T> targetType) {
    String typeName = getTypeName(targetType);  // "EClassifier"
    
    // ... primary cache check with exact match ...
    
    Map<String, List<EObject>> typeMap = typeCache.get(source);
    if (typeMap != null) {
        List<EObject> targets = typeMap.get(typeName);  // Looks for "EClassifier", cache has "EDataType"
        if (targets != null && !targets.isEmpty()) {
            return targetType.cast(targets.get(0));
        }
    }
    return null;
}
```

It does not consider type inheritance where `EDataType extends EClassifier`.

## Fix Applied

Modified `getEquivalent()` to first try exact type match, then fall back to checking type assignability:

```java
public <T extends EObject> T getEquivalent(EObject source, Class<T> targetType) {
    String typeName = getTypeName(targetType);

    // Check primary cache first (exact match)
    Map<String, EObject> primaryMap = primaryCache.get(source);
    if (primaryMap != null) {
        EObject primary = primaryMap.get(typeName);
        if (primary != null) {
            return targetType.cast(primary);
        }
    }

    // Check for assignable types in type cache (e.g., EDataType when requesting EClassifier)
    // This handles cases where EDataType is cached but EClassifier is requested
    Map<String, List<EObject>> typeMap = typeCache.get(source);
    if (typeMap != null) {
        // First try exact type match
        List<EObject> exactTargets = typeMap.get(typeName);
        if (exactTargets != null && !exactTargets.isEmpty()) {
            return targetType.cast(exactTargets.get(0));
        }
        // Fall back to assignable type check
        for (List<EObject> targets : typeMap.values()) {
            for (EObject target : targets) {
                if (targetType.isInstance(target)) {
                    return targetType.cast(target);
                }
            }
        }
    }

    return null;
}
```

## Key Change

Added assignability check using `targetType.isInstance(target)` which correctly handles:
- `EClassifier.class.isInstance(eDataTypeInstance)` returns `true`
- `EClass.class.isInstance(eClassInstance)` returns `true`
- Any supertype/interface lookup finding a compatible subtype in the cache

## Test Case

```java
// Setup: Create and cache an EDataType
EDataType dataType = EcoreFactory.eINSTANCE.createEDataType();
cache.addMapping(source, "CreateDataType", dataType, true);

// Before fix: Returns null (cache miss)
// After fix: Returns dataType (correct)
EClassifier result = cache.getEquivalent(source, EClassifier.class);
assertNotNull(result);
assertTrue(result instanceof EDataType);
```

## Impact

This fix ensures that `equivalent()` calls correctly find cached targets when:
- The requested type is a supertype of the cached target
- The requested type is an interface implemented by the cached target
- Any inheritance/interface relationship exists between requested and cached types

## Files Modified

- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/ElementResolutionCache.java`

## Recommendation

Consider adding similar assignability checks to:
- `getEquivalents()` method for consistency
- Primary cache lookup (currently only exact match)
- Add unit tests for type hierarchy scenarios
