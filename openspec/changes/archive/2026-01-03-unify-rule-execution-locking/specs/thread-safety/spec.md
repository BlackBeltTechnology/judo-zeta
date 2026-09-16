# Thread Safety Specification Delta

## ADDED Requirements

### Requirement: Rule execution MUST use unified locking across all API methods

All methods that trigger rule execution (`equivalent()`, `executeParentRule()`, etc.) SHALL use the same locking mechanism to prevent race conditions when the same (source, ruleName) pair is accessed concurrently through different API methods.

#### Scenario: Mixed equivalent() and executeParentRule() calls are synchronized

**Given** a transformation running in parallel mode
**And** Thread A calls `equivalent(source, TargetType.class)` for a rule named "RuleX"
**And** Thread B calls `executeParentRule("RuleX", source)` for the same source
**When** both calls happen concurrently
**Then** only ONE thread executes the rule
**And** the other thread waits and receives the cached result
**Because** both methods use the same `ruleLocks` mechanism

#### Scenario: No duplicate rule executions for same source and rule

**Given** a transformation with multiple threads
**And** the same (source, ruleName) pair is accessed through any combination of API methods
**When** the first thread starts executing the rule
**Then** subsequent threads MUST wait for completion
**And** all threads receive the same result object

#### Scenario: Race condition test detects duplicate execution before fix

**Given** a test class `DualLockingRaceConditionTest`
**And** Thread A calls `equivalent(source, TargetType.class)` for rule "TestRule"
**And** Thread B calls `executeParentRule("TestRule", source)` concurrently
**And** an AtomicInteger counter tracks rule executions per (source, ruleName)
**When** the test runs before the fix is applied
**Then** the counter value SHOULD be greater than 1 (duplicate execution)
**Because** the dual locking mechanism allows both threads to execute simultaneously
