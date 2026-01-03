# Proposal: Unify Rule Execution Locking

**Change ID:** unify-rule-execution-locking
**Status:** Proposed
**Created:** 2026-01-03
**Updated:** 2026-01-03

## Problem Statement

Race condition exists when the same (source, ruleName) pair is accessed through different API methods (`equivalent()` vs `executeParentRule()`) concurrently. Each method uses a different locking mechanism that doesn't synchronize with the other.

### Dual Locking Mechanisms

| Method | Lock Mechanism | Lock Key |
|--------|---------------|----------|
| `equivalent()` | `ruleLocks` (per-key ReentrantLock) | `RuleCacheKey(source, ruleName)` with `Objects.hash()` |
| `executeParentRule()` | `lockStripes` (1024 striped locks via `ElementResolutionCache`) | `CacheKey` with `System.identityHashCode(source)` |

### Race Condition Scenario

```
Thread A: equivalent(model, Model.class)     → acquires ruleLocks[key1]
Thread B: executeParentRule("Model", model)  → acquires lockStripes[hash2]
                                               ↑ DIFFERENT LOCK!
```

Both threads execute the same rule simultaneously because they use different lock pools.

### Symptoms

- Duplicate rule executions
- Race to cache results (one may overwrite the other)
- Missing elements when execution order matters
- Potential null returns in edge cases

## Proposed Solution

Unify `executeParentRule()` to use the same locking mechanism as `equivalent()` - the `ruleLocks` map with `RuleCacheKey`.

### Changes Required

**TransformationContext.executeParentRule():**

Replace the call to `resolutionCache.getOrCreate()` with direct locking via `ruleLocks`:

```java
// Instead of:
return (T) resolutionCache.getOrCreate(source, parentRuleName, () -> {
    return parentRule.execute(source, this);
}, parentRule.isPrimary());

// Use unified locking:
RuleCacheKey key = new RuleCacheKey(source, parentRuleName);

// Fast path: check caches first
T cached = resolutionCache.getByRule(source, parentRuleName);
if (cached != null) {
    return cached;
}
EObject existing = executingLazyRules.get(key);
if (existing != null) {
    return (T) existing;
}

// Acquire same lock as equivalent()
ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
boolean lockAcquired = lock.tryLock(30, TimeUnit.SECONDS);
if (!lockAcquired) {
    throw new RuntimeException("Deadlock detected in executeParentRule");
}
try {
    // Double-check after acquiring lock
    cached = resolutionCache.getByRule(source, parentRuleName);
    if (cached != null) return cached;

    existing = executingLazyRules.get(key);
    if (existing != null) return (T) existing;

    // Execute rule under lock
    T result = parentRule.execute(source, this);
    if (result != null) {
        resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
        executingLazyRules.put(key, result);
    }
    return result;
} finally {
    lock.unlock();
}
```

## Impact Analysis

| Scenario | Before | After |
|----------|--------|-------|
| Both calls via `equivalent()` | ✅ Synchronized | ✅ Synchronized |
| Both calls via `executeParentRule()` | ✅ Synchronized (striped) | ✅ Synchronized (per-key) |
| Mixed `equivalent()` + `executeParentRule()` | ⚠️ **RACE CONDITION** | ✅ Synchronized |

### Performance Impact

- **Positive:** Eliminates duplicate rule executions in mixed-call scenarios
- **Neutral:** Per-key locking instead of striped locking has similar contention characteristics
- **Minor overhead:** Additional `executingLazyRules` check, but this is O(1) ConcurrentHashMap lookup

## Risks

1. **Lock ordering changes:** Different lock acquisition order might reveal previously hidden deadlocks
   - Mitigated by using `tryLock()` with timeout

2. **Behavioral changes:** Code relying on duplicate execution might break
   - This is unlikely since duplicate execution was always a bug

## Test-First Approach

Before implementing the fix, create a test that reproduces the race condition:

**DualLockingRaceConditionTest.java:**
- Thread A calls `equivalent(source, TargetType.class)` triggering rule "TestRule"
- Thread B calls `executeParentRule("TestRule", source)` for same source
- Track rule execution count per (source, ruleName) pair
- **EXPECTED BEFORE FIX:** Count > 1 (duplicate execution detected)
- **EXPECTED AFTER FIX:** Count == 1 (single execution, second thread gets cached result)

This ensures we have a reproducible test case that:
1. FAILS before the fix (proving the bug exists)
2. PASSES after the fix (proving the fix works)

## Success Criteria

1. `DualLockingRaceConditionTest` PASSES after fix
2. All existing tests pass (500+)
3. No duplicate rule executions for same (source, ruleName) pair
4. Mixed `equivalent()` + `executeParentRule()` calls properly synchronized
5. No performance regression in transformation time
