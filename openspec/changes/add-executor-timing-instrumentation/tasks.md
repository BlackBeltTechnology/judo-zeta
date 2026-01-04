# Tasks: Add Executor Timing Instrumentation

## 1. Instrument TransformationExecutor.transform()

- [x] **1.1** Add `TransformationMetrics.startTransformation()` at start of transform()
- [x] **1.2** Add `TransformationMetrics.endTransformation()` in finally block
- [x] **1.3** Time element collection phase (source element gathering)

## 2. Instrument Staging and Parallel Execution

- [x] **2.1** Time `commitStagedElements()` call in `transformWithStaging()`
- [x] **2.2** Time list conversion in `transformParallel()` (ArrayList creation)
- [x] **2.3** Time chunk partitioning in `transformParallel()`

## 3. Validation

- [ ] **3.1** Run transformation with metrics enabled to verify output
- [ ] **3.2** Verify UNACCOUNTED percentage is reduced
- [x] **3.3** Run all transformation-core tests (500+) - **PASSED: 500 tests, 0 failures**
- [ ] **3.4** Verify no performance overhead when metrics disabled
