## Why

Zeta transformations scale to tens of thousands of elements in production, but we lack systematic performance testing to identify bottlenecks before they impact users. Current performance tests are ad-hoc and don't cover realistic model complexity or configuration variations. We need a reproducible benchmark suite to validate optimizations and detect regressions.

## What Changes

- Add `SyntheticModelGenerator` for creating configurable test models (packages, classes, attributes, references, operations)
- Add `ZetaPerformanceBenchmark` parameterized test suite with configurable matrix (size, parallel/sequential, chunk size, execution strategy)
- Add benchmark result reporting (CSV output, metrics aggregation, threshold assertions)
- Add CI integration for performance regression detection
- Add memory profiling instrumentation (heap measurement, leak detection)

## Capabilities

### New Capabilities
- `synthetic-model-generation`: Generate realistic PSM-style models with configurable complexity (depth, fan-out, type distribution, cross-reference patterns)
- `performance-benchmark-harness`: Parameterized test runner executing transformations across configuration matrix with warmup, measured runs, metrics collection
- `benchmark-reporting`: Export benchmark results to CSV, aggregate statistics, assert performance thresholds, integrate with CI/CD

### Modified Capabilities

## Impact

- New test package: `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/performance/`
- New generator class: `SyntheticModelGenerator` in test package
- New benchmark suite: `ZetaPerformanceBenchmark` with JUnit 5 parameterized tests
- Optional: JMH (Java Microbenchmark Harness) integration for fine-grained method-level benchmarks
- No production code changes — purely testing infrastructure
