package hu.blackbelt.judo.zeta.common;

/*-
 * #%L
 * Judo :: Zeta :: Common
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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.emf.ecore.EObject;

import static java.util.Optional.ofNullable;

/**
 * Registry for extension methods with caching support.
 *
 * <p>Scans for classes annotated with @ExtensionMethod and registers
 * their public methods as extension methods for the specified EClass type.</p>
 *
 * <p>This class uses reflection to detect annotations by name to avoid
 * circular dependencies between zeta-common and zeta-annotations modules.</p>
 */
public class ExtensionMethodRegistry {

    private static final String EXTENSION_METHOD_ANNOTATION = "ExtensionMethod";
    private static final String CACHED_ANNOTATION = "Cached";

    private final Map<
        Class<? extends EObject>,
        List<ExtensionMethodDescriptor>
    > extensions = new HashMap<>();

    /**
     * Cache for extension method results.
     * Uses Optional to allow caching null return values (ConcurrentHashMap doesn't allow null).
     */
    private final Map<CacheKey, Optional<Object>> cache = new ConcurrentHashMap<>();

    /**
     * Register extension methods from a class.
     *
     * @param extensionClass the class containing extension methods
     */
    public void register(Class<?> extensionClass) {
        Annotation extensionMethodAnnotation = findAnnotationByName(
            extensionClass.getAnnotations(),
            EXTENSION_METHOD_ANNOTATION
        );
        
        if (extensionMethodAnnotation == null) {
            throw new IllegalArgumentException(
                "Class must be annotated with @ExtensionMethod: " +
                    extensionClass.getName()
            );
        }

        Class<? extends EObject> targetType = getExtensionMethodTargetType(extensionMethodAnnotation);

        try {
            Object instance = extensionClass
                .getDeclaredConstructor()
                .newInstance();

            for (Method method : extensionClass.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    boolean cached = hasAnnotationByName(method.getAnnotations(), CACHED_ANNOTATION);
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
     * Find annotation by simple name (to avoid compile-time dependency).
     */
    private Annotation findAnnotationByName(Annotation[] annotations, String simpleName) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getSimpleName().equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Check if an annotation with the given simple name is present.
     */
    private boolean hasAnnotationByName(Annotation[] annotations, String simpleName) {
        return findAnnotationByName(annotations, simpleName) != null;
    }

    /**
     * Get the target type from @ExtensionMethod annotation using reflection.
     */
    @SuppressWarnings("unchecked")
    private Class<? extends EObject> getExtensionMethodTargetType(Annotation annotation) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            return (Class<? extends EObject>) valueMethod.invoke(annotation);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get target type from @ExtensionMethod annotation", e);
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
    @SuppressWarnings("unchecked")
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
            // Use Optional to cache null values (ConcurrentHashMap doesn't allow null)
            Optional<Object> cached = cache.computeIfAbsent(key, k ->
                ofNullable(descriptor.invoke(target, args))
            );
            return (T) cached.orElse(null);
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
     * Clear cache (called at end of validation/transformation run).
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
