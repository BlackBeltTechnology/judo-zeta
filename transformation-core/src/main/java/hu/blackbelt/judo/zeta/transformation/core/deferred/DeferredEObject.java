package hu.blackbelt.judo.zeta.transformation.core.deferred;

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

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic proxy handler for deferred EMF writes.
 *
 * <p>Intercepts setter calls and list accessor calls to defer modifications
 * until the commit phase. Getters return pending values if available,
 * otherwise delegate to the actual EMF object.</p>
 *
 * <h3>Intercepted Operations:</h3>
 * <ul>
 *   <li>{@code setXxx(value)} - Records SetAttributeOp/SetReferenceOp</li>
 *   <li>{@code getXxxList()} - Returns DeferredEList wrapper</li>
 *   <li>{@code eSet(feature, value)} - Records appropriate operation</li>
 *   <li>{@code eUnset(feature)} - Records UnsetFeatureOp</li>
 * </ul>
 *
 * <h3>Read-After-Write Consistency:</h3>
 * <p>Pending values are tracked per-feature so that reads after writes
 * return the expected value:</p>
 * <pre>{@code
 * proxy.setName("Orders");
 * String name = proxy.getName();  // Returns "Orders", not null
 * }</pre>
 *
 * @see EMFOperation
 * @see DeferredEList
 * @see OperationQueue
 */
public class DeferredEObject implements InvocationHandler {

    /**
     * Marker interface to identify deferred proxies.
     */
    public interface ProxyMarker {
        /**
         * Get the underlying delegate object.
         *
         * @return the real EObject
         */
        EObject getDelegate();

        /**
         * Apply all pending values to the delegate.
         *
         * <p>This flushes pending attribute/reference values that were set on the proxy
         * to the actual EMF object. This is necessary before operations like
         * {@code EcoreUtil.copy()} that read directly from the delegate.</p>
         *
         * <p>Note: This only applies single-valued features. List operations are
         * handled separately by the OperationQueue.</p>
         */
        void applyPendingValues();
    }

    private static final Set<String> PASSTHROUGH_METHODS = Set.of(
            "equals", "hashCode", "toString", "getClass",
            "eClass", "eResource", "eContainer", "eContainingFeature",
            "eContainmentFeature", "eContents", "eCrossReferences",
            "eAllContents", "eIsProxy", "eAdapters"
    );

    private final EObject delegate;
    private final OperationQueue queue;
    private final ConcurrentHashMap<EStructuralFeature, Object> pendingValues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EStructuralFeature, DeferredEList<?>> wrappedLists = new ConcurrentHashMap<>();

    /**
     * Create a new deferred proxy handler.
     *
     * @param delegate the actual EMF object
     * @param queue the operation queue for recording changes
     */
    public DeferredEObject(EObject delegate, OperationQueue queue) {
        this.delegate = delegate;
        this.queue = queue;
    }

    /**
     * Create a proxy for an EObject.
     *
     * @param delegate the actual EMF object
     * @param queue the operation queue
     * @param <T> the EObject type
     * @return a proxy that defers write operations
     */
    @SuppressWarnings("unchecked")
    public static <T extends EObject> T createProxy(T delegate, OperationQueue queue) {
        if (delegate == null) {
            return null;
        }

        // Don't double-wrap
        if (delegate instanceof ProxyMarker) {
            return delegate;
        }

        DeferredEObject handler = new DeferredEObject(delegate, queue);

        // Get all interfaces the delegate implements
        Class<?>[] interfaces = getAllInterfaces(delegate.getClass());

        // Add our marker interface
        Class<?>[] allInterfaces = Arrays.copyOf(interfaces, interfaces.length + 1);
        allInterfaces[interfaces.length] = ProxyMarker.class;

        return (T) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                allInterfaces,
                handler
        );
    }

    /**
     * Unwrap a proxy to get the delegate.
     *
     * @param object the object (may be a proxy)
     * @param <T> the type
     * @return the unwrapped object
     */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(T object) {
        if (object instanceof ProxyMarker) {
            return (T) ((ProxyMarker) object).getDelegate();
        }
        return object;
    }

    /**
     * Apply pending values to the delegate and return the unwrapped object.
     *
     * <p>If the object is a deferred proxy, this flushes all pending single-valued
     * feature values to the delegate before returning it. This is necessary before
     * operations like {@code EcoreUtil.copy()} that read directly from the delegate.</p>
     *
     * <p>If the object is not a proxy, it is returned unchanged.</p>
     *
     * @param object the object (may be a proxy)
     * @param <T> the type
     * @return the unwrapped object with pending values applied
     */
    @SuppressWarnings("unchecked")
    public static <T> T unwrapWithPendingValues(T object) {
        if (object instanceof ProxyMarker proxy) {
            proxy.applyPendingValues();
            return (T) proxy.getDelegate();
        }
        return object;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        // Handle ProxyMarker interface
        if ("getDelegate".equals(methodName) && args == null) {
            return delegate;
        }

        if ("applyPendingValues".equals(methodName) && args == null) {
            doApplyPendingValues();
            return null;
        }

        // Pass through certain methods directly
        if (PASSTHROUGH_METHODS.contains(methodName)) {
            return method.invoke(delegate, args);
        }

        // Handle eSet
        if ("eSet".equals(methodName) && args != null && args.length == 2) {
            EStructuralFeature feature = (EStructuralFeature) args[0];
            Object value = args[1];
            handleSet(feature, value);
            return null;
        }

        // Handle eUnset
        if ("eUnset".equals(methodName) && args != null && args.length == 1) {
            EStructuralFeature feature = (EStructuralFeature) args[0];
            handleUnset(feature);
            return null;
        }

        // Handle eGet
        if ("eGet".equals(methodName) && args != null && args.length >= 1) {
            EStructuralFeature feature = (EStructuralFeature) args[0];
            return handleGet(feature);
        }

        // Handle setXxx methods
        if (methodName.startsWith("set") && args != null && args.length == 1) {
            String featureName = decapitalize(methodName.substring(3));
            EStructuralFeature feature = findFeature(featureName);
            if (feature != null) {
                handleSet(feature, args[0]);
                return null;
            }
        }

        // Handle getXxx methods that return lists
        if (methodName.startsWith("get") && (args == null || args.length == 0)) {
            String featureName = decapitalize(methodName.substring(3));
            EStructuralFeature feature = findFeature(featureName);
            if (feature != null && feature.isMany()) {
                // Don't wrap EMap (e.g., EAnnotation.getDetails()) - it has special semantics
                // that DeferredEList doesn't support (put, get by key, etc.)
                Object realValue = delegate.eGet(feature);
                if (realValue instanceof EMap) {
                    return realValue;
                }
                return getWrappedList(feature);
            }
            // Single-valued getter - check pending values
            if (feature != null) {
                return handleGet(feature);
            }
        }

        // Handle isXxx methods (boolean getters)
        if (methodName.startsWith("is") && (args == null || args.length == 0)) {
            String featureName = decapitalize(methodName.substring(2));
            EStructuralFeature feature = findFeature(featureName);
            if (feature != null) {
                return handleGet(feature);
            }
        }

        // Default: delegate to the real object
        return method.invoke(delegate, unwrapArgs(args));
    }

    private void handleSet(EStructuralFeature feature, Object value) {
        // Store pending value (may be proxy) for read-after-write consistency
        if (value != null) {
            pendingValues.put(feature, value);
        } else {
            pendingValues.remove(feature);
        }

        // Queue the operation with UNWRAPPED values
        // EMF's inverse handling (eInverseAdd) requires real EObject instances,
        // not proxies. Unwrap at queue time to ensure correct bidirectional refs.
        if (feature instanceof EReference) {
            EObject refValue = (EObject) value;
            EObject realRefValue = unwrap(refValue);  // Unwrap before queueing
            queue.add(new EMFOperation.SetReferenceOp(
                    delegate, feature, realRefValue, queue.nextSequence()
            ));
        } else {
            queue.add(new EMFOperation.SetAttributeOp(
                    delegate, feature, value, queue.nextSequence()
            ));
        }
    }

    private void handleUnset(EStructuralFeature feature) {
        pendingValues.remove(feature);
        queue.add(new EMFOperation.UnsetFeatureOp(
                delegate, feature, queue.nextSequence()
        ));
    }

    private Object handleGet(EStructuralFeature feature) {
        // Check pending values first (read-after-write consistency)
        Object pending = pendingValues.get(feature);
        if (pending != null) {
            return pending;
        }

        // For lists, return wrapped list (but not EMap)
        if (feature.isMany()) {
            Object realValue = delegate.eGet(feature);
            // Don't wrap EMap - it has special semantics that DeferredEList doesn't support
            if (realValue instanceof EMap) {
                return realValue;
            }
            return getWrappedList(feature);
        }

        // Delegate to real object
        return delegate.eGet(feature);
    }

    /**
     * Apply all pending single-valued feature values to the delegate.
     *
     * <p>This is called before operations that need to read the delegate directly,
     * such as {@code EcoreUtil.copy()}.</p>
     */
    private void doApplyPendingValues() {
        for (var entry : pendingValues.entrySet()) {
            EStructuralFeature feature = entry.getKey();
            Object value = entry.getValue();
            // Apply to delegate - unwrap if value is also a proxy
            Object realValue = unwrap(value);
            delegate.eSet(feature, realValue);
        }
        // Don't clear pendingValues - they're still needed for read-after-write consistency
        // and will be replayed via OperationQueue anyway
    }

    @SuppressWarnings("unchecked")
    private <T> DeferredEList<T> getWrappedList(EStructuralFeature feature) {
        return (DeferredEList<T>) wrappedLists.computeIfAbsent(feature, f -> {
            EList<T> realList = (EList<T>) delegate.eGet(f);
            return new DeferredEList<>(realList, delegate, f, queue);
        });
    }

    private EStructuralFeature findFeature(String name) {
        // Try with the given name first
        EStructuralFeature feature = delegate.eClass().getEStructuralFeature(name);
        if (feature != null) {
            return feature;
        }
        // EMF features always start with lowercase, so try that
        if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            String lowered = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            return delegate.eClass().getEStructuralFeature(lowered);
        }
        return null;
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        // EMF always uses lowercase first character for feature names
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static Class<?>[] getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> interfaces = new java.util.LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null) {
            for (Class<?> iface : current.getInterfaces()) {
                addInterfaceRecursively(iface, interfaces);
            }
            current = current.getSuperclass();
        }
        return interfaces.toArray(new Class<?>[0]);
    }

    private static void addInterfaceRecursively(Class<?> iface, java.util.Set<Class<?>> set) {
        if (set.add(iface)) {
            for (Class<?> superIface : iface.getInterfaces()) {
                addInterfaceRecursively(superIface, set);
            }
        }
    }

    private static Object[] unwrapArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = unwrap(args[i]);
        }
        return result;
    }
}
