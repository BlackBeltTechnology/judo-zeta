# Proposal: fix-xmi-id-context-in-nested-lazy-rules

## Problem Statement

When @Lazy @Greedy rules are triggered via `ctx.equivalent()` from within a parent rule, XMI IDs may be assigned incorrectly. The symptom is:

- **Observed**: XMI ID `GenericUser/(esm/_xxx)/RelationFeatureView` is associated with an Action object
- **Expected**: This XMI ID should be associated with a PageDefinition object
- **Scale**: 104 type mismatches reported

### Reproduction Scenario

In `RelationFeatureViewTransformations.java`:
1. `RelationFeatureView` rule (@Greedy) creates a PageDefinition with ID `...RelationFeatureView`
2. Within the same rule, `ctx.equivalent(source, "RelationFeatureViewBackAction")` is called
3. This triggers `RelationFeatureViewBackAction` rule (@Lazy @Greedy) which creates an Action
4. The Action's auto-generated XMI ID uses the **parent rule's name** instead of its own

## Root Cause Analysis

### Finding 1: Stale pendingXmiIdIndex Entries

When `setElementId()` is called to override an auto-generated ID with a custom ID, the old index entry is NOT removed:

```java
public void setElementId(EObject element, String id) {
    pendingXmiIds.put(element, id);        // Overwrites: element -> newId
    pendingXmiIdIndex.put(id, element);    // Adds: newId -> element
    // BUG: Old entry (oldId -> element) is NOT removed!
}
```

This causes:
1. Action created with auto-ID `...RelationFeatureView` (wrong)
2. Index: `[...RelationFeatureView] -> Action`
3. Custom ID set: `...RelationFeatureViewBackAction`
4. Index: `[...RelationFeatureViewBackAction] -> Action`
5. **Stale entry remains**: `[...RelationFeatureView] -> Action`

When `findByXmiId("...RelationFeatureView")` is called later, it returns Action instead of PageDefinition.

### Finding 2: Potential Context Pollution

The `currentExecutingRule` ThreadLocal may not be correctly set in some code path, causing auto-generated IDs to use the parent rule's name instead of the child rule's name. The exact code path needs investigation via diagnostic logging.

## Proposed Fix

### Fix 1: Clean up stale index entries in setElementId()

```java
public void setElementId(EObject element, String id) {
    // Remove old index entry if ID is changing
    String oldId = pendingXmiIds.get(element);
    if (oldId != null && !oldId.equals(id)) {
        pendingXmiIdIndex.remove(oldId);
    }

    pendingXmiIds.put(element, id);
    pendingXmiIdIndex.put(id, element);
    // ... rest of method
}
```

### Fix 2: Add diagnostic assertion

Add assertion in `createTargetInPackage()` to detect context pollution early:

```java
if (rule != null && !rule.getTargetType().isAssignableFrom(targetType)) {
    LOG.warn("Creating {} but currentExecutingRule is {} (expects {})",
        targetType.getSimpleName(), rule.getName(), rule.getTargetType().getSimpleName());
}
```

## Impact Assessment

- **Risk**: Low - the index cleanup is a safe optimization
- **Scope**: Affects all transformations with custom setElementId() calls
- **Backward Compatibility**: No behavioral change for correct transformations; fixes incorrect ones

## Test Plan

1. Create test reproducing nested @Lazy @Greedy rule execution
2. Verify XMI ID lookup returns correct element types
3. Verify no stale index entries remain after ID changes
