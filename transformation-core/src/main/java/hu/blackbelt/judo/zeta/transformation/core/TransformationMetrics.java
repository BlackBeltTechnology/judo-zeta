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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Performance metrics collector for transformation profiling.
 *
 * <p>Collects timing and count metrics for key operations to identify bottlenecks.</p>
 */
public class TransformationMetrics {

    private static volatile boolean enabled = false;

    // Count metrics
    private static final AtomicLong equivalentCalls = new AtomicLong(0);
    private static final AtomicLong equivalentCacheHits = new AtomicLong(0);
    private static final AtomicLong equivalentCacheMisses = new AtomicLong(0);
    private static final AtomicLong getRulesForSourceCalls = new AtomicLong(0);
    private static final AtomicLong ruleIterations = new AtomicLong(0);
    private static final AtomicLong guardEvaluations = new AtomicLong(0);
    private static final AtomicLong ruleExecutions = new AtomicLong(0);
    private static final AtomicLong findByXmiIdCalls = new AtomicLong(0);
    private static final AtomicLong findByXmiIdScans = new AtomicLong(0);
    private static final AtomicLong lockAcquisitions = new AtomicLong(0);

    // Time metrics (nanoseconds)
    private static final AtomicLong equivalentTotalNanos = new AtomicLong(0);
    private static final AtomicLong getRulesForSourceNanos = new AtomicLong(0);
    private static final AtomicLong guardEvaluationNanos = new AtomicLong(0);
    private static final AtomicLong ruleExecutionNanos = new AtomicLong(0);
    private static final AtomicLong findByXmiIdNanos = new AtomicLong(0);
    private static final AtomicLong cacheOperationNanos = new AtomicLong(0);
    private static final AtomicLong lockWaitNanos = new AtomicLong(0);

    // Per-rule metrics
    private static final ConcurrentHashMap<String, AtomicLong> ruleExecutionCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> ruleExecutionNanosMap = new ConcurrentHashMap<>();

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void reset() {
        equivalentCalls.set(0);
        equivalentCacheHits.set(0);
        equivalentCacheMisses.set(0);
        getRulesForSourceCalls.set(0);
        ruleIterations.set(0);
        guardEvaluations.set(0);
        ruleExecutions.set(0);
        findByXmiIdCalls.set(0);
        findByXmiIdScans.set(0);
        lockAcquisitions.set(0);

        equivalentTotalNanos.set(0);
        getRulesForSourceNanos.set(0);
        guardEvaluationNanos.set(0);
        ruleExecutionNanos.set(0);
        findByXmiIdNanos.set(0);
        cacheOperationNanos.set(0);
        lockWaitNanos.set(0);

        ruleExecutionCounts.clear();
        ruleExecutionNanosMap.clear();

        greedyRuleExecutions.set(0);
        greedyRuleNanos.set(0);
        greedyRuleExecutionCounts.clear();
        greedyRuleNanosMap.clear();
    }

    // Count recording methods
    public static void recordEquivalentCall() {
        if (enabled) equivalentCalls.incrementAndGet();
    }

    public static void recordEquivalentCacheHit() {
        if (enabled) equivalentCacheHits.incrementAndGet();
    }

    public static void recordEquivalentCacheMiss() {
        if (enabled) equivalentCacheMisses.incrementAndGet();
    }

    public static void recordGetRulesForSourceCall() {
        if (enabled) getRulesForSourceCalls.incrementAndGet();
    }

    public static void recordRuleIteration() {
        if (enabled) ruleIterations.incrementAndGet();
    }

    public static void recordGuardEvaluation() {
        if (enabled) guardEvaluations.incrementAndGet();
    }

    public static void recordRuleExecution(String ruleName) {
        if (enabled) {
            ruleExecutions.incrementAndGet();
            ruleExecutionCounts.computeIfAbsent(ruleName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public static void recordFindByXmiIdCall() {
        if (enabled) findByXmiIdCalls.incrementAndGet();
    }

    public static void recordFindByXmiIdScan() {
        if (enabled) findByXmiIdScans.incrementAndGet();
    }

    public static void recordLockAcquisition() {
        if (enabled) lockAcquisitions.incrementAndGet();
    }

    // Greedy rule metrics
    private static final AtomicLong greedyRuleExecutions = new AtomicLong(0);
    private static final AtomicLong greedyRuleNanos = new AtomicLong(0);
    private static final ConcurrentHashMap<String, AtomicLong> greedyRuleExecutionCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> greedyRuleNanosMap = new ConcurrentHashMap<>();

    // Time recording methods
    public static void addEquivalentNanos(long nanos) {
        if (enabled) equivalentTotalNanos.addAndGet(nanos);
    }

    public static void recordGreedyRuleExecution(String ruleName, long nanos) {
        if (enabled) {
            greedyRuleExecutions.incrementAndGet();
            greedyRuleNanos.addAndGet(nanos);
            greedyRuleExecutionCounts.computeIfAbsent(ruleName, k -> new AtomicLong(0)).incrementAndGet();
            greedyRuleNanosMap.computeIfAbsent(ruleName, k -> new AtomicLong(0)).addAndGet(nanos);
        }
    }

    public static void addGetRulesForSourceNanos(long nanos) {
        if (enabled) getRulesForSourceNanos.addAndGet(nanos);
    }

    public static void addGuardEvaluationNanos(long nanos) {
        if (enabled) guardEvaluationNanos.addAndGet(nanos);
    }

    public static void addRuleExecutionNanos(String ruleName, long nanos) {
        if (enabled) {
            ruleExecutionNanos.addAndGet(nanos);
            ruleExecutionNanosMap.computeIfAbsent(ruleName, k -> new AtomicLong(0)).addAndGet(nanos);
        }
    }

    public static void addFindByXmiIdNanos(long nanos) {
        if (enabled) findByXmiIdNanos.addAndGet(nanos);
    }

    public static void addCacheOperationNanos(long nanos) {
        if (enabled) cacheOperationNanos.addAndGet(nanos);
    }

    public static void addLockWaitNanos(long nanos) {
        if (enabled) lockWaitNanos.addAndGet(nanos);
    }

    /**
     * Print a comprehensive performance report.
     */
    public static String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ZETA TRANSFORMATION PERFORMANCE REPORT ==========\n\n");

        sb.append("=== OPERATION COUNTS ===\n");
        sb.append(String.format("  equivalent() calls:       %,d\n", equivalentCalls.get()));
        sb.append(String.format("    - Cache hits:           %,d (%.1f%%)\n",
                equivalentCacheHits.get(),
                equivalentCalls.get() > 0 ? (100.0 * equivalentCacheHits.get() / equivalentCalls.get()) : 0));
        sb.append(String.format("    - Cache misses:         %,d (%.1f%%)\n",
                equivalentCacheMisses.get(),
                equivalentCalls.get() > 0 ? (100.0 * equivalentCacheMisses.get() / equivalentCalls.get()) : 0));
        sb.append(String.format("  getRulesForSource() calls: %,d\n", getRulesForSourceCalls.get()));
        sb.append(String.format("  Rule iterations:          %,d\n", ruleIterations.get()));
        sb.append(String.format("  Guard evaluations:        %,d\n", guardEvaluations.get()));
        sb.append(String.format("  Rule executions:          %,d\n", ruleExecutions.get()));
        sb.append(String.format("  findByXmiId() calls:      %,d\n", findByXmiIdCalls.get()));
        sb.append(String.format("  findByXmiId() scans:      %,d\n", findByXmiIdScans.get()));
        sb.append(String.format("  Lock acquisitions:        %,d\n", lockAcquisitions.get()));

        sb.append("\n=== TIMING BREAKDOWN ===\n");
        long totalNanos = equivalentTotalNanos.get();
        sb.append(String.format("  equivalent() total:       %,d ms\n", totalNanos / 1_000_000));
        sb.append(String.format("    - getRulesForSource:    %,d ms (%.1f%%)\n",
                getRulesForSourceNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * getRulesForSourceNanos.get() / totalNanos) : 0));
        sb.append(String.format("    - Guard evaluation:     %,d ms (%.1f%%)\n",
                guardEvaluationNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * guardEvaluationNanos.get() / totalNanos) : 0));
        sb.append(String.format("    - Rule execution:       %,d ms (%.1f%%)\n",
                ruleExecutionNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * ruleExecutionNanos.get() / totalNanos) : 0));
        sb.append(String.format("    - findByXmiId:          %,d ms (%.1f%%)\n",
                findByXmiIdNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * findByXmiIdNanos.get() / totalNanos) : 0));
        sb.append(String.format("    - Cache operations:     %,d ms (%.1f%%)\n",
                cacheOperationNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * cacheOperationNanos.get() / totalNanos) : 0));
        sb.append(String.format("    - Lock wait:            %,d ms (%.1f%%)\n",
                lockWaitNanos.get() / 1_000_000,
                totalNanos > 0 ? (100.0 * lockWaitNanos.get() / totalNanos) : 0));

        // Average time per operation
        sb.append("\n=== AVERAGE TIME PER OPERATION ===\n");
        if (equivalentCalls.get() > 0) {
            sb.append(String.format("  Per equivalent() call:    %.3f ms\n",
                    (double) equivalentTotalNanos.get() / equivalentCalls.get() / 1_000_000));
        }
        if (getRulesForSourceCalls.get() > 0) {
            sb.append(String.format("  Per getRulesForSource:    %.3f ms\n",
                    (double) getRulesForSourceNanos.get() / getRulesForSourceCalls.get() / 1_000_000));
        }
        if (guardEvaluations.get() > 0) {
            sb.append(String.format("  Per guard evaluation:     %.3f ms\n",
                    (double) guardEvaluationNanos.get() / guardEvaluations.get() / 1_000_000));
        }
        if (ruleExecutions.get() > 0) {
            sb.append(String.format("  Per rule execution:       %.3f ms\n",
                    (double) ruleExecutionNanos.get() / ruleExecutions.get() / 1_000_000));
        }

        // Top 10 slowest rules (via equivalent())
        if (!ruleExecutionNanosMap.isEmpty()) {
            sb.append("\n=== TOP 10 SLOWEST LAZY RULES (via equivalent()) ===\n");
            ruleExecutionNanosMap.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(10)
                    .forEach(entry -> {
                        String ruleName = entry.getKey();
                        long nanos = entry.getValue().get();
                        long count = ruleExecutionCounts.getOrDefault(ruleName, new AtomicLong(0)).get();
                        sb.append(String.format("  %-50s %,7d ms (%,d calls, %.3f ms/call)\n",
                                ruleName,
                                nanos / 1_000_000,
                                count,
                                count > 0 ? (double) nanos / count / 1_000_000 : 0));
                    });
        }

        // Greedy rule execution metrics
        sb.append("\n=== GREEDY RULE EXECUTION ===\n");
        sb.append(String.format("  Total greedy executions:  %,d\n", greedyRuleExecutions.get()));
        sb.append(String.format("  Total greedy time:        %,d ms\n", greedyRuleNanos.get() / 1_000_000));
        if (greedyRuleExecutions.get() > 0) {
            sb.append(String.format("  Avg time per execution:   %.3f ms\n",
                    (double) greedyRuleNanos.get() / greedyRuleExecutions.get() / 1_000_000));
        }

        // Top 10 slowest greedy rules
        if (!greedyRuleNanosMap.isEmpty()) {
            sb.append("\n=== TOP 10 SLOWEST GREEDY RULES ===\n");
            greedyRuleNanosMap.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(10)
                    .forEach(entry -> {
                        String ruleName = entry.getKey();
                        long nanos = entry.getValue().get();
                        long count = greedyRuleExecutionCounts.getOrDefault(ruleName, new AtomicLong(0)).get();
                        sb.append(String.format("  %-50s %,7d ms (%,d calls, %.3f ms/call)\n",
                                ruleName,
                                nanos / 1_000_000,
                                count,
                                count > 0 ? (double) nanos / count / 1_000_000 : 0));
                    });
        }

        // Potential issues
        sb.append("\n=== POTENTIAL ISSUES ===\n");
        if (equivalentCacheMisses.get() > equivalentCacheHits.get()) {
            sb.append("  ⚠ Low cache hit rate - consider caching more aggressively\n");
        }
        if (findByXmiIdScans.get() > 1000) {
            sb.append(String.format("  ⚠ High XMI ID scan count (%,d) - consider indexing pending IDs\n",
                    findByXmiIdScans.get()));
        }
        if (getRulesForSourceCalls.get() > 0 &&
            (double) ruleIterations.get() / getRulesForSourceCalls.get() > 20) {
            sb.append(String.format("  ⚠ High rule iterations per source (%.1f avg) - consider caching rule lookups\n",
                    (double) ruleIterations.get() / getRulesForSourceCalls.get()));
        }

        sb.append("\n============================================================\n");
        return sb.toString();
    }
}
