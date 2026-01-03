# Tasks: Fix Parallel Execution Race Conditions

## Phase 1: Immediate Fixes (Short-Term)

- [ ] **1.1 Add global lock for cross-thread cache access**
  - Add fallback synchronization when local cache misses
  - Use `ReentrantReadWriteLock` for read-heavy access patterns
  - *Dependency:* None
  - *Location:* `transformation-core/src/main/java/.../ElementResolutionCache.java`

- [ ] **1.2a Reproduce EMF containment race condition with tests** *(PREREQUISITE)*

  **Goal:** Create a failing test that reproduces the Tatami project issue before implementing any fix.

  **Test scenario:**
  - Enable `autoAddRootElements=true`
  - Create parent and child elements in parallel rules
  - Child created first, staged as root element
  - Parent sets containment reference to child in different thread
  - Verify: orphaned elements appear as root elements in resource

  **Test file:** `transformation-core/src/test/java/.../ContainmentRaceConditionTest.java`

  **Expected result:** Test should FAIL initially, proving the race condition exists.

  - *Dependency:* None
  - *Blocking:* Task 1.2b (fix cannot proceed until reproduction confirmed)

- [ ] **1.2b Synchronize ALL EMF Containment Operations** *(CRITICAL - EXPANDED SCOPE)*

  **Problem:** EMF containment operations are NOT thread-safe. When `autoAddRootElements=true`:
  1. Every element created via `createTarget()` is staged with `isRootElement=true`
  2. During parallel execution, containment assignments like `parent.setIcon(icon)` race with other threads
  3. EMF's bidirectional reference updates are corrupted
  4. Icons lose their container reference (`eContainer() == null`)
  5. During commit, orphaned Icons are added to the resource as root elements

  **Scope (choose one approach):**

  **Option A: Synchronize containment operations**
  - Wrap ALL containment assignments with `synchronized(targetResource)`:
    - `parent.setIcon(icon)` → synchronized
    - `parent.getChildren().add(child)` → synchronized
    - `Resource.getContents().add()` → synchronized
  - *Pros:* Minimal code changes
  - *Cons:* Performance impact from contention

  **Option B: Defer containment to commit phase** *(RECOMMENDED)*
  - Stage containment operations instead of executing immediately
  - Execute all containment assignments in single-threaded commit phase
  - *Pros:* No synchronization needed during parallel phase
  - *Cons:* More complex implementation, requires tracking deferred operations

  - *Dependency:* **1.2a** (must reproduce issue first)
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java`
  - *Related:* Esm2UiZetaTransformation.java:272 (`autoAddRootElements=true`)

- [x] **1.3 Add stress tests for high contention scenarios**
  - Created `ParallelRaceConditionStressTest.java`
  - Tests with 6 eager rules calling `equivalent()` on same 5000 sources
  - Tests cross-rule reference chains (A→B→C)
  - Tests @Extends under parallel execution
  - **Result:** All tests pass - `equivalent()` has proper locking
  - *Location:* `transformation-core/src/test/java/.../ParallelRaceConditionStressTest.java`

- [x] **1.3.1 Reproduce equivalentDiscriminated() race condition**
  - Created `EquivalentDiscriminatedRaceTest.java`
  - **BUG CONFIRMED**: `equivalentDiscriminated()` lacks locking
  - Cross-entity reference test: 500 referrers × 6 discriminators = 3000 calls on same source
  - Expected: 6 unique clones (cached), Actual: 7-17 clones (1-11 extra per run)
  - 18/30 runs failed (60% failure rate)
  - `equivalent(source, Class)` tests **pass** (proper locking exists)
  - *Location:* `transformation-core/src/test/java/.../EquivalentDiscriminatedRaceTest.java`

- [x] **1.3.2 Add locking to equivalentDiscriminated()**
  - Added `DiscriminatedCacheKey` class for per-key locking
  - Added `discriminatedLocks` ConcurrentHashMap for thread-safe lock management
  - Implemented double-checked locking pattern around clone creation
  - **Result:** All 30 test runs pass with exactly 6 clones (0 duplicates)
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java` (lines 1403-1475)

- [ ] **1.4 Verify Phase 1 fixes don't break existing tests**
  - Run all 444+ tests in sequential mode
  - Run all tests in parallel mode
  - Verify no performance regression > 20%
  - *Dependency:* 1.1, 1.2

## Phase 2: Thread-Isolated Architecture (Long-Term)

### Partition Strategy

- [ ] **2.1 Implement partition strategy**
  - Create `PartitionStrategy` interface
  - Implement `ContainerBasedPartition` (partition by source element container)
  - Implement `TypeBasedPartition` (partition by source element type)
  - Add partition balancing logic
  - *Dependency:* Phase 1 complete
  - *Location:* `transformation-core/src/main/java/.../parallel/`

- [ ] **2.2 Create partition analyzer**
  - Analyze source model to determine optimal partition boundaries
  - Identify cross-partition references upfront
  - Balance partition sizes for even workload distribution
  - *Dependency:* 2.1
  - *Location:* `transformation-core/src/main/java/.../parallel/PartitionAnalyzer.java`

### Thread-Local State

- [ ] **2.3 Implement thread-local caches**
  - Create `ThreadLocalResolutionCache` with per-thread storage
  - Each worker thread has isolated cache instance
  - No synchronization needed for local cache operations
  - *Dependency:* 2.1
  - *Location:* `transformation-core/src/main/java/.../parallel/ThreadLocalResolutionCache.java`

- [ ] **2.4 Implement thread-local element staging**
  - Create `ThreadLocalElementQueue` for staging created elements
  - Elements not added to Resource until merge phase
  - Track sequence numbers for deterministic ordering
  - *Dependency:* 2.3
  - *Location:* `transformation-core/src/main/java/.../parallel/ThreadLocalElementQueue.java`

- [ ] **2.5 Add cross-partition reference placeholders**
  - Create `DeferredReference` class for cross-partition references
  - Record (source, ruleName, targetType) for later resolution
  - Store placeholder in local cache until merge
  - *Dependency:* 2.3, 2.4
  - *Location:* `transformation-core/src/main/java/.../parallel/DeferredReference.java`

### Merge Phase

- [ ] **2.6 Implement cache merge logic**
  - Merge all thread-local caches into global cache
  - Resolve any conflicts (should not occur with proper partitioning)
  - Verify no duplicate entries
  - *Dependency:* 2.3, 2.4, 2.5
  - *Location:* `transformation-core/src/main/java/.../parallel/CacheMerger.java`

- [ ] **2.7 Implement reference resolution**
  - Resolve all `DeferredReference` placeholders
  - Look up actual targets from merged cache
  - Update EMF references to point to resolved targets
  - *Dependency:* 2.6
  - *Location:* `transformation-core/src/main/java/.../parallel/ReferenceResolver.java`

- [ ] **2.8 Implement element commit logic**
  - Commit staged elements to Resource in deterministic order
  - Sort by sequence number within partition
  - Apply containment relationships correctly
  - *Dependency:* 2.6, 2.7
  - *Location:* `transformation-core/src/main/java/.../parallel/ElementCommitter.java`

### Integration

- [ ] **2.9 Integrate thread-isolated architecture into TransformationExecutor**
  - Add option to use new architecture: `parallelStrategy(ParallelStrategy.THREAD_ISOLATED)`
  - Default to current implementation for backward compatibility
  - Switch based on model size threshold
  - *Dependency:* 2.6, 2.7, 2.8
  - *Location:* `transformation-core/src/main/java/.../TransformationExecutor.java`

- [ ] **2.10 Update TransformationContext for thread-isolated mode**
  - Route cache operations through thread-local storage
  - Handle cross-partition equivalent() calls
  - Support both legacy and new parallel modes
  - *Dependency:* 2.9
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java`

## Phase 3: Validation

- [ ] **3.1 Create comprehensive parallel stress tests**
  - Test with 10,000+ elements
  - Test with deep inheritance hierarchies
  - Test with heavy cross-rule references
  - *Dependency:* 2.9, 2.10

- [ ] **3.2 Validate sequential/parallel equivalence**
  - Run same transformation in both modes
  - Compare element counts
  - Compare all element attributes and references
  - *Dependency:* 3.1

- [ ] **3.3 Performance benchmarking**
  - Measure speedup vs sequential (target: 3x+)
  - Measure overhead vs current parallel implementation
  - Profile hot paths for optimization
  - *Dependency:* 3.2

- [ ] **3.4 Run full test suite**
  - All 444+ tests pass
  - No regressions in sequential mode
  - Parallel mode produces identical results
  - *Dependency:* 3.3

## Implementation Notes

### Partition Strategy Details

```
Partition by Container:
  Container A → Partition 1 (Elements: A1, A2, A3)
  Container B → Partition 2 (Elements: B1, B2, B3)
  Container C → Partition 3 (Elements: C1, C2, C3)

Cross-Partition Reference:
  A1 references B2 → DeferredReference(A1, "RuleX", B2)
  Resolved during merge phase
```

### Thread-Local Cache Structure

```java
class ThreadLocalResolutionCache {
    // Thread-local storage for each worker
    private static final ThreadLocal<Map<CacheKey, Object>> localCache =
        ThreadLocal.withInitial(HashMap::new);

    // Cross-partition references awaiting resolution
    private static final ThreadLocal<List<DeferredReference>> deferredRefs =
        ThreadLocal.withInitial(ArrayList::new);
}
```

### Merge Phase Algorithm

1. Collect all thread-local caches
2. Merge into global cache (check for conflicts)
3. Collect all deferred references
4. Resolve each reference from global cache
5. Collect all staged elements
6. Sort by sequence number
7. Commit to Resource in order

## Verification Criteria

1. Sequential and parallel modes produce identical element counts
2. All containment relationships are correct
3. No NPE during EMF iteration
4. No duplicate elements in collections
5. Performance speedup >= 3x for large models
6. All 444+ tests pass
