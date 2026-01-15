# Tasks: Fix Greedy Rule Same-Source Deadlock

## Status: Implemented

## Phase 1: Add Source-Level Locking

- [x] **1.1** Add `sourceLocks` ConcurrentHashMap to `TransformationExecutor`
  - Key: `EObject` (source element)
  - Value: `ReentrantLock`
  - Purpose: Serialize all eager rules for the same source element

- [x] **1.2** Modify `TransformationExecutor.transformChunk()` to acquire source lock
  - Wrap the eager rule execution with source lock
  - Use `tryLock(30, TimeUnit.SECONDS)` for timeout-based deadlock detection
  - Release lock in finally block

- [x] **1.3** Clear `sourceLocks` in executor reset/cleanup
  - Add to state reset between transformations
  - Prevent memory leaks for long-lived executors

## Phase 2: Unit Tests

- [x] **2.1** Create test for same-source greedy rule deadlock prevention
  - Multiple @Greedy rules for same source type
  - One rule calls `equivalent()` to look up another's target
  - Verify no deadlock occurs (test completes within timeout)
  - File: `SourceLevelLockingTest.java`

- [x] **2.2** Create stress test with high concurrency
  - 200 source elements
  - 4 greedy rules per source type
  - Rules call `equivalent()` on main target
  - Verify correct element counts and no deadlocks
  - 5 repeated runs to catch intermittent issues

- [x] **2.3** Verify cross-source parallelism still works
  - Different source elements execute in parallel (verified by fast completion ~20ms)
  - Only same-source rules are serialized

## Phase 3: Integration Testing

- [x] **3.1** Run existing parallel transformation tests
  - All 575 tests passed
  - No regressions in element counts or ordering

- [x] **3.2** Test with PSM2ASM-like rule patterns
  - Created test rules mimicking the deadlock scenario
  - Main target rule (CreateMainTarget) + annotation rules (CreateAnnotation, etc.)
  - Verified correct execution without deadlock

## Phase 4: Documentation

- [x] **4.1** Update `docs/transformation/architecture/parallel-execution.md`
  - Documented source-level locking for greedy rules
  - Explained why this prevents the deadlock pattern

- [x] **4.2** Update `agent-docs/EXECUTION.md`
  - Added section on greedy rule serialization per source

## Acceptance Criteria

1. [x] PSM2ASM transformation pattern completes without deadlock timeout
2. [x] All existing tests pass (575 tests)
3. [x] Element counts match expected values (200 sources × 4 rules)
4. [x] No new warnings or errors in logs
5. [x] Performance impact acceptable (transformations complete in ~20ms)

## Dependencies

- None (self-contained change in judo-zeta)

## Parallelizable Work

- Phase 1 and Phase 2 can be done in parallel ✓
- Phase 3 depends on Phase 1 + 2 ✓
- Phase 4 can be done after Phase 1 ✓

## Rollback Plan

If issues arise:
1. Add `executor.useSourceLocking(false)` configuration option
2. Fall back to current per-rule locking behavior
