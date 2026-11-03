## ADDED Requirements

### Requirement: Idempotent Rule Caching (Correct Behavior)

The Zeta Transformation Framework SHALL implement idempotent rule caching where the same (source, ruleName) pair always returns the same target instance. This is the correct behavior that ensures deterministic, thread-safe transformations.

**Note**: ETL's compound XMI ID generation for `@lazy @greedy` rules from different calling contexts is a **bug** that violates idempotency. Zeta intentionally does NOT replicate this buggy behavior.

#### Scenario: Same source returns same target regardless of calling context

**Given** a rule "CreateOperationBody" for source type Operation
**And** Rule A invokes `ctx.equivalent(operation, "CreateOperationBody")` from context X
**And** Rule B invokes `ctx.equivalent(operation, "CreateOperationBody")` from context Y
**When** both rules execute for the same Operation instance
**Then** both rules receive the SAME cached target instance
**And** the target has a simple XMI ID like `(esm/_abc123)/CreateOperationBody`
**And** NO compound ID is generated
**Because** idempotent caching ensures deterministic results

#### Scenario: Cache key ensures idempotency

**Given** a transformation with multiple rules calling `equivalent()` for the same (source, ruleName) pair
**When** the cache lookup is performed
**Then** the cache key is computed from (source identity, ruleName) only
**And** the calling rule's context is NOT part of the cache key
**And** all callers receive the same cached target
**Because** context-independent caching is required for idempotency

#### Scenario: ETL compound ID bug is not replicated

**Given** ETL would generate compound ID format `((A)/RuleName)_((B)/CallerRule)` (buggy behavior)
**When** the same scenario executes in Zeta
**Then** Zeta generates simple ID format `(A)/RuleName`
**And** the caller context `B` is NOT included in the ID
**Because** ETL's compound ID generation violates idempotency and is a known bug

#### Scenario: Expected XMI ID differences from ETL

**Given** a transformation that triggers compound ID generation in ETL
**When** the same transformation runs in Zeta
**Then** XMI ID differences occur due to ETL bugs:
  - 2 OperationBody IDs (ETL compound bug, Zeta simple correct)
  - 8 random fault UUIDs (ETL uses non-deterministic EcoreUtil.generateUUID())
  - 8 compound fault parameter IDs (related to random UUIDs)
  - 2 additional compound IDs (other ETL context-dependent bugs)
**And** these differences represent Zeta's CORRECT behavior vs ETL's BUGGY behavior
**And** no attempt is made to replicate ETL's bugs

---

### Requirement: Context-Independent Rule Caching

The rule execution cache SHALL use only the source element identity and rule name as cache key. The calling context (which rule invoked the lookup) SHALL NOT affect caching behavior.

**Rationale**: This design ensures:
1. **Idempotency**: Same input always produces same output
2. **Determinism**: Results are consistent across runs
3. **Thread-safety**: Parallel execution produces identical results to sequential
4. **Predictability**: No hidden context-dependent behavior

#### Scenario: Multiple callers get same cached result

**Given** Rule A, Rule B, and Rule C all call `ctx.equivalent(source, "TargetRule")`
**And** all three rules use the same source element
**When** TargetRule executes for the first caller (e.g., Rule A)
**Then** a target is created and cached
**And** Rule B receives the same cached target (not a new instance)
**And** Rule C receives the same cached target (not a new instance)
**And** TargetRule executes exactly once, not three times

#### Scenario: Idempotent transformation guarantees

**Given** the design goal of idempotent transformations
**When** the same (source, ruleName) pair is requested multiple times
**Then** the same target is returned every time
**And** this behavior is consistent regardless of parallel or sequential execution
**And** this is the CORRECT behavior (ETL's context-dependent caching is a bug)

---

## Cross-Reference

- **parallel-transformation spec**: Thread-safety requirements that depend on idempotent caching
- **greedy-target-lookup spec**: Target lookup behavior for greedy rules
- **rule-execution spec**: Core rule execution semantics
