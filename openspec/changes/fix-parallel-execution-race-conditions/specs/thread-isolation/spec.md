# thread-isolation Spec Delta

## ADDED Requirements

### Requirement: Thread-Isolated Parallel Architecture

The transformation framework MUST support an optional thread-isolated parallel execution mode that eliminates shared mutable state during the transformation phase.

#### Scenario: Thread-isolated mode is enabled via builder

**Given** a TransformationExecutor is being configured
**When** the builder sets `parallelStrategy(ParallelStrategy.THREAD_ISOLATED)`
**Then** the executor uses thread-isolated parallel architecture
**And** each worker thread has isolated local state
**And** no shared mutable state is accessed during transformation phase

#### Scenario: Legacy mode remains default for backward compatibility

**Given** a TransformationExecutor is being configured
**And** no parallel strategy is explicitly set
**When** parallel execution is enabled
**Then** the executor uses the legacy shared-state parallel architecture
**And** existing behavior is preserved

---

### Requirement: Partition-Based Work Distribution

Source elements MUST be partitioned to minimize cross-partition references before parallel transformation.

#### Scenario: Elements are grouped by container

**Given** a collection of source elements with various containers
**When** partitioning is performed
**Then** elements with the same container are assigned to the same partition
**And** cross-partition references are minimized
**And** partition sizes are balanced within 20% of each other

#### Scenario: Partition count matches available processors

**Given** a machine with N available processors
**When** partitioning is performed
**Then** the number of partitions is at most N
**And** each worker thread processes exactly one partition
**And** no work stealing occurs between workers

#### Scenario: Thread-isolated mode auto-selects for large models

**Given** a transformation with more than 1000 source elements
**And** parallel execution is enabled
**When** the transformation begins
**Then** thread-isolated mode is automatically selected
**And** partition-based processing is used

#### Scenario: Small element sets use legacy mode

**Given** a collection of fewer than 1000 source elements
**And** no explicit parallel strategy is set
**When** the transformation begins
**Then** legacy shared-state parallel mode is used
**And** thread-isolation overhead is avoided for small transformations

---

### Requirement: Thread-Local Resolution Cache

Each worker thread MUST have an isolated local cache that requires no synchronization.

#### Scenario: Worker creates elements in local cache

**Given** thread-isolated parallel mode is active
**And** a worker thread is transforming elements from its partition
**When** the worker calls `cache.getOrCreate(source, rule, creator)`
**Then** the result is stored in the thread-local cache
**And** no synchronization is required
**And** other workers cannot see this cache entry until merge phase

#### Scenario: Local cache lookup is lock-free

**Given** thread-isolated parallel mode is active
**And** a worker has previously cached a result for (source, rule)
**When** `equivalent()` is called again for the same pair
**Then** the cached result is returned immediately
**And** no locks are acquired
**And** no global cache is consulted

#### Scenario: Cross-partition equivalent creates deferred reference

**Given** thread-isolated parallel mode is active
**And** Worker 1 is transforming element E1 from Partition A
**And** E1 references element E2 which is in Partition B (Worker 2's partition)
**When** Worker 1 calls `equivalent(E2, targetType)`
**Then** a DeferredReference is created and stored locally
**And** Worker 1 continues without waiting for Worker 2
**And** the reference is resolved during merge phase

---

### Requirement: Element Staging Queue

Created elements MUST be staged in thread-local queues until the merge phase.

#### Scenario: Created elements are not added to Resource during parallel phase

**Given** thread-isolated parallel mode is active
**And** a worker thread calls `createTarget()` and `addToResource()`
**When** the element is created
**Then** the element is added to the thread-local staging queue
**And** the element is NOT added to the target Resource
**And** no EMF notifications are triggered during this phase

#### Scenario: Staged elements include sequence numbers

**Given** thread-isolated parallel mode is active
**When** elements are added to the staging queue
**Then** each element is assigned a monotonically increasing sequence number
**And** the sequence number is unique within the thread
**And** the sequence enables deterministic ordering during commit

---

### Requirement: Single-Threaded Merge Phase

After parallel transformation completes, a single thread MUST merge all local state and commit to the target Resource.

#### Scenario: Local caches are merged into global cache

**Given** all worker threads have completed transformation
**When** the merge phase begins
**Then** all thread-local caches are merged into a single global cache
**And** the merge is performed by a single thread
**And** duplicate key conflicts indicate a partitioning failure

#### Scenario: Deferred references are resolved from global cache

**Given** the merge phase has merged all local caches
**And** there are pending deferred references
**When** reference resolution is performed
**Then** each DeferredReference is resolved using the global cache
**And** the resolved target is set on the referring element
**And** all cross-partition references are now valid

#### Scenario: Staged elements are committed in deterministic order

**Given** the merge phase has resolved all references
**When** elements are committed to the target Resource
**Then** elements are sorted by (threadId, sequenceNumber)
**And** elements are added to Resource in this sorted order
**And** the order is deterministic across multiple runs
**And** containment relationships are preserved

#### Scenario: Only root elements are added to Resource contents

**Given** staged elements include both root and contained elements
**When** elements are committed to the target Resource
**Then** only elements without a container are added to Resource.contents
**And** contained elements are accessible via their container
**And** no element appears twice in the Resource

---

### Requirement: Race Condition Prevention

The thread-isolated architecture MUST prevent all classes of race conditions observed in production.

#### Scenario: No NPE in EMF model iteration

**Given** thread-isolated parallel mode is active
**And** source model is being traversed by worker threads
**When** workers iterate over source elements
**Then** no NullPointerException is thrown during iteration
**And** EMF internal state is not concurrently modified
**And** source model traversal uses snapshot iterators

#### Scenario: Element count is consistent between modes

**Given** a transformation that can run in sequential or parallel mode
**When** the transformation is run in sequential mode producing N elements
**And** the transformation is run in thread-isolated parallel mode
**Then** the parallel mode also produces exactly N elements
**And** no extra elements are created due to race conditions
**And** no elements are lost due to race conditions

#### Scenario: Container references are never null

**Given** thread-isolated parallel mode is active
**And** elements are created with containment relationships
**When** the merge phase completes
**Then** all contained elements have non-null eContainer()
**And** containment is set before any reference resolution
**And** EMF bidirectional references are consistent

---

### Requirement: Performance Target

The thread-isolated architecture MUST maintain significant speedup over sequential execution.

#### Scenario: Speedup target is met for large models

**Given** a source model with 10,000+ elements
**And** a transformation with moderate cross-rule references
**When** running in thread-isolated parallel mode
**Then** the speedup over sequential is at least 3x
**And** the speedup is measured on wall-clock time

#### Scenario: No regression for small models

**Given** a source model with fewer than 1,000 elements
**When** running in thread-isolated parallel mode
**Then** performance is within 10% of sequential mode
**And** parallel overhead is acceptable for small models

#### Scenario: Memory usage is bounded

**Given** a large source model during parallel transformation
**When** the merge phase completes
**Then** thread-local caches are eligible for garbage collection
**And** memory usage returns to sequential transformation levels
**And** no memory leak from parallel state

---

## MODIFIED Requirements

### Requirement: Thread-Safe Element Resolution Cache (Modified)

The ElementResolutionCache MUST support both legacy shared-state mode and new thread-isolated mode.

#### Scenario: Cache mode is determined by parallel strategy

**Given** a transformation with parallel execution enabled
**When** `ParallelStrategy.THREAD_ISOLATED` is set
**Then** the cache operates in thread-isolated mode
**And** getOrCreate uses thread-local storage

**When** `ParallelStrategy.LEGACY_SHARED_STATE` is set or no strategy is specified
**Then** the cache operates in legacy shared-state mode
**And** getOrCreate uses global ConcurrentHashMap with lock striping
