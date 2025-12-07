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

    private TransformationRegistry transformationRegistry;
    private EPackage targetPackage;

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

        // Add to target resource
        if (!targetResourceSet.getResources().isEmpty()) {
            Resource targetResource = targetResourceSet.getResources().get(0);
            targetResource.getContents().add(instance);
        }

        return (T) instance;
    }

    /**
     * Get the equivalent target for a source element.
     * If not already transformed, triggers lazy transformation if a matching rule exists.
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param <T> the target type
     * @return the equivalent target, or null if not found
     */
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
                        EObject target = rule.execute(source, this);
                        resolutionCache.addMapping(source, rule.getName(), target, rule.isPrimary());
                        return targetType.cast(target);
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

        // Set discriminated ID
        String baseId = getElementId(original);
        String discriminatedId = baseId + "/(discriminator/" + discriminator + ")";
        setElementId(clone, discriminatedId);

        // Add to target model
        if (!targetResourceSet.getResources().isEmpty()) {
            Resource targetResource = targetResourceSet.getResources().get(0);
            targetResource.getContents().add(clone);
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

    private String getElementId(EObject element) {
        // Use EMF intrinsic ID or UUID
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

        // Last resort: generate UUID
        return UUID.randomUUID().toString();
    }

    private void setElementId(EObject element, String id) {
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null && idFeature.isChangeable()) {
            element.eSet(idFeature, id);
        }

        // Also set XMI ID if resource supports it
        Resource resource = element.eResource();
        if (resource instanceof XMIResource) {
            ((XMIResource) resource).setID(element, id);
        }
    }
}
