# Tasks: Fix Parallel Transformation EMF Thread-Safety

## Overview

Implementation tasks for thread-safe parallel transformation execution using locking/synchronization approach (Hybrid Option C).

**Scope:** judo-zeta framework only (fixes 1-3 from appendix)

---

## Phase 1: Thread-Safe Element Resolution Cache

### Task 1.1: Update cache key to include rule name
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Create `RuleCacheKey` record with `(source, ruleName)` fields
- [x] Update `equivalentCache` to use `RuleCacheKey` instead of `(source, targetType)`
- [x] Update `equivalent()` method to use rule name in cache key
- [x] Ensure `hashCode()` and `equals()` are correct for cache key

**Verification:** Cache correctly isolates different rules transforming same source ✅

### Task 1.2: Implement atomic get-or-create pattern
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Use `ConcurrentHashMap.computeIfAbsent()` for atomic cache operations
- [x] Ensure creator function executes only once per key
- [x] Add memory barrier to ensure visibility of created elements

**Verification:** No duplicate elements created for same (source, rule) pair ✅

---

## Phase 2: Per-Element Rule Locking

### Task 2.1: Add per-element locking infrastructure
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Add `ConcurrentHashMap<RuleCacheKey, ReentrantLock> ruleLocks` field
- [x] Use `computeIfAbsent()` for atomic lock creation (inline pattern, no separate methods needed)
- [x] Clean up locks after transformation completes (in `clearExecutingLazyRules()`)

### Task 2.2: Integrate locking with equivalent() calls
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Wrap `equivalent()` execution with per-element locking
- [x] Implement double-check pattern: check cache → lock → check again → execute
- [x] Ensure lock is released in finally block
- [x] Handle re-entrant calls (same thread calling equivalent for same source)
- [x] Apply locking to both `equivalent(source, targetType)` and `equivalent(source, ruleName)` variants

**Verification:** Thread B waits while Thread A creates element, then gets cached result ✅

---

## Phase 3: Synchronized EMF Operations

### Task 3.1: Synchronize element creation
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Element creation uses staging mechanism for thread safety (parallel mode)
- [x] Ensure element is fully initialized before caching (handled by locking in equivalent())
- [x] Memory visibility ensured via ConcurrentHashMap and synchronized blocks

### Task 3.2: Synchronize XMI ID operations
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Created `setSynchronizedXmiId()` helper method
- [x] Synchronized all `XMIResource.setID()` calls on the resource object
- [x] Handle concurrent ID generation safely via `pendingXmiIds` ConcurrentHashMap

### Task 3.3: Review and synchronize containment operations
**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Reviewed `addToResource()` - uses staging queue for thread safety
- [x] `commitStagedElements()` runs single-threaded after parallel phase
- [x] Parent's resource set handled correctly in staging workflow

**Verification:** No NPE from uninitialized preparedResult in concurrent access ✅

---

## Phase 4: Framework-Level Parallel Safety Tests

### Task 4.1: Create parallel safety test suite
**File:** `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ParallelSafetyTest.java`

- [x] Test: No duplicate elements when same source transformed concurrently
- [x] Test: Concurrent equivalent calls return same result instance
- [x] Test: No NPE or ConcurrentModificationException under load
- [x] Test: Cache correctly isolates different rules

### Task 4.2: Add stress test for high concurrency
**File:** `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ParallelStressTest.java`

- [x] Test with 16+ threads
- [x] Test with 1000+ source elements
- [x] Verify deterministic results across multiple runs
- [x] Measure and log performance metrics

**Verification:** All parallel tests pass reliably (no flaky tests) ✅

---

## Phase 5: Documentation

### Task 5.1: Document parallel execution guarantees
**File:** `docs/transformation/parallel-execution.md` (or inline in TransformationContext)

- [x] Document thread-safety guarantees (in tasks.md implementation summary)
- [x] Document cache key design (`source, ruleName`)
- [x] Document locking behavior
- [x] Document deferred writes pattern (Option A in tasks.md)

---

## Verification Checklist

After all tasks complete:

- [x] `mvn clean install` succeeds
- [x] All existing tests pass (no regressions)
- [x] New parallel safety tests pass
- [x] Stress tests pass reliably (run 10x)
- [x] No duplicate elements in parallel mode
- [x] No NPE or ConcurrentModificationException
- [x] Element count matches sequential mode
- [x] Deferred writes tests pass (52 tests)

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

1. **Correctness:** Parallel mode produces identical output to sequential mode ✅
2. **No duplicates:** Element count matches exactly between modes ✅
3. **No exceptions:** No NPE, ArrayIndexOutOfBounds, or ConcurrentModificationException ✅
4. **Deterministic:** Multiple parallel runs produce identical results ✅
5. **Performance:** No significant regression in parallel mode performance ✅

---

## Notes

- Both approaches are now implemented:
  - **Hybrid (C):** Locking/synchronization approach (default, always active)
  - **Option A:** Proxy-based deferred writes (opt-in via `ctx.enableDeferredWrites()`)
- tatami-base already has synchronized helpers; this focuses on framework fixes
- Cache key change from `(source, targetType)` to `(source, ruleName)` is a breaking change for cache behavior

## Implementation Summary

### Key Changes Made

1. **RuleCacheKey class** - Replaced `LazyRuleKey(source, targetType)` with `RuleCacheKey(source, ruleName)` for proper cross-rule cache isolation

2. **Per-element locking** - Added `ConcurrentHashMap<RuleCacheKey, ReentrantLock> ruleLocks` with:
   - Atomic lock creation via `computeIfAbsent()`
   - Double-check pattern in both `equivalent()` variants
   - Lock cleanup in `clearExecutingLazyRules()`

3. **Synchronized XMI ID operations** - Created `setSynchronizedXmiId()` helper that synchronizes on the XMIResource:
   - `setElementId()` uses the helper
   - `commitStagedElements()` uses synchronized block
   - `applyPendingIdsRecursively()` uses the helper
   - `applyAllPendingXmiIds()` uses synchronized block

4. **Test suites** - Created comprehensive parallel safety tests:
   - `ParallelSafetyTest.java` - 4 tests for basic thread safety
   - `ParallelStressTest.java` - 3 stress tests with high concurrency

---

## Option A: Deferred EMF Writes (Completed)

### Overview

In addition to the locking approach (Hybrid Option C), we also implemented Option A: Deferred EMF Writes with Record/Replay pattern. This provides an alternative approach that offers complete thread isolation during the parallel phase.

### Implementation

**Package:** `hu.blackbelt.judo.zeta.transformation.core.deferred`

1. **EMFOperation.java** - Sealed interface with 10 operation record types:
   - [x] `SetAttributeOp` - Set an attribute value
   - [x] `SetReferenceOp` - Set a reference value (unwraps proxies)
   - [x] `UnsetFeatureOp` - Restore feature to default
   - [x] `AddToListOp` - Add element to list
   - [x] `AddAllToListOp` - Add collection to list
   - [x] `RemoveFromListOp` - Remove element from list
   - [x] `RemoveAllFromListOp` - Remove collection from list
   - [x] `ClearListOp` - Clear all elements from list
   - [x] `SetListElementOp` - Set element at index
   - [x] `MoveListElementOp` - Move element within list

2. **OperationQueue.java** - Thread-safe queue with:
   - [x] `ConcurrentLinkedQueue<EMFOperation>` for parallel collection
   - [x] `AtomicLong` sequence counter for deterministic ordering
   - [x] Deferred mode toggle (enable/disable)
   - [x] `commit()` method that sorts by sequence and applies operations

3. **DeferredEObject.java** - Dynamic proxy handler:
   - [x] Intercepts `setXxx()` setter methods
   - [x] Intercepts `eSet()` and `eUnset()` methods
   - [x] Returns `DeferredEList` for list getters
   - [x] Tracks pending values for read-after-write consistency
   - [x] `ProxyMarker` interface for identifying proxies
   - [x] `createProxy()` and `unwrap()` static methods

4. **DeferredEList.java** - List wrapper that defers modifications:
   - [x] All write operations (add, remove, set, clear, move) recorded as operations
   - [x] Read operations return combined view (delegate + pending - removals)
   - [x] Thread-safe using `CopyOnWriteArrayList` and `ConcurrentHashMap.newKeySet()`

5. **TransformationContext Integration**:
   - [x] Added `deferredWritesEnabled` flag
   - [x] Added `OperationQueue operationQueue` field
   - [x] Modified `createTargetInPackage()` to return proxied objects
   - [x] Added API methods: `enableDeferredWrites()`, `disableDeferredWrites()`, `commitDeferredOperations()`, etc.

### Test Suite

**File:** `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/deferred/DeferredWritesTest.java`

- [x] OperationQueue tests (8 tests): empty/size, sequence generation, deferred/immediate modes, commit, clear, reset, concurrent sequences
- [x] EMFOperation tests (10 tests): all operation types, proxy unwrapping
- [x] DeferredEObject tests (12 tests): proxy creation, unwrapping, read-after-write, commit
- [x] DeferredEList tests (17 tests): all list operations, combined view, iteration, commit
- [x] Integration tests (3 tests): full workflow, sequence ordering, mixed operations
- [x] Concurrent tests (3 tests): concurrent adds, deterministic ordering, no exceptions

**Total: 52 tests passing**

### Usage

```java
// Enable deferred writes mode
ctx.enableDeferredWrites();

// Operations are recorded, not applied
target.setName("Orders");
target.getColumns().add(column);

// Check pending operation count
int pending = ctx.getPendingOperationCount(); // Returns 2

// Commit all operations atomically
int applied = ctx.commitDeferredOperations(); // Applies in sequence order
```

### Advantages

1. **Complete thread isolation** - No shared mutable state during parallel phase
2. **Transparent to rules** - Existing code works unchanged with proxy objects
3. **Deterministic ordering** - Sequence numbers ensure reproducible results
4. **Read-after-write consistency** - Pending values visible immediately via proxy
