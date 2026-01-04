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

import hu.blackbelt.judo.zeta.annotation.*;
import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for transformation rules with classpath scanning.
 *
 * <p>Scans for classes annotated with {@link hu.blackbelt.judo.zeta.annotation.TransformationContext}
 * and registers their transformation rule methods.</p>
 */
public class TransformationRegistry {

    private static final Logger log = LoggerFactory.getLogger(TransformationRegistry.class);

    // Use LinkedHashMap to preserve registration order for deterministic rule execution
    // This ensures rules execute in the same order as they are defined in the source class
    private final Map<Class<? extends EObject>, List<TransformRuleDescriptor>> rulesBySourceType = new LinkedHashMap<>();
    private final Map<String, TransformRuleDescriptor> rulesByName = new LinkedHashMap<>();
    private final List<Method> preTransformationHooks = new ArrayList<>();
    private final List<Method> postTransformationHooks = new ArrayList<>();
    private final Map<Method, Object> hookInstances = new LinkedHashMap<>();

    // Cache for getRulesForSource() results - O(1) lookup for repeated calls with same type
    // Thread-safe for parallel transformation execution
    private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache =
            new ConcurrentHashMap<>();

    // Pre-filtered index: sourceType → eager-executable rules only
    // Built at registration time to avoid runtime filtering in executeEagerRulesFor()
    private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> eagerRulesByTypeCache =
            new ConcurrentHashMap<>();

    // Pre-filtered index: sourceType → lazy rules only
    // Built at registration time to avoid runtime filtering in equivalent()
    private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> lazyRulesByTypeCache =
            new ConcurrentHashMap<>();

    // Cache for @Primary rules by target type
    // Used to get canonical rule name for lock key normalization in equivalent()
    private final ConcurrentHashMap<Class<?>, TransformRuleDescriptor> primaryRuleByTargetTypeCache =
            new ConcurrentHashMap<>();

    /**
     * Register a transformation context class.
     *
     * @param transformationClass the class containing transformation rules
     */
    public void register(Class<?> transformationClass) {
        hu.blackbelt.judo.zeta.annotation.TransformationContext contextAnnotation =
                transformationClass.getAnnotation(hu.blackbelt.judo.zeta.annotation.TransformationContext.class);

        if (contextAnnotation == null) {
            log.warn("Class {} is not annotated with @TransformationContext, skipping", transformationClass.getName());
            return;
        }

        Class<? extends EObject> defaultSourceType = contextAnnotation.source();
        Class<? extends EObject> defaultTargetType = contextAnnotation.target();

        try {
            Object instance = transformationClass.getDeclaredConstructor().newInstance();

            // Scan for transformation rules
            for (Method method : transformationClass.getDeclaredMethods()) {
                TransformRule ruleAnnotation = method.getAnnotation(TransformRule.class);

                if (ruleAnnotation != null) {
                    registerRule(instance, method, ruleAnnotation, defaultSourceType, defaultTargetType);
                }

                // Scan for hooks
                if (method.isAnnotationPresent(PreExecution.class)) {
                    preTransformationHooks.add(method);
                    hookInstances.put(method, instance);
                    method.setAccessible(true);
                }

                if (method.isAnnotationPresent(PostExecution.class)) {
                    postTransformationHooks.add(method);
                    hookInstances.put(method, instance);
                    method.setAccessible(true);
                }
            }

            log.debug("Registered transformation rules from: {}", transformationClass.getName());

            // Invalidate caches when rules are registered dynamically
            // This ensures new rules are visible in subsequent lookups
            invalidateCaches();
        } catch (Exception e) {
            log.error("Failed to register transformation class: {}", transformationClass.getName(), e);
            throw new RuntimeException("Failed to register transformation: " + transformationClass.getName(), e);
        }
    }

    /**
     * Invalidate all cached rule lookups.
     * Called when rules are registered dynamically after initial setup.
     */
    private void invalidateCaches() {
        rulesBySourceTypeCache.clear();
        eagerRulesByTypeCache.clear();
        lazyRulesByTypeCache.clear();
    }

    private void registerRule(
            Object instance,
            Method ruleMethod,
            TransformRule ruleAnnotation,
            Class<? extends EObject> defaultSourceType,
            Class<? extends EObject> defaultTargetType
    ) {
        String name = ruleAnnotation.name();
        String description = ruleAnnotation.description();

        // Extract @Transform annotations (new API)
        List<TransformDefinition> transforms = new ArrayList<>();
        Transform[] transformAnnotations = ruleMethod.getAnnotationsByType(Transform.class);
        for (Transform t : transformAnnotations) {
            transforms.add(new TransformDefinition(t.alias(), t.type()));
        }

        // Extract @To annotations (new API)
        List<ToDefinition> tos = new ArrayList<>();
        To[] toAnnotations = ruleMethod.getAnnotationsByType(To.class);
        for (To t : toAnnotations) {
            tos.add(new ToDefinition(t.alias(), t.type()));
        }

        // Determine source and target types (backward compatibility)
        Class<? extends EObject>[] sourceTypes = ruleAnnotation.sourceTypes();
        Class<? extends EObject>[] targetTypes = ruleAnnotation.targetTypes();

        Class<? extends EObject> sourceType;
        Class<? extends EObject> targetType;

        // Priority: @Transform annotations > sourceTypes attribute > default from @TransformationContext
        if (!transforms.isEmpty()) {
            sourceType = transforms.get(0).getType();
        } else if (sourceTypes.length > 0) {
            sourceType = sourceTypes[0];
            // Convert sourceTypes to TransformDefinitions with default alias
            for (Class<? extends EObject> st : sourceTypes) {
                transforms.add(new TransformDefinition("source", st));
            }
        } else {
            sourceType = defaultSourceType;
            transforms.add(new TransformDefinition("source", defaultSourceType));
        }

        // Priority: @To annotations > targetTypes attribute > method return type > default from @TransformationContext
        if (!tos.isEmpty()) {
            targetType = tos.get(0).getType();
        } else if (targetTypes.length > 0) {
            targetType = targetTypes[0];
            // Convert targetTypes to ToDefinitions with default alias
            for (Class<? extends EObject> tt : targetTypes) {
                tos.add(new ToDefinition("target", tt));
            }
        } else {
            // Try to extract target type from method return type (TransformFunction<Source, Target>)
            Class<? extends EObject> extractedTargetType = extractTargetTypeFromReturnType(ruleMethod);
            if (extractedTargetType != null) {
                targetType = extractedTargetType;
                tos.add(new ToDefinition("target", extractedTargetType));
                log.debug("Extracted target type from method return type: {} for rule: {}",
                        extractedTargetType.getSimpleName(), name);
            } else {
                targetType = defaultTargetType;
                tos.add(new ToDefinition("target", defaultTargetType));
            }
        }

        // Find guard method if specified
        Guard guardAnnotation = ruleMethod.getAnnotation(Guard.class);
        Method guardMethod = null;
        if (guardAnnotation != null) {
            // Try multi-source guard signature first (EObject[], TransformationContext)
            try {
                guardMethod = instance.getClass().getDeclaredMethod(
                        guardAnnotation.method(),
                        EObject[].class,
                        TransformationContext.class
                );
            } catch (NoSuchMethodException e) {
                // Fall back to single-source guard signature (EObject, TransformationContext)
                try {
                    guardMethod = instance.getClass().getDeclaredMethod(
                            guardAnnotation.method(),
                            EObject.class,
                            TransformationContext.class
                    );
                } catch (NoSuchMethodException e2) {
                    throw new RuntimeException(
                            "Guard method not found: " + guardAnnotation.method() + " for rule: " + name + 
                            ". Expected signature: (EObject, TransformationContext) or (EObject[], TransformationContext)", e2);
                }
            }
        }

        // Check annotations
        boolean isLazy = ruleMethod.isAnnotationPresent(Lazy.class);
        boolean isAbstract = ruleMethod.isAnnotationPresent(Abstract.class);
        boolean isPrimary = ruleMethod.isAnnotationPresent(Primary.class);
        boolean isGreedy = ruleMethod.isAnnotationPresent(Greedy.class);
        boolean isDetached = ruleMethod.isAnnotationPresent(Detached.class);
        boolean isActivityBased = ruleMethod.isAnnotationPresent(ActivityBased.class);

        // Warn if @ActivityBased is used without required @Greedy and @Lazy
        if (isActivityBased && (!isGreedy || !isLazy)) {
            log.warn("Rule '{}' has @ActivityBased but is missing @Greedy and/or @Lazy. " +
                    "@ActivityBased only has effect when used with both @Greedy and @Lazy.", name);
        }

        // Get extends rules
        Extends extendsAnnotation = ruleMethod.getAnnotation(Extends.class);
        List<String> extendsRules = extendsAnnotation != null
                ? Arrays.asList(extendsAnnotation.value())
                : Collections.emptyList();

        TransformRuleDescriptor descriptor = new TransformRuleDescriptor(
                instance,
                ruleMethod,
                name,
                description,
                sourceType,
                targetType,
                guardMethod,
                isLazy,
                isAbstract,
                isPrimary,
                isGreedy,
                isDetached,
                isActivityBased,
                extendsRules,
                transforms,
                tos
        );

        rulesBySourceType.computeIfAbsent(sourceType, k -> new ArrayList<>()).add(descriptor);
        rulesByName.put(name, descriptor);

        // Register @Primary rules for target type lookup (used for lock key normalization)
        if (isPrimary) {
            TransformRuleDescriptor existingPrimary = primaryRuleByTargetTypeCache.putIfAbsent(targetType, descriptor);
            if (existingPrimary != null) {
                log.warn("Multiple @Primary rules for target type {}: '{}' and '{}'. Using first registered: '{}'",
                        targetType.getSimpleName(), existingPrimary.getName(), name, existingPrimary.getName());
            }
        }

        log.debug("Registered rule: {} ({} -> {}) with {} transforms, {} tos",
                name, sourceType.getSimpleName(), targetType.getSimpleName(),
                transforms.size(), tos.size());
    }

    /**
     * Get all rules for a given source type (including greedy matches from supertypes).
     *
     * <p>Results are cached for O(1) lookup on repeated calls with the same type.
     * The cache uses ConcurrentHashMap for thread-safe parallel execution.</p>
     *
     * @param sourceType the source type
     * @return collection of applicable rules (unmodifiable)
     */
    public Collection<TransformRuleDescriptor> getRulesForSource(Class<? extends EObject> sourceType) {
        return rulesBySourceTypeCache.computeIfAbsent(sourceType, this::computeRulesForSource);
    }

    /**
     * Compute rules for a given source type (internal method for cache population).
     * Uses LinkedHashSet for O(1) deduplication while preserving registration order.
     */
    private List<TransformRuleDescriptor> computeRulesForSource(Class<?> sourceType) {
        // Use LinkedHashSet for O(1) deduplication while preserving insertion order
        Set<TransformRuleDescriptor> result = new LinkedHashSet<>();

        // Get rules for this exact type
        result.addAll(rulesBySourceType.getOrDefault(sourceType, Collections.emptyList()));

        // Get rules from supertypes/interfaces that apply to this type
        // For greedy rules: always include if supertype matches
        // For non-greedy rules: include if the rule's sourceType is assignable from the element type
        for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
            Class<? extends EObject> ruleSourceType = entry.getKey();
            if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
                // LinkedHashSet.addAll handles deduplication in O(1) per element
                result.addAll(entry.getValue());
            }
        }

        // Return as unmodifiable ArrayList for iteration efficiency and immutability
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /**
     * Get pre-filtered eager-executable rules for a source type.
     *
     * <p>Returns only rules that are eligible for Phase 1 (eager) execution:
     * <ul>
     *   <li>Not lazy</li>
     *   <li>Not multi-source</li>
     *   <li>Not abstract</li>
     *   <li>Type matches (exact or greedy subtype)</li>
     * </ul></p>
     *
     * <p>This eliminates 4 runtime checks per rule in executeEagerRulesFor(),
     * reducing O(n×r) to O(n×m) where m << r.</p>
     *
     * @param sourceType the source element's runtime type
     * @return pre-filtered list of applicable eager rules (unmodifiable)
     */
    public List<TransformRuleDescriptor> getEagerRulesForType(Class<? extends EObject> sourceType) {
        return eagerRulesByTypeCache.computeIfAbsent(sourceType, this::computeEagerRulesForType);
    }

    /**
     * Compute pre-filtered eager rules for a type (internal cache population).
     *
     * <p>Note: We filter only by isEagerExecutable() here. The actual appliesTo()
     * check must still happen at runtime because it requires an actual EObject
     * instance for EMF type matching semantics.</p>
     */
    private List<TransformRuleDescriptor> computeEagerRulesForType(Class<?> sourceType) {
        List<TransformRuleDescriptor> allRules = computeRulesForSource(sourceType);
        List<TransformRuleDescriptor> eagerRules = new ArrayList<>();

        for (TransformRuleDescriptor rule : allRules) {
            // Only filter by pre-computed eager flag
            // appliesTo() check happens at runtime (needs actual EObject for EMF semantics)
            if (rule.isEagerExecutable()) {
                eagerRules.add(rule);
            }
        }

        return Collections.unmodifiableList(eagerRules);
    }

    /**
     * Get pre-filtered lazy rules for a source type.
     *
     * <p>Returns only lazy rules that could transform the given source type.
     * Used by equivalent() to avoid iterating all rules.</p>
     *
     * @param sourceType the source element's runtime type
     * @return pre-filtered list of applicable lazy rules (unmodifiable)
     */
    public List<TransformRuleDescriptor> getLazyRulesForType(Class<? extends EObject> sourceType) {
        return lazyRulesByTypeCache.computeIfAbsent(sourceType, this::computeLazyRulesForType);
    }

    /**
     * Compute pre-filtered lazy rules for a type (internal cache population).
     */
    private List<TransformRuleDescriptor> computeLazyRulesForType(Class<?> sourceType) {
        List<TransformRuleDescriptor> allRules = computeRulesForSource(sourceType);
        List<TransformRuleDescriptor> lazyRules = new ArrayList<>();

        for (TransformRuleDescriptor rule : allRules) {
            if (rule.isLazy() && !rule.isAbstract()) {
                lazyRules.add(rule);
            }
        }

        return Collections.unmodifiableList(lazyRules);
    }

    /**
     * Get rule by name.
     */
    public TransformRuleDescriptor getRuleByName(String name) {
        return rulesByName.get(name);
    }

    /**
     * Get the @Primary rule for a given target type.
     *
     * <p>Used for lock key normalization in equivalent(). When multiple rules produce
     * the same target type, the @Primary rule's name is used as the canonical lock key
     * to ensure consistent locking between equivalent() and executeParentRule().</p>
     *
     * @param targetType the target type class
     * @return the @Primary rule for this target type, or null if none exists
     */
    public TransformRuleDescriptor getPrimaryRuleForTargetType(Class<?> targetType) {
        return primaryRuleByTargetTypeCache.get(targetType);
    }

    /**
     * Get all registered rules.
     */
    public Collection<TransformRuleDescriptor> getAllRules() {
        return rulesBySourceType.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Clear all rules' rejected element caches.
     *
     * <p>Called during executor reset to ensure fresh state for reused executors.
     * Each rule maintains its own rejected set for ETL-compatible guard caching.</p>
     */
    public void clearAllRejectedSets() {
        for (TransformRuleDescriptor rule : getAllRules()) {
            rule.clearRejected();
        }
    }

    /**
     * Invoke all pre-transformation hooks.
     */
    public void invokePreTransformationHooks(TransformationContext ctx) {
        for (Method hook : preTransformationHooks) {
            try {
                Object instance = hookInstances.get(hook);
                hook.invoke(instance, ctx);
            } catch (Exception e) {
                log.error("Failed to invoke pre-transformation hook: {}", hook.getName(), e);
            }
        }
    }

    /**
     * Invoke all post-transformation hooks.
     */
    public void invokePostTransformationHooks(TransformationContext ctx) {
        for (Method hook : postTransformationHooks) {
            try {
                Object instance = hookInstances.get(hook);
                hook.invoke(instance, ctx);
            } catch (Exception e) {
                log.error("Failed to invoke post-transformation hook: {}", hook.getName(), e);
            }
        }
    }

    /**
     * Extract the target type from a rule method's return type.
     *
     * <p>For methods returning {@code TransformFunction<SourceType, TargetType>},
     * extracts TargetType as the second generic type argument.</p>
     *
     * <p>This enables lazy rules to declare their target type via the method signature
     * without requiring explicit @To annotations, e.g.:</p>
     * <pre>{@code
     * public TransformFunction<ActorType, UnmappedTransferObjectType> createMetadataType() {
     *     // Target type UnmappedTransferObjectType is extracted from the return type
     * }
     * }</pre>
     *
     * @param ruleMethod the rule method
     * @return the extracted target type, or null if extraction fails
     */
    @SuppressWarnings("unchecked")
    private Class<? extends EObject> extractTargetTypeFromReturnType(Method ruleMethod) {
        Type returnType = ruleMethod.getGenericReturnType();

        // Check if return type is parameterized (e.g., TransformFunction<S, T>)
        if (returnType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) returnType;
            Type[] typeArgs = parameterizedType.getActualTypeArguments();

            // TransformFunction has 2 type parameters: <SourceType, TargetType>
            // We want the second one (index 1)
            if (typeArgs.length >= 2) {
                Type targetTypeArg = typeArgs[1];

                // Handle direct class reference
                if (targetTypeArg instanceof Class) {
                    Class<?> targetClass = (Class<?>) targetTypeArg;
                    if (EObject.class.isAssignableFrom(targetClass)) {
                        return (Class<? extends EObject>) targetClass;
                    }
                }

                // Handle parameterized types (e.g., if target is itself generic)
                if (targetTypeArg instanceof ParameterizedType) {
                    Type rawType = ((ParameterizedType) targetTypeArg).getRawType();
                    if (rawType instanceof Class && EObject.class.isAssignableFrom((Class<?>) rawType)) {
                        return (Class<? extends EObject>) rawType;
                    }
                }
            }
        }

        return null;
    }
}
