# Tasks: Fix Bidirectional Reference Race Condition

## Status: COMPLETED

## Phase 1: Fix - Unwrap at Queue Time

- [x] **1.1** Modify `DeferredEObject.handleSet()` to unwrap reference values
  - Call `unwrap(refValue)` before creating `SetReferenceOp`
  - Keep `pendingValues` storing the original (proxy) for read-after-write consistency

- [x] **1.2** `DeferredEList` already unwraps in `EMFOperation` apply methods
  - No additional changes needed (unwrapping happens at apply time for lists)

## Phase 2: Cleanup

- [x] **2.1** Remove redundant unwrapping in `SetReferenceOp.apply()`
  - Value is already unwrapped at queue time

- [x] **2.2** Remove redundant unwrapping in list operations
  - `AddToListOp`, `AddAllToListOp`, `SetListElementOp`, `RemoveFromListOp`, etc.
  - Already handled by `EMFOperation.unwrapIfProxy()` static helper

## Phase 3: Validation

- [x] **3.1** EMF opposite reference validation errors are FIXED
  - No more "opposite features do not refer to each other" errors

## Summary of Changes

### judo-zeta (this repo)
- `DeferredEObject.handleSet()`: Unwrap reference values before queueing
- `EMFOperation.SetReferenceOp.apply()`: Simplified (no unwrapping needed)

### judo-tatami-esm2psm (downstream)
- `ActorRules.java`: Unwrap target before `principalPsmTO.setActorType()`

## Phase 4: Fix Deadlock in Unified Locking

- [x] **4.1** Investigate deadlock cause in unified locking approach
  - 4 tests timing out with "Potential deadlock detected"
  - Root cause: Lock striping + nested locking causes cross-thread deadlock

- [x] **4.2** Revert to per-key locks in TransformationContext
  - Changed 4 locations from `resolutionCache.getLockFor()` to `ruleLocks.computeIfAbsent()`
  - Lines 1120, 1489, 1645, 1939 now use per-key locks
  - Also changed blocking `lock.lock()` to `tryLock(30, SECONDS)` at lines 1489 and 1939

- [x] **4.3** Verify all tests pass
  - All 575 tests pass with 0 failures

## Summary of Changes

### TransformationContext.java - Locking Fix
- Line 1120: Changed from `resolutionCache.getLockFor()` to `ruleLocks.computeIfAbsent()`
- Line 1489: Changed from blocking `lock.lock()` to `tryLock(30, SECONDS)` with per-key lock
- Line 1645: Changed from `resolutionCache.getLockFor()` to `ruleLocks.computeIfAbsent()`
- Line 1939: Changed from blocking `lock.lock()` to `tryLock(30, SECONDS)` with per-key lock

### Root Cause of Deadlock
Lock striping (1024 fixed locks) with nested locking causes deadlocks when:
1. Thread A holds stripe[X] for (source1, ruleA), waits for stripe[Y] for (source1, ruleB)
2. Thread B holds stripe[Y] for (source2, ruleB), waits for stripe[X] for (source2, ruleA)
3. Different (source, rule) pairs can hash to same stripe → collision → deadlock

Per-key locks avoid this because each (source, rule) pair has its own unique lock.

## Remaining Issue (Separate)

1-8 duplicate elements still being created - this is a separate race condition related to duplicate rule execution, not bidirectional references. Should be tracked in a separate proposal.
