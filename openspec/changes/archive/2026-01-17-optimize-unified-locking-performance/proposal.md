# Proposal: Optimize Unified Locking Performance

**Change ID:** optimize-unified-locking-performance
**Status:** Proposed
**Created:** 2026-01-04
**Updated:** 2026-01-04

## Why

The fix for the dual-locking race condition (commit `ed51378`) introduced significant performance overhead in `executeParentRule()`, resulting in **+123% slowdown** in transformation time. While the race condition fix is correct, the implementation has multiple sources of overhead that can be eliminated without compromising thread safety.

### Performance Regression

| Metric | Previous | New | Change |
|--------|----------|-----|--------|
| executor.transform() | 7,799ms | 17,380ms | +123% slower |
| postProcess() | 433ms | 681ms | +57% slower |
| Total Zeta | 8,302ms | 18,163ms | +119% slower |
| Speedup vs ETL | 5.74x | 3.56x | -38% |

## What Changes

Optimize the unified locking implementation to eliminate overhead while maintaining the race condition fix:

1. **Avoid object allocation on fast path** - check caches before creating `RuleCacheKey`
2. **Use `lock.lock()` instead of `tryLock(timeout)`** - consistent with `equivalent()` behavior
3. **Use identity-based hash** - `System.identityHashCode(source)` instead of EMF `hashCode()`
4. **Reduce cache lookup duplication** - single check before lock, single check after

## Problem Analysis

### 1. Object Allocation per Call (HIGH impact)

**Before (original):**
```java
return (T) resolutionCache.getOrCreate(source, parentRuleName, () -> {...}, isPrimary);
```
No object allocation on fast path.

**After (current):**
```java
RuleCacheKey key = new RuleCacheKey(source, parentRuleName);  // NEW OBJECT every call
EObject existing = executingLazyRules.get(key);
```
Creates object on every call, even for cache hits.

### 2. Hash Computation Overhead (MEDIUM impact)

**Current `RuleCacheKey.hashCode()`:**
```java
return Objects.hash(source, ruleName);  // Calls source.hashCode() - expensive for EMF
```

**Original striped lock hash:**
```java
int hash = System.identityHashCode(source) ^ ruleName.hashCode();  // Fast identity hash
```

EMF objects have complex `hashCode()` implementations that traverse object state.

### 3. Lock Acquisition Method (LOW-MEDIUM impact)

| Method | Before | After |
|--------|--------|-------|
| `equivalent()` | `lock.lock()` | `lock.lock()` |
| `executeParentRule()` | `lock.lock()` | `tryLock(30, TimeUnit.SECONDS)` |

The `tryLock(timeout)` has timeout handling overhead that `lock.lock()` doesn't have.

### 4. Lock Selection Mechanism (LOW impact)

| Aspect | Before (Striped) | After (Per-Key) |
|--------|------------------|-----------------|
| Lock storage | Pre-allocated array[1024] | ConcurrentHashMap |
| Lock lookup | `lockStripes[hash % 1024]` | `computeIfAbsent(key, ...)` |
| Memory | Fixed 1024 locks | Grows with unique keys |

### 5. Cache Lookup Duplication (LOW impact)

**Before:** Single cache check inside `getOrCreate()`

**After:** 2 checks before lock + 2 checks after lock = 4 cache lookups

## Proposed Solution

### Option A: Optimize Current Approach (Recommended)

Keep `ruleLocks` for unified locking but optimize the hot path:

```java
public <T extends EObject> T executeParentRule(String parentRuleName, EObject source, T target) {
    // ... setup code ...

    // Fast path: check caches BEFORE creating key object
    EObject cached = resolutionCache.getByRule(source, parentRuleName);
    if (cached != null) {
        return (T) cached;
    }

    // Only create key when needed for locking
    RuleCacheKey key = new RuleCacheKey(source, parentRuleName);

    // Check executingLazyRules (needs key)
    EObject existing = executingLazyRules.get(key);
    if (existing != null) {
        return (T) existing;
    }

    // Use lock.lock() like equivalent() does
    ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
    lock.lock();
    try {
        // Single double-check (resolutionCache is authoritative)
        cached = resolutionCache.getByRule(source, parentRuleName);
        if (cached != null) {
            return (T) cached;
        }

        // Execute and cache
        EObject result = parentRule.execute(source, this);
        if (result != null) {
            resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
            executingLazyRules.put(key, result);
        }
        return (T) result;
    } finally {
        lock.unlock();
    }
}
```

### Option B: Identity-Based RuleCacheKey

Additionally, change `RuleCacheKey` to use identity hash:

```java
private static class RuleCacheKey {
    final EObject source;
    final String ruleName;
    private final int hashCode;  // Pre-computed

    RuleCacheKey(EObject source, String ruleName) {
        this.source = source;
        this.ruleName = ruleName;
        this.hashCode = System.identityHashCode(source) ^ ruleName.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleCacheKey that = (RuleCacheKey) o;
        return source == that.source && Objects.equals(ruleName, that.ruleName);  // Identity comparison
    }

    @Override
    public int hashCode() {
        return hashCode;  // Pre-computed
    }
}
```

## Expected Performance Improvement

| Optimization | Expected Impact |
|--------------|-----------------|
| Avoid key allocation on cache hit | ~30-40% improvement |
| Use `lock.lock()` vs `tryLock(timeout)` | ~5-10% improvement |
| Identity-based hash | ~10-20% improvement |
| Reduce double-checks | ~5% improvement |

**Target:** Restore performance to within 10-15% of original (acceptable overhead for thread safety).

## Risks

1. **None for Option A** - Same locking semantics, just optimized
2. **Low for Option B** - Identity comparison is correct for EMF (same object = same model element)

## Success Criteria

1. `DualLockingRaceConditionTest` still passes (no race condition regression)
2. Performance within 15% of baseline (before the race condition fix)
3. All 500+ existing tests pass
