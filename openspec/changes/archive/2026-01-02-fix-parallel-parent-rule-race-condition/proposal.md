# Proposal: Fix Parallel Parent Rule Race Condition

**Change ID:** fix-parallel-parent-rule-race-condition
**Status:** Draft
**Created:** 2026-01-02

## Why

Despite the previous fix (`fix-eager-rule-race-condition`), parallel transformation mode still produces extra elements on large models. The RackInspect model shows +13 extra elements (22,147 vs 22,134 expected).

**Root Cause Analysis:**

The previous fix addressed eager rule execution in `TransformationExecutor.executeEagerRulesFor()` by using atomic `getOrCreate()`. However, there are two remaining race condition windows:

1. **`executeParentRule()` in TransformationContext (lines 1383-1437)**
   - Checks cache with `getByRule()` (line 1395)
   - Executes rule and caches result (lines 1423-1425)
   - **Race window:** Multiple threads can pass the cache check before any thread caches a result

2. **`executeParentRulesInChain()` in TransformRuleDescriptor (lines 697-725)**
   - Checks cache with `getByRule()` (line 707)
   - Executes parent rule and caches result (lines 717-721)
   - **Race window:** Same issue - non-atomic cache check + execute

**Evidence:**
- RackInspect model: Expected 22,134 elements, Actual 22,147 elements (+13 duplicates)
- Issue persists after `fix-eager-rule-race-condition` was applied
- Duplicates likely come from `@Extends` parent rule chains being executed concurrently

## What Changes

### Fix 1: Atomic `executeParentRule()` in TransformationContext

Replace the non-atomic cache check + execute pattern with `getOrCreate()`:

**Current (RACE CONDITION):**
```java
EObject cached = resolutionCache.getByRule(source, parentRuleName);
if (cached != null) {
    return (T) cached;
}
// ... race window ...
EObject result = parentRule.execute(source, this);
resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
```

**Fixed:**
```java
return resolutionCache.getOrCreate(source, parentRuleName, () -> {
    // All state management happens inside the lock
    // ... inheritance state setup ...
    return parentRule.execute(source, this);
}, parentRule.isPrimary());
```

### Fix 2: Atomic `executeParentRulesInChain()` in TransformRuleDescriptor

Same pattern - replace non-atomic cache check with `getOrCreate()`:

**Current (RACE CONDITION):**
```java
EObject cached = cache.getByRule(source, parentRuleName);
if (cached != null) {
    continue; // Parent already executed
}
// ... race window ...
EObject result = parentRule.execute(source, context);
cache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
```

**Fixed:**
```java
cache.getOrCreate(source, parentRuleName, () -> {
    return parentRule.execute(source, context);
}, parentRule.isPrimary());
```

## Approach: Test-First Development

### Phase 1: Reproduce the Problem

Before implementing any fix, create tests that expose the race condition:

1. **Add tests to `ParallelFeatureCombinationTest.java`** with:
   - Concurrent `executeParentRule()` test - 50 threads call same parent rule
   - Concurrent `@Extends` inheritance test - child rule executed from 50 threads
   - Multi-level chain test - grandparent/parent/child inheritance
   - Use `CountDownLatch` to synchronize thread start and maximize collision probability
   - Count rule invocations using `AtomicInteger` - assert exactly 1 invocation per (source, rule)
   - Track created instances - assert all threads receive identical target
   - Use minimal mock transformation rules specifically for concurrency testing

2. **Expected test behavior before fix:**
   - Tests should FAIL, proving the race condition exists
   - Parent rule invocation count > 1 (multiple threads execute)
   - Multiple distinct target instances created

3. **Expected test behavior after fix:**
   - Tests should PASS
   - Parent rule invocation count == 1
   - All threads receive same target instance

### Phase 2: Implementation

## Clarifications

1. **Thread count:** Use 50 threads to maximize chance of catching race conditions
2. **Pre-created target:** Still use atomic `getOrCreate()` even when pre-created target is passed, ensuring ETL compatibility
3. **Verify `equivalent()`:** Verify the existing locking in `equivalent()` method is correct (already has per-element locking at lines 942-995)
4. **Test style:** Use minimal mock rules specifically for testing concurrency
5. **Test location:** Add tests to existing `ParallelFeatureCombinationTest.java`

## Scope

- `ParallelFeatureCombinationTest.java` - Add new nested test class for parent rule race condition tests
- `TransformationContext.java` - Make `executeParentRule()` use atomic `getOrCreate()`
- `TransformRuleDescriptor.java` - Make `executeParentRulesInChain()` use atomic `getOrCreate()`
- Verify `equivalent()` method locking is correct

## Complexity

The main challenge is that `executeParentRule()` has complex inheritance state management that must happen inside the atomic block:
- Save/restore `preCreatedTarget`
- Save/restore `inInheritanceExecution`
- Handle lazy rule context reset

This state management must be carefully preserved while moving execution inside the `getOrCreate()` supplier.

## Success Criteria

1. **Correctness:** Parallel mode produces identical element count to sequential mode (22,134 elements)
2. **No duplicates:** Same source + parent rule pair always returns same target
3. **Deterministic:** Multiple parallel runs produce identical results
4. **Regression-free:** Existing tests continue to pass
5. **Inheritance semantics preserved:** `@Extends` chains work correctly

## Risks

- **Inheritance state complexity:** The inheritance state (preCreatedTarget, inInheritanceExecution) uses ThreadLocal storage. Moving execution inside `getOrCreate()` lock doesn't change the thread, so ThreadLocal semantics should be preserved.
- **Lock ordering:** If a parent rule calls `equivalent()` which tries to lock another (source, rule) pair, we could have nested locks. The current implementation handles this correctly - locks are per (source, rule) pair.

## Alternatives Considered

1. **Add separate `executeParentRuleAtomic()` method:** Rejected - would duplicate code and require callers to change.
2. **Global lock on parent rule execution:** Rejected - too coarse, would serialize all parent rule execution.
3. **Optimistic concurrency with retry:** Rejected - adds complexity, doesn't prevent duplicate creation.
