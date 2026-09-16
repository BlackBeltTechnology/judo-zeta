# Proposal: Fix EMF Containment Race Condition in Parallel Execution

## Why

ASM2RDBMS parallel transformation fails with `NullPointerException` in `EcoreUtil$ProperContentIterator.hasNext()`:

```
java.lang.NullPointerException: Cannot invoke "org.eclipse.emf.ecore.InternalEObject.eDirectResource()"
because "this.preparedResult" is null
```

**Root Cause**: During parallel rule execution, multiple threads concurrently modify EMF EList collections via `table.getFields().add(field)`. EMF ELists (BasicEList) are not thread-safe - concurrent modifications corrupt internal state (modCount, data[], size). When `commitStagedElements()` iterates the corrupted model, `iterator.next()` returns corrupted internal state causing the NPE.

**Why ASM2RDBMS is affected but PSM2ASM works**:
- ASM2RDBMS has **flat structure** with few large collections (RdbmsModel → Tables → Fields)
- PSM2ASM has **deep hierarchy** with many small collections (lower contention)
- Higher containment contention in ASM2RDBMS triggers the race condition more frequently

## What Changes

### 0. Create Failing Tests First (TDD)

Before implementing the fix, create tests that reproduce the bug:

- **NPE reproduction test** - Flat structure with concurrent containment adds
- **Stress test** - 100 elements to 5 shared parents, repeated 10 times
- **Determinism test** - Verify element counts vary between runs

**All tests must FAIL before implementation begins.**

### 1. Enable Deferred Writes for Parallel Execution

The deferred writes infrastructure (`DeferredEObject`, `DeferredEList`, `OperationQueue`) already exists but is NOT enabled during parallel transformation. Enable it to defer containment operations:

- **Auto-enable with opt-out**: Deferred writes automatically enabled when `parallel=true`
- Transformations can call `context.disableDeferredWrites()` to opt-out if needed
- **Lists only**: Only EList add/remove operations are deferred (not property setters)
- `createTarget()` returns proxied EObjects when deferred writes enabled
- Queue is replayed single-threaded after parallel phase completes

### 2. Replay Deferred Operations Before Commit

Add operation replay phase between parallel execution and `commitStagedElements()`:

```
Phase 1: Parallel transformation (deferred writes)
Phase 2: Replay deferred operations (single-threaded)
Phase 3: Commit staged elements to Resource
```

### 3. Verify Tests Pass

After implementation, all Phase 0 tests must PASS.

## Files Modified

1. `ContainmentRaceConditionTest.java` - NEW: Failing tests for bug reproduction
2. `TransformationExecutor.java` - Enable deferred writes, add replay phase
3. `TransformationContext.java` - Add `applyDeferredOperations()` method
4. `OperationQueue.java` - Add `drainSorted()` method
