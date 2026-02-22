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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata descriptor for a transformation rule.
 *
 * <p>Holds information about the rule annotation, guard method,
 * dependencies, and provides rule invocation capability.</p>
 */
public class TransformRuleDescriptor {

    private final Object instance;
    private final Method ruleMethod;
    private final String name;
    private final String description;
    private final Class<? extends EObject> sourceType;
    private final Class<? extends EObject> targetType;
    private final Method guardMethod;
    private final boolean isLazy;
    private final boolean isAbstract;
    private final boolean isPrimary;
    private final boolean isGreedy;
    private final boolean isDetached;
    private final boolean isActivityBased;
    private final List<String> extendsRules;

    /**
     * List of source type definitions from @Transform annotations.
     */
    private final List<TransformDefinition> transforms;

    /**
     * List of target type definitions from @To annotations.
     */
    private final List<ToDefinition> tos;

    private TransformFunction<EObject, EObject> cachedFunction;
    private MultiSourceTransformFunction<EObject> cachedMultiSourceFunction;
    private TransformGuard cachedGuard;
    private MultiSourceTransformGuard cachedMultiSourceGuard;
    private Boolean isMultiSourceGuard;

    /**
     * Pre-computed flag indicating if this rule is eligible for eager execution.
     * True if: !isLazy && !isMultiSource && !isAbstract
     * Computed once at construction time, not per-element.
     */
    private final boolean isEagerExecutable;

    /**
     * Per-rule cache of source elements rejected by this rule's guard.
     *
     * <p>ETL-compatible behavior: Each rule maintains its own rejected set.
     * When a guard returns false for a source element, it's added to this set.
     * Subsequent guard evaluations for the same source return false immediately
     * without re-evaluating the guard.</p>
     *
     * <p>Thread-safe for parallel transformations.</p>
     */
    private final Set<EObject> rejected = ConcurrentHashMap.newKeySet();

    /**
     * Unique integer ordinal assigned at registration time.
     *
     * <p>Used for O(1) array-indexed cache lookups instead of String-based map lookups.
     * The ordinal is assigned sequentially by TransformationRegistry.register() and
     * is immutable once set (can only be set once, from -1 to a non-negative value).</p>
     *
     * <p>Value of -1 indicates the ordinal has not been assigned yet.</p>
     */
    private int ordinal = -1;

    public TransformRuleDescriptor(
            Object instance,
            Method ruleMethod,
            String name,
            String description,
            Class<? extends EObject> sourceType,
            Class<? extends EObject> targetType,
            Method guardMethod,
            boolean isLazy,
            boolean isAbstract,
            boolean isPrimary,
            boolean isGreedy,
            List<String> extendsRules
    ) {
        this(instance, ruleMethod, name, description, sourceType, targetType, guardMethod,
                isLazy, isAbstract, isPrimary, isGreedy, false, false, extendsRules,
                Collections.emptyList(), Collections.emptyList());
    }

    public TransformRuleDescriptor(
            Object instance,
            Method ruleMethod,
            String name,
            String description,
            Class<? extends EObject> sourceType,
            Class<? extends EObject> targetType,
            Method guardMethod,
            boolean isLazy,
            boolean isAbstract,
            boolean isPrimary,
            boolean isGreedy,
            boolean isDetached,
            List<String> extendsRules
    ) {
        this(instance, ruleMethod, name, description, sourceType, targetType, guardMethod,
                isLazy, isAbstract, isPrimary, isGreedy, isDetached, false, extendsRules,
                Collections.emptyList(), Collections.emptyList());
    }

    public TransformRuleDescriptor(
            Object instance,
            Method ruleMethod,
            String name,
            String description,
            Class<? extends EObject> sourceType,
            Class<? extends EObject> targetType,
            Method guardMethod,
            boolean isLazy,
            boolean isAbstract,
            boolean isPrimary,
            boolean isGreedy,
            boolean isDetached,
            boolean isActivityBased,
            List<String> extendsRules,
            List<TransformDefinition> transforms,
            List<ToDefinition> tos
    ) {
        this.instance = instance;
        this.ruleMethod = ruleMethod;
        this.name = name;
        this.description = description;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.guardMethod = guardMethod;
        this.isLazy = isLazy;
        this.isAbstract = isAbstract;
        this.isPrimary = isPrimary;
        this.isGreedy = isGreedy;
        this.isDetached = isDetached;
        this.isActivityBased = isActivityBased;
        this.extendsRules = extendsRules;
        this.transforms = transforms != null ? transforms : Collections.emptyList();
        this.tos = tos != null ? tos : Collections.emptyList();

        // Pre-compute eager executable flag (avoids per-element checks)
        // A rule is eager-executable if it's not lazy, not multi-source, and not abstract
        this.isEagerExecutable = !isLazy && this.transforms.size() <= 1 && !isAbstract;

        ruleMethod.setAccessible(true);
        if (guardMethod != null) {
            guardMethod.setAccessible(true);
        }
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Class<? extends EObject> getSourceType() {
        return sourceType;
    }

    public Class<? extends EObject> getTargetType() {
        return targetType;
    }

    public boolean isLazy() {
        return isLazy;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public boolean isGreedy() {
        return isGreedy;
    }

    /**
     * Check if this rule is marked as detached.
     *
     * <p>Detached rules create objects that are NOT added to Resource.contents.
     * The caller is responsible for adding the object to its proper container.</p>
     *
     * @return true if the rule is marked with @Detached
     */
    public boolean isDetached() {
        return isDetached;
    }

    /**
     * Check if this rule uses activity-based processing.
     *
     * <p>Activity-based rules only process elements that are "activated" via
     * {@code equivalent()} calls. Elements never referenced are not processed.</p>
     *
     * <p><b>Note:</b> This annotation only has effect when used with both
     * {@code @Greedy} and {@code @Lazy}.</p>
     *
     * @return true if the rule is marked with @ActivityBased
     */
    public boolean isActivityBased() {
        return isActivityBased;
    }

    /**
     * Check if this rule is eligible for eager (Phase 1) execution.
     *
     * <p>Pre-computed at construction time. A rule is eager-executable if:
     * <ul>
     *   <li>Not lazy (lazy rules execute on-demand via equivalent())</li>
     *   <li>Not multi-source (multi-source rules use Cartesian product)</li>
     *   <li>Not abstract (abstract rules only execute via executeParentRule())</li>
     * </ul></p>
     *
     * <p>Using this pre-computed flag avoids per-element runtime checks.</p>
     *
     * @return true if this rule can execute in Phase 1 (eager execution)
     */
    public boolean isEagerExecutable() {
        return isEagerExecutable;
    }

    /**
     * Check if this source element was previously rejected by this rule's guard.
     *
     * <p>ETL-compatible behavior: Each rule maintains its own rejected set.
     * This allows different rules to independently evaluate the same source element.</p>
     *
     * @param source the source element to check
     * @return true if the source was previously rejected by this rule's guard
     */
    public boolean wasRejected(EObject source) {
        return rejected.contains(source);
    }

    /**
     * Record that this source element was rejected by this rule's guard.
     *
     * <p>Called when the guard evaluates to false. Subsequent guard evaluations
     * for the same source will return false immediately without re-evaluating.</p>
     *
     * @param source the rejected source element
     */
    public void recordRejection(EObject source) {
        rejected.add(source);
    }

    /**
     * Clear all rejected elements from this rule's cache.
     *
     * <p>Called during executor reset to ensure fresh state for reused executors.</p>
     */
    public void clearRejected() {
        rejected.clear();
    }

    /**
     * Get the rule's ordinal for O(1) array-indexed cache lookups.
     *
     * @return the ordinal (0 or greater if assigned, -1 if not yet assigned)
     */
    public int getOrdinal() {
        return ordinal;
    }

    /**
     * Set the rule's ordinal. Can only be set once (from -1 to a non-negative value).
     *
     * <p>This is called by TransformationRegistry during rule registration.
     * The ordinal is immutable once set to prevent confusion from re-assignment.</p>
     *
     * @param ordinal the ordinal to assign (must be >= 0)
     * @throws IllegalStateException if the ordinal was already set
     * @throws IllegalArgumentException if ordinal is negative
     */
    public void setOrdinal(int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must be non-negative: " + ordinal);
        }
        if (this.ordinal != -1) {
            throw new IllegalStateException(
                    "Ordinal already set for rule '" + name + "': " + this.ordinal +
                    ". Cannot re-assign to: " + ordinal);
        }
        this.ordinal = ordinal;
    }

    /**
     * Check if this rule has been assigned an ordinal.
     *
     * @return true if ordinal has been assigned (>= 0)
     */
    public boolean hasOrdinal() {
        return ordinal >= 0;
    }

    public List<String> getExtendsRules() {
        return extendsRules;
    }

    /**
     * Get the list of source type definitions from @Transform annotations.
     *
     * @return list of TransformDefinition (may be empty for backward compatibility)
     */
    public List<TransformDefinition> getTransforms() {
        return transforms;
    }

    /**
     * Get the list of target type definitions from @To annotations.
     *
     * @return list of ToDefinition (may be empty for backward compatibility)
     */
    public List<ToDefinition> getTos() {
        return tos;
    }

    /**
     * Check if this rule uses multiple source types (Cartesian product).
     *
     * <p>A rule is multi-source when it has more than one @Transform annotation,
     * requiring the executor to generate a Cartesian product of elements from
     * each source alias.</p>
     *
     * @return true if the rule has multiple @Transform annotations
     */
    public boolean isMultiSource() {
        return transforms.size() > 1;
    }

    /**
     * Get the number of source types for this rule.
     *
     * <p>For single-source rules (backward compatible), returns 1.
     * For multi-source rules, returns the number of @Transform annotations.</p>
     *
     * @return the number of source types
     */
    public int getSourceCount() {
        return transforms.isEmpty() ? 1 : transforms.size();
    }

    /**
     * Check if this rule applies to the given source element.
     *
     * <p>ETL semantics (Epsilon ETL documentation):</p>
     * <ul>
     *   <li><b>Non-greedy, non-lazy (default)</b>: Rule matches elements where the element's type
     *       has a <b>type-of relationship</b> with the declared sourceType. This means the
     *       element's type must be exactly the declared source type (not a subtype).</li>
     *   <li><b>Greedy (@Greedy)</b>: Rule matches elements where the element's type has a
     *       <b>kind-of relationship</b> with the declared sourceType. This means the
     *       element's type is the source type OR any subtype. For example, GreedyRule(A) matches B
     *       if B extends A.</li>
     *   <li><b>Lazy (@Lazy)</b>: Uses same matching semantics as non-greedy (type-of).
     *       Lazy rules are triggered explicitly via equivalent() calls.</li>
     * </ul>
     *
     * @param source the source element to check
     * @return true if this rule should transform the source element
     */
    public boolean appliesTo(EObject source) {
        if (isGreedy) {
            // Greedy: Kind-of semantics - matches sourceType and all subtypes
            return sourceType.isInstance(source);
        } else {
            // Non-greedy, Lazy: Type-of semantics - matches ONLY the exact declared type
            // For EMF interfaces (like EClass), use EMF type name matching
            // For concrete classes, use exact Java class match
            if (sourceType.isInterface()) {
                // Match based on EMF EClass name, not Java class
                String expectedTypeName = sourceType.getSimpleName();
                String actualTypeName = source.eClass().getName();
                return expectedTypeName.equals(actualTypeName);
            } else {
                return sourceType.equals(source.getClass());
            }
        }
    }

    /**
     * Evaluate the guard for this rule (single-source).
     *
     * <p>ETL-compatible rejection caching: If this source was previously rejected
     * by this rule's guard, returns false immediately without re-evaluating.
     * If the guard fails, the source is recorded as rejected for future calls.</p>
     *
     * @param source the source element
     * @param context the transformation context
     * @return true if the guard passes (or no guard), false otherwise
     */
    public boolean evaluateGuard(EObject source, TransformationContext context) {
        // ETL-compatible: Check rejection cache first
        if (rejected.contains(source)) {
            return false;
        }

        TransformGuard guard = getGuard();
        if (guard == null) {
            return true;
        }

        boolean result = guard.evaluate(source, context);
        if (!result) {
            // Record rejection for future calls (ETL-compatible caching)
            rejected.add(source);
        }
        return result;
    }

    /**
     * Evaluate the guard for this rule (multi-source).
     *
     * <p>For multi-source rules with Cartesian product, passes all source elements
     * to the guard for evaluation. The guard can decide based on the combination
     * of all sources.</p>
     *
     * <p>ETL-compatible rejection caching uses the first source element as the cache key,
     * matching the single-source fallback behavior.</p>
     *
     * @param sources array of source elements from Cartesian product tuple
     * @param context the transformation context
     * @return true if the guard passes (or no guard), false otherwise
     */
    public boolean evaluateGuard(EObject[] sources, TransformationContext context) {
        if (guardMethod == null) {
            return true;
        }

        // Check if guard supports multi-source signature
        if (isMultiSourceGuard()) {
            // Multi-source guards: NO caching because guard decision depends on
            // the combination of all sources, not just the first element.
            // Same source[0] can pass with one combination and fail with another.
            MultiSourceTransformGuard guard = getMultiSourceGuard();
            return guard.evaluate(sources, context);
        } else {
            // Fall back to single-source guard with first element
            // ETL-compatible: rejection caching applies only to single-source guards
            EObject source = sources[0];

            // Check rejection cache first
            if (rejected.contains(source)) {
                return false;
            }

            TransformGuard guard = getGuard();
            boolean result = guard == null || guard.evaluate(source, context);

            if (!result) {
                // Record rejection for future calls
                rejected.add(source);
            }
            return result;
        }
    }

    /**
     * Check if the guard method supports multi-source signature.
     *
     * <p>Detects guard signature by checking parameter types:
     * <ul>
     *   <li>{@code (EObject[], TransformationContext)} → multi-source</li>
     *   <li>{@code (EObject, TransformationContext)} → single-source</li>
     * </ul></p>
     *
     * @return true if the guard accepts EObject[] as first parameter
     */
    private boolean isMultiSourceGuard() {
        if (isMultiSourceGuard == null) {
            if (guardMethod == null) {
                isMultiSourceGuard = false;
            } else {
                Class<?>[] paramTypes = guardMethod.getParameterTypes();
                isMultiSourceGuard = paramTypes.length >= 1 && paramTypes[0].isArray();
            }
        }
        return isMultiSourceGuard;
    }

    /**
     * Get the transform function for single-source rules (lazily initialized).
     *
     * @throws IllegalStateException if called on a multi-source rule
     */
    @SuppressWarnings("unchecked")
    public TransformFunction<EObject, EObject> getFunction() {
        if (isMultiSource()) {
            throw new IllegalStateException(
                    "Cannot get single-source function for multi-source rule: " + name + 
                    ". Use getMultiSourceFunction() instead.");
        }
        if (cachedFunction == null) {
            try {
                cachedFunction = (TransformFunction<EObject, EObject>) ruleMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get transform function: " + name, e);
            }
        }
        return cachedFunction;
    }

    /**
     * Get the transform function for multi-source rules (lazily initialized).
     *
     * <p>Used when the rule has multiple @Transform annotations and receives
     * an array of source elements from the Cartesian product.</p>
     *
     * @return the multi-source transform function
     * @throws IllegalStateException if called on a single-source rule
     */
    @SuppressWarnings("unchecked")
    public MultiSourceTransformFunction<EObject> getMultiSourceFunction() {
        if (!isMultiSource()) {
            throw new IllegalStateException(
                    "Cannot get multi-source function for single-source rule: " + name + 
                    ". Use getFunction() instead.");
        }
        if (cachedMultiSourceFunction == null) {
            try {
                cachedMultiSourceFunction = (MultiSourceTransformFunction<EObject>) ruleMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get multi-source transform function: " + name, e);
            }
        }
        return cachedMultiSourceFunction;
    }

    /**
     * Get the guard for single-source rules (lazily initialized).
     */
    public TransformGuard getGuard() {
        if (guardMethod == null) {
            return null;
        }

        if (cachedGuard == null) {
            cachedGuard = (source, ctx) -> {
                try {
                    return (Boolean) guardMethod.invoke(instance, source, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to evaluate guard for: " + name, e);
                }
            };
        }

        return cachedGuard;
    }

    /**
     * Get the guard for multi-source rules (lazily initialized).
     *
     * <p>Used when the guard method signature accepts {@code EObject[]} as the first parameter.</p>
     *
     * @return the multi-source guard, or null if no guard method
     */
    public MultiSourceTransformGuard getMultiSourceGuard() {
        if (guardMethod == null) {
            return null;
        }

        if (cachedMultiSourceGuard == null) {
            cachedMultiSourceGuard = (sources, ctx) -> {
                try {
                    return (Boolean) guardMethod.invoke(instance, sources, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to evaluate multi-source guard for: " + name, e);
                }
            };
        }

        return cachedMultiSourceGuard;
    }

    /**
     * Execute this rule on a single source element.
     *
     * <p><b>@Extends inheritance (ETL semantics):</b> When this rule has @Extends annotations,
     * the framework automatically:</p>
     * <ol>
     *   <li>Pre-creates a target instance of this rule's target type</li>
     *   <li>Executes all parent rules first (in declaration order), passing the pre-created target</li>
     *   <li>Executes this rule's transform function</li>
     * </ol>
     *
     * <p>Parent rules receive the pre-created target via {@code ctx.createTarget()}, which returns
     * the existing instance instead of creating a new one. This enables code reuse through
     * inheritance - parent rules initialize common properties, child rules add specifics.</p>
     *
     * @param source the source element
     * @param context the transformation context
     * @return the target element
     * @throws IllegalStateException if called on a multi-source rule
     */
    public EObject execute(EObject source, TransformationContext context) {
        // Track current executing rule for @Detached support
        TransformRuleDescriptor previousRule = context.getCurrentExecutingRule();
        context.setCurrentExecutingRule(this);

        // CRITICAL: Save and update currentSource for proper ID generation
        // When equivalent() triggers a lazy rule, currentSource must point to the
        // actual source being transformed (not the caller's source).
        // This ensures XMI IDs are generated from the correct source element.
        EObject previousSource = context.getCurrentSource();
        context.setCurrentSource(source);

        // Push rule invocation onto the stack for call chain tracking (Path C)
        // This enables context-aware discriminator resolution and debugging
        context.pushRuleInvocation(name, source, null);

        // CRITICAL: Save inheritance state for nested transformation support
        // When a transform function triggers another transformation (via equivalent()),
        // the nested transformation should start FRESH, not inherit the outer's inheritance
        // context. Without this reset, executeWithInheritance() would see inInheritanceExecution=true
        // and assume it's being called as a parent rule, skipping target creation and reusing
        // the outer transformation's preCreatedTarget - causing target sharing bugs.
        //
        // HOWEVER: We must NOT reset if we're being called as a parent rule in an @Extends chain.
        // Parent rules SHOULD share the target with the child rule that set up the inheritance context.
        boolean previousInInheritance = context.isInInheritanceExecution();
        EObject previousPreCreatedTarget = context.getPreCreatedTarget();

        // Only reset inheritance state if we're NOT in inheritance execution mode.
        // If we ARE in inheritance mode, we're being called as a parent rule and should
        // use the pre-created target from the child rule.
        boolean shouldResetInheritance = !previousInInheritance;
        if (shouldResetInheritance) {
            context.setInInheritanceExecution(false);
            context.clearPreCreatedTarget();
        }

        try {
            // Check if this rule extends parent rules
            if (!extendsRules.isEmpty() && context.getTransformationRegistry() != null) {
                // ETL Semantics: ALL parent guards must pass before child executes
                // "the element must also satisfy the guard of the rule (and all the rules it extends)"
                TransformationRegistry registry = context.getTransformationRegistry();
                for (String parentRuleName : extendsRules) {
                    TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
                    if (parentRule != null && !parentRule.evaluateGuard(source, context)) {
                        return null;  // Parent guard rejected - abort entire chain
                    }
                }
                return executeWithInheritance(source, context);
            }

            // No inheritance - execute normally
            return getFunction().transform(source, context);
        } finally {
            // Pop rule invocation from stack (Path C)
            context.popRuleInvocation();

            // Restore inheritance state (for nested rule execution)
            context.setInInheritanceExecution(previousInInheritance);
            if (previousPreCreatedTarget != null) {
                context.setPreCreatedTarget(previousPreCreatedTarget);
            } else {
                context.clearPreCreatedTarget();
            }

            // Restore previous source (for nested rule execution)
            if (previousSource != null) {
                context.setCurrentSource(previousSource);
            } else {
                context.clearCurrentSource();
            }

            // Restore previous rule (for nested rule execution)
            if (previousRule != null) {
                context.setCurrentExecutingRule(previousRule);
            } else {
                context.clearCurrentExecutingRule();
            }
        }
    }

    /**
     * Execute rule with @Extends inheritance chain.
     * Pre-creates target, executes parents first, then this rule.
     *
     * <p>The target is created ONCE (of the child's type) and shared across the entire
     * inheritance chain. Parent rules' createTarget() calls return this shared instance.</p>
     *
     * <p>Multi-level inheritance is supported: if a parent also has @Extends, its parents
     * are executed first (recursively), all sharing the same pre-created target.</p>
     */
    private EObject executeWithInheritance(EObject source, TransformationContext context) {
        // Check if already in inheritance execution (nested @Extends)
        if (context.isInInheritanceExecution()) {
            // We're being called as a parent in a chain
            // First execute OUR parents recursively, then our own function
            executeParentRulesInChain(source, context);
            return getFunction().transform(source, context);
        }

        // Pre-create target of THIS rule's type (most derived)
        // We need to create it directly to avoid side effects, then set inheritance mode
        EObject target = createTargetDirectly(context);

        // If target is null (abstract type), execute without pre-creation
        // The transform function will create the concrete type via createTarget()
        // which will then be used for parent rules via executeParentRule(name, source, target)
        if (target == null) {
            // Execute parents first without pre-created target
            // Parents with abstract types will also return null from createTarget()
            // This pattern requires the LEAF child rule to create the concrete type
            // and pass it to parents via executeParentRule(name, source, target)
            executeParentRulesInChain(source, context);
            return getFunction().transform(source, context);
        }

        // Set structured XMI ID on the pre-created target
        // This is needed because createTargetDirectly() bypasses createTarget() ID generation
        context.setStructuredIdOnTarget(target, source, name);

        try {
            // Set pre-created target and enable inheritance mode for entire chain
            context.setPreCreatedTarget(target);
            context.setInInheritanceExecution(true);

            // Execute all parent rules first (in order)
            // Parent rules will get the pre-created target via createTarget()
            executeParentRulesInChain(source, context);

            // Now execute this rule (child) - still in inheritance mode so
            // its createTarget() returns the same pre-created target
            return getFunction().transform(source, context);

        } finally {
            // Clean up
            context.clearPreCreatedTarget();
            context.setInInheritanceExecution(false);
        }
    }

    /**
     * Execute parent rules in the inheritance chain.
     * Handles multi-level inheritance by recursively calling execute() on parents.
     *
     * <p>Uses atomic getOrCreate to prevent race conditions where multiple threads
     * executing the same child rule for the same source could cause duplicate
     * parent rule executions.</p>
     */
    private void executeParentRulesInChain(EObject source, TransformationContext context) {
        if (extendsRules.isEmpty()) {
            return;
        }

        TransformationRegistry registry = context.getTransformationRegistry();
        ElementResolutionCache cache = context.getElementResolutionCache();

        for (String parentRuleName : extendsRules) {
            TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
            if (parentRule != null) {
                // Use atomic getOrCreate to prevent race conditions
                // Multiple concurrent calls for the same (source, parentRuleName) will only execute once.
                // The first thread's supplier runs and caches the result; other threads get the cached value.
                cache.getOrCreate(source, parentRuleName, () -> {
                    // Execute parent - this handles recursive @Extends
                    // If parent also has @Extends, executeWithInheritance detects we're in chain
                    // and recursively executes the grandparent first
                    return parentRule.execute(source, context);
                }, parentRule.isPrimary());
            }
        }
    }

    /**
     * Create target instance directly without going through TransformationContext.createTarget().
     * This avoids side effects like auto-adding to resource during pre-creation.
     *
     * @return the created target, or null if the target type is abstract
     */
    private EObject createTargetDirectly(TransformationContext context) {
        String typeName = targetType.getSimpleName();

        // Try to find the EPackage for this target type
        for (org.eclipse.emf.ecore.EPackage pkg : context.getTargetPackages()) {
            org.eclipse.emf.ecore.EClassifier classifier = pkg.getEClassifier(typeName);
            if (classifier instanceof org.eclipse.emf.ecore.EClass) {
                org.eclipse.emf.ecore.EClass eClass = (org.eclipse.emf.ecore.EClass) classifier;
                // Check if the type is abstract - cannot pre-create abstract types
                if (eClass.isAbstract() || eClass.isInterface()) {
                    return null; // Let the transform function create the concrete type
                }
                return pkg.getEFactoryInstance().create(eClass);
            }
        }

        // Try auto-discovery for generated metamodels
        try {
            return context.createTarget(targetType);
        } catch (IllegalArgumentException e) {
            // Target type might be abstract or not found - let transform function handle it
            return null;
        }
    }

    /**
     * Execute this rule on multiple source elements (Cartesian product tuple).
     *
     * @param sources array of source elements from Cartesian product
     * @param context the transformation context
     * @return the target element
     * @throws IllegalStateException if called on a single-source rule
     */
    public EObject execute(EObject[] sources, TransformationContext context) {
        return getMultiSourceFunction().transform(sources, context);
    }

    @Override
    public String toString() {
        return "TransformRuleDescriptor{" +
                "name='" + name + '\'' +
                ", sourceType=" + sourceType.getSimpleName() +
                ", targetType=" + targetType.getSimpleName() +
                ", isLazy=" + isLazy +
                ", isAbstract=" + isAbstract +
                ", isPrimary=" + isPrimary +
                ", isGreedy=" + isGreedy +
                ", isActivityBased=" + isActivityBased +
                '}';
    }
}
