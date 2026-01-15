# Design: Fix Greedy Rule Same-Source Deadlock

## Architecture Decision

### Decision: Source-Level Locking for Eager Rule Execution

When executing eager (non-lazy) rules for a source element, all rules for that source element execute under a **single source-level lock**. This prevents the deadlock that occurs when multiple greedy rules process the same source concurrently and one calls `equivalent()` on the other's target.

## Current Behavior Analysis

### Lock Hierarchy (Current)

```
TransformationExecutor.executeEagerRulesFor(source)
    │
    └── for each matching rule:
            │
            └── TransformationContext.equivalent(source, targetType, eagerExecution=true)
                    │
                    └── Lock: (source, ruleName)  ← PER-RULE LOCK
                            │
                            └── Execute rule
                                    │
                                    └── Rule body may call ctx.equivalent(source, OtherType)
                                            │
                                            └── Lock: (source, otherRuleName)  ← DIFFERENT LOCK
                                                    │
                                                    └── DEADLOCK if both locks held by different threads
```

### The Deadlock Scenario

```
Source X is processed by Thread 1 and Thread 2 simultaneously:

Thread 1: CreateAnnotation(X)          Thread 2: CreateMain(X)
────────────────────────────           ────────────────────────
Lock(X, "CreateAnnotation") ✓          Lock(X, "CreateMain") ✓
    │                                       │
    ▼                                       ▼
Rule body executes                      Rule body executes
    │                                       │
    ▼                                       │
ctx.equivalent(X, MainType)                 │
    │                                       │
    ▼                                       │
Lock(X, "CreateMain") ← BLOCKED!            │
                                            │
═══════════════════════════════════════════════════════
Both threads hold one lock and wait for the other = DEADLOCK
```

## Proposed Solution

### Lock Hierarchy (Proposed)

```
TransformationExecutor.executeEagerRulesFor(source)
    │
    └── Lock: source  ← SOURCE-LEVEL LOCK (NEW)
            │
            └── for each matching rule:
                    │
                    └── Execute rule (no additional lock for eager execution)
                            │
                            └── Rule body may call ctx.equivalent(source, OtherType)
                                    │
                                    └── Check cache → return if exists (no lock needed)
                                    │
                                    └── If cache miss: wait for source lock (held by same thread)
```

### Key Insight: ReentrantLock Enables Same-Thread Access

Since `ReentrantLock` allows the same thread to acquire the lock multiple times:

1. Thread acquires source lock for eager rule processing
2. Same thread executes Rule A, which calls `equivalent(source, TypeB)`
3. `equivalent()` checks cache → cache miss (Rule B not yet executed)
4. `equivalent()` can acquire source lock (same thread) → execute Rule B
5. Rule B completes → cached → returns to Rule A

This eliminates cross-thread deadlock while maintaining correct semantics.

## Implementation Details

### Option 1: Lock in TransformationExecutor (Recommended)

Add source-level locking in `TransformationExecutor.executeEagerRulesFor()`:

```java
// In TransformationExecutor
private final ConcurrentHashMap<EObject, ReentrantLock> sourceLocks = new ConcurrentHashMap<>();

void executeEagerRulesFor(EObject source, List<TransformRuleDescriptor> rules) {
    ReentrantLock sourceLock = sourceLocks.computeIfAbsent(source, k -> new ReentrantLock());
    sourceLock.lock();
    try {
        for (TransformRuleDescriptor rule : rules) {
            // Execute each rule for this source sequentially
            context.equivalent(source, rule.getTargetType(), true /* eager */);
        }
    } finally {
        sourceLock.unlock();
    }
}
```

**Pros:**
- Clean separation: executor handles source-level coordination
- Context remains unaware of this coordination
- Easy to disable for debugging

**Cons:**
- Executor needs access to source lock map

### Option 2: Lock in TransformationContext

Modify `equivalent()` to detect eager rule execution and use source-level locking:

```java
// In TransformationContext.equivalent()
if (eagerExecution) {
    // Use source-level lock for eager rules
    ReentrantLock sourceLock = sourceLocks.computeIfAbsent(source, k -> new ReentrantLock());
    sourceLock.lock();
    try {
        return executeWithinSourceLock(source, targetType);
    } finally {
        sourceLock.unlock();
    }
} else {
    // Lazy rules: use per-(source, ruleName) lock as before
    return executeWithPerRuleLock(source, targetType);
}
```

**Pros:**
- All locking logic in one place
- Consistent behavior regardless of call path

**Cons:**
- More complex logic in `equivalent()`
- Need to track "eager execution" state

### Recommendation: Option 1

Option 1 is cleaner because:
1. Executor already orchestrates rule execution
2. Context doesn't need to know about eager vs lazy distinction for locking
3. Easier to test and reason about

## Impact on Parallelism

### Before (Current Behavior)

```
Source X: Rules A, B, C execute in parallel
          → Race condition / deadlock possible

Source Y: Rules A, B, C execute in parallel
          → Race condition / deadlock possible
```

### After (Proposed Behavior)

```
Source X: Rules A, B, C execute SEQUENTIALLY (under source lock)
          → No race condition / deadlock
          → Thread 1 holds lock for all X rules

Source Y: Rules A, B, C execute SEQUENTIALLY
          → No race condition / deadlock
          → Thread 2 holds lock for all Y rules

Sources X and Y execute IN PARALLEL (different threads)
```

### Performance Impact

For a model with N sources and M rules per source:

| Metric | Before | After | Impact |
|--------|--------|-------|--------|
| Maximum parallelism | N × M threads | N threads | Reduced by factor M |
| Typical M value | 3-5 rules | - | - |
| Practical impact | Often contention | Clean execution | Likely faster |

**Why it may be faster:** Eliminating lock contention and deadlock recovery overhead often outweighs the reduced parallelism.

## Edge Cases

### 1. Lazy Rules Calling equivalent()

Lazy rules are invoked via `equivalent()` from within eager rules. Since the source lock is held by the same thread (ReentrantLock), this works correctly.

### 2. Cross-Source Dependencies

If Rule A for source X calls `equivalent(Y, TypeB)`:
- Source Y may be processed by a different thread
- Thread X waits for Y's source lock
- This is correct behavior (not a deadlock, just waiting)

### 3. Circular Cross-Source Dependencies

If Rule A(X) calls equivalent(Y) AND Rule B(Y) calls equivalent(X):
- Thread 1 holds lock(X), waits for lock(Y)
- Thread 2 holds lock(Y), waits for lock(X)
- **DEADLOCK** - but this is a fundamental model issue, not a locking bug

This is detected by the 30-second timeout and reported as circular dependency.

## Testing Strategy

1. **Unit Test**: Verify source-level locking prevents concurrent same-source execution
2. **Stress Test**: Run 50+ rules on same source type with high parallelism
3. **Integration Test**: Run full PSM2ASM transformation without deadlock
4. **Performance Test**: Compare execution time before/after

## Migration

No API changes required. The fix is internal to the executor/context.

## Rollback Plan

If issues arise:
1. Add configuration flag to disable source-level locking
2. Fall back to per-rule locking with longer timeout
3. Run in sequential mode as last resort
