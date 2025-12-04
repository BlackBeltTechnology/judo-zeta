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

import hu.blackbelt.judo.zeta.validation.annotation.Cached;
import hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.emf.ecore.EObject;

/**
 * Registry for extension methods with caching support.
 *
 * <p>Scans for classes annotated with {@link ExtensionMethod} and registers
 * their public methods as extension methods for the specified EClass type.</p>
 */
public class ExtensionMethodRegistry {

    private final Map<
        Class<? extends EObject>,
        List<ExtensionMethodDescriptor>
    > extensions = new HashMap<>();
    private final Map<CacheKey, Object> cache = new ConcurrentHashMap<>();

    /**
     * Register extension methods from a class.
     *
     * @param extensionClass the class containing extension methods
     */
    public void register(Class<?> extensionClass) {
        ExtensionMethod annotation = extensionClass.getAnnotation(
            ExtensionMethod.class
        );
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Class must be annotated with @ExtensionMethod: " +
                    extensionClass.getName()
            );
        }

        Class<? extends EObject> targetType = annotation.value();

        try {
            Object instance = extensionClass
                .getDeclaredConstructor()
                .newInstance();

            for (Method method : extensionClass.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    boolean cached = method.isAnnotationPresent(Cached.class);
                    extensions
                        .computeIfAbsent(targetType, k -> new ArrayList<>())
                        .add(
                            new ExtensionMethodDescriptor(
                                instance,
                                method,
                                cached
                            )
                        );
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to register extension methods from: " +
                    extensionClass.getName(),
                e
            );
        }
    }

    /**
     * Invoke extension method with caching if applicable.
     *
     * @param target the target object
     * @param methodName the method name
     * @param args method arguments
     * @return the method result
     */
    public <T> T invoke(EObject target, String methodName, Object... args) {
        ExtensionMethodDescriptor descriptor = findMethod(
            target.getClass(),
            methodName,
            args
        );

        if (descriptor == null) {
            throw new IllegalArgumentException(
                "Extension method not found: " +
                    methodName +
                    " for type: " +
                    target.getClass().getName() +
                    " with " +
                    args.length +
                    " arguments"
            );
        }

        if (descriptor.isCached()) {
            CacheKey key = CacheKeyBuilder.build(target, methodName, args);
            return (T) cache.computeIfAbsent(key, k ->
                descriptor.invoke(target, args)
            );
        }

        return (T) descriptor.invoke(target, args);
    }

    /**
     * Find extension method by name and argument types.
     */
    private ExtensionMethodDescriptor findMethod(
        Class<?> targetType,
        String methodName,
        Object... args
    ) {
        // Collect all types to search (class hierarchy + all interfaces)
        Set<Class<?>> typesToSearch = new LinkedHashSet<>();
        collectAllTypes(targetType, typesToSearch);

        for (Class<?> type : typesToSearch) {
            List<ExtensionMethodDescriptor> methods = extensions.get(type);
            if (methods != null) {
                for (ExtensionMethodDescriptor descriptor : methods) {
                    if (
                        descriptor.getName().equals(methodName) &&
                        descriptor.matchesArguments(args)
                    ) {
                        return descriptor;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Recursively collect all types in the hierarchy (classes and interfaces).
     */
    private void collectAllTypes(Class<?> type, Set<Class<?>> types) {
        if (type == null || type == Object.class || !types.add(type)) {
            return;
        }

        // Add superclass hierarchy
        collectAllTypes(type.getSuperclass(), types);

        // Add all interfaces (recursively)
        for (Class<?> iface : type.getInterfaces()) {
            collectAllTypes(iface, types);
        }
    }

    /**
     * Clear cache (called at end of validation run).
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Get registered extension methods for a type (for debugging).
     */
    public Collection<ExtensionMethodDescriptor> getExtensionsFor(
        Class<? extends EObject> type
    ) {
        return extensions.getOrDefault(type, Collections.emptyList());
    }
}
