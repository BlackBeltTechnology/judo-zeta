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
import hu.blackbelt.judo.zeta.transformation.core.deferred.DeferredEObject;
import hu.blackbelt.judo.zeta.transformation.core.deferred.OperationQueue;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final Logger LOG = LoggerFactory.getLogger(TransformationContext.class);

    private final ModelProvider modelProvider;
    private final ResourceSet sourceResourceSet;
    private final ResourceSet targetResourceSet;
    private final ExtensionMethodRegistry extensionRegistry;
    private ElementResolutionCache resolutionCache;

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
     * Reverse index for O(1) ResourceSet → alias lookup.
     * Populated when resources are registered.
     */
    private final Map<ResourceSet, String> resourceSetToAlias = new ConcurrentHashMap<>();

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
     * Counter for staged elements to establish memory visibility.
     * Incrementing after offer() and reading before poll() creates a happens-before
     * relationship that ensures all staged elements are visible during commit.
     */
    private final AtomicInteger stagedElementCount = new AtomicInteger(0);

    /**
     * Flag indicating whether element staging is enabled.
     * When true, createTarget() stages elements instead of adding to Resource.
     */
    private final AtomicBoolean stagingEnabled = new AtomicBoolean(false);

    /**
     * Flag to skip XMIResource.getEObject() lookup in findByXmiId.
     * Set to true for fresh transformations where no pre-existing elements exist.
     * This avoids expensive XMI resource lookups that always return null.
     */
    private volatile boolean skipXmiIdResourceLookup = true;

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
     * Reverse lookup index for pendingXmiIds: xmiId -> EObject.
     * Enables O(1) lookup by XMI ID instead of O(n) linear scan.
     */
    private final ConcurrentHashMap<String, EObject> pendingXmiIdIndex = new ConcurrentHashMap<>();

    /**
     * Track which rule created each element.
     * Used to distinguish same-rule vs external ID reads.
     */
    private final ConcurrentHashMap<EObject, TransformRuleDescriptor> elementCreatingRule = new ConcurrentHashMap<>();

    /**
     * Track elements whose ID was read by a rule OTHER than the creating rule.
     * Once an element is in this map, its ID becomes immutable (setElementId will throw).
     * Value is the first rule that read the ID externally (for error messages).
     */
    private final ConcurrentHashMap<EObject, TransformRuleDescriptor> idReadByExternalRule = new ConcurrentHashMap<>();

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
     * Whether to include the source element's name as a prefix in structured IDs.
     * When false (default), IDs are: (alias/sourceId)/RuleName
     * When true, IDs are: ElementName/(alias/sourceId)/RuleName
     *
     * <p>Mutually exclusive with {@link #globalIdPrefix}.</p>
     */
    private volatile boolean includeElementNameInStructuredIds = false;

    /**
     * Global prefix to prepend to ALL generated structured XMI IDs.
     * When set, IDs have format: GlobalPrefix/(alias/sourceId)/RuleName
     *
     * <p>This matches ETL behavior where actorType.name prefixes all IDs uniformly.
     * Mutually exclusive with {@link #includeElementNameInStructuredIds}.</p>
     */
    private volatile String globalIdPrefix = null;

    /**
     * Cache for structured ID generation to avoid repeated string building.
     * Key is (source identity hash, ruleName), value is the generated ID.
     */
    private final Map<Long, String> structuredIdCache = new ConcurrentHashMap<>();

    /**
     * When true, treat all @Greedy @Lazy rules as activity-based.
     * This matches Epsilon ETL behavior where greedy lazy rules only process
     * elements that are referenced via equivalent() calls.
     */
    private volatile boolean etlCompatibilityMode = false;

    /**
     * When true, EMF write operations are deferred and recorded as operations
     * for later replay during the commit phase. This provides complete thread
     * isolation during parallel transformation execution.
     *
     * <p>When enabled, {@code createTarget()} returns proxied EObjects that
     * intercept setters and list modifications.</p>
     */
    private volatile boolean deferredWritesEnabled = false;

    /**
     * Strategy for equivalentDiscriminated cloning behavior.
     * Default: CLONE_PRISTINE (existing behavior).
     */
    private EquivalentDiscriminatedStrategy equivalentDiscriminatedStrategy =
            EquivalentDiscriminatedStrategy.CLONE_PRISTINE;

    /**
     * Tracks first-call semantics for CLONE_CURRENT_STATE strategy.
     */
    private final OriginalTracker originalTracker = new OriginalTracker();

    /**
     * Queue for storing deferred EMF operations during parallel phase.
     * Operations are replayed in sequence order during commit.
     */
    private final OperationQueue operationQueue = new OperationQueue();

    /**
     * Collection of all created deferred proxies.
     * Used to clear pending state after commit to prevent double-counting.
     */
    private final Set<DeferredEObject.ProxyMarker> createdProxies =
            ConcurrentHashMap.newKeySet();

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
        private final int hashCode;  // Pre-computed for performance

        RuleCacheKey(EObject source, String ruleName) {
            this.source = source;
            this.ruleName = ruleName;
            // Use identity hash for source (fast) instead of EMF hashCode (slow)
            this.hashCode = System.identityHashCode(source) ^ ruleName.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RuleCacheKey that = (RuleCacheKey) o;
            // Use identity comparison for source (EMF identity = object reference)
            return source == that.source && Objects.equals(ruleName, that.ruleName);
        }

        @Override
        public int hashCode() {
            return hashCode;  // Return pre-computed hash
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

    /**
     * Cache key for discriminated equivalent lookups.
     * Combines source element, rule name, and discriminator value.
     */
    private static class DiscriminatedCacheKey {
        final EObject source;
        final String ruleName;
        final String discriminator;

        DiscriminatedCacheKey(EObject source, String ruleName, String discriminator) {
            this.source = source;
            this.ruleName = ruleName;
            this.discriminator = discriminator;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DiscriminatedCacheKey that = (DiscriminatedCacheKey) o;
            return Objects.equals(source, that.source)
                    && Objects.equals(ruleName, that.ruleName)
                    && Objects.equals(discriminator, that.discriminator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, ruleName, discriminator);
        }
    }

    /**
     * Per-key locks for thread-safe discriminated equivalent execution.
     * Uses ReentrantLock to prevent duplicate clone creation when multiple threads
     * call equivalentDiscriminated() with the same (source, ruleName, discriminator) tuple.
     */
    private final ConcurrentHashMap<DiscriminatedCacheKey, ReentrantLock> discriminatedLocks = new ConcurrentHashMap<>();

    /**
     * Cache key for discriminator-only lookups (ETL-compatible mode).
     * Uses only (ruleName, discriminator) as key, ignoring source object identity.
     *
     * <p>This enables sharing instances across different source objects when they
     * resolve to the same discriminator value - matching ETL's string-based caching.</p>
     */
    private static class DiscriminatorOnlyCacheKey {
        final String ruleName;
        final String discriminator;

        DiscriminatorOnlyCacheKey(String ruleName, String discriminator) {
            this.ruleName = ruleName;
            this.discriminator = discriminator;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DiscriminatorOnlyCacheKey that = (DiscriminatorOnlyCacheKey) o;
            return Objects.equals(ruleName, that.ruleName)
                    && Objects.equals(discriminator, that.discriminator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleName, discriminator);
        }

        @Override
        public String toString() {
            return "DiscriminatorOnlyCacheKey{ruleName='" + ruleName +
                   "', discriminator='" + discriminator + "'}";
        }
    }

    /**
     * Cache for discriminator-only lookups (ETL-compatible mode).
     * Key: (ruleName, discriminator) -> Value: target EObject
     *
     * <p>When {@link DiscriminatorResolver.Resolution#useDiscriminatorOnlyCache()} is true,
     * this cache is used instead of the source-based discriminated cache.</p>
     */
    private final ConcurrentHashMap<DiscriminatorOnlyCacheKey, EObject> discriminatorOnlyCache =
        new ConcurrentHashMap<>();

    /**
     * Per-key locks for thread-safe discriminator-only cache operations.
     */
    private final ConcurrentHashMap<DiscriminatorOnlyCacheKey, ReentrantLock> discriminatorOnlyLocks =
        new ConcurrentHashMap<>();

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

    /**
     * Thread-local flag indicating whether the current thread is executing a lazy rule
     * via equivalentDiscriminated(). When true, addToResource() should skip adding
     * the original element to the resource because only the discriminated clone
     * should be added.
     *
     * <p>This prevents orphan elements caused by both the original and clone being
     * added to Resource.contents.</p>
     */
    private final ThreadLocal<Boolean> inDiscriminatedExecution = ThreadLocal.withInitial(() -> false);

    /**
     * Thread-local counter for tracking multiple createTarget() calls within the same rule execution.
     * Key format: "sourceIdentityHash_ruleName_targetTypeName"
     * Value: instance count (0 = first instance, 1 = second, etc.)
     *
     * This is used to generate unique XMI IDs when a single rule creates multiple elements.
     * Without this, all elements created by createTarget() in the same rule get the same ID.
     */
    private final ThreadLocal<Map<String, Integer>> ruleInstanceCounters =
            ThreadLocal.withInitial(HashMap::new);

    // ==================== Rule Invocation Stack (Path C Implementation) ====================

    /**
     * Thread-local stack tracking the chain of rule invocations.
     *
     * <p>This stack enables:
     * <ul>
     *   <li>Debugging and tracing of transformation call chains</li>
     *   <li>Context-aware discriminator resolution via {@link DiscriminatorResolver}</li>
     *   <li>Understanding how elements were created (call path)</li>
     * </ul>
     *
     * <p>The stack is pushed when a rule starts execution and popped when it completes.
     * Most recent invocation is at the top of the stack (index 0 when converted to list).</p>
     *
     * @see #pushRuleInvocation(String, EObject, String)
     * @see #popRuleInvocation()
     * @see #getRuleInvocationChain()
     */
    private final ThreadLocal<Deque<RuleInvocation>> ruleInvocationStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Optional discriminator resolver for context-aware discriminator inference.
     *
     * <p>When set, {@code equivalentDiscriminated()} with a null discriminator
     * will call this resolver to determine the appropriate discriminator based
     * on the current rule invocation chain.</p>
     *
     * <p>This enables ETL-compatible discriminator patterns where the discriminator
     * depends on the calling context (e.g., Button vs Action context for ActionDefinitions).</p>
     *
     * @see DiscriminatorResolver
     * @see #setDiscriminatorResolver(DiscriminatorResolver)
     */
    private volatile DiscriminatorResolver discriminatorResolver;

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
        this.resolutionCache = new ElementResolutionCache();  // Default to parallel mode
        this.attributes = new ConcurrentHashMap<>();

        // Register default aliases
        resourceRegistry.put("source", sourceResourceSet);
        resourceRegistry.put("target", targetResourceSet);
        // Populate reverse index for O(1) lookup
        resourceSetToAlias.put(sourceResourceSet, "source");
        resourceSetToAlias.put(targetResourceSet, "target");
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
     * Execute a lazy rule immediately for explicit equivalent() calls.
     *
     * <p>This method is used when `equivalent(source, "RuleName")` is called explicitly
     * with a rule name, and the rule is effectively activity-based (e.g., @Greedy @Lazy).
     * In this case, we execute the rule immediately instead of just recording activation
     * for Phase 2. This ensures cross-rule target lookups work within the same pass.</p>
     *
     * <p>For example, when RelationType rule calls `ctx.equivalent(source.getTarget(), CLASS_TYPE)`
     * and ClassType rule is @Greedy @Lazy, this method ensures ClassType is created immediately
     * so RelationType can reference it.</p>
     *
     * @param source the source element
     * @param rule the rule descriptor
     * @return the transformed target, or null if guard fails or rule doesn't apply
     */
    private EObject executeLazyRuleImmediately(EObject source, TransformRuleDescriptor rule) {
        if (rule == null || !rule.appliesTo(source)) {
            return null;
        }

        // Check cache first
        EObject cached = resolutionCache.getByRule(source, rule.getName());
        if (cached != null) {
            return cached;
        }

        // Evaluate guard
        if (!rule.evaluateGuard(source, this)) {
            return null;
        }

        // Use (source, ruleName) key for cache isolation
        RuleCacheKey key = new RuleCacheKey(source, rule.getName());

        // Check if already executing
        EObject existing = executingLazyRules.get(key);
        if (existing != null) {
            return existing;
        }

        // Check for recursion
        NamedRuleKey ruleKey = new NamedRuleKey(source, rule.getName());
        Set<NamedRuleKey> inProgress = inProgressNamedRules.get();
        if (inProgress.contains(ruleKey)) {
            return null;
        }

        // Acquire lock
        ReentrantLock lock = resolutionCache.getLockFor(source, rule.getName());
        boolean lockAcquired;
        try {
            lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!lockAcquired) {
            return null;
        }

        try {
            // Double-check cache
            cached = resolutionCache.getByRule(source, rule.getName());
            if (cached != null) {
                return cached;
            }

            existing = executingLazyRules.get(key);
            if (existing != null) {
                return existing;
            }

            // Execute the rule
            inProgress.add(ruleKey);
            try {
                boolean wasInInheritance = isInInheritanceExecution();
                EObject savedPreCreated = getPreCreatedTarget();
                setInInheritanceExecution(false);
                clearPreCreatedTarget();

                try {
                    EObject result = rule.execute(source, this);
                    if (result != null) {
                        resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                        executingLazyRules.put(key, result);
                    }
                    return result;
                } finally {
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
     * Enable or disable element name prefix in structured IDs.
     *
     * <p>When disabled (default), structured IDs have the format:
     * {@code (alias/sourceId)/RuleName}</p>
     *
     * <p>When enabled, structured IDs have the format:
     * {@code ElementName/(alias/sourceId)/RuleName}</p>
     *
     * @param include true to include element name prefix, false to exclude (default)
     * @throws IllegalStateException if globalIdPrefix is set and include is true
     */
    public void setIncludeElementNameInStructuredIds(boolean include) {
        if (include && globalIdPrefix != null) {
            throw new IllegalStateException(
                "Cannot enable includeElementNameInStructuredIds when globalIdPrefix is set. " +
                "These options are mutually exclusive.");
        }
        this.includeElementNameInStructuredIds = include;
    }

    /**
     * Check if element name prefix is included in structured IDs.
     *
     * @return true if element name prefix is included, false otherwise (default)
     */
    public boolean isIncludeElementNameInStructuredIds() {
        return includeElementNameInStructuredIds;
    }

    /**
     * Set a global prefix for ALL generated structured XMI IDs.
     *
     * <p>When set, all structured IDs have the format:
     * {@code GlobalPrefix/(alias/sourceId)/RuleName}</p>
     *
     * <p>This matches ETL behavior where {@code actorType.name} prefixes all IDs uniformly,
     * as opposed to {@link #setIncludeElementNameInStructuredIds} which uses each element's
     * own name (varying per element).</p>
     *
     * <p>Must be called before any rules execute (during initialization).</p>
     *
     * @param prefix the global prefix to prepend, or null/empty to disable
     * @throws IllegalStateException if includeElementNameInStructuredIds is enabled
     */
    public void setGlobalIdPrefix(String prefix) {
        // Normalize empty string to null
        String normalizedPrefix = (prefix == null || prefix.isEmpty()) ? null : prefix;

        if (normalizedPrefix != null && includeElementNameInStructuredIds) {
            throw new IllegalStateException(
                "Cannot set globalIdPrefix when includeElementNameInStructuredIds is enabled. " +
                "These options are mutually exclusive.");
        }
        this.globalIdPrefix = normalizedPrefix;
    }

    /**
     * Get the global ID prefix.
     *
     * @return the global prefix, or null if not set
     */
    public String getGlobalIdPrefix() {
        return globalIdPrefix;
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
     * Set the cloning strategy for equivalentDiscriminated().
     *
     * @param strategy the strategy to use
     */
    public void setEquivalentDiscriminatedStrategy(EquivalentDiscriminatedStrategy strategy) {
        this.equivalentDiscriminatedStrategy = strategy;
    }

    /**
     * Get the current cloning strategy for equivalentDiscriminated().
     *
     * @return the current strategy
     */
    public EquivalentDiscriminatedStrategy getEquivalentDiscriminatedStrategy() {
        return equivalentDiscriminatedStrategy;
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
     * Configure the cache for sequential (non-parallel) execution.
     *
     * <p>In sequential mode, the cache uses simpler data structures (HashMap, ArrayList)
     * instead of thread-safe collections (ConcurrentHashMap, CopyOnWriteArrayList),
     * and bypasses all locking. This provides significant performance improvement
     * when parallel execution is not needed.</p>
     *
     * <p>This method should be called before transformation begins. If the cache
     * already exists with the requested mode, it is preserved to maintain existing
     * mappings (important when multiple executors share the same context).</p>
     *
     * @param sequential true for sequential mode, false for parallel mode
     */
    public void configureSequentialMode(boolean sequential) {
        // Only create a new cache if the mode is different from current mode
        // This preserves existing mappings when multiple executors share the same context
        if (this.resolutionCache == null || this.resolutionCache.isSequentialMode() != sequential) {
            this.resolutionCache = new ElementResolutionCache(sequential);
        }
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
     * Create a target element with a specific XMI ID.
     *
     * <p>Use this method when you need to set a custom ID for the target element.
     * Setting the ID at creation time prevents race conditions where other rules
     * might read the ID before it's finalized.</p>
     *
     * <p><b>Race Condition Prevention:</b> When using the standard
     * {@link #createTarget(Class)} followed by {@link #setElementId(EObject, String)},
     * there's a window where other rules (via {@link #equivalent} calls) may read
     * the initial auto-generated ID before your custom ID is set. This leads to
     * cache misses and potential duplicate elements.</p>
     *
     * <p><b>Example:</b></p>
     * <pre>
     * // Build custom ID first
     * String customId = "myprefix/" + source.getName() + "/target";
     *
     * // Create target with ID already set - no race condition
     * EPackage target = ctx.createTarget(EPackage.class, customId);
     *
     * // Safe to call other rules - they'll see the correct ID
     * ctx.equivalent(source, OtherRule.class);
     * </pre>
     *
     * @param targetType the type of element to create
     * @param customId the XMI ID to assign, or null for auto-generated structured ID
     * @param <T> the target type
     * @return the created element with ID already set
     * @see #createTarget(Class)
     * @see #setElementId(EObject, String)
     */
    public <T extends EObject> T createTarget(Class<T> targetType, String customId) {
        // Create target with auto-generated ID first
        T instance = createTarget(targetType);

        // If custom ID is provided, override the auto-generated one
        if (customId != null) {
            setElementIdInternal(instance, customId);
        }

        return instance;
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
        long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        TransformationMetrics.recordCreateTarget();

        try {
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

            // FIX for Bug #2: Generate unique IDs for multiple createTarget() calls
            // Track instance count per (source, ruleName, targetType) to avoid collisions
            String targetId = generateUniqueTargetId(source, rule, ruleName, targetType);

            setElementIdInternal(instance, targetId);

            // Track which rule created this element (for external read detection)
            if (rule != null) {
                elementCreatingRule.put(instance, rule);
            }

            // EARLY CACHING for circular discriminated call handling:
            // Cache the target immediately so that circular calls to equivalentDiscriminated()
            // with different discriminators can find and clone this (partial) target.
            // Without this, circular discriminated calls would return null because the
            // recursion check (inProgressRules.contains(key)) fires before the target is cached.
            // This enables patterns like:
            //   1. Rule A (discriminator "d1") starts, creates target
            //   2. Rule A calls Rule B
            //   3. Rule B calls equivalentDiscriminated(..., "d2") for Rule A
            //   4. Instead of returning null, find the in-progress target and clone it
            if (rule != null && source != null && rule.isLazy()) {
                // Use ordinal-based in-progress tracking (O(1) array lookup) when available
                if (rule.hasOrdinal() && transformationRegistry != null) {
                    resolutionCache.markInProgress(source, rule.getOrdinal(), instance,
                            transformationRegistry.getRuleCount());
                }
                // Also update the legacy map for backward compatibility during migration
                RuleCacheKey key = new RuleCacheKey(source, rule.getName());
                executingLazyRules.putIfAbsent(key, instance);
            }

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

            // Wrap with deferred proxy if deferred writes mode is enabled
            // The proxy intercepts setter and list operations, deferring them until commit
            if (deferredWritesEnabled) {
                T proxy = DeferredEObject.createProxy((T) instance, operationQueue);
                // Track the proxy so we can clear pending state after commit
                if (proxy instanceof DeferredEObject.ProxyMarker marker) {
                    createdProxies.add(marker);
                }
                return proxy;
            }

            return (T) instance;
        } finally {
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addCreateTargetNanos(System.nanoTime() - startNanos);
            }
        }
    }

    /**
     * Add an element to the target resource as a root element.
     *
     * <p>Use this for elements that should be root elements in the target model,
     * not contained by other elements.</p>
     *
     * <p>If the element is a deferred proxy, it is unwrapped before being added
     * to the resource to prevent serialization failures.</p>
     *
     * @param element the element to add as root
     */
    public void addToResource(EObject element) {
        if (element == null) {
            return;
        }

        // Skip adding original to resource during discriminated execution.
        // When a lazy rule is called via equivalentDiscriminated(), only the
        // discriminated clone should be added to the resource, not the original.
        // This prevents orphan elements.
        if (inDiscriminatedExecution.get()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Skipping addToResource during discriminated execution for element: {}",
                        element.eClass().getName());
            }
            return;
        }

        // Unwrap proxy before adding to resource to prevent serialization issues
        // DeferredEList cannot be cast to InternalEList during XMI serialization
        EObject unwrapped = DeferredEObject.unwrap(element);

        if (stagingEnabled.get()) {
            // Parallel mode: stage for later commit with ordering
            // The commit phase will check eContainer() == null before adding
            long sequence = creationSequence.getAndIncrement();
            elementOrder.put(unwrapped, sequence);
            stagedElements.offer(new StagedElement(unwrapped, true, sequence));
            stagedElementCount.incrementAndGet(); // Memory barrier for visibility
        } else {
            // Sequential mode: add directly to Resource
            if (!targetResourceSet.getResources().isEmpty()) {
                Resource targetResource = targetResourceSet.getResources().get(0);
                targetResource.getContents().add(unwrapped);

                // Apply pending XMI ID if one was set before adding to resource
                // Check both original element and unwrapped element for pending IDs
                String pendingId = pendingXmiIds.get(element);
                if (pendingId == null) {
                    pendingId = pendingXmiIds.get(unwrapped);
                }
                if (pendingId != null && targetResource instanceof XMIResource) {
                    setSynchronizedXmiId((XMIResource) targetResource, unwrapped, pendingId);
                }
            }
        }
    }


    /**
     * Get the equivalent target for a source element.
     * If not already transformed, triggers lazy transformation if a matching rule exists.
     *
     * <p>When multiple rules produce the same target type, the first matching rule
     * (in registration order) is used. For deterministic behavior with multiple
     * rules, use {@link #equivalent(EObject, String)} with explicit rule name.</p>
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param <T> the target type
     * @return the equivalent target, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T equivalent(EObject source, Class<T> targetType) {
        long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        TransformationMetrics.recordEquivalentCall();

        try {
            // Check cache first - Restored to ensure previously transformed elements are found
            // irrespective of rule visibility (fixes ETLPatternIntegrationTest regression)
            T cached = resolutionCache.getEquivalent(source, targetType);
            if (cached != null) {
                TransformationMetrics.recordEquivalentCacheHit();
                return cached;
            }
            TransformationMetrics.recordEquivalentCacheMiss();

            // Check XMI ID lookup and on-demand execution for ALL rules (eager AND lazy)
            if (transformationRegistry != null) {
                @SuppressWarnings("unchecked")
                Collection<TransformRuleDescriptor> allRules = transformationRegistry.getRulesForSource(
                        (Class<? extends EObject>) source.getClass());

                for (TransformRuleDescriptor rule : allRules) {
                    TransformationMetrics.recordRuleIteration();
                    // Only check rules with compatible target type
                    if (!targetType.isAssignableFrom(rule.getTargetType())) {
                        continue;
                    }
                    // Skip abstract rules (they don't create targets)
                    if (rule.isAbstract()) continue;
                    // Runtime check: appliesTo (EMF type semantics)
                    if (!rule.appliesTo(source)) {
                        continue;
                    }

                    // First try XMI ID lookup (for already-created targets)
                    if (useStructuredIds) {
                        String structuredId = generateStructuredId(source, rule.getName());
                        long xmiIdStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                        T existingByXmiId = findByXmiId(structuredId, targetType);
                        if (TransformationMetrics.isEnabled()) {
                            TransformationMetrics.addFindByXmiIdNanos(System.nanoTime() - xmiIdStartNanos);
                        }
                        TransformationMetrics.recordFindByXmiIdCall();

                        if (existingByXmiId != null) {
                            // Found by XMI ID - cache it and return
                            resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
                            return existingByXmiId;
                        }
                    }

                    // XMI ID lookup failed - target doesn't exist yet
                    // Execute the rule on-demand (works for both lazy AND eager rules)
                    // This is the key fix: eager rules are executed on-demand like lazy rules
                    // when called via equivalent() and the target doesn't exist yet.

                    // Record activation for activity-based rules
                    if (rule.isActivityBased()) {
                        activate(rule.getName(), source);
                    }

                    // Evaluate guard
                    long guardStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                    boolean guardResult = rule.evaluateGuard(source, this);
                    if (TransformationMetrics.isEnabled()) {
                        TransformationMetrics.addGuardEvaluationNanos(System.nanoTime() - guardStartNanos);
                    }
                    TransformationMetrics.recordGuardEvaluation();

                    if (!guardResult) continue;

                    // Use rule name for lock key - each rule executes independently
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
                        return null;
                    }

                    // Acquire per-rule lock for thread-safe execution
                    long lockStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                    ReentrantLock lock = resolutionCache.getLockFor(source, rule.getName());
                    boolean lockAcquired;
                    try {
                        lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for lock on " + key, e);
                    }
                    if (!lockAcquired) {
                        throw new RuntimeException(
                            "Potential deadlock detected: timeout waiting for lock on equivalent(" +
                            source.eClass().getName() + ", " + rule.getName() + "). " +
                            "This may indicate circular rule dependencies.");
                    }
                    if (TransformationMetrics.isEnabled()) {
                        TransformationMetrics.addLockWaitNanos(System.nanoTime() - lockStartNanos);
                    }
                    TransformationMetrics.recordLockAcquisition();

                    try {
                        // Double-check after acquiring lock (another thread may have completed)
                        EObject existingAfterLock = executingLazyRules.get(key);
                        if (existingAfterLock != null) {
                            return (T) existingAfterLock;
                        }

                        // Also double-check resolution cache for this specific rule
                        EObject cachedAgain = resolutionCache.getByRule(source, rule.getName());
                        if (cachedAgain != null) {
                            return (T) cachedAgain;
                        }

                        // Check XMI ID again after lock (target may have been created)
                        if (useStructuredIds) {
                            String structuredId = generateStructuredId(source, rule.getName());
                            T existingByXmiId = findByXmiId(structuredId, targetType);
                            if (existingByXmiId != null) {
                                resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
                                executingLazyRules.put(key, existingByXmiId);
                                return existingByXmiId;
                            }
                        }

                        // Mark as in-progress for recursion detection
                        inProgress.add(key);
                        try {
                            // Save and reset inheritance state for equivalent() calls
                            boolean wasInInheritance = isInInheritanceExecution();
                            EObject savedPreCreated = getPreCreatedTarget();
                            setInInheritanceExecution(false);
                            clearPreCreatedTarget();

                            try {
                                // Execute the rule
                                long ruleStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                                EObject result = rule.execute(source, this);
                                if (TransformationMetrics.isEnabled()) {
                                    TransformationMetrics.addRuleExecutionNanos(rule.getName(), System.nanoTime() - ruleStartNanos);
                                }
                                TransformationMetrics.recordRuleExecution(rule.getName());
                                if (result != null) {
                                    // Store mapping under rule name
                                    resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                                    executingLazyRules.put(key, result);
                                }
                                return (T) result;
                            } finally {
                                // Restore inheritance state
                                setInInheritanceExecution(wasInInheritance);
                                if (savedPreCreated != null) {
                                    setPreCreatedTarget(savedPreCreated);
                                }
                            }
                        } finally {
                            inProgress.remove(key);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } // End of first IF

            // If no matching rules found via the unified path above, fall back to
            // the original lazy rule loop (for backwards compatibility with edge cases)
            // Try to find and execute a matching lazy rule
            // Uses pre-filtered lazy rules index for O(1) lookup
            if (transformationRegistry != null) {
                long getRulesStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                @SuppressWarnings("unchecked")
                List<TransformRuleDescriptor> rules = transformationRegistry.getLazyRulesForType(
                        (Class<? extends EObject>) source.getClass());
                if (TransformationMetrics.isEnabled()) {
                    TransformationMetrics.addGetRulesForSourceNanos(System.nanoTime() - getRulesStartNanos);
                }
                TransformationMetrics.recordGetRulesForSourceCall();

                for (TransformRuleDescriptor rule : rules) {
                    TransformationMetrics.recordRuleIteration();
                    // Pre-filtered: isLazy && !isAbstract
                    // Runtime checks: appliesTo (EMF semantics) and target type compatibility
                    if (!rule.appliesTo(source)) continue;
                    if (targetType.isAssignableFrom(rule.getTargetType())) {
                        // Record activation for activity-based rules
                        // This tracks which elements were referenced via equivalent()
                        if (rule.isActivityBased()) {
                            activate(rule.getName(), source);
                        }

                        long guardStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                        boolean guardResult = rule.evaluateGuard(source, this);
                        if (TransformationMetrics.isEnabled()) {
                            TransformationMetrics.addGuardEvaluationNanos(System.nanoTime() - guardStartNanos);
                        }
                        TransformationMetrics.recordGuardEvaluation();

                        if (guardResult) {
                            // FIX: Use canonical rule name for lock key (same fix as above)
                            String canonicalRuleName = rule.getName();
                            TransformRuleDescriptor primaryRule = transformationRegistry.getPrimaryRuleForTargetType(targetType);
                            if (primaryRule != null && primaryRule.appliesTo(source)) {
                                canonicalRuleName = primaryRule.getName();
                            }

                            // When structured IDs are enabled, try XMI ID-based lookup first (ETL semantics)
                            if (useStructuredIds) {
                                String structuredId = generateStructuredId(source, rule.getName());
                                long xmiIdStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                                T existingByXmiId = findByXmiId(structuredId, targetType);
                                if (TransformationMetrics.isEnabled()) {
                                    TransformationMetrics.addFindByXmiIdNanos(System.nanoTime() - xmiIdStartNanos);
                                }
                                TransformationMetrics.recordFindByXmiIdCall();
                                if (existingByXmiId != null) {
                                    // Found by XMI ID - cache it and return (under both rule names)
                                    resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
                                    if (!canonicalRuleName.equals(rule.getName())) {
                                        resolutionCache.addMapping(source, canonicalRuleName, existingByXmiId, rule.isPrimary());
                                    }
                                    return existingByXmiId;
                                }
                            }

                            // Use (source, canonicalRuleName) key for cross-rule cache isolation
                            RuleCacheKey key = new RuleCacheKey(source, canonicalRuleName);

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
                            // CRITICAL: Use the SAME lock stripe as ElementResolutionCache.getOrCreate()
                            // to prevent race conditions between eager execution and equivalent() calls
                            // IMPORTANT: Use tryLock with timeout to prevent deadlocks from
                            // circular dependencies (e.g., RuleA calls equivalent(B) while
                            // RuleB calls equivalent(A) from different threads)
                            long lockStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                            ReentrantLock lock = resolutionCache.getLockFor(source, canonicalRuleName);
                            boolean lockAcquired;
                            try {
                                // 30 second timeout to detect deadlocks
                                lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted while waiting for lock on " + key, e);
                            }
                            if (!lockAcquired) {
                                throw new RuntimeException(
                                    "Potential deadlock detected: timeout waiting for lock on equivalent(" +
                                    source.eClass().getName() + ", " + rule.getName() + "). " +
                                    "This may indicate circular rule dependencies.");
                            }
                            if (TransformationMetrics.isEnabled()) {
                                TransformationMetrics.addLockWaitNanos(System.nanoTime() - lockStartNanos);
                            }
                            TransformationMetrics.recordLockAcquisition();
                            try {
                                // Double-check after acquiring lock (another thread may have completed)
                                EObject existingAfterLock = executingLazyRules.get(key);
                                if (existingAfterLock != null) {
                                    return (T) existingAfterLock;
                                }

                                // Also double-check resolution cache for this specific rule
                                EObject cachedAgain = resolutionCache.getByRule(source, rule.getName());
                                if (cachedAgain != null) {
                                    return (T) cachedAgain;
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
                                        long ruleStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
                                        EObject result = rule.execute(source, this);
                                        if (TransformationMetrics.isEnabled()) {
                                            TransformationMetrics.addRuleExecutionNanos(rule.getName(), System.nanoTime() - ruleStartNanos);
                                        }
                                        TransformationMetrics.recordRuleExecution(rule.getName());
                                        if (result != null) {
                                            // Cache the result atomically under both rule names
                                            resolutionCache.addMapping(source, rule.getName(), result, rule.isPrimary());
                                            if (!canonicalRuleName.equals(rule.getName())) {
                                                resolutionCache.addMapping(source, canonicalRuleName, result, rule.isPrimary());
                                            }
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
        } finally {
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addEquivalentNanos(System.nanoTime() - startNanos);
            }
        }
    }

    /**
     * Get the equivalent target for a source element using a specific named rule.
     *
     * <p>This combines type-safe lookup with rule name discrimination:
     * <ul>
     *   <li>Finds the rule by name</li>
     *   <li>Validates that the rule's target type is assignable to the expected type</li>
     *   <li>Evaluates guard at invocation time</li>
     *   <li>Caches the result</li>
     * </ul></p>
     *
     * <p>For simpler usage without targetType validation, use {@link #equivalent(EObject, String)}.</p>
     *
     * @param source the source element
     * @param targetType the expected target type
     * @param ruleName the rule name to execute
     * @param <T> the target type
     * @return the equivalent target, or null if rule not found, doesn't apply, guard fails, or target type mismatch
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T equivalent(EObject source, Class<T> targetType, String ruleName) {
        if (ruleName == null || transformationRegistry == null) {
            return null;
        }

        // Check cache first by rule name
        EObject cached = resolutionCache.getByRule(source, ruleName);
        if (cached != null) {
            // Validate target type compatibility
            if (cached != null && targetType.isInstance(cached)) {
                TransformationMetrics.recordEquivalentCacheHit();
                return (T) cached;
            }
            return null;
        }

        // Find the specific rule
        TransformRuleDescriptor rule = transformationRegistry.getRuleByName(ruleName);
        if (rule == null || !rule.appliesTo(source)) {
            return null;
        }

        // Validate target type compatibility
        if (!targetType.isAssignableFrom(rule.getTargetType())) {
            // Rule produces a different target type than expected
            return null;
        }

        // Delegate to the named equivalent method for execution
        return (T) equivalent(source, ruleName);
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
        long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        TransformationMetrics.recordEquivalentsCall();

        try {
            return resolutionCache.getEquivalents(source, targetType);
        } finally {
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addEquivalentsNanos(System.nanoTime() - startNanos);
            }
        }
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
     * <h3>Idempotent Caching Behavior</h3>
     *
     * <p>The result is cached by {@code (source, ruleName)} only. The calling context
     * (which rule is invoking this method) is <strong>intentionally NOT</strong> included
     * in the cache key. This ensures:</p>
     * <ul>
     *   <li>Same (source, ruleName) pair always returns the same target instance</li>
     *   <li>XMI IDs are simple format: {@code (source_id)/RuleName}</li>
     *   <li>Multiple callers receive identical references (correct idempotent behavior)</li>
     * </ul>
     *
     * <p><strong>Note:</strong> ETL includes calling context in its cache key, producing
     * compound XMI IDs and non-idempotent behavior. This is a <strong>bug in ETL</strong>
     * that Zeta intentionally does not replicate.</p>
     *
     * @param source the source element
     * @param ruleName the rule name to execute
     * @param <T> the target type
     * @return the equivalent target, or null if rule not found, doesn't apply, or guard fails
     * @see ElementResolutionCache
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T equivalent(EObject source, String ruleName) {
        long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        TransformationMetrics.recordEquivalentCall();

        try {
            if (ruleName == null || transformationRegistry == null) {
                return null;
            }

            // Check cache first by rule name
            EObject cached = resolutionCache.getByRule(source, ruleName);
            if (cached != null) {
                TransformationMetrics.recordEquivalentCacheHit();
                return (T) cached;
            }
            TransformationMetrics.recordEquivalentCacheMiss();

            // Find the specific rule
            TransformRuleDescriptor rule = transformationRegistry.getRuleByName(ruleName);
            if (rule == null || !rule.appliesTo(source)) {
                return null;
            }

            // Record activation for effectively activity-based rules
            // This tracks which elements were referenced via equivalent()
            // For activity-based rules called implicitly (via Type), we only record activation
            // For activity-based rules called explicitly (via rule name), we execute immediately
            // This ensures cross-rule lookups work during the same pass
            if (isEffectivelyActivityBased(rule)) {
                // For explicit calls with rule name, execute immediately
                // This enables cross-rule target lookups within the same pass
                EObject immediateResult = executeLazyRuleImmediately(source, rule);
                if (immediateResult != null) {
                    TransformationMetrics.recordEquivalentCacheHit();
                    return (T) immediateResult;
                }
                // Fall back to activation for Phase 2
                activate(rule.getName(), source);
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

            // Acquire per-rule lock for thread-safe execution
            ReentrantLock lock = resolutionCache.getLockFor(source, ruleName);
            boolean lockAcquired;
            try {
                lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for lock on " + key, e);
            }
            if (!lockAcquired) {
                throw new RuntimeException(
                    "Potential deadlock detected: timeout waiting for lock on executeLazyRule(" +
                    source.eClass().getName() + ", " + ruleName + "). " +
                    "This may indicate circular rule dependencies.");
            }
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
        } finally {
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addEquivalentNanos(System.nanoTime() - startNanos);
            }
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
        long startNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
        TransformationMetrics.recordEquivalentDiscriminatedCall();

        System.out.println("DEBUG_ENTRY: equivalentDiscriminated called: rule=" + ruleName + " disc=" + discriminator + " strategy=" + equivalentDiscriminatedStrategy);

        // Fail-fast: CLONE_CURRENT_STATE is incompatible with deferred writes (parallel mode)
        if (equivalentDiscriminatedStrategy == EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE
                && deferredWritesEnabled) {
            throw new IllegalStateException(
                    "CLONE_CURRENT_STATE strategy is incompatible with deferred writes (parallel mode). " +
                    "ETL's equivalentDiscriminated depends on mutation ordering which is non-deterministic " +
                    "in parallel mode. Use sequential execution (parallel=false) with CLONE_CURRENT_STATE.");
        }

        // Use local variable for potentially resolved discriminator
        String effectiveDiscriminator = discriminator;
        boolean useDiscriminatorOnlyCache = false;  // Option B.1: flag for cache mode

        // If no explicit discriminator provided, try to resolve via configured resolver
        // This enables context-aware discriminator inference based on call chain (Path C)
        // Enhanced with Option B.1: Resolution now includes cache mode
        if (effectiveDiscriminator == null && discriminatorResolver != null) {
            DiscriminatorResolver.Resolution resolution = discriminatorResolver.resolve(source, ruleName, getRuleInvocationChain());
            if (resolution != null) {
                effectiveDiscriminator = resolution.discriminator();
                useDiscriminatorOnlyCache = resolution.useDiscriminatorOnlyCache();

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Resolved discriminator for {}: {} (cacheMode={})",
                        ruleName, effectiveDiscriminator,
                        useDiscriminatorOnlyCache ? "DISCRIMINATOR_ONLY" : "SOURCE_BASED");
                }
            }
        }

        try {
            // Option B.1: Check discriminator-only cache FIRST when enabled
            // This enables sharing instances across different source objects with same discriminator
            if (useDiscriminatorOnlyCache && effectiveDiscriminator != null) {
                DiscriminatorOnlyCacheKey discOnlyKey = new DiscriminatorOnlyCacheKey(ruleName, effectiveDiscriminator);
                EObject cachedDiscOnly = discriminatorOnlyCache.get(discOnlyKey);
                if (cachedDiscOnly != null && targetType.isInstance(cachedDiscOnly)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("DISCRIMINATOR_ONLY cache HIT: {} discriminator={} -> {}",
                            ruleName, effectiveDiscriminator, System.identityHashCode(cachedDiscOnly));
                    }
                    return (T) cachedDiscOnly;
                }
            }

            // When structured IDs are enabled, use XMI ID-based lookup first (ETL semantics)
            if (useStructuredIds && effectiveDiscriminator != null) {
                String baseId = generateStructuredId(source, ruleName);
                String discriminatedId = generateDiscriminatedId(baseId, effectiveDiscriminator);

                // Look up by XMI ID in target resource
                T existing = findByXmiId(discriminatedId, targetType);
                if (existing != null) {
                    // Cache it for future lookups and return
                    resolutionCache.addDiscriminatedMapping(source, existing, ruleName, effectiveDiscriminator);
                    // Also cache in discriminator-only cache if mode is enabled
                    if (useDiscriminatorOnlyCache) {
                        DiscriminatorOnlyCacheKey discOnlyKey = new DiscriminatorOnlyCacheKey(ruleName, effectiveDiscriminator);
                        discriminatorOnlyCache.put(discOnlyKey, existing);
                    }
                    if ("create".equals(effectiveDiscriminator) && LOG.isDebugEnabled()) {
                        LOG.debug("XMI_LOOKUP found 'create' clone: clone={}, id={}",
                                System.identityHashCode(existing), discriminatedId);
                    }
                    return existing;
                }
            }

            // Check discriminated cache (object-reference based)
            T cached = resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, effectiveDiscriminator);
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
                    // When a named rule is explicitly given, use that rule name for the lock key
                    // (No @Primary normalization - the caller explicitly requested this specific rule)

                    // Check if already in cache by rule name
                    EObject existing = resolutionCache.getByRule(source, ruleName);
                    if (existing != null && targetType.isInstance(existing)) {
                        original = (T) existing;
                    } else {
                        // Use RuleCacheKey for locking to share lock with executeParentRule()
                        RuleCacheKey key = new RuleCacheKey(source, ruleName);

                        // NOTE: We intentionally DON'T check executingLazyRules outside the lock here.
                        // The executingLazyRules map may contain partially-initialized proxies
                        // (from createTarget's early caching for circular dependency handling).
                        // Accessing them before the lazy rule completes setting attributes causes
                        // race conditions where clones have missing attribute values (e.g., null source).
                        // The lock-based path below handles this correctly.

                        // Check for recursion
                        Set<RuleCacheKey> inProgress = inProgressRules.get();
                        if (inProgress.contains(key)) {
                                // Circular call detected. For discriminated calls, we can still
                                // proceed if the original target has been early-cached (via createTarget()).
                                // This enables patterns where Rule A calls Rule B, and Rule B needs
                                // a discriminated variant of Rule A's output.
                                if (effectiveDiscriminator != null) {
                                    // Check if the original was early-cached
                                    // First try ordinal-based lookup (O(1) array access)
                                    EObject earlyCached = null;
                                    if (rule.hasOrdinal()) {
                                        earlyCached = resolutionCache.getInProgress(source, rule.getOrdinal());
                                    }
                                    // Fall back to legacy map if not found via ordinal
                                    if (earlyCached == null) {
                                        earlyCached = executingLazyRules.get(key);
                                    }
                                    if (earlyCached != null && targetType.isInstance(earlyCached)) {
                                        // Use the early-cached target as the original for cloning
                                        // Skip lock acquisition and rule execution - go straight to cloning
                                        original = (T) earlyCached;
                                    } else {
                                        // No early-cached target available - return null
                                        return null;
                                    }
                                } else {
                                    // Non-discriminated circular call - return null to break cycle
                                    return null;
                                }
                            }

                            // Only acquire lock and execute rule if we don't have the original yet
                            if (original == null) {
                                // Acquire per-rule lock for thread-safe execution
                            ReentrantLock lock = resolutionCache.getLockFor(source, ruleName);
                            boolean lockAcquired;
                            try {
                                lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Interrupted while waiting for lock on " + key, e);
                            }
                            if (!lockAcquired) {
                                throw new RuntimeException("Potential deadlock detected: timeout waiting for lock on equivalentDiscriminated");
                            }

                            try {
                                // Double-check after acquiring lock
                                existing = resolutionCache.getByRule(source, ruleName);
                                if (existing != null && targetType.isInstance(existing)) {
                                    original = (T) existing;
                                } else {
                                    EObject existingFromLazyRules = executingLazyRules.get(key);
                                    if (existingFromLazyRules != null && targetType.isInstance(existingFromLazyRules)) {
                                        original = (T) existingFromLazyRules;
                                    } else {
                                        // Mark as in-progress
                                        inProgress.add(key);
                                        try {
                                            // Save and reset inheritance state
                                            boolean wasInInheritance = isInInheritanceExecution();
                                            EObject savedPreCreated = getPreCreatedTarget();
                                            setInInheritanceExecution(false);
                                            clearPreCreatedTarget();

                                            try {
                                                // When discriminator is set, mark this as discriminated execution.
                                                // This causes addToResource() to skip adding the original,
                                                // preventing orphan elements (only the clone should be added).
                                                //
                                                // EXCEPTION: For DISCRIMINATOR_ONLY cache mode, we DON'T set this flag
                                                // because we want the original to be added to Resource directly
                                                // (no cloning - the original becomes THE element with discriminated ID).
                                                // This matches ETL semantics where there's only one element per discriminator.
                                                boolean wasInDiscriminated = inDiscriminatedExecution.get();
                                                if (effectiveDiscriminator != null && !useDiscriminatorOnlyCache
                                                        && equivalentDiscriminatedStrategy != EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE) {
                                                    // Only skip addToResource for source-based cache (clone path)
                                                    // For discriminator-only cache, let addToResource work normally
                                                    // For CLONE_CURRENT_STATE, the first caller gets the original directly,
                                                    // so it must be added to the resource (like discriminator-only mode)
                                                    inDiscriminatedExecution.set(true);
                                                }
                                                try {
                                                    // Execute the specific rule
                                                    EObject result = rule.execute(source, this);
                                                    if (result != null) {
                                                        resolutionCache.addMapping(source, ruleName, result, rule.isPrimary());
                                                        executingLazyRules.put(key, result);
                                                        if (targetType.isInstance(result)) {
                                                            original = (T) result;
                                                        }
                                                    }
                                                } finally {
                                                    // Restore previous discriminated execution state
                                                    inDiscriminatedExecution.set(wasInDiscriminated);
                                                }
                                            } finally {
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
                                    }
                                }
                            } finally {
                                lock.unlock();
                            }
                            }  // end if (original == null) for lock/execute
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
            if (effectiveDiscriminator == null) {
                return original;
            }

            // CLONE_CURRENT_STATE strategy: ETL-compatible first-call-gets-original semantics.
            // The first caller for a given (source, ruleName) gets the original object directly.
            // Subsequent callers get clones of the original's current (possibly mutated) state.
            System.out.println("DEBUG_STRATEGY: strategy=" + equivalentDiscriminatedStrategy + " rule=" + ruleName + " disc=" + effectiveDiscriminator);
            if (equivalentDiscriminatedStrategy == EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE) {
                // Check discriminated cache first (another caller with same discriminator)
                T cachedDisc = resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, effectiveDiscriminator);
                if (cachedDisc != null) {
                    return cachedDisc;
                }

                OriginalTracker.FirstCallResult firstCallResult =
                        originalTracker.checkAndRegisterFirstCall(source, ruleName, effectiveDiscriminator);

                if (firstCallResult.isFirstCall()) {
                    // FIRST CALLER: Return the original directly (no cloning)
                    // Store the base ID before we modify it with the discriminator suffix
                    String baseId;
                    if (useStructuredIds) {
                        baseId = generateStructuredId(source, ruleName);
                    } else {
                        baseId = getElementId(original);
                    }
                    originalTracker.registerBaseId(source, ruleName, baseId);

                    String discriminatedId = generateDiscriminatedId(baseId, effectiveDiscriminator);
                    setElementId(original, discriminatedId);

                    // Add original to resource if not @Detached and not already contained
                    // In CLONE_CURRENT_STATE mode, inDiscriminatedExecution is NOT set, so
                    // addToResource() in the rule may or may not have been called.
                    // We ensure the original is in the resource here (matching ETL behavior).
                    TransformRuleDescriptor rule = transformationRegistry != null
                            ? transformationRegistry.getRuleByName(ruleName) : null;
                    boolean isDetached = rule != null && rule.isDetached();

                    if (!isDetached && original.eContainer() == null && original.eResource() == null) {
                        if (!targetResourceSet.getResources().isEmpty()) {
                            Resource targetResource = targetResourceSet.getResources().get(0);
                            targetResource.getContents().add(original);
                            if (targetResource instanceof XMIResource) {
                                setSynchronizedXmiId((XMIResource) targetResource, original, discriminatedId);
                            }
                        }
                    }

                    // Cache the mapping
                    resolutionCache.addDiscriminatedMapping(source, original, ruleName, effectiveDiscriminator);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("CLONE_CURRENT_STATE: First caller gets original. {} discriminator={} element={}",
                                ruleName, effectiveDiscriminator, System.identityHashCode(original));
                    }

                    return original;
                } else {
                    // SUBSEQUENT CALLER: Clone from CURRENT state of original
                    T clone = (T) EcoreUtil.copy(original);

                    // Track the clone's creating rule (same as original's creating rule)
                    TransformRuleDescriptor originalCreatingRule = elementCreatingRule.get(original);
                    if (originalCreatingRule != null) {
                        elementCreatingRule.put(clone, originalCreatingRule);
                    }

                    // Generate discriminated ID for the clone using stored base ID
                    String baseId = originalTracker.getBaseId(source, ruleName);
                    String discriminatedId = generateDiscriminatedId(baseId, effectiveDiscriminator);
                    setElementId(clone, discriminatedId);

                    // Add clone to resource (check @Detached)
                    TransformRuleDescriptor rule = transformationRegistry != null
                            ? transformationRegistry.getRuleByName(ruleName) : null;
                    boolean isDetached = rule != null && rule.isDetached();

                    if (!isDetached) {
                        if (!targetResourceSet.getResources().isEmpty()) {
                            Resource targetResource = targetResourceSet.getResources().get(0);
                            targetResource.getContents().add(clone);
                            if (targetResource instanceof XMIResource) {
                                setSynchronizedXmiId((XMIResource) targetResource, clone, discriminatedId);
                            }
                        }
                    }

                    // Cache discriminated result
                    resolutionCache.addDiscriminatedMapping(source, clone, ruleName, effectiveDiscriminator);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("CLONE_CURRENT_STATE: Subsequent caller gets clone of current state. {} discriminator={} clone={}",
                                ruleName, effectiveDiscriminator, System.identityHashCode(clone));
                    }

                    return clone;
                }
            }

            // DISCRIMINATOR_ONLY cache mode: Use original directly without cloning.
            // This matches ETL semantics where there's only ONE element per discriminator key,
            // not an "original" + "clone" pair. The original's ID is updated to the discriminated ID.
            // This prevents orphan elements - no unused "original" left behind.
            if (useDiscriminatorOnlyCache) {
                // Generate discriminated ID for the original
                String discriminatedId;
                if (useStructuredIds) {
                    String baseId = generateStructuredId(source, ruleName);
                    discriminatedId = generateDiscriminatedId(baseId, effectiveDiscriminator);
                } else {
                    String baseId = getElementId(original);
                    discriminatedId = baseId + "/(discriminator/" + effectiveDiscriminator + ")";
                }

                // Update the original's ID to the discriminated ID
                // This is safe because the original was just created by the rule - no external
                // rule has read its ID yet (we're still in the same rule execution context)
                setElementId(original, discriminatedId);

                // Cache in discriminator-only cache for future lookups from any source
                DiscriminatorOnlyCacheKey discOnlyKey = new DiscriminatorOnlyCacheKey(ruleName, effectiveDiscriminator);
                discriminatorOnlyCache.put(discOnlyKey, original);

                // Also cache in regular discriminated cache for consistent lookups
                resolutionCache.addDiscriminatedMapping(source, original, ruleName, effectiveDiscriminator);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("DISCRIMINATOR_ONLY mode: Using original directly (no clone). {} discriminator={} element={}",
                        ruleName, effectiveDiscriminator, System.identityHashCode(original));
                }

                return original;
            }

            // Thread-safe clone creation using double-checked locking.
            // This prevents duplicate clones when multiple threads call equivalentDiscriminated()
            // with the same (source, ruleName, discriminator) tuple concurrently.
            //
            // Option B.1: When useDiscriminatorOnlyCache is enabled, use discriminator-only key
            // for both locking and caching to enable sharing across different source objects.
            DiscriminatedCacheKey discKey = new DiscriminatedCacheKey(source, ruleName, effectiveDiscriminator);
            DiscriminatorOnlyCacheKey discOnlyKey = useDiscriminatorOnlyCache
                ? new DiscriminatorOnlyCacheKey(ruleName, effectiveDiscriminator) : null;

            // DEBUG: Track clone creation for "create" discriminator
            boolean isCreateDiscriminator = "create".equals(effectiveDiscriminator);

            // Acquire per-key lock for thread-safe clone creation
            // IMPORTANT: Use tryLock with timeout to prevent deadlocks from
            // circular dependencies (e.g., RuleA clones B while RuleB clones A)
            //
            // Option B.1: Use discriminator-only lock when enabled for proper sharing
            long lockStartNanos = TransformationMetrics.isEnabled() ? System.nanoTime() : 0;
            ReentrantLock lock;
            if (useDiscriminatorOnlyCache) {
                lock = discriminatorOnlyLocks.computeIfAbsent(discOnlyKey, k -> new ReentrantLock());
            } else {
                lock = discriminatedLocks.computeIfAbsent(discKey, k -> new ReentrantLock());
            }
            boolean lockAcquired;
            try {
                // 30 second timeout to detect deadlocks
                lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for discriminated lock on " +
                    (useDiscriminatorOnlyCache ? discOnlyKey : discKey), e);
            }
            if (!lockAcquired) {
                throw new RuntimeException(
                    "Potential deadlock detected: timeout waiting for lock on equivalentDiscriminated(" +
                    source.eClass().getName() + ", " + ruleName + ", discriminator=" + effectiveDiscriminator + "). " +
                    "This may indicate circular rule dependencies.");
            }
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addLockWaitNanos(System.nanoTime() - lockStartNanos);
            }
            TransformationMetrics.recordLockAcquisition();
            try {
                // Double-check after acquiring lock (another thread may have completed)
                // Option B.1: Check discriminator-only cache first when enabled
                if (useDiscriminatorOnlyCache) {
                    EObject cachedDiscOnly = discriminatorOnlyCache.get(discOnlyKey);
                    if (cachedDiscOnly != null && targetType.isInstance(cachedDiscOnly)) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("DISCRIMINATOR_ONLY cache HIT (after lock): {} discriminator={} -> {}",
                                ruleName, effectiveDiscriminator, System.identityHashCode(cachedDiscOnly));
                        }
                        return (T) cachedDiscOnly;
                    }
                }
                T cachedAfterLock = resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, effectiveDiscriminator);
                if (cachedAfterLock != null) {
                    return cachedAfterLock;
                }

                // Clone for discriminated version
                // Unwrap and apply pending values before cloning to ensure proper EMF copy
                // This is necessary because deferred writes store values in the proxy's pendingValues map,
                // and EcoreUtil.copy() reads directly from the delegate which wouldn't have those values applied
                EObject unwrappedOriginal = DeferredEObject.unwrapWithPendingValues(original);
                T clone = (T) EcoreUtil.copy(unwrappedOriginal);

                // Track the clone's creating rule (same as original's creating rule)
                // This enables external read detection for clones - if a different rule reads
                // the clone's ID and then tries to change it via setElementId(), it will fail
                TransformRuleDescriptor originalCreatingRule = elementCreatingRule.get(original);
                if (originalCreatingRule != null) {
                    elementCreatingRule.put(clone, originalCreatingRule);
                }

                // Generate discriminated ID following ETL semantics
                // Format: <source-path>/<rule-name>/(discriminator/<discriminator-value>)
                String discriminatedId;
                if (useStructuredIds) {
                    String baseId = generateStructuredId(source, ruleName);
                    discriminatedId = generateDiscriminatedId(baseId, effectiveDiscriminator);
                } else {
                    // Legacy: append discriminator to whatever ID the original has
                    String baseId = getElementId(original);
                    discriminatedId = baseId + "/(discriminator/" + effectiveDiscriminator + ")";
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
                        boolean staged = stagedElements.offer(new StagedElement(clone, true, sequence));
                        stagedElementCount.incrementAndGet(); // Memory barrier for visibility
                        if (isCreateDiscriminator && LOG.isDebugEnabled()) {
                            LOG.debug("STAGED 'create' clone: staged={}, seq={}, clone={}, id={}",
                                    staged, sequence, System.identityHashCode(clone), discriminatedId);
                        }
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
                resolutionCache.addDiscriminatedMapping(source, clone, ruleName, effectiveDiscriminator);

                // Option B.1: Also store in discriminator-only cache when enabled
                // This enables future lookups from different source objects to find this clone
                if (useDiscriminatorOnlyCache) {
                    discriminatorOnlyCache.put(discOnlyKey, clone);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("DISCRIMINATOR_ONLY cache STORE: {} discriminator={} clone={}",
                            ruleName, effectiveDiscriminator, System.identityHashCode(clone));
                    }
                }

                return clone;
            } finally {
                lock.unlock();
            }
        } finally {
            if (TransformationMetrics.isEnabled()) {
                TransformationMetrics.addEquivalentDiscriminatedNanos(System.nanoTime() - startNanos);
            }
        }
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
     *   <li>Direct lazy rule invocation from helper methods</li>
     * </ul>
     *
     * <p><b>Note on Guards:</b> This method does NOT evaluate the parent rule's guard.
     * For automatic {@code @Extends} inheritance chains, guard evaluation happens in
     * {@code TransformRuleDescriptor.execute()} before the chain executes. For direct
     * invocations via this method, the caller is responsible for checking guards if needed.</p>
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

        // NOTE: Guards are NOT checked here. For @Extends inheritance chains,
        // guard evaluation happens in TransformRuleDescriptor.execute() before
        // executeWithInheritance() is called. This method is for direct rule
        // invocation where the caller controls guard evaluation.

        // Save current inheritance state to restore later
        // ThreadLocal is per-thread, so this is safe to do outside the lock
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
            // FAST PATH: Check resolution cache BEFORE creating key object (avoids allocation on cache hit)
            EObject cached = resolutionCache.getByRule(source, parentRuleName);
            if (cached != null) {
                return (T) cached;
            }

            // When a named rule is explicitly given, use that rule name for the lock key
            RuleCacheKey key = new RuleCacheKey(source, parentRuleName);

            // Check if currently being executed by another thread
            EObject existing = executingLazyRules.get(key);
            if (existing != null) {
                return (T) existing;
            }

            // Acquire per-rule lock for thread-safe execution
            ReentrantLock lock = resolutionCache.getLockFor(source, parentRuleName);
            boolean lockAcquired;
            try {
                lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for lock on " + key, e);
            }
            if (!lockAcquired) {
                throw new RuntimeException(
                    "Potential deadlock detected: timeout waiting for lock on executeParentRule(" +
                    source.eClass().getName() + ", " + parentRuleName + "). " +
                    "This may indicate circular rule dependencies.");
            }
            try {
                // Double-check after acquiring lock (another thread may have completed)
                cached = resolutionCache.getByRule(source, parentRuleName);
                if (cached != null) {
                    return (T) cached;
                }

                // Also check XMI ID (target may have been created by eager rule but not yet cached)
                if (useStructuredIds) {
                    String structuredId = generateStructuredId(source, parentRuleName);
                    T existingByXmiId = findByXmiId(structuredId, (Class<T>) parentRule.getTargetType());
                    if (existingByXmiId != null) {
                        // Found by XMI ID - cache it and return
                        resolutionCache.addMapping(source, parentRuleName, existingByXmiId, parentRule.isPrimary());
                        executingLazyRules.put(key, existingByXmiId);
                        return existingByXmiId;
                    }
                }

                // Execute the rule under the lock
                EObject result = parentRule.execute(source, this);
                if (result != null) {
                    // Cache the result atomically
                    resolutionCache.addMapping(source, parentRuleName, result, parentRule.isPrimary());
                    executingLazyRules.put(key, result);
                }
                return (T) result;
            } finally {
                lock.unlock();
            }
        } finally {
            // Restore previous inheritance state
            // ThreadLocal is per-thread, so restoration is safe even if this thread's supplier wasn't called
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
     * Debug helper: Get the name of the rule that created an element.
     * For testing/debugging only.
     *
     * @param element the element
     * @return the creating rule name, or "null" if not tracked
     */
    public String getElementCreatingRuleDebug(EObject element) {
        TransformRuleDescriptor rule = elementCreatingRule.get(element);
        return rule != null ? rule.getName() : "null";
    }

    /**
     * Debug helper: Get the name of the rule that first read an element's ID externally.
     * For testing/debugging only.
     *
     * @param element the element
     * @return the reading rule name, or "null" if not read externally
     */
    public String getIdReadByExternalRuleDebug(EObject element) {
        TransformRuleDescriptor rule = idReadByExternalRule.get(element);
        return rule != null ? rule.getName() : "null";
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

    // ==================== Rule Invocation Stack (Path C) ====================

    /**
     * Push a rule invocation onto the stack.
     *
     * <p>Called by the rule execution framework when a rule starts execution.
     * The invocation captures the rule name, source element, and optional discriminator.</p>
     *
     * @param ruleName the name of the rule being invoked
     * @param source the source element being transformed
     * @param discriminator the discriminator value (null if not discriminated)
     */
    void pushRuleInvocation(String ruleName, EObject source, String discriminator) {
        ruleInvocationStack.get().push(RuleInvocation.of(ruleName, source, discriminator));
        if (LOG.isTraceEnabled()) {
            LOG.trace("PUSH rule invocation: {} (stack depth={})",
                    ruleName, ruleInvocationStack.get().size());
        }
    }

    /**
     * Pop the most recent rule invocation from the stack.
     *
     * <p>Called by the rule execution framework when a rule completes execution.</p>
     *
     * @return the popped invocation, or null if stack was empty
     */
    RuleInvocation popRuleInvocation() {
        Deque<RuleInvocation> stack = ruleInvocationStack.get();
        if (stack.isEmpty()) {
            LOG.warn("Attempted to pop from empty rule invocation stack");
            return null;
        }
        RuleInvocation invocation = stack.pop();
        if (LOG.isTraceEnabled()) {
            LOG.trace("POP rule invocation: {} (stack depth={})",
                    invocation.ruleName(), stack.size());
        }
        return invocation;
    }

    /**
     * Get the current rule invocation chain as an unmodifiable list.
     *
     * <p>The list is ordered with the most recent invocation first (index 0).
     * This represents the call stack from the current rule back to the root.</p>
     *
     * <p>Example usage for debugging:</p>
     * <pre>{@code
     * List<RuleInvocation> chain = ctx.getRuleInvocationChain();
     * for (int i = 0; i < chain.size(); i++) {
     *     System.out.println("[" + i + "] " + chain.get(i));
     * }
     * }</pre>
     *
     * @return unmodifiable list of rule invocations (most recent first)
     */
    public List<RuleInvocation> getRuleInvocationChain() {
        return Collections.unmodifiableList(new ArrayList<>(ruleInvocationStack.get()));
    }

    /**
     * Get the current stack depth (number of nested rule invocations).
     *
     * @return the stack depth
     */
    public int getRuleInvocationDepth() {
        return ruleInvocationStack.get().size();
    }

    /**
     * Check if currently inside a specific rule (by name).
     *
     * <p>This checks the entire call stack, not just the current rule.
     * Useful for determining if a certain context is present in the call chain.</p>
     *
     * @param ruleName the rule name to search for
     * @return true if the rule is in the current call chain
     */
    public boolean isInRuleContext(String ruleName) {
        for (RuleInvocation inv : ruleInvocationStack.get()) {
            if (inv.ruleName().equals(ruleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find a specific rule invocation in the call chain by name.
     *
     * <p>Returns the first (most recent) matching invocation.</p>
     *
     * @param ruleName the rule name to find
     * @return the matching invocation, or null if not found
     */
    public RuleInvocation findRuleInChain(String ruleName) {
        for (RuleInvocation inv : ruleInvocationStack.get()) {
            if (inv.ruleName().equals(ruleName)) {
                return inv;
            }
        }
        return null;
    }

    /**
     * Get a formatted string representation of the current call chain.
     * Useful for debugging and logging.
     *
     * @return formatted call chain string
     */
    public String formatRuleInvocationChain() {
        Deque<RuleInvocation> stack = ruleInvocationStack.get();
        if (stack.isEmpty()) {
            return "<empty stack>";
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (RuleInvocation inv : stack) {
            if (depth > 0) {
                sb.append("\n");
            }
            sb.append("[").append(depth).append("] ").append(inv);
            depth++;
        }
        return sb.toString();
    }

    // ==================== Discriminator Resolver (Path C) ====================

    /**
     * Set the discriminator resolver for context-aware discriminator inference.
     *
     * <p>When set, {@code equivalentDiscriminated()} with a null discriminator
     * will call this resolver to determine the appropriate discriminator based
     * on the current rule invocation chain.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * ctx.setDiscriminatorResolver((source, ruleName, callChain) -> {
     *     if (ruleName.equals("OperationFormCallActionDefinition")) {
     *         for (RuleInvocation inv : callChain) {
     *             if (inv.ruleName().equals("TransferObjectFormButtonGroup")) {
     *                 TransferObjectForm form = (TransferObjectForm) inv.source();
     *                 return actorType.getName() + "/(esm/" + getId(form) + ")/TransferObjectForm";
     *             }
     *         }
     *     }
     *     return null; // Use default behavior
     * });
     * }</pre>
     *
     * @param resolver the discriminator resolver, or null to disable
     * @see DiscriminatorResolver
     */
    public void setDiscriminatorResolver(DiscriminatorResolver resolver) {
        this.discriminatorResolver = resolver;
    }

    /**
     * Get the current discriminator resolver.
     *
     * @return the resolver, or null if not set
     */
    public DiscriminatorResolver getDiscriminatorResolver() {
        return discriminatorResolver;
    }

    /**
     * Resolve a discriminator using the configured resolver.
     *
     * <p>This method is called internally by {@code equivalentDiscriminated()}
     * when no explicit discriminator is provided.</p>
     *
     * @param source the source element
     * @param ruleName the rule name
     * @return the resolved discriminator, or null if no resolver or resolver returns null
     */
    String resolveDiscriminator(EObject source, String ruleName) {
        if (discriminatorResolver == null) {
            return null;
        }
        List<RuleInvocation> callChain = getRuleInvocationChain();
        String resolved = discriminatorResolver.resolveDiscriminator(source, ruleName, callChain);
        if (resolved != null && LOG.isDebugEnabled()) {
            LOG.debug("Resolved discriminator for {}: {} (chain depth={})",
                    ruleName, resolved, callChain.size());
        }
        return resolved;
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
        // Populate reverse index for O(1) lookup (first alias wins if same ResourceSet registered twice)
        resourceSetToAlias.putIfAbsent(resourceSet, alias);
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
            stagedElementCount.incrementAndGet(); // Memory barrier for visibility
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
     *
     * <p>All staged elements are unwrapped from deferred proxies before being
     * added to the resource to prevent serialization failures.</p>
     */
    void commitStagedElements() {
        if (targetResourceSet.getResources().isEmpty()) {
            return;
        }

        Resource targetResource = targetResourceSet.getResources().get(0);
        XMIResource xmiResource = targetResource instanceof XMIResource
            ? (XMIResource) targetResource : null;

        // MEMORY BARRIER: Reading the counter establishes happens-before with all
        // incrementAndGet() calls from staging threads. This ensures all staged
        // elements are visible before we start draining the queue.
        int expectedCount = stagedElementCount.get();

        // Collect all staged elements
        List<StagedElement> elementsToCommit = new ArrayList<>(expectedCount);
        StagedElement staged;
        while ((staged = stagedElements.poll()) != null) {
            elementsToCommit.add(staged);
        }

        // Sort by creation sequence for deterministic ordering
        elementsToCommit.sort(Comparator.comparingLong(e -> e.sequence));

        // DEBUG: Count "create" clones in staged elements
        int createCloneCount = 0;
        EObject createClone = null;
        for (StagedElement se : elementsToCommit) {
            String pid = pendingXmiIds.get(se.element);
            if (pid != null && pid.contains("/(discriminator/create)")) {
                createCloneCount++;
                createClone = se.element;
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("COMMIT: total staged={}, 'create' clones found in staged={}", elementsToCommit.size(), createCloneCount);
        }

        // Add to resource in order
        for (StagedElement element : elementsToCommit) {
            // Unwrap proxy before adding to resource
            EObject obj = DeferredEObject.unwrap(element.element);

            // Only add root elements that are not yet contained
            if (element.isRootElement && obj.eContainer() == null) {
                targetResource.getContents().add(obj);
            } else if (element.isRootElement) {
                String pid = pendingXmiIds.get(element.element);
                if (pid != null && pid.contains("/(discriminator/create)")) {
                    LOG.warn("COMMIT: 'create' clone has container! obj={}, container={}, id={}",
                            System.identityHashCode(obj), obj.eContainer(), pid);
                }
            }

            // Apply pending XMI ID now that element is in resource
            // Check both original and unwrapped element for pending IDs
            String pendingId = pendingXmiIds.remove(element.element);
            if (pendingId == null) {
                pendingId = pendingXmiIds.remove(obj);
            }
            if (pendingId != null && xmiResource != null) {
                setSynchronizedXmiId(xmiResource, obj, pendingId);
            }

            // Also apply pending IDs to contained elements recursively
            applyPendingIdsRecursively(obj, xmiResource);
        }

        // DEBUG: Verify "create" clone is in resource
        if (createClone != null && LOG.isDebugEnabled()) {
            EObject unwrappedCreate = DeferredEObject.unwrap(createClone);
            boolean inResource = targetResource.getContents().contains(unwrappedCreate);
            String actualId = xmiResource != null ? xmiResource.getID(unwrappedCreate) : null;
            LOG.debug("COMMIT_END: 'create' clone in resource={}, actualId={}, container={}",
                    inResource, actualId, unwrappedCreate.eContainer());
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
        stagedElementCount.set(0);
    }

    /**
     * Remove contained elements from Resource.contents.
     *
     * <p>In sequential mode with autoAddRootElements=true, elements are added to
     * Resource.contents immediately when created via createTarget(). However, when these
     * elements are later added to containment references (e.g., pkg.getEClassifiers().add(cls)),
     * EMF does NOT automatically remove them from Resource.contents.</p>
     *
     * <p>This method performs a cleanup phase to remove any elements from Resource.contents
     * that have been added to containment (i.e., have eContainer != null).</p>
     *
     * <p>This is the sequential mode equivalent of the parallel mode commit phase check:
     * <code>if (element.isRootElement && obj.eContainer() == null)</code></p>
     *
     * @see #commitStagedElements() for parallel mode behavior
     */
    void cleanupContainedRootElements() {
        if (targetResourceSet.getResources().isEmpty()) {
            return;
        }

        Resource targetResource = targetResourceSet.getResources().get(0);

        // Collect elements to remove (avoid ConcurrentModificationException)
        List<EObject> toRemove = new ArrayList<>();
        for (EObject obj : targetResource.getContents()) {
            if (obj.eContainer() != null) {
                toRemove.add(obj);
            }
        }

        // Remove contained elements from root
        targetResource.getContents().removeAll(toRemove);
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
     * Clear pending XMI IDs, reverse index, and external read tracking (called on reset).
     */
    void clearPendingXmiIds() {
        pendingXmiIds.clear();
        pendingXmiIdIndex.clear();
        elementCreatingRule.clear();
        idReadByExternalRule.clear();
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
        for (Map.Entry<EObject, String> entry : pendingXmiIds.entrySet()) {
            EObject element = entry.getKey();
            String pendingId = entry.getValue();

            // Only apply if the element is in this resource and doesn't already have an ID set
            Resource elementResource = element.eResource();
            if (elementResource == targetResource) {
                synchronized (xmiResource) {
                    String existingId = xmiResource.getID(element);
                    if (existingId == null || !existingId.equals(pendingId)) {
                        xmiResource.setID(element, pendingId);
                    }
                }
            } else if (elementResource == null) {
                // Element is not in any resource - trace up containment chain
                // to find if any ancestor is in the target resource
                EObject ancestor = element.eContainer();
                int depth = 0;
                while (ancestor != null && ancestor.eResource() == null && depth < 50) {
                    ancestor = ancestor.eContainer();
                    depth++;
                }

                if (ancestor != null && ancestor.eResource() == targetResource) {
                    // Ancestor is in resource - the element is transitively in resource
                    // Apply the XMI ID (EMF should recognize it via containment)
                    synchronized (xmiResource) {
                        xmiResource.setID(element, pendingId);
                    }
                } else {
                    // Element is truly orphaned - log for debugging
                    String className = element.eClass().getName();
                    if (className.endsWith("ActionDefinition")) {
                        StringBuilder path = new StringBuilder();
                        EObject current = element;
                        int traceDepth = 0;
                        while (current != null && traceDepth < 10) {
                            if (path.length() > 0) path.append(" -> ");
                            path.append(current.eClass().getName());
                            current = current.eContainer();
                            traceDepth++;
                        }
                        if (current == null) path.append(" -> (root)");
                        System.err.println("[ORPHAN] " + pendingId + " path=" + path);
                    }
                }
            }
            // Elements in different resources are skipped
        }
    }

    /**
     * Clear lazy rule execution tracking and per-element locks (called on reset).
     */
    void clearExecutingLazyRules() {
        executingLazyRules.clear();
        ruleLocks.clear();
        discriminatedLocks.clear();
        originalTracker.clear();
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
     * Get the XMI ID of an element, checking pending IDs first.
     *
     * <p>This method resolves element IDs in the following order of precedence:</p>
     * <ol>
     *   <li><b>Pending IDs</b>: Check {@code pendingXmiIds} map first (deferred elements)</li>
     *   <li><b>Resource URI Fragment</b>: Check {@code resource.getURIFragment(element)} for committed elements</li>
     *   <li><b>Structural Feature</b>: Check for "id" structural feature on the element's EClass</li>
     *   <li><b>Generated UUID</b>: Generate and cache a new UUID starting with underscore</li>
     * </ol>
     *
     * <p><b>Use Case:</b> This method should be used when building discriminators or IDs that
     * include target element IDs, as target elements may have deferred IDs that haven't been
     * committed to the XMI resource yet. Using {@code XMIResource.getID()} directly would
     * return {@code null} for such elements.</p>
     *
     * <p><b>Thread Safety:</b> This method is thread-safe. The underlying {@code pendingXmiIds}
     * is a {@code ConcurrentHashMap}, and UUID generation is atomic per element.</p>
     *
     * @param element the element to get the ID for
     * @return the element's XMI ID, never null (generates one if needed when staging is enabled)
     * @see #getPendingXmiId(EObject)
     * @see #setElementId(EObject, String)
     */
    public String getElementId(EObject element) {
        // Track external reads: if a different rule reads this element's ID,
        // mark it as "read externally" so setElementId() will fail later
        TransformRuleDescriptor currentRule = currentExecutingRule.get();
        TransformRuleDescriptor creatingRule = elementCreatingRule.get(element);

        if (currentRule != null && creatingRule != null && currentRule != creatingRule) {
            // Different rule is reading the ID - mark as externally read
            // Using putIfAbsent to track only the FIRST external read
            idReadByExternalRule.putIfAbsent(element, currentRule);
        }

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
            pendingXmiIdIndex.put(generatedId, element);
        }
        return generatedId;
    }

    /**
     * Get the source element's path for structured ID generation.
     *
     * <p>Format depends on configuration (checked in order of precedence):</p>
     * <ul>
     *   <li>When {@link #globalIdPrefix} is set: {@code <global-prefix>/(<alias>/<source-id>)}</li>
     *   <li>When {@link #includeElementNameInStructuredIds} is true: {@code <element-name>/(<alias>/<source-id>)}</li>
     *   <li>Otherwise (default): {@code (<alias>/<source-id>)}</li>
     * </ul>
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

        // Global prefix takes precedence (ETL actorType.name compatibility)
        if (globalIdPrefix != null) {
            return globalIdPrefix + "/(" + alias + "/" + sourceId + ")";
        }

        // Element name prefix if explicitly enabled
        if (includeElementNameInStructuredIds) {
            String elementName = getContainerName(source);
            if (elementName != null && !elementName.isEmpty()) {
                return elementName + "/(" + alias + "/" + sourceId + ")";
            }
        }

        return "(" + alias + "/" + sourceId + ")";
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
        if (xmiId == null) {
            return null;
        }

        // OPTIMIZATION: Check pending XMI ID index FIRST (O(1) ConcurrentHashMap lookup)
        // During transformation with staging, newly created elements are always here.
        // This avoids the more expensive XMIResource.getEObject() call in most cases.
        EObject element = pendingXmiIdIndex.get(xmiId);
        if (element != null && targetType.isInstance(element)) {
            return (T) element;
        }

        // OPTIMIZATION: Skip XMI resource lookup for fresh transformations.
        // For fresh transformations, there are no pre-existing elements in the XMI resource,
        // so XMIResource.getEObject() would always return null - a waste of time.
        // Also skip when staging is enabled (elements not committed yet).
        if (skipXmiIdResourceLookup || stagingEnabled.get()) {
            return null;
        }

        // Fall back to XMI resource lookup for incremental transformations
        if (!targetResourceSet.getResources().isEmpty()) {
            Resource targetResource = targetResourceSet.getResources().get(0);
            if (targetResource instanceof XMIResource) {
                XMIResource xmiResource = (XMIResource) targetResource;
                EObject xmiElement = xmiResource.getEObject(xmiId);
                if (xmiElement != null && targetType.isInstance(xmiElement)) {
                    return (T) xmiElement;
                }
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

        // O(1) lookup via reverse index instead of O(n) registry scan
        String alias = resourceSetToAlias.get(elementResourceSet);
        return alias != null ? alias : "source";
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

        // Check cache first - use combined hash of source identity and ruleName
        if (source != null && ruleName != null) {
            long cacheKey = ((long) System.identityHashCode(source) << 32) | (ruleName.hashCode() & 0xFFFFFFFFL);
            String cached = structuredIdCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            // Generate and cache
            String id = buildStructuredId(source, ruleName);
            structuredIdCache.put(cacheKey, id);
            return id;
        }

        // Fallback for null source or ruleName (no caching)
        return buildStructuredId(source, ruleName);
    }

    /**
     * Build the structured ID string (internal helper for generateStructuredId).
     */
    private String buildStructuredId(EObject source, String ruleName) {
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
     * Generate a unique XMI ID for a target element, handling multiple createTarget() calls
     * within the same rule execution (Bug #2 fix).
     *
     * <p>When a rule creates multiple elements via createTarget(), each element must have a
     * unique ID. This method tracks instance counts and appends distinguishing suffixes only
     * when necessary to maintain backward compatibility.</p>
     *
     * <p>ID format patterns:</p>
     * <ul>
     *   <li>First (and only) element in rule: {@code (source)/RuleName} (unchanged for backward compat)</li>
     *   <li>Second+ element, different type: {@code (source)/RuleName/TypeName}</li>
     *   <li>Second+ element, same type with index: {@code (source)/RuleName/TypeName#1}, etc.</li>
     * </ul>
     *
     * @param source the source element (can be null)
     * @param rule the current executing rule descriptor (can be null)
     * @param ruleName the rule name (can be null)
     * @param targetType the target element type being created
     * @return a unique XMI ID for this target element
     */
    private <T extends EObject> String generateUniqueTargetId(EObject source, TransformRuleDescriptor rule,
            String ruleName, Class<T> targetType) {
        // If no rule context or structured IDs disabled, use basic generation
        if (ruleName == null || !useStructuredIds) {
            return generateStructuredId(source, ruleName);
        }

        String typeName = targetType.getSimpleName();

        // Build keys for tracking: one for all calls in this (source, rule), one per type
        String ruleKey = String.format("%d_%s",
                source != null ? System.identityHashCode(source) : 0,
                ruleName);
        String typeKey = ruleKey + "_" + typeName;

        Map<String, Integer> counters = ruleInstanceCounters.get();

        // Track total calls for this (source, rule) - to know if this is the first call
        int totalCallsForRule = counters.getOrDefault(ruleKey, 0);
        counters.put(ruleKey, totalCallsForRule + 1);

        // Track calls per type for this (source, rule, type) - to know the index
        int typeIndex = counters.getOrDefault(typeKey, 0);
        counters.put(typeKey, typeIndex + 1);

        // Build base ID
        String baseId = generateStructuredId(source, ruleName);

        // First call for this (source, rule): return baseId for backward compatibility
        if (totalCallsForRule == 0) {
            return baseId;
        }

        // Second+ call: append type suffix (and index if multiple of same type)
        // Format: baseId/TypeName or baseId/TypeName#index
        if (typeIndex == 0) {
            return baseId + "/" + typeName;
        } else {
            return baseId + "/" + typeName + "#" + typeIndex;
        }
    }

    /**
     * Clear the rule instance counters. Called when a rule execution completes to reset
     * the counters for the next execution.
     */
    void clearRuleInstanceCounters() {
        ruleInstanceCounters.get().clear();
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

    /**
     * Set the XMI ID for an element.
     *
     * <p>This method allows explicitly setting unique IDs for elements created inline
     * (via multiple createTarget calls within the same rule). This is necessary because
     * generateStructuredId creates the same ID for all elements from the same source/rule.</p>
     *
     * <p><b>ID Immutability After External Read:</b> If another rule has already read this
     * element's ID via {@link #getElementId(EObject)}, this method will throw an
     * {@link IllegalStateException}. This prevents race conditions where one rule builds
     * discriminators using the initial ID, and another rule later changes it.</p>
     *
     * <p><b>Solution:</b> Use {@link #createTarget(Class, String)} to set custom IDs at
     * creation time, before any other rule can read them.</p>
     *
     * @param element the element to set the ID on
     * @param id the XMI ID to set
     * @throws IllegalStateException if the ID was already read by another rule
     * @see #createTarget(Class, String)
     */
    public void setElementId(EObject element, String id) {
        // Check if ID was read by an external rule - if so, throw to prevent race condition
        TransformRuleDescriptor readingRule = idReadByExternalRule.get(element);
        if (readingRule != null) {
            TransformRuleDescriptor creatingRule = elementCreatingRule.get(element);
            String creatingRuleName = creatingRule != null ? creatingRule.getName() : "unknown";
            throw new IllegalStateException(
                    "Cannot change ID of element after it was read by another rule.\n" +
                    "Element type: " + element.eClass().getName() + "\n" +
                    "Element was created by rule: " + creatingRuleName + "\n" +
                    "ID was read by rule: " + readingRule.getName() + "\n" +
                    "Solution: Use createTarget(type, customId) to set ID at creation time.\n" +
                    "Example: ctx.createTarget(" + element.eClass().getName() + ".class, \"" + id + "\")");
        }

        setElementIdInternal(element, id);
    }

    /**
     * Internal method to set XMI ID without checking for external reads.
     *
     * <p>This is used by framework methods (like createTargetInPackage) that set IDs
     * as part of element creation, before any external rule could read them.</p>
     *
     * @param element the element to set the ID on
     * @param id the XMI ID to set
     */
    private void setElementIdInternal(EObject element, String id) {
        // Set "id" structural feature if available
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null && idFeature.isChangeable()) {
            element.eSet(idFeature, id);
        }

        // Remove old index entry if ID is changing to prevent stale entries
        // This is critical for correct findByXmiId() behavior when an element's
        // auto-generated ID is overwritten with a custom ID
        String oldId = pendingXmiIds.get(element);
        if (oldId != null && !oldId.equals(id)) {
            pendingXmiIdIndex.remove(oldId);
        }

        // COLLISION DETECTION: Check if this ID is already assigned to a DIFFERENT element
        // This is a critical diagnostic for type mismatch bugs where two elements get the same ID
        EObject existingElement = pendingXmiIdIndex.get(id);
        if (existingElement != null && existingElement != element) {
            // ID COLLISION DETECTED - This is the root cause of type mismatch bugs!
            String existingType = existingElement.eClass().getName();
            String newType = element.eClass().getName();
            String existingObjId = Integer.toHexString(System.identityHashCode(existingElement));
            String newObjId = Integer.toHexString(System.identityHashCode(element));
            LOG.error("XMI ID COLLISION DETECTED: ID '{}' is being reassigned from {} (obj@{}) to {} (obj@{}). " +
                    "This will cause type mismatch in findByXmiId()!",
                    id, existingType, existingObjId, newType, newObjId);
            // Log stack trace to identify the culprit code
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stack trace for ID collision:", new Exception("ID collision stack trace"));
            }
        }

        // Always store in pendingXmiIds for later retrieval/application
        // Also update reverse index for O(1) lookup by XMI ID
        pendingXmiIds.put(element, id);
        pendingXmiIdIndex.put(id, element);

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

    // ==================== Deferred Writes API ====================

    /**
     * Enable deferred EMF writes mode.
     *
     * <p>When enabled, {@code createTarget()} returns proxied EObjects that
     * intercept setter and list modification calls. Operations are recorded
     * and replayed during the commit phase.</p>
     *
     * <p>This provides complete thread isolation during parallel transformation,
     * eliminating race conditions without requiring locks on EMF objects.</p>
     *
     * @see #disableDeferredWrites()
     * @see #commitDeferredOperations()
     */
    public void enableDeferredWrites() {
        this.deferredWritesEnabled = true;
        this.operationQueue.enableDeferredMode();
    }

    /**
     * Disable deferred EMF writes mode.
     *
     * <p>After disabling, EMF operations are applied immediately as normal.</p>
     */
    public void disableDeferredWrites() {
        this.deferredWritesEnabled = false;
        this.operationQueue.disableDeferredMode();
    }

    /**
     * Check if deferred writes mode is enabled.
     *
     * @return true if deferred writes are enabled
     */
    public boolean isDeferredWritesEnabled() {
        return deferredWritesEnabled;
    }

    /**
     * Get the operation queue for deferred EMF operations.
     *
     * @return the operation queue
     */
    public OperationQueue getOperationQueue() {
        return operationQueue;
    }

    /**
     * Commit all deferred EMF operations.
     *
     * <p>Operations are sorted by sequence number and applied in order.
     * This method should be called after the parallel transformation phase
     * completes, from a single thread.</p>
     *
     * @return the number of operations applied
     */
    public int commitDeferredOperations() {
        int committed = operationQueue.commit();

        // Clear pending state on all proxies after commit
        // This prevents double-counting: without clearing, DeferredEList.getCombinedView()
        // would return both delegate elements (committed) AND pendingAdditions (stale)
        for (DeferredEObject.ProxyMarker proxy : createdProxies) {
            proxy.clearPendingState();
        }

        return committed;
    }

    /**
     * Clear all deferred operations without applying them.
     *
     * <p>Use this for rollback or cleanup on error.</p>
     */
    public void clearDeferredOperations() {
        operationQueue.clear();
    }

    /**
     * Reset deferred writes state.
     *
     * <p>Clears the operation queue and disables deferred mode.</p>
     */
    public void resetDeferredWrites() {
        operationQueue.reset();
        deferredWritesEnabled = false;
        createdProxies.clear();
    }

    /**
     * Get the number of pending deferred operations.
     *
     * @return the count of pending operations
     */
    public int getPendingOperationCount() {
        return operationQueue.size();
    }

    /**
     * Unwrap a potentially proxied EObject to get the delegate.
     *
     * <p>When using deferred writes, EObjects returned from {@code createTarget()}
     * are proxies. This method returns the underlying delegate object.</p>
     *
     * @param object the object (may be a proxy)
     * @param <T> the type
     * @return the unwrapped object
     */
    public <T> T unwrapProxy(T object) {
        return DeferredEObject.unwrap(object);
    }

    /**
     * Unwrap all proxy references in the target model.
     *
     * <p>This method traverses all elements in the target resource and replaces
     * any proxy references with their unwrapped real objects. This is a cleanup
     * step that should be called after parallel transformation completes to ensure
     * the model contains no proxy objects that would cause serialization failures.</p>
     *
     * <p>Reference values set via deferred writes may contain proxy objects that
     * weren't unwrapped during the commit phase. This method scans all EReference
     * values and replaces proxies with their delegates.</p>
     *
     * @return the number of proxy references that were unwrapped
     */
    @SuppressWarnings("unchecked")
    public int unwrapAllProxiesInModel() {
        int unwrappedCount = 0;

        // Phase 1: Unwrap all proxies in the resolution cache
        // This is critical because equivalent() returns cached values, and if those
        // are proxies, they could end up in containment references after transformation
        unwrappedCount += resolutionCache.unwrapAllProxies();

        // Phase 2: Unwrap proxies in the target resource
        if (!targetResourceSet.getResources().isEmpty()) {
            Resource targetResource = targetResourceSet.getResources().get(0);

            // Traverse all elements in the resource
            for (EObject root : new ArrayList<>(targetResource.getContents())) {
                unwrappedCount += unwrapProxiesRecursively(root);
            }
        }

        return unwrappedCount;
    }

    /**
     * Recursively unwrap proxy references in an element and its contents.
     *
     * @param element the element to process
     * @return the number of proxy references unwrapped
     */
    @SuppressWarnings("unchecked")
    private int unwrapProxiesRecursively(EObject element) {
        int count = 0;

        // Check all EReference features
        for (EStructuralFeature feature : element.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof org.eclipse.emf.ecore.EReference)) {
                continue;
            }

            Object value = element.eGet(feature);
            if (value == null) {
                continue;
            }

            if (feature.isMany()) {
                // Multi-valued reference - check each element
                org.eclipse.emf.common.util.EList<EObject> list =
                        (org.eclipse.emf.common.util.EList<EObject>) value;
                for (int i = 0; i < list.size(); i++) {
                    EObject refValue = list.get(i);
                    if (refValue instanceof DeferredEObject.ProxyMarker) {
                        EObject unwrapped = DeferredEObject.unwrap(refValue);
                        list.set(i, unwrapped);
                        count++;
                    }
                }
            } else {
                // Single-valued reference
                if (value instanceof DeferredEObject.ProxyMarker) {
                    EObject unwrapped = DeferredEObject.unwrap((EObject) value);
                    element.eSet(feature, unwrapped);
                    count++;
                }
            }
        }

        // Process contained elements recursively
        for (EObject child : element.eContents()) {
            count += unwrapProxiesRecursively(child);
        }

        return count;
    }
}
