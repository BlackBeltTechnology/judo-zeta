# Design: Fix EMF Containment Race Condition

## Problem Analysis

### EMF Thread-Safety Issue

EMF EList implementations (`BasicEList`, `EcoreEList`) are NOT thread-safe:

```java
public class BasicEList<E> extends AbstractEList<E> {
    protected int size;           // Not volatile
    protected transient Object[] data;  // Not volatile
    protected int modCount;       // Not volatile - used for fail-fast iteration
}
```

When multiple threads call `list.add(element)` concurrently:
1. Thread A reads `size=5`, prepares to write at index 5
2. Thread B reads `size=5`, prepares to write at index 5
3. Thread A writes element at index 5, sets `size=6`
4. Thread B writes element at index 5 (OVERWRITING Thread A's element), sets `size=6`
5. Result: Lost element, corrupted state

### Failure Timeline in ASM2RDBMS

```
PHASE 1: Parallel Rule Execution
────────────────────────────────────────────────────────────────────
  Thread-1                    Thread-2                    Thread-3
     │                           │                           │
     ▼                           ▼                           ▼
  Create RdbmsTable A        Create RdbmsTable B         Create RdbmsTable C
     │                           │                           │
     ▼                           ▼                           ▼
  Create RdbmsField A1       Create RdbmsField B1        Create RdbmsField C1
     │                           │                           │
     ▼                           ▼                           ▼
  table.getFields().add(A1)  table.getFields().add(B1)   table.getFields().add(C1)
     │                           │                           │
     └── EList internal state ───┴── RACE CONDITION! ───────┘
         (modCount, data[], size corrupted)

PHASE 2: Commit Staged Elements (Single-Threaded)
────────────────────────────────────────────────────────────────────
  Main Thread
     │
     ▼
  commitStagedElements()
     │
     ▼
  targetResource.getContents().add(root)
     │
     ▼
  ResourceImpl.attached(root)  ← Iterates ALL children
     │
     ▼
  EcoreUtil.getAllProperContents(root, false)
     │
     ▼
  ProperContentIterator.hasNext()
     │
     ▼
  iterator.next() returns CORRUPTED state → preparedResult = null
     │
     ▼
  ((InternalEObject)preparedResult).eDirectResource()  ← NPE!
```

## Existing Infrastructure

The Zeta framework already has deferred writes infrastructure:

| Component | Purpose | Status |
|-----------|---------|--------|
| `DeferredEObject` | Proxy that intercepts setters and list accessors | ✅ Implemented |
| `DeferredEList` | Wrapper that queues add/remove operations | ✅ Implemented |
| `OperationQueue` | Thread-safe queue for deferred operations | ✅ Implemented |
| `EMFOperation` | Sealed interface for operation types | ✅ Implemented |
| `enableDeferredWrites()` | Enables deferred mode in context | ✅ Implemented |
| Integration with parallel execution | Enables deferred writes during parallel phase | ❌ Not implemented |

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Activation mode | Auto-enable with opt-out | Fixes bug by default, allows opt-out for edge cases |
| Scope | Lists only | Property setters don't cause NPE, lower overhead |

## Solution Design

### Phase Integration

```
Current Flow:
┌─────────────────────────────────────────────────────────────┐
│  transformWithStaging()                                      │
│    1. enableStaging()           ← Queues root elements       │
│    2. transformParallel()       ← EList corrupted here!      │
│    3. commitStagedElements()    ← NPE when iterating         │
└─────────────────────────────────────────────────────────────┘

Fixed Flow:
┌─────────────────────────────────────────────────────────────┐
│  transformWithStaging()                                      │
│    1. enableStaging()           ← Queues root elements       │
│    2. enableDeferredWrites()    ← NEW: Proxies for setters   │
│    3. transformParallel()       ← Operations queued, no race │
│    4. applyDeferredOperations() ← NEW: Single-threaded apply │
│    5. commitStagedElements()    ← Safe iteration             │
└─────────────────────────────────────────────────────────────┘
```

### createTarget() Behavior Change

```java
public <T extends EObject> T createTarget(Class<T> targetClass) {
    T instance = factory.create(targetClass);

    // Queue for staging (existing)
    if (stagingEnabled.get()) {
        stagedElements.offer(new StagedElement(instance, true, seq));
    }

    // Wrap with deferred proxy (NEW)
    if (deferredWritesEnabled) {
        return DeferredEObject.createProxy(instance, operationQueue);
    }

    return instance;
}
```

### Operation Replay

```java
public void applyDeferredOperations() {
    // Sort by sequence for deterministic ordering
    List<EMFOperation> ops = operationQueue.drainSorted();

    // Apply single-threaded
    for (EMFOperation op : ops) {
        op.apply();
    }
}
```

## Why Not Per-Parent Synchronization?

Per-parent `synchronized(parent)` was considered but has issues:

| Approach | Pros | Cons |
|----------|------|------|
| Per-parent sync | Simple | Deadlock risk with bidirectional refs |
| | | Still allows corrupted iteration |
| | | Reduces parallelism |
| Deferred writes | True thread isolation | More complex |
| | Deterministic ordering | Memory overhead |
| | No deadlock risk | |

**Decision**: Use deferred writes because:
1. Infrastructure already exists
2. No synchronization = no deadlocks
3. Sequence numbers ensure deterministic results
4. Transparent to transformation rules

## Compatibility Issues

### 1. Read-After-Write Inconsistency

```java
// Rule code
parent.getChildren().add(child);
int count = parent.getChildren().size();  // Returns OLD size, not +1!
boolean found = parent.getChildren().contains(child);  // Returns false!
```

**Impact**: Rules that read from lists after modifying them will get stale data.

### 2. eContainer() Returns Null

```java
parent.getChildren().add(child);
EObject container = child.eContainer();  // Returns null until replay!
```

**Impact**: Containment-based navigation fails during parallel phase.

### 3. Cross-Rule Visibility

```java
// Rule A (Thread 1)
parent.getChildren().add(childA);

// Rule B (Thread 2) - running concurrently
for (Child c : parent.getChildren()) {  // Won't see childA!
    // ...
}
```

**Impact**: Rules can't see each other's additions during parallel phase.

### 4. Existing Synchronization Conflicts

```java
// If transformation already uses synchronized helpers:
TransformationHelper.synchronizedAdd(list, element);  // Double-handling?
```

**Impact**: May conflict with existing thread-safety workarounds.

### 5. List Order Dependencies

```java
// Insertion order may differ between sequential and parallel
parent.getChildren().add(a);  // seq=5
parent.getChildren().add(b);  // seq=3 (from different thread)
// After replay: order is [b, a] not [a, b]
```

**Impact**: Element ordering may change vs. sequential mode.

### Mitigation

The existing `DeferredEList` handles read-after-write by tracking pending additions:

```java
// From DeferredEList.java
public boolean contains(Object o) {
    if (pendingAdditions.contains(o)) {
        return true;  // Check pending first
    }
    return delegate.contains(o);
}
```

However, **cross-rule visibility** and **eContainer()** issues cannot be mitigated - they're inherent to deferred writes.

## Performance Impact

| Metric | Before | After | Impact |
|--------|--------|-------|--------|
| Parallel phase | Direct writes | Queued operations | Slightly faster (no sync) |
| Replay phase | N/A | O(n) single-threaded | New overhead |
| Memory | O(elements) | O(elements + operations) | ~1.5x during parallel |
| Total time | N/A (crashes) | Working | Correctness > speed |

## Testing Strategy

1. **Reproduction test**: ASM2RDBMS-like transformation with high containment contention
2. **Stress test**: 100 threads, 10,000 elements, repeated runs
3. **Determinism test**: Sequential vs parallel produce identical XMI
4. **Memory test**: Monitor heap during large transformations
