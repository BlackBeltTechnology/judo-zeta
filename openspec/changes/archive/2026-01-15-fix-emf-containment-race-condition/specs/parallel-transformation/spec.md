# Parallel Transformation Specification Delta

## ADDED Requirements

### Requirement: Deferred Writes for Parallel Execution

The parallel transformation MUST use deferred writes to prevent EMF EList corruption during concurrent containment operations.

#### Scenario: Deferred writes enabled during parallel phase

**Given** a transformation executing with `parallel=true`
**And** the element count exceeds the parallel threshold
**When** the parallel transformation phase begins
**Then** deferred writes mode is automatically enabled
**And** `createTarget()` returns proxied EObjects
**And** EList modifications are queued, not executed immediately

#### Scenario: Containment operations are deferred

**Given** parallel transformation is in progress
**And** deferred writes mode is enabled
**When** a rule calls `parent.getChildren().add(child)`
**Then** the add operation is recorded in the OperationQueue
**And** the operation includes a sequence number for ordering
**And** the actual EList is NOT modified during parallel phase

#### Scenario: Deferred operations replayed before commit

**Given** parallel transformation phase has completed
**And** deferred operations are queued
**When** `applyDeferredOperations()` is called
**Then** all operations are sorted by sequence number
**And** operations are applied single-threaded in sequence order
**And** this happens BEFORE `commitStagedElements()`

### Requirement: High Containment Contention Safety

The transformation framework MUST handle transformations with high containment contention without data corruption.

#### Scenario: Many fields added to few tables

**Given** a transformation similar to ASM2RDBMS
**And** a flat structure with few parent objects
**And** many child objects added to each parent's containment
**When** multiple threads add children to the same parent concurrently
**Then** no EList internal state corruption occurs
**And** all children are correctly contained after transformation
**And** no `NullPointerException` during model iteration

#### Scenario: Resource attachment after deferred writes

**Given** parallel transformation with deferred writes
**And** deferred operations have been applied
**When** `commitStagedElements()` adds root elements to Resource
**Then** `ResourceImpl.attached()` can safely iterate all descendants
**And** `EcoreUtil.getAllProperContents()` returns valid elements
**And** no `preparedResult is null` error occurs

### Requirement: Deterministic Operation Ordering

Deferred operations MUST be applied in a deterministic order to ensure reproducible transformation results.

#### Scenario: Operations ordered by sequence number

**Given** multiple threads queueing deferred operations
**And** each operation has a unique sequence number
**When** operations are replayed
**Then** operations are sorted by sequence number ascending
**And** the order is identical across multiple transformation runs
**And** sequential and parallel modes produce equivalent results

#### Scenario: Operations from same thread maintain relative order

**Given** Thread A queues operations Op1, Op2, Op3 in that order
**And** each operation gets a sequence number atomically
**When** operations are replayed
**Then** Op1 is applied before Op2
**And** Op2 is applied before Op3
**Because** sequence numbers preserve intra-thread ordering
