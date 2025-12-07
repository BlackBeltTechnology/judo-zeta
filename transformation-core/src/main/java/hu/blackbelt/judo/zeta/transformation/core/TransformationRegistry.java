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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for transformation rules with classpath scanning.
 *
 * <p>Scans for classes annotated with {@link hu.blackbelt.judo.zeta.annotation.TransformationContext}
 * and registers their transformation rule methods.</p>
 */
public class TransformationRegistry {

    private static final Logger log = LoggerFactory.getLogger(TransformationRegistry.class);

    private final Map<Class<? extends EObject>, List<TransformRuleDescriptor>> rulesBySourceType = new HashMap<>();
    private final Map<String, TransformRuleDescriptor> rulesByName = new HashMap<>();
    private final List<Method> preTransformationHooks = new ArrayList<>();
    private final List<Method> postTransformationHooks = new ArrayList<>();
    private final Map<Method, Object> hookInstances = new HashMap<>();

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

        // Determine source and target types
        Class<? extends EObject>[] sourceTypes = ruleAnnotation.sourceTypes();
        Class<? extends EObject>[] targetTypes = ruleAnnotation.targetTypes();

        Class<? extends EObject> sourceType = sourceTypes.length > 0 ? sourceTypes[0] : defaultSourceType;
        Class<? extends EObject> targetType = targetTypes.length > 0 ? targetTypes[0] : defaultTargetType;

        // Find guard method if specified
        Guard guardAnnotation = ruleMethod.getAnnotation(Guard.class);
        Method guardMethod = null;
        if (guardAnnotation != null) {
            try {
                guardMethod = instance.getClass().getDeclaredMethod(
                        guardAnnotation.method(),
                        EObject.class,
                        TransformationContext.class
                );
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(
                        "Guard method not found: " + guardAnnotation.method() + " for rule: " + name, e);
            }
        }

        // Check annotations
        boolean isLazy = ruleMethod.isAnnotationPresent(Lazy.class);
        boolean isAbstract = ruleMethod.isAnnotationPresent(Abstract.class);
        boolean isPrimary = ruleMethod.isAnnotationPresent(Primary.class);
        boolean isGreedy = ruleMethod.isAnnotationPresent(Greedy.class);

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
                extendsRules
        );

        rulesBySourceType.computeIfAbsent(sourceType, k -> new ArrayList<>()).add(descriptor);
        rulesByName.put(name, descriptor);

        log.debug("Registered rule: {} ({} -> {})", name, sourceType.getSimpleName(), targetType.getSimpleName());
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

        // Get greedy rules from supertypes
        for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
            Class<? extends EObject> ruleSourceType = entry.getKey();
            if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
                for (TransformRuleDescriptor rule : entry.getValue()) {
                    if (rule.isGreedy() && !result.contains(rule)) {
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
}
