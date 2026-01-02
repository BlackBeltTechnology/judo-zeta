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
import java.util.List;
import java.util.Map;
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
        } catch (Exception e) {
            log.error("Failed to register transformation class: {}", transformationClass.getName(), e);
            throw new RuntimeException("Failed to register transformation: " + transformationClass.getName(), e);
        }
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

        log.debug("Registered rule: {} ({} -> {}) with {} transforms, {} tos", 
                name, sourceType.getSimpleName(), targetType.getSimpleName(), 
                transforms.size(), tos.size());
    }

    /**
     * Get all rules for a given source type (including greedy matches from supertypes).
     *
     * @param sourceType the source type
     * @return collection of applicable rules
     */
    public Collection<TransformRuleDescriptor> getRulesForSource(Class<? extends EObject> sourceType) {
        List<TransformRuleDescriptor> result = new ArrayList<>();

        // Get rules for this exact type
        result.addAll(rulesBySourceType.getOrDefault(sourceType, Collections.emptyList()));

        // Get rules from supertypes/interfaces that apply to this type
        // For greedy rules: always include if supertype matches
        // For non-greedy rules: include if the rule's sourceType is assignable from the element type
        for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
            Class<? extends EObject> ruleSourceType = entry.getKey();
            if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
                for (TransformRuleDescriptor rule : entry.getValue()) {
                    // Include all rules whose sourceType matches (via isAssignableFrom)
                    // This handles cases where rules are defined on interfaces (EClass)
                    // but elements are implementation classes (EClassImpl)
                    if (!result.contains(rule)) {
                        result.add(rule);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Get rule by name.
     */
    public TransformRuleDescriptor getRuleByName(String name) {
        return rulesByName.get(name);
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
