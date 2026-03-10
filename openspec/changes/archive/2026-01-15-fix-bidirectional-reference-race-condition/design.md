# Design: Fix Bidirectional Reference Race Condition

## Root Cause Analysis

### EMF Bidirectional Reference Mechanics

When an EReference has an `eOpposite`, EMF maintains referential integrity automatically:

```java
// EMF generated setter (simplified)
public void setTransferObjectType(MappedTransferObjectType newValue) {
    MappedTransferObjectType oldValue = this.transferObjectType;
    this.transferObjectType = newValue;

    if (newValue != null) {
        // EMF's inverse handling
        ((InternalEObject)newValue).eInverseAdd(
            (InternalEObject)this,  // <-- MUST be real EObject!
            ACTOR_TYPE_FEATURE_ID,
            ...
        );
    }
}
```

### The Problem with Proxies

When Zeta's deferred writes are enabled:

1. `createTarget()` returns a JDK dynamic proxy
2. Transformation rules work with proxies
3. When `proxyA.setRef(proxyB)` is called:
   - Proxy intercepts, queues `SetReferenceOp(delegateA, feature, proxyB)`
   - Note: `delegateA` is correct, but `proxyB` is stored!

4. During commit, `apply()` tries to unwrap:
   ```java
   if (value instanceof ProxyMarker) {
       realValue = ((ProxyMarker) value).getDelegate();
   }
   target.eSet(feature, realValue);
   ```

5. But EMF's `eInverseAdd(this, ...)` uses `this` from the setter context

The issue: If the unwrapping happens too late or incompletely, EMF's internal machinery receives proxy references instead of real `InternalEObject` instances.

### Debug Evidence

```
target         = jdk.proxy2.$Proxy52  (ZETA PROXY - BAD!)
principalPsmTO = MappedTransferObjectTypeImpl  (real EMF object)
```

Result: EMF validation fails with:
```
The opposite features 'transferObjectType' of MappedActorTypeImpl
and 'actorType' of MappedTransferObjectTypeImpl do not refer to each other
```

## Solution: Unwrap at Queue Time

### Principle

**Never store proxy references in deferred operations.** Unwrap immediately when the operation is created, not when it's applied.

### Before (Broken)

```java
// DeferredEObject.handleSet()
private void handleSet(EStructuralFeature feature, Object value) {
    pendingValues.put(feature, value);  // Stores proxy

    if (feature instanceof EReference) {
        EObject refValue = (EObject) value;  // Still a proxy!
        queue.add(new SetReferenceOp(delegate, feature, refValue, seq));
    }
}

// SetReferenceOp.apply() - too late!
public void apply() {
    Object realValue = value;
    if (value instanceof ProxyMarker) {
        realValue = ((ProxyMarker) value).getDelegate();
    }
    target.eSet(feature, realValue);
}
```

### After (Fixed)

```java
// DeferredEObject.handleSet()
private void handleSet(EStructuralFeature feature, Object value) {
    pendingValues.put(feature, value);  // Keep proxy for read-after-write

    if (feature instanceof EReference) {
        EObject refValue = (EObject) value;
        // UNWRAP NOW - before storing in operation
        EObject realRefValue = unwrap(refValue);
        queue.add(new SetReferenceOp(delegate, feature, realRefValue, seq));
    }
}

// SetReferenceOp.apply() - clean, no unwrapping needed
public void apply() {
    target.eSet(feature, value);  // value is already real EObject
}
```

## Changes Required

### DeferredEObject.java

```java
private void handleSet(EStructuralFeature feature, Object value) {
    // Store pending value (may be proxy) for read-after-write consistency
    if (value != null) {
        pendingValues.put(feature, value);
    } else {
        pendingValues.remove(feature);
    }

    // Queue the operation with UNWRAPPED value
    if (feature instanceof EReference) {
        EObject refValue = (EObject) value;
        EObject realRefValue = unwrap(refValue);  // NEW: Unwrap here
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

### DeferredEList.java

Similar changes for `add()`, `addAll()`, `set()`:

```java
@Override
public void add(int index, E element) {
    // Track for read-after-write
    pendingAdditions.add(element);

    // UNWRAP before queueing
    Object realElement = DeferredEObject.unwrap(element);
    queue.add(new EMFOperation.AddToListOp(
            owner, feature, realElement, index, queue.nextSequence()
    ));
}
```

### EMFOperation.java (Optional Cleanup)

Remove now-redundant unwrapping in `apply()` methods:

```java
record SetReferenceOp(...) implements EMFOperation {
    @Override
    public void apply() {
        // No unwrapping needed - value is already real EObject
        target.eSet(feature, value);
    }
}
```

## Why pendingValues Keeps Proxies

The `pendingValues` map is used for read-after-write consistency:

```java
proxy.setRef(otherProxy);
EObject result = proxy.getRef();  // Should return otherProxy, not delegate!
```

Rules may rely on getting back the same proxy they set. So:
- `pendingValues` stores the original value (proxy)
- `SetReferenceOp` stores the unwrapped value (delegate)

This provides correct behavior for both:
1. Read-after-write in transformation rules
2. EMF inverse handling during commit

## Thread Safety

The fix doesn't change thread safety characteristics:
- `unwrap()` is a simple `instanceof` check + cast
- `ConcurrentLinkedQueue.add()` is already thread-safe
- No new synchronization needed
