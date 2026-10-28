# Migration Guide: ID Race Condition Fix

This guide helps ZETA transformation consumers update their code after the `getElementId()` race condition fix.

## What Changed

The framework now **prevents** a race condition where:
1. Rule A creates a target (gets auto-generated ID)
2. Rule A calls `equivalent()` which triggers Rule B
3. Rule B reads the target's ID via `getElementId()`
4. Rule A calls `setElementId()` to override with custom ID
5. **Problem**: Rule B saw the wrong (initial) ID!

### New Behavior

- `setElementId()` now **throws `IllegalStateException`** if another rule has already read the element's ID
- New `createTarget(Class<T>, String customId)` overload sets ID at creation time

---

## How to Identify Affected Code

### Error Message You'll See

```
IllegalStateException: Cannot change ID of element after it was read by another rule.
Element type: EPackage
Element was created by rule: CreateClassType
ID was read by rule: CreateAttributeType
Solution: Use createTarget(type, customId) to set ID at creation time.
Example: ctx.createTarget(EPackage.class, "custom/id")
```

### Patterns That Need Migration

**Pattern 1: setElementId after equivalent() call**
```java
// BROKEN - will throw IllegalStateException
EPackage target = ctx.createTarget(EPackage.class);
ctx.equivalent(source, OtherRule.class);  // OtherRule reads target's ID
ctx.setElementId(target, customId);       // THROWS!
```

**Pattern 2: setElementId after equivalentDiscriminated() call**
```java
// BROKEN - will throw IllegalStateException
EPackage target = ctx.createTarget(EPackage.class);
String discriminator = ctx.getElementId(target) + "/child";
ctx.equivalentDiscriminated(source, ChildRule.class, discriminator);
ctx.setElementId(target, customId);       // THROWS!
```

**Pattern 3: Building discriminator with target ID, then changing ID**
```java
// BROKEN - discriminator uses wrong ID
EPackage classType = ctx.createTarget(EPackage.class);
String disc = ctx.getElementId(classType) + "/attribute";  // Initial ID
ctx.equivalentDiscriminated(source, AttrRule.class, disc);
ctx.setElementId(classType, "custom/" + source.getName()); // THROWS!
```

---

## Migration Strategies

### Strategy 1: Use createTarget(type, customId) - RECOMMENDED

**Before:**
```java
@TransformRule(name = "CreateClassType")
public TransformFunction<EsmClass, EPackage> createClassType() {
    return (source, ctx) -> {
        EPackage classType = ctx.createTarget(EPackage.class);
        classType.setName(source.getName());

        // Call child rules
        ctx.equivalent(source, CreateAttributes.class);

        // Set custom ID - WILL THROW NOW!
        ctx.setElementId(classType, "classType/" + source.getName());

        return classType;
    };
}
```

**After:**
```java
@TransformRule(name = "CreateClassType")
public TransformFunction<EsmClass, EPackage> createClassType() {
    return (source, ctx) -> {
        // Build custom ID FIRST
        String customId = "classType/" + source.getName();

        // Create target WITH custom ID
        EPackage classType = ctx.createTarget(EPackage.class, customId);
        classType.setName(source.getName());

        // Now safe to call child rules - they'll see correct ID
        ctx.equivalent(source, CreateAttributes.class);

        return classType;
    };
}
```

### Strategy 2: Set ID BEFORE calling other rules

**Before:**
```java
EPackage target = ctx.createTarget(EPackage.class);
ctx.equivalent(source, OtherRule.class);  // Reads ID
ctx.setElementId(target, customId);       // THROWS!
```

**After:**
```java
EPackage target = ctx.createTarget(EPackage.class);
ctx.setElementId(target, customId);       // Set BEFORE other rules
ctx.equivalent(source, OtherRule.class);  // Now sees correct ID
```

### Strategy 3: Build discriminator with custom ID

**Before:**
```java
EPackage classType = ctx.createTarget(EPackage.class);
// Using initial auto-generated ID in discriminator
String disc = ctx.getElementId(classType) + "/attribute";
ctx.equivalentDiscriminated(source, AttrRule.class, disc);
ctx.setElementId(classType, customId);  // THROWS & discriminator is wrong!
```

**After:**
```java
// Build custom ID first
String customId = "classType/" + source.getName();

// Create with custom ID
EPackage classType = ctx.createTarget(EPackage.class, customId);

// Discriminator uses the correct ID from the start
String disc = customId + "/attribute";
ctx.equivalentDiscriminated(source, AttrRule.class, disc);
```

---

## Common Migration Scenarios

### Scenario: esm2ui Style Transformations

**Before:**
```java
@TransformRule(name = "CreateTransferObjectType")
public TransformFunction<EsmClass, ui::ClassType> createTOType() {
    return (esmClass, ctx) -> {
        ClassType classType = ctx.createTarget(ClassType.class);

        // Create attributes using target's ID in discriminator
        for (EsmAttribute attr : esmClass.getAttributes()) {
            String disc = ctx.getElementId(classType) + "/attr/" + attr.getName();
            ui::AttributeType uiAttr = ctx.equivalentDiscriminated(
                attr, AttributeType.class, "CreateAttribute", disc);
            classType.getAttributes().add(uiAttr);
        }

        // Override ID based on actor type - WILL THROW!
        ctx.setElementId(classType, actorType.getName() + "/" + esmClass.getName());

        return classType;
    };
}
```

**After:**
```java
@TransformRule(name = "CreateTransferObjectType")
public TransformFunction<EsmClass, ui::ClassType> createTOType() {
    return (esmClass, ctx) -> {
        // Build custom ID FIRST
        String customId = actorType.getName() + "/" + esmClass.getName();

        // Create WITH custom ID
        ClassType classType = ctx.createTarget(ClassType.class, customId);

        // Now discriminators use correct ID
        for (EsmAttribute attr : esmClass.getAttributes()) {
            String disc = customId + "/attr/" + attr.getName();
            ui::AttributeType uiAttr = ctx.equivalentDiscriminated(
                attr, AttributeType.class, "CreateAttribute", disc);
            classType.getAttributes().add(uiAttr);
        }

        return classType;
    };
}
```

### Scenario: Dynamic ID Based on Child Processing

If you need to build the ID based on information gathered during child processing:

**Before (problematic):**
```java
EPackage target = ctx.createTarget(EPackage.class);
List<EClass> children = ctx.equivalentAll(source.getChildren(), EClass.class);
String customId = buildIdFromChildren(children);  // ID based on results
ctx.setElementId(target, customId);  // THROWS!
```

**After (restructure to compute ID first):**
```java
// Compute what you need for the ID BEFORE creating target
String customId = computeIdFromSource(source);  // Derive from source, not results

EPackage target = ctx.createTarget(EPackage.class, customId);
List<EClass> children = ctx.equivalentAll(source.getChildren(), EClass.class);
```

Or if you truly need child results for the ID (rare), create without custom ID:
```java
// If you really can't know the ID upfront, don't set a custom one
// Let the framework use the structured ID
EPackage target = ctx.createTarget(EPackage.class);
List<EClass> children = ctx.equivalentAll(source.getChildren(), EClass.class);
// Don't call setElementId - use the auto-generated structured ID
```

---

## Quick Reference

| Old Pattern | New Pattern |
|-------------|-------------|
| `createTarget()` then `setElementId()` | `createTarget(type, customId)` |
| `setElementId()` after `equivalent()` | Set ID before or use new overload |
| Build discriminator with `getElementId()` then override | Build ID first, use in both |

---

## Testing Your Migration

After updating your code:

```bash
# Run your transformation tests
mvn test -pl your-transformation-module

# Look for IllegalStateException errors in logs
# Error message will tell you which rules need updating
```

---

## FAQ

**Q: Can I still use setElementId()?**
A: Yes, as long as you call it BEFORE any other rule reads the element's ID via `getElementId()`.

**Q: What if I don't set a custom ID at all?**
A: No change needed. The auto-generated structured ID works as before.

**Q: What if I need the custom ID to depend on the result of child rules?**
A: Restructure to compute the ID from the source element. If that's truly impossible, consider not using a custom ID and relying on the structured ID.

**Q: Does this affect parallel transformations?**
A: Yes, the fix makes parallel transformations safer by preventing the race condition.

---

## Update: Clone Tracking for equivalentDiscriminated (January 2026)

### What Changed

The external read tracking now also applies to **clones** created by `equivalentDiscriminated()`.

When you call `equivalentDiscriminated(source, targetType, ruleName, discriminator)`:
1. The lazy rule creates an "original" element
2. The framework clones this original (via `EcoreUtil.copy()`)
3. The clone gets a discriminated ID and is returned to you

**Previously**: Clones were not tracked, so you could read a clone's ID and then change it.

**Now**: Clones inherit the creating rule from their original. If you read the clone's ID and then try to change it via `setElementId()`, it will throw.

### New Problematic Pattern

```java
// BROKEN - will throw IllegalStateException
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", discriminator);
String currentId = ctx.getElementId(action);  // Reads ID - marks as external read
ctx.setElementId(action, customId);           // THROWS! ID was already read
```

### Why This Matters

The pattern above was trying to:
1. Get an action from a lazy rule (or its clone)
2. Override the ID with a custom one

This is problematic because:
- The clone already has a well-defined discriminated ID
- Other rules might have references to this clone with this ID
- Changing the ID breaks cache consistency and can cause subtle bugs

### Migration for equivalentDiscriminated Patterns

**Pattern: Get-or-create with custom ID**

```java
// BROKEN pattern from esm2ui style code:
String fullId = actorType.getName() + "/(esm/" + getId(source) + ")/" + RULE_NAME;
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
if (action == null) {
    action = ctx.createTarget(Action.class, fullId);
} else {
    ctx.setElementId(action, fullId);  // THROWS!
}
```

**Fix Option 1: Have the lazy rule set the custom ID**

```java
// In the lazy rule itself:
@TransformRule(name = "CreateAction")
@Lazy
public TransformFunction<EsmClass, Action> createAction() {
    return (source, ctx) -> {
        // Build custom ID in the lazy rule
        String customId = buildCustomId(source);
        Action action = ctx.createTarget(Action.class, customId);
        // ... populate action
        return action;
    };
}

// In the caller - just use the result as-is:
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
// DON'T try to change the ID - it's already set correctly by the lazy rule
```

**Fix Option 2: Accept the framework-generated discriminated ID**

```java
// Just accept the discriminated ID - it's already unique and stable
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
// Use action as-is, with its discriminated ID
// ID format: (source/<source-id>)/CreateAction/(discriminator/<disc>)
```

**Fix Option 3: Don't read the ID if you plan to change it**

```java
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
// DON'T call getElementId before setElementId!
ctx.setElementId(action, customId);  // Works if you haven't read the ID
```

### Important: equivalentDiscriminated Never Returns Null

A common misconception is that `equivalentDiscriminated` returns `null` if the element doesn't exist yet. **This is incorrect.**

When you call `equivalentDiscriminated(source, targetType, ruleName, discriminator)`:
- If a matching lazy rule exists, it **executes the rule** and returns the result (or its clone)
- It only returns `null` if:
  - No matching rule is found
  - The rule's guard rejects the source
  - Circular dependency detection triggers

**Wrong assumption:**
```java
// This pattern doesn't work as expected!
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
if (action == null) {
    // You expect to create it yourself, but...
    action = ctx.createTarget(Action.class, customId);
}
// ...action is NEVER null because the lazy rule always executes!
```

**Correct understanding:**
```java
// equivalentDiscriminated WILL execute the lazy rule
Action action = ctx.equivalentDiscriminated(source, Action.class, "CreateAction", disc);
// action is NOT null - the lazy rule created it (or returned a clone)
// Use the action as-is with its framework-assigned ID
```

---

## Summary of All Protected Patterns

| Pattern | Behavior | Migration |
|---------|----------|-----------|
| `createTarget()` → `equivalent()` → `setElementId()` | THROWS | Use `createTarget(type, customId)` |
| `createTarget()` → `getElementId()` → `setElementId()` | THROWS (if external) | Set ID before reading, or use `createTarget(type, customId)` |
| `equivalentDiscriminated()` → `getElementId()` → `setElementId()` | THROWS | Accept the discriminated ID, or set ID in lazy rule |
| `equivalentDiscriminated()` → `setElementId()` (no read) | WORKS | But discouraged - prefer setting ID in lazy rule |

---

## Debug Helpers (For Development Only)

If you need to debug external read tracking issues, these methods are available:

```java
// Get which rule created an element
String creatingRule = ctx.getElementCreatingRuleDebug(element);

// Get which rule first read an element's ID externally
String readingRule = ctx.getIdReadByExternalRuleDebug(element);
```

These are intended for debugging only and should not be used in production code.
