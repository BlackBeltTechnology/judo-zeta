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

import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime context for transformation execution.
 *
 * <p>Provides access to:</p>
 * <ul>
 *   <li>Source and target models</li>
 *   <li>Element resolution cache (transformation trace)</li>
 *   <li>equivalent() and equivalents() methods</li>
 *   <li>Extension method registry and invocation</li>
 *   <li>Custom attributes for pre/post hooks</li>
 * </ul>
 */
public class TransformationContext {

    private final ModelProvider modelProvider;
    private final ResourceSet sourceResourceSet;
    private final ResourceSet targetResourceSet;
    private final ExtensionMethodRegistry extensionRegistry;
    private final ElementResolutionCache resolutionCache;
    private final Map<String, Object> attributes;

    /**
     * Thread-local current source element for parallel transformation support.
     */
    private final ThreadLocal<EObject> currentSource = new ThreadLocal<>();

    /**
     * Queue for collecting elements created during parallel transformation.
     * Elements are staged here instead of being added directly to the target Resource.
     */
    private final ConcurrentLinkedQueue<StagedElement> stagedElements = new ConcurrentLinkedQueue<>();

    /**
     * Flag indicating whether element staging is enabled.
     * When true, createTarget() stages elements instead of adding to Resource.
     */
    private final AtomicBoolean stagingEnabled = new AtomicBoolean(false);

    /**
     * Sequence counter for maintaining deterministic element ordering.
     */
    private final AtomicLong creationSequence = new AtomicLong(0);

    /**
     * Map tracking creation order of each staged element.
     */
    private final ConcurrentHashMap<EObject, Long> elementOrder = new ConcurrentHashMap<>();

    /**
     * Map storing intended XMI IDs for staged elements (applied during commit).
     */
    private final ConcurrentHashMap<EObject, String> pendingXmiIds = new ConcurrentHashMap<>();

    /**
     * Map for tracking lazy rule executions to prevent concurrent duplicates.
     */
    private final ConcurrentHashMap<LazyRuleKey, EObject> executingLazyRules = new ConcurrentHashMap<>();

    private TransformationRegistry transformationRegistry;
    private EPackage targetPackage;

    /**
     * Wrapper for staged elements with ordering metadata.
     */
    private static class StagedElement {
        final EObject element;
        final boolean isRootElement;
        final long sequence;

        StagedElement(EObject element, boolean isRootElement, long sequence) {
            this.element = element;
            this.isRootElement = isRootElement;
            this.sequence = sequence;
        }
    }

    /**
     * Key for tracking lazy rule executions.
     */
    private static class LazyRuleKey {
        final EObject source;
        final Class<?> targetType;

        LazyRuleKey(EObject source, Class<?> targetType) {
            this.source = source;
            this.targetType = targetType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LazyRuleKey that = (LazyRuleKey) o;
            return Objects.equals(source, that.source) && Objects.equals(targetType, that.targetType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, targetType);
        }
    }

    public TransformationContext(
            ModelProvider modelProvider,
            ResourceSet sourceResourceSet,
            ResourceSet targetResourceSet,
            ExtensionMethodRegistry extensionRegistry
    ) {
        this.modelProvider = modelProvider;
        this.sourceResourceSet = sourceResourceSet;
        this.targetResourceSet = targetResourceSet;
        this.extensionRegistry = extensionRegistry;
        this.resolutionCache = new ElementResolutionCache();
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Set the transformation registry.
     */
    public void setTransformationRegistry(TransformationRegistry registry) {
        this.transformationRegistry = registry;
    }

    /**
     * Set the target EPackage for creating target elements.
     */
    public void setTargetPackage(EPackage targetPackage) {
        this.targetPackage = targetPackage;
    }

    /**
     * Get the ModelProvider instance.
     */
    public ModelProvider getModelProvider() {
        return modelProvider;
    }

    /**
     * Get the source resource set.
     */
    public ResourceSet getSourceResourceSet() {
        return sourceResourceSet;
    }

    /**
     * Get the target resource set.
     */
    public ResourceSet getTargetResourceSet() {
        return targetResourceSet;
    }

    /**
     * Get the element resolution cache.
     */
    public ElementResolutionCache getElementResolutionCache() {
        return resolutionCache;
    }

    /**
     * Get the current source element being transformed.
     */
    public EObject getCurrentSource() {
        return currentSource.get();
    }

    /**
     * Set the current source element (internal use by executor).
     */
    public void setCurrentSource(EObject source) {
        this.currentSource.set(source);
    }

    /**
     * Clear the current source element for this thread.
     */
    public void clearCurrentSource() {
        this.currentSource.remove();
    }

    /**
     * Create a new target element of the specified type.
     *
     * <p>If staging is enabled (parallel transformation), the element is queued
     * for later addition to the Resource. Otherwise, it is added immediately.</p>
     *
     * @param targetType the target EObject interface
     * @param <T> the target type
     * @return the new target element
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T createTarget(Class<T> targetType) {
        if (targetPackage == null) {
            throw new IllegalStateException("Target package not set. Call setTargetPackage() first.");
        }

        String typeName = targetType.getSimpleName();
        EClass eClass = (EClass) targetPackage.getEClassifier(typeName);

        if (eClass == null) {
            throw new IllegalArgumentException("EClass not found in target package: " + typeName);
        }

        EObject instance = targetPackage.getEFactoryInstance().create(eClass);

        if (stagingEnabled.get()) {
            // Parallel mode: stage for later commit with ordering
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(instance, sequence);
            stagedElements.offer(new StagedElement(instance, true, sequence));
        } else {
            // Sequential mode: add directly to Resource
            if (!targetResourceSet.getResources().isEmpty()) {
                Resource targetResource = targetResourceSet.getResources().get(0);
                targetResource.getContents().add(instance);
            }
        }

        return (T) instance;
    }

    /**
     * Get the equivalent target for a source element.
     * If not already transformed, triggers lazy transformation if a matching rule exists.
     *
     * <p>Thread-safe: Uses computeIfAbsent to prevent duplicate lazy rule execution
     * when multiple threads call equivalent() for the same source concurrently.</p>
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param <T> the target type
     * @return the equivalent target, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T equivalent(EObject source, Class<T> targetType) {
        // Check cache first
        T cached = resolutionCache.getEquivalent(source, targetType);
        if (cached != null) {
            return cached;
        }

        // Try to find and execute a matching lazy rule
        if (transformationRegistry != null) {
            Collection<TransformRuleDescriptor> rules = transformationRegistry.getRulesForSource(source.getClass());
            for (TransformRuleDescriptor rule : rules) {
                if (rule.appliesTo(source) && targetType.isAssignableFrom(rule.getTargetType())) {
                    if (rule.evaluateGuard(source, this)) {
                        // Use computeIfAbsent to prevent concurrent duplicate execution
                        LazyRuleKey key = new LazyRuleKey(source, targetType);
                        EObject target = executingLazyRules.computeIfAbsent(key, k -> {
                            // Double-check cache inside computeIfAbsent
                            T existing = resolutionCache.getEquivalent(source, targetType);
                            if (existing != null) {
                                return existing;
                            }
                            EObject result = rule.execute(source, this);
                            resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                            return result;
                        });
                        return (T) target;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get all equivalent targets for a source element.
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param <T> the target type
     * @return list of equivalent targets
     */
    public <T extends EObject> List<T> equivalents(EObject source, Class<T> targetType) {
        return resolutionCache.getEquivalents(source, targetType);
    }

    /**
     * Get a discriminated equivalent (for multiple transformations of the same source).
     *
     * <p>If staging is enabled, the cloned element is staged for later commit
     * and its XMI ID is stored for deferred assignment.</p>
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param ruleName the rule name
     * @param discriminator the discriminator value
     * @param <T> the target type
     * @return the discriminated target
     */
    public <T extends EObject> T equivalentDiscriminated(
            EObject source,
            Class<T> targetType,
            String ruleName,
            String discriminator
    ) {
        // Check discriminated cache first
        T cached = resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, discriminator);
        if (cached != null) {
            return cached;
        }

        // Get or create base transformation
        T original = equivalent(source, targetType);
        if (original == null) {
            return null;
        }

        // Clone for discriminated version
        @SuppressWarnings("unchecked")
        T clone = (T) EcoreUtil.copy(original);

        // Set discriminated ID (works for both staged and non-staged)
        String baseId = getElementId(original);
        String discriminatedId = baseId + "/(discriminator/" + discriminator + ")";
        setElementId(clone, discriminatedId);

        if (stagingEnabled.get()) {
            // Parallel mode: stage for later commit with ordering
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(clone, sequence);
            stagedElements.offer(new StagedElement(clone, true, sequence));
        } else {
            // Sequential mode: add directly to Resource
            if (!targetResourceSet.getResources().isEmpty()) {
                Resource targetResource = targetResourceSet.getResources().get(0);
                targetResource.getContents().add(clone);
            }
        }

        // Cache discriminated result
        resolutionCache.addDiscriminatedMapping(source, clone, ruleName, discriminator);

        return clone;
    }

    /**
     * Execute a parent rule (for @Extends inheritance).
     *
     * @param parentRuleName the parent rule name
     * @param source the source element
     * @param <T> the target type
     * @return the target from parent rule execution
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T executeParentRule(String parentRuleName, EObject source) {
        if (transformationRegistry == null) {
            throw new IllegalStateException("Transformation registry not set");
        }

        TransformRuleDescriptor parentRule = transformationRegistry.getRuleByName(parentRuleName);
        if (parentRule == null) {
            throw new IllegalArgumentException("Parent rule not found: " + parentRuleName);
        }

        // Execute parent rule directly (allows abstract rules to execute when explicitly called)
        return (T) parentRule.execute(source, this);
    }

    /**
     * Get all instances of a type from the source model.
     *
     * @param sourceType the source type
     * @param <T> the source type
     * @return collection of instances
     */
    public <T extends EObject> Collection<T> getAllSource(Class<T> sourceType) {
        return modelProvider.getAllContents(sourceResourceSet, sourceType);
    }

    /**
     * Call cached extension method on any EObject.
     *
     * @param target the target object
     * @param methodName the method name
     * @param args method arguments
     * @param <T> the return type
     * @return the method result
     */
    public <T> T call(EObject target, String methodName, Object... args) {
        return extensionRegistry.invoke(target, methodName, args);
    }

    /**
     * Convenience: call extension method on current source element.
     */
    public <T> T call(String methodName, Object... args) {
        return call(currentSource.get(), methodName, args);
    }

    /**
     * Set a custom attribute.
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Get a custom attribute.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Clear extension method cache.
     */
    public void clearExtensionCache() {
        extensionRegistry.clearCache();
    }

    // ==================== Staging Infrastructure ====================

    /**
     * Enable staging mode for parallel transformation.
     * Called by TransformationExecutor before parallel phase.
     */
    void enableStaging() {
        stagingEnabled.set(true);
    }

    /**
     * Disable staging mode and return to direct Resource modification.
     */
    void disableStaging() {
        stagingEnabled.set(false);
    }

    /**
     * Check if staging is currently enabled.
     *
     * @return true if staging is enabled
     */
    public boolean isStagingEnabled() {
        return stagingEnabled.get();
    }

    /**
     * Get the number of staged elements.
     *
     * @return count of staged elements
     */
    public int getStagedElementCount() {
        return stagedElements.size();
    }

    /**
     * Commit all staged elements to the target Resource.
     * Elements are sorted by creation sequence for deterministic ordering.
     * Must be called from a single thread after parallel phase completes.
     */
    void commitStagedElements() {
        if (targetResourceSet.getResources().isEmpty()) {
            return;
        }

        Resource targetResource = targetResourceSet.getResources().get(0);
        XMIResource xmiResource = targetResource instanceof XMIResource
            ? (XMIResource) targetResource : null;

        // Collect all staged elements
        List<StagedElement> elementsToCommit = new ArrayList<>();
        StagedElement staged;
        while ((staged = stagedElements.poll()) != null) {
            elementsToCommit.add(staged);
        }

        // Sort by creation sequence for deterministic ordering
        elementsToCommit.sort(Comparator.comparingLong(e -> e.sequence));

        // Add to resource in order
        for (StagedElement element : elementsToCommit) {
            EObject obj = element.element;

            // Only add root elements that are not yet contained
            if (element.isRootElement && obj.eContainer() == null) {
                targetResource.getContents().add(obj);
            }

            // Apply pending XMI ID now that element is in resource
            String pendingId = pendingXmiIds.remove(obj);
            if (pendingId != null && xmiResource != null) {
                xmiResource.setID(obj, pendingId);
            }

            // Also apply pending IDs to contained elements recursively
            applyPendingIdsRecursively(obj, xmiResource);
        }
    }

    /**
     * Recursively apply pending XMI IDs to contained elements.
     */
    private void applyPendingIdsRecursively(EObject parent, XMIResource xmiResource) {
        if (xmiResource == null) {
            return;
        }
        for (EObject child : parent.eContents()) {
            String pendingId = pendingXmiIds.remove(child);
            if (pendingId != null) {
                xmiResource.setID(child, pendingId);
            }
            applyPendingIdsRecursively(child, xmiResource);
        }
    }

    /**
     * Clear staging queue (called on cleanup or reset).
     */
    void clearStagedElements() {
        stagedElements.clear();
    }

    /**
     * Clear element ordering data (called on reset).
     */
    void clearElementOrder() {
        elementOrder.clear();
        creationSequence.set(0);
    }

    /**
     * Clear pending XMI IDs (called on reset).
     */
    void clearPendingXmiIds() {
        pendingXmiIds.clear();
    }

    /**
     * Clear lazy rule execution tracking (called on reset).
     */
    void clearExecutingLazyRules() {
        executingLazyRules.clear();
    }

    /**
     * Get the creation sequence number for an element.
     *
     * @param element the element
     * @return the sequence number, or -1 if not tracked
     */
    public long getElementSequence(EObject element) {
        return elementOrder.getOrDefault(element, -1L);
    }

    /**
     * Get the pending XMI ID for an element.
     *
     * @param element the element
     * @return the pending ID, or null if none
     */
    public String getPendingXmiId(EObject element) {
        return pendingXmiIds.get(element);
    }

    // ==================== ID Handling ====================

    private String getElementId(EObject element) {
        // First check pending IDs for staged elements
        String pendingId = pendingXmiIds.get(element);
        if (pendingId != null) {
            return pendingId;
        }

        // Check resource for committed elements
        Resource resource = element.eResource();
        if (resource != null) {
            String id = resource.getURIFragment(element);
            if (id != null && !id.startsWith("/")) {
                return id;
            }
        }

        // Fallback: use model element identifier attribute
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null) {
            Object idValue = element.eGet(idFeature);
            if (idValue != null) {
                return idValue.toString();
            }
        }

        // Generate UUID and store for staged elements (ensures consistency)
        String generatedId = UUID.randomUUID().toString();
        if (stagingEnabled.get()) {
            pendingXmiIds.put(element, generatedId);
        }
        return generatedId;
    }

    private void setElementId(EObject element, String id) {
        // Set "id" structural feature if available
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null && idFeature.isChangeable()) {
            element.eSet(idFeature, id);
        }

        if (stagingEnabled.get()) {
            // Store for later XMI ID assignment during commit
            pendingXmiIds.put(element, id);
        } else {
            // Direct assignment if already in resource
            Resource resource = element.eResource();
            if (resource instanceof XMIResource) {
                ((XMIResource) resource).setID(element, id);
            }
        }
    }
}
