## ADDED Requirements

### Requirement: Export benchmark results to CSV
The benchmark reporter SHALL export all collected metrics to CSV files with columns: configuration_id, model_size, parallel, execution_strategy, chunk_size, thread_count, iteration_id, total_time_ms, throughput_per_sec, heap_mb, cache_hit_rate, guard_rejections, lock_wait_ns.

#### Scenario: CSV export
- **WHEN** benchmark completes with 10 measured iterations
- **THEN** reporter creates CSV file with 10 data rows
- **AND** CSV file is written to `target/benchmark-results/` directory

### Requirement: Aggregate statistics across iterations
The benchmark reporter SHALL calculate and export aggregate statistics: median, mean, min, max, standard deviation for each metric across all iterations of a given configuration.

#### Scenario: Summary statistics
- **WHEN** benchmark completes with 5 iterations
- **THEN** reporter exports summary row with p50, p25, p75, mean, min, max, stddev for total_time_ms

### Requirement: Console summary with human-readable output
The benchmark reporter SHALL print a formatted table to console showing key metrics (median time, throughput, memory) for each configuration.

#### Scenario: Console output
- **WHEN** benchmark test completes
- **THEN** reporter prints table with rows for each configuration and columns for size, mode, median_time, throughput

### Requirement: Support baseline comparison
The benchmark reporter SHALL support loading a baseline CSV file and calculating percentage difference from baseline for each metric.

#### Scenario: Baseline comparison
- **WHEN** current run results are compared with baseline file
- **AND** baseline median time was 100ms and current is 110ms
- **THEN** reporter indicates "+10% degradation from baseline"
- **WHEN** degradation exceeds configurable threshold (e.g., 20%)
- **THEN** test fails with regression warning

### Requirement: Detect performance regressions in CI
The benchmark reporter SHALL provide CI integration mode that fails the build if performance regresses beyond acceptable thresholds.

#### Scenario: CI regression detection
- **WHEN** benchmark runs in CI with maxRegression=15%
- **AND** current median time is 20% worse than baseline
- **THEN** build fails with "Performance regression detected: 20% exceeds 15% threshold"
