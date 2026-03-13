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

    // Total transformation timing
    private static final AtomicLong totalTransformationNanos = new AtomicLong(0);
    private static volatile long transformationStartNanos = 0;

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

    // NEW: Additional count metrics
    private static final AtomicLong createTargetCalls = new AtomicLong(0);
    private static final AtomicLong setXmiIdCalls = new AtomicLong(0);
    private static final AtomicLong extensionMethodCalls = new AtomicLong(0);
    private static final AtomicLong attributeSetCalls = new AtomicLong(0);
    private static final AtomicLong referenceSetCalls = new AtomicLong(0);
    private static final AtomicLong equivalentDiscriminatedCalls = new AtomicLong(0);
    private static final AtomicLong equivalentsCalls = new AtomicLong(0);
    private static final AtomicLong postProcessingCalls = new AtomicLong(0);

    // Time metrics (nanoseconds)
    private static final AtomicLong equivalentTotalNanos = new AtomicLong(0);
    private static final AtomicLong getRulesForSourceNanos = new AtomicLong(0);
    private static final AtomicLong guardEvaluationNanos = new AtomicLong(0);
    private static final AtomicLong ruleExecutionNanos = new AtomicLong(0);
    private static final AtomicLong findByXmiIdNanos = new AtomicLong(0);
    private static final AtomicLong cacheOperationNanos = new AtomicLong(0);
    private static final AtomicLong lockWaitNanos = new AtomicLong(0);

    // NEW: Additional time metrics
    private static final AtomicLong createTargetNanos = new AtomicLong(0);
    private static final AtomicLong setXmiIdNanos = new AtomicLong(0);
    private static final AtomicLong extensionMethodNanos = new AtomicLong(0);
    private static final AtomicLong attributeSetNanos = new AtomicLong(0);
    private static final AtomicLong referenceSetNanos = new AtomicLong(0);
    private static final AtomicLong equivalentDiscriminatedNanos = new AtomicLong(0);
    private static final AtomicLong equivalentsNanos = new AtomicLong(0);
    private static final AtomicLong postProcessingNanos = new AtomicLong(0);
    private static final AtomicLong stagingCommitNanos = new AtomicLong(0);
    private static final AtomicLong modelIterationNanos = new AtomicLong(0);
    private static final AtomicLong deferredOperationsNanos = new AtomicLong(0);
    private static final AtomicLong proxyUnwrapNanos = new AtomicLong(0);

    // Phase 2: Executor-level timing metrics
    private static final AtomicLong ruleMatchingNanos = new AtomicLong(0);
    private static final AtomicLong ruleLoopNanos = new AtomicLong(0);
    private static final AtomicLong chunkProcessingNanos = new AtomicLong(0);
    private static final AtomicLong futureCreationNanos = new AtomicLong(0);
    private static final AtomicLong parallelWaitNanos = new AtomicLong(0);
    private static final AtomicLong cacheGetOrCreateNanos = new AtomicLong(0);

    // Phase 3: Fine-grained cache operation timing (investigation)
    private static final AtomicLong cacheLookupNanos = new AtomicLong(0);
    private static final AtomicLong cacheRejectionCheckNanos = new AtomicLong(0);
    private static final AtomicLong cacheAddMappingNanos = new AtomicLong(0);
    private static final AtomicLong cacheMarkRejectedNanos = new AtomicLong(0);
    private static final AtomicLong cacheLookupCount = new AtomicLong(0);
    private static final AtomicLong cacheRejectionCheckCount = new AtomicLong(0);
    private static final AtomicLong cacheHitCount = new AtomicLong(0);
    private static final AtomicLong cacheRejectionHitCount = new AtomicLong(0);
    private static final AtomicLong cacheMissCount = new AtomicLong(0);
    private static final AtomicLong cacheAddMappingCount = new AtomicLong(0);
    private static final AtomicLong cacheMarkRejectedCount = new AtomicLong(0);

    // Per-extension method metrics
    private static final ConcurrentHashMap<String, AtomicLong> extensionMethodCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> extensionMethodNanosMap = new ConcurrentHashMap<>();

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

    // Test-accessible getters for counter values
    public static long getRuleIterations() {
        return ruleIterations.get();
    }

    public static long getRuleExecutions() {
        return ruleExecutions.get();
    }

    public static long getGuardEvaluations() {
        return guardEvaluations.get();
    }

    public static long getEquivalentCalls() {
        return equivalentCalls.get();
    }

    public static long getEquivalentCacheHits() {
        return equivalentCacheHits.get();
    }

    public static long getEquivalentCacheMisses() {
        return equivalentCacheMisses.get();
    }

    public static void reset() {
        // Total transformation timing
        totalTransformationNanos.set(0);
        transformationStartNanos = 0;

        // Count metrics
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

        // New count metrics
        createTargetCalls.set(0);
        setXmiIdCalls.set(0);
        extensionMethodCalls.set(0);
        attributeSetCalls.set(0);
        referenceSetCalls.set(0);
        equivalentDiscriminatedCalls.set(0);
        equivalentsCalls.set(0);
        postProcessingCalls.set(0);

        // Time metrics
        equivalentTotalNanos.set(0);
        getRulesForSourceNanos.set(0);
        guardEvaluationNanos.set(0);
        ruleExecutionNanos.set(0);
        findByXmiIdNanos.set(0);
        cacheOperationNanos.set(0);
        lockWaitNanos.set(0);

        // New time metrics
        createTargetNanos.set(0);
        setXmiIdNanos.set(0);
        extensionMethodNanos.set(0);
        attributeSetNanos.set(0);
        referenceSetNanos.set(0);
        equivalentDiscriminatedNanos.set(0);
        equivalentsNanos.set(0);
        postProcessingNanos.set(0);
        stagingCommitNanos.set(0);
        modelIterationNanos.set(0);
        deferredOperationsNanos.set(0);
        proxyUnwrapNanos.set(0);

        // Phase 2 metrics
        ruleMatchingNanos.set(0);
        ruleLoopNanos.set(0);
        chunkProcessingNanos.set(0);
        futureCreationNanos.set(0);
        parallelWaitNanos.set(0);
        cacheGetOrCreateNanos.set(0);

        // Phase 3: Fine-grained cache metrics
        cacheLookupNanos.set(0);
        cacheRejectionCheckNanos.set(0);
        cacheAddMappingNanos.set(0);
        cacheMarkRejectedNanos.set(0);
        cacheLookupCount.set(0);
        cacheRejectionCheckCount.set(0);
        cacheHitCount.set(0);
        cacheRejectionHitCount.set(0);
        cacheMissCount.set(0);
        cacheAddMappingCount.set(0);
        cacheMarkRejectedCount.set(0);

        // Per-rule and per-method maps
        ruleExecutionCounts.clear();
        ruleExecutionNanosMap.clear();
        extensionMethodCounts.clear();
        extensionMethodNanosMap.clear();

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

    // NEW: Additional recording methods
    public static void recordCreateTarget() {
        if (enabled) createTargetCalls.incrementAndGet();
    }

    public static void recordSetXmiId() {
        if (enabled) setXmiIdCalls.incrementAndGet();
    }

    public static void recordExtensionMethodCall(String methodName) {
        if (enabled) {
            extensionMethodCalls.incrementAndGet();
            extensionMethodCounts.computeIfAbsent(methodName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public static void recordAttributeSet() {
        if (enabled) attributeSetCalls.incrementAndGet();
    }

    public static void recordReferenceSet() {
        if (enabled) referenceSetCalls.incrementAndGet();
    }

    public static void recordEquivalentDiscriminatedCall() {
        if (enabled) equivalentDiscriminatedCalls.incrementAndGet();
    }

    public static void recordEquivalentsCall() {
        if (enabled) equivalentsCalls.incrementAndGet();
    }

    public static void recordPostProcessingCall() {
        if (enabled) postProcessingCalls.incrementAndGet();
    }

    // Transformation timing
    public static void startTransformation() {
        if (enabled) transformationStartNanos = System.nanoTime();
    }

    public static void endTransformation() {
        if (enabled && transformationStartNanos > 0) {
            totalTransformationNanos.addAndGet(System.nanoTime() - transformationStartNanos);
        }
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

    // NEW: Additional timing methods
    public static void addCreateTargetNanos(long nanos) {
        if (enabled) createTargetNanos.addAndGet(nanos);
    }

    public static void addSetXmiIdNanos(long nanos) {
        if (enabled) setXmiIdNanos.addAndGet(nanos);
    }

    public static void addExtensionMethodNanos(String methodName, long nanos) {
        if (enabled) {
            extensionMethodNanos.addAndGet(nanos);
            extensionMethodNanosMap.computeIfAbsent(methodName, k -> new AtomicLong(0)).addAndGet(nanos);
        }
    }

    public static void addAttributeSetNanos(long nanos) {
        if (enabled) attributeSetNanos.addAndGet(nanos);
    }

    public static void addReferenceSetNanos(long nanos) {
        if (enabled) referenceSetNanos.addAndGet(nanos);
    }

    public static void addEquivalentDiscriminatedNanos(long nanos) {
        if (enabled) equivalentDiscriminatedNanos.addAndGet(nanos);
    }

    public static void addEquivalentsNanos(long nanos) {
        if (enabled) equivalentsNanos.addAndGet(nanos);
    }

    public static void addPostProcessingNanos(long nanos) {
        if (enabled) postProcessingNanos.addAndGet(nanos);
    }

    public static void addStagingCommitNanos(long nanos) {
        if (enabled) stagingCommitNanos.addAndGet(nanos);
    }

    public static void addDeferredOperationsNanos(long nanos) {
        if (enabled) deferredOperationsNanos.addAndGet(nanos);
    }

    public static void addModelIterationNanos(long nanos) {
        if (enabled) modelIterationNanos.addAndGet(nanos);
    }

    public static void addProxyUnwrapNanos(long nanos) {
        if (enabled) proxyUnwrapNanos.addAndGet(nanos);
    }

    // Phase 2: Executor-level timing methods
    public static void addRuleMatchingNanos(long nanos) {
        if (enabled) ruleMatchingNanos.addAndGet(nanos);
    }

    public static void addRuleLoopNanos(long nanos) {
        if (enabled) ruleLoopNanos.addAndGet(nanos);
    }

    public static void addChunkProcessingNanos(long nanos) {
        if (enabled) chunkProcessingNanos.addAndGet(nanos);
    }

    public static void addFutureCreationNanos(long nanos) {
        if (enabled) futureCreationNanos.addAndGet(nanos);
    }

    public static void addParallelWaitNanos(long nanos) {
        if (enabled) parallelWaitNanos.addAndGet(nanos);
    }

    public static void addCacheGetOrCreateNanos(long nanos) {
        if (enabled) cacheGetOrCreateNanos.addAndGet(nanos);
    }

    // Phase 3: Fine-grained cache timing methods
    public static void addCacheLookupNanos(long nanos) {
        if (enabled) {
            cacheLookupNanos.addAndGet(nanos);
            cacheLookupCount.incrementAndGet();
        }
    }

    public static void recordCacheHit() {
        if (enabled) cacheHitCount.incrementAndGet();
    }

    public static void addCacheRejectionCheckNanos(long nanos) {
        if (enabled) {
            cacheRejectionCheckNanos.addAndGet(nanos);
            cacheRejectionCheckCount.incrementAndGet();
        }
    }

    public static void recordCacheRejectionHit() {
        if (enabled) cacheRejectionHitCount.incrementAndGet();
    }

    public static void recordCacheMiss() {
        if (enabled) cacheMissCount.incrementAndGet();
    }

    public static void addCacheAddMappingNanos(long nanos) {
        if (enabled) {
            cacheAddMappingNanos.addAndGet(nanos);
            cacheAddMappingCount.incrementAndGet();
        }
    }

    public static void addCacheMarkRejectedNanos(long nanos) {
        if (enabled) {
            cacheMarkRejectedNanos.addAndGet(nanos);
            cacheMarkRejectedCount.incrementAndGet();
        }
    }

    /**
     * Print a comprehensive performance report.
     */
    public static String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ZETA TRANSFORMATION PERFORMANCE REPORT ==========\n\n");

        // Total transformation time
        long totalTransformMs = totalTransformationNanos.get() / 1_000_000;
        if (totalTransformMs > 0) {
            sb.append(String.format("=== TOTAL TRANSFORMATION TIME: %,d ms ===\n\n", totalTransformMs));
        }

        sb.append("=== OPERATION COUNTS ===\n");
        sb.append(String.format("  equivalent() calls:           %,d\n", equivalentCalls.get()));
        sb.append(String.format("    - Cache hits:               %,d (%.1f%%)\n",
                equivalentCacheHits.get(),
                equivalentCalls.get() > 0 ? (100.0 * equivalentCacheHits.get() / equivalentCalls.get()) : 0));
        sb.append(String.format("    - Cache misses:             %,d (%.1f%%)\n",
                equivalentCacheMisses.get(),
                equivalentCalls.get() > 0 ? (100.0 * equivalentCacheMisses.get() / equivalentCalls.get()) : 0));
        sb.append(String.format("  equivalentDiscriminated():    %,d\n", equivalentDiscriminatedCalls.get()));
        sb.append(String.format("  equivalents() calls:          %,d\n", equivalentsCalls.get()));
        sb.append(String.format("  createTarget() calls:         %,d\n", createTargetCalls.get()));
        sb.append(String.format("  setXmiId() calls:             %,d\n", setXmiIdCalls.get()));
        sb.append(String.format("  extensionMethod() calls:      %,d\n", extensionMethodCalls.get()));
        sb.append(String.format("  getRulesForSource() calls:    %,d\n", getRulesForSourceCalls.get()));
        sb.append(String.format("  Rule iterations:              %,d\n", ruleIterations.get()));
        sb.append(String.format("  Guard evaluations:            %,d\n", guardEvaluations.get()));
        sb.append(String.format("  Rule executions:              %,d\n", ruleExecutions.get()));
        sb.append(String.format("  findByXmiId() calls:          %,d\n", findByXmiIdCalls.get()));
        sb.append(String.format("  findByXmiId() scans:          %,d\n", findByXmiIdScans.get()));
        sb.append(String.format("  Lock acquisitions:            %,d\n", lockAcquisitions.get()));

        // Calculate accounted and unaccounted time
        long greedyMs = greedyRuleNanos.get() / 1_000_000;
        long equivalentMs = equivalentTotalNanos.get() / 1_000_000;
        long createTargetMs = createTargetNanos.get() / 1_000_000;
        long setXmiIdMs = setXmiIdNanos.get() / 1_000_000;
        long extensionMs = extensionMethodNanos.get() / 1_000_000;
        long equivDiscMs = equivalentDiscriminatedNanos.get() / 1_000_000;
        long equivsMs = equivalentsNanos.get() / 1_000_000;
        long stagingMs = stagingCommitNanos.get() / 1_000_000;
        long proxyUnwrapMs = proxyUnwrapNanos.get() / 1_000_000;
        long modelIterMs = modelIterationNanos.get() / 1_000_000;
        long postProcMs = postProcessingNanos.get() / 1_000_000;

        // Phase 2 metrics (these are nested/overlapping - shown for debugging)
        long ruleMatchMs = ruleMatchingNanos.get() / 1_000_000;
        long ruleLoopMs = ruleLoopNanos.get() / 1_000_000;
        long chunkProcMs = chunkProcessingNanos.get() / 1_000_000;
        long futureCreateMs = futureCreationNanos.get() / 1_000_000;
        long parallelWaitMs = parallelWaitNanos.get() / 1_000_000;
        long cacheGetOrCreateMs = cacheGetOrCreateNanos.get() / 1_000_000;

        // Calculate exclusive times to avoid double-counting
        // Hierarchy: parallelWait > chunkProcessing > ruleLoop > (ruleMatching + cacheGetOrCreate + greedyExecution)
        // ruleLoop exclusive = ruleLoop - cacheGetOrCreate - ruleMatching (greedy is already separate)
        long ruleLoopExclusiveMs = Math.max(0, ruleLoopMs - cacheGetOrCreateMs - ruleMatchMs);
        // cacheGetOrCreate exclusive = cacheGetOrCreate - greedy execution - guard evaluation
        // (both greedy and guard are measured inside cacheGetOrCreate)
        long cacheExclusiveMs = Math.max(0, cacheGetOrCreateMs - greedyMs - guardEvaluationNanos.get() / 1_000_000);

        // ACCOUNTED uses exclusive metrics only (no container metrics that overlap)
        // parallelWait is wall-clock time (not additive with CPU time metrics)
        long accountedMs = greedyMs + equivalentMs + createTargetMs + setXmiIdMs + extensionMs
                + equivDiscMs + equivsMs + stagingMs + proxyUnwrapMs + modelIterMs + postProcMs
                + ruleMatchMs + ruleLoopExclusiveMs + cacheExclusiveMs + futureCreateMs;
        long unaccountedMs = totalTransformMs > 0 ? totalTransformMs - accountedMs : 0;

        // Guard evaluation timing (shown separately from greedy rule execution)
        long guardEvalMs = guardEvaluationNanos.get() / 1_000_000;

        sb.append("\n=== TIMING BREAKDOWN (ALL COMPONENTS) ===\n");
        if (totalTransformMs > 0) {
            sb.append(String.format("  Greedy rule execution:        %,7d ms (%5.1f%%)\n",
                    greedyMs, 100.0 * greedyMs / totalTransformMs));
            sb.append(String.format("    (Guard evaluation):         %,7d ms (%5.1f%% of cache ops, %,d evals)\n",
                    guardEvalMs, cacheGetOrCreateMs > 0 ? 100.0 * guardEvalMs / cacheGetOrCreateMs : 0, guardEvaluations.get()));
            sb.append(String.format("  equivalent() total:           %,7d ms (%5.1f%%)\n",
                    equivalentMs, 100.0 * equivalentMs / totalTransformMs));
            sb.append(String.format("  equivalentDiscriminated():    %,7d ms (%5.1f%%)\n",
                    equivDiscMs, 100.0 * equivDiscMs / totalTransformMs));
            sb.append(String.format("  equivalents():                %,7d ms (%5.1f%%)\n",
                    equivsMs, 100.0 * equivsMs / totalTransformMs));
            sb.append(String.format("  createTarget():               %,7d ms (%5.1f%%)\n",
                    createTargetMs, 100.0 * createTargetMs / totalTransformMs));
            sb.append(String.format("  setXmiId():                   %,7d ms (%5.1f%%)\n",
                    setXmiIdMs, 100.0 * setXmiIdMs / totalTransformMs));
            sb.append(String.format("  Extension methods:            %,7d ms (%5.1f%%)\n",
                    extensionMs, 100.0 * extensionMs / totalTransformMs));
            sb.append(String.format("  Model iteration:              %,7d ms (%5.1f%%)\n",
                    modelIterMs, 100.0 * modelIterMs / totalTransformMs));
            sb.append(String.format("  Staging commit:               %,7d ms (%5.1f%%)\n",
                    stagingMs, 100.0 * stagingMs / totalTransformMs));
            sb.append(String.format("  Proxy unwrap:                 %,7d ms (%5.1f%%)\n",
                    proxyUnwrapMs, 100.0 * proxyUnwrapMs / totalTransformMs));
            sb.append(String.format("  Post-processing:              %,7d ms (%5.1f%%)\n",
                    postProcMs, 100.0 * postProcMs / totalTransformMs));
            sb.append(String.format("  Rule matching:                %,7d ms (%5.1f%%)\n",
                    ruleMatchMs, 100.0 * ruleMatchMs / totalTransformMs));
            sb.append(String.format("  Rule loop (exclusive):        %,7d ms (%5.1f%%)\n",
                    ruleLoopExclusiveMs, 100.0 * ruleLoopExclusiveMs / totalTransformMs));
            sb.append(String.format("  Cache ops (exclusive):        %,7d ms (%5.1f%%)\n",
                    cacheExclusiveMs, 100.0 * cacheExclusiveMs / totalTransformMs));
            sb.append(String.format("  Future creation:              %,7d ms (%5.1f%%)\n",
                    futureCreateMs, 100.0 * futureCreateMs / totalTransformMs));
            sb.append(String.format("  ----------------------------------------\n"));
            sb.append(String.format("  ACCOUNTED:                    %,7d ms (%5.1f%%)\n",
                    accountedMs, 100.0 * accountedMs / totalTransformMs));
            sb.append(String.format("  UNACCOUNTED:                  %,7d ms (%5.1f%%)\n",
                    unaccountedMs, 100.0 * unaccountedMs / totalTransformMs));

            // Show parallel execution summary (wall-clock vs CPU time)
            if (parallelWaitMs > 0) {
                sb.append(String.format("\n  --- Parallel Execution (wall-clock) ---\n"));
                sb.append(String.format("  Parallel wait (wall-clock):   %,7d ms\n", parallelWaitMs));
                sb.append(String.format("  Chunk CPU time (total):       %,7d ms\n", chunkProcMs));
                if (chunkProcMs > 0) {
                    sb.append(String.format("  Parallelization efficiency:   %5.1fx\n",
                            (double) chunkProcMs / parallelWaitMs));
                }
            }
        } else {
            sb.append("  (No total time recorded - call startTransformation/endTransformation)\n");
        }

        sb.append("\n=== equivalent() INTERNAL BREAKDOWN ===\n");
        long eqNanos = equivalentTotalNanos.get();
        sb.append(String.format("  getRulesForSource:            %,7d ms (%.1f%%)\n",
                getRulesForSourceNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * getRulesForSourceNanos.get() / eqNanos) : 0));
        sb.append(String.format("  Guard evaluation:             %,7d ms (%.1f%%)\n",
                guardEvaluationNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * guardEvaluationNanos.get() / eqNanos) : 0));
        sb.append(String.format("  Rule execution:               %,7d ms (%.1f%%)\n",
                ruleExecutionNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * ruleExecutionNanos.get() / eqNanos) : 0));
        sb.append(String.format("  findByXmiId:                  %,7d ms (%.1f%%)\n",
                findByXmiIdNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * findByXmiIdNanos.get() / eqNanos) : 0));
        sb.append(String.format("  Cache operations:             %,7d ms (%.1f%%)\n",
                cacheOperationNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * cacheOperationNanos.get() / eqNanos) : 0));
        sb.append(String.format("  Lock wait:                    %,7d ms (%.1f%%)\n",
                lockWaitNanos.get() / 1_000_000,
                eqNanos > 0 ? (100.0 * lockWaitNanos.get() / eqNanos) : 0));

        // Fine-grained cache operation breakdown (Phase 3 investigation)
        long cacheOpsTotal = cacheGetOrCreateNanos.get();
        long lookupMs = cacheLookupNanos.get() / 1_000_000;
        long rejCheckMs = cacheRejectionCheckNanos.get() / 1_000_000;
        long addMapMs = cacheAddMappingNanos.get() / 1_000_000;
        long markRejMs = cacheMarkRejectedNanos.get() / 1_000_000;
        long cacheOpsMs = cacheOpsTotal / 1_000_000;
        long cacheInstrumentedMs = lookupMs + rejCheckMs + addMapMs + markRejMs;
        long cacheUnaccountedMs = cacheOpsMs - cacheInstrumentedMs - greedyMs;

        sb.append("\n=== FINE-GRAINED CACHE BREAKDOWN (Phase 3) ===\n");
        sb.append(String.format("  Total getOrCreate ops:        %,d\n", cacheLookupCount.get()));
        sb.append(String.format("    - Cache hits:               %,d (%.1f%%)\n",
                cacheHitCount.get(),
                cacheLookupCount.get() > 0 ? (100.0 * cacheHitCount.get() / cacheLookupCount.get()) : 0));
        sb.append(String.format("    - Rejection hits:           %,d (%.1f%%)\n",
                cacheRejectionHitCount.get(),
                cacheLookupCount.get() > 0 ? (100.0 * cacheRejectionHitCount.get() / cacheLookupCount.get()) : 0));
        sb.append(String.format("    - Cache misses:             %,d (%.1f%%)\n",
                cacheMissCount.get(),
                cacheLookupCount.get() > 0 ? (100.0 * cacheMissCount.get() / cacheLookupCount.get()) : 0));
        sb.append(String.format("  ----------------------------------------\n"));
        sb.append(String.format("  Cache lookup (getByRule):     %,7d ms (%,d calls, %.3f μs/call)\n",
                lookupMs, cacheLookupCount.get(),
                cacheLookupCount.get() > 0 ? (double) cacheLookupNanos.get() / cacheLookupCount.get() / 1000 : 0));
        sb.append(String.format("  Rejection check (isRejected): %,7d ms (%,d calls, %.3f μs/call)\n",
                rejCheckMs, cacheRejectionCheckCount.get(),
                cacheRejectionCheckCount.get() > 0 ? (double) cacheRejectionCheckNanos.get() / cacheRejectionCheckCount.get() / 1000 : 0));
        sb.append(String.format("  Add mapping:                  %,7d ms (%,d calls, %.3f μs/call)\n",
                addMapMs, cacheAddMappingCount.get(),
                cacheAddMappingCount.get() > 0 ? (double) cacheAddMappingNanos.get() / cacheAddMappingCount.get() / 1000 : 0));
        sb.append(String.format("  Mark rejected:                %,7d ms (%,d calls, %.3f μs/call)\n",
                markRejMs, cacheMarkRejectedCount.get(),
                cacheMarkRejectedCount.get() > 0 ? (double) cacheMarkRejectedNanos.get() / cacheMarkRejectedCount.get() / 1000 : 0));
        sb.append(String.format("  Greedy rule execution:        %,7d ms (already reported above)\n", greedyMs));
        sb.append(String.format("  ----------------------------------------\n"));
        sb.append(String.format("  Cache instrumented total:     %,7d ms\n", cacheInstrumentedMs + greedyMs));
        sb.append(String.format("  Cache total (getOrCreate):    %,7d ms\n", cacheOpsMs));
        sb.append(String.format("  Cache UNACCOUNTED:            %,7d ms (%.1f%% of cache ops)\n",
                cacheUnaccountedMs,
                cacheOpsMs > 0 ? (100.0 * cacheUnaccountedMs / cacheOpsMs) : 0));

        // Top 10 slowest extension methods
        if (!extensionMethodNanosMap.isEmpty()) {
            sb.append("\n=== TOP 10 SLOWEST EXTENSION METHODS ===\n");
            extensionMethodNanosMap.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(10)
                    .forEach(entry -> {
                        String methodName = entry.getKey();
                        long nanos = entry.getValue().get();
                        long count = extensionMethodCounts.getOrDefault(methodName, new AtomicLong(0)).get();
                        sb.append(String.format("  %-40s %,7d ms (%,d calls, %.3f ms/call)\n",
                                methodName,
                                nanos / 1_000_000,
                                count,
                                count > 0 ? (double) nanos / count / 1_000_000 : 0));
                    });
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
                        sb.append(String.format("  %-40s %,7d ms (%,d calls, %.3f ms/call)\n",
                                ruleName,
                                nanos / 1_000_000,
                                count,
                                count > 0 ? (double) nanos / count / 1_000_000 : 0));
                    });
        }

        // Greedy rule execution metrics
        sb.append("\n=== GREEDY RULE EXECUTION ===\n");
        sb.append(String.format("  Total greedy executions:      %,d\n", greedyRuleExecutions.get()));
        sb.append(String.format("  Total greedy time:            %,d ms\n", greedyRuleNanos.get() / 1_000_000));
        if (greedyRuleExecutions.get() > 0) {
            sb.append(String.format("  Avg time per execution:       %.3f ms\n",
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
                        sb.append(String.format("  %-40s %,7d ms (%,d calls, %.3f ms/call)\n",
                                ruleName,
                                nanos / 1_000_000,
                                count,
                                count > 0 ? (double) nanos / count / 1_000_000 : 0));
                    });
        }

        // Potential issues
        sb.append("\n=== POTENTIAL ISSUES ===\n");
        boolean hasIssues = false;
        if (equivalentCacheMisses.get() > equivalentCacheHits.get()) {
            sb.append("  WARNING: Low cache hit rate - consider caching more aggressively\n");
            hasIssues = true;
        }
        if (findByXmiIdScans.get() > 1000) {
            sb.append(String.format("  WARNING: High XMI ID scan count (%,d) - consider indexing pending IDs\n",
                    findByXmiIdScans.get()));
            hasIssues = true;
        }
        if (getRulesForSourceCalls.get() > 0 &&
            (double) ruleIterations.get() / getRulesForSourceCalls.get() > 20) {
            sb.append(String.format("  WARNING: High rule iterations per source (%.1f avg) - consider caching rule lookups\n",
                    (double) ruleIterations.get() / getRulesForSourceCalls.get()));
            hasIssues = true;
        }
        if (totalTransformMs > 0 && unaccountedMs > totalTransformMs * 0.3) {
            sb.append(String.format("  WARNING: %.1f%% of time is UNACCOUNTED - add more instrumentation\n",
                    100.0 * unaccountedMs / totalTransformMs));
            hasIssues = true;
        }
        if (!hasIssues) {
            sb.append("  (No issues detected)\n");
        }

        sb.append("\n============================================================\n");
        return sb.toString();
    }
}
