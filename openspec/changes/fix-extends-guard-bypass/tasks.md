# Tasks: Fix @Extends Guard Bypass

## Phase 1: Bug Reproduction

- [x] **1.1 Create failing test for guard bypass**
  - Add `ExtendsGuardBypassTest.java` in transformation-core tests
  - Test parent rule with `@Abstract` + `@Primary` + `@Guard`
  - Test child rule with `@Extends("ParentRule")`
  - Verify guard is NOT evaluated (bug reproduction)
  - *Dependency:* None
  - *Status:* COMPLETE - Test fails as expected

## Phase 2: Fix Implementation

- [ ] **2.1 Add guard check to executeParentRulesInChain()**
  - Modify `TransformRuleDescriptor.executeParentRulesInChain()` (~line 709)
  - Add `parentRule.evaluateGuard(source, context)` check before execution
  - Skip parent if guard rejects
  - *Dependency:* 1.1
  - *Location:* `transformation-core/src/main/java/.../TransformRuleDescriptor.java`

- [ ] **2.2 Add guard check to executeParentRule()**
  - Modify `TransformationContext.executeParentRule()` (~line 1516)
  - Add `parentRule.evaluateGuard(source, this)` check before cache.getOrCreate
  - Return null if guard rejects
  - *Dependency:* 1.1
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java`

## Phase 3: Verification

- [ ] **3.1 Verify ExtendsGuardBypassTest passes**
  - All 3 tests should pass after fix
  - *Dependency:* 2.1, 2.2

- [ ] **3.2 Run full test suite**
  - Run all 444+ tests
  - Ensure no regressions
  - *Dependency:* 3.1

- [ ] **3.3 Update test assertions to verify guard call counts**
  - Ensure parent guard is called expected number of times
  - Verify rejection caching works correctly
  - *Dependency:* 3.1

## Implementation Notes

### Guard Evaluation Details

The `TransformRuleDescriptor.evaluateGuard()` method:
- Returns `true` if no guard is defined
- Checks rejection cache before evaluating (ETL-compatible caching)
- Caches rejections for subsequent calls
- Is thread-safe for parallel execution

### Code Location Reference

| File | Method | Line (approx) |
|------|--------|---------------|
| TransformRuleDescriptor.java | executeParentRulesInChain | 709-722 |
| TransformationContext.java | executeParentRule | 1461-1511 |
| TransformRuleDescriptor.java | evaluateGuard | 365-388 |

### Expected Code Change

```java
// TransformRuleDescriptor.executeParentRulesInChain() - ADD:
if (!parentRule.evaluateGuard(source, context)) {
    continue;  // Guard rejected - skip this parent
}

// TransformationContext.executeParentRule() - ADD:
if (!parentRule.evaluateGuard(source, this)) {
    return null;  // Guard rejected
}
```

## Verification Criteria

1. Parent guard is called when child uses `@Extends`
2. Parent rule is skipped if guard rejects
3. No duplicate elements created
4. Guard rejection caching works correctly
5. All existing tests pass
