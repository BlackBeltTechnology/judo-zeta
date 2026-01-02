# parallel-transformation Specification Delta

## Purpose

Fixes thread-safety issues in parallel transformation execution by adding atomic cache operations with per-rule locking.

## MODIFIED Requirements

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

## Cross-References

- **guard-propagation**: Guard rejection caching uses similar per-rule isolation
- **etl-patterns**: Atomic get-or-create matches ETL's equivalent() semantics
