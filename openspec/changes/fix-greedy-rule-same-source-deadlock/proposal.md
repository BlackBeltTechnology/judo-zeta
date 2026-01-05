# Proposal: Fix Greedy Rule Same-Source Deadlock

**Change ID**: fix-greedy-rule-same-source-deadlock
**Status**: Draft
**Created**: 2026-01-05

## Problem Statement

When multiple `@Greedy` rules transform the same source element and one rule calls `ctx.equivalent(source, ...)` to look up a target created by another rule for the **same source**, a deadlock occurs in parallel execution.

### Error Message
```
Potential deadlock detected: timeout waiting for lock on
equivalent(TransferObjectRelation, CreateTransferObjectRelation)
```

### Root Cause

The current locking strategy uses `(source, ruleName)` as the lock key. When two @Greedy rules process the same source element:

1. **Rule A** (e.g., `CreateTransferObjectRelation`) creates main target → acquires `lock(X, "RuleA")`
2. **Rule B** (e.g., `CreateTransferObjectRelationPermissions`) creates annotation → acquires `lock(X, "RuleB")`
3. **Rule B** calls `ctx.equivalent(X, EReference.class)` → tries to acquire `lock(X, "RuleA")`
4. **DEADLOCK**: Rule B waits for Rule A's lock, but both are processing the same source concurrently

```
Thread 1                              Thread 2
────────                              ────────
CreatePermissions(X)                  CreateRelation(X)
    │                                     │
    ▼                                     ▼
Lock(X, "Permissions") ✓              Lock(X, "Relation") ✓
    │                                     │
    ▼                                     │
ctx.equivalent(X, EReference)             │
    │                                     │
    ▼                                     │
Lock(X, "Relation") BLOCKED               │
         ═══ DEADLOCK (30s timeout) ═══
```

### Scale of Impact

In judo-tatami PSM2ASM transformation, 50+ rules follow this pattern:

| Source Type | Main Target Rule | Annotation Rules (call equivalent) |
|-------------|------------------|-----------------------------------|
| TransferObjectRelation | CreateTransferObjectRelation | 4 rules |
| TransferAttribute | CreateTransferObjectAttribute | 7 rules |
| TransferObjectType | CreateMappedTransferObjectTypeClass | 6 rules |
| TransferOperation | CreateBoundTransferOperation | 10 rules |
| AbstractActorType | CreateAbstractActorType | 3 rules |
| DataProperty | CreateDerivedAttribute | 5 rules |

## Proposed Solution

**Option A: Source-Level Locking for Greedy Rules** (Recommended)

When processing a source element through eager/greedy rules, use the **source element** as the lock key (not source + ruleName). This serializes all greedy rules for the same source element, preventing the deadlock.

```
Before (Current):
  Lock key = (source, ruleName)
  → Different rules get different locks
  → Concurrent execution of same-source rules
  → Deadlock when one calls equivalent()

After (Proposed):
  Lock key = source (for greedy rule processing)
  → All rules for same source share one lock
  → Rules execute sequentially per source
  → equivalent() finds result in cache (no lock needed)
```

### Implementation Changes

1. **TransformationExecutor**: Add source-level locking when executing eager rules
2. **TransformationContext**: Keep existing per-(source, ruleName) locking for `equivalent()` calls
3. The source lock serializes greedy rules; by the time an annotation rule calls `equivalent()`, the main target is already created and cached

### Trade-offs

| Approach | Pros | Cons |
|----------|------|------|
| Source-level lock for greedy | Simple, prevents deadlock, matches ETL semantics | Slightly less parallelism (rules for same source are sequential) |
| Rule dependency analysis | Optimal parallelism | Complex, requires static analysis |
| Retry-based execution | No locking changes | Non-deterministic ordering, complex error handling |

### Why Source-Level Locking is Acceptable

1. **ETL Semantics**: In Epsilon ETL, rules for the same source element execute sequentially
2. **Minimal Impact**: Different source elements still execute in parallel
3. **Cache Benefits**: After main rule completes, annotation rules find targets in cache (fast path)

## Decisions

1. **Implementation Location**: Option 1 - Lock in `TransformationExecutor` (not Context)
2. **Existing Locks**: Keep both source-level lock AND existing per-rule locks (defense in depth)
3. **Parallelism**: Accept sequential execution for same-source rules (5 rules → sequential)
4. **Cross-Source Deadlock**: Timeout + error message is acceptable

## Affected Components

- `TransformationExecutor.executeEagerRulesFor()` - Add source-level lock
- Existing per-rule locks in `TransformationContext.equivalent()` remain unchanged

## Success Criteria

1. PSM2ASM transformation completes without deadlock
2. All existing tests pass
3. Element counts match sequential mode
4. Performance impact < 10% for typical transformations

## Related Specs

- `parallel-transformation` - Thread-safe parallel execution requirements
- `rule-execution` - Rule execution order requirements
