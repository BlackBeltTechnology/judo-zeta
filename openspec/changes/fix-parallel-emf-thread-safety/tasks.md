# Tasks: Fix Parallel Transformation EMF Thread-Safety

## Overview

Implementation tasks for thread-safe parallel transformation execution using locking/synchronization approach (Hybrid Option C).

**Scope:** judo-zeta framework only (fixes 1-3 from appendix)

---

## Phase 1: Thread-Safe Element Resolution Cache

### Task 1.1: Update cache key to include rule name
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Create `RuleCacheKey` record with `(source, ruleName)` fields
- [ ] Update `equivalentCache` to use `RuleCacheKey` instead of `(source, targetType)`
- [ ] Update `equivalent()` method to use rule name in cache key
- [ ] Ensure `hashCode()` and `equals()` are correct for cache key

**Verification:** Cache correctly isolates different rules transforming same source

### Task 1.2: Implement atomic get-or-create pattern
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Use `ConcurrentHashMap.computeIfAbsent()` for atomic cache operations
- [ ] Ensure creator function executes only once per key
- [ ] Add memory barrier to ensure visibility of created elements

**Verification:** No duplicate elements created for same (source, rule) pair

---

## Phase 2: Per-Element Rule Locking

### Task 2.1: Add per-element locking infrastructure
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Add `ConcurrentHashMap<RuleCacheKey, ReentrantLock> ruleLocks` field
- [ ] Implement `acquireLock(source, ruleName)` method
- [ ] Implement `releaseLock(source, ruleName)` method
- [ ] Clean up locks after transformation completes (in `reset()`)

### Task 2.2: Integrate locking with equivalent() calls
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Wrap `equivalent()` execution with per-element locking
- [ ] Implement double-check pattern: check cache → lock → check again → execute
- [ ] Ensure lock is released in finally block
- [ ] Handle re-entrant calls (same thread calling equivalent for same source)

**Verification:** Thread B waits while Thread A creates element, then gets cached result

---

## Phase 3: Synchronized EMF Operations

### Task 3.1: Synchronize element creation
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Add synchronization around `createTarget()` element initialization
- [ ] Ensure element is fully initialized before caching
- [ ] Use memory barrier (volatile or synchronized) for visibility

### Task 3.2: Synchronize XMI ID operations
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Synchronize `setXmiId()` on the resource object
- [ ] Synchronize `getOrCreateXmiId()` operations
- [ ] Handle concurrent ID generation safely

### Task 3.3: Review and synchronize containment operations
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [ ] Review `addToResource()` for thread safety
- [ ] Synchronize staged element commit if needed
- [ ] Ensure parent's resource is set before child operations

**Verification:** No NPE from uninitialized preparedResult in concurrent access

---

## Phase 4: Framework-Level Parallel Safety Tests

### Task 4.1: Create parallel safety test suite
**File:** `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ParallelSafetyTest.java`

- [ ] Test: No duplicate elements when same source transformed concurrently
- [ ] Test: Element count matches between sequential and parallel modes
- [ ] Test: No NPE or ConcurrentModificationException under load
- [ ] Test: Cache correctly isolates different rules

### Task 4.2: Add stress test for high concurrency
**File:** `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ParallelStressTest.java`

- [ ] Test with 16+ threads
- [ ] Test with 1000+ source elements
- [ ] Verify deterministic results across multiple runs
- [ ] Measure and log performance metrics

**Verification:** All parallel tests pass reliably (no flaky tests)

---

## Phase 5: Documentation

### Task 5.1: Document parallel execution guarantees
**File:** `docs/transformation/parallel-execution.md` (or inline in TransformationContext)

- [ ] Document thread-safety guarantees
- [ ] Document cache key design (`source, ruleName`)
- [ ] Document locking behavior
- [ ] Document when to use synchronized helpers in rules

---

## Verification Checklist

After all tasks complete:

- [ ] `mvn clean install` succeeds
- [ ] All existing tests pass (no regressions)
- [ ] New parallel safety tests pass
- [ ] Stress tests pass reliably (run 10x)
- [ ] No duplicate elements in parallel mode
- [ ] No NPE or ConcurrentModificationException
- [ ] Element count matches sequential mode

---

## Dependencies

```
Phase 1 (Cache) ──► Phase 2 (Locking) ──► Phase 3 (EMF Sync) ──► Phase 4 (Tests)
                                                                       │
                                                                       ▼
                                                               Phase 5 (Docs)
```

**Phases 1-3 are sequential** - each builds on the previous.
**Phases 4-5 can start after Phase 3.**

---

## Success Criteria

1. **Correctness:** Parallel mode produces identical output to sequential mode
2. **No duplicates:** Element count matches exactly between modes
3. **No exceptions:** No NPE, ArrayIndexOutOfBounds, or ConcurrentModificationException
4. **Deterministic:** Multiple parallel runs produce identical results
5. **Performance:** No significant regression in parallel mode performance

---

## Notes

- This implements the "Hybrid (C)" approach: locking/synchronization first
- Proxy-based deferred writes (Option A) can be added later if locking proves insufficient
- tatami-base already has synchronized helpers; this focuses on framework fixes
- Cache key change from `(source, targetType)` to `(source, ruleName)` is a breaking change for cache behavior
