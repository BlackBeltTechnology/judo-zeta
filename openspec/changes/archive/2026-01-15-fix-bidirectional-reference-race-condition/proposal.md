# Proposal: Fix Bidirectional Reference Race Condition

**Change ID**: fix-bidirectional-reference-race-condition
**Status**: Draft
**Created**: 2026-01-04

## Problem Statement

When deferred writes are enabled for parallel transformation, bidirectional EMF references fail validation with:

```
The opposite features 'transferObjectType' of MappedActorTypeImpl
and 'actorType' of MappedTransferObjectTypeImpl do not refer to each other
```

This means one side of the bidirectional relationship points correctly, but the inverse does not point back.

### Root Cause (Confirmed via Debugging)

When `SetReferenceOp.apply()` executes `target.eSet(feature, value)`, EMF's generated setter calls:

```java
((InternalEObject)newValue).eInverseAdd(this, OPPOSITE_FEATURE_ID, ...);
```

The problem is that **Zeta's JDK dynamic proxy is being passed to EMF's inverse handling mechanism**, which expects real `InternalEObject` instances. The proxy doesn't properly participate in EMF's `eInverseAdd`/`eInverseRemove` protocol, causing the inverse reference to not be set.

Debug output showing the issue:
```
target         = jdk.proxy2.$Proxy52  (ZETA PROXY - wrong!)
principalPsmTO = MappedTransferObjectTypeImpl  (real EMF object)
```

### Why This Happens

In `DeferredEObject.handleSet()`, the value is stored as-is (potentially a proxy):

```java
queue.add(new EMFOperation.SetReferenceOp(
    delegate, feature, refValue, queue.nextSequence()  // refValue may be a proxy!
));
```

While `SetReferenceOp.apply()` tries to unwrap:
```java
if (value instanceof DeferredEObject.ProxyMarker) {
    realValue = ((DeferredEObject.ProxyMarker) value).getDelegate();
}
```

This unwrapping happens too late - EMF's internal machinery may have already cached or used the proxy reference.

## Proposed Solution

**Unwrap proxy values at queue time, not at apply time.**

The fix is simple: in `DeferredEObject.handleSet()`, unwrap the value BEFORE queueing the operation. This ensures EMF never sees a proxy during inverse handling.

### Implementation

Modify `DeferredEObject.handleSet()`:

```java
private void handleSet(EStructuralFeature feature, Object value) {
    // Store pending value for read-after-write consistency
    if (value != null) {
        pendingValues.put(feature, value);
    } else {
        pendingValues.remove(feature);
    }

    // Queue the operation
    if (feature instanceof EReference) {
        EObject refValue = (EObject) value;
        // CRITICAL: Unwrap proxy BEFORE queueing to ensure EMF's inverse
        // handling receives real EObject instances, not JDK proxies
        EObject realRefValue = unwrap(refValue);
        queue.add(new EMFOperation.SetReferenceOp(
                delegate, feature, realRefValue, queue.nextSequence()
        ));
    } else {
        queue.add(new EMFOperation.SetAttributeOp(
                delegate, feature, value, queue.nextSequence()
        ));
    }
}
```

And simplify `SetReferenceOp.apply()` (unwrapping already done):

```java
@Override
public void apply() {
    // Value is already unwrapped at queue time
    target.eSet(feature, value);
}
```

## Affected Components

| Component | Change |
|-----------|--------|
| `DeferredEObject.handleSet()` | Unwrap reference values before queueing |
| `DeferredEList` | Unwrap elements before queueing add/addAll operations |
| `EMFOperation.SetReferenceOp` | Remove apply-time unwrapping (optional cleanup) |
| `EMFOperation.AddToListOp` | Remove apply-time unwrapping (optional cleanup) |

## Why This Works

1. **Queue time**: `handleSet()` unwraps proxy → stores real EObject in operation
2. **Apply time**: `target.eSet(feature, realValue)` passes real objects to EMF
3. **EMF inverse**: `eInverseAdd(this, ...)` receives real `InternalEObject`
4. **Result**: Bidirectional references are correctly maintained

## Alternatives Considered

### Alternative A: Fix proxy to implement InternalEObject properly
Make the JDK proxy properly delegate all `InternalEObject` methods.
- **Pro**: Proxy behaves like real EMF object
- **Con**: Very complex - InternalEObject has many methods
- **Con**: Risk of subtle EMF compatibility issues

### Alternative B: Don't proxy objects with bidirectional references
Detect bidirectional references and skip proxy wrapping.
- **Pro**: Avoids the issue entirely
- **Con**: Hard to detect all bidirectional refs at creation time
- **Con**: Inconsistent behavior

**Decision**: Unwrap at queue time is simplest and most reliable.

## Spec Deltas

### parallel-transformation

Add requirement for bidirectional reference handling during deferred writes.

## Testing Strategy

1. **Unit test**: Create EMF model with bidirectional references, verify deferred writes work correctly
2. **Integration test**: Parallel transformation with `MappedActorType` ↔ `MappedTransferObjectType` pattern
3. **Stress test**: High concurrency with many bidirectional reference pairs

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Detection logic incorrect | Medium | High | Comprehensive test coverage |
| Performance overhead of checking | Low | Low | Use efficient queue lookup |
| Edge cases with multiple opposites | Low | Medium | Test diamond-shaped references |
