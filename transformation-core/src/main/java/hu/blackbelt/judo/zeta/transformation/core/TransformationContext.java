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
import java.util.concurrent.locks.ReentrantLock;
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
     * Preferred alias for the source ResourceSet.
     * When set, getResourceAlias() returns this alias for elements from the source ResourceSet.
     * This allows transformations to use a custom alias like "esm" instead of "source".
     */
    private volatile String preferredSourceAlias = null;

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
     * Sequence counter for maintaining deterministic element ordering in staging.
     */
    private final AtomicLong creationSequence = new AtomicLong(0);

    /**
     * Sequence counter for deterministic ID generation (separate from ordering).
     */
    private final AtomicLong idSequence = new AtomicLong(0);

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
     * Key is (source, ruleName) for cross-rule isolation.
     */
    private final ConcurrentHashMap<RuleCacheKey, EObject> executingLazyRules = new ConcurrentHashMap<>();

    /**
     * Per-element locks for thread-safe rule execution.
     * Key is (source, ruleName) to allow unrelated sources to execute in parallel without blocking.
     * Uses ReentrantLock to handle recursive equivalent() calls from the same thread.
     */
    private final ConcurrentHashMap<RuleCacheKey, ReentrantLock> ruleLocks = new ConcurrentHashMap<>();

    /**
     * ThreadLocal set to track rule cache keys currently being executed in this thread.
     * Used to detect and handle recursive equivalent() calls.
     * Key is (source, ruleName) for cross-rule isolation.
     */
    private final ThreadLocal<Set<RuleCacheKey>> inProgressRules = ThreadLocal.withInitial(HashSet::new);

    private TransformationRegistry transformationRegistry;

    /**
     * Tracks which source elements have been "activated" for activity-based rules.
     * Used with {@code @Greedy @Lazy @ActivityBased} rules to match ETL semantics.
     */
    private final ActivationTracker activationTracker = new ActivationTracker();

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
     * When true, createTarget() automatically adds root elements to the target resource.
     * This provides convenience at the cost of strict ETL semantics compliance.
     * Default is false (ETL semantics - explicit addToResource() required).
     */
    private volatile boolean autoAddRootElements = false;

    /**
     * When true, generated XMI IDs follow ETL-style structured format:
     * &lt;source-path&gt;/&lt;rule-name&gt;/(discriminator/&lt;discriminator-value&gt;)
     * Default is true (ETL semantics for traceability).
     */
    private volatile boolean useStructuredIds = true;

    /**
     * When true, treat all @Greedy @Lazy rules as activity-based.
     * This matches Epsilon ETL behavior where greedy lazy rules only process
     * elements that are referenced via equivalent() calls.
     */
    private volatile boolean etlCompatibilityMode = false;

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
     * Key for tracking lazy rule executions using (source, ruleName) pair.
     *
     * <p>Using rule name instead of target type provides proper cross-rule isolation:
     * different rules transforming the same source element maintain independent cache entries.
     * This prevents cache pollution when multiple rules (e.g., Rule A and Rule B) both
     * apply to the same source element.</p>
     */
    private static class RuleCacheKey {
        final EObject source;
        final String ruleName;

        RuleCacheKey(EObject source, String ruleName) {
            this.source = source;
            this.ruleName = ruleName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RuleCacheKey that = (RuleCacheKey) o;
            return Objects.equals(source, that.source) && Objects.equals(ruleName, that.ruleName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, ruleName);
        }

        @Override
        public String toString() {
            return "RuleCacheKey{source=" + source + ", ruleName='" + ruleName + "'}";
        }
    }

    /**
     * Key for tracking named rule executions to prevent infinite recursion.
     */
    private static class NamedRuleKey {
        final EObject source;
        final String ruleName;

        NamedRuleKey(EObject source, String ruleName) {
            this.source = source;
            this.ruleName = ruleName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NamedRuleKey that = (NamedRuleKey) o;
            return Objects.equals(source, that.source) && Objects.equals(ruleName, that.ruleName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, ruleName);
        }
    }

    // Thread-local set to track in-progress named rule executions (prevents recursion)
    private final ThreadLocal<Set<NamedRuleKey>> inProgressNamedRules = ThreadLocal.withInitial(HashSet::new);

    /**
     * Thread-local pre-created target for @Extends inheritance.
     * When executing a child rule with @Extends, the framework pre-creates the target
     * of the child's type, then passes it through the inheritance chain.
     * All rules in the chain receive this via createTarget() instead of creating new instances.
     */
    private final ThreadLocal<EObject> preCreatedTarget = new ThreadLocal<>();

    /**
     * Thread-local flag indicating we're executing an @Extends inheritance chain.
     * During this phase, createTarget() returns the pre-created target if compatible.
     * This applies to BOTH parent rules AND the child rule in the chain.
     */
    private final ThreadLocal<Boolean> inInheritanceExecution = ThreadLocal.withInitial(() -> false);

    /**
     * Thread-local tracking of the currently executing rule.
     * Used to check if the current rule is @Detached in createTarget().
     */
    private final ThreadLocal<TransformRuleDescriptor> currentExecutingRule = new ThreadLocal<>();

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
     * Get the activation tracker for activity-based rules.
     *
     * @return the activation tracker
     */
    public ActivationTracker getActivationTracker() {
        return activationTracker;
    }

    /**
     * Record that a source element was activated for an activity-based rule.
     *
     * <p>This is a convenience method that delegates to the activation tracker.
     * Called from {@code equivalent()} when a matching activity-based rule is found.</p>
     *
     * @param ruleName the name of the activity-based rule
     * @param source the source element that was activated
     */
    public void activate(String ruleName, EObject source) {
        activationTracker.activate(ruleName, source);
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
     * Enable or disable automatic root element addition.
     *
     * <p>When enabled, {@link #createTarget(Class)} automatically adds created
     * elements to the target resource (legacy behavior). When disabled (default),
     * you must explicitly call {@link #addToResource(EObject)} for root elements
     * (strict ETL semantics).</p>
     *
     * @param autoAdd true to automatically add root elements, false for ETL semantics
     */
    public void setAutoAddRootElements(boolean autoAdd) {
        this.autoAddRootElements = autoAdd;
    }

    /**
     * Check if automatic root element addition is enabled.
     *
     * @return true if createTarget() automatically adds to resource
     */
    public boolean isAutoAddRootElements() {
        return autoAddRootElements;
    }

    /**
     * Enable or disable ETL-style structured XMI IDs.
     *
     * <p>When enabled (default), generated XMI IDs follow the ETL pattern:</p>
     * <pre>{@code <source-container>/(esm/<source-id>)/<rule-name>}</pre>
     *
     * <p>For discriminated equivalents:</p>
     * <pre>{@code <source-container>/(esm/<source-id>)/<rule-name>/(discriminator/<discriminator-value>)}</pre>
     *
     * <p>When disabled, simple UUIDs are used (legacy behavior).</p>
     *
     * @param useStructured true for ETL-style structured IDs, false for UUIDs
     */
    public void setUseStructuredIds(boolean useStructured) {
        this.useStructuredIds = useStructured;
    }

    /**
     * Check if ETL-style structured XMI IDs are enabled.
     *
     * @return true if structured IDs are used
     */
    public boolean isUseStructuredIds() {
        return useStructuredIds;
    }

    /**
     * Enable or disable ETL compatibility mode.
     *
     * <p>When enabled, all @Greedy @Lazy rules are treated as activity-based,
     * meaning they only process elements that are referenced via equivalent()
     * calls during transformation. This matches Epsilon ETL behavior.</p>
     *
     * @param enabled true to enable ETL compatibility mode
     */
    public void setEtlCompatibilityMode(boolean enabled) {
        this.etlCompatibilityMode = enabled;
    }

    /**
     * Check if ETL compatibility mode is enabled.
     *
     * @return true if ETL compatibility mode is active
     */
    public boolean isEtlCompatibilityMode() {
        return etlCompatibilityMode;
    }

    /**
     * Check if a rule is effectively activity-based.
     *
     * <p>A rule is effectively activity-based if:</p>
     * <ul>
     *   <li>It has the @ActivityBased annotation, OR</li>
     *   <li>ETL compatibility mode is enabled AND the rule is @Greedy @Lazy</li>
     * </ul>
     *
     * @param rule the rule to check
     * @return true if the rule should be treated as activity-based
     */
    public boolean isEffectivelyActivityBased(TransformRuleDescriptor rule) {
        if (rule.isActivityBased()) {
            return true;
        }
        return etlCompatibilityMode && rule.isGreedy() && rule.isLazy();
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
     *
     * <p>ETL semantics (default): Elements are NOT added to resource root automatically.
     * They become part of the model when assigned to containment references.
     * Only true root elements should be explicitly added via {@link #addToResource(EObject)}.</p>
     *
     * <p>When {@link #setAutoAddRootElements(boolean)} is set to true, elements are
     * automatically added to the resource (legacy behavior for convenience).</p>
     *
     * <p><b>@Extends inheritance:</b> During parent rule execution, if a pre-created target
     * exists and is compatible with the requested type, it is returned instead of creating
     * a new instance. This enables ETL-style inheritance where parent rules operate on the
     * same target instance as child rules.</p>
     *
     * <p><b>Structured IDs:</b> When {@link #setUseStructuredIds(boolean)} is enabled (default),
     * the created element gets an ETL-style structured XMI ID based on the source element
     * and rule name.</p>
     */
    @SuppressWarnings("unchecked")
    private <T extends EObject> T createTargetInPackage(Class<T> targetType, EPackage pkg) {
        // During @Extends inheritance execution, return pre-created target if compatible
        // This enables ETL-style inheritance: all rules in the chain share the same target
        if (Boolean.TRUE.equals(inInheritanceExecution.get())) {
            EObject preCreated = preCreatedTarget.get();
            if (preCreated != null && targetType.isInstance(preCreated)) {
                return targetType.cast(preCreated);
            }
        }

        String typeName = targetType.getSimpleName();
        EClass eClass = (EClass) pkg.getEClassifier(typeName);

        if (eClass == null) {
            throw new IllegalArgumentException(
                    "EClass '" + typeName + "' not found in package: " + pkg.getNsURI());
        }

        EObject instance = pkg.getEFactoryInstance().create(eClass);

        // Generate and set XMI ID
        // When useStructuredIds is enabled: ETL-style structured ID
        // When disabled: simple UUID
        EObject source = currentSource.get();
        TransformRuleDescriptor rule = currentExecutingRule.get();
        String ruleName = rule != null ? rule.getName() : null;
        String targetId = generateStructuredId(source, ruleName);
        setElementId(instance, targetId);

        // Check if current rule is @Detached - detached rules NEVER add to resource
        // The caller is responsible for adding to the appropriate container
        boolean isDetached = isCurrentRuleDetached();

        // When autoAddRootElements is enabled AND rule is NOT detached, add to resource
        // @Detached overrides autoAddRootElements
        if (autoAddRootElements && !isDetached) {
            addToResource(instance);
        }
        // Otherwise ETL semantics: do NOT add to resource root automatically
        // Elements become part of the model when assigned to containment references
        // Use addToResource() explicitly for true root elements

        return (T) instance;
    }

    /**
     * Add an element to the target resource as a root element.
     *
     * <p>Use this for elements that should be root elements in the target model,
     * not contained by other elements.</p>
     *
     * @param element the element to add as root
     */
    public void addToResource(EObject element) {
        if (element == null) {
            return;
        }

        if (stagingEnabled.get()) {
            // Parallel mode: stage for later commit with ordering
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(element, sequence);
            stagedElements.offer(new StagedElement(element, true, sequence));
        } else {
            // Sequential mode: add directly to Resource
            if (!targetResourceSet.getResources().isEmpty()) {
                Resource targetResource = targetResourceSet.getResources().get(0);
                targetResource.getContents().add(element);

                // Apply pending XMI ID if one was set before adding to resource
                String pendingId = pendingXmiIds.get(element);
                if (pendingId != null && targetResource instanceof XMIResource) {
                    setSynchronizedXmiId((XMIResource) targetResource, element, pendingId);
                }
            }
        }
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
                    // Record activation for activity-based rules
                    // This tracks which elements were referenced via equivalent()
                    if (rule.isActivityBased()) {
                        activate(rule.getName(), source);
                    }

                    if (rule.evaluateGuard(source, this)) {
                        // When structured IDs are enabled, try XMI ID-based lookup first (ETL semantics)
                        if (useStructuredIds) {
                            String structuredId = generateStructuredId(source, rule.getName());
                            T existingByXmiId = findByXmiId(structuredId, targetType);
                            if (existingByXmiId != null) {
                                // Found by XMI ID - cache it and return
                                resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
                                return existingByXmiId;
                            }
                        }

                        // Use (source, ruleName) key for cross-rule cache isolation
                        RuleCacheKey key = new RuleCacheKey(source, rule.getName());

                        // Fast path: check if already executed (from cache or concurrent execution)
                        EObject existing = executingLazyRules.get(key);
                        if (existing != null) {
                            return (T) existing;
                        }

                        // Check if this key is currently being executed in this thread (recursion)
                        Set<RuleCacheKey> inProgress = inProgressRules.get();
                        if (inProgress.contains(key)) {
                            // Recursive call detected - return null to break the cycle
                            // The caller should handle null gracefully
                            return null;
                        }

                        // Acquire per-element lock for thread-safe execution
                        // Uses computeIfAbsent for atomic lock creation
                        ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
                        lock.lock();
                        try {
                            // Double-check after acquiring lock (another thread may have completed)
                            EObject existingAfterLock = executingLazyRules.get(key);
                            if (existingAfterLock != null) {
                                return (T) existingAfterLock;
                            }

                            // Also double-check resolution cache
                            T cachedAgain = resolutionCache.getEquivalent(source, targetType);
                            if (cachedAgain != null) {
                                return cachedAgain;
                            }

                            // Mark as in-progress for recursion detection
                            inProgress.add(key);
                            try {
                                // CRITICAL: Save and reset inheritance state for equivalent() calls.
                                // When a transform function calls equivalent() to look up related elements,
                                // the nested transformation should start FRESH, not inherit the caller's
                                // inheritance context. Without this reset, the nested rule would see
                                // inInheritanceExecution=true and potentially reuse the caller's preCreatedTarget.
                                boolean wasInInheritance = isInInheritanceExecution();
                                EObject savedPreCreated = getPreCreatedTarget();
                                setInInheritanceExecution(false);
                                clearPreCreatedTarget();

                                try {
                                    // Execute the rule with clean inheritance state
                                    EObject result = rule.execute(source, this);
                                    if (result != null) {
                                        // Cache the result atomically
                                        resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                                        executingLazyRules.put(key, result);
                                    }
                                    return (T) result;
                                } finally {
                                    // Restore inheritance state for caller
                                    setInInheritanceExecution(wasInInheritance);
                                    if (savedPreCreated != null) {
                                        setPreCreatedTarget(savedPreCreated);
                                    } else {
                                        clearPreCreatedTarget();
                                    }
                                }
                            } finally {
                                inProgress.remove(key);
                            }
                        } finally {
                            lock.unlock();
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

        // Record activation for effectively activity-based rules
        // This tracks which elements were referenced via equivalent()
        // For activity-based rules, we ONLY record activation and return null
        // The actual execution happens in Phase 2 (executeActivityBasedRules)
        if (isEffectivelyActivityBased(rule)) {
            activate(rule.getName(), source);
            // Don't execute now - Phase 2 will execute for activated elements
            return null;
        }

        // When structured IDs are enabled, try XMI ID-based lookup first (ETL semantics)
        if (useStructuredIds) {
            String structuredId = generateStructuredId(source, ruleName);
            T existingByXmiId = findByXmiId(structuredId, (Class<T>) rule.getTargetType());
            if (existingByXmiId != null) {
                // Found by XMI ID - cache it and return
                resolutionCache.addMapping(source, ruleName, existingByXmiId, rule.isPrimary());
                return existingByXmiId;
            }
        }

        // ETL semantics: guards ARE evaluated at invocation time for @lazy rules
        if (!rule.evaluateGuard(source, this)) {
            return null;
        }

        // Use (source, ruleName) key for cross-rule cache isolation
        RuleCacheKey key = new RuleCacheKey(source, ruleName);

        // Fast path: check if already executed
        EObject existing = executingLazyRules.get(key);
        if (existing != null) {
            return (T) existing;
        }

        // Check for recursion - if we're already executing this rule for this source
        NamedRuleKey ruleKey = new NamedRuleKey(source, ruleName);
        Set<NamedRuleKey> inProgress = inProgressNamedRules.get();
        if (inProgress.contains(ruleKey)) {
            // Recursive call detected - return null to break the cycle
            return null;
        }

        // Acquire per-element lock for thread-safe execution
        ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check after acquiring lock (another thread may have completed)
            EObject existingAfterLock = executingLazyRules.get(key);
            if (existingAfterLock != null) {
                return (T) existingAfterLock;
            }

            // Also double-check resolution cache
            cached = resolutionCache.getByRule(source, ruleName);
            if (cached != null) {
                return (T) cached;
            }

            // Mark as in-progress for recursion detection
            inProgress.add(ruleKey);
            try {
                // CRITICAL: Save and reset inheritance state for equivalent() calls.
                // When a transform function calls equivalent() to look up related elements,
                // the nested transformation should start FRESH, not inherit the caller's
                // inheritance context. Without this reset, the nested rule would see
                // inInheritanceExecution=true and potentially reuse the caller's preCreatedTarget.
                boolean wasInInheritance = isInInheritanceExecution();
                EObject savedPreCreated = getPreCreatedTarget();
                setInInheritanceExecution(false);
                clearPreCreatedTarget();

                try {
                    // Execute the rule with clean inheritance state
                    EObject result = rule.execute(source, this);
                    if (result != null) {
                        // Cache the result atomically
                        resolutionCache.addMapping(source, ruleName, result, rule.isPrimary());
                        executingLazyRules.put(key, result);
                    }
                    return (T) result;
                } finally {
                    // Restore inheritance state for caller
                    setInInheritanceExecution(wasInInheritance);
                    if (savedPreCreated != null) {
                        setPreCreatedTarget(savedPreCreated);
                    } else {
                        clearPreCreatedTarget();
                    }
                }
            } finally {
                inProgress.remove(ruleKey);
            }
        } finally {
            lock.unlock();
        }
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
        // When structured IDs are enabled, use XMI ID-based lookup first (ETL semantics)
        if (useStructuredIds && discriminator != null) {
            String baseId = generateStructuredId(source, ruleName);
            String discriminatedId = generateDiscriminatedId(baseId, discriminator);

            // Look up by XMI ID in target resource
            T existing = findByXmiId(discriminatedId, targetType);
            if (existing != null) {
                // Cache it for future lookups and return
                resolutionCache.addDiscriminatedMapping(source, existing, ruleName, discriminator);
                return existing;
            }
        }

        // Check discriminated cache (object-reference based)
        T cached = resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, discriminator);
        if (cached != null) {
            return cached;
        }

        // If ruleName is specified, find and execute that specific rule
        T original = null;
        if (ruleName != null && transformationRegistry != null) {
            TransformRuleDescriptor rule = transformationRegistry.getRuleByName(ruleName);
            if (rule != null && rule.appliesTo(source)) {
                // Record activation for activity-based rules
                // This tracks which elements were referenced via equivalent()
                if (rule.isActivityBased()) {
                    activate(rule.getName(), source);
                }
            }
            // ETL semantics: guards ARE evaluated at invocation time for @lazy rules
            if (rule != null && rule.appliesTo(source) && rule.evaluateGuard(source, this)) {
                // Check if already in cache by rule name
                EObject existing = resolutionCache.getByRule(source, ruleName);
                if (existing != null && targetType.isInstance(existing)) {
                    original = (T) existing;
                } else {
                    // Check for recursion - if we're already executing this rule for this source
                    NamedRuleKey ruleKey = new NamedRuleKey(source, ruleName);
                    Set<NamedRuleKey> inProgress = inProgressNamedRules.get();
                    if (inProgress.contains(ruleKey)) {
                        // Recursive call detected - return null to break the cycle
                        return null;
                    }

                    // Mark as in-progress and execute the rule
                    inProgress.add(ruleKey);
                    try {
                        // Double-check cache after marking in-progress
                        existing = resolutionCache.getByRule(source, ruleName);
                        if (existing != null && targetType.isInstance(existing)) {
                            original = (T) existing;
                        } else {
                            // CRITICAL: Save and reset inheritance state for equivalent() calls.
                            // When a transform function calls equivalent() to look up related elements,
                            // the nested transformation should start FRESH, not inherit the caller's
                            // inheritance context. Without this reset, the nested rule would see
                            // inInheritanceExecution=true and potentially reuse the caller's preCreatedTarget.
                            boolean wasInInheritance = isInInheritanceExecution();
                            EObject savedPreCreated = getPreCreatedTarget();
                            setInInheritanceExecution(false);
                            clearPreCreatedTarget();

                            try {
                                // Execute the specific rule with clean inheritance state
                                EObject result = rule.execute(source, this);
                                if (result != null) {
                                    resolutionCache.addMapping(source, ruleName, result, rule.isPrimary());
                                    if (targetType.isInstance(result)) {
                                        original = (T) result;
                                    }
                                }
                            } finally {
                                // Restore inheritance state for caller
                                setInInheritanceExecution(wasInInheritance);
                                if (savedPreCreated != null) {
                                    setPreCreatedTarget(savedPreCreated);
                                } else {
                                    clearPreCreatedTarget();
                                }
                            }
                        }
                    } finally {
                        inProgress.remove(ruleKey);
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

        // Generate discriminated ID following ETL semantics
        // Format: <source-path>/<rule-name>/(discriminator/<discriminator-value>)
        String discriminatedId;
        if (useStructuredIds) {
            String baseId = generateStructuredId(source, ruleName);
            discriminatedId = generateDiscriminatedId(baseId, discriminator);
        } else {
            // Legacy: append discriminator to whatever ID the original has
            String baseId = getElementId(original);
            discriminatedId = baseId + "/(discriminator/" + discriminator + ")";
        }
        setElementId(clone, discriminatedId);

        // Check if the rule is @Detached - detached rules don't add to Resource
        // The caller is responsible for adding to the appropriate container
        TransformRuleDescriptor rule = transformationRegistry != null
                ? transformationRegistry.getRuleByName(ruleName)
                : null;
        boolean isDetached = rule != null && rule.isDetached();

        if (!isDetached) {
            // Only add to resource if NOT detached
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

                    // Apply the discriminated XMI ID
                    if (targetResource instanceof XMIResource) {
                        setSynchronizedXmiId((XMIResource) targetResource, clone, discriminatedId);
                    }
                }
            }
        }
        // For @Detached rules: caller adds clone to appropriate container
        // e.g., page.getActions().add(clone)

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
        return executeParentRule(parentRuleName, source, null);
    }

    /**
     * Execute a parent rule with a pre-created target (for manual @Extends inheritance).
     *
     * <p>This overload allows the child rule to create the target first and pass it
     * to the parent rule. The parent's {@code createTarget()} calls will return the
     * pre-created target instead of creating a new instance.</p>
     *
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Parent rules that have abstract target types (cannot be instantiated)</li>
     *   <li>Cases where the child needs to control the concrete type</li>
     *   <li>Legacy code migration where rules were designed differently</li>
     * </ul>
     *
     * <p>Example:</p>
     * <pre>{@code
     * @TransformRule(name = "ConcreteRule")
     * public TransformFunction<Entity, Table> concreteRule() {
     *     return (entity, ctx) -> {
     *         Table table = ctx.createTarget(Table.class);
     *         ctx.executeParentRule("AbstractRule", entity, table);  // Parent uses same table
     *         table.setSpecificProperty("value");
     *         return table;
     *     };
     * }
     * }</pre>
     *
     * <p><b>Important:</b> When invoking a lazy rule WITHOUT a pre-created target,
     * this method resets the inheritance context so the lazy rule can create its own
     * target independently. This fixes issues where nested lazy rule invocations
     * would incorrectly inherit the caller's inheritance state.</p>
     *
     * @param parentRuleName the parent rule name
     * @param source the source element
     * @param target the pre-created target (parent's createTarget() will return this)
     * @param <T> the target type
     * @return the target from parent rule execution (same as passed target if provided)
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T executeParentRule(String parentRuleName, EObject source, T target) {
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

        // Save current inheritance state to restore later
        boolean wasInInheritance = isInInheritanceExecution();
        EObject previousPreCreated = getPreCreatedTarget();

        if (target != null) {
            // If target provided, set up inheritance context so parent's createTarget() returns it
            setPreCreatedTarget(target);
            setInInheritanceExecution(true);
        } else if (wasInInheritance && previousPreCreated != null && parentRule.isLazy()) {
            // IMPORTANT: When invoking a LAZY rule from within an inheritance context,
            // ALWAYS reset the context so the lazy rule creates its own independent target.
            // This is crucial because lazy rules are meant to create separate objects,
            // not share the caller's pre-created target.
            //
            // Without this fix, two lazy rules with the same target type (e.g., both creating
            // UnmappedTransferObjectType) would incorrectly share the same pre-created target,
            // with the second rule overwriting the first rule's properties.
            clearPreCreatedTarget();
            setInInheritanceExecution(false);
        }

        try {
            // Execute parent rule and cache the result
            EObject result = parentRule.execute(source, this);
            if (result != null) {
                resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
            }
            return (T) result;
        } finally {
            // Restore previous inheritance state
            if (previousPreCreated != null) {
                setPreCreatedTarget(previousPreCreated);
            } else {
                clearPreCreatedTarget();
            }
            setInInheritanceExecution(wasInInheritance);
        }
    }

    // ==================== @Extends Inheritance Support ====================

    /**
     * Set the pre-created target for @Extends inheritance chain execution.
     * Called by TransformRuleDescriptor before executing parent rules.
     *
     * @param target the pre-created target (child's type)
     */
    void setPreCreatedTarget(EObject target) {
        preCreatedTarget.set(target);
    }

    /**
     * Get the pre-created target for the current inheritance chain.
     *
     * @return the pre-created target, or null if not in inheritance execution
     */
    EObject getPreCreatedTarget() {
        return preCreatedTarget.get();
    }

    /**
     * Clear the pre-created target after inheritance chain execution.
     */
    void clearPreCreatedTarget() {
        preCreatedTarget.remove();
    }

    /**
     * Set whether we're currently executing an @Extends inheritance chain.
     * When true, createTarget() returns the pre-created target if compatible.
     *
     * @param executing true during inheritance chain execution
     */
    void setInInheritanceExecution(boolean executing) {
        inInheritanceExecution.set(executing);
    }

    /**
     * Check if we're currently executing an @Extends inheritance chain.
     *
     * @return true if in inheritance chain execution
     */
    boolean isInInheritanceExecution() {
        return Boolean.TRUE.equals(inInheritanceExecution.get());
    }

    /**
     * Set a structured XMI ID on a target element.
     *
     * <p>This is called by TransformRuleDescriptor when pre-creating a target
     * in executeWithInheritance(). Since createTargetDirectly() bypasses the
     * normal createTarget() flow, the structured ID must be set explicitly.</p>
     *
     * @param target the target element
     * @param source the source element (for ID generation)
     * @param ruleName the rule name (for ID generation)
     */
    void setStructuredIdOnTarget(EObject target, EObject source, String ruleName) {
        if (target == null) {
            return;
        }
        // Only set if not already set
        String existingId = getPendingXmiId(target);
        if (existingId != null) {
            return;
        }
        String targetId = generateStructuredId(source, ruleName);
        setElementId(target, targetId);
    }

    /**
     * Get the transformation registry.
     *
     * @return the transformation registry
     */
    public TransformationRegistry getTransformationRegistry() {
        return transformationRegistry;
    }

    // ==================== Current Rule Tracking (@Detached support) ====================

    /**
     * Set the currently executing rule.
     * Called by rule execution framework before invoking a rule's transform function.
     *
     * @param rule the rule being executed
     */
    void setCurrentExecutingRule(TransformRuleDescriptor rule) {
        currentExecutingRule.set(rule);
    }

    /**
     * Get the currently executing rule.
     *
     * @return the current rule, or null if not executing a rule
     */
    TransformRuleDescriptor getCurrentExecutingRule() {
        return currentExecutingRule.get();
    }

    /**
     * Clear the current executing rule.
     * Called after rule execution completes.
     */
    void clearCurrentExecutingRule() {
        currentExecutingRule.remove();
    }

    /**
     * Check if the currently executing rule is marked as @Detached.
     *
     * @return true if the current rule is detached
     */
    boolean isCurrentRuleDetached() {
        TransformRuleDescriptor rule = currentExecutingRule.get();
        return rule != null && rule.isDetached();
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
     * Set the preferred alias for the source ResourceSet.
     * When set, getResourceAlias() returns this alias for elements from the source ResourceSet.
     * This allows transformations to use a custom alias like "esm" instead of "source".
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * ctx.registerResource("esm", esmModel.getResourceSet());
     * ctx.setPreferredSourceAlias("esm");
     * // Now XMI IDs will use "esm" instead of "source"
     * }</pre>
     *
     * @param alias the preferred alias (must be registered)
     * @throws IllegalArgumentException if alias is not registered
     */
    public void setPreferredSourceAlias(String alias) {
        if (alias != null && !resourceRegistry.containsKey(alias)) {
            throw new IllegalArgumentException(
                    "Cannot set preferred source alias '" + alias + "' - not registered. " +
                    "Available aliases: " + resourceRegistry.keySet()
            );
        }
        this.preferredSourceAlias = alias;
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
                setSynchronizedXmiId(xmiResource, obj, pendingId);
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
                setSynchronizedXmiId(xmiResource, child, pendingId);
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
        idSequence.set(0);
    }

    /**
     * Clear pending XMI IDs (called on reset).
     */
    void clearPendingXmiIds() {
        pendingXmiIds.clear();
    }

    /**
     * Apply all pending XMI IDs to elements in the target resource.
     *
     * <p>This should be called after the transformation completes to ensure all elements
     * have their XMI IDs properly set. Elements added through containment references
     * (not via addToResource()) may have pending IDs that were never applied.</p>
     *
     * <p>This method iterates through all elements in the target resource and applies
     * any pending IDs that haven't been applied yet.</p>
     */
    public void applyAllPendingXmiIds() {
        if (targetResourceSet.getResources().isEmpty()) {
            return;
        }

        Resource targetResource = targetResourceSet.getResources().get(0);
        if (!(targetResource instanceof XMIResource)) {
            return;
        }

        XMIResource xmiResource = (XMIResource) targetResource;

        // Apply pending IDs to all elements in the resource
        int appliedCount = 0;
        for (Map.Entry<EObject, String> entry : pendingXmiIds.entrySet()) {
            EObject element = entry.getKey();
            String pendingId = entry.getValue();

            // Only apply if the element is in this resource and doesn't already have an ID set
            if (element.eResource() == targetResource) {
                synchronized (xmiResource) {
                    String existingId = xmiResource.getID(element);
                    if (existingId == null || !existingId.equals(pendingId)) {
                        xmiResource.setID(element, pendingId);
                        appliedCount++;
                    }
                }
            }
        }

        // Note: Applied appliedCount pending XMI IDs to elements
    }

    /**
     * Clear lazy rule execution tracking and per-element locks (called on reset).
     */
    void clearExecutingLazyRules() {
        executingLazyRules.clear();
        ruleLocks.clear();
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

    /**
     * Get the XMI ID of an element (from resource or generate one).
     *
     * @param element the element
     * @return the element's XMI ID
     */
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
        String generatedId = "_" + UUID.randomUUID().toString().replace("-", "");
        if (stagingEnabled.get()) {
            pendingXmiIds.put(element, generatedId);
        }
        return generatedId;
    }

    /**
     * Get the source element's path for structured ID generation.
     *
     * <p>Format: {@code <container-name>/(<alias>/<source-id>)} or just {@code (<alias>/<source-id>)}
     * if no named container is available. The alias is determined from the registered resource aliases.</p>
     *
     * @param source the source element
     * @return the source path string
     */
    private String getSourcePath(EObject source) {
        if (source == null) {
            return "";
        }

        // Get the source element's XMI ID
        String sourceId = getSourceElementId(source);

        // Get the resource alias for the source element
        String alias = getResourceAlias(source);

        // Try to get a container name (useful for traceability)
        String containerName = getContainerName(source);

        if (containerName != null && !containerName.isEmpty()) {
            return containerName + "/(" + alias + "/" + sourceId + ")";
        } else {
            return "(" + alias + "/" + sourceId + ")";
        }
    }

    /**
     * Find an element in the target resource by its XMI ID.
     *
     * <p>This enables ETL-style ID-based lookup for discriminated equivalents.
     * When the same (source + discriminator) combination is looked up again,
     * the existing element is found by its XMI ID.</p>
     *
     * @param xmiId the XMI ID to search for
     * @param targetType the expected target type
     * @param <T> the target type
     * @return the element with the given ID, or null if not found
     */
    @SuppressWarnings("unchecked")
    private <T extends EObject> T findByXmiId(String xmiId, Class<T> targetType) {
        if (xmiId == null || targetResourceSet.getResources().isEmpty()) {
            return null;
        }

        Resource targetResource = targetResourceSet.getResources().get(0);

        // Check XMI resource for ID-based lookup
        if (targetResource instanceof XMIResource) {
            XMIResource xmiResource = (XMIResource) targetResource;
            EObject element = xmiResource.getEObject(xmiId);
            if (element != null && targetType.isInstance(element)) {
                return (T) element;
            }
        }

        // Also check pending XMI IDs for staged elements not yet committed
        for (Map.Entry<EObject, String> entry : pendingXmiIds.entrySet()) {
            if (xmiId.equals(entry.getValue()) && targetType.isInstance(entry.getKey())) {
                return (T) entry.getKey();
            }
        }

        return null;
    }

    /**
     * Get the resource alias for an element based on its ResourceSet.
     *
     * <p>Looks up the element's ResourceSet in the registered resource aliases.
     * If a preferred source alias is set and the element is from that ResourceSet,
     * returns the preferred alias. Otherwise returns the first matching alias,
     * or "source" as default if no match is found.</p>
     *
     * @param element the element
     * @return the resource alias (e.g., "esm", "asm", "mapping")
     */
    private String getResourceAlias(EObject element) {
        if (element == null) {
            return preferredSourceAlias != null ? preferredSourceAlias : "source";
        }

        Resource resource = element.eResource();
        if (resource == null) {
            return preferredSourceAlias != null ? preferredSourceAlias : "source";
        }

        ResourceSet elementResourceSet = resource.getResourceSet();
        if (elementResourceSet == null) {
            return preferredSourceAlias != null ? preferredSourceAlias : "source";
        }

        // If preferred source alias is set and element is from that ResourceSet, use it
        if (preferredSourceAlias != null) {
            ResourceSet preferredResourceSet = resourceRegistry.get(preferredSourceAlias);
            if (preferredResourceSet == elementResourceSet) {
                return preferredSourceAlias;
            }
        }

        // Find the alias for this ResourceSet
        for (Map.Entry<String, ResourceSet> entry : resourceRegistry.entrySet()) {
            if (entry.getValue() == elementResourceSet) {
                return entry.getKey();
            }
        }

        // Default to "source" if no matching alias found
        return "source";
    }

    /**
     * Get the XMI ID of a source element (from its resource).
     *
     * @param source the source element
     * @return the source element's XMI ID
     */
    private String getSourceElementId(EObject source) {
        Resource resource = source.eResource();
        if (resource != null) {
            String id = resource.getURIFragment(source);
            if (id != null && !id.startsWith("/")) {
                return id;
            }
        }

        // Try to get "id" attribute
        EStructuralFeature idFeature = source.eClass().getEStructuralFeature("id");
        if (idFeature != null) {
            Object idValue = source.eGet(idFeature);
            if (idValue != null) {
                return idValue.toString();
            }
        }

        // Fallback to hash-based ID for consistency
        return "_" + Integer.toHexString(System.identityHashCode(source));
    }

    /**
     * Get a meaningful container name for the source element.
     *
     * <p>Tries to find a named container (element with "name" attribute)
     * walking up the containment hierarchy.</p>
     *
     * @param source the source element
     * @return the container name, or null if none found
     */
    private String getContainerName(EObject source) {
        // First try the source element itself
        String name = getElementName(source);
        if (name != null) {
            return name;
        }

        // Walk up containment hierarchy looking for named element
        EObject container = source.eContainer();
        while (container != null) {
            name = getElementName(container);
            if (name != null) {
                return name;
            }
            container = container.eContainer();
        }

        return null;
    }

    /**
     * Get the "name" attribute of an element if available.
     *
     * @param element the element
     * @return the name value, or null if not available
     */
    private String getElementName(EObject element) {
        EStructuralFeature nameFeature = element.eClass().getEStructuralFeature("name");
        if (nameFeature != null) {
            Object nameValue = element.eGet(nameFeature);
            if (nameValue != null && !nameValue.toString().isEmpty()) {
                return nameValue.toString();
            }
        }
        return null;
    }

    /**
     * Generate a structured XMI ID following ETL semantics.
     *
     * <p>Format: {@code <source-path>/<rule-name>}</p>
     * <p>Example: {@code GenericUser/(esm/_abc123)/Entity2Table}</p>
     *
     * <p>When no source or rule is available, falls back to a deterministic
     * sequence-based ID to ensure reproducible XMI output.</p>
     *
     * @param source the source element (can be null)
     * @param ruleName the rule name (can be null)
     * @return the structured ID, or a sequence-based ID if no context available
     */
    String generateStructuredId(EObject source, String ruleName) {
        if (!useStructuredIds) {
            // Deterministic ID based on ID sequence for reproducibility
            return "_seq" + idSequence.getAndIncrement();
        }

        StringBuilder id = new StringBuilder();

        // Add source path
        if (source != null) {
            id.append(getSourcePath(source));
        }

        // Add rule name
        if (ruleName != null && !ruleName.isEmpty()) {
            if (id.length() > 0) {
                id.append("/");
            }
            id.append(ruleName);
        }

        // If no source or rule name, fall back to deterministic sequence-based ID
        if (id.length() == 0) {
            return "_seq" + idSequence.getAndIncrement();
        }

        return id.toString();
    }

    /**
     * Generate a discriminated structured XMI ID following ETL semantics.
     *
     * <p>Format: {@code <base-id>/(discriminator/<discriminator-value>)}</p>
     * <p>Example: {@code GenericUser/(esm/_abc123)/TableAction/(discriminator/relation1)}</p>
     *
     * @param baseId the base structured ID
     * @param discriminator the discriminator value
     * @return the discriminated ID
     */
    String generateDiscriminatedId(String baseId, String discriminator) {
        if (discriminator == null || discriminator.isEmpty()) {
            return baseId;
        }
        return baseId + "/(discriminator/" + discriminator + ")";
    }

    private void setElementId(EObject element, String id) {
        // Set "id" structural feature if available
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null && idFeature.isChangeable()) {
            element.eSet(idFeature, id);
        }

        // Always store in pendingXmiIds for later retrieval/application
        pendingXmiIds.put(element, id);

        // If element is already in a resource, apply the ID now
        Resource resource = element.eResource();
        if (resource instanceof XMIResource) {
            setSynchronizedXmiId((XMIResource) resource, element, id);
        }
    }

    /**
     * Thread-safe XMI ID setter.
     *
     * <p>Synchronizes on the resource to prevent concurrent modification exceptions
     * when multiple threads set IDs on elements in the same resource during parallel
     * transformation execution.</p>
     *
     * @param resource the XMI resource
     * @param element the element to set the ID for
     * @param id the XMI ID to set
     */
    private void setSynchronizedXmiId(XMIResource resource, EObject element, String id) {
        if (resource == null || element == null || id == null) {
            return;
        }
        synchronized (resource) {
            resource.setID(element, id);
        }
    }
}
