# Design: Fix @Extends Guard Bypass

## Current Architecture (Buggy)

```
┌────────────────────────────────────────────────────────────────────────────┐
│                     TransformationExecutor.transform()                      │
│                                                                             │
│  Eager Rule Execution:                                                      │
│    for each element:                                                        │
│      for each rule:                                                         │
│        if (rule.evaluateGuard(source))        ← Guards checked HERE        │
│          cache.getOrCreate(source, rule, () -> {                           │
│            rule.execute(source, context);                                   │
│          });                                                                │
└────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌────────────────────────────────────────────────────────────────────────────┐
│                     TransformRuleDescriptor.execute()                       │
│                                                                             │
│  if (extendsRules.isEmpty())                                               │
│    return function.transform(source, context);                              │
│  else                                                                       │
│    return executeWithInheritance(source, context);                          │
│           ↓                                                                 │
│           ┌─────────────────────────────────────────────────────────────┐   │
│           │ executeWithInheritance()                                    │   │
│           │                                                              │   │
│           │   // Pre-create target of child's type                      │   │
│           │   target = createTargetDirectly(targetType);                │   │
│           │   context.setPreCreatedTarget(target);                      │   │
│           │                                                              │   │
│           │   // Execute parent rules FIRST                             │   │
│           │   executeParentRulesInChain(source, context);               │   │
│           │   ↓                                                          │   │
│           │   ┌─────────────────────────────────────────────────────┐   │   │
│           │   │ executeParentRulesInChain()                         │   │   │
│           │   │                                                      │   │   │
│           │   │   for (parentRuleName : extendsRules):              │   │   │
│           │   │     parentRule = registry.getRuleByName()            │   │   │
│           │   │     cache.getOrCreate(source, parentRuleName,        │   │   │
│           │   │       () -> parentRule.execute()   ← NO GUARD!      │   │   │
│           │   │     )                                                │   │   │
│           │   │                             ↑                        │   │   │
│           │   │                      BUG: Guard bypassed            │   │   │
│           │   └─────────────────────────────────────────────────────┘   │   │
│           │                                                              │   │
│           │   // Then execute child's function                          │   │
│           │   return function.transform(source, context);               │   │
│           └─────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
```

## Why Guards Are Bypassed

### Scenario: Abstract Parent with Guard

1. **ParentRule** has `@Abstract` + `@Guard(isSpecial)` - only matches "Special_*"
2. **ChildRule** has `@Extends("ParentRule")` + no guard (matches all)

### Execution Flow for "Regular_Item"

```
1. Executor iterates rules
   - ParentRule is @Abstract → SKIPPED (not matched)
   - ChildRule matches → Guard checked → Passes

2. ChildRule.execute() is called
   - Has @Extends → calls executeWithInheritance()
   - Pre-creates target
   - Calls executeParentRulesInChain()

3. executeParentRulesInChain()
   - Gets ParentRule from registry
   - Calls cache.getOrCreate(source, "ParentRule", () -> {
       parentRule.execute(source, context);  // ← NO GUARD CHECK!
     })
   - ParentRule.execute() runs
   - Target is populated by parent

4. ChildRule function runs
   - Further modifies target

RESULT: Both parent and child execute, even though parent's guard
        would have rejected "Regular_Item"
```

## Fixed Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│                     executeParentRulesInChain() - FIXED                     │
│                                                                             │
│   for (parentRuleName : extendsRules):                                     │
│     parentRule = registry.getRuleByName()                                  │
│                                                                             │
│     // NEW: Check parent's guard before execution                          │
│     if (!parentRule.evaluateGuard(source, context)) {                      │
│       continue;  // Guard rejected → skip this parent                      │
│     }                                                                       │
│                                                                             │
│     cache.getOrCreate(source, parentRuleName,                              │
│       () -> parentRule.execute()   ← Now only if guard passes              │
│     )                                                                       │
└────────────────────────────────────────────────────────────────────────────┘
```

## Guard Evaluation Flow

```
┌─────────────────────────────────────────────────────────────────┐
│               TransformRuleDescriptor.evaluateGuard()            │
│                                                                  │
│   // ETL-compatible: Check rejection cache first                │
│   if (rejected.contains(source)) {                              │
│     return false;  // Previously rejected                       │
│   }                                                              │
│                                                                  │
│   // No guard = always passes                                   │
│   if (guardMethod == null) {                                    │
│     return true;                                                │
│   }                                                              │
│                                                                  │
│   // Evaluate guard method                                      │
│   boolean result = guardMethod.invoke(instance, source, ctx);   │
│                                                                  │
│   // Cache rejection for future calls                           │
│   if (!result) {                                                │
│     rejected.add(source);                                       │
│   }                                                              │
│                                                                  │
│   return result;                                                │
└─────────────────────────────────────────────────────────────────┘
```

## Fix Locations

### Location 1: TransformRuleDescriptor.executeParentRulesInChain()

**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformRuleDescriptor.java`
**Line:** ~709-722

```java
// BEFORE:
for (String parentRuleName : extendsRules) {
    TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
    if (parentRule != null) {
        cache.getOrCreate(source, parentRuleName, () -> {
            return parentRule.execute(source, context);
        }, parentRule.isPrimary());
    }
}

// AFTER:
for (String parentRuleName : extendsRules) {
    TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
    if (parentRule != null) {
        // Check parent's guard before execution
        if (!parentRule.evaluateGuard(source, context)) {
            continue;  // Guard rejected - skip this parent
        }

        cache.getOrCreate(source, parentRuleName, () -> {
            return parentRule.execute(source, context);
        }, parentRule.isPrimary());
    }
}
```

### Location 2: TransformationContext.executeParentRule()

**File:** `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`
**Line:** ~1461-1511

```java
// BEFORE (line ~1516):
try {
    return (T) resolutionCache.getOrCreate(source, parentRuleName, () -> {
        return parentRule.execute(source, this);
    }, parentRule.isPrimary());
}

// AFTER:
try {
    // Check parent's guard before execution
    if (!parentRule.evaluateGuard(source, this)) {
        return null;  // Guard rejected
    }

    return (T) resolutionCache.getOrCreate(source, parentRuleName, () -> {
        return parentRule.execute(source, this);
    }, parentRule.isPrimary());
}
```

## Thread Safety

Both fixes use existing thread-safe patterns:
- `evaluateGuard()` uses thread-safe rejection cache (`ConcurrentHashMap.newKeySet()`)
- `getOrCreate()` uses lock striping for concurrent access
- Guard evaluation happens before entering the lock, minimizing contention

## ETL Compatibility

The fix aligns with ETL semantics:
- In ETL, parent rules in `extends` chains are only executed if their guards pass
- Guard results are cached per (source, rule) pair
- Rejected sources are not re-evaluated

## Testing Strategy

1. **Unit Tests:** `ExtendsGuardBypassTest`
   - `testParentGuardEvaluatedViaExtends` - Verifies guard is called
   - `testParentGuardPassesForValidElement` - Verifies guard passes for valid elements
   - `testNoDuplicateElementsWhenParentGuardRejects` - Verifies no duplicates

2. **Regression Tests:** Run all 444+ existing tests

3. **Edge Cases:**
   - Multi-level inheritance (grandparent rules)
   - Multiple parents in `@Extends({"Rule1", "Rule2"})`
   - Parent without guard (should always execute)
