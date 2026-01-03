# Proposal: Fix @Extends Guard Bypass

**Change ID:** fix-extends-guard-bypass
**Status:** Proposed
**Created:** 2026-01-03
**Updated:** 2026-01-03

## Problem Statement

When a child rule uses `@Extends` to invoke a parent rule that has a `@Guard`, the parent's guard is **not evaluated**. This causes incorrect behavior when the parent's guard should reject certain source elements.

### Symptoms

- Validation errors like: "Named element X is not unique in its container"
- Duplicate elements in collections (e.g., units added twice to a measure)
- Tests pass with ETL but fail with Zeta

### Root Cause

The `executeParentRulesInChain()` method in `TransformRuleDescriptor` calls `parentRule.execute()` directly without checking the parent's guard:

```java
// TransformRuleDescriptor.java line 715-720
cache.getOrCreate(source, parentRuleName, () -> {
    return parentRule.execute(source, context);  // NO GUARD CHECK!
}, parentRule.isPrimary());
```

Similarly, `TransformationContext.executeParentRule()` has the same issue at line 1498-1500.

### Test Evidence

The bug is reproduced in `ExtendsGuardBypassTest`:

1. Parent rule has `@Abstract` + `@Primary` + `@Guard(method = "isSpecial")`
2. Child rule has `@Primary` + `@Extends("ParentRule")`
3. Child's guard always passes
4. Parent's guard only accepts elements starting with "Special_"

**Expected:** For "Regular_Item", parent guard rejects, child should not execute (or parent should not contribute).

**Actual:** Parent guard is **never called**. Parent executes, causing duplicate elements.

## ETL Semantics Reference

From the [Epsilon ETL documentation](https://eclipse.dev/epsilon/doc/etl/):

> "the element must...also satisfy the `guard` of the rule (and all the rules it extends)."

This means **all guards in the inheritance chain must pass** (conjunctive AND relationship). If ANY parent guard rejects, the child rule should also abort.

## Proposed Solution

### Location 1: Evaluate Guards Before executeWithInheritance

Add guard evaluation for ALL parent rules BEFORE executing the child:

```java
// TransformRuleDescriptor.java - execute() method, before calling executeWithInheritance()
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

### Location 2: Evaluate Guard in TransformationContext.executeParentRule

Add guard evaluation in the public API method:

```java
// TransformationContext.java - executeParentRule()
public <T extends EObject> T executeParentRule(String parentRuleName, EObject source, T target) {
    // ... existing validation ...

    // NEW: Evaluate parent's guard before execution
    if (!parentRule.evaluateGuard(source, this)) {
        return null;  // Parent guard rejected
    }

    // ... rest of method ...
}
```

### Key Semantic: Child Aborts if ANY Parent Rejects

Unlike the initial proposal where skipped parents allowed children to continue, the correct ETL behavior is:

- If `@Extends({"Rule1", "Rule2"})` and Rule1's guard passes but Rule2's guard rejects
- The child rule should **abort entirely** (return null)
- No partial execution

## ETL Semantics Compatibility

In Epsilon ETL, parent rules in `@extends` chains are only executed if their guards pass. The proposed fix aligns Zeta with this behavior.

**Note:** Guard results should be cached per (source, ruleName) pair to avoid redundant evaluation, which is already implemented via `TransformRuleDescriptor.evaluateGuard()`.

## Impact

- **Files to modify:**
  - `TransformRuleDescriptor.java`: Add guard check in `executeParentRulesInChain()`
  - `TransformationContext.java`: Add guard check in `executeParentRule()`

- **Risk:** Low - isolated changes to parent rule invocation paths

- **Backward compatibility:** The fix makes Zeta more correct. Existing code that relied on bypassed guards was incorrect.

## Success Criteria

1. All existing tests pass (444+)
2. New `ExtendsGuardBypassTest` tests pass:
   - `testParentGuardEvaluatedViaExtends`
   - `testParentGuardPassesForValidElement`
   - `testNoDuplicateElementsWhenParentGuardRejects`
3. No duplicate elements created when parent guard rejects
