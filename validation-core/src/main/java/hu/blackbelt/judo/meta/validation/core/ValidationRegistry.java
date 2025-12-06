package hu.blackbelt.judo.zeta.validation.core;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
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
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.emf.ecore.EObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for validation rules with classpath scanning.
 *
 * <p>Scans for classes annotated with {@link ValidationContext} and registers
 * their constraint and critique methods.</p>
 */
public class ValidationRegistry {

    private static final Logger log = LoggerFactory.getLogger(
        ValidationRegistry.class
    );

    private final Map<
        Class<? extends EObject>,
        List<ValidatorDescriptor>
    > validators = new HashMap<>();
    private final Map<String, ValidatorDescriptor> descriptorsByName =
        new HashMap<>();
    private final List<Method> preValidationHooks = new ArrayList<>();
    private final List<Method> postValidationHooks = new ArrayList<>();
    private final Map<Method, Object> hookInstances = new HashMap<>();

    /**
     * Register a validator class.
     *
     * @param validatorClass the class containing validation rules
     */
    public void register(Class<?> validatorClass) {
        hu.blackbelt.judo.zeta.annotation.ValidationContext contextAnnotation =
            validatorClass.getAnnotation(
                hu.blackbelt.judo.zeta.annotation.ValidationContext.class
            );
        if (contextAnnotation == null) {
            log.warn(
                "Class {} is not annotated with @ValidationContext, skipping",
                validatorClass.getName()
            );
            return;
        }

        Class<? extends EObject> contextType = contextAnnotation.value();

        try {
            Object instance = validatorClass
                .getDeclaredConstructor()
                .newInstance();

            // Scan for validation rules
            for (Method method : validatorClass.getDeclaredMethods()) {
                Constraint constraint = method.getAnnotation(Constraint.class);
                Critique critique = method.getAnnotation(Critique.class);

                if (constraint != null) {
                    registerRule(
                        instance,
                        method,
                        constraint.name(),
                        constraint.message(),
                        Severity.ERROR,
                        contextType
                    );
                } else if (critique != null) {
                    registerRule(
                        instance,
                        method,
                        critique.name(),
                        critique.message(),
                        Severity.WARNING,
                        contextType
                    );
                }

                // Scan for hooks
                if (method.isAnnotationPresent(PreExecution.class)) {
                    preValidationHooks.add(method);
                    hookInstances.put(method, instance);
                    method.setAccessible(true);
                }

                if (method.isAnnotationPresent(PostExecution.class)) {
                    postValidationHooks.add(method);
                    hookInstances.put(method, instance);
                    method.setAccessible(true);
                }
            }

            log.debug(
                "Registered validation rules from: {}",
                validatorClass.getName()
            );
        } catch (Exception e) {
            log.error(
                "Failed to register validator class: {}",
                validatorClass.getName(),
                e
            );
            throw new RuntimeException(
                "Failed to register validator: " + validatorClass.getName(),
                e
            );
        }
    }

    private void registerRule(
        Object instance,
        Method ruleMethod,
        String name,
        String message,
        Severity severity,
        Class<? extends EObject> contextType
    ) {
        // Find guard method if specified
        hu.blackbelt.judo.zeta.annotation.Guard guardAnnotation =
            ruleMethod.getAnnotation(
                hu.blackbelt.judo.zeta.annotation.Guard.class
            );
        Method guardMethod = null;
        if (guardAnnotation != null) {
            try {
                guardMethod = instance
                    .getClass()
                    .getDeclaredMethod(
                        guardAnnotation.method(),
                        EObject.class,
                        ValidationContext.class
                    );
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(
                    "Guard method not found: " +
                        guardAnnotation.method() +
                        " for rule: " +
                        name,
                    e
                );
            }
        }

        // Get satisfies dependencies
        Satisfies satisfiesAnnotation = ruleMethod.getAnnotation(
            Satisfies.class
        );
        List<String> dependencies = satisfiesAnnotation != null
            ? Arrays.asList(satisfiesAnnotation.value())
            : Collections.emptyList();

        ValidatorDescriptor descriptor = new ValidatorDescriptor(
            instance,
            ruleMethod,
            name,
            message,
            severity,
            contextType,
            guardMethod,
            dependencies
        );

        validators
            .computeIfAbsent(contextType, k -> new ArrayList<>())
            .add(descriptor);
        descriptorsByName.put(name, descriptor);
    }

    /**
     * Get all validators for a given EClass type (including supertypes).
     *
     * @param eClass the EClass type
     * @return collection of validator descriptors
     */
    public Collection<ValidatorDescriptor> getValidatorsFor(
        Class<? extends EObject> eClass
    ) {
        List<ValidatorDescriptor> result = new ArrayList<>();

        // Get validators for this exact type
        result.addAll(validators.getOrDefault(eClass, Collections.emptyList()));

        // Get validators for supertypes
        for (
            Class<?> superType = eClass.getSuperclass();
            superType != null && EObject.class.isAssignableFrom(superType);
            superType = superType.getSuperclass()
        ) {
            result.addAll(
                validators.getOrDefault(superType, Collections.emptyList())
            );
        }

        // Get validators for all interfaces (recursively)
        collectInterfaceValidators(eClass, result);

        return result;
    }

    /**
     * Recursively collect validators for all interfaces implemented by a class.
     */
    private void collectInterfaceValidators(
        Class<?> clazz,
        List<ValidatorDescriptor> result
    ) {
        if (clazz == null) {
            return;
        }

        // Check direct interfaces
        for (Class<?> iface : clazz.getInterfaces()) {
            if (EObject.class.isAssignableFrom(iface)) {
                result.addAll(
                    validators.getOrDefault(iface, Collections.emptyList())
                );
                // Recursively check interfaces extended by this interface
                collectInterfaceValidators(iface, result);
            }
        }

        // Also check interfaces from superclass
        collectInterfaceValidators(clazz.getSuperclass(), result);
    }

    /**
     * Get validator by name.
     */
    public ValidatorDescriptor getValidatorByName(String name) {
        return descriptorsByName.get(name);
    }

    /**
     * Get all registered validators.
     */
    public Collection<ValidatorDescriptor> getAllValidators() {
        return validators
            .values()
            .stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * Invoke all pre-validation hooks.
     */
    public void invokePreValidationHooks(ValidationContext ctx) {
        for (Method hook : preValidationHooks) {
            try {
                Object instance = hookInstances.get(hook);
                hook.invoke(instance, ctx);
            } catch (Exception e) {
                log.error(
                    "Failed to invoke pre-validation hook: {}",
                    hook.getName(),
                    e
                );
            }
        }
    }

    /**
     * Invoke all post-validation hooks.
     */
    public void invokePostValidationHooks(ValidationContext ctx) {
        for (Method hook : postValidationHooks) {
            try {
                Object instance = hookInstances.get(hook);
                hook.invoke(instance, ctx);
            } catch (Exception e) {
                log.error(
                    "Failed to invoke post-validation hook: {}",
                    hook.getName(),
                    e
                );
            }
        }
    }
}
