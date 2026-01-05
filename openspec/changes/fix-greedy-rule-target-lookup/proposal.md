# Proposal: Fix ctx.equivalent() Returns Null for Greedy Pass Targets

**Change ID**: `fix-greedy-rule-target-lookup`
**Status**: Draft
**Author**: Robson
**Date**: 2026-01-05
**Related Issue**: ZETA-EQ-001 (ctx.equivalent() Returns Null for Greedy Pass Targets)

## Executive Summary

Fix a critical bug where `ctx.equivalent(source, RULE_NAME)` returns `null` when trying to retrieve targets created by other `@Greedy` rules during the same greedy pass. This blocks full ETL equivalence and causes ~8,000 missing elements in judo-tatami-esm2ui.

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
// Rule A: Creates targets during greedy pass
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
        // This returns NULL - BUG
        ClassType targetType = ctx.equivalent(source.getTarget(), CLASS_TYPE);
        target.setTarget(targetType);  // Sets null!
    };
}
```

## Root Cause Analysis

### Hypothesis 1: XMI ID Lookup Not Triggered for Eager Rules

The `equivalent()` method may not be performing XMI ID lookup when the rule is eager (not lazy). Looking at the current implementation, the XMI ID lookup might only be triggered after checking the cache, but eager rule targets may not have their XMI IDs set correctly before other rules try to look them up.

### Hypothesis 2: Staging Delay Affects ID Availability

When parallel transformation uses staging, XMI IDs are applied during commit, not immediately on creation. If `equivalent()` checks XMI IDs before commit completes, the IDs won't be found.

### Hypothesis 3: Target Caching Before ID Generation

Targets might be cached in `ElementResolutionCache` before their XMI IDs are generated, causing the lookup to fail when `equivalent()` tries to find them by ID.

## Proposed Solution

### Option A: Fix XMI ID Lookup Timing

Ensure `equivalent()` checks XMI IDs immediately after cache miss, regardless of whether the rule is eager or lazy. The XMI ID should be generated at target creation time (not deferred).

**Pros**: Minimal change, preserves existing behavior
**Cons**: May impact performance if IDs are generated for elements that are never looked up

### Option B: Cache Targets with Correct ID Immediately

Ensure targets are added to the XMI ID index immediately upon creation, before being cached.

**Pros**: Correct semantics, no timing issues
**Cons**: Requires restructuring target creation flow

### Option C: Sequential Greedy Pass to Staging

Run greedy pass sequentially (no parallel) to ensure all targets are committed before cross-rule lookups.

**Pros**: Simple fix, matches ETL sequential behavior
**Cons**: Performance impact for large models

## Recommended Approach

**Option A with verification**: Fix XMI ID lookup timing and add comprehensive tests for the greedy-to-greedy pattern.

## Scope

### In Scope

1. Fix `TransformationContext.equivalent()` to correctly find targets from eager greedy rules
2. Ensure XMI ID lookup works for targets created in the same greedy pass
3. Add integration test for `@Greedy` → `ctx.equivalent()` → `@Greedy` pattern
4. Verify fix works in both sequential and parallel modes

### Out of Scope

1. Changes to `@ActivityBased` behavior (already covered by existing specs)
2. Performance optimization beyond the fix
3. Changes to rule execution ordering

## Dependencies

- `parallel-transformation` spec (existing)
- `activity-based-greedy` spec (existing)
- `element-resolution-cache` implementation

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Fix breaks parallel transformation | High | Test in both modes |
| Performance regression | Medium | Benchmark before/after |
| Unintended side effects | Medium | Add regression tests |

## Acceptance Criteria

1. `ctx.equivalent()` returns non-null for targets created by other greedy rules in the same pass
2. `Esm2UiExternalModelTest` passes with 208 dataElements matching
3. All RelationType.target values are correctly set
4. Both sequential and parallel modes produce identical results
5. No regression in existing transformation tests

## References

- Related Issue: ZETA-EQ-001
- Affected Module: judo-tatami-esm2ui
- Related Specs: parallel-transformation, activity-based-greedy
