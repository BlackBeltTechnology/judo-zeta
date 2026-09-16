## Context

Zeta already has `TransformationMetrics` for collecting timing/count data and several ad-hoc performance tests (`CacheLookupBenchmarkTest`, `ParallelStressTest`, `GreedyRulePerformanceTest`, `TransformationDeterminismTest$LargeModelTests`). However:

1. Tests use simple `EClass`/`EAttribute` structures, not realistic PSM model complexity
2. No systematic exploration of the configuration space (chunkSize, parallelThreshold, executionStrategy)
3. No reproducible baseline numbers or regression detection
4. Tests are scattered and not organized as a coherent benchmark suite

Production PSM→ASM transformations involve 10K-50K elements with deep cross-references, guard conditions, and lazy rule triggering patterns that simple tests don't exercise.

## Goals / Non-Goals

**Goals:**
- Generate synthetic models that mirror real PSM complexity (packages, classes, attributes, operations, references, guards)
- Parameterized benchmark covering: size (1K-100K), parallel mode, execution strategies, chunk sizes, thread counts
- Collect and report metrics: total time, throughput (elements/sec), memory, cache hit rates, lock contention
- Establish baseline numbers and regression thresholds
- CI integration for performance drift detection

**Non-Goals:**
- Profiling hotspots at the JVM level (use async-profiler/JFR separately)
- Testing with real customer models (keep it synthetic and reproducible)
- Validating semantic correctness (existing tests cover that)

## Decisions

### Decision 1: JUnit 5 Parameterized Tests vs JMH

**Chosen:** JUnit 5 `@ParameterizedTest` with custom test runner.

**Rationale:**
- Leverages existing test infrastructure and CI integration
- Easier to read/maintain for Java developers
- JMH is better for micro-benchmarking single methods, but we need end-to-end transformation times
- Can still use JMH later for method-level profiling if needed

**Alternative:** JMH benchmarks — rejected for end-to-end complexity; JMH's warmup model doesn't match transformation phases well.

### Decision 2: Synthetic Model Generation Strategy

**Chosen:** Builder pattern with configurable parameters (depth, fanOut, typeDistribution, crossReferencePattern).

**Rationale:**
- Mirrors how real PSM models are structured (packages contain classes, classes have attributes/references/operations)
- Configurable to generate different complexity scenarios (wide/shallow, narrow/deep, sparse/dense references)
- Deterministic output (fixed random seed) for reproducible benchmarks

**Alternative:** Loading real PSM XMI files — rejected because they're not portable, require external data, and can't scale to arbitrary sizes.

### Decision 3: Metrics Output Format

**Chosen:** CSV files + console summary.

**Rationale:**
- CSV for tooling (spreadsheets, plotting scripts, CI trend analysis)
- Console summary for human readability during test runs
- Simple, no external dependencies

**Alternative:** InfluxDB/Prometheus — rejected for complexity; CI logs are sufficient for regression detection.

### Decision 4: Warmup and Measurement Strategy

**Chosen:** 3 warmup iterations, 5 measured iterations, report median and IQR.

**Rationale:**
- JVM JIT compilation needs warmup to reach steady state
- Median is more robust to outliers than mean
- Interquartile range (IQR) shows run-to-run variance
- Matches standard benchmarking practices

## Risks / Trade-offs

- **[Test execution time]** → Full benchmark matrix could take 30+ minutes → Mitigation: Make benchmark tests opt-in (`@Tag("performance")`), not run by default `mvn test`
- **[Memory measurement accuracy]** → Java heap sizing affects results → Document required JVM args (`-Xmx4G`), report heap usage not absolute memory
- **[CI flakiness]** → Shared CI runners have variable load → Use percentile-based thresholds, allow some variance
- **[Model realism gap]** → Synthetic models may not trigger all real-world bottlenecks → Continuously refine generator patterns based on production profiling
- **[Maintenance overhead]** → Benchmark suite needs updates when framework changes → Keep benchmark rules simple, focus on transformation patterns not edge cases
