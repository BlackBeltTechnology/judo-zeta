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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Parallel transformation executor with two-phase execution strategy.
 *
 * <p>Executes transformations in distinct phases:</p>
 * <ol>
 *   <li>Phase 1 (Eager): Execute all non-lazy, non-abstract rules</li>
 *   <li>Phase 2 (Lazy): Lazy rules execute on-demand via equivalent() calls</li>
 * </ol>
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
    private volatile ExecutorService executor;

    /**
     * Shared exception holder for fail-fast error handling.
     * Reset for each transform() call.
     */
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();

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
    }

    private TransformationExecutor(Builder builder) {
        this.registry = Objects.requireNonNull(builder.registry, "registry is required");
        this.context = Objects.requireNonNull(builder.context, "context is required");
        this.parallel = builder.parallel;
        this.parallelThreshold = builder.parallelThreshold;
        this.chunkSize = builder.chunkSize;
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

        public TransformationExecutor build() {
            return new TransformationExecutor(this);
        }
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
            }

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
            context.commitStagedElements();

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
        List<EObject> elementList = new ArrayList<>(sourceElements);
        int numProcessors = Runtime.getRuntime().availableProcessors();

        // Calculate chunk size (use configured value as minimum)
        int effectiveChunkSize = Math.max(chunkSize, (elementList.size() + numProcessors - 1) / numProcessors);

        // Partition elements
        List<List<EObject>> chunks = partitionList(elementList, effectiveChunkSize);

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

    private void executeEagerRulesFor(EObject source) {
        Collection<TransformRuleDescriptor> rules = registry.getRulesForSource(source.getClass());

        for (TransformRuleDescriptor rule : rules) {
            // Check for fail-fast
            if (firstError.get() != null) {
                return;
            }

            // Skip lazy rules - they execute on-demand via equivalent()
            if (rule.isLazy()) continue;

            // Skip abstract rules - they only execute via executeParentRule()
            if (rule.isAbstract()) continue;

            // Check if already transformed (idempotent)
            EObject cached = context.getElementResolutionCache().getByRule(source, rule.getName());
            if (cached != null) continue;

            // Check guard
            if (!rule.evaluateGuard(source, context)) continue;

            // Check if rule applies to this element
            if (!rule.appliesTo(source)) continue;

            // Execute rule and cache result
            try {
                EObject target = rule.execute(source, context);
                if (target != null) {
                    context.getElementResolutionCache().addMapping(
                            source, rule.getName(), target, rule.isPrimary());
                }
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
