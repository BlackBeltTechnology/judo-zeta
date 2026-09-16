# Design: Fix Parallel Transformation EMF Thread-Safety

## Executive Summary

This document provides detailed technical design for both solution options, enabling an informed implementation decision.

## Current State Analysis

### EMF Threading Model

EMF has specific threading characteristics that constrain our design:

| Operation Type | Thread-Safe? | Notes |
|----------------|--------------|-------|
| `EFactory.create()` | Yes | Creates isolated instance |
| `eGet()` for attributes | Yes | Read-only access |
| `eGet()` for references | Yes | Returns reference, not copy |
| `eSet()` for attributes | **No** | Modifies internal state |
| `eSet()` for references | **No** | May trigger inverse update |
| `EList.add()` | **No** | Modifies backing array |
| `EList.addAll()` | **No** | Multiple modifications |
| `EList.iterator()` | **No** | Fails if modified during iteration |
| `Resource.getContents()` | **No** | Returns mutable EList |

### Current Protection Gaps

```
createTarget()
    │
    ├─ EFactory.create() ──────────────────── SAFE (thread-safe)
    │
    ├─ stagingEnabled? ──┬── YES: stagedElements.offer() ── SAFE (deferred)
    │                    │
    │                    └── NO: resource.getContents().add() ── SAFE (sequential)
    │
    └─ return instance ────────────────────── PROBLEM STARTS HERE
         │
         ▼
    Rule execution uses returned instance:
         │
         ├─ instance.setName("X") ─────────── UNSAFE (concurrent eSet)
         │
         ├─ instance.setRef(other) ────────── UNSAFE (bidirectional update)
         │
         └─ instance.getList().add(x) ─────── UNSAFE (EList modification)
```

### Problematic Patterns in Real Transformations

**Pattern 1: Simple Attribute Setting**
```java
Table table = ctx.createTarget(Table.class);
table.setName(source.getName());  // UNSAFE if another thread accesses table
```

**Pattern 2: Reference Setting with Bidirectional**
```java
Column col = ctx.createTarget(Column.class);
col.setTable(table);  // UNSAFE - also modifies table.getColumns()
table.getColumns().add(col);  // UNSAFE - double modification
```

**Pattern 3: Collection Building**
```java
Table table = ctx.createTarget(Table.class);
for (Attribute attr : source.getAttributes()) {
    Column col = ctx.equivalent(attr, Column.class);
    table.getColumns().add(col);  // UNSAFE - concurrent list modification
}
```

**Pattern 4: Cross-Rule References**
```java
// Rule 1
Table table = ctx.createTarget(Table.class);
table.setSchema(ctx.equivalent(source.getNamespace(), Schema.class));

// Rule 2 (parallel, same schema)
Schema schema = ctx.equivalent(ns, Schema.class);  // Same target!
schema.getTables().add(table);  // Race condition with Rule 1
```

---

## Option A: Deferred EMF Writes - Detailed Design

### Core Components

#### 1. EMF Operation Hierarchy

```java
package hu.blackbelt.judo.zeta.transformation.core.deferred;

/**
 * Sealed interface for all deferred EMF operations.
 * Sealed ensures exhaustive handling in replay logic.
 */
public sealed interface EMFOperation 
    permits SetFeatureOp, UnsetFeatureOp, 
            AddToListOp, AddAllToListOp, RemoveFromListOp, 
            ClearListOp, MoveInListOp, SetInListOp {
    
    /**
     * Sequence number for deterministic ordering.
     */
    long sequence();
    
    /**
     * Target object being modified.
     */
    EObject target();
    
    /**
     * Apply this operation to the EMF model.
     * Called during single-threaded commit phase.
     */
    void apply();
    
    /**
     * Human-readable description for debugging.
     */
    String describe();
}
```

#### 2. Concrete Operation Records

```java
/**
 * Set a single-valued feature (attribute or reference).
 */
public record SetFeatureOp(
    EObject target,
    EStructuralFeature feature,
    Object value,
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        target.eSet(feature, value);
    }
    
    @Override
    public String describe() {
        return String.format("SET %s.%s = %s", 
            target.eClass().getName(), feature.getName(), value);
    }
}

/**
 * Add element to a multi-valued feature.
 */
public record AddToListOp(
    EObject target,
    EStructuralFeature feature,
    Object element,
    int index,  // -1 for append
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        if (index < 0) {
            list.add(element);
        } else {
            list.add(index, element);
        }
    }
    
    @Override
    public String describe() {
        return String.format("ADD %s.%s[%d] = %s",
            target.eClass().getName(), feature.getName(), 
            index < 0 ? list.size() : index, element);
    }
}

/**
 * Add multiple elements to a multi-valued feature.
 */
public record AddAllToListOp(
    EObject target,
    EStructuralFeature feature,
    List<?> elements,
    int index,  // -1 for append
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        if (index < 0) {
            list.addAll((Collection<?>) elements);
        } else {
            list.addAll(index, (Collection<?>) elements);
        }
    }
}

/**
 * Remove element from a multi-valued feature.
 */
public record RemoveFromListOp(
    EObject target,
    EStructuralFeature feature,
    Object element,  // Element to remove, or null if by index
    int index,       // Index to remove, or -1 if by element
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        if (element != null) {
            list.remove(element);
        } else {
            list.remove(index);
        }
    }
}

/**
 * Clear a multi-valued feature.
 */
public record ClearListOp(
    EObject target,
    EStructuralFeature feature,
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        list.clear();
    }
}

/**
 * Move element within a multi-valued feature.
 */
public record MoveInListOp(
    EObject target,
    EStructuralFeature feature,
    int oldIndex,
    int newIndex,
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        list.move(newIndex, oldIndex);
    }
}

/**
 * Set element at index in a multi-valued feature.
 */
public record SetInListOp(
    EObject target,
    EStructuralFeature feature,
    int index,
    Object element,
    long sequence
) implements EMFOperation {
    
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        list.set(index, element);
    }
}
```

#### 3. Operation Queue

```java
/**
 * Thread-safe queue for collecting EMF operations.
 */
public class OperationQueue {
    
    private final ConcurrentLinkedQueue<EMFOperation> operations = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    
    /**
     * Record an operation for later replay.
     */
    public void record(EMFOperation operation) {
        operations.offer(operation);
    }
    
    /**
     * Get next sequence number (thread-safe).
     */
    public long nextSequence() {
        return sequenceGenerator.getAndIncrement();
    }
    
    /**
     * Replay all operations in sequence order.
     * Must be called from single thread.
     */
    public void replay() {
        // Drain queue to list
        List<EMFOperation> allOps = new ArrayList<>();
        EMFOperation op;
        while ((op = operations.poll()) != null) {
            allOps.add(op);
        }
        
        // Sort by sequence for deterministic order
        allOps.sort(Comparator.comparingLong(EMFOperation::sequence));
        
        // Apply in order
        for (EMFOperation operation : allOps) {
            try {
                operation.apply();
            } catch (Exception e) {
                throw new TransformationException(
                    "Failed to apply operation: " + operation.describe(), 
                    e, operation.target(), null);
            }
        }
    }
    
    /**
     * Clear all pending operations (for rollback).
     */
    public void clear() {
        operations.clear();
    }
    
    /**
     * Get count of pending operations.
     */
    public int size() {
        return operations.size();
    }
    
    /**
     * Reset sequence counter.
     */
    public void resetSequence() {
        sequenceGenerator.set(0);
    }
}
```

#### 4. Deferred EObject Wrapper

```java
/**
 * Dynamic proxy handler that intercepts EMF modifications.
 */
public class DeferredEObjectHandler implements InvocationHandler {
    
    private final EObject delegate;
    private final OperationQueue queue;
    private final AtomicLong sequencer;
    
    // Cache pending values for read-after-write consistency
    private final ConcurrentHashMap<EStructuralFeature, Object> pendingValues = new ConcurrentHashMap<>();
    
    // Cache wrapped lists
    private final ConcurrentHashMap<EStructuralFeature, DeferredEList<?>> wrappedLists = new ConcurrentHashMap<>();
    
    public DeferredEObjectHandler(EObject delegate, OperationQueue queue, AtomicLong sequencer) {
        this.delegate = delegate;
        this.queue = queue;
        this.sequencer = sequencer;
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        
        // Handle Object methods
        if ("equals".equals(name)) {
            return delegate.equals(unwrap(args[0]));
        }
        if ("hashCode".equals(name)) {
            return delegate.hashCode();
        }
        if ("toString".equals(name)) {
            return "Deferred[" + delegate.toString() + "]";
        }
        
        // Handle EObject methods
        if ("eClass".equals(name)) {
            return delegate.eClass();
        }
        if ("eResource".equals(name)) {
            return delegate.eResource();
        }
        if ("eContainer".equals(name)) {
            return delegate.eContainer();
        }
        
        // Handle generated setters: setXxx(value)
        if (name.startsWith("set") && name.length() > 3 && args != null && args.length == 1) {
            String featureName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            EStructuralFeature feature = delegate.eClass().getEStructuralFeature(featureName);
            if (feature != null) {
                Object value = unwrap(args[0]);
                pendingValues.put(feature, value);
                queue.record(new SetFeatureOp(delegate, feature, value, sequencer.getAndIncrement()));
                return null;
            }
        }
        
        // Handle generated getters: getXxx()
        if (name.startsWith("get") && name.length() > 3 && (args == null || args.length == 0)) {
            String featureName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            EStructuralFeature feature = delegate.eClass().getEStructuralFeature(featureName);
            if (feature != null) {
                // Check pending value first (read-after-write)
                Object pending = pendingValues.get(feature);
                if (pending != null) {
                    return pending;
                }
                
                Object result = method.invoke(delegate, args);
                
                // Wrap EList for deferred modifications
                if (result instanceof EList && feature.isMany()) {
                    return wrappedLists.computeIfAbsent(feature, 
                        f -> new DeferredEList<>((EList<?>) result, delegate, f, queue, sequencer));
                }
                
                return result;
            }
        }
        
        // Handle isXxx() for boolean features
        if (name.startsWith("is") && name.length() > 2 && (args == null || args.length == 0)) {
            String featureName = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            EStructuralFeature feature = delegate.eClass().getEStructuralFeature(featureName);
            if (feature != null) {
                Object pending = pendingValues.get(feature);
                if (pending != null) {
                    return pending;
                }
            }
        }
        
        // Pass through to delegate
        return method.invoke(delegate, args);
    }
    
    /**
     * Unwrap proxied objects to get the real EObject.
     */
    private Object unwrap(Object obj) {
        if (obj instanceof Proxy) {
            InvocationHandler handler = Proxy.getInvocationHandler(obj);
            if (handler instanceof DeferredEObjectHandler) {
                return ((DeferredEObjectHandler) handler).delegate;
            }
        }
        return obj;
    }
    
    /**
     * Get the underlying delegate.
     */
    public EObject getDelegate() {
        return delegate;
    }
}
```

#### 5. Deferred EList Wrapper

```java
/**
 * EList wrapper that records modifications for deferred application.
 */
public class DeferredEList<E> implements EList<E> {
    
    private final EList<E> delegate;
    private final EObject owner;
    private final EStructuralFeature feature;
    private final OperationQueue queue;
    private final AtomicLong sequencer;
    
    // Track pending additions for iteration consistency
    private final CopyOnWriteArrayList<E> pendingAdds = new CopyOnWriteArrayList<>();
    
    public DeferredEList(EList<E> delegate, EObject owner, 
                         EStructuralFeature feature, OperationQueue queue, 
                         AtomicLong sequencer) {
        this.delegate = delegate;
        this.owner = owner;
        this.feature = feature;
        this.queue = queue;
        this.sequencer = sequencer;
    }
    
    @Override
    public boolean add(E element) {
        pendingAdds.add(element);
        queue.record(new AddToListOp(owner, feature, unwrap(element), -1, sequencer.getAndIncrement()));
        return true;
    }
    
    @Override
    public void add(int index, E element) {
        pendingAdds.add(element);  // Simplified - doesn't track index
        queue.record(new AddToListOp(owner, feature, unwrap(element), index, sequencer.getAndIncrement()));
    }
    
    @Override
    public boolean addAll(Collection<? extends E> c) {
        List<Object> unwrapped = c.stream().map(this::unwrap).collect(Collectors.toList());
        pendingAdds.addAll(c);
        queue.record(new AddAllToListOp(owner, feature, unwrapped, -1, sequencer.getAndIncrement()));
        return !c.isEmpty();
    }
    
    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        List<Object> unwrapped = c.stream().map(this::unwrap).collect(Collectors.toList());
        pendingAdds.addAll(c);
        queue.record(new AddAllToListOp(owner, feature, unwrapped, index, sequencer.getAndIncrement()));
        return !c.isEmpty();
    }
    
    @Override
    public boolean remove(Object o) {
        pendingAdds.remove(o);
        queue.record(new RemoveFromListOp(owner, feature, unwrap(o), -1, sequencer.getAndIncrement()));
        return true;  // Assume success - verified at replay
    }
    
    @Override
    public E remove(int index) {
        E removed = get(index);
        pendingAdds.remove(removed);
        queue.record(new RemoveFromListOp(owner, feature, null, index, sequencer.getAndIncrement()));
        return removed;
    }
    
    @Override
    public void clear() {
        pendingAdds.clear();
        queue.record(new ClearListOp(owner, feature, sequencer.getAndIncrement()));
    }
    
    @Override
    public E set(int index, E element) {
        E old = get(index);
        queue.record(new SetInListOp(owner, feature, index, unwrap(element), sequencer.getAndIncrement()));
        return old;
    }
    
    @Override
    public void move(int newPosition, E object) {
        int oldPosition = indexOf(object);
        queue.record(new MoveInListOp(owner, feature, oldPosition, newPosition, sequencer.getAndIncrement()));
    }
    
    @Override
    public E move(int newPosition, int oldPosition) {
        E element = get(oldPosition);
        queue.record(new MoveInListOp(owner, feature, oldPosition, newPosition, sequencer.getAndIncrement()));
        return element;
    }
    
    // Read operations - combine delegate with pending adds
    
    @Override
    public int size() {
        return delegate.size() + pendingAdds.size();
    }
    
    @Override
    public boolean isEmpty() {
        return delegate.isEmpty() && pendingAdds.isEmpty();
    }
    
    @Override
    public boolean contains(Object o) {
        return delegate.contains(unwrap(o)) || pendingAdds.contains(o);
    }
    
    @Override
    public E get(int index) {
        if (index < delegate.size()) {
            return delegate.get(index);
        }
        return pendingAdds.get(index - delegate.size());
    }
    
    @Override
    public Iterator<E> iterator() {
        // Return iterator over combined view
        List<E> combined = new ArrayList<>(delegate);
        combined.addAll(pendingAdds);
        return combined.iterator();
    }
    
    @Override
    public ListIterator<E> listIterator() {
        List<E> combined = new ArrayList<>(delegate);
        combined.addAll(pendingAdds);
        return combined.listIterator();
    }
    
    @Override
    public ListIterator<E> listIterator(int index) {
        List<E> combined = new ArrayList<>(delegate);
        combined.addAll(pendingAdds);
        return combined.listIterator(index);
    }
    
    @SuppressWarnings("unchecked")
    private E unwrap(Object obj) {
        if (obj instanceof Proxy) {
            InvocationHandler handler = Proxy.getInvocationHandler(obj);
            if (handler instanceof DeferredEObjectHandler) {
                return (E) ((DeferredEObjectHandler) handler).getDelegate();
            }
        }
        return (E) obj;
    }
    
    // Remaining EList methods...
    // (indexOf, lastIndexOf, subList, toArray, containsAll, removeAll, retainAll)
}
```

#### 6. Proxy Factory

```java
/**
 * Factory for creating deferred EObject proxies.
 */
public class DeferredProxyFactory {
    
    private final OperationQueue queue;
    private final AtomicLong sequencer;
    private final ConcurrentHashMap<EObject, Object> proxyCache = new ConcurrentHashMap<>();
    
    public DeferredProxyFactory(OperationQueue queue) {
        this.queue = queue;
        this.sequencer = new AtomicLong(0);
    }
    
    /**
     * Wrap an EObject in a deferred proxy.
     * Returns cached proxy if already wrapped.
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T wrap(T eObject) {
        return (T) proxyCache.computeIfAbsent(eObject, obj -> {
            Class<?>[] interfaces = collectInterfaces(obj.getClass());
            return Proxy.newProxyInstance(
                obj.getClass().getClassLoader(),
                interfaces,
                new DeferredEObjectHandler(obj, queue, sequencer)
            );
        });
    }
    
    /**
     * Unwrap a proxy to get the real EObject.
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T unwrap(T proxy) {
        if (proxy instanceof Proxy) {
            InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (handler instanceof DeferredEObjectHandler) {
                return (T) ((DeferredEObjectHandler) handler).getDelegate();
            }
        }
        return proxy;
    }
    
    /**
     * Clear proxy cache.
     */
    public void clear() {
        proxyCache.clear();
        sequencer.set(0);
    }
    
    private Class<?>[] collectInterfaces(Class<?> clazz) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfacesRecursive(clazz, interfaces);
        return interfaces.toArray(new Class<?>[0]);
    }
    
    private void collectInterfacesRecursive(Class<?> clazz, Set<Class<?>> interfaces) {
        if (clazz == null) return;
        for (Class<?> iface : clazz.getInterfaces()) {
            interfaces.add(iface);
            collectInterfacesRecursive(iface, interfaces);
        }
        collectInterfacesRecursive(clazz.getSuperclass(), interfaces);
    }
}
```

#### 7. Integration with TransformationContext

```java
public class TransformationContext {
    
    // Existing fields...
    
    // NEW: Deferred write infrastructure
    private final OperationQueue operationQueue = new OperationQueue();
    private final DeferredProxyFactory proxyFactory = new DeferredProxyFactory(operationQueue);
    private final AtomicBoolean deferredWriteEnabled = new AtomicBoolean(false);
    
    /**
     * Enable deferred write mode for parallel transformation.
     */
    void enableDeferredWrite() {
        deferredWriteEnabled.set(true);
    }
    
    /**
     * Disable deferred write mode.
     */
    void disableDeferredWrite() {
        deferredWriteEnabled.set(false);
    }
    
    /**
     * Apply all deferred operations.
     * Must be called from single thread after parallel phase.
     */
    void applyDeferredOperations() {
        operationQueue.replay();
    }
    
    /**
     * Clear deferred operations without applying.
     */
    void clearDeferredOperations() {
        operationQueue.clear();
        proxyFactory.clear();
    }
    
    /**
     * Create a new target element of the specified type.
     * If deferred write is enabled, returns a proxy.
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T createTarget(Class<T> targetType) {
        // ... existing creation logic ...
        
        EObject instance = targetPackage.getEFactoryInstance().create(eClass);
        
        if (stagingEnabled.get()) {
            // Stage for Resource addition
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(instance, sequence);
            stagedElements.offer(new StagedElement(instance, true, sequence));
        } else {
            targetResource.getContents().add(instance);
        }
        
        // Wrap in proxy if deferred write enabled
        if (deferredWriteEnabled.get()) {
            return proxyFactory.wrap((T) instance);
        }
        
        return (T) instance;
    }
}
```

#### 8. Integration with TransformationExecutor

```java
public class TransformationExecutor {
    
    private void transformWithStaging(Collection<? extends EObject> sourceElements) {
        try {
            // Enable staging for Resource additions
            context.enableStaging();
            
            // Enable deferred write for attribute/reference modifications
            context.enableDeferredWrite();
            
            // Phase 1: Parallel transformation
            transformParallel(sourceElements);
            
            // Check for errors before commit
            if (firstError.get() != null) {
                return;
            }
            
            // Phase 2: Apply deferred operations (single-threaded)
            context.applyDeferredOperations();
            
            // Phase 3: Commit staged elements to Resource
            context.commitStagedElements();
            
        } finally {
            context.disableDeferredWrite();
            context.disableStaging();
            context.clearDeferredOperations();
            context.clearStagedElements();
        }
    }
}
```

---

## Option B: Synchronized EMF Access - Detailed Design

### Core Components

#### 1. Synchronized Access Helper

```java
/**
 * Thread-safe wrapper for EMF operations.
 * Uses fine-grained locking on target objects.
 */
public class SynchronizedEMFAccess {
    
    /**
     * Set a feature value with synchronization.
     */
    public static void set(EObject target, EStructuralFeature feature, Object value) {
        synchronized (target) {
            target.eSet(feature, value);
        }
    }
    
    /**
     * Set a feature value by name.
     */
    public static void set(EObject target, String featureName, Object value) {
        EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            throw new IllegalArgumentException("Unknown feature: " + featureName + " on " + target.eClass().getName());
        }
        set(target, feature, value);
    }
    
    /**
     * Add to a multi-valued feature with synchronization.
     */
    public static <T> void add(EObject container, EStructuralFeature feature, T element) {
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.add(element);
        }
    }
    
    /**
     * Add all to a multi-valued feature.
     */
    public static <T> void addAll(EObject container, EStructuralFeature feature, Collection<T> elements) {
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.addAll(elements);
        }
    }
    
    /**
     * Remove from a multi-valued feature.
     */
    public static <T> boolean remove(EObject container, EStructuralFeature feature, T element) {
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            return list.remove(element);
        }
    }
    
    /**
     * Set a bidirectional reference with deadlock prevention.
     * Always acquires locks in consistent order based on identity hash.
     */
    public static void setBidirectional(EObject a, EStructuralFeature aFeature, 
                                        EObject b, EStructuralFeature bFeature) {
        EObject first, second;
        if (System.identityHashCode(a) < System.identityHashCode(b)) {
            first = a;
            second = b;
        } else {
            first = b;
            second = a;
        }
        
        synchronized (first) {
            synchronized (second) {
                a.eSet(aFeature, b);
                // EMF automatically handles inverse
            }
        }
    }
}
```

#### 2. TransformationContext Extensions

```java
public class TransformationContext {
    
    // ==================== Synchronized Access Methods ====================
    
    /**
     * Thread-safe: Set a single-valued feature.
     */
    public void set(EObject target, String featureName, Object value) {
        EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            throw new IllegalArgumentException("Unknown feature: " + featureName);
        }
        synchronized (target) {
            target.eSet(feature, value);
        }
    }
    
    /**
     * Thread-safe: Add to a multi-valued feature.
     */
    public <T> void add(EObject container, String featureName, T element) {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany()) {
            throw new IllegalArgumentException("Not a multi-valued feature: " + featureName);
        }
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.add(element);
        }
    }
    
    /**
     * Thread-safe: Add all to a multi-valued feature.
     */
    public <T> void addAll(EObject container, String featureName, Collection<T> elements) {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany()) {
            throw new IllegalArgumentException("Not a multi-valued feature: " + featureName);
        }
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.addAll(elements);
        }
    }
    
    /**
     * Thread-safe: Remove from a multi-valued feature.
     */
    public <T> boolean remove(EObject container, String featureName, T element) {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany()) {
            throw new IllegalArgumentException("Not a multi-valued feature: " + featureName);
        }
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            return list.remove(element);
        }
    }
    
    /**
     * Thread-safe: Batch modify with exclusive lock.
     * Use for complex modifications to a single object.
     */
    public <T extends EObject> void modify(T target, Consumer<T> modifier) {
        synchronized (target) {
            modifier.accept(target);
        }
    }
    
    /**
     * Thread-safe: Batch modify multiple objects.
     * Acquires locks in consistent order to prevent deadlock.
     */
    public void modifyAll(Collection<? extends EObject> targets, Consumer<EObject> modifier) {
        // Sort by identity hash for consistent lock order
        List<EObject> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparingInt(System::identityHashCode));
        
        // Acquire all locks
        modifyAllRecursive(sorted, 0, modifier);
    }
    
    private void modifyAllRecursive(List<EObject> targets, int index, Consumer<EObject> modifier) {
        if (index >= targets.size()) {
            // All locks acquired, apply modifications
            for (EObject target : targets) {
                modifier.accept(target);
            }
            return;
        }
        
        synchronized (targets.get(index)) {
            modifyAllRecursive(targets, index + 1, modifier);
        }
    }
}
```

#### 3. Usage Examples

```java
// Before (UNSAFE):
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    table.setName(source.getName());
    table.getColumns().add(column);
    return table;
};

// After (SAFE with synchronized methods):
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    ctx.set(table, "name", source.getName());
    ctx.add(table, "columns", column);
    return table;
};

// After (SAFE with modify block):
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    ctx.modify(table, t -> {
        t.setName(source.getName());
        t.getColumns().add(column);
    });
    return table;
};
```

---

## Testing Strategy

### Common Test Cases for Both Options

```java
/**
 * Base test class for thread-safety verification.
 */
public abstract class ConcurrentEMFTestBase {
    
    protected ExecutorService executor;
    protected TransformationContext context;
    
    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(50);
        context = createContext();
    }
    
    @AfterEach
    void tearDown() {
        executor.shutdown();
    }
    
    /**
     * Test concurrent attribute setting on different objects.
     */
    @RepeatedTest(100)
    void testConcurrentAttributeSetOnDifferentObjects() throws Exception {
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        List<EClass> targets = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            targets.add(createTarget(EClass.class));
        }
        
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    setName(targets.get(idx), "Name_" + idx);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(threadCount, successCount.get());
        
        // Verify all names set correctly
        for (int i = 0; i < threadCount; i++) {
            assertEquals("Name_" + i, targets.get(i).getName());
        }
    }
    
    /**
     * Test concurrent list additions to same object.
     */
    @RepeatedTest(100)
    void testConcurrentListAdditionToSameObject() throws Exception {
        int threadCount = 50;
        int addsPerThread = 10;
        EClass target = createTarget(EClass.class);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < addsPerThread; i++) {
                        EAttribute attr = createAttribute("attr_" + threadId + "_" + i);
                        addToFeatures(target, attr);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        // Apply deferred ops if Option A
        applyDeferredOperations();
        
        // Verify count
        assertEquals(threadCount * addsPerThread, target.getEStructuralFeatures().size());
    }
    
    // Abstract methods for option-specific implementation
    protected abstract <T extends EObject> T createTarget(Class<T> type);
    protected abstract void setName(EClass target, String name);
    protected abstract void addToFeatures(EClass target, EAttribute attr);
    protected abstract void applyDeferredOperations();
}
```

### Option A Specific Tests

```java
public class DeferredWriteTest extends ConcurrentEMFTestBase {
    
    @Test
    void testOperationOrdering() {
        context.enableDeferredWrite();
        
        EClass target = context.createTarget(EClass.class);
        
        // Record operations
        target.setName("First");
        target.setName("Second");
        target.setName("Final");
        
        // Verify pending value
        assertEquals("Final", target.getName());
        
        // Apply operations
        context.applyDeferredOperations();
        
        // Verify applied
        EClass unwrapped = context.unwrap(target);
        assertEquals("Final", unwrapped.getName());
    }
    
    @Test
    void testReadAfterWriteConsistency() {
        context.enableDeferredWrite();
        
        EClass target = context.createTarget(EClass.class);
        target.setName("TestName");
        
        // Read should see pending value
        assertEquals("TestName", target.getName());
        
        // isAbstract should work
        target.setAbstract(true);
        assertTrue(target.isAbstract());
    }
    
    @Test
    void testListOperations() {
        context.enableDeferredWrite();
        
        EClass target = context.createTarget(EClass.class);
        EAttribute attr1 = context.create(EAttribute.class);
        EAttribute attr2 = context.create(EAttribute.class);
        
        target.getEStructuralFeatures().add(attr1);
        target.getEStructuralFeatures().add(attr2);
        
        // Should see pending adds
        assertEquals(2, target.getEStructuralFeatures().size());
        assertTrue(target.getEStructuralFeatures().contains(attr1));
        
        // Apply
        context.applyDeferredOperations();
        
        EClass unwrapped = context.unwrap(target);
        assertEquals(2, unwrapped.getEStructuralFeatures().size());
    }
}
```

### Option B Specific Tests

```java
public class SynchronizedAccessTest extends ConcurrentEMFTestBase {
    
    @Test
    void testDeadlockPrevention() throws Exception {
        // Create objects that will be locked in different orders
        EClass a = context.createTarget(EClass.class);
        EClass b = context.createTarget(EClass.class);
        
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);
        
        // Thread 1: modifies a then b
        Future<?> f1 = executor.submit(() -> {
            try {
                startLatch.await();
                context.modifyAll(Arrays.asList(a, b), obj -> {
                    ((EClass) obj).setAbstract(true);
                    Thread.sleep(10);  // Hold lock longer
                });
            } catch (Exception e) {
                deadlockDetected.set(true);
            }
        });
        
        // Thread 2: modifies b then a (would deadlock without ordering)
        Future<?> f2 = executor.submit(() -> {
            try {
                startLatch.await();
                context.modifyAll(Arrays.asList(b, a), obj -> {
                    ((EClass) obj).setInterface(true);
                    Thread.sleep(10);
                });
            } catch (Exception e) {
                deadlockDetected.set(true);
            }
        });
        
        startLatch.countDown();
        
        // Should complete without deadlock
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        
        assertFalse(deadlockDetected.get());
        assertTrue(a.isAbstract());
        assertTrue(a.isInterface());
    }
}
```

---

## Migration Guide

### For Option A (Transparent - No Code Changes Required)

Transformation rules continue to use direct EMF calls:

```java
// Works automatically with deferred proxies
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);  // Returns proxy
    table.setName(source.getName());              // Recorded, not applied
    table.getColumns().add(column);               // Recorded, not applied
    return table;
};
```

### For Option B (Requires Code Changes)

```java
// Before (UNSAFE):
table.setName(source.getName());
table.getColumns().add(column);

// After (SAFE):
ctx.set(table, "name", source.getName());
ctx.add(table, "columns", column);

// Or with modify block:
ctx.modify(table, t -> {
    t.setName(source.getName());
    t.getColumns().add(column);
});
```

---

## Decision Matrix

| Criterion | Weight | Option A | Option B |
|-----------|--------|----------|----------|
| Correctness guarantee | 30% | 10/10 | 8/10 |
| Implementation complexity | 20% | 4/10 | 9/10 |
| Performance overhead | 15% | 6/10 | 7/10 |
| Migration effort | 15% | 10/10 | 5/10 |
| Debugging capability | 10% | 9/10 | 6/10 |
| Maintenance burden | 10% | 5/10 | 9/10 |
| **Weighted Total** | 100% | **7.35** | **7.25** |

Both options score similarly overall, but with different trade-offs:
- **Option A**: Better for correctness and migration, harder to implement
- **Option B**: Simpler to implement and maintain, requires rule changes

## Recommendation

**Implement Option A (Deferred Writes)** as the primary mechanism because:

1. **No migration required** - Existing rules work without changes
2. **Complete isolation** - Impossible to have race conditions during parallel phase
3. **Deterministic** - Sequence numbers ensure reproducible results
4. **Debuggable** - Operation log enables powerful diagnostics

The higher implementation complexity is justified by the superior correctness guarantees and zero migration cost for existing transformations.
