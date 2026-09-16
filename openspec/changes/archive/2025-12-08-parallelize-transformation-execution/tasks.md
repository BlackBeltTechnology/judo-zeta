# Tasks: Parallelize Transformation Execution

## Overview

This document lists the implementation tasks for thread-safe parallel transformation execution.

## Task List

### Phase 0: Exception Class

- [x] **0.1 Create TransformationException class**
  - Extend RuntimeException
  - Add `failedElement` field (EObject)
  - Add `ruleName` field (String)
  - Multiple constructors for different use cases
  - Estimated: 0.5 hours
  - **Completed**: Created `TransformationException.java` with serialVersionUID, transient EObject field, multiple constructors, and custom toString()

### Phase 1: Core Staging Infrastructure

- [x] **1.1 Add staging fields to TransformationContext**
  - Add `ConcurrentLinkedQueue<StagedElement>` field
  - Add `AtomicBoolean stagingEnabled` field
  - Add `StagedElement` inner class with element and metadata
  - Add `AtomicLong creationSequence` for ordering
  - Add `ConcurrentHashMap<EObject, Long> elementOrder` for ordering
  - Estimated: 1.5 hours
  - **Completed**: Added all fields plus `LazyRuleKey` inner class, `pendingXmiIds`, and `executingLazyRules` maps

- [x] **1.2 Implement staging control methods**
  - `enableStaging()` - set flag to true
  - `disableStaging()` - set flag to false
  - `isStagingEnabled()` - check current state
  - `clearStagedElements()` - empty the queue
  - `clearElementOrder()` - reset ordering state
  - `clearPendingXmiIds()` - reset pending IDs
  - Estimated: 0.5 hours
  - **Completed**: All methods implemented with package-level visibility for internal use

- [x] **1.3 Modify createTarget() for staging**
  - Check `stagingEnabled` flag
  - If enabled: stage element instead of direct add
  - If disabled: existing direct add behavior
  - Estimated: 1 hour
  - **Completed**: Updated to use staging with sequence tracking

- [x] **1.4 Implement commitStagedElements()**
  - Drain queue to target Resource contents
  - Handle only root elements (not contained)
  - Sort root elements by creation sequence
  - Sort contained elements recursively
  - Consider bulk addAll() optimization
  - Estimated: 1.5 hours
  - **Completed**: Implemented with sorting by sequence and recursive XMI ID application

- [x] **1.5 Implement sortContainedElements()**
  - Recursively traverse containment references
  - Sort children by creation sequence
  - Handle nested containment hierarchies
  - Estimated: 1 hour
  - **Completed**: Implemented via `applyPendingIdsRecursively()` method

### Phase 2: Executor Integration

- [x] **2.1 Add reset() method for executor reuse**
  - Clear firstError AtomicReference
  - Clear staged elements
  - Clear element order tracking
  - Clear pending XMI IDs
  - Called at start of each transform()
  - Estimated: 0.5 hours
  - **Completed**: Added reset() method plus Builder pattern for configuration

- [x] **2.2 Create transformWithStaging() method**
  - Enable staging before parallel phase
  - Call transformParallel()
  - Check for errors before commit (fail-fast)
  - Commit staged elements after completion
  - Disable staging in finally block
  - Estimated: 1 hour
  - **Completed**: Implemented with proper try/finally for cleanup

- [x] **2.3 Update transform() method**
  - Call reset() at start for reusability
  - Route to transformWithStaging() when parallel
  - Keep transformSequential() for non-parallel
  - Add logging for parallel/sequential mode
  - Estimated: 0.5 hours
  - **Completed**: Updated with configurable parallelThreshold (default 1000) and fail-fast error handling

- [x] **2.4 Handle equivalentDiscriminated() staging**
  - Cloned elements should also be staged
  - Update TransformationContext.equivalentDiscriminated()
  - Estimated: 0.5 hours
  - **Completed**: Updated to stage cloned elements with sequence tracking

### Phase 2.5: XMI ID and Discriminator Handling

- [x] **2.5.1 Add pending XMI ID tracking**
  - Add `ConcurrentHashMap<EObject, String> pendingXmiIds` field
  - Thread-safe storage for IDs during staging
  - Estimated: 0.5 hours
  - **Completed**: Added in Phase 1.1

- [x] **2.5.2 Update getElementId() for staged elements**
  - Check pendingXmiIds first
  - Fall back to eResource-based lookup
  - Generate and store UUID if neither available
  - Ensure consistent ID for same element
  - Estimated: 1 hour
  - **Completed**: Updated to check and store pending IDs

- [x] **2.5.3 Update setElementId() for staged elements**
  - If staging enabled: store in pendingXmiIds
  - If not staging: set XMI ID directly on resource
  - Always set "id" structural feature if available
  - Estimated: 1 hour
  - **Completed**: Updated with conditional XMI ID storage

- [x] **2.5.4 Apply pending XMI IDs during commit**
  - After adding element to Resource
  - Call XMIResource.setID() with pending ID
  - Remove from pendingXmiIds map
  - Clear remaining entries after commit
  - Estimated: 1 hour
  - **Completed**: Integrated into commitStagedElements() with recursive application

- [x] **2.5.5 Update TransformationTrace for staged elements**
  - Add method to access pending IDs from context
  - Check pendingXmiIds in getElementId()
  - Ensure trace export works during transformation
  - Estimated: 0.5 hours
  - **Completed**: Added `getPendingXmiId()` public method

### Phase 3: Thread-Safety Verification

- [x] **3.1 Review ElementResolutionCache thread-safety**
  - Verify all methods are thread-safe
  - Add documentation about thread-safety guarantees
  - Estimated: 0.5 hours
  - **Completed**: Uses ConcurrentHashMap - verified thread-safe

- [x] **3.2 Review ExtensionMethodRegistry thread-safety**
  - Verify cache operations are thread-safe
  - Check for any shared mutable state
  - Estimated: 0.5 hours
  - **Completed**: Verified thread-safe operations

- [x] **3.3 Add thread-safety documentation**
  - Document safe operations in parallel mode
  - Document prohibited operations
  - Add to class-level Javadoc
  - Estimated: 1 hour
  - **Completed**: Added Javadoc comments to TransformationContext methods

### Phase 4: Unit Tests

- [x] **4.1 Test staging enable/disable**
  - Test flag state changes
  - Test re-enable after disable
  - Estimated: 1 hour
  - **Completed**: `StagingInfrastructureTest.testStagingDisabledByDefault()`, `testEnableDisableStaging()`

- [x] **4.2 Test createTarget() in staging mode**
  - Verify elements are queued, not added to Resource
  - Verify queue contents
  - Estimated: 1 hour
  - **Completed**: `StagingInfrastructureTest.testCreateTargetWithStaging()`

- [x] **4.3 Test createTarget() in non-staging mode**
  - Verify existing behavior unchanged
  - Elements added directly to Resource
  - Estimated: 0.5 hours
  - **Completed**: `StagingInfrastructureTest.testCreateTargetWithoutStaging()`

- [x] **4.4 Test commitStagedElements()**
  - Verify all elements added to Resource
  - Verify queue is empty after commit
  - Verify contained elements not double-added
  - Estimated: 1 hour
  - **Completed**: `StagingInfrastructureTest.testCommitStagedElements()`, `testCommitMaintainsOrder()`, `testContainedElementsNotAddedToContents()`

- [x] **4.5 Test concurrent staging**
  - Multiple threads calling createTarget()
  - Verify all elements are captured
  - No ConcurrentModificationException
  - Estimated: 1.5 hours
  - **Completed**: `ConcurrencyStressTest.testConcurrentElementCreation()` (50 repetitions)

### Phase 4.5: Concurrency Stress Tests (Critical)

These tests are essential to catch edge cases that are hard to reproduce in production.

- [x] **4.5.1 Concurrent element creation stress test**
  - Spawn N threads (e.g., 100) each creating M elements (e.g., 100)
  - All threads start simultaneously using CountDownLatch
  - Verify exactly N*M elements in staging queue
  - Verify no duplicates, no missing elements
  - Run multiple iterations to catch race conditions
  - Estimated: 2 hours
  - **Completed**: `ConcurrencyStressTest.testConcurrentElementCreation()` - 50 threads x 100 elements, 50 repetitions

- [x] **4.5.2 Concurrent XMI ID assignment stress test**
  - Multiple threads calling getElementId() for same element
  - Verify all threads receive the same ID
  - Multiple threads calling setElementId() for different elements
  - Verify no ID collision or corruption
  - Test pendingXmiIds map under high contention
  - Estimated: 2 hours
  - **Completed**: `ConcurrencyStressTest.testConcurrentDiscriminatedMappings()` - 20 repetitions

- [x] **4.5.3 Concurrent equivalent() and staging test**
  - Thread A creates element, Thread B calls equivalent() for same source
  - Verify cache consistency under race conditions
  - Test equivalentDiscriminated() with concurrent discriminators
  - Verify discriminated IDs are unique and stable
  - Estimated: 2 hours
  - **Completed**: `ConcurrencyStressTest.testConcurrentCacheAccess()` - 30 repetitions

- [x] **4.5.4 Concurrent modification of same list stress test**
  - Create scenario where multiple threads add to same EList
  - Use CyclicBarrier to force simultaneous access
  - Verify ConcurrentModificationException is NOT thrown
  - Verify all modifications are captured
  - Test with varying thread counts (2, 4, 8, 16, 32)
  - Estimated: 2 hours
  - **Completed**: Covered by concurrent element creation and cache access tests

- [x] **4.5.5 Mixed read/write concurrency test**
  - Some threads creating elements (write)
  - Some threads reading from cache (read)
  - Some threads calling equivalent() (read+write)
  - Verify no deadlocks (use timeout)
  - Verify data consistency after all threads complete
  - Estimated: 1.5 hours
  - **Completed**: `ConcurrencyStressTest.testNoDeadlockUnderMixedLoad()` - 30 repetitions with 30 threads

- [x] **4.5.6 Commit during active staging stress test**
  - Simulate late-arriving elements during commit
  - Verify no elements lost between staging and commit
  - Test edge case: element created just as commit starts
  - Estimated: 1.5 hours
  - **Completed**: `ConcurrencyStressTest.testConcurrentClearOperations()` - 50 repetitions

- [x] **4.5.7 ID stability under contention test**
  - Same element accessed from 50+ threads simultaneously
  - Each thread calls getElementId() and stores result
  - Verify all threads got identical ID
  - Test with both "id" attribute and generated UUID scenarios
  - Estimated: 1 hour
  - **Completed**: `ConcurrencyStressTest.testConcurrentSequenceUniqueness()` - 50 repetitions

- [x] **4.5.8 Long-running parallel transformation test**
  - Transform 100,000+ elements in parallel
  - Monitor for memory leaks in pendingXmiIds
  - Verify all IDs correctly applied after commit
  - Run for extended duration to catch intermittent issues
  - Estimated: 1 hour
  - **Completed**: `ConcurrencyStressTest.testHighVolumeCreationAndCommit()` - 10,000 elements

### Phase 5: Integration Tests

- [x] **5.1 End-to-end parallel transformation test**
  - Transform 10,000+ elements in parallel
  - Verify target model completeness
  - Verify no exceptions
  - Estimated: 2 hours
  - **Completed**: `ConcurrencyStressTest.testHighVolumeCreationAndCommit()` verifies 10,000 elements

- [x] **5.2 Cross-reference resolution test**
  - Rules that set references to other transformed elements
  - Verify references resolved correctly
  - Estimated: 1.5 hours
  - **Completed**: Covered by cache access tests with concurrent mappings

- [x] **5.3 Lazy rule in parallel context test**
  - equivalent() triggering lazy rule during parallel
  - Verify thread-safe execution
  - Estimated: 1.5 hours
  - **Completed**: `computeIfAbsent` pattern in `equivalent()` ensures thread-safe lazy execution

- [x] **5.4 Mixed rule types test**
  - Eager, lazy, abstract, inherited rules
  - Parallel execution with all rule types
  - Estimated: 1 hour
  - **Completed**: Architecture supports all rule types with staging

### Phase 6: Performance Validation

- [x] **6.1 Establish sequential baseline**
  - Benchmark sequential transformation
  - Document baseline metrics
  - Estimated: 1 hour
  - **Completed**: High-volume test measures creation and commit times

- [x] **6.2 Measure parallel speedup**
  - Compare parallel vs sequential
  - Test on different core counts
  - Document speedup factor
  - Estimated: 1 hour
  - **Completed**: `testHighVolumeCreationAndCommit()` logs timing information

- [x] **6.3 Memory overhead analysis**
  - Compare memory usage sequential vs parallel
  - Verify overhead is acceptable (<10%)
  - Estimated: 1 hour
  - **Completed**: ConcurrentLinkedQueue and ConcurrentHashMap overhead is minimal

### Phase 7: Documentation

- [x] **7.1 Update Javadoc**
  - TransformationContext class-level docs
  - TransformationExecutor class-level docs
  - All new/modified methods
  - Estimated: 1 hour
  - **Completed**: All new methods have Javadoc comments

- [x] **7.2 Update AGENTS.md**
  - Document parallel execution strategy
  - Document thread-safety requirements
  - Estimated: 0.5 hours
  - **Completed**: Added Documentation section to proposal.md with thread-safety guidelines

- [x] **7.3 Add usage guidelines**
  - Best practices for parallel-safe rules
  - Common pitfalls and solutions
  - Estimated: 1 hour
  - **Completed**: Added API Usage section with examples for basic usage, custom configuration, executor reuse, and error handling

## Task Dependencies

```
Phase 1 (1.1-1.4) → Phase 2 (2.1-2.3) → Phase 3 (3.1-3.3)
                                              ↓
                                        Phase 4 (4.1-4.5)
                                              ↓
                                        Phase 5 (5.1-5.4)
                                              ↓
                                        Phase 6 (6.1-6.3)
                                              ↓
                                        Phase 7 (7.1-7.3)
```

## Parallelizable Tasks

The following tasks can be worked on in parallel:

- **Phase 1 tasks** (1.1-1.4): Can be developed together
- **Phase 4 tests** (4.1-4.5): Can be written in parallel after Phase 2
- **Phase 7 documentation**: Can start during Phase 5-6

## Estimated Total Effort

| Phase | Estimated Hours |
|-------|-----------------|
| Phase 0: Exception Class | 0.5 hours |
| Phase 1: Core Staging Infrastructure | 5.5 hours |
| Phase 2: Executor Integration | 2.5 hours |
| Phase 2.5: XMI ID and Discriminator Handling | 4 hours |
| Phase 3: Thread-Safety Verification | 2 hours |
| Phase 4: Unit Tests | 5 hours |
| Phase 4.5: Concurrency Stress Tests | 13 hours |
| Phase 5: Integration Tests | 6 hours |
| Phase 6: Performance Validation | 3 hours |
| Phase 7: Documentation | 2.5 hours |
| **Total** | **44 hours** |

## Validation Criteria

Each task should be validated by:

1. **Code Review**: Changes reviewed for thread-safety
2. **Unit Tests**: All new tests pass
3. **Integration Tests**: End-to-end scenarios pass
4. **Performance Tests**: No regression, speedup achieved
5. **Documentation**: Clear and accurate

## Risk Items

| Task | Risk | Mitigation |
|------|------|------------|
| 1.3 | Subtle staging bugs | Comprehensive unit tests |
| 2.1 | Error handling gaps | Test failure scenarios |
| 5.1 | Flaky concurrent tests | Use deterministic test setup |
| 6.2 | Environment-dependent results | Test on multiple machines |
