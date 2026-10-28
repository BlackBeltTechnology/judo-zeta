# Design: fix-xmi-id-context-in-nested-lazy-rules

## Analysis of RelationFeatureViewTransformations.java

### Observed Pattern

The transformation file shows this structure:

```java
// Main rule: @Greedy, creates PageDefinition
@TransformRule(name = "RelationFeatureView")
@Greedy
public TransformFunction<RelationFeature, PageDefinition> relationFeatureView() {
    return (source, ctx) -> {
        PageDefinition target = ctx.createTarget(PageDefinition.class);  // Auto-ID generated
        String id = actorType.getName() + "/(esm/" + getId(source) + ")/RelationFeatureView";
        ctx.setElementId(target, id);  // Custom ID set

        // Trigger child rules via equivalent()
        Action backAction = ctx.equivalent(source, "RelationFeatureViewBackAction");
        target.getActions().add(backAction);
        // ... more equivalent() calls
        return target;
    };
}

// Child rule: @Lazy @Greedy, creates Action
@TransformRule(name = "RelationFeatureViewBackAction")
@Lazy @Greedy
public TransformFunction<RelationFeature, Action> relationFeatureViewBackAction() {
    return (source, ctx) -> {
        Action action = ctx.createTarget(Action.class);  // Auto-ID generated here!
        String id = actorType.getName() + "/(esm/" + getId(source) + ")/RelationFeatureViewBackAction";
        ctx.setElementId(action, id);  // Custom ID set
        return action;
    };
}
```

### XMI ID Generation Flow

When `createTarget(Action.class)` is called:

```
1. createTargetInPackage() executes:
   - source = currentSource.get()           // Should be RelationFeature
   - rule = currentExecutingRule.get()       // SHOULD be "RelationFeatureViewBackAction"
   - targetId = generateStructuredId(source, rule.getName())
   - setElementId(instance, targetId)        // Auto-generated ID

2. Rule body continues:
   - ctx.setElementId(action, customId)      // Overwrite with custom ID
```

### Root Cause Hypothesis

The issue occurs when `currentExecutingRule.get()` returns the **parent rule** (RelationFeatureView) instead of the **child rule** (RelationFeatureViewBackAction) during step 1.

This causes:
- Auto-generated ID: `GenericUser/(esm/_xxx)/RelationFeatureView` (WRONG)
- Custom ID: `GenericUser/(esm/_xxx)/RelationFeatureViewBackAction` (CORRECT)

### pendingXmiIdIndex Stale Entry Bug

When `setElementId()` is called twice (auto then custom), the index is not cleaned up:

```java
public void setElementId(EObject element, String id) {
    pendingXmiIds.put(element, id);        // Overwrites: element -> newId
    pendingXmiIdIndex.put(id, element);    // Adds: newId -> element
    // BUG: Old entry (oldId -> element) is NOT removed!
}
```

Result:
1. `pendingXmiIdIndex["/RelationFeatureView"] = Action` (stale, from auto-ID)
2. `pendingXmiIdIndex["/RelationFeatureViewBackAction"] = Action` (correct, from custom ID)
3. `pendingXmiIds[Action] = "/RelationFeatureViewBackAction"` (correct)

The stale entry can cause lookup issues when `findByXmiId("/RelationFeatureView", ...)` is called - it returns the Action instead of the expected PageDefinition.

## Context Management Code Paths

### TransformRuleDescriptor.execute() - CORRECT

```java
public EObject execute(EObject source, TransformationContext context) {
    TransformRuleDescriptor previousRule = context.getCurrentExecutingRule();
    context.setCurrentExecutingRule(this);  // Sets to child rule

    EObject previousSource = context.getCurrentSource();
    context.setCurrentSource(source);

    try {
        return getFunction().transform(source, context);  // Rule body
    } finally {
        // Restore context
        context.setCurrentExecutingRule(previousRule);
        context.setCurrentSource(previousSource);
    }
}
```

This appears correct - it sets `currentExecutingRule` BEFORE the rule body runs.

### Question: Where Does Context Get Wrong?

Possibilities:
1. **Race condition in parallel execution**: Another thread's context leaks
2. **Missing synchronization**: ThreadLocal not properly isolated
3. **Code path bypassing execute()**: Some path creates targets without proper context setup

## Investigation Tasks

1. Add assertion in `createTargetInPackage()`:
   ```java
   if (rule != null) {
       // Verify rule type matches target type being created
       assert rule.getTargetType().isAssignableFrom(targetType) :
           "Creating " + targetType + " but currentExecutingRule is " + rule.getName();
   }
   ```

2. Fix `setElementId()` to remove stale index entries:
   ```java
   public void setElementId(EObject element, String id) {
       // Remove old index entry if ID is changing
       String oldId = pendingXmiIds.get(element);
       if (oldId != null && !oldId.equals(id)) {
           pendingXmiIdIndex.remove(oldId);  // Clean up stale entry!
       }

       pendingXmiIds.put(element, id);
       pendingXmiIdIndex.put(id, element);
       // ...
   }
   ```

3. Create test that triggers nested lazy rule execution and verifies XMI IDs.

## Summary

**Primary Issue**: `pendingXmiIdIndex` accumulates stale entries when element IDs are changed from auto-generated to custom. This can cause `findByXmiId()` to return wrong elements.

**Secondary Issue (to investigate)**: `currentExecutingRule` might not be correctly set in some code path, causing auto-generated IDs to use the wrong rule name.
