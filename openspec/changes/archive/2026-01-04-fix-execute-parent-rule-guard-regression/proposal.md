# Proposal: Fix executeParentRule Guard Regression

**Change ID:** fix-execute-parent-rule-guard-regression
**Status:** Applied
**Created:** 2026-01-03
**Updated:** 2026-01-03

## Problem Statement

Regression in Zeta version `1.0.0.20260103_131027_0fb37582` causes 309 missing elements in RackInspect model transformation.

### Symptoms

- 99 DataProperty elements missing
- 99 DataExpressionType elements missing
- 309 total elements missing (including related elements)
- Warning: "No default equivalent for DataMember: active"

### Root Cause

Commit `4a44f4c` (fix-extends-guard-bypass) added guard evaluation to `executeParentRule()`:

```java
// TransformationContext.java line 1613-1617
// ETL Semantics: Check parent's guard before execution
if (!parentRule.evaluateGuard(source, this)) {
    return null;  // Parent guard rejected
}
```

This guard check is **redundant** for `@Extends` chains (already handled in `TransformRuleDescriptor.execute()`) but **breaks** direct rule invocation use cases.

### Invocation Chain Analysis

```
1. CreateTransferAttributeForDefaultValue (@Greedy)
   calls getDataMemberPSMDefaultEquivalent(source, ctx)

2. Helper method calls:
   ctx.executeParentRule("CreateDataPropertyForTransferAttributeDefaultValue", dm)

3. Rule has guard: isDataMemberDefaultOnMappedTO
   - Guard rejects EntityType containers (correct behavior for @Extends)
   - But helper expects rule to run regardless

4. BEFORE fix: executeParentRule() ran rule directly → SUCCESS
   AFTER fix: executeParentRule() checks guard → FAILURE (null returned)
```

### Why the Guard Check is Redundant

For `@Extends` inheritance chains, guards are already checked in `TransformRuleDescriptor.execute()`:

```java
// TransformRuleDescriptor.java lines 604-612
if (!extendsRules.isEmpty() && context.getTransformationRegistry() != null) {
    // ETL Semantics: ALL parent guards must pass before child executes
    for (String parentRuleName : extendsRules) {
        TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
        if (parentRule != null && !parentRule.evaluateGuard(source, context)) {
            return null;  // Parent guard rejected - abort entire chain
        }
    }
    return executeWithInheritance(source, context);
}
```

The automatic `@Extends` chain uses `executeParentRulesInChain()` which calls `parentRule.execute()` directly, NOT `ctx.executeParentRule()`. Therefore, the guard check in `executeParentRule()` is:

1. **NOT used** for automatic `@Extends` chains
2. **ONLY used** for manual calls from user code

## Proposed Solution

Remove the guard check from `executeParentRule()` in `TransformationContext.java`:

```java
// REMOVE these lines (1613-1617):
// ETL Semantics: Check parent's guard before execution
// "the element must also satisfy the guard of the rule (and all the rules it extends)"
if (!parentRule.evaluateGuard(source, this)) {
    return null;  // Parent guard rejected
}
```

### Rationale

1. **`@Extends` guard checks are already handled** in `TransformRuleDescriptor.execute()` (lines 604-612)
2. **Manual callers can check guards themselves** if needed via `rule.evaluateGuard(source, ctx)`
3. **Restores previous behavior** that worked correctly for direct rule invocation
4. **No impact on `@Extends` semantics** since those use a different code path

### Alternative Considered: New Method

We could add a separate `executeRuleDirectly()` method without guard check. However, this:
- Adds API complexity
- Requires caller code changes
- The guard check is already redundant, so removal is simpler

## Impact Analysis

| Version | Model | Result |
|---------|-------|--------|
| `fcfb6dc` (before) | RackInspect | PASS |
| `0fb3758` (after) | RackInspect | FAIL (309 missing) |
| `0fb3758` (after) | Northwind | PASS (smaller, no EntityType defaults) |

## Test Plan

1. All existing tests pass (607+)
2. `ExtendsGuardBypassTest` tests still pass (guards checked in `TransformRuleDescriptor.execute()`)
3. RackInspect model transformation produces correct output
4. Northwind model transformation produces correct output

## Success Criteria

1. RackInspect model produces same output as version `fcfb6dc`
2. All 607+ tests pass
3. `ExtendsGuardBypassTest` tests pass (no regression in @Extends guard behavior)
