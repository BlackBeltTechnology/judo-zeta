# Proposal: Fix getElementId() Race Condition

**Change ID:** `fix-get-element-id-race-condition`
**Date:** 2026-01-17
**Status:** PROPOSED
**Priority:** High
**Affects:** All ZETA transformations that use `setElementId()` after `createTarget()`

---

## Problem Statement

### Race Condition Scenario

When a rule creates a target and later sets a custom ID, other rules that access the target between these two operations see the **initial** (wrong) ID:

```
Timeline:
1. Rule A: createTarget()         → auto-sets ID to "(source/abc)/RuleA"
2. Rule A: equivalent(RuleB)      → triggers Rule B
3. Rule B: getElementId(targetA)  → returns "(source/abc)/RuleA" (INITIAL)
4. Rule B: uses ID in discriminator
5. Rule A: setElementId(targetA, "custom/xyz")  → ID is now "custom/xyz"
6. MISMATCH: Rule B's discriminator has wrong ID!
```

### Confirmed by Tests

```
=== Race Condition Test Results ===
ID after createTarget (initial): (source/_a7ad6e5)/MainRule
Custom ID set via setElementId:  custom/RaceSource/special
ID seen by other rule:           (source/_a7ad6e5)/MainRule  ← WRONG!

RACE CONDITION CONFIRMED:
  Discriminators built with the 'seen' ID will NOT match the final ID!
```

### Impact

- **Cache misses**: Lookups with the correct (final) ID won't find elements cached under the initial ID
- **Orphan elements**: Elements may be duplicated because cache lookup fails
- **Incorrect references**: Discriminated clones may reference wrong elements

---

## Proposed Solutions

### Option A: `createTarget()` with Explicit ID (Recommended)

Add an overload that accepts the custom ID upfront, eliminating the window for race conditions.

```java
// Current API - race condition possible
EPackage target = ctx.createTarget(EPackage.class);
// ... other rules may read ID here ...
ctx.setElementId(target, "custom/id");

// New API - no race condition
EPackage target = ctx.createTarget(EPackage.class, "custom/id");
// ID is set from the start
```

**Implementation:**
```java
public <T extends EObject> T createTarget(Class<T> targetType, String customId) {
    T instance = createTargetInPackage(targetType, targetPackage);
    if (customId != null) {
        setElementId(instance, customId);
    }
    return instance;
}
```

**Pros:**
- Simple, backward-compatible (new overload, existing code unchanged)
- No race condition - ID is known from creation
- Minimal framework changes

**Cons:**
- Requires transformation code changes to use new overload
- Doesn't fix existing code automatically

---

### Option B: Deferred ID Assignment

Don't auto-assign ID in `createTarget()`. Instead, defer until:
1. Rule explicitly calls `setElementId()`, OR
2. Rule completes without setting ID (then auto-assign)

```java
// createTarget no longer sets ID immediately
EPackage target = ctx.createTarget(EPackage.class);
// target has NO ID yet

// Option 1: Explicit ID
ctx.setElementId(target, "custom/id");

// Option 2: Rule completes, framework auto-assigns
// (happens in rule completion hook)
```

**Implementation Changes:**
1. Remove `setElementId()` call from `createTargetInPackage()`
2. Add "pending targets" set for elements without IDs
3. On rule completion, auto-assign IDs to pending targets
4. `getElementId()` on pending target either:
   - Returns null (caller must handle)
   - Throws exception (forces explicit ID)
   - Auto-assigns immediately (current behavior as fallback)

**Pros:**
- Fixes the race condition at the source
- No change to transformation code patterns

**Cons:**
- Breaking change if code depends on immediate ID availability
- More complex implementation
- Need to handle "no ID yet" state

---

### Option C: ID Immutability After External Read

Once any rule OTHER than the creating rule reads the ID, it becomes immutable.

```java
// In TransformationContext
private Set<EObject> idReadExternally = ConcurrentHashMap.newKeySet();

public String getElementId(EObject element) {
    String id = resolveId(element);

    // Track if ID was read by a different rule
    if (currentExecutingRule.get() != creatingRule(element)) {
        idReadExternally.add(element);
    }
    return id;
}

public void setElementId(EObject element, String id) {
    if (idReadExternally.contains(element)) {
        throw new IllegalStateException(
            "Cannot change ID after it was read by another rule. " +
            "Use createTarget(type, customId) to set ID at creation time.");
    }
    // ... normal setElementId logic
}
```

**Pros:**
- Prevents the race condition by making it an error
- Self-documenting: forces correct usage pattern

**Cons:**
- Breaking change - existing code may throw exceptions
- Need to track element-to-rule mapping
- May be too restrictive for some use cases

---

### Option D: ID Change Notification (Complex)

Track ID changes and update all discriminators that used the old ID.

**Pros:**
- Fixes issue without changing transformation code

**Cons:**
- Very complex implementation
- Performance overhead
- May not be possible for all cases (discriminators in caches, etc.)

---

## Recommended Approach: Option A + Option C

Combine the solutions for maximum safety:

1. **Add `createTarget(type, customId)` overload** (Option A)
   - Provides the "correct" way to set custom IDs
   - No race condition possible

2. **Make ID immutable after external read** (Option C)
   - Prevents the race condition when using old pattern
   - Clear error message guides developers to use new pattern

3. **Document the pattern** in AGENTS.md and Javadoc

### Migration Path

```java
// BEFORE (race condition possible)
EPackage target = ctx.createTarget(EPackage.class);
ctx.equivalent(source, OtherRule.class);  // May read ID here!
ctx.setElementId(target, "custom/id");    // Too late!

// AFTER (no race condition)
EPackage target = ctx.createTarget(EPackage.class, "custom/id");
ctx.equivalent(source, OtherRule.class);  // Sees correct ID
```

---

## API Changes

### New Method: `createTarget(Class<T>, String)`

```java
/**
 * Create a target element with a specific XMI ID.
 *
 * <p>Use this method when you need to set a custom ID for the target element.
 * Setting the ID at creation time prevents race conditions where other rules
 * might read the ID before it's finalized.</p>
 *
 * <p><b>Example:</b></p>
 * <pre>
 * // Build custom ID first
 * String customId = buildCustomId(source);
 *
 * // Create target with ID already set
 * EPackage target = ctx.createTarget(EPackage.class, customId);
 *
 * // Safe to call other rules - they'll see the correct ID
 * ctx.equivalent(source, OtherRule.class);
 * </pre>
 *
 * @param targetType the type of element to create
 * @param customId the XMI ID to assign (null for auto-generated structured ID)
 * @return the created element with ID already set
 */
public <T extends EObject> T createTarget(Class<T> targetType, String customId);
```

### Modified Method: `setElementId(EObject, String)`

Add validation to prevent ID changes after external reads:

```java
/**
 * Set the XMI ID of an element.
 *
 * <p><b>Warning:</b> If another rule has already read this element's ID via
 * {@link #getElementId(EObject)}, this method will throw an exception.
 * Use {@link #createTarget(Class, String)} to set custom IDs at creation time.</p>
 *
 * @throws IllegalStateException if ID was already read by another rule
 */
public void setElementId(EObject element, String id);
```

---

## Test Cases

### Test 1: createTarget with custom ID prevents race condition

```java
@Test
void createTargetWithCustomId_preventsRaceCondition() {
    // Create target with custom ID upfront
    EPackage target = ctx.createTarget(EPackage.class, "custom/id");

    // Call other rule
    ctx.equivalent(source, OtherRule.class);
    // OtherRule sees "custom/id"

    // Verify ID is correct
    assertEquals("custom/id", ctx.getElementId(target));
    assertEquals("custom/id", idSeenByOtherRule.get());
}
```

### Test 2: setElementId after external read throws exception

```java
@Test
void setElementId_afterExternalRead_throwsException() {
    EPackage target = ctx.createTarget(EPackage.class);

    // Another rule reads the ID
    ctx.equivalent(source, OtherRule.class);  // Reads target's ID

    // Attempting to change ID should fail
    assertThrows(IllegalStateException.class, () -> {
        ctx.setElementId(target, "custom/id");
    });
}
```

### Test 3: setElementId before external read succeeds

```java
@Test
void setElementId_beforeExternalRead_succeeds() {
    EPackage target = ctx.createTarget(EPackage.class);

    // Set custom ID BEFORE other rules read it
    ctx.setElementId(target, "custom/id");

    // Now call other rule - sees correct ID
    ctx.equivalent(source, OtherRule.class);

    assertEquals("custom/id", idSeenByOtherRule.get());
}
```

---

## Files to Modify

| File | Change |
|------|--------|
| `TransformationContext.java` | Add `createTarget(type, customId)` overload |
| `TransformationContext.java` | Add external read tracking in `getElementId()` |
| `TransformationContext.java` | Add validation in `setElementId()` |
| `GetElementIdTimingIssueTest.java` | Add tests for new behavior |

---

## Backward Compatibility

- **`createTarget(type)`**: Unchanged behavior (auto-assigns ID)
- **`setElementId()`**: New validation may throw exceptions for code with race conditions
  - This is intentional - it surfaces bugs that were previously silent
  - Migration: use `createTarget(type, customId)` instead

---

## References

- **Test file:** `GetElementIdTimingIssueTest.java`
- **Related proposal:** `expose-get-element-id-public-api`
- **Related code:** `TransformationContext.java:1108-1143` (createTargetInPackage)
