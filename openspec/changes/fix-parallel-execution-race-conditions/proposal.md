# Proposal: Fix Parallel Execution Race Conditions

**Change ID:** fix-parallel-execution-race-conditions
**Status:** In Progress
**Created:** 2026-01-03
**Updated:** 2026-01-03
**Blocks:** optimize-greedy-rule-performance

## Related Proposals

| Proposal | Relationship |
|----------|--------------|
| `optimize-greedy-rule-performance` | **Blocked by this proposal** - Performance optimizations should not proceed until race conditions are fixed. Optimizing before fixing correctness risks making corruption happen faster or hiding bugs with different timing. |

> **Important:** Both proposals involve `getOrCreate()` and locking mechanisms. This proposal adds locking for thread safety, while the performance proposal aims to reduce locking overhead. The performance proposal must be revisited after this fix to ensure optimizations don't reintroduce race conditions.

## Implementation Progress

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1.3.2 | ✅ Complete | Fixed `equivalentDiscriminated()` race condition |
| Phase 1.2 | ❌ CRITICAL | EMF containment operations NOT thread-safe |
| Phase 2 | ⏸️ Deferred | Long-term optimization |

### Critical Issue: EMF Containment Race Condition

**Reported from Tatami project production usage.**

**Status:** ⚠️ Needs reproduction with tests before implementing fix.

When `autoAddRootElements=true` is set:

```
Thread 1                           Thread 2
────────                           ────────
icon = createTarget(Icon.class)
  → staged with isRootElement=true
                                   parent.setIcon(icon)
                                     → EMF bidirectional update starts
icon added to staging queue          → eContainer being set
                                     → RACE: container ref corrupted

During commit:
  icon.eContainer() == null  ← ORPHANED!
  → icon added to resource root (DUPLICATE!)
```

**Evidence from Zeta documentation (parallel-execution.md):**

| Operation | Thread-Safe | Recommendation |
|-----------|-------------|----------------|
| Direct Resource modification | ❌ No | Avoid in parallel rules |

**Impact:** Orphaned elements with `eContainer() == null` get incorrectly added as root elements during commit phase.

## Problem Statement

Parallel execution in ZETA provides significant speedup (4.13x observed) but has critical race conditions that cause model corruption and runtime errors in production transformations with complex interdependencies.

### Observed Issues

| Issue | Symptom | Root Cause |
|-------|---------|------------|
| NPE in EMF Iteration | `NullPointerException: Cannot invoke eDirectResource()` | EMF's internal model traversal not thread-safe |
| Element Count Mismatch | 23,347 vs 23,335 elements (12 extra) | Race in cache operations causing duplicates |
| Null Container References | `Called feature 'container' on undefined object` | Unsynchronized containment list modifications |

### Performance Trade-off

| Mode | Time | Elements | Status |
|------|------|----------|--------|
| Sequential | 9,236 ms | 23,335 | Valid |
| Parallel | 2,239 ms | 23,347 | Invalid |

**Speedup: 4.13x faster, but with data corruption**

## Current State Analysis

### What Works: equivalent() with Locking

The `equivalent()` method with proper locking handles these scenarios correctly:
- Lazy rule execution with equivalent() caching (no duplicates)
- High contention: 6 eager rules calling equivalent() on same 5000 sources
- Cross-rule reference chains (A→B→C via equivalent())
- @Extends inheritance under parallel execution

**Test Results for equivalent():**
- High contention: 5000 lazy executions for 5000 sources (100% correct)
- All stress test iterations pass

### BUG FIXED: equivalentDiscriminated() Race Condition

**Test:** `EquivalentDiscriminatedRaceTest.testCrossEntityReferenceRace`

The `equivalentDiscriminated()` method **lacked locking** and created duplicate clones (now fixed):

| Metric | Value |
|--------|-------|
| Referrers accessing shared entity | 500 |
| Discriminators per call | 6 |
| Total invocations | 3,000 |
| Expected unique clones | 6 |
| **Actual clones per run** | **7-58 (varies)** |
| **Extra duplicates** | **1-52 per run** |

**Root Cause:** `TransformationContext.equivalentDiscriminated()` (lines 1251-1419):

```
Thread A: cache.check() → MISS
Thread B: cache.check() → MISS (before A caches)
Thread A: clone = EcoreUtil.copy(original)
Thread B: clone = EcoreUtil.copy(original)  ← DUPLICATE!
Thread A: stagedElements.add(clone); cache.put(clone)
Thread B: stagedElements.add(clone); cache.put(clone)
```

**Code Location:**
1. Line 1276: Checks discriminated cache (NO LOCK)
2. Line 1366: Creates clone via `EcoreUtil.copy()`
3. Lines 1388-1406: Adds clone to stagedElements
4. Line 1412: Caches clone in discriminatedCache

**Fix Applied:** Added double-checked locking similar to `equivalent()`:

1. Added `DiscriminatedCacheKey` class for per-key locking
2. Added `discriminatedLocks` ConcurrentHashMap
3. Wrapped clone creation in lock/try/finally block
4. Double-check cache after acquiring lock

**Result:** All 30 stress test runs now pass with exactly 6 clones (0 duplicates).

**Location:** `TransformationContext.java` lines 303-335 (key class), 1403-1475 (locking)

## Proposed Long-Term Solution

### Architecture: Thread-Isolated Transformation

Replace shared-state parallel execution with a thread-isolated architecture:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Current: Shared State Architecture                    │
│                                                                          │
│   Worker 1 ─────┬──────► Shared Resolution Cache ◄──────┬───── Worker 2 │
│                 │              (Race!)                   │               │
│   Worker 3 ─────┴──────► Shared EMF Resource ◄──────────┴───── Worker 4 │
│                              (Race!)                                     │
└─────────────────────────────────────────────────────────────────────────┘

                                    ↓

┌─────────────────────────────────────────────────────────────────────────┐
│                    Proposed: Thread-Isolated Architecture                │
│                                                                          │
│   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                   │
│   │  Worker 1   │   │  Worker 2   │   │  Worker 3   │                   │
│   │  ─────────  │   │  ─────────  │   │  ─────────  │                   │
│   │ Local Cache │   │ Local Cache │   │ Local Cache │                   │
│   │ Local Queue │   │ Local Queue │   │ Local Queue │                   │
│   └──────┬──────┘   └──────┬──────┘   └──────┬──────┘                   │
│          │                 │                 │                           │
│          └────────────────►▼◄────────────────┘                           │
│                    Merge Phase (Single-Threaded)                         │
│                    ─────────────────────────────                         │
│                    • Merge local caches                                  │
│                    • Commit to EMF Resource                              │
│                    • Resolve cross-partition refs                        │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Design Principles

1. **Partition-Based Processing**
   - Partition source elements by container/type to minimize cross-partition references
   - Each partition processed by single thread with isolated state

2. **Deferred Cross-Partition References**
   - References to elements in other partitions are recorded as placeholders
   - Resolved during single-threaded merge phase

3. **Lock-Free Local Operations**
   - Each thread has its own cache and staging queue
   - No synchronization needed for local operations

4. **Atomic Merge Phase**
   - Single thread merges all local caches
   - Resolves cross-references atomically
   - Commits to EMF Resource in deterministic order

## Implementation Strategy

**Decision:** Implement both Phase 1 and Phase 2 sequentially. Phase 1 provides immediate safety while Phase 2 delivers the optimal long-term solution.

**Activation:** Thread-isolated mode auto-selects for models with >1000 elements.

### Phase 1: Immediate Fixes (Short-Term)

Deploy first to provide immediate safety for production.

1. **Add Global Lock for Cross-Thread Cache Access**
   - Fallback synchronization when local cache misses
   - Performance impact acceptable for correctness

2. **Synchronize ALL EMF Containment Operations** *(EXPANDED SCOPE)*
   - **Step 1:** Reproduce the race condition with a failing test first
   - **Step 2:** Implement fix (one of the options below)
   - **Not just** `Resource.getContents().add()` calls
   - **ALL containment assignments** like `parent.setIcon(icon)`, `parent.getChildren().add(child)`
   - EMF bidirectional reference updates are NOT atomic
   - Use `synchronized(targetResource)` blocks around all containment operations
   - **Alternative:** Defer containment assignments to single-threaded commit phase

3. **Reproduce Production Issues**
   - Create stress tests that replicate exact production failure patterns
   - Test with high contention on same source elements
   - Test with complex cross-rule reference chains
   - Test with @Extends inheritance under parallel execution
   - **NEW:** Test `autoAddRootElements=true` with containment assignments

### Phase 2: Thread-Isolated Architecture (Long-Term)

Deploy after Phase 1 to eliminate synchronization overhead.

1. **Implement Partition Strategy**
   - Partition by source element container
   - Balance partition sizes
   - Auto-select for models >1000 elements

2. **Thread-Local Caches**
   - Each worker thread has isolated cache
   - No shared mutable state during transformation

3. **Merge Phase Implementation**
   - Merge local caches deterministically
   - Resolve cross-partition references
   - Commit to Resource

4. **Performance Validation**
   - Verify speedup maintained (target: 3x+)
   - Verify correctness on large models

## Success Criteria

1. All existing 444+ tests pass
2. New parallel stress tests pass with high contention
3. Production transformation produces identical results in sequential and parallel modes
4. Performance speedup ≥ 3x for large models
5. No race conditions detected under stress testing

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Performance regression from added synchronization | Phase 1 is temporary; Phase 2 eliminates need |
| Partition strategy may not eliminate all cross-refs | Deferred reference resolution handles edge cases |
| Complex merge logic may introduce bugs | Extensive testing, incremental implementation |
