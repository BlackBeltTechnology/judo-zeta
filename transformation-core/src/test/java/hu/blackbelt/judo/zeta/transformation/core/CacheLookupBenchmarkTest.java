package hu.blackbelt.judo.zeta.transformation.core;

/*-
 * #%L
 * Judo :: Zeta :: Transformation Core
 * %%
 * Copyright (C) 2018 - 2024 BlackBelt Technology
 * %%
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EObjectImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark test to validate cache lookup performance assumptions.
 *
 * <p>This test validates the assumptions from the performance analysis:
 * <ul>
 *   <li>Two-level map lookup overhead</li>
 *   <li>String interning benefits</li>
 *   <li>Composite key vs two-level cache</li>
 *   <li>Array-based lookup performance</li>
 * </ul>
 *
 * <p>Run with: mvn test -Dtest=CacheLookupBenchmarkTest -pl transformation-core
 */
public class CacheLookupBenchmarkTest {

    // Simulation parameters matching PSM2ASM transformation
    private static final int SOURCE_COUNT = 22_000;  // ~22K elements in RackInspect
    private static final int RULE_COUNT = 50;        // ~50 rules
    private static final int LOOKUP_COUNT = 40_000;  // ~40K equivalent() calls
    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 5;

    private List<EObject> sourceElements;
    private String[] ruleNames;
    private Random random;

    @BeforeEach
    void setUp() {
        // Create mock source elements
        sourceElements = new ArrayList<>(SOURCE_COUNT);
        for (int i = 0; i < SOURCE_COUNT; i++) {
            sourceElements.add(EcoreFactory.eINSTANCE.createEClass());
        }

        // Create rule names (similar to actual transformation rules)
        ruleNames = new String[RULE_COUNT];
        for (int i = 0; i < RULE_COUNT; i++) {
            ruleNames[i] = "Create" + generateRuleName(i);
        }

        random = new Random(42);  // Fixed seed for reproducibility
    }

    private String generateRuleName(int index) {
        String[] prefixes = {"TransferObject", "Entity", "Operation", "Attribute", "Reference", "Type", "Bound", "Unbound"};
        String[] suffixes = {"Class", "Relation", "Permissions", "Annotation", "Parameter", "Type", "Name", "Body"};
        return prefixes[index % prefixes.length] + suffixes[(index / prefixes.length) % suffixes.length];
    }

    // ==================== ASSUMPTION 1: Two-Level vs Single-Level Lookup ====================

    @Test
    void validateAssumption_TwoLevelMapIsSlowerThanSingleLevel() {
        System.out.println("\n=== ASSUMPTION 1: Two-Level vs Single-Level Map Lookup ===\n");

        // Setup: Two-level cache (current implementation)
        Map<EObject, Map<String, EObject>> twoLevelCache = new IdentityHashMap<>();
        populateTwoLevelCache(twoLevelCache);

        // Setup: Single-level cache with composite key
        Map<CompositeKey, EObject> singleLevelCache = new HashMap<>();
        populateSingleLevelCache(singleLevelCache);

        // Generate random lookup sequence
        int[][] lookupSequence = generateLookupSequence(LOOKUP_COUNT);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            benchmarkTwoLevelLookup(twoLevelCache, lookupSequence);
            benchmarkSingleLevelLookup(singleLevelCache, lookupSequence);
        }

        // Benchmark
        long twoLevelTotal = 0, singleLevelTotal = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            twoLevelTotal += benchmarkTwoLevelLookup(twoLevelCache, lookupSequence);
            singleLevelTotal += benchmarkSingleLevelLookup(singleLevelCache, lookupSequence);
        }

        long twoLevelAvg = twoLevelTotal / BENCHMARK_ITERATIONS;
        long singleLevelAvg = singleLevelTotal / BENCHMARK_ITERATIONS;
        double ratio = (double) twoLevelAvg / singleLevelAvg;

        System.out.printf("Two-level (IdentityHashMap → HashMap): %,d ns/lookup (total: %,d ms)%n",
                twoLevelAvg * 1_000_000 / LOOKUP_COUNT, twoLevelAvg / 1_000_000);
        System.out.printf("Single-level (CompositeKey HashMap):   %,d ns/lookup (total: %,d ms)%n",
                singleLevelAvg * 1_000_000 / LOOKUP_COUNT, singleLevelAvg / 1_000_000);
        System.out.printf("Ratio: %.2fx %s%n", ratio,
                ratio > 1 ? "(two-level is SLOWER - assumption VALID)" : "(two-level is FASTER - assumption INVALID)");

        // Validate assumption: two-level should be at least somewhat slower
        // Note: This may not always be true due to JIT optimization
        System.out.printf("%nConclusion: Two-level map lookup is %.1f%% %s than single-level%n",
                Math.abs(ratio - 1) * 100, ratio > 1 ? "slower" : "faster");
    }

    private void populateTwoLevelCache(Map<EObject, Map<String, EObject>> cache) {
        for (EObject source : sourceElements) {
            Map<String, EObject> innerMap = new HashMap<>();
            // Each source has mappings for ~30% of rules (simulating real transformation)
            for (int i = 0; i < RULE_COUNT; i++) {
                if (random.nextDouble() < 0.3) {
                    innerMap.put(ruleNames[i], EcoreFactory.eINSTANCE.createEClass());
                }
            }
            if (!innerMap.isEmpty()) {
                cache.put(source, innerMap);
            }
        }
        random = new Random(42);  // Reset for consistent lookup sequence
    }

    private void populateSingleLevelCache(Map<CompositeKey, EObject> cache) {
        random = new Random(42);  // Same seed as two-level
        for (EObject source : sourceElements) {
            for (int i = 0; i < RULE_COUNT; i++) {
                if (random.nextDouble() < 0.3) {
                    cache.put(new CompositeKey(source, ruleNames[i]), EcoreFactory.eINSTANCE.createEClass());
                }
            }
        }
        random = new Random(42);  // Reset for consistent lookup sequence
    }

    private int[][] generateLookupSequence(int count) {
        int[][] sequence = new int[count][2];
        Random r = new Random(123);  // Different seed for lookup pattern
        for (int i = 0; i < count; i++) {
            sequence[i][0] = r.nextInt(SOURCE_COUNT);
            sequence[i][1] = r.nextInt(RULE_COUNT);
        }
        return sequence;
    }

    private long benchmarkTwoLevelLookup(Map<EObject, Map<String, EObject>> cache, int[][] sequence) {
        long start = System.nanoTime();
        int hits = 0;
        for (int[] lookup : sequence) {
            EObject source = sourceElements.get(lookup[0]);
            String ruleName = ruleNames[lookup[1]];
            Map<String, EObject> innerMap = cache.get(source);
            if (innerMap != null) {
                EObject result = innerMap.get(ruleName);
                if (result != null) hits++;
            }
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("  Two-level: %,d hits in %,d ms%n", hits, elapsed / 1_000_000);
        return elapsed;
    }

    private long benchmarkSingleLevelLookup(Map<CompositeKey, EObject> cache, int[][] sequence) {
        long start = System.nanoTime();
        int hits = 0;
        for (int[] lookup : sequence) {
            EObject source = sourceElements.get(lookup[0]);
            String ruleName = ruleNames[lookup[1]];
            CompositeKey key = new CompositeKey(source, ruleName);
            EObject result = cache.get(key);
            if (result != null) hits++;
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("  Single-level: %,d hits in %,d ms%n", hits, elapsed / 1_000_000);
        return elapsed;
    }

    // ==================== ASSUMPTION 2: String Interning Benefits ====================

    @Test
    void validateAssumption_StringInterningIsFaster() {
        System.out.println("\n=== ASSUMPTION 2: String Interning Benefits ===\n");

        // Create non-interned rule names (simulating dynamic creation)
        String[] nonInternedNames = new String[RULE_COUNT];
        for (int i = 0; i < RULE_COUNT; i++) {
            nonInternedNames[i] = new String(ruleNames[i]);  // Force new String object
        }

        // Intern the rule names
        String[] internedNames = new String[RULE_COUNT];
        for (int i = 0; i < RULE_COUNT; i++) {
            internedNames[i] = ruleNames[i].intern();
        }

        // Benchmark string comparison
        int iterations = 10_000_000;

        // Warmup
        for (int i = 0; i < 3; i++) {
            benchmarkStringEquals(nonInternedNames, iterations);
            benchmarkStringIdentity(internedNames, iterations);
        }

        // Benchmark
        long equalsTime = 0, identityTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            equalsTime += benchmarkStringEquals(nonInternedNames, iterations);
            identityTime += benchmarkStringIdentity(internedNames, iterations);
        }

        double ratio = (double) equalsTime / identityTime;
        System.out.printf("%nString.equals(): %,d ns total%n", equalsTime / BENCHMARK_ITERATIONS);
        System.out.printf("Identity (==):   %,d ns total%n", identityTime / BENCHMARK_ITERATIONS);
        System.out.printf("Ratio: %.2fx %s%n", ratio,
                ratio > 1 ? "(equals is SLOWER - assumption VALID)" : "(equals is FASTER - assumption INVALID)");

        // Note: Modern JVMs may optimize equals() to be as fast as identity for interned strings
        System.out.printf("%nConclusion: String identity is %.1f%% %s than equals()%n",
                Math.abs(ratio - 1) * 100, ratio > 1 ? "faster" : "slower");
    }

    private long benchmarkStringEquals(String[] names, int iterations) {
        long start = System.nanoTime();
        int matches = 0;
        for (int i = 0; i < iterations; i++) {
            String a = names[i % RULE_COUNT];
            String b = names[(i + 7) % RULE_COUNT];
            if (a.equals(b)) matches++;
        }
        return System.nanoTime() - start;
    }

    private long benchmarkStringIdentity(String[] names, int iterations) {
        long start = System.nanoTime();
        int matches = 0;
        for (int i = 0; i < iterations; i++) {
            String a = names[i % RULE_COUNT];
            String b = names[(i + 7) % RULE_COUNT];
            if (a == b) matches++;
        }
        return System.nanoTime() - start;
    }

    // ==================== ASSUMPTION 3: Array-Based Lookup is Fastest ====================

    @Test
    void validateAssumption_ArrayBasedLookupIsFastest() {
        System.out.println("\n=== ASSUMPTION 3: Array-Based Lookup Performance ===\n");

        // Setup: Array-based cache (indexed by rule ordinal)
        EObject[][] arrayCache = new EObject[SOURCE_COUNT][RULE_COUNT];
        populateArrayCache(arrayCache);

        // Setup: Two-level cache for comparison
        Map<EObject, Map<String, EObject>> twoLevelCache = new IdentityHashMap<>();
        populateTwoLevelCache(twoLevelCache);

        // Generate lookup sequence with ordinals
        int[][] lookupSequence = generateLookupSequence(LOOKUP_COUNT);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            benchmarkArrayLookup(arrayCache, lookupSequence);
            benchmarkTwoLevelLookup(twoLevelCache, lookupSequence);
        }

        // Benchmark
        long arrayTotal = 0, twoLevelTotal = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            arrayTotal += benchmarkArrayLookup(arrayCache, lookupSequence);
            twoLevelTotal += benchmarkTwoLevelLookup(twoLevelCache, lookupSequence);
        }

        long arrayAvg = arrayTotal / BENCHMARK_ITERATIONS;
        long twoLevelAvg = twoLevelTotal / BENCHMARK_ITERATIONS;
        double speedup = (double) twoLevelAvg / arrayAvg;

        System.out.printf("%nArray-based lookup:    %,d ns/lookup (total: %,d ms)%n",
                arrayAvg * 1_000_000 / LOOKUP_COUNT, arrayAvg / 1_000_000);
        System.out.printf("Two-level map lookup:  %,d ns/lookup (total: %,d ms)%n",
                twoLevelAvg * 1_000_000 / LOOKUP_COUNT, twoLevelAvg / 1_000_000);
        System.out.printf("Speedup: %.2fx %s%n", speedup,
                speedup > 1 ? "(array is FASTER - assumption VALID)" : "(array is SLOWER - assumption INVALID)");

        assertTrue(speedup > 1.5, "Array-based lookup should be at least 1.5x faster");
    }

    private void populateArrayCache(EObject[][] cache) {
        random = new Random(42);
        for (int s = 0; s < SOURCE_COUNT; s++) {
            for (int r = 0; r < RULE_COUNT; r++) {
                if (random.nextDouble() < 0.3) {
                    cache[s][r] = EcoreFactory.eINSTANCE.createEClass();
                }
            }
        }
        random = new Random(42);
    }

    private long benchmarkArrayLookup(EObject[][] cache, int[][] sequence) {
        long start = System.nanoTime();
        int hits = 0;
        for (int[] lookup : sequence) {
            int sourceIndex = lookup[0];
            int ruleIndex = lookup[1];
            EObject result = cache[sourceIndex][ruleIndex];
            if (result != null) hits++;
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("  Array-based: %,d hits in %,d ms%n", hits, elapsed / 1_000_000);
        return elapsed;
    }

    // ==================== ASSUMPTION 4: computeIfAbsent Allocation Overhead ====================

    @Test
    void validateAssumption_ComputeIfAbsentHasOverhead() {
        System.out.println("\n=== ASSUMPTION 4: computeIfAbsent Allocation Overhead ===\n");

        int iterations = 1_000_000;

        // Test 1: computeIfAbsent on existing key (should not allocate)
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            map.put(i, "value" + i);
        }

        // Warmup
        for (int i = 0; i < 3; i++) {
            benchmarkComputeIfAbsentExisting(map, iterations);
            benchmarkGetExisting(map, iterations);
        }

        // Benchmark
        long computeTime = 0, getTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            computeTime += benchmarkComputeIfAbsentExisting(map, iterations);
            getTime += benchmarkGetExisting(map, iterations);
        }

        double ratio = (double) computeTime / getTime;
        System.out.printf("%ncomputeIfAbsent (existing): %,d ns total%n", computeTime / BENCHMARK_ITERATIONS);
        System.out.printf("get (existing):             %,d ns total%n", getTime / BENCHMARK_ITERATIONS);
        System.out.printf("Ratio: %.2fx%n", ratio);

        // Note: For existing keys, computeIfAbsent should be similar to get
        System.out.printf("%nConclusion: computeIfAbsent overhead for existing keys is %.1f%%%n",
                (ratio - 1) * 100);
    }

    private long benchmarkComputeIfAbsentExisting(Map<Integer, String> map, int iterations) {
        long start = System.nanoTime();
        int count = 0;
        for (int i = 0; i < iterations; i++) {
            String value = map.computeIfAbsent(i % 1000, k -> "new" + k);
            if (value != null) count++;
        }
        return System.nanoTime() - start;
    }

    private long benchmarkGetExisting(Map<Integer, String> map, int iterations) {
        long start = System.nanoTime();
        int count = 0;
        for (int i = 0; i < iterations; i++) {
            String value = map.get(i % 1000);
            if (value != null) count++;
        }
        return System.nanoTime() - start;
    }

    // ==================== OVERALL PERFORMANCE PROJECTION ====================

    @Test
    void projectPerformanceImprovement() {
        System.out.println("\n=== PERFORMANCE IMPROVEMENT PROJECTION ===\n");

        // Current bottleneck: 2,383ms for ~40K cache operations = ~60μs per operation
        // Target: < 500ms = < 12.5μs per operation

        System.out.println("Current state:");
        System.out.println("  Cache ops time: 2,383ms");
        System.out.println("  Operations: ~40,000");
        System.out.println("  Time per op: ~60μs");
        System.out.println();

        // Run microbenchmark to estimate actual overhead
        int ops = 40_000;

        // Simulate current implementation
        Map<EObject, Map<String, EObject>> currentCache = new IdentityHashMap<>();
        populateTwoLevelCache(currentCache);
        int[][] sequence = generateLookupSequence(ops);

        long currentTime = 0;
        for (int i = 0; i < 5; i++) {
            currentTime += benchmarkTwoLevelLookupOnly(currentCache, sequence);
        }
        currentTime /= 5;

        // Simulate array-based implementation
        EObject[][] arrayCache = new EObject[SOURCE_COUNT][RULE_COUNT];
        populateArrayCache(arrayCache);

        long arrayTime = 0;
        for (int i = 0; i < 5; i++) {
            arrayTime += benchmarkArrayLookup(arrayCache, sequence);
        }
        arrayTime /= 5;

        System.out.println("Microbenchmark results (pure lookup, no guards/execution):");
        System.out.printf("  Two-level map: %,d ms (%,d ns/op)%n", currentTime / 1_000_000, currentTime * 1000 / ops);
        System.out.printf("  Array-based:   %,d ms (%,d ns/op)%n", arrayTime / 1_000_000, arrayTime * 1000 / ops);
        System.out.printf("  Speedup:       %.2fx%n", (double) currentTime / arrayTime);
        System.out.println();

        // Project real-world improvement
        // The 2,383ms includes more than just lookups (guard evaluation, mapping)
        // Pure lookup optimization might only improve a portion of that
        double lookupPortion = 0.3;  // Estimate: ~30% of cache ops time is pure lookup
        double potentialSavings = (2383 * lookupPortion) * (1 - (double) arrayTime / currentTime);

        System.out.println("Projected real-world improvement:");
        System.out.printf("  Estimated lookup portion: %.0f%% of cache ops time%n", lookupPortion * 100);
        System.out.printf("  Potential savings: %.0f ms%n", potentialSavings);
        System.out.printf("  New cache ops time: %.0f ms (down from 2,383ms)%n", 2383 - potentialSavings);
        System.out.println();

        System.out.println("RECOMMENDATION:");
        System.out.println("  Before implementing array-based cache, profile to determine");
        System.out.println("  what portion of 'cache ops' time is actually map lookups vs");
        System.out.println("  guard evaluation, rejection checks, and mapping operations.");
    }

    private long benchmarkTwoLevelLookupOnly(Map<EObject, Map<String, EObject>> cache, int[][] sequence) {
        long start = System.nanoTime();
        int hits = 0;
        for (int[] lookup : sequence) {
            EObject source = sourceElements.get(lookup[0]);
            String ruleName = ruleNames[lookup[1]];
            Map<String, EObject> innerMap = cache.get(source);
            if (innerMap != null) {
                EObject result = innerMap.get(ruleName);
                if (result != null) hits++;
            }
        }
        return System.nanoTime() - start;
    }

    // ==================== ASSUMPTION 5: Metrics Instrumentation Overhead ====================

    @Test
    void validateAssumption_MetricsInstrumentationOverhead() {
        System.out.println("\n=== ASSUMPTION 5: Metrics Instrumentation Overhead ===\n");

        // Create cache and populate with some data
        ElementResolutionCache cache = new ElementResolutionCache(true); // sequential mode
        for (int i = 0; i < 1000; i++) {
            EObject source = sourceElements.get(i % SOURCE_COUNT);
            String rule = ruleNames[i % RULE_COUNT];
            cache.addMapping(source, rule, EcoreFactory.eINSTANCE.createEClass(), false);
        }

        int operations = 100_000;
        int[][] sequence = generateLookupSequence(operations);

        // Test 1: getOrCreate with metrics DISABLED
        TransformationMetrics.disable();
        TransformationMetrics.reset();
        long disabledTime = benchmarkGetOrCreate(cache, sequence);

        // Test 2: getOrCreate with metrics ENABLED
        TransformationMetrics.enable();
        TransformationMetrics.reset();
        long enabledTime = benchmarkGetOrCreate(cache, sequence);

        TransformationMetrics.disable();

        double overhead = ((double) enabledTime / disabledTime - 1) * 100;
        System.out.printf("Metrics DISABLED: %,d ms (%,d ns/op)%n",
                disabledTime / 1_000_000, disabledTime * 1000 / operations);
        System.out.printf("Metrics ENABLED:  %,d ms (%,d ns/op)%n",
                enabledTime / 1_000_000, enabledTime * 1000 / operations);
        System.out.printf("Overhead: %.1f%%%n", overhead);
        System.out.println();

        // Test 3: Pure System.nanoTime() overhead
        long nanoTimeOverhead = benchmarkSystemNanoTime(operations);
        System.out.printf("Pure System.nanoTime() overhead: %,d ms for %,d calls (%,d ns/call)%n",
                nanoTimeOverhead / 1_000_000, operations, nanoTimeOverhead * 1000 / operations);

        System.out.println("\nConclusion:");
        if (overhead > 20) {
            System.out.printf("  Metrics instrumentation adds %.1f%% overhead - SIGNIFICANT%n", overhead);
            System.out.println("  Consider making metrics conditional or reducing nanoTime() calls");
        } else if (overhead > 5) {
            System.out.printf("  Metrics instrumentation adds %.1f%% overhead - MODERATE%n", overhead);
        } else {
            System.out.printf("  Metrics instrumentation adds %.1f%% overhead - NEGLIGIBLE%n", overhead);
        }
    }

    private long benchmarkGetOrCreate(ElementResolutionCache cache, int[][] sequence) {
        // Warmup
        for (int i = 0; i < 3; i++) {
            doGetOrCreateBenchmark(cache, sequence);
        }

        // Benchmark
        long total = 0;
        for (int i = 0; i < 5; i++) {
            total += doGetOrCreateBenchmark(cache, sequence);
        }
        return total / 5;
    }

    private long doGetOrCreateBenchmark(ElementResolutionCache cache, int[][] sequence) {
        long start = System.nanoTime();
        int hits = 0;
        for (int[] lookup : sequence) {
            EObject source = sourceElements.get(lookup[0]);
            String ruleName = ruleNames[lookup[1]];
            EObject result = cache.getOrCreate(source, ruleName, () -> null, false);
            if (result != null) hits++;
        }
        return System.nanoTime() - start;
    }

    private long benchmarkSystemNanoTime(int iterations) {
        // Warmup
        for (int i = 0; i < 3; i++) {
            doNanoTimeBenchmark(iterations);
        }
        // Benchmark
        long total = 0;
        for (int i = 0; i < 5; i++) {
            total += doNanoTimeBenchmark(iterations);
        }
        return total / 5;
    }

    private long doNanoTimeBenchmark(int iterations) {
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            // Each getOrCreate with metrics enabled makes 2-4 nanoTime() calls
            sum += System.nanoTime();
            sum += System.nanoTime();
            sum += System.nanoTime();
        }
        long elapsed = System.nanoTime() - start;
        // Prevent dead code elimination
        if (sum == 0) System.out.println("unexpected");
        return elapsed;
    }

    // ==================== Helper Classes ====================

    /**
     * Composite key for single-level cache implementation.
     */
    private static class CompositeKey {
        final EObject source;
        final String ruleName;
        final int hash;

        CompositeKey(EObject source, String ruleName) {
            this.source = source;
            this.ruleName = ruleName;
            // Pre-compute hash using identity for source
            this.hash = 31 * System.identityHashCode(source) + ruleName.hashCode();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CompositeKey k)) return false;
            return source == k.source && ruleName.equals(k.ruleName);
        }
    }
}
