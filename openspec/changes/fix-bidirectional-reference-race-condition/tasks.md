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

## Remaining Issue (Separate)

1-8 duplicate elements still being created - this is a separate race condition related to duplicate rule execution, not bidirectional references. Should be tracked in a separate proposal.
