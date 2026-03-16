# ecore-performance-benchmarks Specification

## Purpose

Performance benchmark tests using realistic Ecore models to measure transformation throughput, memory usage, and scalability.

## ADDED Requirements

### Requirement: Tiered performance test levels

The performance benchmark framework SHALL support multiple test levels with different model sizes.

#### Scenario: Small model benchmark

- **WHEN** performance test runs with level=SMALL (20 classes)
- **THEN** transformation completes in <1 second
- **AND** test is included in standard CI runs

#### Scenario: Medium model benchmark

- **WHEN** performance test runs with level=MEDIUM (100 classes)
- **THEN** transformation completes in <10 seconds
- **AND** test is tagged with @Tag("performance")

#### Scenario: Large model benchmark

- **WHEN** performance test runs with level=LARGE (500 classes)
- **THEN** transformation completes in <60 seconds
- **AND** test is excluded from standard CI runs

---

### Requirement: Throughput measurement

The performance benchmark framework SHALL measure and report transformation throughput.

#### Scenario: Measure elements per second

- **WHEN** transformation completes
- **THEN** throughput is calculated as totalElements / durationSeconds
- **AND** throughput is logged in format "X elements/second"

#### Scenario: Compare sequential vs parallel throughput

- **WHEN** benchmark runs both sequential and parallel transformations
- **THEN** both throughputs are reported
- **AND** speedup ratio is calculated (parallel / sequential)

---

### Requirement: Memory usage tracking

The performance benchmark framework SHALL track memory usage during transformations.

#### Scenario: Track peak memory usage

- **WHEN** transformation runs
- **THEN** peak memory usage is recorded
- **AND** memory delta (after - before) is reported

#### Scenario: Detect memory leak indicators

- **WHEN** transformation completes
- **AND** resource cleanup is performed
- **THEN** memory is reclaimed
- **AND** retained memory is within acceptable bounds

---

### Requirement: Scalability measurement

The performance benchmark framework SHALL measure how transformation time scales with model size.

#### Scenario: Linear scaling verification

- **WHEN** benchmark runs with 100, 200, 400 class models
- **THEN** scaling ratio is approximately linear
- **AND** doubling model size less than doubles transformation time

#### Scenario: Identify scaling bottlenecks

- **WHEN** benchmark runs with progressively larger models
- **AND** scaling becomes super-linear
- **THEN** bottleneck is identified and logged
- **AND** test report suggests investigation

---

### Requirement: Warmup and measurement separation

The performance benchmark framework SHALL separate warmup runs from measurement runs.

#### Scenario: Warmup before measurement

- **WHEN** benchmark executes
- **THEN** warmup run is performed first (not measured)
- **AND** measurement run follows warmup
- **AND** only measurement run is reported

#### Scenario: Multiple measurement iterations

- **WHEN** benchmark is configured for multiple iterations
- **THEN** measurement runs multiple times
- **AND** average, min, max are reported

---

### Requirement: Benchmark result reporting

The performance benchmark framework SHALL produce structured benchmark reports.

#### Scenario: Log benchmark results

- **WHEN** benchmark completes
- **THEN** results are logged in structured format:
  - Model size (classes, attributes, references)
  - Duration (ms)
  - Throughput (elements/second)
  - Memory usage (MB)
  - Speedup ratio (if parallel comparison)

#### Scenario: Compare with baseline

- **WHEN** benchmark runs and baseline exists
- **THEN** current results are compared to baseline
- **AND** regression warning is logged if >10% slower

---

### Requirement: Reproducible benchmark conditions

The performance benchmark framework SHALL ensure reproducible benchmark conditions.

#### Scenario: Fixed seed for reproducibility

- **WHEN** benchmark runs
- **THEN** same seed is used for model generation
- **AND** same model is used for sequential and parallel comparisons

#### Scenario: Clean state between runs

- **WHEN** multiple benchmark runs execute
- **THEN** each run starts with clean ResourceSet
- **AND** no state carries over between runs

---

### Requirement: Parallel execution benchmarking

The performance benchmark framework SHALL specifically benchmark parallel transformation execution.

#### Scenario: Measure parallel speedup

- **WHEN** parallel transformation runs with threshold=1 (force parallel)
- **THEN** parallel throughput is measured
- **AND** speedup vs sequential is calculated

#### Scenario: Thread utilization tracking

- **WHEN** parallel transformation runs
- **THEN** number of threads used is recorded
- **AND** thread efficiency (actual speedup / ideal speedup) is calculated

---

### Requirement: Benchmark test tagging

The performance benchmark framework SHALL use JUnit 5 tags for test filtering.

#### Scenario: Small tests untagged

- **WHEN** benchmark test is SMALL level
- **THEN** test has no performance tag
- **AND** runs in standard CI

#### Scenario: Medium tests tagged

- **WHEN** benchmark test is MEDIUM level
- **THEN** test has @Tag("performance")
- **AND** excluded from standard CI with -Dtest.excludeTags=performance

#### Scenario: Large tests tagged

- **WHEN** benchmark test is LARGE level
- **THEN** test has @Tag("performance") and @Tag("slow")
- **AND** only runs in manual or scheduled CI
