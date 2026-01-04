# Spec: Parallel Transformation Execution

**Capability**: parallel-transformation  
**Status**: Active  
**Parent**: transformation-core

## Purpose

This specification defines the requirements for thread-safe parallel transformation execution in the Zeta transformation framework. It enables concurrent transformation of large EMF models while maintaining data integrity and deterministic output ordering.
## Requirements
### Requirement: Element Staging for Parallel Execution

The transformation framework MUST support staging of created target elements during parallel transformation to avoid concurrent modification of EMF Resources.

#### Scenario: Staging is enabled during parallel transformation

**Given** a transformation with parallel execution enabled  
**And** the source element count exceeds the parallel threshold (5000)  
**When** the transformation begins  
**Then** element staging mode is activated  
**And** created elements are queued instead of added to the target Resource  

#### Scenario: Staging is disabled during sequential transformation

**Given** a transformation with parallel execution disabled  
**Or** the source element count is below the parallel threshold  
**When** the transformation begins  
**Then** element staging mode remains disabled  
**And** created elements are added directly to the target Resource  

#### Scenario: Staged elements are committed after parallel phase

**Given** a parallel transformation has completed the transformation phase  
**When** all worker threads have finished  
**Then** all staged elements are added to the target Resource  
**And** the staging queue is empty  
**And** only root elements (not contained) are added to Resource.contents  

---

### Requirement: Thread-Safe Element Creation

The `createTarget()` method MUST be safe for concurrent calls from multiple threads.

#### Scenario: Multiple threads call createTarget concurrently

**Given** parallel transformation is in progress  
**And** multiple worker threads are executing transformation rules  
**When** each thread calls `context.createTarget(TargetType.class)`  
**Then** each thread receives a unique target element instance  
**And** no ConcurrentModificationException is thrown  
**And** all created elements are captured in the staging queue  

#### Scenario: Created element is immediately usable by creating thread

**Given** a worker thread calls `context.createTarget(Table.class)`  
**When** the element is created  
**Then** the creating thread can immediately set properties on the element  
**And** the creating thread can add children to the element's containment references  
**And** these operations do not affect other threads  

---

### Requirement: Thread-Safe Element Resolution Cache

The ElementResolutionCache MUST support concurrent read and write access from multiple threads with atomic get-or-create semantics.

#### Scenario: Cache key includes rule name for isolation

**Given** a transformation with multiple rules that transform the same source type
**And** Rule A and Rule B both apply to the same source element
**When** `equivalent()` is called for Rule A
**And** `equivalent()` is called for Rule B
**Then** each rule has an independent cache entry
**And** cache key is `(source, ruleName)` not `(source, targetType)`
**And** cross-rule cache pollution is prevented

#### Scenario: Atomic get-or-create prevents duplicate creation

**Given** parallel transformation is in progress
**And** Thread A and Thread B call `equivalent()` for the same (source, rule) pair simultaneously
**When** Thread A begins rule execution
**Then** Thread B waits for Thread A to complete
**And** Thread B receives the cached result from Thread A
**And** only one target element is created
**And** `computeIfAbsent()` is used for atomic cache operations

#### Scenario: Per-element locking prevents race conditions

**Given** parallel transformation with many threads
**And** multiple threads request equivalent for same source
**When** the first thread acquires the lock
**Then** other threads wait on the lock
**And** lock is per `(source, ruleName)` pair not global
**And** unrelated sources execute in parallel without blocking

---

### Requirement: Concurrent Lazy Rule Execution

The `equivalent()` method MUST handle concurrent calls for the same source element without creating duplicate targets.

#### Scenario: Concurrent equivalent() for same source

**Given** parallel transformation is in progress  
**And** Thread A calls `equivalent(source, TargetType.class)`  
**And** Thread B calls `equivalent(source, TargetType.class)` simultaneously  
**When** both calls complete  
**Then** only one lazy rule execution occurs  
**And** both threads receive the same target element  
**And** only one target element exists in the staging queue  

#### Scenario: First thread executes, others wait

**Given** multiple threads call `equivalent()` for the same source  
**When** the first thread begins lazy rule execution  
**Then** other threads wait for the execution to complete  
**And** other threads receive the cached result  
**And** no duplicate transformations occur  

#### Scenario: Cache is checked before execution

**Given** a source element has already been transformed  
**When** `equivalent()` is called for that source  
**Then** the cached target is returned immediately  
**And** no lazy rule execution occurs  

---

### Requirement: Staged Element Commit

The commit phase MUST transfer all staged elements to the target Resource in a single-threaded manner.

#### Scenario: All staged elements are committed

**Given** a parallel transformation has completed  
**And** the staging queue contains N elements  
**When** `commitStagedElements()` is called  
**Then** all N root elements are added to `targetResource.getContents()`  
**And** the staging queue is empty after commit  

#### Scenario: Contained elements are not duplicated

**Given** a transformation creates a parent element with children  
**And** the children are added to the parent's containment reference  
**When** `commitStagedElements()` is called  
**Then** only the parent (root) element is added to Resource.contents  
**And** children remain contained within their parent  
**And** children are not added separately to Resource.contents  

#### Scenario: Commit handles empty queue gracefully

**Given** a parallel transformation completes  
**And** no elements were created (empty transformation)  
**When** `commitStagedElements()` is called  
**Then** no exceptions are thrown  
**And** the target Resource is unchanged  

---

### Requirement: Parallel Transformation Performance

Parallel transformation MUST provide performance benefits for large models.

#### Scenario: Parallel transformation is faster than sequential

**Given** a model with more than 10,000 source elements  
**And** transformation rules that perform non-trivial work  
**When** parallel transformation is executed on a multi-core system  
**Then** the transformation completes faster than sequential execution  
**And** the speedup is proportional to available CPU cores (up to a limit)  

#### Scenario: Sequential fallback for small models

**Given** a model with fewer than 5000 source elements  
**When** transformation is requested with parallel=true  
**Then** the transformation executes sequentially  
**And** no parallel overhead is incurred  

---

### Requirement: Fail-Fast Error Handling

The parallel transformation MUST use fail-fast error handling to abort on the first error.

#### Scenario: First exception aborts all threads

**Given** parallel transformation is in progress with multiple worker threads  
**And** one transformation rule throws an exception  
**When** the exception occurs  
**Then** the error is recorded in a shared AtomicReference  
**And** other worker threads check for errors and abort their work  
**And** no partial results are committed to the target Resource  

#### Scenario: No partial commit on failure

**Given** a transformation with 10,000 elements  
**And** an error occurs after 5,000 elements are staged  
**When** the error is detected  
**Then** all staged elements are cleared  
**And** the target Resource remains unchanged  
**And** a TransformationException is thrown with the original cause  

#### Scenario: Post-transformation hooks still execute on failure

**Given** a transformation fails with an exception  
**When** the error is handled  
**Then** staged elements are cleared  
**And** staging mode is disabled  
**And** post-transformation hooks are still invoked for cleanup  

---

### Requirement: Deterministic Element Ordering

The parallel transformation MUST preserve deterministic element ordering in the target Resource.

#### Scenario: Elements are ordered by creation sequence

**Given** parallel transformation creates elements across multiple threads  
**When** elements are committed to the target Resource  
**Then** elements appear in the order they were created (by sequence number)  
**And** the order is deterministic across multiple runs  

#### Scenario: Creation sequence is thread-safe

**Given** multiple threads calling `createTarget()` simultaneously  
**When** sequence numbers are assigned  
**Then** each element receives a unique sequence number  
**And** sequence numbers are assigned atomically (no duplicates)  

#### Scenario: Ordering is applied during commit

**Given** staged elements with sequence numbers  
**When** `commitStagedElements()` is called  
**Then** elements are sorted by sequence number before adding to Resource  
**And** the elementOrder map is cleared after commit  

#### Scenario: Contained elements are ordered within parent

**Given** a parent element with multiple children added from different threads  
**When** `commitStagedElements()` is called  
**Then** children are sorted by creation sequence within their parent's containment list  
**And** the order is deterministic across multiple runs  

#### Scenario: Nested containment ordering

**Given** elements with multiple levels of containment (parent -> child -> grandchild)  
**When** `commitStagedElements()` is called  
**Then** ordering is applied recursively at each containment level  
**And** all levels maintain deterministic creation order  

---

### Requirement: XMI ID Handling for Staged Elements

The transformation framework MUST correctly handle XMI IDs for elements that are staged during parallel transformation.

#### Scenario: Staged element ID is consistent across calls

**Given** a parallel transformation is in progress  
**And** an element has been created via `createTarget()` but not yet committed  
**When** `getElementId()` is called multiple times for the same element  
**Then** the same ID is returned each time  
**And** the ID is stored in a pending ID map for later application  

#### Scenario: Discriminated ID uses stable base ID

**Given** a parallel transformation is in progress  
**And** `equivalentDiscriminated()` is called for a staged element  
**When** the discriminated ID is constructed  
**Then** `getElementId()` returns a stable base ID (from pending map or attribute)  
**And** the discriminated ID is correctly formed as `baseId/(discriminator/value)`  
**And** the discriminated ID is stored in the pending ID map  

#### Scenario: XMI IDs are applied during commit

**Given** a parallel transformation has completed  
**And** staged elements have pending XMI IDs  
**When** `commitStagedElements()` is called  
**Then** each element is added to the target Resource  
**And** the pending XMI ID is applied via `XMIResource.setID()`  
**And** the pending ID map is cleared  

#### Scenario: ID attribute is set regardless of staging

**Given** a target element type has an "id" structural feature  
**When** `setElementId()` is called (staging enabled or disabled)  
**Then** the "id" attribute is set on the element  
**And** this occurs immediately, not deferred  

---

### Requirement: TransformationException Class

The framework MUST provide a TransformationException class for reporting transformation failures.

#### Scenario: TransformationException is a RuntimeException

**Given** a transformation fails with an error  
**When** the error is thrown  
**Then** a TransformationException is thrown  
**And** it extends RuntimeException  
**And** it contains the original cause  

#### Scenario: TransformationException includes context

**Given** a transformation rule fails on a specific element  
**When** TransformationException is created  
**Then** it includes the failed element (if available)  
**And** it includes the rule name (if available)  
**And** it includes the original exception as cause  

---

### Requirement: Executor Reusability

The TransformationExecutor MUST be reusable for multiple transformations.

#### Scenario: Executor resets state between transformations

**Given** a TransformationExecutor instance  
**And** a previous transformation has completed (success or failure)  
**When** a new transformation is started  
**Then** the executor resets its internal state  
**And** firstError is cleared  
**And** staged elements are cleared  
**And** element ordering is reset  
**And** pending XMI IDs are cleared  

#### Scenario: Executor can run multiple transformations

**Given** a single TransformationExecutor instance  
**When** transform() is called multiple times  
**Then** each transformation executes independently  
**And** no state leaks between transformations  

---

### Requirement: createTarget Behavior

The `createTarget()` method MUST support staging mode for thread-safe parallel execution.

#### Scenario: createTarget with staging enabled

**Given** staging mode is enabled  
**When** `createTarget(TargetType.class)` is called  
**Then** a new target element is created via EFactory  
**And** the element is NOT added to the target Resource  
**And** the element is added to the staging queue  
**And** the element is returned to the caller  

#### Scenario: createTarget with staging disabled

**Given** staging mode is disabled (sequential execution)  
**When** `createTarget(TargetType.class)` is called  
**Then** a new target element is created via EFactory  
**And** the element IS added to the target Resource.contents  
**And** the element is returned to the caller  
**And** this matches the original behavior  

---

### Requirement: equivalentDiscriminated Behavior

The `equivalentDiscriminated()` method MUST respect staging mode.

#### Scenario: equivalentDiscriminated with staging enabled

**Given** staging mode is enabled  
**When** `equivalentDiscriminated()` creates a cloned element  
**Then** the cloned element is added to the staging queue  
**And** the cloned element is NOT added to the target Resource directly  

---

### Requirement: Concurrency Stress Test Coverage

The transformation framework MUST include comprehensive concurrency stress tests to catch edge cases that are hard to reproduce in production.

#### Scenario: Concurrent element creation is stable

**Given** N threads (e.g., 100) each creating M elements (e.g., 100)  
**And** all threads start simultaneously using synchronization primitives  
**When** all threads complete their work  
**Then** exactly N*M elements exist in the staging queue  
**And** no elements are duplicated or missing  
**And** no ConcurrentModificationException is thrown  

#### Scenario: Concurrent XMI ID access returns consistent IDs

**Given** multiple threads calling `getElementId()` for the same staged element  
**When** all threads complete  
**Then** all threads received the exact same ID value  
**And** the ID is stable and deterministic  

#### Scenario: Concurrent list modification is handled safely

**Given** multiple threads attempting to add elements to the same target collection  
**And** threads are synchronized to maximize collision probability  
**When** all threads complete their modifications  
**Then** no ConcurrentModificationException is thrown  
**And** all modifications are correctly captured  
**And** the test passes reliably across multiple iterations  

#### Scenario: Mixed read/write operations complete without deadlock

**Given** threads performing concurrent reads (cache lookups)  
**And** threads performing concurrent writes (element creation)  
**And** threads performing read+write operations (equivalent() calls)  
**When** all operations complete within a timeout period  
**Then** no deadlock occurs  
**And** all data is consistent after completion  

#### Scenario: ID stability under high contention

**Given** 50+ threads accessing the same element simultaneously  
**And** each thread calls `getElementId()` and stores the result  
**When** all threads complete  
**Then** all stored IDs are identical  
**And** the test passes across multiple iterations  

#### Scenario: Long-running parallel transformation remains stable

**Given** a transformation of 100,000+ elements running in parallel  
**When** the transformation runs for extended duration  
**Then** no memory leaks occur in pending ID tracking  
**And** all IDs are correctly applied after commit  
**And** the transformation completes successfully  

---

### Requirement: Transformation Determinism Verification

The transformation framework MUST include tests that verify sequential and parallel execution produce identical results.

#### Scenario: Sequential and parallel produce identical XMI output

**Given** a transformation with a source model of N elements  
**And** the transformation is executed in sequential mode  
**And** the same transformation is executed in parallel mode  
**When** both target resources are serialized to XMI  
**Then** the XMI byte output is identical for both executions  

#### Scenario: Multiple parallel runs produce identical output

**Given** a transformation with a source model  
**And** the transformation is executed in parallel mode multiple times  
**When** all target resources are serialized to XMI  
**Then** all XMI outputs are byte-identical  

#### Scenario: Nested containment ordering is deterministic

**Given** a source model with nested containment (3+ levels)  
**And** the transformation creates corresponding nested target elements  
**When** the transformation is executed in parallel mode multiple times  
**Then** child elements within each parent are in the same order  
**And** the order matches the sequential execution order  

#### Scenario: Large model transformation is deterministic

**Given** a source model with 10,000+ elements  
**And** the element count exceeds the parallel threshold  
**When** the transformation is executed in sequential and parallel modes  
**Then** both modes produce structurally equal target models  
**And** XMI serialization produces identical output

### Requirement: Thread-Safe EMF Operations

EMF operations during parallel transformation MUST be synchronized to prevent NPE and data corruption.

#### Scenario: Element is fully initialized before caching

**Given** a thread creates a new target element
**When** the element is added to the cache
**Then** the element's EMF internal state is fully initialized
**And** `eResource()` returns non-null if attached
**And** other threads accessing the cached element see a consistent state

#### Scenario: XMI ID operations are synchronized

**Given** parallel transformation setting XMI IDs
**When** multiple threads set IDs on elements in the same Resource
**Then** `XMLResource.setID()` calls are synchronized on the Resource
**And** no ConcurrentModificationException is thrown
**And** all IDs are correctly set

---

### Requirement: Proxy Unwrapping Before Resource Addition

Proxy objects created by deferred writes MUST be unwrapped before being added to the target EMF Resource.

#### Scenario: Staged elements are unwrapped before commit

**Given** deferred writes mode is enabled
**And** transformation creates proxied target elements
**When** `commitStagedElements()` is called
**Then** all proxied elements are unwrapped to real EObjects
**And** the target resource contains only real EObjects
**And** no `DeferredEObject.ProxyMarker` instances exist in resource

#### Scenario: Model serialization succeeds after parallel transformation

**Given** parallel transformation with deferred writes enabled
**And** transformation completes with staged elements
**When** the target model is serialized to XMI
**Then** serialization completes without ClassCastException
**And** all containment features serialize correctly
**And** output XMI is valid and well-formed

### Requirement: Proxy Reference Cleanup

Reference values in the transformed model MUST NOT contain proxy objects after transformation completes.

#### Scenario: Post-commit cleanup unwraps remaining proxies

**Given** parallel transformation has completed
**And** some reference values may contain proxy objects
**When** `unwrapAllProxiesInModel()` is called
**Then** all EReference values are scanned for proxies
**And** proxy references are replaced with unwrapped real objects
**And** no proxy instances remain in the model graph

### Requirement: Atomic Parent Rule Execution

The `executeParentRule()` method MUST use atomic get-or-create operations to prevent duplicate parent rule execution during parallel transformation.

#### Scenario: Concurrent executeParentRule() returns same instance

**Given** parallel transformation with multiple worker threads
**And** child rule "ChildA" extends parent rule "ParentRule"
**And** child rule "ChildB" also extends parent rule "ParentRule"
**When** Thread A calls `executeParentRule("ParentRule", source)` for ChildA
**And** Thread B calls `executeParentRule("ParentRule", source)` for ChildB simultaneously
**Then** only one parent rule execution occurs
**And** both threads receive the same target element instance
**And** the target is cached for future lookups

#### Scenario: Inheritance state preserved during atomic execution

**Given** parallel transformation is in progress
**And** a child rule provides a pre-created target via `executeParentRule(name, source, target)`
**When** multiple threads call `executeParentRule()` for the same parent
**Then** the first thread's pre-created target is used
**And** subsequent threads receive the same cached result
**And** inheritance state (preCreatedTarget, inInheritanceExecution) is correctly managed

### Requirement: Atomic Inheritance Chain Execution

The `@Extends` inheritance chain execution MUST use atomic operations to prevent duplicate parent execution in multi-level inheritance.

#### Scenario: Multi-level inheritance executes each parent once

**Given** rule "GrandChild" extends "Child" extends "Parent"
**And** parallel transformation is processing element E
**When** multiple threads execute GrandChild rule for element E
**Then** Parent rule executes exactly once for E
**And** Child rule executes exactly once for E
**And** GrandChild rule executes exactly once for E
**And** all rules share the same pre-created target

#### Scenario: Diamond inheritance handles correctly

**Given** rule "D" extends both "B" and "C"
**And** rules "B" and "C" both extend "A"
**When** rule D executes for element E
**Then** rule A executes exactly once
**And** all paths through the diamond share the same target from A

### Requirement: XMI ID Lookup for All Rule Types

The `equivalent()` method MUST check XMI ID lookup for ALL rule types (eager and lazy), not just lazy rules.

#### Scenario: equivalent() finds target created by eager rule

**Given** a transformation with an EAGER rule for type A
**And** an EAGER rule for type B that calls `equivalent(sourceA, TypeA.class)`
**And** NO lazy rule exists for type A
**When** `equivalent()` is called during parallel execution
**Then** XMI ID lookup is performed for all matching rules
**And** the target created by the eager rule is found via XMI ID
**And** `equivalent()` returns the correct target (not null)

#### Scenario: Cross-element eager rule dependencies resolve correctly

**Given** parallel transformation with multiple eager rules
**And** Rule A transforms EOperation and calls `equivalent(returnType, EClassifier.class)`
**And** Rule B transforms EDataType (only eager rule, no lazy rule)
**When** Thread 1 (Rule A) calls `equivalent()` before Thread 2 (Rule B) completes
**Then** Thread 1 waits for or finds Thread 2's result via XMI ID
**And** the operation's eType is correctly set (not null)

### Requirement: On-Demand Rule Execution for Cache Misses

When `equivalent()` cannot find a cached or XMI ID target, it MUST execute the matching rule on-demand with proper locking.

#### Scenario: Eager rule executed on-demand via equivalent()

**Given** `equivalent()` is called for a source with only eager rules
**And** cache lookup returns miss
**And** XMI ID lookup returns miss (target not yet created)
**When** `equivalent()` evaluates matching rules
**Then** the eager rule is executed on-demand
**And** proper per-element locking prevents duplicate execution
**And** the created target is cached and returned

### Requirement: executeParentRule XMI ID Lookup

The `executeParentRule()` method MUST check XMI ID for targets created by the parent rule.

#### Scenario: executeParentRule finds existing target via XMI ID

**Given** a transformation with rule inheritance (@Extends)
**And** the parent rule has already executed and created a target
**And** the target is in the XMI ID index but not in resolution cache
**When** a child rule calls `executeParentRule(parentName, source)`
**Then** XMI ID lookup is performed for the parent rule
**And** the existing target is found and cached
**And** the parent rule is NOT re-executed

### Requirement: Regression Tests for Rule Type Coverage

Comprehensive regression tests SHALL exist for all `equivalent()` variants and `executeParentRule()`.

#### Scenario: Separate eager rules test coverage

**Given** a test with Type A transformed by eager Rule A
**And** Type B transformed ONLY by eager Rule B (no lazy rule)
**And** Rule A calls `equivalent(sourceB, TypeB.class)`
**When** tests execute with structured IDs enabled
**Then** `equivalent()` returns non-null (not regression)
**And** tests verify this across parallel and sequential modes
**And** tests are repeated to catch intermittent race conditions

#### Scenario: equivalent(source, ruleName) test coverage

**Given** tests for `equivalent(source, ruleName)` method
**When** cross-element dependencies exist between eager rules
**Then** tests verify correct target resolution
**And** tests verify caching behavior
**And** tests verify XMI ID lookup

#### Scenario: equivalentDiscriminated test coverage

**Given** tests for `equivalentDiscriminated()` method
**When** discriminated targets are created by eager rules
**Then** tests verify correct target resolution via XMI ID
**And** tests verify discriminator path handling

#### Scenario: executeParentRule test coverage

**Given** tests for `executeParentRule()` method
**When** parent rules are eager rules without lazy fallback
**Then** tests verify XMI ID lookup finds parent target
**And** tests verify no duplicate parent execution
**And** tests verify inheritance chain integrity

## Thread-Safety Contracts

### Contract: Transformation Rule Thread-Safety

Transformation rules MUST follow these thread-safety guidelines:

1. **SAFE**: Create new elements via `ctx.createTarget()`
2. **SAFE**: Set properties on elements created by the current rule
3. **SAFE**: Add children to containment references of own elements
4. **SAFE**: Reference elements obtained via `ctx.equivalent()`
5. **UNSAFE**: Modify source model elements
6. **UNSAFE**: Modify elements created by other rules
7. **UNSAFE**: Use shared mutable state without synchronization

#### Scenario: Rule follows thread-safety contract

**Given** a transformation rule that only modifies elements it creates  
**And** references are obtained via context methods  
**When** the rule executes in parallel  
**Then** no thread-safety violations occur  

#### Scenario: Rule violates thread-safety contract

**Given** a transformation rule that modifies shared state  
**When** the rule executes in parallel  
**Then** the behavior is undefined  
**And** data corruption may occur  
**And** this is documented as user error  

---

## Configuration

### Configuration: Parallel Threshold

| Setting | Default | Description |
|---------|---------|-------------|
| `parallelThreshold` | 1000 | Minimum elements for parallel execution |
| `chunkSize` | 100 | Elements per worker thread chunk |

#### Scenario: Default parallel threshold

**Given** a TransformationExecutor with default configuration  
**And** a source model with 1500 elements  
**When** transformation is executed  
**Then** parallel execution is used  

#### Scenario: Custom parallel threshold via builder

**Given** a TransformationExecutor built with `.parallelThreshold(500)`  
**And** a source model with 600 elements  
**When** transformation is executed  
**Then** parallel execution is used  

#### Scenario: Threshold configured via builder

**Given** a TransformationExecutor built with:
```java
TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallelThreshold(2000)
    .chunkSize(50)
    .build();
```
**When** a model with 2500 elements is transformed  
**Then** parallel execution is used with chunk size of 50  

---

## Compatibility

### Backward Compatibility

#### Scenario: Sequential transformation unchanged

**Given** parallel execution is disabled  
**When** transformation is executed  
**Then** behavior is identical to the previous implementation  
**And** all existing tests pass  

#### Scenario: API unchanged

**Given** existing code using TransformationExecutor  
**When** upgraded to the new version  
**Then** no code changes are required  
**And** compilation succeeds  
**And** runtime behavior is compatible  
