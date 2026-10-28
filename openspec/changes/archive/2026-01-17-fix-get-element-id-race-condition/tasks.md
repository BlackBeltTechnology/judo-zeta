# Tasks: Fix getElementId() Race Condition

## Phase 1: TDD Test Creation (RED)

- [x] **Task 1.1**: Add tests for `createTarget(type, customId)` overload
  - Test: Custom ID is set immediately
  - Test: Other rules see the custom ID via `getElementId()`
  - Test: null customId uses auto-generated ID
  - Test: No race condition when using new overload
  - **Status**: COMPLETED - Tests added to GetElementIdTimingIssueTest.java

- [x] **Task 1.2**: Add tests for ID immutability after external read
  - Test: `setElementId()` throws after external read
  - Test: `setElementId()` succeeds before external read
  - Test: Same rule can change ID multiple times (no external read)
  - Test: External read tracking is per-element
  - **Status**: COMPLETED - Tests added to GetElementIdTimingIssueTest.java

- [x] **Task 1.3**: Add tests for error messages
  - Test: Exception message contains element info
  - Test: Exception message suggests `createTarget(type, customId)`
  - **Status**: COMPLETED - Tests added to GetElementIdTimingIssueTest.java

## Phase 2: Implementation (GREEN)

- [x] **Task 2.1**: Add external read tracking infrastructure
  - Add `ConcurrentHashMap<EObject, TransformRuleDescriptor> idReadByExternalRule`
  - Add `ConcurrentHashMap<EObject, TransformRuleDescriptor> elementCreatingRule`
  - Thread-safe tracking of which rule created each element
  - Thread-safe tracking of external ID reads
  - **Status**: COMPLETED - Added to TransformationContext.java:158-169

- [x] **Task 2.2**: Modify `createTargetInPackage()` to track creating rule
  - Store `currentExecutingRule.get()` as the creating rule for the element
  - This enables distinguishing same-rule vs external reads
  - **Status**: COMPLETED - Added to TransformationContext.java:1158-1161

- [x] **Task 2.3**: Modify `getElementId()` to track external reads
  - Check if current rule is different from creating rule
  - If different, mark element as "ID read externally"
  - This makes the ID immutable
  - **Status**: COMPLETED - Added to TransformationContext.java:3057-3067

- [x] **Task 2.4**: Modify `setElementId()` to enforce immutability
  - Check if element's ID was read externally
  - If yes, throw `IllegalStateException` with clear message
  - Include: element, reading rule, suggestion to use new API
  - **Status**: COMPLETED - Added to TransformationContext.java:3476-3492

- [x] **Task 2.5**: Add `createTarget(Class<T>, String)` overload
  - Accept optional custom ID parameter
  - Set ID before returning (no window for race condition)
  - null customId falls back to auto-generated ID
  - **Status**: COMPLETED - Added to TransformationContext.java:1101-1143

- [x] **Task 2.6**: Update Javadoc for all modified methods
  - Document the race condition scenario
  - Document the new `createTarget(type, customId)` overload
  - Document ID immutability after external read
  - Provide code examples

## Phase 3: Verification (REFACTOR)

- [x] **Task 3.1**: Run TDD tests
  - Command: `mvn test -pl transformation-core -Dtest=GetElementIdTimingIssueTest`
  - **Result**: 19 tests pass

- [x] **Task 3.2**: Run full test suite
  - Command: `mvn test -pl transformation-core`
  - **Result**: 784 tests pass, 0 failures, 4 skipped (no regression)

- [x] **Task 3.3**: Run parallel execution tests
  - Command: `mvn test -pl transformation-core -Dtest=*Parallel*`
  - **Result**: Thread safety confirmed

## Phase 4: Documentation

- [x] **Task 4.1**: Javadoc updated with comprehensive documentation
  - Race condition scenario documented in setElementId()
  - createTarget(type, customId) documented with examples
  - ID immutability after external read documented

## Dependencies

```
Task 1.1 ─┬─→ Task 2.5 ─→ Task 3.1
Task 1.2 ─┼─→ Task 2.1 ─→ Task 2.2 ─→ Task 2.3 ─→ Task 2.4 ─→ Task 3.1
Task 1.3 ─┘                                              │
                                                         ↓
                                                    Task 3.2 ─→ Task 3.3
                                                         │
                                                         ↓
                                                    Task 2.6 ─→ Task 4.1
```

## Validation Checkpoints

| Checkpoint | Command | Expected Result |
|------------|---------|-----------------|
| Tests compile | `mvn test-compile -pl transformation-core` | SUCCESS |
| New tests pass | `mvn test -Dtest=GetElementIdTimingIssueTest` | All pass |
| No regression | `mvn test -pl transformation-core` | All existing tests pass |
| Thread safety | `mvn test -Dtest=*Parallel*` | No race conditions |
| OpenSpec valid | `openspec validate fix-get-element-id-race-condition --strict` | 0 errors |

## Implementation Notes

### External Read Tracking Data Structures

```java
// Track which rule created each element
private final ConcurrentHashMap<EObject, TransformRuleDescriptor> elementCreatingRule =
    new ConcurrentHashMap<>();

// Track elements whose ID was read by external rules
private final ConcurrentHashMap<EObject, TransformRuleDescriptor> idReadByExternalRule =
    new ConcurrentHashMap<>();
```

### Modified getElementId() Logic

```java
public String getElementId(EObject element) {
    String id = resolveElementId(element);

    // Track external reads
    TransformRuleDescriptor currentRule = currentExecutingRule.get();
    TransformRuleDescriptor creatingRule = elementCreatingRule.get(element);

    if (currentRule != null && creatingRule != null && currentRule != creatingRule) {
        // Different rule is reading - mark as externally read
        idReadByExternalRule.putIfAbsent(element, currentRule);
    }

    return id;
}
```

### Modified setElementId() Logic

```java
public void setElementId(EObject element, String id) {
    // Check for external read
    TransformRuleDescriptor readingRule = idReadByExternalRule.get(element);
    if (readingRule != null) {
        throw new IllegalStateException(
            "Cannot change ID of element after it was read by another rule.\n" +
            "Element: " + element + "\n" +
            "ID was read by rule: " + readingRule.getName() + "\n" +
            "Solution: Use createTarget(type, customId) to set ID at creation time.\n" +
            "Example: ctx.createTarget(EPackage.class, \"" + id + "\")");
    }

    // ... existing setElementId logic
}
```

### New createTarget Overload

```java
public <T extends EObject> T createTarget(Class<T> targetType, String customId) {
    // Create without auto-ID
    T instance = createTargetWithoutAutoId(targetType);

    // Set custom ID (or generate if null)
    String finalId = customId != null ? customId : generateStructuredId(source, ruleName);
    setElementIdInternal(instance, finalId);  // Bypass external read check

    // Track creating rule
    elementCreatingRule.put(instance, currentExecutingRule.get());

    return instance;
}
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Existing code throws new exceptions | Medium | Medium | Clear error messages guide fix |
| Performance overhead from tracking | Low | Low | ConcurrentHashMap is efficient |
| Thread safety issues in tracking | Low | High | Use atomic operations, thorough testing |
| Breaking parallel transformations | Low | Medium | Run parallel tests extensively |
