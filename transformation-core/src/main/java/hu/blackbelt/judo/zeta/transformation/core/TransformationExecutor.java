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
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parallel transformation executor with two-phase execution strategy.
 *
 * <p>Executes transformations in distinct phases:</p>
 * <ol>
 *   <li>Phase 1 (Eager): Execute all non-lazy, non-abstract rules</li>
 *   <li>Phase 2 (Lazy): Lazy rules execute on-demand via equivalent() calls</li>
 * </ol>
 *
 * <p>Supports multi-source rules with Cartesian product execution. When a rule has
 * multiple @Transform annotations, elements are collected from each alias and all
 * combinations are processed:</p>
 * <pre>
 * // If "asm" has [A, B, C] and "mapping" has [M1, M2]
 * // Rule fires 6 times: (A,M1), (A,M2), (B,M1), (B,M2), (C,M1), (C,M2)
 * </pre>
 */
public class TransformationExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransformationExecutor.class);

    /**
     * Default minimum number of elements required for parallel execution.
     */
    public static final int DEFAULT_PARALLEL_THRESHOLD = 1000;
    private static final int DEFAULT_CHUNK_SIZE = 100;

    private final TransformationRegistry registry;
    private final TransformationContext context;
    private final boolean parallel;
    private final int parallelThreshold;
    private final int chunkSize;
    private final boolean etlCompatibilityMode;
    private volatile ExecutorService executor;

    /**
     * Maximum iterations for the activity-based fixpoint loop.
     * Prevents infinite loops if rules keep activating each other.
     */
    private static final int MAX_ACTIVITY_BASED_ITERATIONS = 100;

    /**
     * Shared exception holder for fail-fast error handling.
     * Reset for each transform() call.
     */
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();

    /**
     * Cache for model traversal results during element collection.
     * Maps alias -> type -> elements.
     * Cleared at end of each transformation to prevent memory leaks.
     *
     * <p>This optimization reduces O(r × n) element collection to O(unique_types × n)
     * where r = number of rules and n = model size. If 10 rules share the same
     * (alias, type), the model is traversed once instead of 10 times.</p>
     */
    private Map<String, Map<Class<?>, Collection<EObject>>> elementsByAliasAndType;

    /**
     * Create executor with default settings.
     *
     * @deprecated Use {@link Builder} instead for better configuration control.
     */
    @Deprecated
    public TransformationExecutor(
            TransformationRegistry registry,
            TransformationContext context,
            boolean parallel
    ) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.context = Objects.requireNonNull(context, "context is required");
        this.parallel = parallel;
        this.parallelThreshold = DEFAULT_PARALLEL_THRESHOLD;
        this.chunkSize = DEFAULT_CHUNK_SIZE;
        this.etlCompatibilityMode = false;
    }

    private TransformationExecutor(Builder builder) {
        this.registry = Objects.requireNonNull(builder.registry, "registry is required");
        this.context = Objects.requireNonNull(builder.context, "context is required");
        this.parallel = builder.parallel;
        this.parallelThreshold = builder.parallelThreshold;
        this.chunkSize = builder.chunkSize;
        this.etlCompatibilityMode = builder.etlCompatibilityMode;
    }

    /**
     * Create a new builder for TransformationExecutor.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TransformationExecutor.
     */
    public static class Builder {
        private TransformationRegistry registry;
        private TransformationContext context;
        private boolean parallel = true;
        private int parallelThreshold = DEFAULT_PARALLEL_THRESHOLD;
        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private boolean etlCompatibilityMode = false;

        public Builder registry(TransformationRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder context(TransformationContext context) {
            this.context = context;
            return this;
        }

        public Builder parallel(boolean parallel) {
            this.parallel = parallel;
            return this;
        }

        public Builder parallelThreshold(int threshold) {
            this.parallelThreshold = threshold;
            return this;
        }

        public Builder chunkSize(int size) {
            this.chunkSize = size;
            return this;
        }

        /**
         * Enable ETL compatibility mode.
         *
         * <p>When enabled, all {@code @Greedy @Lazy} rules are treated as if they
         * also had {@code @ActivityBased}. This means they only process elements
         * that are "activated" via {@code equivalent()} calls, matching Epsilon ETL
         * behavior.</p>
         *
         * @param enabled true to enable ETL compatibility mode
         * @return this builder
         */
        public Builder etlCompatibilityMode(boolean enabled) {
            this.etlCompatibilityMode = enabled;
            return this;
        }

        public TransformationExecutor build() {
            return new TransformationExecutor(this);
        }
    }

    /**
     * Check if a rule is effectively activity-based.
     *
     * <p>A rule is effectively activity-based if:</p>
     * <ul>
     *   <li>It has the {@code @ActivityBased} annotation, OR</li>
     *   <li>ETL compatibility mode is enabled AND the rule is both {@code @Greedy} and {@code @Lazy}</li>
     * </ul>
     *
     * @param rule the rule to check
     * @return true if the rule should use activity-based processing
     */
    private boolean isEffectivelyActivityBased(TransformRuleDescriptor rule) {
        if (rule.isActivityBased()) {
            return true;
        }
        // In ETL compatibility mode, all @Greedy @Lazy rules are activity-based
        return etlCompatibilityMode && rule.isGreedy() && rule.isLazy();
    }

    /**
     * Execute Phase 2: Process activity-based rules for activated elements.
     *
     * <p>This method runs after Phase 1 (eager execution) completes. It processes only
     * the source elements that were "activated" via {@code equivalent()} calls during
     * Phase 1.</p>
     *
     * <p>Uses a fixpoint loop to handle late activations - if an activity-based rule
     * activates another activity-based rule via {@code equivalent()}, the newly activated
     * elements are processed in subsequent iterations.</p>
     */
    private void executeActivityBasedRules() {
        ActivationTracker tracker = context.getActivationTracker();

        // Find all effectively activity-based rules
        List<TransformRuleDescriptor> activityBasedRules = registry.getAllRules().stream()
                .filter(this::isEffectivelyActivityBased)
                .collect(Collectors.toList());

        if (activityBasedRules.isEmpty()) {
            return;
        }

        log.debug("Executing Phase 2: {} activity-based rules with {} total activations",
                activityBasedRules.size(), tracker.getTotalActivationCount());

        // Track which (source, rule) pairs have been processed to avoid re-execution
        Set<String> processedKeys = new HashSet<>();

        // Fixpoint loop: continue until no new activations are processed
        int iteration = 0;
        boolean madeProgress;

        do {
            madeProgress = false;
            iteration++;

            if (iteration > MAX_ACTIVITY_BASED_ITERATIONS) {
                log.warn("Activity-based execution exceeded {} iterations, stopping to prevent infinite loop",
                        MAX_ACTIVITY_BASED_ITERATIONS);
                break;
            }

            for (TransformRuleDescriptor rule : activityBasedRules) {
                if (firstError.get() != null) {
                    return;
                }

                Set<EObject> activated = tracker.getActivated(rule.getName());
                for (EObject source : activated) {
                    String key = rule.getName() + ":" + System.identityHashCode(source);

                    // Skip if already processed
                    if (processedKeys.contains(key)) {
                        continue;
                    }

                    // Skip if already in cache (executed via equivalent() during Phase 1)
                    if (context.getElementResolutionCache().getByRule(source, rule.getName()) != null) {
                        processedKeys.add(key);
                        continue;
                    }

                    // Check if rule applies and guard passes
                    if (!rule.appliesTo(source)) {
                        processedKeys.add(key);
                        continue;
                    }

                    if (!rule.evaluateGuard(source, context)) {
                        processedKeys.add(key);
                        continue;
                    }

                    // Execute the rule
                    try {
                        EObject target = rule.execute(source, context);
                        if (target != null) {
                            context.getElementResolutionCache().addMapping(
                                    source, rule.getName(), target, rule.isPrimary());
                            madeProgress = true;
                            log.trace("Activity-based rule {} processed activated element {}",
                                    rule.getName(), source);
                        }
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                        return;
                    }

                    processedKeys.add(key);
                }
            }
        } while (madeProgress);

        log.debug("Phase 2 completed after {} iteration(s), processed {} elements",
                iteration, processedKeys.size());
    }

    /**
     * Reset executor state for reuse.
     * Called at the start of each transform() invocation.
     */
    private void reset() {
        firstError.set(null);
        context.clearStagedElements();
        context.clearElementOrder();
        context.clearPendingXmiIds();
        context.clearExecutingLazyRules();
        context.getActivationTracker().clear();
        // Clear per-rule guard rejection caches (ETL-compatible)
        registry.clearAllRejectedSets();
        // Clear atomic cache rejection tracking (for getOrCreate pattern)
        context.getElementResolutionCache().clearRejections();
        // Propagate ETL compatibility mode to context for equivalent() calls
        context.setEtlCompatibilityMode(etlCompatibilityMode);
    }

    /**
     * Initialize the element collection cache.
     * Called at the start of transform() before element collection.
     */
    private void initElementCache() {
        elementsByAliasAndType = new HashMap<>();
    }

    /**
     * Clear the element collection cache.
     * Called at the end of transform() to prevent memory leaks.
     */
    private void clearElementCache() {
        elementsByAliasAndType = null;
    }

    /**
     * Get elements from the cache, populating on first access for each (alias, type) pair.
     *
     * <p>Uses computeIfAbsent for lazy population - only traverses the model once
     * per unique (alias, type) pair, regardless of how many rules use that combination.</p>
     *
     * @param alias the resource alias
     * @param type the element type
     * @param <T> the element type
     * @return collection of elements (never null)
     */
    @SuppressWarnings("unchecked")
    private <T extends EObject> Collection<T> getCachedElements(String alias, Class<T> type) {
        return (Collection<T>) elementsByAliasAndType
                .computeIfAbsent(alias, k -> new HashMap<>())
                .computeIfAbsent(type, t -> new ArrayList<>(context.all(alias, type)));
    }

    /**
     * Get or create the executor service for parallel transformation.
     */
    private ExecutorService getOrCreateExecutor() {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    executor = Executors.newWorkStealingPool();
                }
            }
        }
        return executor;
    }

    /**
     * Transform all source elements by collecting them from registered resources based on rule definitions.
     *
     * <p>This method automatically collects source elements from the appropriate resource aliases
     * as defined by the @Transform annotations on registered rules. For rules using the new
     * @Transform annotation, elements are collected from the specified alias. For backward
     * compatibility, rules without @Transform annotations use the default "source" alias.</p>
     *
     * <p>Multi-source rules with multiple @Transform annotations are handled specially:
     * elements are collected from each alias and processed as Cartesian product tuples.</p>
     *
     * <p>Executor is reusable - state is reset at the start of each call.</p>
     *
     * @return the transformation result
     * @throws TransformationException if transformation fails (fail-fast behavior)
     */
    public TransformationResult transform() {
        // Reset state for reuse
        reset();
        // Initialize element collection cache
        initElementCache();

        long startTime = System.currentTimeMillis();

        // Start total transformation timing
        TransformationMetrics.startTransformation();

        // Invoke pre-transformation hooks
        registry.invokePreTransformationHooks(context);

        try {
            // Time element collection phase
            long elementCollectionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;

            // Collect single-source elements for single-source rules
            Set<EObject> singleSourceElements = new LinkedHashSet<>();

            // Track multi-source rules to process separately
            List<TransformRuleDescriptor> multiSourceRules = new ArrayList<>();

            for (TransformRuleDescriptor rule : registry.getAllRules()) {
                if (rule.isMultiSource()) {
                    // Handle multi-source rules separately with Cartesian product
                    multiSourceRules.add(rule);
                } else {
                    List<TransformDefinition> transforms = rule.getTransforms();
                    if (!transforms.isEmpty()) {
                        // Single @Transform annotation - collect from specified alias (CACHED)
                        TransformDefinition transform = transforms.get(0);
                        Collection<? extends EObject> elements = getCachedElements(
                                transform.getAlias(), transform.getType());
                        singleSourceElements.addAll(elements);
                    } else {
                        // Backward compatibility: use sourceType with default "source" alias (CACHED)
                        Collection<? extends EObject> elements = getCachedElements(
                                "source", rule.getSourceType());
                        singleSourceElements.addAll(elements);
                    }
                }
            }

            // Record element collection time
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addModelIterationNanos(System.nanoTime() - elementCollectionStart);
            }
            
            // Process single-source elements
            boolean useParallel = parallel && singleSourceElements.size() >= parallelThreshold;
            if (useParallel) {
                transformWithStaging(singleSourceElements);
            } else {
                transformSequential(singleSourceElements);

                // Cleanup contained elements from Resource.contents when autoAddRootElements is enabled.
                // In sequential mode, createTarget() with autoAddRootElements adds elements directly
                // to Resource.contents. When elements are later added to containment references,
                // EMF does NOT automatically remove them from Resource.contents. This cleanup phase
                // removes any elements that have been added to containment (eContainer != null),
                // mirroring the parallel mode commit phase check.
                if (context.isAutoAddRootElements()) {
                    context.cleanupContainedRootElements();
                }
            }

            // Process multi-source rules with Cartesian product
            for (TransformRuleDescriptor rule : multiSourceRules) {
                if (firstError.get() != null) {
                    break;
                }
                executeMultiSourceRule(rule);
            }

            // Phase 2: Execute activity-based rules for activated elements only
            // This matches ETL semantics where @greedy @lazy rules only process
            // elements referenced via equivalent() during Phase 1
            executeActivityBasedRules();

            // Check for errors (fail-fast)
            Throwable error = firstError.get();
            if (error != null) {
                if (error instanceof TransformationException) {
                    throw (TransformationException) error;
                }
                throw new TransformationException("Transformation failed", error);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformation completed in {}ms{}",
                    duration, useParallel ? " (parallel)" : "");

            return new TransformationResult(context, duration);

        } finally {
            // End total transformation timing
            TransformationMetrics.endTransformation();

            // Always disable staging and cleanup
            context.disableStaging();

            // Invoke post-transformation hooks
            registry.invokePostTransformationHooks(context);

            // Clear caches
            context.clearExtensionCache();

            // Clear element collection cache to prevent memory leaks
            clearElementCache();
        }
    }

    /**
     * Transform all source elements.
     *
     * <p>Executor is reusable - state is reset at the start of each call.</p>
     *
     * @param sourceElements elements to transform
     * @return the transformation result
     * @throws TransformationException if transformation fails (fail-fast behavior)
     */
    public TransformationResult transform(Collection<? extends EObject> sourceElements) {
        // Reset state for reuse
        reset();

        long startTime = System.currentTimeMillis();

        // Invoke pre-transformation hooks
        registry.invokePreTransformationHooks(context);

        try {
            // Use parallel only if requested AND element count exceeds threshold
            boolean useParallel = parallel && sourceElements.size() >= parallelThreshold;

            if (useParallel) {
                transformWithStaging(sourceElements);
            } else {
                transformSequential(sourceElements);

                // Cleanup contained elements from Resource.contents when autoAddRootElements is enabled.
                // In sequential mode, createTarget() with autoAddRootElements adds elements directly
                // to Resource.contents. When elements are later added to containment references,
                // EMF does NOT automatically remove them from Resource.contents. This cleanup phase
                // removes any elements that have been added to containment (eContainer != null),
                // mirroring the parallel mode commit phase check.
                if (context.isAutoAddRootElements()) {
                    context.cleanupContainedRootElements();
                }
            }

            // Phase 2: Execute activity-based rules for activated elements only
            // This matches ETL semantics where @greedy @lazy rules only process
            // elements referenced via equivalent() during Phase 1
            executeActivityBasedRules();

            // Check for errors (fail-fast)
            Throwable error = firstError.get();
            if (error != null) {
                if (error instanceof TransformationException) {
                    throw (TransformationException) error;
                }
                throw new TransformationException("Transformation failed", error);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformation completed in {}ms, processed {} elements{}",
                    duration, sourceElements.size(), useParallel ? " (parallel)" : "");

            return new TransformationResult(context, duration);

        } finally {
            // Always disable staging and cleanup
            context.disableStaging();

            // Invoke post-transformation hooks
            registry.invokePostTransformationHooks(context);

            // Clear caches
            context.clearExtensionCache();
        }
    }

    /**
     * Transform with staging enabled for thread-safe parallel execution.
     */
    private void transformWithStaging(Collection<? extends EObject> sourceElements) {
        try {
            // Phase 1: Enable staging and transform in parallel
            context.enableStaging();
            transformParallel(sourceElements);

            // Check for errors before commit
            if (firstError.get() != null) {
                return;
            }

            // Phase 2: Commit staged elements to Resource (single-threaded)
            long commitStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
            context.commitStagedElements();
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addStagingCommitNanos(System.nanoTime() - commitStart);
            }

        } finally {
            context.disableStaging();
            context.clearStagedElements();
        }
    }

    private void transformSequential(Collection<? extends EObject> sourceElements) {
        for (EObject source : sourceElements) {
            // Check for fail-fast
            if (firstError.get() != null) {
                return;
            }
            try {
                context.setCurrentSource(source);
                executeEagerRulesFor(source);
            } finally {
                context.clearCurrentSource();
            }
        }
    }

    private void transformParallel(Collection<? extends EObject> sourceElements) {
        // Time list conversion (ArrayList creation from Set)
        long listConversionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        List<EObject> elementList = new ArrayList<>(sourceElements);
        if (TransformationMetrics.isEnabled()) {
            TransformationMetrics.addModelIterationNanos(System.nanoTime() - listConversionStart);
        }

        int numProcessors = Runtime.getRuntime().availableProcessors();

        // Calculate chunk size (use configured value as minimum)
        int effectiveChunkSize = Math.max(chunkSize, (elementList.size() + numProcessors - 1) / numProcessors);

        // Time partitioning
        long partitionStart = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        List<List<EObject>> chunks = partitionList(elementList, effectiveChunkSize);
        if (TransformationMetrics.isEnabled()) {
            TransformationMetrics.addModelIterationNanos(System.nanoTime() - partitionStart);
        }

        // Process chunks in parallel
        ExecutorService exec = getOrCreateExecutor();
        List<CompletableFuture<Void>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.runAsync(() -> transformChunk(chunk), exec))
                .collect(Collectors.toList());

        // Wait for completion
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void transformChunk(List<EObject> chunk) {
        for (EObject source : chunk) {
            // Check for fail-fast - stop if another thread encountered an error
            if (firstError.get() != null) {
                return;
            }
            try {
                context.setCurrentSource(source);
                executeEagerRulesFor(source);
            } finally {
                context.clearCurrentSource();
            }
        }
    }

    /**
     * Execute a multi-source rule with Cartesian product of elements.
     *
     * <p>Collects elements from each @Transform alias, generates all combinations
     * (Cartesian product), and executes the rule for each tuple.</p>
     *
     * @param rule the multi-source rule to execute
     */
    private void executeMultiSourceRule(TransformRuleDescriptor rule) {
        // Skip lazy and abstract rules
        if (rule.isLazy() || rule.isAbstract()) {
            return;
        }

        List<TransformDefinition> transforms = rule.getTransforms();
        
        // Collect elements from each alias
        List<List<EObject>> elementLists = new ArrayList<>();
        for (TransformDefinition transform : transforms) {
            Collection<? extends EObject> elements = context.all(
                    transform.getAlias(), transform.getType());
            if (elements.isEmpty()) {
                // If any source has no elements, Cartesian product is empty
                return;
            }
            elementLists.add(new ArrayList<>(elements));
        }
        
        // Generate Cartesian product and execute rule for each tuple
        List<EObject[]> cartesianProduct = generateCartesianProduct(elementLists);
        
        log.debug("Executing multi-source rule '{}' with {} tuples from {} sources",
                rule.getName(), cartesianProduct.size(), transforms.size());
        
        // Track executed tuples to avoid duplicates within same execution
        Set<String> executedTuples = new HashSet<>();
        
        for (EObject[] sources : cartesianProduct) {
            if (firstError.get() != null) {
                return;
            }
            
            // Use first element as primary source for context and tracing
            EObject primarySource = sources[0];
            
            // Create unique key for this tuple to prevent duplicate execution
            String tupleKey = createTupleKey(sources, rule.getName());
            if (executedTuples.contains(tupleKey)) continue;
            executedTuples.add(tupleKey);
            
            // Check guard with all sources
            if (!rule.evaluateGuard(sources, context)) continue;
            
            // Execute rule with all sources
            try {
                context.setCurrentSource(primarySource);
                EObject target = rule.execute(sources, context);
                if (target != null) {
                    // Cache with primary source for equivalent() lookups
                    context.getElementResolutionCache().addMapping(
                            primarySource, rule.getName(), target, rule.isPrimary());
                }
            } catch (Exception e) {
                log.error("Error executing multi-source rule '{}' on {}: {}",
                        rule.getName(), java.util.Arrays.toString(sources), e.getMessage(), e);
                TransformationException transformException = new TransformationException(
                        "Error executing rule '" + rule.getName() + "': " + e.getMessage(),
                        e, primarySource, rule.getName());
                firstError.compareAndSet(null, transformException);
                return;
            } finally {
                context.clearCurrentSource();
            }
        }
    }

    /**
     * Create a unique key for a source tuple to prevent duplicate execution.
     *
     * @param sources array of source elements
     * @param ruleName the rule name
     * @return unique string key for this tuple
     */
    private String createTupleKey(EObject[] sources, String ruleName) {
        StringBuilder sb = new StringBuilder(ruleName);
        for (EObject source : sources) {
            sb.append(":").append(System.identityHashCode(source));
        }
        return sb.toString();
    }

    /**
     * Generate Cartesian product of element lists.
     *
     * <p>For example, if input is [[A, B], [1, 2, 3]], the result is:
     * [[A, 1], [A, 2], [A, 3], [B, 1], [B, 2], [B, 3]]</p>
     *
     * @param elementLists list of element lists, one per @Transform annotation
     * @return list of all element tuples (Cartesian product)
     */
    private List<EObject[]> generateCartesianProduct(List<List<EObject>> elementLists) {
        List<EObject[]> result = new ArrayList<>();
        
        if (elementLists.isEmpty()) {
            return result;
        }
        
        // Start with tuples from first list
        for (EObject element : elementLists.get(0)) {
            result.add(new EObject[]{element});
        }
        
        // Extend tuples with elements from remaining lists
        for (int i = 1; i < elementLists.size(); i++) {
            List<EObject> currentList = elementLists.get(i);
            List<EObject[]> newResult = new ArrayList<>();
            
            for (EObject[] tuple : result) {
                for (EObject element : currentList) {
                    // Create new tuple with one more element
                    EObject[] newTuple = java.util.Arrays.copyOf(tuple, tuple.length + 1);
                    newTuple[tuple.length] = element;
                    newResult.add(newTuple);
                }
            }
            
            result = newResult;
        }
        
        return result;
    }

    private void executeEagerRulesFor(EObject source) {
        Collection<TransformRuleDescriptor> rules = registry.getRulesForSource(source.getClass());

        for (TransformRuleDescriptor rule : rules) {
            // Check for fail-fast
            if (firstError.get() != null) {
                return;
            }

            // Pre-checks that can be done outside the lock (rule metadata, not source-specific state)
            // Skip multi-source rules - they are handled by executeMultiSourceRule()
            if (rule.isMultiSource()) continue;

            // Skip lazy rules - they execute on-demand via equivalent()
            if (rule.isLazy()) continue;

            // Skip abstract rules - they only execute via executeParentRule()
            if (rule.isAbstract()) continue;

            // Skip activity-based rules - they execute only for activated elements in Phase 2
            // This matches ETL semantics where @greedy @lazy rules only process
            // elements that were referenced via equivalent()
            if (isEffectivelyActivityBased(rule)) continue;

            // Check if rule applies to this element (type check)
            if (!rule.appliesTo(source)) continue;

            // Check if element comes from the correct resource alias
            if (!isFromExpectedAlias(source, rule)) continue;

            // Atomic get-or-create: lock covers cache check + guard evaluation + rule execution
            // This prevents race conditions where multiple threads could create duplicate targets
            try {
                final String ruleName = rule.getName();
                context.getElementResolutionCache().getOrCreate(
                        source,
                        ruleName,
                        () -> {
                            // Guard evaluation inside the lock to prevent race conditions
                            if (!rule.evaluateGuard(source, context)) {
                                return null;  // Guard rejected - don't execute
                            }
                            // Rule execution inside the lock with timing
                            long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                            EObject result = rule.execute(source, context);
                            if (TransformationMetrics.isEnabled()) {
                                TransformationMetrics.recordGreedyRuleExecution(ruleName, System.nanoTime() - startNanos);
                            }
                            return result;
                        },
                        rule.isPrimary()
                );
            } catch (Exception e) {
                log.error("Error executing rule '{}' on {}: {}",
                        rule.getName(), source, e.getMessage(), e);
                // Set first error for fail-fast (only first error is captured)
                TransformationException transformException = new TransformationException(
                        "Error executing rule '" + rule.getName() + "': " + e.getMessage(),
                        e, source, rule.getName());
                firstError.compareAndSet(null, transformException);
                return; // Stop processing this element
            }
        }
    }

    /**
     * Check if the source element comes from a resource that matches one of the rule's expected aliases.
     *
     * <p>For rules with @Transform annotations, verifies the element is from one of the specified aliases.
     * For backward compatibility, rules without @Transform annotations accept elements from any resource.</p>
     *
     * @param source the source element to check
     * @param rule the rule descriptor
     * @return true if the element is from an expected resource alias
     */
    private boolean isFromExpectedAlias(EObject source, TransformRuleDescriptor rule) {
        List<TransformDefinition> transforms = rule.getTransforms();
        
        // Backward compatibility: rules without @Transform accept elements from any resource
        if (transforms.isEmpty()) {
            return true;
        }
        
        // Check if element is from one of the expected aliases
        org.eclipse.emf.ecore.resource.Resource elementResource = source.eResource();
        if (elementResource == null) {
            log.debug("isFromExpectedAlias: source {} has no resource", source);
            return false;
        }
        
        org.eclipse.emf.ecore.resource.ResourceSet elementResourceSet = elementResource.getResourceSet();
        if (elementResourceSet == null) {
            log.debug("isFromExpectedAlias: source {} resource has no resourceSet", source);
            return false;
        }
        
        for (TransformDefinition transform : transforms) {
            org.eclipse.emf.ecore.resource.ResourceSet aliasResourceSet = 
                    context.getResource(transform.getAlias());
            if (aliasResourceSet != null && aliasResourceSet.equals(elementResourceSet)) {
                return true;
            }
        }
        
        log.debug("isFromExpectedAlias: no match for source {} in rule {}", source, rule.getName());
        return false;
    }

    private <T> List<List<T>> partitionList(List<T> list, int chunkSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            partitions.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return partitions;
    }

    /**
     * Shutdown the executor (call when done with all transformations).
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
