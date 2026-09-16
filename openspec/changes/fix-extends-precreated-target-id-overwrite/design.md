## Context

The `createTarget(Class<T>, String customId)` method at `TransformationContext.java:1343` is a two-step operation:

```java
public <T extends EObject> T createTarget(Class<T> targetType, String customId) {
    T instance = createTarget(targetType);       // Step 1: may return preCreatedTarget
    if (customId != null) {
        setElementIdInternal(instance, customId); // Step 2: overwrites ID unconditionally
    }
    return instance;
}
```

During `@Extends` inheritance, `executeWithInheritance()` in `TransformRuleDescriptor.java:739`:
1. Pre-creates a target of the child's type
2. Sets the XMI ID via `context.setStructuredIdOnTarget(target, source, childRuleName)` — ID = `.../ChildRuleName`
3. Sets `inInheritanceExecution=true` and `preCreatedTarget=target`
4. Executes parent rules — their `createTarget(Class)` returns the pre-created target
5. Executes the child rule

The bug: when a parent rule calls `createTarget(Class, source, "ParentRuleName")`, Step 2 overwrites the child's ID with `.../ParentRuleName`.

### The collision cascade

After the ID overwrite, any additional `createTarget()` call in the parent rule (e.g., creating an inline Icon) gets an auto-generated ID based on `(source, currentRuleName)` = `.../ParentRuleName` — the same ID that was just assigned to the pre-created target. This triggers `XMI ID COLLISION DETECTED` errors and corrupts the `pendingXmiIdIndex` lookup table.

## Goals / Non-Goals

**Goals:**
- Preserve the pre-created target's XMI ID when `createTarget(Class, String)` returns it during `@Extends` inheritance
- Eliminate XMI ID collisions caused by parent rules overwriting child IDs
- Maintain backward compatibility for all non-inheritance `createTarget(Class, String)` calls

**Non-Goals:**
- Changing the ID generation logic in `generateUniqueTargetId()` (orthogonal concern)
- Modifying `executeWithInheritance()` or `setStructuredIdOnTarget()` (they work correctly)
- Fixing the ESM2UI transformation code (the framework should handle this correctly)

## Decisions

### 1. Detection mechanism

**Decision:** After `createTarget(targetType)` returns, check if the returned instance is the same object as `preCreatedTarget.get()` AND we are in inheritance execution mode.

**Rationale:**
- Identity comparison (`instance == preCreatedTarget.get()`) is the most precise check — it only matches the exact pre-created target, not any other element
- Combined with `inInheritanceExecution` flag for safety
- No false positives: a newly created element is never the pre-created target

**Alternative considered:** Check if `getPendingXmiId(instance) != null` (i.e., ID already set). Rejected because newly created elements also get auto-IDs, so this would suppress legitimate custom ID overrides.

### 2. Behavior when pre-created target is detected

**Decision:** Skip the `setElementIdInternal()` call entirely. Do NOT log a warning.

**Rationale:**
- The pre-created target already has the correct ID (set by `executeWithInheritance`)
- This is the expected inheritance semantics: the child defines the target's identity
- Parent rules calling `createTarget()` during inheritance is normal — they should get the shared target without side effects
- Warning would be noisy for a correct code path

### 3. The `createTarget(Class, EObject, String)` overload

**Decision:** No changes needed — it delegates to `createTarget(Class, String)` which will be fixed.

```java
public <T extends EObject> T createTarget(Class<T> targetType, EObject source, String suffix) {
    String customId = buildSourceBasedId(source, suffix);
    return createTarget(targetType, customId);  // ← fix applies here
}
```

## Implementation

The fix is a single conditional in `createTarget(Class<T>, String customId)`:

```java
public <T extends EObject> T createTarget(Class<T> targetType, String customId) {
    T instance = createTarget(targetType);

    // During @Extends inheritance, if createTarget() returned the pre-created target,
    // preserve its ID (set by executeWithInheritance). The child rule defines the
    // target's identity; parent rules must not overwrite it.
    if (Boolean.TRUE.equals(inInheritanceExecution.get())
            && instance == preCreatedTarget.get()) {
        return instance;
    }

    if (customId != null) {
        setElementIdInternal(instance, customId);
    }
    return instance;
}
```

**Why this is safe for all callers:**

| Caller context | `inInheritanceExecution` | `instance == preCreatedTarget` | Result |
|---|---|---|---|
| Normal rule (no @Extends) | false | N/A | Custom ID applied (unchanged) |
| Child rule in @Extends chain | true | true (same target) | ID preserved (FIX) |
| Parent creating different-type element (e.g., Icon) | true | false (new element) | Custom ID applied (unchanged) |
| `executeParentRule(name, src, null)` with isolation | false (cleared by isolation fix) | false | Custom ID applied (unchanged) |

## Risks / Trade-offs

**Risk: Future parent rules might intentionally want to override child ID**
- **Mitigation:** No existing parent rule does this. If needed in the future, a new API `createTarget(Class, String, boolean forceId)` can be added. The current behavior (child wins) matches ETL semantics.

**Risk: Test coverage gap for this specific scenario**
- **Mitigation:** Task 2.1 adds a dedicated test case reproducing the exact collision pattern.

**Trade-off: Fix is minimal and localized**
- **Pro:** Single method change, no architectural impact, no API changes
- **Con:** Doesn't make the two-step `createTarget`→`setId` pattern more robust in general (but that's a broader refactor)
