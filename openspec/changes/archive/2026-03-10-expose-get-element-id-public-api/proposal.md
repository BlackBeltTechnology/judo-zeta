# Proposal: Expose getElementId() as Public API

**Change ID:** `expose-get-element-id-public-api`
**Date:** 2026-01-17
**Status:** PROPOSED
**Priority:** Medium
**Affects:** All ZETA-based transformations that build discriminators from target elements

---

## Executive Summary

The ZETA framework's deferred ID mechanism (`pendingXmiIds`) causes `XMIResource.getID()` to return `null` for target elements whose IDs haven't been committed yet. This breaks discriminator construction when discriminators include target element IDs.

We propose making the existing private `getElementId(EObject)` method public, providing a single reliable way to get element IDs regardless of commit state.

---

## Problem Statement

### Current Behavior

ZETA uses deferred ID assignment for target elements:

```
1. ctx.setElementId(element, id)  →  Stores ID in pendingXmiIds map
2. [transformation continues...]
3. applyPendingXmiIds()           →  Applies IDs to XMI resource (commit phase)
```

When transformation code needs to get an element's ID **before commit**, the standard EMF approach fails:

```java
// This returns NULL for deferred elements!
Resource resource = element.eResource();
if (resource instanceof XMIResource) {
    return ((XMIResource) resource).getID(element);  // → null
}
```

### Impact

This causes problems when building discriminators from target element IDs:

```java
// In mapAttributeType() - targetClassType is a UI element
String discriminator = getElementId(targetClassType);  // → null!
ctx.equivalentDiscriminated(attr, AttributeType.class, CLONE_ATTRIBUTE_TYPE, discriminator);
// Creates element with null discriminator → cache misses → orphans
```

### Current Workaround

Transformation projects (like esm2ui) must implement their own workaround:

```java
public static String getId(EObject element, TransformationContext ctx) {
    if (element == null) return null;

    // First check pending IDs for deferred elements
    if (ctx != null) {
        String pendingId = ctx.getPendingXmiId(element);
        if (pendingId != null) {
            return pendingId;
        }
    }

    // Fall back to XMI resource check
    Resource resource = element.eResource();
    if (resource instanceof XMIResource) {
        return ((XMIResource) resource).getID(element);
    }
    return null;
}
```

**Problems with this workaround:**
1. Every ZETA-based transformation must implement this pattern
2. Easy to forget and use the wrong method
3. Inconsistent API - some code uses `getId(element)`, some uses `getId(element, ctx)`
4. The logic duplicates what ZETA already has internally (`TransformationContext.getElementId()`)

---

## Proposed Solution

### Make getElementId() Public

`TransformationContext` already has a private `getElementId()` method that handles this correctly:

```java
// Current (private) - TransformationContext.java line 2977
private String getElementId(EObject element) {
    // First check pending IDs for staged elements
    String pendingId = pendingXmiIds.get(element);
    if (pendingId != null) {
        return pendingId;
    }

    // Check resource for committed elements
    Resource resource = element.eResource();
    if (resource != null) {
        String id = resource.getURIFragment(element);
        if (id != null && !id.startsWith("/")) {
            return id;
        }
    }

    // Fallback: use model element identifier attribute
    EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
    if (idFeature != null) {
        Object idValue = element.eGet(idFeature);
        if (idValue != null) {
            return idValue.toString();
        }
    }

    // Generate UUID and store for staged elements
    String generatedId = "_" + UUID.randomUUID().toString().replace("-", "");
    if (stagingEnabled.get()) {
        pendingXmiIds.put(element, generatedId);
        pendingXmiIdIndex.put(generatedId, element);
    }
    return generatedId;
}
```

**Proposed Change:**

```java
// Change visibility from private to public
public String getElementId(EObject element) {
    // ... same implementation
}
```

**Benefits:**
- Zero new code - just change visibility modifier
- Consistent with existing ZETA API
- Single source of truth for element ID resolution
- Handles all edge cases (pending, committed, generated)
- Thread-safe (uses `ConcurrentHashMap`)

---

## API Behavior

### ID Resolution Order

The `getElementId()` method resolves IDs in this order:

1. **Pending IDs**: Check `pendingXmiIds` map first (deferred elements)
2. **Committed IDs**: Fall back to `resource.getURIFragment()` for committed elements
3. **Structural Feature**: Check for "id" structural feature on element
4. **Generated UUID**: Generate and cache a UUID if none exists

### Null Handling

The method generates an ID if none exists (when staging is enabled). This matches existing ZETA behavior and ensures callers never receive null for valid elements.

### Thread Safety

The method is thread-safe:
- `pendingXmiIds` is a `ConcurrentHashMap`
- UUID generation is atomic per element (cached immediately)

---

## Test Coverage

TDD tests have been created in `GetElementIdPublicApiTest.java`:

| Test Category | Test Cases |
|---------------|------------|
| Pending ID Resolution | Returns pending ID, works before resource addition, takes precedence |
| Committed ID Resolution | Returns XMI ID, falls back to URI fragment |
| Generated ID Fallback | Generates UUID, consistent across calls, unique per element |
| Null/Edge Cases | Handles null element, uses "id" feature |
| Integration | Works during transformation, with discriminated clones |
| Thread Safety | Concurrent access returns same ID |
| Discriminator Construction | Enables correct discriminator building pattern |

---

## Migration Path

### Before (Workaround Pattern)

```java
// In transformation code
String discriminator = IdExtensions.getId(targetClassType, ctx);
```

### After (Public API)

```java
// Direct API call
String discriminator = ctx.getElementId(targetClassType);
```

### Deprecation

Once this change is implemented, transformation projects should deprecate their workarounds:

```java
/**
 * @deprecated Use {@code ctx.getElementId(element)} instead.
 */
@Deprecated
public static String getId(EObject element, TransformationContext ctx) {
    if (ctx != null) {
        return ctx.getElementId(element);
    }
    return getId(element);
}
```

---

## Acceptance Criteria

1. `TransformationContext.getElementId(EObject)` is public
2. Method checks `pendingXmiIds` before XMI resource
3. Method generates UUID for elements without IDs (current behavior)
4. All TDD tests pass
5. Javadoc documents the deferred ID handling
6. No breaking changes to existing API

---

## Files to Modify

| File | Change |
|------|--------|
| `TransformationContext.java:2977` | Change `private` to `public` |
| `TransformationContext.java:2971-2976` | Update Javadoc |

---

## References

- **TDD Test File:** `GetElementIdPublicApiTest.java`
- **Private method location:** `TransformationContext.java:2977-3009`
- **Pending ID storage:** `TransformationContext.java:150` (`pendingXmiIds`)
- **Related spec:** `rule-execution` - XMI ID handling requirements
