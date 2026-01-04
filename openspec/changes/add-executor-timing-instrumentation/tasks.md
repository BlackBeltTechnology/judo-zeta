# Tasks: Add Executor Timing Instrumentation

## Phase 1: Use Existing Metrics (Completed)

### 1. Instrument TransformationExecutor.transform()

- [x] **1.1** Add `TransformationMetrics.startTransformation()` at start of transform()
- [x] **1.2** Add `TransformationMetrics.endTransformation()` in finally block
- [x] **1.3** Time element collection phase (source element gathering)

### 2. Instrument Staging and Parallel Execution

- [x] **2.1** Time `commitStagedElements()` call in `transformWithStaging()`
- [x] **2.2** Time list conversion in `transformParallel()` (ArrayList creation)
- [x] **2.3** Time chunk partitioning in `transformParallel()`

## Phase 2: Add New Metrics for Remaining 70%

### 3. Add New Metrics to TransformationMetrics.java

- [x] **3.1** Add `ruleMatchingNanos` counter and `addRuleMatchingNanos()` method
- [x] **3.2** Add `ruleLoopNanos` counter and `addRuleLoopNanos()` method
- [x] **3.3** Add `chunkProcessingNanos` counter and `addChunkProcessingNanos()` method
- [x] **3.4** Add `futureCreationNanos` counter and `addFutureCreationNanos()` method
- [x] **3.5** Add `parallelWaitNanos` counter and `addParallelWaitNanos()` method
- [x] **3.6** Add `cacheGetOrCreateNanos` counter and `addCacheGetOrCreateNanos()` method
- [x] **3.7** Update `reset()` to clear new counters
- [x] **3.8** Update `generateReport()` to include new metrics

### 4. Instrument Executor Methods

- [x] **4.1** Instrument `transformChunk()` - time entire chunk processing
- [x] **4.2** Instrument `executeEagerRulesFor()` - time `getRulesForSource()` lookup
- [x] **4.3** Instrument `executeEagerRulesFor()` - time rule iteration loop
- [x] **4.4** Instrument `executeEagerRulesFor()` - time cache `getOrCreate()` operation
- [x] **4.5** Instrument `transformParallel()` - time future creation
- [x] **4.6** Instrument `transformParallel()` - time parallel wait

## Validation

- [x] **V.1** Run all transformation-core tests (500+) - **Phase 1 PASSED**
- [ ] **V.2** Run transformation with metrics enabled to verify Phase 2 output
- [ ] **V.3** Verify UNACCOUNTED percentage is reduced to <10%
- [ ] **V.4** Verify no performance overhead when metrics disabled
- [x] **V.5** Run all transformation-core tests after Phase 2 - **PASSED: 500 tests, 0 failures**
