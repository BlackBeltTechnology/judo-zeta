# Design: Fix Parallel Execution Race Conditions

## Current Architecture (Problematic)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TransformationExecutor (Parallel Mode)                     │
│                                                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Source Elements                                                     │   │
│   │  [E1, E2, E3, E4, E5, E6, E7, E8, ...]                              │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                    ↓                                         │
│                            Chunk into batches                                │
│                                    ↓                                         │
│   ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐          │
│   │  Worker 1  │  │  Worker 2  │  │  Worker 3  │  │  Worker 4  │          │
│   │  [E1, E2]  │  │  [E3, E4]  │  │  [E5, E6]  │  │  [E7, E8]  │          │
│   └──────┬─────┘  └──────┬─────┘  └──────┬─────┘  └──────┬─────┘          │
│          │               │               │               │                  │
│          └───────────────┴───────────────┴───────────────┘                  │
│                                    ↓                                         │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                    SHARED STATE (Race Condition Sources)              │   │
│   │                                                                       │   │
│   │  ┌──────────────────────┐  ┌──────────────────────┐                 │   │
│   │  │ ElementResolutionCache│  │  Target Resource     │                 │   │
│   │  │ (ConcurrentHashMap)   │  │  (EMF EList)         │                 │   │
│   │  │                      │  │                      │                 │   │
│   │  │  Race: Multiple      │  │  Race: EMF internal  │                 │   │
│   │  │  threads calling     │  │  notification system │                 │   │
│   │  │  getOrCreate() for   │  │  not thread-safe     │                 │   │
│   │  │  same source element │  │                      │                 │   │
│   │  └──────────────────────┘  └──────────────────────┘                 │   │
│   │                                                                       │   │
│   │  ┌──────────────────────┐  ┌──────────────────────┐                 │   │
│   │  │ Rule Descriptor State│  │  Containment Lists   │                 │   │
│   │  │ (rejected caches)    │  │  (EObject.eContents) │                 │   │
│   │  │                      │  │                      │                 │   │
│   │  │  Race: Concurrent    │  │  Race: Concurrent    │                 │   │
│   │  │  guard evaluations   │  │  add/remove ops      │                 │   │
│   │  └──────────────────────┘  └──────────────────────┘                 │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Race Condition Analysis

### Issue 1: NPE in EMF Model Iteration

```
Thread 1                           Thread 2
────────                           ────────
resource.getContents()
  → returns EList
    → iterator.next()
      → eObject.eDirectResource()      resource.getContents().add(newObj)
        → INTERNAL EMF STATE             → EMF notification
          BEING MODIFIED                   → iterator invalidated
            → NPE!
```

**Root Cause:** EMF's internal model traversal uses non-thread-safe iterators that can be invalidated by concurrent modifications.

### Issue 2: Element Count Mismatch

```
Thread 1                           Thread 2
────────                           ────────
cache.getOrCreate(source, rule)    cache.getOrCreate(source, rule)
  → cache.get(key) == null           → cache.get(key) == null
  → execute rule                       → execute rule
  → cache.put(key, target1)            → cache.put(key, target2)
  → add to staging queue               → add to staging queue
                                         → DUPLICATE!
```

**Root Cause:** Even with `computeIfAbsent`, the rule execution itself is not atomic, allowing duplicate creation before cache update.

### Issue 3: Null Container References

```
Thread 1                           Thread 2
────────                           ────────
parent.getContents().add(child)    child.eContainer()
  → modifying parent's list          → reading container ref
    → EMF updating backref             → container not yet set
      → race with read                   → NULL!
```

**Root Cause:** EMF containment relationships involve bidirectional references that are not atomically updated.

### Issue 4: Orphaned Elements with autoAddRootElements=true (CRITICAL)

**Reported from Tatami project production usage.**

When `autoAddRootElements=true` is set in transformation (e.g., `Esm2UiZetaTransformation.java:272`):

```
Thread 1                           Thread 2
────────                           ────────
icon = createTarget(Icon.class)
  → staged with isRootElement=true
  → icon.eContainer() == null

                                   parent.setIcon(icon)
                                     → EMF starts bidirectional update:
                                       1. Set icon.eContainer = parent
                                       2. Add to parent's feature

icon queued for commit               → RACE: icon.eContainer being set
                                       but staging check already passed

Thread 1 continues:
  icon still in staging queue
  (was marked root before containment set)

During single-threaded commit:
  for each staged element:
    if (element.eContainer() == null) {  ← May be null due to race!
      resource.getContents().add(element)
    }

  → Orphaned icons added as root elements!
  → Duplicate elements in model!
```

**Evidence from Zeta documentation (parallel-execution.md):**

| Operation | Thread-Safe | Recommendation |
|-----------|-------------|----------------|
| Direct Resource modification | ❌ No | Avoid in parallel rules |
| Containment assignments | ❌ No | Not documented! |

**Root Cause:**
1. `createTarget()` with `autoAddRootElements=true` marks element as root **before** containment is set
2. Containment assignment (`parent.setIcon(icon)`) happens in different thread
3. EMF's bidirectional reference update is not atomic
4. Commit phase sees `eContainer() == null` due to timing

**Selected Solution: Defer containment to commit phase**

```java
// During parallel phase - just record the operation:
ctx.deferContainment(parent, "icon", icon);

// During single-threaded commit:
for (DeferredContainment dc : deferredContainments) {
    dc.apply();  // Safe: single-threaded
}
```

**Benefits:**
- Preserves parallelism during transformation phase
- All EMF containment operations happen in single thread
- No synchronization overhead during parallel execution

**Implementation Details:**

```java
class DeferredContainment {
    EObject parent;
    String featureName;
    EObject child;

    void apply() {
        EStructuralFeature feature = parent.eClass().getEStructuralFeature(featureName);
        if (feature.isMany()) {
            ((EList<EObject>) parent.eGet(feature)).add(child);
        } else {
            parent.eSet(feature, child);
        }
    }
}
```

---

## Future Work: Thread-Isolated Architecture

> **Note:** The Thread-Isolated Architecture has been moved to a separate proposal: `implement-thread-isolated-parallel-architecture`
>
> The detailed design below is preserved for reference but will be implemented in that separate proposal.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Thread-Isolated Parallel Execution                     │
│                                                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Phase 1: Partitioning                                                │   │
│   │                                                                       │   │
│   │  Source Elements: [E1, E2, E3, E4, E5, E6, E7, E8]                   │   │
│   │                            ↓                                          │   │
│   │  ┌──────────────────────────────────────────────────────────────┐   │   │
│   │  │  PartitionAnalyzer                                            │   │   │
│   │  │  - Group by container (minimize cross-partition refs)        │   │   │
│   │  │  - Balance partition sizes                                    │   │   │
│   │  │  - Identify cross-partition dependencies                      │   │   │
│   │  └──────────────────────────────────────────────────────────────┘   │   │
│   │                            ↓                                          │   │
│   │  Partition 1: [E1, E2]    Partition 2: [E3, E4, E5]                  │   │
│   │  Partition 3: [E6, E7]    Partition 4: [E8]                          │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Phase 2: Isolated Transformation (PARALLEL)                          │   │
│   │                                                                       │   │
│   │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐     │   │
│   │  │   Worker 1      │  │   Worker 2      │  │   Worker 3      │     │   │
│   │  │   ──────────    │  │   ──────────    │  │   ──────────    │     │   │
│   │  │   Partition 1   │  │   Partition 2   │  │   Partition 3   │     │   │
│   │  │                 │  │                 │  │                 │     │   │
│   │  │ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌─────────────┐ │     │   │
│   │  │ │Local Cache  │ │  │ │Local Cache  │ │  │ │Local Cache  │ │     │   │
│   │  │ │(HashMap)    │ │  │ │(HashMap)    │ │  │ │(HashMap)    │ │     │   │
│   │  │ │             │ │  │ │             │ │  │ │             │ │     │   │
│   │  │ │E1 → T1      │ │  │ │E3 → T3      │ │  │ │E6 → T6      │ │     │   │
│   │  │ │E2 → T2      │ │  │ │E4 → T4      │ │  │ │E7 → T7      │ │     │   │
│   │  │ │             │ │  │ │E5 → T5      │ │  │ │             │ │     │   │
│   │  │ └─────────────┘ │  │ └─────────────┘ │  │ └─────────────┘ │     │   │
│   │  │                 │  │                 │  │                 │     │   │
│   │  │ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌─────────────┐ │     │   │
│   │  │ │Staged Queue │ │  │ │Staged Queue │ │  │ │Staged Queue │ │     │   │
│   │  │ │[T1, T2]     │ │  │ │[T3, T4, T5] │ │  │ │[T6, T7]     │ │     │   │
│   │  │ └─────────────┘ │  │ └─────────────┘ │  │ └─────────────┘ │     │   │
│   │  │                 │  │                 │  │                 │     │   │
│   │  │ ┌─────────────┐ │  │ ┌─────────────┐ │  │ ┌─────────────┐ │     │   │
│   │  │ │Deferred Refs│ │  │ │Deferred Refs│ │  │ │Deferred Refs│ │     │   │
│   │  │ │T1 → E3      │ │  │ │(none)       │ │  │ │T7 → E1      │ │     │   │
│   │  │ └─────────────┘ │  │ └─────────────┘ │  │ └─────────────┘ │     │   │
│   │  └─────────────────┘  └─────────────────┘  └─────────────────┘     │   │
│   │                                                                       │   │
│   │  NO SHARED MUTABLE STATE - ALL OPERATIONS ARE LOCAL                  │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Phase 3: Merge (SINGLE-THREADED)                                     │   │
│   │                                                                       │   │
│   │  3a. Merge Local Caches → Global Cache                               │   │
│   │      Worker 1 cache + Worker 2 cache + Worker 3 cache → Global       │   │
│   │                                                                       │   │
│   │  3b. Resolve Deferred References                                      │   │
│   │      T1.ref = lookup(E3) in global cache → T3                        │   │
│   │      T7.ref = lookup(E1) in global cache → T1                        │   │
│   │                                                                       │   │
│   │  3c. Commit Staged Elements                                           │   │
│   │      Sort by sequence number                                          │   │
│   │      For each element: resource.getContents().add(element)           │   │
│   │                                                                       │   │
│   │  ALL EMF OPERATIONS HAPPEN IN SINGLE THREAD - NO RACE CONDITIONS    │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Partition Strategy

### Container-Based Partitioning

```java
public class ContainerBasedPartitionStrategy implements PartitionStrategy {

    @Override
    public List<Partition> partition(Collection<EObject> elements, int numPartitions) {
        // Group elements by their container
        Map<EObject, List<EObject>> byContainer = elements.stream()
            .collect(Collectors.groupingBy(e -> e.eContainer() != null ? e.eContainer() : e));

        // Balance partitions by size
        List<Partition> partitions = new ArrayList<>();
        for (int i = 0; i < numPartitions; i++) {
            partitions.add(new Partition(i));
        }

        // Round-robin assignment of container groups
        int partitionIndex = 0;
        for (Map.Entry<EObject, List<EObject>> entry : byContainer.entrySet()) {
            partitions.get(partitionIndex).addElements(entry.getValue());
            partitionIndex = (partitionIndex + 1) % numPartitions;
        }

        return partitions;
    }
}
```

### Cross-Partition Reference Detection

```java
public class PartitionAnalyzer {

    public Set<CrossPartitionRef> analyzeCrossRefs(List<Partition> partitions) {
        Set<CrossPartitionRef> crossRefs = new HashSet<>();

        for (Partition partition : partitions) {
            for (EObject element : partition.getElements()) {
                for (EReference ref : element.eClass().getEAllReferences()) {
                    if (!ref.isContainment()) {
                        Object target = element.eGet(ref);
                        if (target instanceof EObject) {
                            Partition targetPartition = findPartition((EObject) target, partitions);
                            if (targetPartition != partition) {
                                crossRefs.add(new CrossPartitionRef(
                                    partition.getId(), element, ref, targetPartition.getId()));
                            }
                        }
                    }
                }
            }
        }

        return crossRefs;
    }
}
```

## Thread-Local State Design

### ThreadLocalResolutionCache

```java
public class ThreadLocalResolutionCache {

    // Thread-local cache storage
    private static final ThreadLocal<LocalCacheState> LOCAL_STATE =
        ThreadLocal.withInitial(LocalCacheState::new);

    static class LocalCacheState {
        // Local cache: (source, ruleName) → target
        final Map<CacheKey, EObject> cache = new HashMap<>();

        // Staged elements waiting for commit
        final List<StagedElement> stagedElements = new ArrayList<>();

        // Deferred cross-partition references
        final List<DeferredReference> deferredRefs = new ArrayList<>();

        // Sequence counter for deterministic ordering
        final AtomicLong sequence = new AtomicLong(0);
    }

    public <T extends EObject> T getOrCreate(
            EObject source,
            String ruleName,
            Supplier<T> creator) {

        LocalCacheState state = LOCAL_STATE.get();
        CacheKey key = new CacheKey(source, ruleName);

        // Check local cache first (no synchronization needed)
        T cached = (T) state.cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Execute transformation (no synchronization needed)
        T target = creator.get();

        // Store in local cache
        state.cache.put(key, target);

        // Stage for later commit
        state.stagedElements.add(new StagedElement(
            target,
            state.sequence.getAndIncrement(),
            Thread.currentThread().getId()
        ));

        return target;
    }

    public void recordDeferredReference(
            EObject sourceElement,
            String targetRuleName,
            EReference reference) {

        LOCAL_STATE.get().deferredRefs.add(
            new DeferredReference(sourceElement, targetRuleName, reference));
    }
}
```

### DeferredReference Structure

```java
public class DeferredReference {
    private final EObject sourceElement;      // Source we're referencing
    private final String targetRuleName;      // Rule that transforms it
    private final EObject referringTarget;    // Target that needs the reference
    private final EReference reference;       // The reference to set

    // Used during resolution
    public void resolve(Map<CacheKey, EObject> globalCache) {
        CacheKey key = new CacheKey(sourceElement, targetRuleName);
        EObject resolvedTarget = globalCache.get(key);

        if (resolvedTarget != null) {
            if (reference.isMany()) {
                ((List) referringTarget.eGet(reference)).add(resolvedTarget);
            } else {
                referringTarget.eSet(reference, resolvedTarget);
            }
        }
    }
}
```

## Merge Phase Design

### Cache Merger

```java
public class CacheMerger {

    public Map<CacheKey, EObject> mergeLocalCaches(
            Collection<LocalCacheState> localStates) {

        Map<CacheKey, EObject> globalCache = new HashMap<>();

        for (LocalCacheState state : localStates) {
            for (Map.Entry<CacheKey, EObject> entry : state.cache.entrySet()) {
                CacheKey key = entry.getKey();
                EObject target = entry.getValue();

                // Conflict detection (should never happen with proper partitioning)
                if (globalCache.containsKey(key)) {
                    EObject existing = globalCache.get(key);
                    if (existing != target) {
                        throw new TransformationException(
                            "Duplicate transformation for " + key +
                            " - partition strategy failed to prevent overlap");
                    }
                }

                globalCache.put(key, target);
            }
        }

        return globalCache;
    }
}
```

### Reference Resolver

```java
public class ReferenceResolver {

    public void resolveAllReferences(
            Collection<LocalCacheState> localStates,
            Map<CacheKey, EObject> globalCache) {

        for (LocalCacheState state : localStates) {
            for (DeferredReference ref : state.deferredRefs) {
                ref.resolve(globalCache);
            }
        }
    }
}
```

### Element Committer

```java
public class ElementCommitter {

    public void commitToResource(
            Resource resource,
            Collection<LocalCacheState> localStates) {

        // Collect all staged elements
        List<StagedElement> allStaged = new ArrayList<>();
        for (LocalCacheState state : localStates) {
            allStaged.addAll(state.stagedElements);
        }

        // Sort by sequence number for deterministic ordering
        allStaged.sort(Comparator
            .comparingLong(StagedElement::getThreadId)
            .thenComparingLong(StagedElement::getSequence));

        // Commit to resource (single-threaded, no synchronization needed)
        for (StagedElement staged : allStaged) {
            if (staged.isRootElement()) {
                resource.getContents().add(staged.getElement());
            }
            // Non-root elements are added via containment relationships
        }
    }
}
```

## Integration with TransformationExecutor

```java
public class TransformationExecutor {

    public enum ParallelStrategy {
        LEGACY_SHARED_STATE,    // Current implementation
        THREAD_ISOLATED         // New implementation
    }

    public void transform() {
        if (!parallel) {
            transformSequential(sourceElements);
        } else if (parallelStrategy == ParallelStrategy.THREAD_ISOLATED) {
            transformThreadIsolated(sourceElements);
        } else {
            transformParallelLegacy(sourceElements);
        }
    }

    private void transformThreadIsolated(Collection<? extends EObject> sourceElements) {
        // Phase 1: Partition
        List<Partition> partitions = partitionStrategy.partition(
            sourceElements, Runtime.getRuntime().availableProcessors());

        // Phase 2: Transform (parallel)
        List<Future<LocalCacheState>> futures = new ArrayList<>();
        for (Partition partition : partitions) {
            futures.add(executor.submit(() -> {
                ThreadLocalResolutionCache.init();  // Initialize thread-local state
                for (EObject source : partition.getElements()) {
                    transformElement(source);
                }
                return ThreadLocalResolutionCache.getLocalState();
            }));
        }

        // Wait for all workers
        List<LocalCacheState> localStates = new ArrayList<>();
        for (Future<LocalCacheState> future : futures) {
            localStates.add(future.get());
        }

        // Phase 3: Merge (single-threaded)
        Map<CacheKey, EObject> globalCache = cacheMerger.mergeLocalCaches(localStates);
        referenceResolver.resolveAllReferences(localStates, globalCache);
        elementCommitter.commitToResource(targetResource, localStates);
    }
}
```

## Performance Analysis

### Expected Speedup

| Scenario | Legacy Parallel | Thread-Isolated | Improvement |
|----------|-----------------|-----------------|-------------|
| Low cross-refs | 4x | 4x | Same |
| Medium cross-refs | 3x | 3.5x | +17% |
| High cross-refs | 2x | 3x | +50% |
| Very high cross-refs | 1x (sequential fallback) | 2.5x | +150% |

### Why Thread-Isolated Is Faster

1. **No Lock Contention**: Local caches require no synchronization
2. **No Cache Line Bouncing**: Thread-local storage avoids CPU cache invalidation
3. **Batch Commit**: Single-threaded commit phase is more cache-efficient
4. **Predictable Performance**: No lock convoy effects under high load

### Memory Overhead

- **Per-worker overhead**: ~O(elements_in_partition) for local cache
- **Merge phase peak**: ~O(total_elements) for global cache
- **Acceptable for**: Models up to millions of elements on modern hardware

## Backward Compatibility

1. **Default to Legacy**: Existing code continues to work unchanged
2. **Opt-in**: New parallel strategy enabled via builder:
   ```java
   TransformationExecutor.builder()
       .parallelStrategy(ParallelStrategy.THREAD_ISOLATED)
       .build();
   ```
3. **Automatic Selection**: Auto-selects based on model size (>1000 elements):
   ```java
   if (sourceElements.size() > 1000) {
       parallelStrategy = ParallelStrategy.THREAD_ISOLATED;
   }
   ```
   This threshold is chosen to balance overhead vs. safety - thread-isolation has minimal setup cost but provides critical safety for larger transformations.

## Testing Strategy

1. **Unit Tests**: Each component tested in isolation
2. **Integration Tests**: Full transformation with both strategies
3. **Stress Tests**: High-contention scenarios with many cross-references
4. **Equivalence Tests**: Sequential vs parallel produce identical results
5. **Performance Tests**: Measure speedup vs legacy implementation
