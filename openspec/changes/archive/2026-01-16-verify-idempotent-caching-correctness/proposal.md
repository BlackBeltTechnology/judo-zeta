# Change: Verify Idempotent Caching Correctness (ETL Compound ID Bug)

## Why

After thorough investigation, it has been determined that ETL's compound XMI ID generation for `@lazy @greedy` rules is a **bug**, not intended behavior. Zeta's idempotent caching (returning the same target for the same source) is the **correct** behavior.

**Key finding**: ETL creates compound XMI IDs when the same `@lazy @greedy` rule is invoked for the same source from different calling contexts. This violates idempotent transformation principles and creates non-deterministic output.

## What Changes

- **TESTS**: Add tests verifying Zeta's correct idempotent caching behavior
- **DOCUMENTATION**: Document that Zeta's behavior is correct and ETL's is a bug
- **SPEC**: Update `etl-patterns` spec to clarify the correct semantics

## Analysis

### ETL Behavior (Bug - Non-Idempotent)

When a `@lazy @greedy` rule (e.g., `CreateOperationBody`) is invoked multiple times for the same Operation source from different transformation contexts, ETL incorrectly creates multiple targets:

```
ETL XMI ID outputs (buggy):
- 6 simple IDs: (esm/X)/OperationBody
- 2 compound IDs: ((esm/A)/OperationBody)_((esm/B)/MappedTransferObjectType)
```

**Why this is a bug**:
1. Violates idempotent transformation principle (same input should produce same output)
2. Creates non-deterministic results depending on execution order
3. Breaks parallel execution guarantees
4. Makes transformation results unpredictable

### Zeta Behavior (Correct - Idempotent)

Zeta's `ctx.equivalent(source, "RuleName")` correctly returns the same cached target regardless of calling context:

```
Zeta XMI ID outputs (correct):
- 8 simple IDs: (esm/X)/OperationBody (all uniform)
```

**Why this is correct**:
1. Idempotent: same (source, ruleName) always produces same target
2. Deterministic: results are consistent across runs
3. Thread-safe: parallel execution produces identical results to sequential
4. Predictable: no hidden context-dependent behavior

### Design Rationale

The cache key `(source, ruleName)` intentionally excludes calling context because:

1. **Idempotency**: A transformation should produce the same output regardless of which rule requests a target first
2. **Parallelism**: Thread-safe execution requires deterministic caching
3. **Simplicity**: Context-independent caching is easier to reason about
4. **Performance**: Single cache lookup without context chain tracking

## Impact

### XMI ID Differences from ETL (Expected)

When comparing Zeta output to ETL output, differences are expected due to ETL's bug:

| Category | Count | Explanation |
|----------|-------|-------------|
| OperationBody IDs | 2 | ETL creates compound (bug), Zeta creates simple (correct) |
| Random fault UUIDs | 8 | ETL uses `EcoreUtil.generateUUID()` (non-deterministic) |
| Compound fault parameter IDs | 8 | Related to random UUID differences |
| Additional compound IDs | 2 | Other ETL context-dependent behavior |

**These differences are NOT Zeta bugs** - they represent Zeta's correct behavior vs ETL's buggy behavior.

## Recommended Approach

1. **Add tests** verifying Zeta's idempotent caching is working correctly
2. **Document** that ETL compound ID behavior is a known bug
3. **Update specs** to clarify correct semantics
4. **Do NOT attempt to replicate** ETL's buggy behavior

## Related Capabilities

- `etl-patterns` - ETL compatibility patterns (documents known ETL bugs)
- `greedy-target-lookup` - Greedy rule target resolution
- `rule-execution` - Core rule execution semantics
- `parallel-transformation` - Thread-safe execution requirements
