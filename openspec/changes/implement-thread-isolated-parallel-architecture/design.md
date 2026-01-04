# Design: Thread-Isolated Parallel Architecture

## Overview

This design eliminates shared mutable state during parallel transformation by giving each worker thread its own isolated cache and staging queue. All EMF operations are deferred to a single-threaded merge phase.

## Architecture

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

        // Sort by (threadId, sequence) for deterministic ordering
        allStaged.sort(Comparator
            .comparingLong(StagedElement::getThreadId)
            .thenComparingLong(StagedElement::getSequence));

        // Commit in order
        for (StagedElement staged : allStaged) {
            if (staged.getElement().eContainer() == null) {
                resource.getContents().add(staged.getElement());
            }
        }
    }
}
```

## Merge Phase Algorithm

1. Collect all thread-local caches
2. Merge into global cache (check for conflicts)
3. Collect all deferred references
4. Resolve each reference from global cache
5. Collect all staged elements
6. Sort by sequence number
7. Commit to Resource in order

## Thread Safety Guarantees

| Phase | Shared State | Synchronization |
|-------|--------------|-----------------|
| Partitioning | Read-only source | None needed |
| Transformation | Thread-local only | None needed |
| Merge | Sequential access | None needed |

All potential race conditions are eliminated by design.
