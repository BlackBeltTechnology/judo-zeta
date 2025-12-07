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
     * Minimum number of elements required for parallel execution to be beneficial.
     */
    private static final int PARALLEL_THRESHOLD = 5000;
    private static final int CHUNK_SIZE = 100;

    private final TransformationRegistry registry;
    private final TransformationContext context;
    private final boolean parallel;
    private volatile ExecutorService executor;

    public TransformationExecutor(
            TransformationRegistry registry,
            TransformationContext context,
            boolean parallel
    ) {
        this.registry = registry;
        this.context = context;
        this.parallel = parallel;
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
     * @param sourceElements elements to transform
     * @return the transformation result
     */
    public TransformationResult transform(Collection<? extends EObject> sourceElements) {
        long startTime = System.currentTimeMillis();

        // Invoke pre-transformation hooks
        registry.invokePreTransformationHooks(context);

        try {
            // Use parallel only if requested AND element count exceeds threshold
            boolean useParallel = parallel && sourceElements.size() >= PARALLEL_THRESHOLD;

            if (useParallel) {
                transformParallel(sourceElements);
            } else {
                transformSequential(sourceElements);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Transformation completed in {}ms, processed {} elements",
                    duration, sourceElements.size());

            return new TransformationResult(context, duration);

        } finally {
            // Invoke post-transformation hooks
            registry.invokePostTransformationHooks(context);

            // Clear caches
            context.clearExtensionCache();
        }
    }

    private void transformSequential(Collection<? extends EObject> sourceElements) {
        for (EObject source : sourceElements) {
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

        // Calculate chunk size
        int chunkSize = Math.max(CHUNK_SIZE, (elementList.size() + numProcessors - 1) / numProcessors);

        // Partition elements
        List<List<EObject>> chunks = partitionList(elementList, chunkSize);

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
