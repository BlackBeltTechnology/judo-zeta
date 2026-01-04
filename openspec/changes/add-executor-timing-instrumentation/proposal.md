# Proposal: Add Executor Timing Instrumentation

**Change ID:** add-executor-timing-instrumentation
**Status:** Proposed
**Created:** 2026-01-04
**Updated:** 2026-01-04

## Why

The `TransformationMetrics` class already has timing metrics defined but they are not being called from `TransformationExecutor`. This leaves significant portions of transformation time unaccounted for in performance reports, making it difficult to identify bottlenecks.

Current report shows high UNACCOUNTED percentage because:
- Total transformation timing not recorded
- Element collection/model iteration not timed
- Staging commit phase not timed
- Parallel execution overhead not tracked

## What Changes

Add instrumentation calls in `TransformationExecutor.java` to use existing `TransformationMetrics` methods:

1. **Total transformation timing** - wrap the entire `transform()` method
2. **Element collection timing** - measure source element gathering phase
3. **Staging commit timing** - measure `commitStagedElements()` call
4. **Parallel execution overhead** - track list conversion and partitioning

## Implementation Details

### 1. Total Transformation Timing (in `transform()`)

```java
public TransformationResult transform() {
    reset();
    initElementCache();
    long startTime = System.currentTimeMillis();

    TransformationMetrics.startTransformation();  // ADD

    registry.invokePreTransformationHooks(context);
    try {
        // ... existing code ...
        return new TransformationResult(context, duration);
    } finally {
        TransformationMetrics.endTransformation();  // ADD
        context.disableStaging();
        registry.invokePostTransformationHooks(context);
        context.clearExtensionCache();
        clearElementCache();
    }
}
```

### 2. Element Collection Timing

```java
long elementCollectionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;

Set<EObject> singleSourceElements = new LinkedHashSet<>();
// ... element collection loop ...

if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addModelIterationNanos(System.nanoTime() - elementCollectionStart);
}
```

### 3. Staging Commit Timing

```java
long commitStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
context.commitStagedElements();
if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addStagingCommitNanos(System.nanoTime() - commitStart);
}
```

### 4. Parallel Execution Overhead

```java
// Time list conversion
long listConversionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
List<EObject> elementList = new ArrayList<>(sourceElements);
if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addModelIterationNanos(System.nanoTime() - listConversionStart);
}

// Time partitioning
long partitionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
List<List<EObject>> chunks = partitionList(elementList, effectiveChunkSize);
if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addModelIterationNanos(System.nanoTime() - partitionStart);
}
```

## Expected Report Output

After these changes, the timing breakdown will show:

```
=== TIMING BREAKDOWN (ALL COMPONENTS) ===
  Greedy rule execution:          1,200 ms (15.0%)
  equivalent() total:               400 ms ( 5.0%)
  createTarget():                   300 ms ( 3.8%)
  Model iteration:                2,500 ms (31.3%)  <-- Now tracked
  Staging commit:                   800 ms (10.0%)  <-- Now tracked
  ----------------------------------------
  ACCOUNTED:                      5,200 ms (65.0%)
  UNACCOUNTED:                    2,800 ms (35.0%)  <-- Reduced
```

## No New Metrics Required

All timing methods already exist in `TransformationMetrics`:
- `startTransformation()` / `endTransformation()` - lines 238-246
- `addModelIterationNanos()` - line 335
- `addStagingCommitNanos()` - line 331

This proposal only adds the INSTRUMENTATION CALLS in `TransformationExecutor`.

## Success Criteria

1. UNACCOUNTED percentage reduced from ~50%+ to <30%
2. All 500+ existing tests pass
3. No measurable performance overhead when metrics disabled
