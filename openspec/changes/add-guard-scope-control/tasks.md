# Tasks: Add Guard Scope Control (Per-Rule Rejection)

## Overview

Implementation tasks for ETL-compatible per-rule guard rejection caching.

---

## Phase 1: Add Rejected Set to TransformRuleDescriptor

### Task 1.1: Add rejected collection to TransformRuleDescriptor
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformRuleDescriptor.java`

- [ ] Add `private final Set<EObject> rejected = ConcurrentHashMap.newKeySet()` field
- [ ] Add `wasRejected(EObject source)` method
- [ ] Add `recordRejection(EObject source)` method
- [ ] Add `clearRejected()` method for executor reset

**Verification**: Compiles without errors

---

## Phase 2: Integrate Rejection Caching in Guard Evaluation

### Task 2.1: Update evaluateGuard to check/record rejections
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformRuleDescriptor.java`

- [ ] Check `rejected.contains(source)` before evaluating guard
- [ ] Return `false` immediately if already rejected
- [ ] Add to `rejected` set when guard fails
- [ ] Log debug message for rejection cache hits

**Verification**: Guard is evaluated once per (rule, source) pair

---

## Phase 3: Clear Rejections on Executor Reset

### Task 3.1: Add method to clear all rules' rejected sets
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationRegistry.java`

- [ ] Add `clearAllRejectedSets()` method
- [ ] Iterate through all rules and call `clearRejected()`

### Task 3.2: Call clearAllRejectedSets in executor reset
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`

- [ ] Call `registry.clearAllRejectedSets()` in `reset()` method

**Verification**: Executor reuse works correctly with fresh rejection state

---

## Phase 4: Testing

### Task 4.1: Add unit tests for per-rule rejection
**File**: `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/GuardRejectionCacheTest.java`

- [ ] Test rejection is recorded when guard fails
- [ ] Test subsequent calls return false immediately (cache hit)
- [ ] Test different rules have independent rejected sets
- [ ] Test concurrent rejection recording is thread-safe
- [ ] Test clearRejected() clears the set
- [ ] Test executor reset clears all rules' rejected sets

**Verification**: All tests pass

---

## Phase 5: Documentation (Optional)

### Task 5.1: Document guard rejection caching
**Files**: `docs/transformation/reference/annotations.md`

- [ ] Document that guards are cached per (rule, source)
- [ ] Explain ETL-compatible behavior

---

## Verification Checklist

After all tasks complete:

- [ ] `mvn clean install` succeeds
- [ ] All existing tests pass (no regressions)
- [ ] Guard evaluated once per (rule, source) pair
- [ ] Rejection isolated per rule (no cross-rule effects)
- [ ] Executor reset clears all rejected sets
- [ ] Thread-safe for parallel transformations

---

## Dependencies

```
Task 1.1 ──► Task 2.1 ──► Task 3.1 ──► Task 3.2 ──► Task 4.1 ──► Task 5.1
```

**All tasks are sequential** - each depends on the previous.

---

## Note: Root Cause Investigation

This change implements ETL-compatible per-rule rejection caching, which is a **performance optimization** that avoids redundant guard evaluation.

However, the element count difference (245 vs 517) is likely NOT caused by guard rejection but by:

1. **Activity-based semantics**: Enable `etlCompatibilityMode(true)` for @Lazy @Greedy rules
2. **Caching issues**: @Lazy rules via `executeParentRule()` need manual cache management

**Recommendation**: Test with `etlCompatibilityMode(true)` first to verify if this resolves the element count difference before implementing per-rule rejection.
