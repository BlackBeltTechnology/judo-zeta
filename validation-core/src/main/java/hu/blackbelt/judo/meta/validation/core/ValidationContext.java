package hu.blackbelt.judo.zeta.validation.core;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
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

import hu.blackbelt.judo.zeta.validation.ModelProvider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;

/**
 * Runtime context for validation execution.
 *
 * <p>Provides access to:</p>
 * <ul>
 *   <li>The current element being validated</li>
 *   <li>ModelProvider instance for model traversal</li>
 *   <li>Result cache for satisfies() checks</li>
 *   <li>Extension method registry and invocation</li>
 *   <li>Custom attributes for pre/post hooks</li>
 * </ul>
 */
public class ValidationContext {

    /**
     * Three-state cache value for satisfies() checks.
     */
    private enum SatisfiesState {
        EVALUATING, // Currently being evaluated (prevents infinite recursion)
        SATISFIED, // Constraint passed
        NOT_SATISFIED, // Constraint failed or guard failed
    }

    private final ModelProvider modelProvider;
    private final ResourceSet resourceSet;
    private final ExtensionMethodRegistry extensionRegistry;
    private final Map<CacheKey, SatisfiesState> satisfiesCache;
    private final Map<String, Object> attributes;

    /**
     * Thread-local current element for parallel validation support.
     * Each thread has its own current element, avoiding race conditions.
     */
    private final ThreadLocal<EObject> currentElement = new ThreadLocal<>();
    private ValidationRegistry validationRegistry;

    public ValidationContext(
        ModelProvider modelProvider,
        ResourceSet resourceSet,
        ExtensionMethodRegistry extensionRegistry
    ) {
        this.modelProvider = modelProvider;
        this.resourceSet = resourceSet;
        this.extensionRegistry = extensionRegistry;
        this.satisfiesCache = new ConcurrentHashMap<>();
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Set the validation registry for satisfies() constraint evaluation.
     */
    public void setValidationRegistry(ValidationRegistry registry) {
        this.validationRegistry = registry;
    }

    /**
     * Get the ModelProvider instance.
     */
    public ModelProvider getModelProvider() {
        return modelProvider;
    }

    /**
     * Get the resource set containing the model.
     */
    public ResourceSet getResourceSet() {
        return resourceSet;
    }

    /**
     * Get the current element being validated.
     * Uses ThreadLocal for thread-safety in parallel validation.
     */
    public EObject getCurrentElement() {
        return currentElement.get();
    }

    /**
     * Set the current element being validated (internal use by executor).
     * Uses ThreadLocal for thread-safety in parallel validation.
     */
    public void setCurrentElement(EObject element) {
        this.currentElement.set(element);
    }

    /**
     * Clear the current element for this thread.
     * Should be called after validation completes to prevent memory leaks.
     */
    public void clearCurrentElement() {
        this.currentElement.remove();
    }

    /**
     * Check if a constraint is satisfied for the current element.
     *
     * @param constraintName the constraint name
     * @return true if the constraint is satisfied
     */
    public boolean satisfies(String constraintName) {
        return satisfies(currentElement.get(), constraintName);
    }

    /**
     * Check if a constraint is satisfied for ANY element (not just self).
     * This enables cross-object dependency checking in guards.
     *
     * @param element the element to check
     * @param constraintName the constraint name
     * @return true if the constraint is satisfied
     */
    public boolean satisfies(EObject element, String constraintName) {
        CacheKey key = new CacheKey(element, constraintName);

        // Check cache first
        SatisfiesState cached = satisfiesCache.get(key);
        if (cached != null) {
            switch (cached) {
                case SATISFIED:
                    return true;
                case NOT_SATISFIED:
                    return false;
                case EVALUATING:
                    // Circular dependency - assume satisfied to break the loop
                    return true;
            }
        }

        // Mark as evaluating to detect circular dependencies
        satisfiesCache.put(key, SatisfiesState.EVALUATING);

        // Evaluate the constraint
        boolean result = evaluateConstraint(element, constraintName);

        // Cache the result
        satisfiesCache.put(
            key,
            result ? SatisfiesState.SATISFIED : SatisfiesState.NOT_SATISFIED
        );

        return result;
    }

    /**
     * Check if all elements in a collection satisfy a constraint.
     * Convenience method for forAll patterns.
     *
     * @param elements the elements to check
     * @param constraintName the constraint name
     * @return true if all elements satisfy the constraint
     */
    public boolean allSatisfy(
        Collection<? extends EObject> elements,
        String constraintName
    ) {
        return elements.stream().allMatch(e -> satisfies(e, constraintName));
    }

    /**
     * Evaluate a constraint for an element (internal use by satisfies cache).
     * Returns true if the constraint passes, false if it fails.
     */
    private boolean evaluateConstraint(EObject element, String constraintName) {
        if (validationRegistry == null) {
            // No registry available, assume satisfied
            return true;
        }

        // Find validators that match the constraint name and apply to this element type
        Collection<ValidatorDescriptor> validators =
            validationRegistry.getValidatorsFor(element.getClass());
        for (ValidatorDescriptor validator : validators) {
            if (
                validator.getName().equals(constraintName) &&
                validator.appliesTo(element)
            ) {
                // Check the constraint's own @Satisfies dependencies first
                // This prevents executing a constraint that depends on failed prerequisites
                List<String> dependencies =
                    validator.getSatisfiesDependencies();
                for (String dependency : dependencies) {
                    if (!satisfies(element, dependency)) {
                        // Dependency failed, this constraint cannot be satisfied
                        return false;
                    }
                }

                // Check the guard
                Guard guard = validator.getGuard();
                if (guard != null && !guard.evaluate(element, this)) {
                    // Guard failed, constraint doesn't apply
                    // In EVL semantics for @Satisfies, if the prerequisite constraint's guard fails,
                    // the dependent constraint should NOT run, so return FALSE
                    return false;
                }
                // Execute the rule
                ValidationResult result = validator
                    .getRule()
                    .validate(element, this);
                return !result.isFailed();
            }
        }

        // No matching constraint found - assume satisfied
        return true;
    }

    /**
     * Get all instances of a given EClass type from the resource set.
     *
     * @param eClass the EClass type
     * @return collection of instances
     */
    public <T extends EObject> Collection<T> getAllInstances(Class<T> eClass) {
        return modelProvider.getAllContents(resourceSet, eClass);
    }

    /**
     * Call cached extension method on any EObject.
     *
     * @param target the target object
     * @param methodName the method name
     * @param args method arguments
     * @return the method result
     */
    public <T> T call(EObject target, String methodName, Object... args) {
        return extensionRegistry.invoke(target, methodName, args);
    }

    /**
     * Convenience: call extension method on current element.
     *
     * @param methodName the method name
     * @param args method arguments
     * @return the method result
     */
    public <T> T call(String methodName, Object... args) {
        return call(currentElement.get(), methodName, args);
    }

    /**
     * Set a custom attribute (for use in pre/post hooks).
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Get a custom attribute.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not set
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Clear the satisfies cache (called between validation runs).
     */
    public void clearSatisfiesCache() {
        satisfiesCache.clear();
    }

    /**
     * Clear extension method cache (delegates to registry).
     */
    public void clearExtensionCache() {
        extensionRegistry.clearCache();
    }

    /**
     * Cache key for satisfies() results.
     */
    private static final class CacheKey {

        private final EObject element;
        private final String constraintName;

        CacheKey(EObject element, String constraintName) {
            this.element = element;
            this.constraintName = constraintName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return (
                element == cacheKey.element &&
                Objects.equals(constraintName, cacheKey.constraintName)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                System.identityHashCode(element),
                constraintName
            );
        }
    }
}
