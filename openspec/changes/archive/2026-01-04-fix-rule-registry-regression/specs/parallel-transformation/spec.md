# Parallel Transformation Specification Delta

## ADDED Requirements

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
