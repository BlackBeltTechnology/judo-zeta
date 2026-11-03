# Proposal: Fix ctx.equivalent() Returns Null for Greedy Pass Targets

**Change ID**: `fix-greedy-rule-target-lookup`
**Status**: In Progress
**Author**: Robson
**Date**: 2026-01-05
**Related Issue**: ZETA-EQ-001 (ctx.equivalent() Returns Null for Greedy Pass Targets)

## Executive Summary

Fix a critical bug where `ctx.equivalent(source, "RULE_NAME")` returns `null` when called from another `@Greedy` rule during the same transformation pass. This blocks cross-rule target lookups and causes ~8,000 missing elements in judo-tatami-esm2ui.

## Problem Statement

### Symptoms

1. `ctx.equivalent(source.getTarget(), CLASS_TYPE)` returns `null` for valid TransferObjectTypes
2. `RelationType.target` is null, causing comparison failures
3. 136 dataElements found vs 208 expected (72 missing ClassTypes)
4. All RelationType.target values are null in the output model

### Reproduction

```bash
cd judo-tatami-esm2ui
mvn test -Dtest=Esm2UiExternalModelTest
# Observe: dataElements: size=136 vs size=208
```

### Affected Pattern

```java
// Rule A: Creates targets (marked @Lazy for Phase 2 execution)
@Greedy
@Primary
@Lazy
public TransformFunction<Class, ClassType> classType() {
    return (source, ctx) -> {
        ClassType target = ctx.createTarget(ClassType.class);
        // ... setup ...
        application.getDataElements().add(target);
        return target;
    };
}

// Rule B: Tries to find Rule A's targets
@Greedy
public TransformFunction<RelationFeature, RelationType> relationType() {
    return (source, ctx) -> {
        // This returns NULL - BUG!
        ClassType targetType = ctx.equivalent(source.getTarget(), CLASS_TYPE);
        target.setTarget(targetType);  // Sets null!
    };
}
```

## Root Cause Analysis

### Confirmed Root Cause: Lazy Rule Activation Only

The issue is in `TransformationContext.equivalent(source, String)`:

```java
// Line 1480-1484
if (isEffectivelyActivityBased(rule)) {
    activate(rule.getName(), source);
    // Don't execute now - Phase 2 will execute for activated elements
    return null;  // ❌ Returns null!
}
```

When `equivalent(source, "ClassType")` is called from RelationType rule:

1. It finds the ClassType rule which is `@Lazy`
2. `isEffectivelyActivityBased(rule)` returns true (since it's @Greedy @Lazy)
3. It only **records activation** and returns `null`
4. The actual ClassType execution happens in **Phase 2**
5. But by then, RelationType has already set `target.setTarget(null)`!

### The Fix

When `equivalent()` is called **explicitly with a rule name** (not via type-based lookup), lazy rules should execute **immediately** rather than just recording activation for Phase 2.

## Proposed Solution

Modify `equivalent(EObject source, String ruleName)` to execute lazy rules immediately when called explicitly:

```java
// Before: Just records activation
if (isEffectivelyActivityBased(rule)) {
    activate(rule.getName(), source);
    return null;
}

// After: Execute immediately for explicit calls
if (isEffectivelyActivityBased(rule)) {
    // For explicit calls (with rule name), execute immediately
    // This ensures cross-rule lookups work during the same pass
    EObject result = executeLazyRuleImmediately(source, rule);
    if (result != null) {
        return (T) result;
    }
    // Fall back to activation for Phase 2
    activate(rule.getName(), source);
    return null;
}
```

## Scope

### In Scope

1. Modify `TransformationContext.equivalent(source, String)` to execute lazy rules immediately
2. Add unit test for cross-rule target lookup pattern
3. Verify fix in sequential and parallel modes

### Out of Scope

1. Changes to Phase 2 activity-based processing
2. Changes to type-based `equivalent(source, Type)` overload

## Dependencies

- `activity-based-greedy` spec (existing)

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Fix breaks Phase 2 activation | Medium | Only affect explicit rule name calls |
| Performance regression | Low | Lazy rules still cached normally |

## Acceptance Criteria

1. `ctx.equivalent(source, "ClassType")` returns non-null when called from another greedy rule
2. `Esm2UiExternalModelTest` passes with 208 dataElements matching
3. All RelationType.target values are correctly set
4. Both sequential and parallel modes produce identical results
5. No regression in existing transformation tests

---

## Extended Scope: Cross-Source-Type Lazy Rule Invocation (JNG-6349)

### Problem Statement

After the initial fix (Phases 1-4) was implemented, a related but distinct bug was discovered:

`ctx.equivalent(source, "RuleName")` returns `null` when:
1. The calling rule has source type A (e.g., `RelationFeature`)
2. The target rule has source type B (e.g., `TransferObjectTable`) - **different type!**
3. The target rule has `@Lazy @Greedy` annotations
4. The target rule's `@Greedy` pass hasn't executed yet

### Difference from Initial Fix

The initial fix (Phases 1-4) addressed the **same-source-type** scenario:
- `ClassType` rule: `@Greedy @Lazy @Primary` on `EClass`
- `RelationType` rule: `@Greedy` on `EReference`
- Both rules are in the **same transformation hierarchy** (Ecore types)
- Test: `GreedyLazyCrossRuleLookupTest.java` - **PASSES**

The new issue is **cross-source-type**:
- Rule A: source type `RelationFeature`
- Rule B: source type `TransferObjectTable` (completely different metamodel type)
- Rules are in **different transformation classes** with different `@TransformationContext`

### Debug Evidence

```
18:49:51.083 [main] INFO  RelationFeatureTableAddSelectorTransformations -
    Looking up ActionDefinition for table=Entity4Table (id=_Q87XXOvxEfCwMo6V1TZ9oA)
18:49:51.083 [main] INFO  RelationFeatureTableAddSelectorTransformations -
    ActionDefinition lookup result=null

# LATER - After all RelationFeature processing is complete:

18:50:13.838 [main] INFO  TransferObjectTableTransformations -
    TransferObjectTableAddSelectorAddActionDefinition: INVOKED for source=Entity2Table
```

### Hypothesis

The fix `executeLazyRuleImmediately()` is being called but returning `null` because one of:

1. **`appliesTo()` returns false** - EMF type matching issue between packages
2. **Guard evaluation fails** - Guard rejects cross-type sources
3. **Cache key mismatch** - Cache lookup fails for cross-type scenario

### Extended Acceptance Criteria

6. `ctx.equivalent(source, "RuleName")` returns non-null for **cross-source-type** lookups
7. Registration order independence - result is same regardless of transformation class registration order
8. `Esm2UiBenchmarkTest.benchmarkTransformations()` passes with no null `actionDefinition` values
9. 40+ `RelationFeatureTableAddSelectorAddAction` elements have valid targets
