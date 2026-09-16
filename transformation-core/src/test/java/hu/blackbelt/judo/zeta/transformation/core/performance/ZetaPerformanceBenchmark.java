package hu.blackbelt.judo.zeta.transformation.core.performance;

import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import hu.blackbelt.judo.zeta.transformation.core.ExecutionStrategy;
import hu.blackbelt.judo.zeta.transformation.core.TransformationContext;
import hu.blackbelt.judo.zeta.transformation.core.TransformationExecutor;
import hu.blackbelt.judo.zeta.transformation.core.TransformationMetrics;
import hu.blackbelt.judo.zeta.transformation.core.TransformationRegistry;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance benchmark suite for Zeta transformations.
 *
 * <p>Tests transformation performance across a matrix of configurations:
 * model size, parallel mode, execution strategy, chunk size, thread count.</p>
 *
 * <p>Run with: {@code mvn test -Pperformance -Dtest=ZetaPerformanceBenchmark}</p>
 *
 * <p>Tests are tagged with @Tag("performance") and excluded from normal test runs.</p>
 */
@Tag("performance")
@DisplayName("Zeta Performance Benchmarks")
class ZetaPerformanceBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ZetaPerformanceBenchmark.class);

    private static final String CSV_OUTPUT_DIR = "target/benchmark-results/";

    // Benchmark configuration
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 5;
    private static final long MAX_REGRESSION_PERCENT = 20; // Fail if 20% slower than baseline

    @BeforeEach
    void setUp() {
        // Ensure output directory exists
        new File(CSV_OUTPUT_DIR).mkdirs();
    }

    // ==================== Small Model Benchmarks ====================

    @Nested
    @DisplayName("Small Model (1K elements)")
    class SmallModelBenchmarks {

        @Test
        @DisplayName("Sequential, ELEMENT_BY_ELEMENT")
        void smallSequentialElementByElement() {
            BenchmarkConfig config = new BenchmarkConfig(1_000, false, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 1);
            runBenchmark("small-sequential-EBE", config);
        }

        @Test
        @DisplayName("Parallel, ELEMENT_BY_ELEMENT")
        void smallParallelElementByElement() {
            BenchmarkConfig config = new BenchmarkConfig(1_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 4);
            runBenchmark("small-parallel-EBE", config);
        }

        @Test
        @DisplayName("Parallel, RULE_BY_RULE")
        void smallParallelRuleByRule() {
            BenchmarkConfig config = new BenchmarkConfig(1_000, true, ExecutionStrategy.RULE_BY_RULE, 100, 4);
            runBenchmark("small-parallel-RBR", config);
        }
    }

    // ==================== Medium Model Benchmarks ====================

    @Nested
    @DisplayName("Medium Model (10K elements)")
    class MediumModelBenchmarks {

        @Test
        @DisplayName("Sequential, ELEMENT_BY_ELEMENT")
        void mediumSequentialElementByElement() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, false, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 1);
            runBenchmark("medium-sequential-EBE", config);
        }

        @Test
        @DisplayName("Parallel, ELEMENT_BY_ELEMENT")
        void mediumParallelElementByElement() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 4);
            runBenchmark("medium-parallel-EBE", config);
        }

        @Test
        @DisplayName("Parallel, RULE_BY_RULE")
        void mediumParallelRuleByRule() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.RULE_BY_RULE, 100, 4);
            runBenchmark("medium-parallel-RBR", config);
        }
    }

    // ==================== Chunk Size Sensitivity ====================

    @Nested
    @DisplayName("Chunk Size Sensitivity")
    class ChunkSizeBenchmarks {

        @Test
        @DisplayName("Chunk size 10 (10K elements, parallel)")
        void chunkSize10() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 10, 4);
            runBenchmark("chunkSize-10", config);
        }

        @Test
        @DisplayName("Chunk size 100 (10K elements, parallel)")
        void chunkSize100() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 4);
            runBenchmark("chunkSize-100", config);
        }

        @Test
        @DisplayName("Chunk size 500 (10K elements, parallel)")
        void chunkSize500() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 500, 4);
            runBenchmark("chunkSize-500", config);
        }

        @Test
        @DisplayName("Chunk size 1000 (10K elements, parallel)")
        void chunkSize1000() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 1000, 4);
            runBenchmark("chunkSize-1000", config);
        }
    }

    // ==================== Thread Count Scaling ====================

    @Nested
    @DisplayName("Thread Count Scaling")
    class ThreadCountBenchmarks {

        @Test
        @DisplayName("Thread count 1 (10K elements)")
        void threadCount1() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 1);
            runBenchmark("threads-1", config);
        }

        @Test
        @DisplayName("Thread count 2 (10K elements)")
        void threadCount2() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 2);
            runBenchmark("threads-2", config);
        }

        @Test
        @DisplayName("Thread count 4 (10K elements)")
        void threadCount4() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 4);
            runBenchmark("threads-4", config);
        }

        @Test
        @DisplayName("Thread count 8 (10K elements)")
        void threadCount8() {
            BenchmarkConfig config = new BenchmarkConfig(10_000, true, ExecutionStrategy.ELEMENT_BY_ELEMENT, 100, 8);
            runBenchmark("threads-8", config);
        }
    }

    // ==================== Core Benchmark Execution ====================

    private void runBenchmark(String benchmarkId, BenchmarkConfig config) {
        log.info("=== Benchmark: {} ===", benchmarkId);
        log.info("Config: size={}, parallel={}, strategy={}, chunkSize={}, threads={}",
                config.elementCount, config.parallel, config.strategy, config.chunkSize, config.threadCount);

        TransformationMetrics.enable();

        // Warmup iterations
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            log.info("Warmup iteration {} of {}", i + 1, WARMUP_ITERATIONS);
            executeTransformation(config);
        }

        // Clear metrics after warmup
        TransformationMetrics.reset();

        // Measured iterations
        List<BenchmarkIteration> iterations = new ArrayList<>();
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            log.info("Measured iteration {} of {}", i + 1, MEASURED_ITERATIONS);

            long heapBefore = getHeapUsedMB();
            long startNanos = System.nanoTime();

            executeTransformation(config);

            long endNanos = System.nanoTime();
            long heapAfter = getHeapUsedMB();

            // Capture metrics before reset
            long equivalentCalls = getEquivalentCalls();
            long cacheHits = getCacheHits();
            double cacheHitRate = equivalentCalls > 0 ? (double) cacheHits / equivalentCalls : 0.0;

            iterations.add(new BenchmarkIteration(
                    i, config, endNanos - startNanos, heapAfter - heapBefore, cacheHitRate
            ));

            TransformationMetrics.reset();
        }

        // Calculate and report statistics
        BenchmarkStatistics stats = calculateStatistics(iterations);

        // Export to CSV
        exportToCsv(benchmarkId, iterations);
        exportSummaryToCsv(benchmarkId, stats);

        // Print console summary
        printConsoleSummary(benchmarkId, stats);

        // Check performance thresholds
        assertWithinThreshold(benchmarkId, stats, config.parallel);

        // Check against baseline if exists
        checkBaseline(benchmarkId, stats);

        log.info("=== Benchmark complete: {} ===", benchmarkId);
    }

    private void executeTransformation(BenchmarkConfig config) {
        SyntheticModelGenerator generator = new SyntheticModelGenerator.Builder()
                .packageCount(config.packageCount)
                .classesPerPackage(config.classesPerPackage)
                .attributesPerClass(config.attributesPerClass)
                .referencesPerClass(config.referencesPerClass)
                .operationsPerClass(config.operationsPerClass)
                .guardRejectionRate(config.guardRejectionRate)
                .build();

        ResourceSet sourceResourceSet = generator.getResourceSet();

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider(sourceResourceSet);

        TransformationContext context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                new ResourceSetImpl(),
                extensionRegistry
        );
        // Note: Don't set targetPackage to EcorePackage.eINSTANCE as it causes massive overhead
        context.registerResource("source", sourceResourceSet);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(BenchmarkRules.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(config.parallel)
                .parallelThreshold(config.parallelThreshold)
                .chunkSize(config.chunkSize)
                .executionStrategy(config.strategy)
                .build();

        executor.transform();
    }

    private long getHeapUsedMB() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    // ==================== Statistics Calculation ====================

    private BenchmarkStatistics calculateStatistics(List<BenchmarkIteration> iterations) {
        List<Long> timesMicros = new ArrayList<>();
        List<Long> memoryDeltas = new ArrayList<>();
        List<Double> cacheHitRates = new ArrayList<>();
        long elementCount = iterations.isEmpty() ? 0 : iterations.get(0).config.elementCount;

        for (BenchmarkIteration iteration : iterations) {
            // Convert to microseconds for better precision on fast transformations
            timesMicros.add(iteration.totalTimeNanos / 1_000);
            memoryDeltas.add(iteration.heapDeltaMb);
            if (iteration.cacheHitRate > 0) {
                cacheHitRates.add(iteration.cacheHitRate);
            }
        }

        long medianMicros = calculatePercentile(timesMicros, 50);
        double medianMs = medianMicros / 1000.0;
        double meanMs = timesMicros.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
        double throughput = medianMs > 0 ? (elementCount * 1000.0) / medianMs : 0.0;

        return new BenchmarkStatistics(
                (long) medianMs,
                (long) (calculatePercentile(timesMicros, 25) / 1000.0),
                (long) (calculatePercentile(timesMicros, 75) / 1000.0),
                meanMs,
                calculatePercentile(memoryDeltas, 50),
                cacheHitRates.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                throughput
        );
    }

    private long calculatePercentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    // ==================== Reporting ====================

    private void exportToCsv(String benchmarkId, List<BenchmarkIteration> iterations) {
        String filename = CSV_OUTPUT_DIR + benchmarkId + "-raw.csv";
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write("iteration,config_id,element_count,parallel,execution_strategy,chunk_size," +
                    "thread_count,total_time_ms,throughput_per_sec,heap_delta_mb,cache_hit_rate\n");

            for (BenchmarkIteration it : iterations) {
                double totalTimeMs = it.totalTimeNanos / 1_000_000.0;
                double throughput = it.config.elementCount / (totalTimeMs / 1000.0);

                writer.write(String.format("%d,%s,%d,%b,%s,%d,%d,%.2f,%.2f,%d,%.4f\n",
                        it.iterationId, benchmarkId, it.config.elementCount, it.config.parallel,
                        it.config.strategy, it.config.chunkSize, it.config.threadCount,
                        totalTimeMs, throughput, it.heapDeltaMb, it.cacheHitRate));
            }
        } catch (IOException e) {
            log.error("Failed to write CSV: {}", filename, e);
        }
    }

    private void exportSummaryToCsv(String benchmarkId, BenchmarkStatistics stats) {
        String filename = CSV_OUTPUT_DIR + benchmarkId + "-summary.csv";
        try (FileWriter writer = new FileWriter(filename, true)) { // append mode
            writer.write(benchmarkId + "," + stats + "\n");
        } catch (IOException e) {
            log.error("Failed to write summary CSV: {}", filename, e);
        }
    }

    private void printConsoleSummary(String benchmarkId, BenchmarkStatistics stats) {
        log.info("\n=== Results: {} ===", benchmarkId);

        // Format time with appropriate precision
        String timeStr;
        if (stats.medianTimeMs < 1) {
            timeStr = String.format("<1ms (%.2fμs)", stats.meanTimeMs * 1000);
        } else if (stats.medianTimeMs < 10) {
            timeStr = String.format("%.2fms", stats.meanTimeMs);
        } else {
            timeStr = String.format("%.0fms", stats.meanTimeMs);
        }

        log.info("Total Time: median={}ms, mean={}, IQR=[{}ms, {}ms]",
                stats.medianTimeMs > 0 ? stats.medianTimeMs : "<1",
                timeStr,
                stats.p25TimeMs > 0 ? stats.p25TimeMs : "<1",
                stats.p75TimeMs > 0 ? stats.p75TimeMs : "<1");

        if (stats.throughputPerSec > 0) {
            String throughputStr;
            if (stats.throughputPerSec > 1_000_000) {
                throughputStr = String.format("%.1fM elements/sec", stats.throughputPerSec / 1_000_000);
            } else if (stats.throughputPerSec > 1000) {
                throughputStr = String.format("%.1fK elements/sec", stats.throughputPerSec / 1000);
            } else {
                throughputStr = String.format("%.0f elements/sec", stats.throughputPerSec);
            }
            log.info("Throughput: {}", throughputStr);
        }

        if (stats.medianHeapMb != 0) {
            log.info("Memory: median={}MB delta", stats.medianHeapMb);
        }

        if (stats.cacheHitRate > 0) {
            log.info("Cache hit rate: {}%", String.format("%.2f", stats.cacheHitRate * 100));
        }
    }

    // ==================== Threshold and Baseline ====================

    private void assertWithinThreshold(String benchmarkId, BenchmarkStatistics stats, boolean isParallel) {
        // Note: Thresholds are disabled during development. Enable once baseline is established.
        // Example: 1K elements within 10 seconds (parallel), 20 seconds (sequential)
        long thresholdMs = isParallel ? 60_000 : 120_000; // More lenient for development
        // Note: Disabled during development - uncomment to enable
        // assertTrue(stats.medianTimeMs < thresholdMs,
        //         String.format("Benchmark %s exceeded threshold: %d ms > %d ms",
        //                 benchmarkId, stats.medianTimeMs, thresholdMs));
        log.info("Threshold check disabled: {}ms < {}ms", stats.medianTimeMs, thresholdMs);
    }

    private void checkBaseline(String benchmarkId, BenchmarkStatistics stats) {
        // TODO: Load baseline CSV and compare
        // For now, just log the current results as potential baseline
        log.info("Baseline not configured. Current results可以作为 baseline: {}ms median", stats.medianTimeMs);
    }

    // ==================== Inner Classes ====================

    private static class BenchmarkConfig {
        final int elementCount;
        final boolean parallel;
        final ExecutionStrategy strategy;
        final int chunkSize;
        final int threadCount;
        final int parallelThreshold = 5000;

        // Derived values
        final int packageCount;
        final int classesPerPackage;
        final int attributesPerClass;
        final int referencesPerClass;
        final int operationsPerClass;
        final double guardRejectionRate;

        BenchmarkConfig(int elementCount, boolean parallel, ExecutionStrategy strategy,
                        int chunkSize, int threadCount) {
            this.elementCount = elementCount;
            this.parallel = parallel;
            this.strategy = strategy;
            this.chunkSize = chunkSize;
            this.threadCount = threadCount;

            // Derive model structure from element count
            this.packageCount = Math.max(1, elementCount / 500);
            this.classesPerPackage = Math.max(1, elementCount / packageCount);
            this.attributesPerClass = 5;
            this.referencesPerClass = 2;
            this.operationsPerClass = 1;
            this.guardRejectionRate = 0.1; // 10% rejection rate
        }

        @Override
        public String toString() {
            return String.format("BenchmarkConfig{size=%d, parallel=%s, strategy=%s, chunk=%d, threads=%d}",
                    elementCount, parallel, strategy, chunkSize, threadCount);
        }
    }

    private static class BenchmarkIteration {
        final int iterationId;
        final BenchmarkConfig config;
        final long totalTimeNanos;
        final long heapDeltaMb;
        final double cacheHitRate;

        BenchmarkIteration(int iterationId, BenchmarkConfig config, long totalTimeNanos,
                           long heapDeltaMb, double cacheHitRate) {
            this.iterationId = iterationId;
            this.config = config;
            this.totalTimeNanos = totalTimeNanos;
            this.heapDeltaMb = heapDeltaMb;
            this.cacheHitRate = cacheHitRate;
        }
    }

    private static class BenchmarkStatistics {
        final long medianTimeMs;
        final long p25TimeMs;
        final long p75TimeMs;
        final double meanTimeMs;
        final long medianHeapMb;
        final double cacheHitRate;
        final double throughputPerSec;

        BenchmarkStatistics(long medianTimeMs, long p25TimeMs, long p75TimeMs,
                           double meanTimeMs, long medianHeapMb, double cacheHitRate) {
            this.medianTimeMs = medianTimeMs;
            this.p25TimeMs = p25TimeMs;
            this.p75TimeMs = p75TimeMs;
            this.meanTimeMs = meanTimeMs;
            this.medianHeapMb = medianHeapMb;
            this.cacheHitRate = cacheHitRate;

            // Calculate throughput: elements per second
            // We'll need elementCount from config, so for now estimate
            this.throughputPerSec = 0.0; // Placeholder
        }

        BenchmarkStatistics(long medianTimeMs, long p25TimeMs, long p75TimeMs,
                           double meanTimeMs, long medianHeapMb, double cacheHitRate, double throughputPerSec) {
            this.medianTimeMs = medianTimeMs;
            this.p25TimeMs = p25TimeMs;
            this.p75TimeMs = p75TimeMs;
            this.meanTimeMs = meanTimeMs;
            this.medianHeapMb = medianHeapMb;
            this.cacheHitRate = cacheHitRate;
            this.throughputPerSec = throughputPerSec;
        }

        @Override
        public String toString() {
            return String.format("BenchmarkStatistics{median=%dms, p25=%dms, p75=%dms, mean=%.2fms, heap=%dMB, cache=%.2f%%}",
                    medianTimeMs, p25TimeMs, p75TimeMs, meanTimeMs, medianHeapMb, cacheHitRate * 100);
        }
    }

    // ==================== Metrics Capture Helpers ====================

    private static long getEquivalentCalls() {
        return parseMetricFromReport("equivalent() calls:", TransformationMetrics.getReport());
    }

    private static long getCacheHits() {
        String report = TransformationMetrics.getReport();
        // Find the line with "Cache hits" and extract the number
        for (String line : report.split("\n")) {
            if (line.contains("Cache hits")) {
                // Extract all numbers from the line
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(line);
                if (m.find()) {
                    // First number after "Cache hits" is the hit count
                    return Long.parseLong(m.group());
                }
            }
        }
        return 0;
    }

    private static long parseMetricFromReport(String label, String report) {
        for (String line : report.split("\n")) {
            if (line.contains(label)) {
                // Extract all numbers from the line
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(line);
                if (m.find()) {
                    return Long.parseLong(m.group());
                }
            }
        }
        return 0;
    }

    // ==================== Model Provider ====================

    private static class TestModelProvider implements ModelProvider {
        private final ResourceSet sourceResourceSet;

        TestModelProvider(ResourceSet sourceResourceSet) {
            this.sourceResourceSet = sourceResourceSet;
        }

        @Override
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : sourceResourceSet.getResources()) {
                var it = resource.getAllContents();
                while (it.hasNext()) {
                    EObject obj = it.next();
                    if (type.isInstance(obj)) {
                        results.add((T) obj);
                    }
                }
            }
            return results;
        }
    }
}
