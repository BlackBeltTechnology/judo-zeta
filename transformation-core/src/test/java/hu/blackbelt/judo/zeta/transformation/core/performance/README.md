# Zeta Performance Benchmarks

This directory contains performance benchmarks for the Zeta transformation framework.

## Running Benchmarks

### Run all benchmarks
```bash
mvn test -Pperformance -Dtest=ZetaPerformanceBenchmark
```

### Run specific benchmark suite
```bash
# Small model benchmarks only
mvn test -Pperformance -Dtest=ZetaPerformanceBenchmark\$SmallModelBenchmarks

# Chunk size sensitivity
mvn test -Pperformance -Dtest=ZetaPerformanceBenchmark\$ChunkSizeBenchmarks
```

## Interpreting Results

### Console Output
```
=== Results: small-parallel-EBE ===
Total Time: median=245ms, mean=248.12ms, IQR=[240ms, 252ms]
Throughput: 4081/sec
Memory: median=12MB delta
Cache hit rate: 94.52%
```

| Metric | Description |
|--------|-------------|
| **Median time** | p50 time across 5 measured iterations (most representative) |
| **IQR** | Interquartile range [p25, p75] - shows run-to-run variance |
| **Throughput** | Elements processed per second |
| **Memory delta** | Heap memory used during transformation |

### CSV Output Files
Results are written to `target/benchmark-results/`:
- `{benchmarkId}-raw.csv` - Raw data per iteration
- `{benchmarkId}-summary.csv` - Aggregated statistics for comparison

## Detecting Regressions

### Current Run as Baseline
After running benchmarks, current results are logged as potential baseline:
```
Baseline not configured. Current results可以作为基准: 245ms median
```

### Comparing Against Baseline
Copy a summary CSV to create a baseline:
```bash
cp target/benchmark-results/small-parallel-EBE-summary.csv target/benchmark-results/baseline.csv
```

Future runs will compare against this baseline and fail if regression exceeds 20%.

## Configuration

### Benchmark Parameters

| Parameter | Values | Description |
|-----------|--------|-------------|
| Model size | 1K, 10K, 50K, 100K | Number of generated elements |
| Parallel | true/false | Parallel vs sequential execution |
| Strategy | ELEMENT_BY_ELEMENT, RULE_BY_RULE | Eager rule execution order |
| Chunk size | 10, 100, 500, 1000 | Work chunk size for parallel processing |
| Thread count | 1, 2, 4, 8 | Number of parallel threads |

### Adding New Benchmarks

Create a nested test class in `ZetaPerformanceBenchmark`:

```java
@Nested
@DisplayName("Large Model (50K elements)")
class LargeModelBenchmarks {
    @Test
    @DisplayName("Parallel, large model")
    void largeParallel() {
        BenchmarkConfig config = new BenchmarkConfig(50_000, true,
            ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 8);
        runBenchmark("large-parallel", config);
    }
}
```

## JVM Configuration

For consistent results, use fixed heap size:
```bash
mvn test -Pperformance -Dtest=ZetaPerformanceBenchmark -DargLine="-Xmx4G"
```

## Known Limitations

- Benchmarks are excluded from normal `mvn test` runs (use `-Pperformance`)
- Test execution time: full suite ~15-30 minutes
- Results vary by hardware; use same machine for trend analysis
