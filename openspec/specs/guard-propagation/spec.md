# guard-propagation Specification

## Purpose
TBD - created by archiving change add-guard-scope-control. Update Purpose after archive.
## Requirements
### Requirement: Per-Rule Guard Rejection Cache

Each transformation rule MUST maintain its own cache of rejected source elements.

#### Scenario: Guard rejection is cached per rule

**Given** a rule with a guard that returns `false` for element X
**When** the guard is first evaluated for element X
**Then** element X is added to the rule's rejected set
**And** subsequent `evaluateGuard(X)` calls return `false` without re-evaluating the guard

#### Scenario: Rejection cache is per-rule (isolated)

**Given** RuleA with guard that rejects element X
**And** RuleB with guard that accepts element X
**When** RuleA's guard is evaluated for X (rejected)
**And** RuleB's guard is evaluated for X
**Then** RuleA's rejected set contains X
**And** RuleB's rejected set does NOT contain X
**And** RuleB's guard returns `true`

#### Scenario: Rejection cache is thread-safe

**Given** parallel transformation with multiple threads
**And** multiple rules evaluating guards for different elements
**When** guards evaluate concurrently
**Then** all rejections are recorded correctly without data races
**And** no duplicate entries in rejected sets

---

### Requirement: Guard Evaluation Optimization

Guard evaluation MUST check the rejection cache before executing the guard logic.

#### Scenario: Cached rejection avoids guard execution

**Given** a rule with an expensive guard method
**And** element X was previously rejected by this rule
**When** `evaluateGuard(X)` is called again
**Then** the guard method is NOT invoked
**And** `false` is returned immediately

#### Scenario: First evaluation executes guard

**Given** a rule with a guard method
**And** element X was never evaluated by this rule
**When** `evaluateGuard(X)` is called
**Then** the guard method IS invoked
**And** result is cached if `false`

---

### Requirement: Rejection Cache Reset

The rejection cache MUST be cleared when the executor is reset for reuse.

#### Scenario: Executor reset clears all rejection caches

**Given** a transformation that recorded guard rejections
**When** the executor is reset for a new transformation
**Then** all rules' rejected sets are cleared
**And** the new transformation starts with empty rejection caches

#### Scenario: Multiple transformations with executor reuse

**Given** an executor that ran a transformation with rejections
**When** the executor is reset and runs a second transformation
**Then** the second transformation evaluates all guards fresh
**And** previous rejections do not affect the new transformation

---

