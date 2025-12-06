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

import org.eclipse.emf.ecore.EObject;

import java.lang.reflect.Method;

/**
 * Metadata descriptor for an extension method.
 *
 * <p>Holds information about the method, whether it's cached, and provides
 * invocation capability.</p>
 */
public class ExtensionMethodDescriptor {
    private final Object instance;
    private final Method method;
    private final boolean cached;

    public ExtensionMethodDescriptor(Object instance, Method method, boolean cached) {
        this.instance = instance;
        this.method = method;
        this.cached = cached;
        method.setAccessible(true);
    }

    public String getName() {
        return method.getName();
    }

    public boolean isCached() {
        return cached;
    }

    public Class<?>[] getParameterTypes() {
        return method.getParameterTypes();
    }

    /**
     * Invoke the extension method with the given target and arguments.
     *
     * @param target the target object (first parameter)
     * @param args additional arguments
     * @return the method result
     */
    public Object invoke(EObject target, Object... args) {
        try {
            Object[] allArgs = new Object[args.length + 1];
            allArgs[0] = target;
            System.arraycopy(args, 0, allArgs, 1, args.length);
            return method.invoke(instance, allArgs);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke extension method: " + method.getName(), e);
        }
    }

    /**
     * Check if this method matches the given argument types.
     */
    public boolean matchesArguments(Object... args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != args.length + 1) {
            return false;
        }

        // Skip first parameter (target/self), check remaining arguments
        for (int i = 0; i < args.length; i++) {
            Class<?> paramType = paramTypes[i + 1];
            Object arg = args[i];

            if (arg == null) {
                if (paramType.isPrimitive()) {
                    return false;
                }
            } else if (!paramType.isAssignableFrom(arg.getClass())) {
                // Check for primitive types
                if (paramType.isPrimitive()) {
                    if (!isCompatiblePrimitive(paramType, arg.getClass())) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isCompatiblePrimitive(Class<?> primitiveType, Class<?> wrapperType) {
        if (primitiveType == int.class) return wrapperType == Integer.class;
        if (primitiveType == long.class) return wrapperType == Long.class;
        if (primitiveType == double.class) return wrapperType == Double.class;
        if (primitiveType == float.class) return wrapperType == Float.class;
        if (primitiveType == boolean.class) return wrapperType == Boolean.class;
        if (primitiveType == byte.class) return wrapperType == Byte.class;
        if (primitiveType == short.class) return wrapperType == Short.class;
        if (primitiveType == char.class) return wrapperType == Character.class;
        return false;
    }
}
