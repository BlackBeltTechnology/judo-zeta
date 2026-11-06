# performance-benchmark-harness Specification

## Purpose
TBD

## Requirements

### Requirement: Execute benchmarks across configuration matrix
The benchmark harness SHALL execute transformations across a parameterized matrix covering: model size (1K, 10K, 50K, 100K elements), parallel mode (true/false), execution strategy (ELEMENT_BY_ELEMENT, RULE_BY_RULE), chunk size (10, 100, 1000), and thread count (1, 4, 8, 16).

#### Scenario: Small model configuration sweep
- **WHEN** benchmark runs with 1K elements and all combinations of parallel=true/false, chunkSize={10,100}
- **THEN** harness executes 4 benchmark iterations and records metrics for each

### Requirement: Perform warmup iterations before measurement
The benchmark harness SHALL execute 3 warmup iterations with the full transformation before starting measured runs to allow JVM JIT compilation to reach steady state.

#### Scenario: Warmup then measure
- **WHEN** benchmark runs with measured iteration count of 5
- **THEN** harness first executes 3 warmup transformations (discarded)
- **AND** then executes 5 measured transformations with metrics collection

### Requirement: Collect comprehensive metrics per run
The benchmark harness SHALL collect for each measured iteration: total transformation time (nanoseconds), throughput (elements/second), heap memory usage (before/after), and `TransformationMetrics` data (cache hit rate, guard rejections, lock wait time, on-demand executions).

#### Scenario: Metrics collection
- **WHEN** a measured benchmark iteration completes
- **THEN** harness records total time, throughput, heap delta
- **AND** harness retrieves and records `TransformationMetrics.getReport()`

### Requirement: Report median and interquartile range
The benchmark harness SHALL calculate and report median (p50) and interquartile range (p25-p75) across measured iterations for each metric.

#### Scenario: Statistical summary
- **WHEN** 5 measured iterations complete with times [100, 105, 102, 110, 103] ms
- **THEN** harness reports median=103ms and IQR=[101.5, 106.5]ms

### Requirement: Support performance threshold assertions
The benchmark harness SHALL support configurable performance thresholds and fail the test if measured median exceeds the threshold.

#### Scenario: Threshold assertion
- **WHEN** benchmark has threshold maxTimeMs=500 and measured median is 550ms
- **THEN** benchmark test fails with assertion error indicating threshold exceeded

### Requirement: Tag benchmark tests for opt-in execution
All benchmark test methods SHALL be tagged with `@Tag("performance")` so they don't execute during normal `mvn test` runs.

#### Scenario: Opt-in benchmark execution
- **WHEN** developer runs `mvn test` without profile
- **THEN** benchmark tests are skipped
- **WHEN** developer runs `mvn test -Pperformance`
- **THEN** benchmark tests execute
