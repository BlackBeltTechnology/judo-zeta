# Proposal: Add Activity-Based Greedy Processing

**Change ID**: `add-activity-based-greedy`
**Status**: Proposed
**Created**: 2026-01-01
**Type**: Enhancement

## Summary

Add an optional "activity-based" processing mode for `@Greedy` rules that matches Epsilon ETL's implicit filtering behavior. In this mode, `@Greedy` rules only process elements that are "activated" through `equivalent()` calls during transformation, rather than eagerly processing ALL matching elements.

## Problem Statement

### Current Behavior (ZETA)

```java
@Greedy
@Lazy
public TransformFunction<TransferObjectType, ClassType> classType() { ... }
```

ZETA's `@Greedy` eagerly processes **ALL** matching source elements:
- Uses `ModelProvider.getAllContents()` to iterate over all instances
- Processes every `TransferObjectType` in the model (206 types)
- Creates targets for all elements passing guards (135 types)

### Epsilon ETL Behavior

```
@greedy
@lazy
rule ClassType
    transform s: ESM!TransferObjectType
    to t: UI!ClassType { ... }
```

ETL's `@greedy @lazy` has implicit "activity-based" processing:
- Only processes elements that are referenced via `equivalent()` during transformation
- Elements never "activated" are never processed
- Creates targets only for active elements (133 types)

### Impact

| Metric | ETL | ZETA | Difference |
|--------|-----|------|------------|
| Source elements | 206 | 206 | Same |
| Created targets | 133 | 135 | +2 (1.5%) |
| Orphan elements | Skipped | Processed | Different |

The 2 extra elements in ZETA are orphan input types (`PartnerForOperationInput`, `ErrorQualificationInstanceInput`) that no operation references.

## Proposed Solution

Add an `@ActivityBased` annotation that can be combined with `@Greedy @Lazy` to enable activity-based processing:

```java
@Greedy
@Lazy
@ActivityBased  // Only process elements activated via equivalent()
public TransformFunction<TransferObjectType, ClassType> classType() { ... }
```

### How It Works

1. **Phase 1: Activation Tracking**
   - During transformation, track which source elements are referenced via `equivalent()`
   - Store activated elements in `ActivationTracker` (per source type)

2. **Phase 2: Deferred Greedy Execution**
   - After all rules complete their first pass, execute activity-based greedy rules
   - Only process elements that were activated during Phase 1
   - Skip elements that were never referenced

### Alternative: Transformation-Level Setting

Instead of per-rule annotation, provide a transformation-level setting:

```java
TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .activityBasedGreedy(true)  // Apply to all @Greedy @Lazy rules
    .build();
```

## Scope of Changes

### Files to Modify

| Module | File | Change |
|--------|------|--------|
| zeta-annotations | `ActivityBased.java` | New annotation |
| transformation-core | `ActivationTracker.java` | New class to track activations |
| transformation-core | `TransformationContext.java` | Record activations in equivalent() |
| transformation-core | `TransformationExecutor.java` | Two-phase execution for activity-based rules |
| transformation-core | `TransformRuleDescriptor.java` | Add `isActivityBased()` method |

### Backwards Compatibility

- Default behavior unchanged (eager processing of all elements)
- Opt-in via `@ActivityBased` annotation or executor setting
- No migration required for existing transformations

## Trade-offs

### Pros
- Exact output matching with Epsilon ETL
- Potentially fewer unnecessary elements created
- Cleaner output models (no orphan elements)

### Cons
- Two-phase execution adds complexity
- Memory overhead for activation tracking
- May hide bugs (orphan types not being processed could indicate model issues)

## Success Criteria

- [ ] `@ActivityBased` annotation available
- [ ] Activation tracking during `equivalent()` calls
- [ ] Deferred execution for activity-based greedy rules
- [ ] ZETA produces same element count as ETL for benchmark tests
- [ ] All existing tests pass without modification
- [ ] New tests cover activity-based scenarios
- [ ] Documentation updated

## Design Decisions

1. **Both per-rule and per-transformation settings**
   - Per-rule: `@Greedy @Lazy @ActivityBased` for fine-grained control
   - Per-transformation: `etlCompatibilityMode(true)` for ETL ports

2. **Explicit annotations required**
   - `@ActivityBased` does NOT imply `@Lazy`
   - Must use all three: `@Greedy @Lazy @ActivityBased`

3. **ETL compatibility mode available**
   - `TransformationExecutor.builder().etlCompatibilityMode(true)`
   - Automatically applies activity-based to all `@Greedy @Lazy` rules

## Open Questions

1. **What about circular dependencies?**
   - If rule A activates rule B which activates rule A, need cycle detection
   - ETL handles this via lazy execution - first reference creates the element

2. **Performance impact?**
   - Need to benchmark activation tracking overhead
   - Memory usage for large models

## References

- Epsilon ETL Documentation: https://eclipse.dev/epsilon/doc/etl/
- Epsilon FastTransformationStrategy source code
- JUDO Tatami benchmark: 133 vs 135 ClassTypes
- Investigation: Orphan types `PartnerForOperationInput`, `ErrorQualificationInstanceInput`
