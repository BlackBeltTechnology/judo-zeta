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

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMIResource;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builder for cache keys with type-specific key generation strategies.
 *
 * <p>Generates stable cache keys from method invocation context (target object and arguments)
 * using different strategies based on the argument types:</p>
 * <ul>
 *   <li>EObject: XMI ID (stable identifier via Resource or EcoreUtil.getURI)</li>
 *   <li>Primitives: value directly (String, Number, Boolean, Enum)</li>
 *   <li>Collection: ordered list of key parts from elements</li>
 *   <li>Map: sorted by key, then process entries as key-value pairs</li>
 *   <li>Null: special NULL_MARKER</li>
 *   <li>Fallback: identity hash code (not ideal, but safe)</li>
 * </ul>
 */
public class CacheKeyBuilder {
    private static final Object NULL_MARKER = new Object() {
        @Override
        public String toString() {
            return "NULL";
        }
    };

    /**
     * Build cache key from method invocation context.
     *
     * @param self the target object (first parameter of extension method)
     * @param methodName the method name
     * @param args additional method arguments
     * @return the cache key
     */
    public static CacheKey build(Object self, String methodName, Object... args) {
        List<Object> keyParts = new ArrayList<>();
        keyParts.add(toKeyPart(self));
        keyParts.add(methodName);
        for (Object arg : args) {
            keyParts.add(toKeyPart(arg));
        }
        return new CacheKey(keyParts);
    }

    /**
     * Build cache key for satisfies() check.
     *
     * @param element the element to check
     * @param constraintName the constraint name
     * @return the cache key
     */
    public static CacheKey buildSatisfiesKey(EObject element, String constraintName) {
        List<Object> keyParts = new ArrayList<>();
        keyParts.add(toKeyPart(element));
        keyParts.add("satisfies");
        keyParts.add(constraintName);
        return new CacheKey(keyParts);
    }

    /**
     * Convert argument to cache key part based on type.
     */
    private static Object toKeyPart(Object value) {
        if (value == null) {
            return NULL_MARKER;
        }

        if (value instanceof EObject) {
            // ECore object: use XMI ID (stable identifier)
            return getXmiId((EObject) value);
        }

        if (value instanceof String || value instanceof Number ||
            value instanceof Boolean || value instanceof Enum) {
            // Primitive types: use value directly
            return value;
        }

        if (value instanceof Collection) {
            // Collection: ordered list of key parts
            return ((Collection<?>) value).stream()
                .map(CacheKeyBuilder::toKeyPart)
                .collect(Collectors.toList());
        }

        if (value instanceof Map) {
            // Map: sorted by key, then process entries
            return ((Map<?, ?>) value).entrySet().stream()
                .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                .map(e -> Arrays.asList(toKeyPart(e.getKey()), toKeyPart(e.getValue())))
                .collect(Collectors.toList());
        }

        // Fallback: use identity hash code (not ideal, but safe)
        return System.identityHashCode(value);
    }

    /**
     * Get XMI ID for EObject, generating if necessary.
     */
    private static String getXmiId(EObject eObject) {
        Resource resource = eObject.eResource();
        if (resource instanceof XMIResource) {
            String id = ((XMIResource) resource).getID(eObject);
            if (id != null) {
                return id;
            }
        }
        // Fallback: use URI fragment or generate stable ID
        URI uri = EcoreUtil.getURI(eObject);
        return uri != null ? uri.toString() : String.valueOf(System.identityHashCode(eObject));
    }
}
