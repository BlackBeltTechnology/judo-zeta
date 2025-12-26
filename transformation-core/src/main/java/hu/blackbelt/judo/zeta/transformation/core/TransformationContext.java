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
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

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

    /**
     * Custom attributes map.
     * Uses Optional to allow null values (ConcurrentHashMap doesn't allow null).
     */
    private final Map<String, Optional<Object>> attributes;

    /**
     * Registry mapping aliases to ResourceSets.
     * "source" and "target" are registered by default.
     */
    private final Map<String, ResourceSet> resourceRegistry = new ConcurrentHashMap<>();

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

    /**
     * ThreadLocal set to track lazy rule keys currently being executed in this thread.
     * Used to detect and handle recursive equivalent() calls.
     */
    private final ThreadLocal<Set<LazyRuleKey>> inProgressRules = ThreadLocal.withInitial(HashSet::new);

    private TransformationRegistry transformationRegistry;

    /**
     * List of registered target EPackages for element creation (for dynamic models).
     * Thread-safe for parallel transformation support.
     */
    private final List<EPackage> targetPackages = new CopyOnWriteArrayList<>();

    /**
     * Cache for auto-discovered EPackages from generated Java classes.
     * Maps Java class to its discovered EPackage.
     */
    private final ConcurrentHashMap<Class<?>, EPackage> discoveredPackageCache = new ConcurrentHashMap<>();

    /**
     * Negative cache for types where EPackage discovery failed.
     * Prevents repeated discovery attempts for non-generated types.
     */
    private final Set<Class<?>> discoveryFailedCache = ConcurrentHashMap.newKeySet();

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

        // Register default aliases
        resourceRegistry.put("source", sourceResourceSet);
        resourceRegistry.put("target", targetResourceSet);
    }

    /**
     * Set the transformation registry.
     */
    public void setTransformationRegistry(TransformationRegistry registry) {
        this.transformationRegistry = registry;
    }

    /**
     * Set the target EPackage for dynamic EMF models.
     * Clears any previously registered packages.
     *
     * <p><b>Not needed for generated metamodels</b> - the framework auto-discovers
     * EPackages from generated Java classes.</p>
     *
     * @param targetPackage the target EPackage (for dynamic EMF)
     */
    public void setTargetPackage(EPackage targetPackage) {
        this.targetPackages.clear();
        if (targetPackage != null) {
            this.targetPackages.add(targetPackage);
        }
    }

    /**
     * Register a target EPackage for dynamic EMF models.
     *
     * <p><b>Not needed for generated metamodels</b> - the framework auto-discovers
     * EPackages from generated Java classes.</p>
     *
     * @param targetPackage the target EPackage (for dynamic EMF)
     * @throws IllegalArgumentException if targetPackage is null
     */
    public void registerTargetPackage(EPackage targetPackage) {
        if (targetPackage == null) {
            throw new IllegalArgumentException("Target package cannot be null");
        }
        this.targetPackages.add(targetPackage);
    }

    /**
     * Register a target EPackage for dynamic EMF models with sub-package control.
     *
     * <p><b>Not needed for generated metamodels</b> - the framework auto-discovers
     * EPackages from generated Java classes.</p>
     *
     * @param targetPackage the target EPackage (for dynamic EMF)
     * @param includeSubpackages if true, recursively include all sub-packages
     * @throws IllegalArgumentException if targetPackage is null
     */
    public void registerTargetPackage(EPackage targetPackage, boolean includeSubpackages) {
        if (targetPackage == null) {
            throw new IllegalArgumentException("Target package cannot be null");
        }
        if (includeSubpackages) {
            addPackageWithSubpackages(targetPackage);
        } else {
            this.targetPackages.add(targetPackage);
        }
    }

    /**
     * Recursively add a package and all its sub-packages.
     */
    private void addPackageWithSubpackages(EPackage pkg) {
        this.targetPackages.add(pkg);
        for (EPackage subPkg : pkg.getESubpackages()) {
            addPackageWithSubpackages(subPkg);
        }
    }

    /**
     * Get all registered target packages.
     *
     * @return unmodifiable list of registered target packages
     */
    public List<EPackage> getTargetPackages() {
        return Collections.unmodifiableList(new ArrayList<>(targetPackages));
    }

    /**
     * Resolve the EPackage for a target type.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Auto-discover from generated Java class (for generated metamodels)</li>
     *   <li>Fall back to registered packages (for dynamic EMF models)</li>
     * </ol>
     *
     * @param targetType the target type to resolve
     * @return the EPackage containing the type
     * @throws IllegalArgumentException if type's EPackage cannot be discovered or found
     */
    private EPackage resolvePackageForType(Class<?> targetType) {
        // Try auto-discovery first (works for generated metamodels)
        EPackage discovered = discoverPackageForType(targetType);
        if (discovered != null) {
            return discovered;
        }

        // Fall back to registered packages (for dynamic EMF)
        return resolveFromRegisteredPackages(targetType);
    }

    /**
     * Auto-discover the EPackage from a generated EMF Java class.
     *
     * <p>For generated EMF interfaces like {@code com.example.schema.Table},
     * this finds the corresponding {@code SchemaPackage.eINSTANCE} in the same
     * Java package.</p>
     *
     * @param targetType the EMF interface class
     * @return the discovered EPackage, or null if not a generated class
     */
    private EPackage discoverPackageForType(Class<?> targetType) {
        // Check positive cache first
        EPackage cached = discoveredPackageCache.get(targetType);
        if (cached != null) {
            return cached;
        }

        // Check negative cache - don't retry failed discoveries
        if (discoveryFailedCache.contains(targetType)) {
            return null;
        }

        String typeName = targetType.getSimpleName();
        String javaPackageName = targetType.getPackage() != null ? targetType.getPackage().getName() : null;

        if (javaPackageName == null) {
            discoveryFailedCache.add(targetType);
            return null;
        }

        // Look for *Package class in the same Java package
        // EMF convention: SchemaPackage, DataPackage, etc.
        try {
            // Try common naming patterns for EMF package classes
            for (String suffix : Arrays.asList("Package", "")) {
                String basePackageName = derivePackageClassName(javaPackageName, suffix);
                if (basePackageName != null) {
                    EPackage pkg = tryLoadPackage(basePackageName, typeName);
                    if (pkg != null) {
                        discoveredPackageCache.put(targetType, pkg);
                        return pkg;
                    }
                }
            }
        } catch (Exception e) {
            // Auto-discovery failed, will fall back to registered packages
        }

        // Cache the failure to avoid repeated discovery attempts
        discoveryFailedCache.add(targetType);
        return null;
    }

    /**
     * Derive potential EPackage class names from the Java package.
     */
    private String derivePackageClassName(String javaPackageName, String suffix) {
        // Extract last segment of package name and capitalize
        // e.g., "com.example.schema" -> "Schema" -> "SchemaPackage"
        String[] parts = javaPackageName.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        String lastPart = parts[parts.length - 1];
        String capitalized = Character.toUpperCase(lastPart.charAt(0)) + lastPart.substring(1);
        return javaPackageName + "." + capitalized + suffix;
    }

    /**
     * Try to load an EPackage class and verify it contains the target type.
     */
    private EPackage tryLoadPackage(String packageClassName, String typeName) {
        try {
            Class<?> packageClass = Class.forName(packageClassName);

            // Look for static eINSTANCE field
            Field eInstanceField = packageClass.getField("eINSTANCE");
            if (Modifier.isStatic(eInstanceField.getModifiers())
                    && EPackage.class.isAssignableFrom(eInstanceField.getType())) {

                EPackage pkg = (EPackage) eInstanceField.get(null);
                if (pkg != null) {
                    // Verify package contains the type
                    EClassifier classifier = pkg.getEClassifier(typeName);
                    if (classifier instanceof EClass) {
                        return pkg;
                    }
                }
            }
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            // Expected for non-existent or non-EMF classes
        }
        return null;
    }

    /**
     * Resolve type from registered packages (fallback for dynamic EMF).
     */
    private EPackage resolveFromRegisteredPackages(Class<?> targetType) {
        String typeName = targetType.getSimpleName();

        if (targetPackages.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot resolve EPackage for type '" + typeName + "'. " +
                    "For generated metamodels, ensure the package is initialized (e.g., MyPackage.eINSTANCE). " +
                    "For dynamic EMF, call registerTargetPackage() first.");
        }

        // Fast path: single package
        if (targetPackages.size() == 1) {
            EPackage pkg = targetPackages.get(0);
            if (pkg.getEClassifier(typeName) instanceof EClass) {
                return pkg;
            }
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' not found in registered package: " + pkg.getNsURI());
        }

        // Multi-package: find matching package(s)
        List<EPackage> matchingPackages = targetPackages.stream()
                .filter(pkg -> pkg.getEClassifier(typeName) instanceof EClass)
                .collect(Collectors.toList());

        if (matchingPackages.isEmpty()) {
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' not found in any registered package. " +
                    "Registered packages: " + targetPackages.stream()
                            .map(EPackage::getNsURI)
                            .collect(Collectors.toList()));
        }

        if (matchingPackages.size() > 1) {
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' found in multiple packages: " +
                    matchingPackages.stream().map(EPackage::getNsURI).collect(Collectors.toList()) +
                    ". Use createTarget(Class, EPackage) to specify which package to use.");
        }

        return matchingPackages.get(0);
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
     * <p>For generated metamodels, the EPackage is auto-discovered from the Java class.
     * No package registration is needed.</p>
     *
     * @param targetType the target EObject interface (e.g., {@code Table.class})
     * @param <T> the target type
     * @return the new target element
     */
    public <T extends EObject> T createTarget(Class<T> targetType) {
        EPackage pkg = resolvePackageForType(targetType);
        return createTargetInPackage(targetType, pkg);
    }

    /**
     * Create a new target element in a specific package (for dynamic EMF).
     *
     * <p><b>Not needed for generated metamodels</b> - use {@link #createTarget(Class)} instead,
     * which auto-discovers the EPackage.</p>
     *
     * @param targetType the target EObject interface
     * @param pkg the EPackage containing the type (for dynamic EMF)
     * @param <T> the target type
     * @return the new target element
     */
    public <T extends EObject> T createTarget(Class<T> targetType, EPackage pkg) {
        if (pkg == null) {
            throw new IllegalArgumentException("Package cannot be null");
        }
        return createTargetInPackage(targetType, pkg);
    }

    /**
     * Internal method to create a target element in a specific package.
     */
    @SuppressWarnings("unchecked")
    private <T extends EObject> T createTargetInPackage(Class<T> targetType, EPackage pkg) {
        String typeName = targetType.getSimpleName();
        EClass eClass = (EClass) pkg.getEClassifier(typeName);

        if (eClass == null) {
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' not found in package: " + pkg.getNsURI());
        }

        EObject instance = pkg.getEFactoryInstance().create(eClass);

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
     * <p>Thread-safe: Uses a combination of ThreadLocal for recursion detection
     * and ConcurrentHashMap for cross-thread duplicate prevention.</p>
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
                        LazyRuleKey key = new LazyRuleKey(source, targetType);
                        
                        // Check if already executed (from cache or concurrent execution)
                        EObject existing = executingLazyRules.get(key);
                        if (existing != null) {
                            return (T) existing;
                        }
                        
                        // Check if this key is currently being executed in this thread (recursion)
                        Set<LazyRuleKey> inProgress = inProgressRules.get();
                        if (inProgress.contains(key)) {
                            // Recursive call detected - return null to break the cycle
                            // The caller should handle null gracefully
                            return null;
                        }
                        
                        // Mark as in-progress for this thread
                        inProgress.add(key);
                        try {
                            // Double-check cache after marking in-progress
                            T cachedAgain = resolutionCache.getEquivalent(source, targetType);
                            if (cachedAgain != null) {
                                return cachedAgain;
                            }
                            
                            // Execute the rule
                            EObject result = rule.execute(source, this);
                            if (result != null) {
                                resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                                executingLazyRules.put(key, result);
                            }
                            return (T) result;
                        } finally {
                            inProgress.remove(key);
                        }
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
     * Get the equivalent target by executing a specific named rule.
     *
     * <p>This matches Epsilon ETL's equivalent("RuleName") semantics:
     * <ul>
     *   <li>Finds the rule by name</li>
     *   <li>Evaluates guard at invocation time</li>
     *   <li>Caches the result</li>
     * </ul></p>
     *
     * @param source the source element
     * @param ruleName the rule name to execute
     * @param <T> the target type
     * @return the equivalent target, or null if rule not found, doesn't apply, or guard fails
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T equivalent(EObject source, String ruleName) {
        if (ruleName == null || transformationRegistry == null) {
            return null;
        }

        // Check cache first by rule name
        EObject cached = resolutionCache.getByRule(source, ruleName);
        if (cached != null) {
            return (T) cached;
        }

        // Find the specific rule
        TransformRuleDescriptor rule = transformationRegistry.getRuleByName(ruleName);
        if (rule == null || !rule.appliesTo(source)) {
            return null;
        }

        // ETL semantics: guards ARE evaluated at invocation time for @lazy rules
        if (!rule.evaluateGuard(source, this)) {
            return null;
        }

        // Execute the rule
        EObject result = rule.execute(source, this);
        if (result != null) {
            resolutionCache.addMapping(source, ruleName, result, rule.isPrimary());
        }
        return (T) result;
    }

    /**
     * Get a discriminated equivalent (for multiple transformations of the same source).
     *
     * <p>When ruleName is provided, this method finds and executes that SPECIFIC @Lazy rule
     * WITHOUT guard check, matching Epsilon ETL's equivalent("RuleName") semantics.
     * If no ruleName is provided, falls back to generic equivalent() lookup.</p>
     *
     * <p>If staging is enabled, the cloned element is staged for later commit
     * and its XMI ID is stored for deferred assignment.</p>
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param ruleName the rule name (triggers this specific rule, not just any matching rule)
     * @param discriminator the discriminator value
     * @param <T> the target type
     * @return the discriminated target, or null if no matching rule found
     */
    @SuppressWarnings("unchecked")
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

        // If ruleName is specified, find and execute that specific rule
        T original = null;
        if (ruleName != null && transformationRegistry != null) {
            TransformRuleDescriptor rule = transformationRegistry.getRuleByName(ruleName);
            // ETL semantics: guards ARE evaluated at invocation time for @lazy rules
            if (rule != null && rule.appliesTo(source) && rule.evaluateGuard(source, this)) {
                // Check if already in cache by rule name
                EObject existing = resolutionCache.getByRule(source, ruleName);
                if (existing != null && targetType.isInstance(existing)) {
                    original = (T) existing;
                } else {
                    // Execute the specific rule
                    EObject result = rule.execute(source, this);
                    if (result != null) {
                        resolutionCache.addMapping(source, ruleName, result, rule.isPrimary());
                        if (targetType.isInstance(result)) {
                            original = (T) result;
                        }
                    }
                }
            }
        }

        // Fall back to generic equivalent() only if no ruleName or rule not found
        if (original == null) {
            original = equivalent(source, targetType);
        }

        if (original == null) {
            return null;
        }

        // If no discriminator, return the original without cloning (ETL semantics)
        if (discriminator == null) {
            return original;
        }

        // Clone for discriminated version
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
     * <p>This method is idempotent - if the parent rule has already been executed
     * for the given source element, the cached result is returned. This ensures
     * that when multiple child rules extend the same parent, the parent's
     * transformation logic only executes once per source element.</p>
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

        // Check cache first to ensure idempotency
        // This prevents duplicate execution when multiple child rules extend the same parent
        EObject cached = resolutionCache.getByRule(source, parentRuleName);
        if (cached != null) {
            return (T) cached;
        }

        // Execute parent rule and cache the result
        EObject result = parentRule.execute(source, this);
        if (result != null) {
            resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
        }
        return (T) result;
    }

    /**
     * Get all instances of a type from the source model.
     *
     * @param sourceType the source type
     * @param <T> the source type
     * @return collection of instances
     */
    public <T extends EObject> Collection<T> getAllSource(Class<T> sourceType) {
        return all("source", sourceType);
    }

    // ==================== Resource Alias Support ====================

    /**
     * Register a ResourceSet with an alias.
     * This allows accessing multiple models during transformation.
     *
     * @param alias the alias name (e.g., "mapping", "rules")
     * @param resourceSet the ResourceSet to register
     */
    public void registerResource(String alias, ResourceSet resourceSet) {
        if (alias == null) {
            throw new IllegalArgumentException("Resource alias cannot be null");
        }
        if (resourceSet == null) {
            throw new IllegalArgumentException("ResourceSet cannot be null for alias: " + alias);
        }
        resourceRegistry.put(alias, resourceSet);
    }

    /**
     * Get a ResourceSet by its alias.
     *
     * @param alias the alias name
     * @return the ResourceSet
     * @throws IllegalArgumentException if alias is not registered
     */
    public ResourceSet getResource(String alias) {
        ResourceSet rs = resourceRegistry.get(alias);
        if (rs == null) {
            throw new IllegalArgumentException(
                    "Unknown resource alias: '" + alias + "'. " +
                    "Available aliases: " + resourceRegistry.keySet()
            );
        }
        return rs;
    }

    /**
     * Get all instances of a type from an aliased resource.
     *
     * @param alias the resource alias
     * @param type the element type
     * @param <T> the element type
     * @return collection of instances
     */
    public <T extends EObject> Collection<T> all(String alias, Class<T> type) {
        return modelProvider.getAllContents(getResource(alias), type);
    }

    /**
     * Create a new element without adding it to any resource.
     * The element must be explicitly added to a containment reference.
     *
     * <p>For generated metamodels, the EPackage is auto-discovered from the Java class.
     * No package registration is needed.</p>
     *
     * @param type the element type to create (e.g., {@code Column.class})
     * @param <T> the element type
     * @return the new element (not contained anywhere)
     */
    public <T extends EObject> T create(Class<T> type) {
        EPackage pkg = resolvePackageForType(type);
        return createWithoutContainment(type, pkg);
    }

    /**
     * Create a new element in a specific package without containment (for dynamic EMF).
     *
     * <p><b>Not needed for generated metamodels</b> - use {@link #create(Class)} instead,
     * which auto-discovers the EPackage.</p>
     *
     * @param type the element type to create
     * @param pkg the EPackage containing the type (for dynamic EMF)
     * @param <T> the element type
     * @return the new element (not contained anywhere)
     */
    public <T extends EObject> T create(Class<T> type, EPackage pkg) {
        if (pkg == null) {
            throw new IllegalArgumentException("Package cannot be null");
        }
        return createWithoutContainment(type, pkg);
    }

    /**
     * Internal method to create an element without containment in a specific package.
     */
    @SuppressWarnings("unchecked")
    private <T extends EObject> T createWithoutContainment(Class<T> type, EPackage pkg) {
        String typeName = type.getSimpleName();
        EClass eClass = (EClass) pkg.getEClassifier(typeName);

        if (eClass == null) {
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' not found in package: " + pkg.getNsURI());
        }

        EObject instance = pkg.getEFactoryInstance().create(eClass);

        // Track for ordering but do NOT add to any resource
        if (stagingEnabled.get()) {
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(instance, sequence);
            // Stage as non-root element (won't be added to Resource.contents during commit)
            stagedElements.offer(new StagedElement(instance, false, sequence));
        }

        return (T) instance;
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
     *
     * @param key the attribute key
     * @param value the attribute value (null is allowed)
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, ofNullable(value));
    }

    /**
     * Get a custom attribute.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not set
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        Optional<Object> opt = attributes.get(key);
        return opt != null ? (T) opt.orElse(null) : null;
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
