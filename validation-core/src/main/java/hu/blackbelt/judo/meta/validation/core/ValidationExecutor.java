package hu.blackbelt.judo.zeta.validation.core;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
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

/**
 * Parallel validation executor with phased dependency resolution.
 *
 * <p>Executes validation rules in phases:</p>
 * <ol>
 *   <li>Phase 1: Execute all rules without {@code @Satisfies} dependencies</li>
 *   <li>Phase 2: Execute rules whose dependencies are now satisfied</li>
 *   <li>Phase N: Continue until all rules processed or circular dependency detected</li>
 * </ol>
 */
public class ValidationExecutor {

    private static final Logger log = LoggerFactory.getLogger(
        ValidationExecutor.class
    );

    /**
     * Minimum number of elements required for parallel execution to be beneficial.
     * Below this threshold, sequential execution is faster due to parallelization overhead.
     */
    private static final int PARALLEL_THRESHOLD = 5000;

    private final ValidationRegistry registry;
    private final ValidationContext context;
    private final boolean parallel;
    private volatile ExecutorService executor;

    public ValidationExecutor(
        ValidationRegistry registry,
        ValidationContext context,
        boolean parallel
    ) {
        this.registry = registry;
        this.context = context;
        this.parallel = parallel;
        // Executor is created lazily only when parallel execution is actually used
        this.executor = null;
    }

    /**
     * Get or create the executor service for parallel validation.
     * Created lazily to avoid overhead when parallel is not actually used.
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
     * Validate all elements by collecting them from registered resources based on validator definitions.
     *
     * <p>This method automatically collects elements from the appropriate resource aliases
     * as defined by the @Constraint/@Critique annotations on registered validators. For validators
     * using the resourceAlias attribute, elements are collected from the specified alias.
     * For backward compatibility, validators without explicit resourceAlias use the default "source" alias.</p>
     *
     * @return list of validation failures (empty if all passed)
     */
    public List<ValidationResult> validate() {
        // Collect all elements based on validator definitions
        Set<EObject> allElements = new LinkedHashSet<>();
        
        for (ValidatorDescriptor validator : registry.getAllValidators()) {
            String resourceAlias = validator.getResourceAlias();
            Class<? extends EObject> contextType = validator.getContextType();
            
            Collection<? extends EObject> elements = context.all(resourceAlias, contextType);
            allElements.addAll(elements);
        }
        
        return validate(allElements);
    }

    /**
     * Validate all elements in the collection.
     *
     * @param elements elements to validate
     * @return list of validation failures (empty if all passed)
     */
    public List<ValidationResult> validate(
        Collection<? extends EObject> elements
    ) {
        context.clearSatisfiesCache();

        // Invoke pre-validation hooks
        registry.invokePreValidationHooks(context);

        try {
            // Use parallel only if requested AND element count exceeds threshold
            boolean useParallel =
                parallel && elements.size() >= PARALLEL_THRESHOLD;

            List<ValidationResult> allResults = useParallel
                ? validateParallel(elements)
                : validateSequential(elements);

            // Filter out passing results
            return allResults
                .stream()
                .filter(ValidationResult::isFailed)
                .collect(Collectors.toList());
        } finally {
            // Invoke post-validation hooks
            registry.invokePostValidationHooks(context);

            // Clear caches
            context.clearSatisfiesCache();
            context.clearExtensionCache();
        }
    }

    private List<ValidationResult> validateSequential(
        Collection<? extends EObject> elements
    ) {
        List<ValidationResult> results = new ArrayList<>();

        for (EObject element : elements) {
            try {
                context.setCurrentElement(element);
                Collection<ValidatorDescriptor> validators =
                    registry.getValidatorsFor(element.getClass());

                for (ValidatorDescriptor validator : validators) {
                    if (validator.appliesTo(element) && isFromExpectedAlias(element, validator)) {
                        ValidationResult result = validator.validate(
                            element,
                            context
                        );
                        if (result.isFailed()) {
                            results.add(result);
                        }
                    }
                }
            } finally {
                context.clearCurrentElement();
            }
        }

        return results;
    }

    private List<ValidationResult> validateParallel(
        Collection<? extends EObject> elements
    ) {
        List<EObject> elementList = new ArrayList<>(elements);
        int numProcessors = Runtime.getRuntime().availableProcessors();

        // Calculate chunk size - divide work evenly across processors
        int chunkSize = Math.max(
            100,
            (elementList.size() + numProcessors - 1) / numProcessors
        );

        // Partition elements into chunks
        List<List<EObject>> chunks = partitionList(elementList, chunkSize);

        // Pre-compute validator lookups to avoid repeated registry calls
        Map<Class<?>, Collection<ValidatorDescriptor>> validatorCache =
            elementList
                .stream()
                .map(EObject::getClass)
                .distinct()
                .collect(
                    Collectors.toMap(
                        c -> c,
                        registry::getValidatorsFor,
                        (v1, v2) -> v1
                    )
                );

        // Process chunks in parallel using CompletableFuture
        ExecutorService exec = getOrCreateExecutor();
        List<CompletableFuture<List<ValidationResult>>> futures = chunks
            .stream()
            .map(chunk ->
                CompletableFuture.supplyAsync(
                    () -> validateChunk(chunk, validatorCache),
                    exec
                )
            )
            .collect(Collectors.toList());

        // Wait for all chunks to complete and merge results
        return futures
            .stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * Validate a chunk of elements sequentially within a single thread.
     */
    private List<ValidationResult> validateChunk(
        List<EObject> chunk,
        Map<Class<?>, Collection<ValidatorDescriptor>> validatorCache
    ) {
        List<ValidationResult> results = new ArrayList<>();

        for (EObject element : chunk) {
            try {
                context.setCurrentElement(element);
                Collection<ValidatorDescriptor> validators = validatorCache.get(
                    element.getClass()
                );

                if (validators != null) {
                    for (ValidatorDescriptor validator : validators) {
                        if (validator.appliesTo(element) && isFromExpectedAlias(element, validator)) {
                            ValidationResult result = validator.validate(
                                element,
                                context
                            );
                            if (result.isFailed()) {
                                results.add(result);
                            }
                        }
                    }
                }
            } finally {
                context.clearCurrentElement();
            }
        }

        return results;
    }

    /**
     * Check if the element comes from a resource that matches the validator's expected alias.
     *
     * <p>For validators with explicit resourceAlias, verifies the element is from the specified alias.
     * For backward compatibility, validators using the default "source" alias accept elements from
     * the source resource.</p>
     *
     * @param element the element to check
     * @param validator the validator descriptor
     * @return true if the element is from the expected resource alias
     */
    private boolean isFromExpectedAlias(EObject element, ValidatorDescriptor validator) {
        String resourceAlias = validator.getResourceAlias();
        
        // Get the element's resource set
        Resource elementResource = element.eResource();
        if (elementResource == null) {
            // Elements without a resource can only be validated if alias is "source" (default)
            return "source".equals(resourceAlias);
        }
        
        ResourceSet elementResourceSet = elementResource.getResourceSet();
        if (elementResourceSet == null) {
            return "source".equals(resourceAlias);
        }
        
        // Get the expected resource set from the alias
        ResourceSet aliasResourceSet = context.getResource(resourceAlias);
        if (aliasResourceSet == null) {
            // Unknown alias - fall back to accepting if it's the default
            return "source".equals(resourceAlias);
        }
        
        return aliasResourceSet.equals(elementResourceSet);
    }

    /**
     * Partition a list into chunks of specified size.
     */
    private <T> List<List<T>> partitionList(List<T> list, int chunkSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            partitions.add(
                list.subList(i, Math.min(i + chunkSize, list.size()))
            );
        }
        return partitions;
    }

    /**
     * Shutdown the executor (call when done with all validations).
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
