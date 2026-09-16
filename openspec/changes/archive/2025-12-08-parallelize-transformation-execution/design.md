# Design: Thread-Safe Parallel Transformation Execution

## Overview

This document details the architectural design for parallelizing transformation execution while maintaining thread-safe EMF model access.

## Current Architecture Analysis

### Existing Components

```
TransformationExecutor
├── registry: TransformationRegistry
├── context: TransformationContext
├── parallel: boolean
└── executor: ExecutorService (lazy-initialized)

TransformationContext
├── sourceResourceSet: ResourceSet (read-only during transform)
├── targetResourceSet: ResourceSet (WRITE CONTENTION POINT)
├── resolutionCache: ElementResolutionCache (thread-safe)
├── extensionRegistry: ExtensionMethodRegistry (thread-safe)
├── currentSource: ThreadLocal<EObject>
└── attributes: ConcurrentHashMap
```

### Thread-Safety Issues

1. **TransformationContext.createTarget()**: Adds to `targetResource.getContents()` from parallel threads
2. **TransformationContext.equivalentDiscriminated()**: Same issue for cloned elements
3. **Rule Execution**: User-defined rules may modify shared structures

## Proposed Architecture

### Component Changes

```
TransformationContext (MODIFIED)
├── sourceResourceSet: ResourceSet
├── targetResourceSet: ResourceSet
├── resolutionCache: ElementResolutionCache
├── extensionRegistry: ExtensionMethodRegistry
├── currentSource: ThreadLocal<EObject>
├── attributes: ConcurrentHashMap
├── stagedElements: ConcurrentLinkedQueue<EObject>  // NEW
├── stagingEnabled: volatile boolean               // NEW
└── stagingLock: ReentrantLock                     // NEW (for commit)

TransformationExecutor (MODIFIED)
├── transform(): Updated flow for staging
└── transformParallel(): Enable staging before, commit after
```

### Execution Flow Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           transform(sourceElements)                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐     │
│  │  Pre-Transformation Hooks                                        │     │
│  │  registry.invokePreTransformationHooks(context)                  │     │
│  └─────────────────────────────────────────────────────────────────┘     │
│                                   │                                       │
│                    ┌──────────────┴──────────────┐                       │
│                    │                             │                       │
│         useParallel = true           useParallel = false                 │
│                    │                             │                       │
│                    ▼                             ▼                       │
│  ┌─────────────────────────────┐   ┌───────────────────────────┐        │
│  │  context.enableStaging()    │   │  transformSequential()    │        │
│  └─────────────────────────────┘   │  (no staging, direct add) │        │
│                    │               └───────────────────────────┘        │
│                    ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │              PARALLEL PHASE (ForkJoinPool)                       │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │  For each chunk of source elements:                      │    │    │
│  │  │    For each element in chunk:                            │    │    │
│  │  │      context.setCurrentSource(element)                   │    │    │
│  │  │      executeEagerRulesFor(element)                       │    │    │
│  │  │        └─ rule.execute() calls createTarget()            │    │    │
│  │  │             └─ stagedElements.offer(newElement)          │    │    │
│  │  │        └─ resolutionCache.addMapping(...)                │    │    │
│  │  │      context.clearCurrentSource()                        │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                    │                                                     │
│                    ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │              COMMIT PHASE (Single-Threaded)                      │    │
│  │  context.commitStagedElements()                                  │    │
│  │    └─ while (element = stagedElements.poll()) != null:          │    │
│  │         targetResource.getContents().add(element)               │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                    │                                                     │
│                    ▼                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  context.disableStaging()                                        │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                   │                                       │
│  ┌─────────────────────────────────────────────────────────────────┐     │
│  │  Post-Transformation Hooks                                       │     │
│  │  registry.invokePostTransformationHooks(context)                 │     │
│  └─────────────────────────────────────────────────────────────────┘     │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

## Detailed Component Design

### TransformationContext Modifications

```java
package hu.blackbelt.judo.zeta.transformation.core;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransformationContext {
    
    // Existing fields...
    
    /**
     * Queue for collecting elements created during parallel transformation.
     * Elements are staged here instead of being added directly to the target Resource.
     */
    private final ConcurrentLinkedQueue<StagedElement> stagedElements = new ConcurrentLinkedQueue<>();
    
    /**
     * Flag indicating whether element staging is enabled.
     * When true, createTarget() stages elements instead of adding to Resource.
     */
    private final AtomicBoolean stagingEnabled = new AtomicBoolean(false);
    
    /**
     * Wrapper for staged elements with optional ordering metadata.
     */
    private static class StagedElement {
        final EObject element;
        final boolean isRootElement;  // Add to Resource.contents vs. contained
        
        StagedElement(EObject element, boolean isRootElement) {
            this.element = element;
            this.isRootElement = isRootElement;
        }
    }
    
    /**
     * Enable staging mode for parallel transformation.
     * Called by TransformationExecutor before parallel phase.
     */
    void enableStaging() {
        stagingEnabled.set(true);
    }
    
    /**
     * Disable staging mode and return to direct Resource modification.
     */
    void disableStaging() {
        stagingEnabled.set(false);
    }
    
    /**
     * Check if staging is currently enabled.
     */
    boolean isStagingEnabled() {
        return stagingEnabled.get();
    }
    
    /**
     * Commit all staged elements to the target Resource.
     * Must be called from a single thread after parallel phase completes.
     */
    void commitStagedElements() {
        if (targetResourceSet.getResources().isEmpty()) {
            return;
        }
        
        Resource targetResource = targetResourceSet.getResources().get(0);
        EList<EObject> contents = targetResource.getContents();
        
        StagedElement staged;
        while ((staged = stagedElements.poll()) != null) {
            if (staged.isRootElement && staged.element.eContainer() == null) {
                contents.add(staged.element);
            }
        }
    }
    
    /**
     * Create a new target element of the specified type.
     * If staging is enabled, element is queued for later addition to Resource.
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T createTarget(Class<T> targetType) {
        if (targetPackage == null) {
            throw new IllegalStateException("Target package not set");
        }
        
        String typeName = targetType.getSimpleName();
        EClass eClass = (EClass) targetPackage.getEClassifier(typeName);
        
        if (eClass == null) {
            throw new IllegalArgumentException("EClass not found: " + typeName);
        }
        
        EObject instance = targetPackage.getEFactoryInstance().create(eClass);
        
        if (stagingEnabled.get()) {
            // Parallel mode: stage for later commit
            stagedElements.offer(new StagedElement(instance, true));
        } else {
            // Sequential mode: add directly to Resource
            if (!targetResourceSet.getResources().isEmpty()) {
                Resource targetResource = targetResourceSet.getResources().get(0);
                targetResource.getContents().add(instance);
            }
        }
        
        return (T) instance;
    }
    
    /**
     * Clear staging queue (called on cleanup or reset).
     */
    void clearStagedElements() {
        stagedElements.clear();
    }
}
```

### TransformationException Class

```java
package hu.blackbelt.judo.zeta.transformation.core;

/**
 * Runtime exception thrown when a transformation fails.
 * Wraps the original cause for debugging.
 */
public class TransformationException extends RuntimeException {
    
    private final EObject failedElement;
    private final String ruleName;
    
    public TransformationException(String message) {
        super(message);
        this.failedElement = null;
        this.ruleName = null;
    }
    
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
        this.failedElement = null;
        this.ruleName = null;
    }
    
    public TransformationException(String message, Throwable cause, EObject failedElement, String ruleName) {
        super(message, cause);
        this.failedElement = failedElement;
        this.ruleName = ruleName;
    }
    
    /**
     * Get the element that caused the failure, if available.
     */
    public EObject getFailedElement() {
        return failedElement;
    }
    
    /**
     * Get the rule name that failed, if available.
     */
    public String getRuleName() {
        return ruleName;
    }
}
```

### TransformationExecutor Modifications

```java
package hu.blackbelt.judo.zeta.transformation.core;

public class TransformationExecutor {
    
    // Default threshold, configurable via builder
    private static final int DEFAULT_PARALLEL_THRESHOLD = 1000;
    private static final int DEFAULT_CHUNK_SIZE = 100;
    
    private final TransformationRegistry registry;
    private final TransformationContext context;
    private final boolean parallel;
    private final int parallelThreshold;
    private final int chunkSize;
    
    // Shared exception holder for fail-fast (reset for each transform call)
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();
    
    private TransformationExecutor(Builder builder) {
        this.registry = builder.registry;
        this.context = builder.context;
        this.parallel = builder.parallel;
        this.parallelThreshold = builder.parallelThreshold;
        this.chunkSize = builder.chunkSize;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private TransformationRegistry registry;
        private TransformationContext context;
        private boolean parallel = true;
        private int parallelThreshold = DEFAULT_PARALLEL_THRESHOLD;
        private int chunkSize = DEFAULT_CHUNK_SIZE;
        
        public Builder registry(TransformationRegistry registry) {
            this.registry = registry;
            return this;
        }
        
        public Builder context(TransformationContext context) {
            this.context = context;
            return this;
        }
        
        public Builder parallel(boolean parallel) {
            this.parallel = parallel;
            return this;
        }
        
        public Builder parallelThreshold(int threshold) {
            this.parallelThreshold = threshold;
            return this;
        }
        
        public Builder chunkSize(int size) {
            this.chunkSize = size;
            return this;
        }
        
        public TransformationExecutor build() {
            Objects.requireNonNull(registry, "registry is required");
            Objects.requireNonNull(context, "context is required");
            return new TransformationExecutor(this);
        }
    }
    
    /**
     * Reset executor state for reuse.
     * Called at the start of each transform() invocation.
     */
    private void reset() {
        firstError.set(null);
        context.clearStagedElements();
        context.clearElementOrder();
        context.clearPendingXmiIds();
    }
    
    /**
     * Transform all source elements.
     * Executor is reusable - state is reset at the start of each call.
     */
    public TransformationResult transform(Collection<? extends EObject> sourceElements) {
        // Reset state for reuse
        reset();
        
        long startTime = System.currentTimeMillis();
        
        registry.invokePreTransformationHooks(context);
        
        try {
            boolean useParallel = parallel && sourceElements.size() >= parallelThreshold;
            
            if (useParallel) {
                transformWithStaging(sourceElements);
            } else {
                transformSequential(sourceElements);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformation completed in {}ms, processed {} elements{}",
                    duration, sourceElements.size(), useParallel ? " (parallel)" : "");
            
            return new TransformationResult(context, duration);
            
        } finally {
            registry.invokePostTransformationHooks(context);
            context.clearExtensionCache();
        }
    }
    
    /**
     * Transform with staging enabled for thread-safe parallel execution.
     */
    private void transformWithStaging(Collection<? extends EObject> sourceElements) {
        try {
            // Phase 1: Enable staging and transform in parallel
            context.enableStaging();
            transformParallel(sourceElements);
            
            // Phase 2: Commit staged elements to Resource (single-threaded)
            context.commitStagedElements();
            
        } finally {
            context.disableStaging();
            context.clearStagedElements();
        }
    }
    
    // transformSequential() unchanged
    // transformParallel() unchanged
    // transformChunk() unchanged - now safe due to staging
}
```

### ElementResolutionCache Enhancements

The existing cache is already thread-safe, but we add documentation:

```java
/**
 * Thread-safe cache for transformation trace (source → target mappings).
 * 
 * <p>All public methods are safe for concurrent access from multiple threads.
 * Uses ConcurrentHashMap and synchronized lists internally.</p>
 * 
 * <p>Thread-safety guarantees:</p>
 * <ul>
 *   <li>addMapping() - safe for concurrent calls with different sources</li>
 *   <li>getByRule() - safe for concurrent reads</li>
 *   <li>getEquivalent() - safe for concurrent reads</li>
 *   <li>getEquivalents() - returns defensive copy</li>
 * </ul>
 */
public class ElementResolutionCache {
    // Implementation unchanged - already thread-safe
}
```

## Thread-Safety Analysis

### Safe Operations During Parallel Phase

| Operation | Thread-Safe? | Reason |
|-----------|--------------|--------|
| `EFactory.create(eClass)` | Yes | Creates new isolated instance |
| `stagedElements.offer(element)` | Yes | ConcurrentLinkedQueue |
| `resolutionCache.addMapping()` | Yes | ConcurrentHashMap |
| `element.setXxx(primitive)` | Yes | Thread-local element |
| `element.setXxx(reference)` | Yes* | If reference already exists |
| `element.getXxx().add(child)` | Yes* | If adding to own element |

*Safe because each thread only modifies elements it created.

### Potentially Unsafe Operations

| Operation | Risk | Mitigation |
|-----------|------|------------|
| `element.getXxx().add(sharedElement)` | Low | Shared element is read-only |
| Bidirectional reference setting | Medium | EMF handles automatically |
| Modifying source elements | High | Document as prohibited |

### Thread-Safety Contract for Transformation Rules

Rules must follow these guidelines:

1. **DO**: Create new target elements via `ctx.createTarget()`
2. **DO**: Set properties on elements you created
3. **DO**: Reference elements obtained via `ctx.equivalent()`
4. **DON'T**: Modify source elements
5. **DON'T**: Modify target elements created by other rules (use dependencies)
6. **DON'T**: Use non-thread-safe shared state

## Performance Considerations

### Memory Usage

- `ConcurrentLinkedQueue` overhead: ~48 bytes per node
- For 100,000 elements: ~4.8 MB additional memory
- Elements are released immediately on commit

### Commit Phase Performance

```
Sequential add to EList: O(n)
With growth: O(n) amortized (EList uses ArrayList-like growth)
```

For 100,000 elements, commit takes ~100-500ms depending on target model complexity.

### Optimization: Bulk Add

If ordering is not important, consider:

```java
void commitStagedElements() {
    List<EObject> elements = new ArrayList<>();
    StagedElement staged;
    while ((staged = stagedElements.poll()) != null) {
        if (staged.isRootElement && staged.element.eContainer() == null) {
            elements.add(staged.element);
        }
    }
    // Single addAll operation
    targetResource.getContents().addAll(elements);
}
```

This may be faster as `addAll()` can optimize internal array operations.

## Error Handling

### Exception in Transformation Rule

```java
private void executeEagerRulesFor(EObject source) {
    for (TransformRuleDescriptor rule : rules) {
        try {
            EObject target = rule.execute(source, context);
            if (target != null) {
                context.getElementResolutionCache().addMapping(
                    source, rule.getName(), target, rule.isPrimary());
            }
        } catch (Exception e) {
            log.error("Error executing rule '{}' on {}: {}",
                rule.getName(), source, e.getMessage(), e);
            // Continue with other rules - staged elements from this rule
            // are already in queue but won't break other transformations
        }
    }
}
```

### Cleanup on Failure

```java
public TransformationResult transform(...) {
    try {
        if (useParallel) {
            transformWithStaging(sourceElements);
        } else {
            transformSequential(sourceElements);
        }
        return new TransformationResult(context, duration);
    } catch (Exception e) {
        // Clean up staged elements on failure
        context.clearStagedElements();
        throw e;
    } finally {
        context.disableStaging();
        registry.invokePostTransformationHooks(context);
    }
}
```

## Testing Strategy

### Unit Tests

1. **StagingEnabledTest**: Verify staging flag behavior
2. **ConcurrentStagingTest**: Multiple threads staging elements
3. **CommitOrderTest**: Verify all staged elements are committed
4. **StagingDisabledTest**: Verify sequential mode unchanged

### Concurrency Stress Tests (Critical)

These tests are essential to catch race conditions and edge cases that are hard to reproduce in production. They must run reliably across multiple iterations.

#### Test Design Principles

1. **Force Collisions**: Use `CountDownLatch` or `CyclicBarrier` to ensure threads start simultaneously
2. **High Thread Count**: Test with 50-100 concurrent threads to maximize contention
3. **Multiple Iterations**: Run each test 100+ times to catch intermittent failures
4. **Timeout Protection**: Use timeouts to detect deadlocks
5. **Deterministic Verification**: Count elements, verify IDs, check for duplicates

#### Concurrent Element Creation Test

```java
@Test
@RepeatedTest(100)
void testConcurrentElementCreation() throws Exception {
    int threadCount = 100;
    int elementsPerThread = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    
    context.enableStaging();
    
    for (int t = 0; t < threadCount; t++) {
        executor.submit(() -> {
            try {
                startLatch.await(); // All threads wait here
                for (int i = 0; i < elementsPerThread; i++) {
                    context.createTarget(TestElement.class);
                }
            } finally {
                doneLatch.countDown();
            }
        });
    }
    
    startLatch.countDown(); // Release all threads simultaneously
    assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timeout - possible deadlock");
    
    // Verify exact count
    assertEquals(threadCount * elementsPerThread, context.getStagedElementCount());
}
```

#### Concurrent XMI ID Stability Test

```java
@Test
@RepeatedTest(100)
void testConcurrentIdStability() throws Exception {
    int threadCount = 50;
    EObject sharedElement = context.createTarget(TestElement.class);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);
    ConcurrentHashMap<Integer, String> observedIds = new ConcurrentHashMap<>();
    
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        futures.add(executor.submit(() -> {
            barrier.await(); // Synchronize all threads
            String id = context.getElementId(sharedElement);
            observedIds.put(threadId, id);
            return null;
        }));
    }
    
    for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
    }
    
    // All threads must see the same ID
    Set<String> uniqueIds = new HashSet<>(observedIds.values());
    assertEquals(1, uniqueIds.size(), "ID was not stable across threads: " + uniqueIds);
}
```

#### Concurrent List Modification Test

```java
@Test
@RepeatedTest(100)
void testConcurrentListModification() throws Exception {
    int threadCount = 32;
    int modificationsPerThread = 50;
    EObject parent = context.createTarget(ParentElement.class);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threadCount; t++) {
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                for (int i = 0; i < modificationsPerThread; i++) {
                    EObject child = context.createTarget(ChildElement.class);
                    // This would fail without proper staging
                    parent.getChildren().add(child);
                    successCount.incrementAndGet();
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
            return null;
        }));
    }
    
    for (Future<?> f : futures) {
        f.get(30, TimeUnit.SECONDS);
    }
    
    assertNull(failure.get(), "Exception during concurrent modification: " + failure.get());
    assertEquals(threadCount * modificationsPerThread, successCount.get());
}
```

#### Mixed Read/Write Deadlock Detection Test

```java
@Test
void testNoDeadlockUnderMixedLoad() throws Exception {
    int duration = 5; // seconds
    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger operations = new AtomicInteger(0);
    
    // Writer threads
    for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
            while (running.get()) {
                context.createTarget(TestElement.class);
                operations.incrementAndGet();
            }
        });
    }
    
    // Reader threads
    for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
            while (running.get()) {
                context.getElementResolutionCache().getAllMappings();
                operations.incrementAndGet();
            }
        });
    }
    
    // equivalent() threads (read+write)
    for (int i = 0; i < 10; i++) {
        final EObject source = createSourceElement();
        executor.submit(() -> {
            while (running.get()) {
                context.equivalent(source, TestElement.class);
                operations.incrementAndGet();
            }
        });
    }
    
    // Let it run
    Thread.sleep(duration * 1000);
    running.set(false);
    
    // If we get here without timeout, no deadlock occurred
    assertTrue(operations.get() > 0, "No operations completed - possible deadlock");
}
```

### Integration Tests

1. **ParallelTransformationTest**: End-to-end parallel transformation
2. **LargeModelTest**: 10,000+ elements, verify no exceptions
3. **ConcurrencyStressTest**: High contention scenarios
4. **ReferenceResolutionTest**: Cross-references in parallel

### Performance Tests

1. **SequentialBaselineTest**: Measure sequential performance
2. **ParallelSpeedupTest**: Measure parallel speedup
3. **MemoryUsageTest**: Verify memory overhead acceptable

## Backward Compatibility

### API Compatibility

- `TransformationExecutor.transform()`: Signature unchanged
- `TransformationContext.createTarget()`: Signature unchanged
- `TransformationContext.equivalent()`: Signature unchanged
- All existing tests should pass without modification

### Behavioral Compatibility

- Sequential transformation: Behavior identical to before
- Parallel transformation: Same results, different execution order
- Element ordering in Resource: May differ (documented)

## Migration Guide

### For Existing Transformation Rules

No changes required for most rules. Review if rules:

1. **Store shared mutable state**: Convert to ThreadLocal or eliminate
2. **Depend on element ordering**: Add explicit ordering if needed
3. **Modify source model**: Remove such modifications (was always wrong)

### For Custom TransformationExecutor Usage

No changes required. The staging mechanism is internal.

## XMI ID and Discriminator Handling

### The Problem

EMF's XMI ID mechanism depends on elements being contained in a Resource:

1. **`element.eResource()`** returns `null` for staged (not-yet-committed) elements
2. **`XMIResource.setID(element, id)`** requires the element to be in the resource
3. **`XMIResource.getID(element)`** returns `null` for elements not in the resource
4. **`resource.getURIFragment(element)`** returns path-based fragment for non-ID elements

This affects:
- `TransformationContext.getElementId()` - falls back to UUID when `eResource()` is null
- `TransformationContext.setElementId()` - cannot set XMI ID when not in resource
- `TransformationContext.equivalentDiscriminated()` - relies on getElementId/setElementId
- `TransformationTrace.getElementId()` - same fallback issue

### Current Code Analysis

```java
// TransformationContext.java:344-365
private String getElementId(EObject element) {
    Resource resource = element.eResource();
    if (resource != null) {                    // <-- FAILS for staged elements
        String id = resource.getURIFragment(element);
        if (id != null && !id.startsWith("/")) {
            return id;
        }
    }
    // Fallback to "id" attribute or UUID
}

// TransformationContext.java:367-377
private void setElementId(EObject element, String id) {
    // Set "id" attribute if exists
    Resource resource = element.eResource();
    if (resource instanceof XMIResource) {     // <-- FAILS for staged elements
        ((XMIResource) resource).setID(element, id);
    }
}

// TransformationContext.java:252-255
// In equivalentDiscriminated():
String baseId = getElementId(original);        // <-- May get UUID fallback
String discriminatedId = baseId + "/(discriminator/" + discriminator + ")";
setElementId(clone, discriminatedId);          // <-- XMI ID not set
```

### Solution: Deferred XMI ID Assignment

The solution uses a two-part strategy:

#### Part 1: Track Intended IDs During Staging

Store intended XMI IDs in a separate map during the parallel phase:

```java
public class TransformationContext {
    // Map: staged element -> intended XMI ID
    private final ConcurrentHashMap<EObject, String> pendingXmiIds = new ConcurrentHashMap<>();
    
    /**
     * Get element ID - works for both staged and committed elements.
     */
    private String getElementId(EObject element) {
        // First check pending IDs for staged elements
        String pendingId = pendingXmiIds.get(element);
        if (pendingId != null) {
            return pendingId;
        }
        
        // Then check resource (for committed elements)
        Resource resource = element.eResource();
        if (resource != null) {
            String id = resource.getURIFragment(element);
            if (id != null && !id.startsWith("/")) {
                return id;
            }
        }
        
        // Check "id" structural feature
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null) {
            Object idValue = element.eGet(idFeature);
            if (idValue != null) {
                return idValue.toString();
            }
        }
        
        // Generate and store stable ID for staged element
        String generatedId = UUID.randomUUID().toString();
        if (stagingEnabled.get()) {
            pendingXmiIds.put(element, generatedId);
        }
        return generatedId;
    }
    
    /**
     * Set element ID - stores pending ID for staged elements.
     */
    private void setElementId(EObject element, String id) {
        // Set "id" structural feature if available
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null && idFeature.isChangeable()) {
            element.eSet(idFeature, id);
        }
        
        if (stagingEnabled.get()) {
            // Store for later XMI ID assignment
            pendingXmiIds.put(element, id);
        } else {
            // Direct assignment if already in resource
            Resource resource = element.eResource();
            if (resource instanceof XMIResource) {
                ((XMIResource) resource).setID(element, id);
            }
        }
    }
}
```

#### Part 2: Apply XMI IDs During Commit

When committing staged elements, also apply pending XMI IDs:

```java
void commitStagedElements() {
    if (targetResourceSet.getResources().isEmpty()) {
        return;
    }
    
    Resource targetResource = targetResourceSet.getResources().get(0);
    XMIResource xmiResource = targetResource instanceof XMIResource 
        ? (XMIResource) targetResource : null;
    
    EList<EObject> contents = targetResource.getContents();
    
    StagedElement staged;
    while ((staged = stagedElements.poll()) != null) {
        EObject element = staged.element;
        
        // Add to resource if root element
        if (staged.isRootElement && element.eContainer() == null) {
            contents.add(element);
        }
        
        // Apply pending XMI ID now that element is in resource
        String pendingId = pendingXmiIds.remove(element);
        if (pendingId != null && xmiResource != null) {
            xmiResource.setID(element, pendingId);
        }
    }
    
    // Clear any remaining pending IDs (shouldn't happen normally)
    pendingXmiIds.clear();
}
```

### Discriminated Equivalence Flow

With the updated design, `equivalentDiscriminated()` works correctly:

```
1. equivalentDiscriminated(source, Type, "Rule", "create") called
2. equivalent(source, Type) returns original target (may be staged)
3. getElementId(original) checks:
   a. pendingXmiIds map → returns pending ID if staged
   b. eResource().getURIFragment() → returns XMI ID if committed
   c. "id" attribute → returns if set
   d. Generates UUID and stores in pendingXmiIds
4. Builds discriminatedId = baseId + "/(discriminator/create)"
5. EcoreUtil.copy(original) creates clone
6. setElementId(clone, discriminatedId):
   a. Sets "id" attribute if exists
   b. Stores in pendingXmiIds for later XMI ID assignment
7. Stages clone for commit
8. During commit: applies XMI ID from pendingXmiIds
```

### Thread-Safety of ID Tracking

| Component | Thread-Safe? | Mechanism |
|-----------|--------------|-----------|
| `pendingXmiIds` | Yes | ConcurrentHashMap |
| `getElementId()` | Yes | Read from concurrent map |
| `setElementId()` | Yes | Write to concurrent map |
| XMI ID application | Yes | Single-threaded commit phase |

### ID Stability Guarantee

The design ensures:
1. **Consistent IDs**: Same element always returns same ID within a transformation
2. **Stable discriminated IDs**: Base ID is stable → discriminated ID is stable
3. **XMI ID preservation**: IDs are correctly applied to XMIResource after commit
4. **Trace correctness**: TransformationTrace can use pending IDs for staged elements

### Updated TransformationTrace

TransformationTrace should also check pending IDs:

```java
private String getElementId(EObject element) {
    // Delegate to context for consistent ID resolution
    // OR check both sources:
    
    // Check pending IDs (for staged elements during transform)
    String pendingId = context.getPendingXmiId(element);
    if (pendingId != null) {
        return pendingId;
    }
    
    // Original logic for committed elements
    Resource resource = element.eResource();
    if (resource != null) {
        String fragment = resource.getURIFragment(element);
        if (fragment != null && !fragment.startsWith("/")) {
            return fragment;
        }
    }
    // ... fallbacks
}
```

### Testing Requirements for ID Handling

1. **Test staged element ID retrieval**: Create staged element, verify getElementId returns consistent ID
2. **Test discriminated ID with staging**: Call equivalentDiscriminated during parallel phase, verify IDs are correct
3. **Test XMI ID after commit**: Verify XMI IDs are correctly set after commitStagedElements
4. **Test trace with staged elements**: Export trace during/after transformation, verify IDs are present

## Future Enhancements

1. **Configurable Commit Strategy**: Allow ordering preservation
2. **Parallel Commit**: Investigate concurrent Resource modification
3. **Progress Reporting**: Callback during parallel phase
4. **Cancellation Support**: Interruptible transformation
5. **Transaction Support**: Optional EMF Transaction integration
