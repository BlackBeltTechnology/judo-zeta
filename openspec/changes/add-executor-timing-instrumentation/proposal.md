# Proposal: Add Executor Timing Instrumentation

**Change ID:** add-executor-timing-instrumentation
**Status:** In Progress
**Created:** 2026-01-04
**Updated:** 2026-01-04

## Why

The `TransformationMetrics` class has timing metrics but they don't cover all significant execution phases. Initial instrumentation reduced UNACCOUNTED time but ~70% remains untracked.

Analysis shows the main bottlenecks are:
- **executeEagerRulesFor loop** - Called 22K times, each iterating through ALL rules (~50+)
- **Rule skip checks** - 22K × 50 = 1.1M checks per transformation
- **Parallel execution overhead** - Future creation, wait, chunk processing
- **Cache getOrCreate overhead** - Outside of actual rule execution

## What Changes

### Phase 1 (Completed)

Added instrumentation calls for existing `TransformationMetrics` methods:
1. Total transformation timing (`startTransformation`/`endTransformation`)
2. Element collection timing (`addModelIterationNanos`)
3. Staging commit timing (`addStagingCommitNanos`)
4. List conversion and partitioning (`addModelIterationNanos`)

### Phase 2 (This Update)

Add NEW metrics to track the remaining ~70% UNACCOUNTED time:

1. **Rule matching** - `registry.getRulesForSource()` lookup time
2. **Rule loop overhead** - Iteration through rules in `executeEagerRulesFor`
3. **Chunk processing** - Total time in `transformChunk` method
4. **Future creation** - Stream/CompletableFuture setup overhead
5. **Parallel wait** - Time waiting for parallel tasks to complete
6. **Cache getOrCreate** - Cache operation overhead (outside rule execution)

## Implementation Details

### 1. New Metrics in TransformationMetrics.java

```java
// New counters
private static final AtomicLong ruleMatchingNanos = new AtomicLong(0);
private static final AtomicLong ruleLoopNanos = new AtomicLong(0);
private static final AtomicLong chunkProcessingNanos = new AtomicLong(0);
private static final AtomicLong futureCreationNanos = new AtomicLong(0);
private static final AtomicLong parallelWaitNanos = new AtomicLong(0);
private static final AtomicLong cacheGetOrCreateNanos = new AtomicLong(0);

// New recording methods
public static void addRuleMatchingNanos(long nanos) {
    if (enabled.get()) ruleMatchingNanos.addAndGet(nanos);
}
public static void addRuleLoopNanos(long nanos) {
    if (enabled.get()) ruleLoopNanos.addAndGet(nanos);
}
public static void addChunkProcessingNanos(long nanos) {
    if (enabled.get()) chunkProcessingNanos.addAndGet(nanos);
}
public static void addFutureCreationNanos(long nanos) {
    if (enabled.get()) futureCreationNanos.addAndGet(nanos);
}
public static void addParallelWaitNanos(long nanos) {
    if (enabled.get()) parallelWaitNanos.addAndGet(nanos);
}
public static void addCacheGetOrCreateNanos(long nanos) {
    if (enabled.get()) cacheGetOrCreateNanos.addAndGet(nanos);
}
```

### 2. Instrument transformChunk (lines 643-656)

```java
private void transformChunk(List<EObject> chunk) {
    long chunkStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;

    for (EObject source : chunk) {
        if (firstError.get() != null) return;
        try {
            context.setCurrentSource(source);
            executeEagerRulesFor(source);
        } finally {
            context.clearCurrentSource();
        }
    }

    if (TransformationMetrics.isEnabled()) {
        TransformationMetrics.addChunkProcessingNanos(System.nanoTime() - chunkStart);
    }
}
```

### 3. Instrument executeEagerRulesFor (lines 790-853)

This is the **prime suspect** for the 70% UNACCOUNTED time:
- Called 22,134 times (once per source element)
- Each call iterates through ALL registered rules (~50+ rules)
- That's 22,134 × 50 = 1,106,700 rule checks

```java
private void executeEagerRulesFor(EObject source) {
    // Time rule lookup
    long rulesStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
    Collection<TransformRuleDescriptor> rules = registry.getRulesForSource(source.getClass());
    if (TransformationMetrics.isEnabled()) {
        TransformationMetrics.addRuleMatchingNanos(System.nanoTime() - rulesStart);
    }

    // Time rule iteration loop
    long loopStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;

    for (TransformRuleDescriptor rule : rules) {
        if (rule.isMultiSource()) continue;
        if (rule.isLazy()) continue;
        if (rule.isAbstract()) continue;
        if (isEffectivelyActivityBased(rule)) continue;
        if (!rule.appliesTo(source)) continue;
        if (!isFromExpectedAlias(source, rule)) continue;

        // Time cache operation separately
        long cacheStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        context.getElementResolutionCache().getOrCreate(...);
        if (TransformationMetrics.isEnabled()) {
            TransformationMetrics.addCacheGetOrCreateNanos(System.nanoTime() - cacheStart);
        }
    }

    if (TransformationMetrics.isEnabled()) {
        TransformationMetrics.addRuleLoopNanos(System.nanoTime() - loopStart);
    }
}
```

### 4. Instrument transformParallel (lines 633-640)

```java
// Time future creation
long futureStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
List<CompletableFuture<Void>> futures = chunks.stream()
        .map(chunk -> CompletableFuture.runAsync(() -> transformChunk(chunk), exec))
        .collect(Collectors.toList());
if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addFutureCreationNanos(System.nanoTime() - futureStart);
}

// Time parallel wait
long waitStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
if (TransformationMetrics.isEnabled()) {
    TransformationMetrics.addParallelWaitNanos(System.nanoTime() - waitStart);
}
```

## Expected Report Output

After Phase 2 changes:

```
=== TIMING BREAKDOWN (ALL COMPONENTS) ===
  Greedy rule execution:          1,200 ms (15.0%)
  equivalent() total:               400 ms ( 5.0%)
  createTarget():                   300 ms ( 3.8%)
  Model iteration:                  500 ms ( 6.3%)
  Staging commit:                   800 ms (10.0%)
  Rule matching:                    200 ms ( 2.5%)   <-- NEW
  Rule loop overhead:             2,500 ms (31.3%)   <-- NEW (likely bottleneck)
  Chunk processing:               1,000 ms (12.5%)   <-- NEW
  Cache getOrCreate:                300 ms ( 3.8%)   <-- NEW
  Future creation:                   50 ms ( 0.6%)   <-- NEW
  Parallel wait:                    100 ms ( 1.3%)   <-- NEW
  ----------------------------------------
  ACCOUNTED:                      7,350 ms (91.9%)
  UNACCOUNTED:                      650 ms ( 8.1%)   <-- Target: <10%
```

## Success Criteria

1. UNACCOUNTED percentage reduced from ~70% to <10%
2. Rule loop overhead clearly visible as major contributor
3. All 500+ existing tests pass
4. No measurable performance overhead when metrics disabled
