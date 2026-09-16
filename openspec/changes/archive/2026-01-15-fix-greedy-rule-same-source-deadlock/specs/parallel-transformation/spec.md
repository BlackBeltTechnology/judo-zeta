# Spec Delta: Parallel Transformation - Source-Level Locking

**Capability**: parallel-transformation
**Change**: fix-greedy-rule-same-source-deadlock

## ADDED Requirements

### Requirement: Source-Level Locking for Eager Rule Execution

When executing eager (non-lazy) rules for a source element in parallel mode, all rules for that source element MUST execute under a single source-level lock to prevent deadlocks.

#### Scenario: Same-source greedy rules execute sequentially

**Given** parallel transformation is in progress
**And** source element X has multiple matching eager rules (Rule A, Rule B, Rule C)
**And** Rule B calls `ctx.equivalent(X, TypeA.class)` to look up Rule A's target
**When** the executor processes source X
**Then** all rules for source X execute under the same source lock
**And** Rule A completes before Rule B begins (or vice versa based on order)
**And** when Rule B calls `equivalent(X, TypeA)`, the target is found in cache
**And** no deadlock occurs

#### Scenario: Different sources execute in parallel

**Given** parallel transformation is in progress
**And** source elements X and Y each have multiple matching eager rules
**When** the executor processes sources X and Y
**Then** rules for X execute under lock(X)
**And** rules for Y execute under lock(Y)
**And** X and Y processing can occur in parallel (different threads)
**And** source-level locking does not serialize unrelated sources

#### Scenario: ReentrantLock allows same-thread nested access

**Given** Thread T holds the source lock for element X
**And** Rule A is executing for source X
**When** Rule A calls `ctx.equivalent(X, TypeB.class)`
**And** TypeB requires executing Rule B (not yet cached)
**Then** the same thread can acquire the source lock again (reentrant)
**And** Rule B executes within the same thread
**And** Rule B's result is cached
**And** Rule A receives the result and continues

#### Scenario: Cross-source equivalent calls wait correctly

**Given** Thread T1 holds source lock for X and executes Rule A(X)
**And** Thread T2 holds source lock for Y and executes Rule B(Y)
**When** Rule A(X) calls `ctx.equivalent(Y, TypeC.class)`
**Then** Thread T1 waits for Thread T2 to release lock(Y)
**And** once Y's rules complete, Thread T1 can access Y's cached results
**And** this is not a deadlock (unidirectional wait)

#### Scenario: Circular cross-source dependencies detected

**Given** Rule A for source X calls `ctx.equivalent(Y, TypeB.class)`
**And** Rule B for source Y calls `ctx.equivalent(X, TypeA.class)`
**When** Thread T1 (processing X) and Thread T2 (processing Y) execute concurrently
**And** T1 holds lock(X) and waits for lock(Y)
**And** T2 holds lock(Y) and waits for lock(X)
**Then** this is a circular dependency deadlock
**And** it is detected via 30-second lock timeout
**And** a descriptive error is thrown indicating circular dependency

---

### Requirement: Source Lock Timeout

Source lock acquisition MUST use a timeout to detect potential deadlocks from circular cross-source dependencies.

#### Scenario: Lock acquisition times out after 30 seconds

**Given** Thread T attempts to acquire source lock for element X
**And** another thread holds the lock and does not release within 30 seconds
**When** the timeout expires
**Then** a RuntimeException is thrown
**And** the message indicates potential circular dependency
**And** the message includes the source element type for debugging

#### Scenario: Normal lock acquisition succeeds

**Given** source lock for element X is available
**When** a thread attempts to acquire the lock
**Then** acquisition succeeds immediately
**And** no timeout occurs

---

### Requirement: Source Lock Cleanup

Source locks MUST be cleaned up between transformations to prevent memory leaks.

#### Scenario: Source locks cleared on executor reset

**Given** a TransformationExecutor has processed a transformation
**And** source locks exist for elements X, Y, Z
**When** a new transformation starts
**Then** all source locks from the previous transformation are cleared
**And** new source locks are created as needed for the new transformation

#### Scenario: No memory leak for long-lived executors

**Given** a TransformationExecutor is reused for multiple transformations
**When** each transformation completes
**Then** source locks from completed transformations are cleared
**And** memory usage does not grow unbounded
