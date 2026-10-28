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

import hu.blackbelt.judo.zeta.transformation.core.TransformationContext;
import org.eclipse.emf.common.util.AbstractEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDK dynamic proxy handler that defers EMF <em>containment</em> mutations during parallel execution.
 *
 * <h3>What is intercepted:</h3>
 * <ul>
 *   <li>{@code setXxx(child)} where the feature is a single-valued containment EReference
 *       → queued as {@link ContainmentOp.SetReference}</li>
 *   <li>{@code getXxxList()} where the feature is a multi-valued containment EReference
 *       → returns a {@link DeferredContainmentEList} that queues {@code add()} calls</li>
 *   <li>{@code eSet(feature, value)} for a containment EReference → queued</li>
 *   <li>{@code eGet(feature)} for a single-valued containment EReference → returns pending value</li>
 * </ul>
 *
 * <h3>What passes through immediately:</h3>
 * <ul>
 *   <li>All attribute setters/getters</li>
 *   <li>Non-containment EReference setters/getters</li>
 *   <li>All EMF lifecycle methods ({@code eContainer}, {@code eResource}, etc.)</li>
 * </ul>
 *
 * <h3>Read-after-write for single-valued containment:</h3>
 * <p>If a rule calls {@code button.setIcon(icon)} followed by {@code button.getIcon()},
 * the getter returns the pending {@code icon} before commit.</p>
 *
 * @see ContainmentOp
 * @see TransformationContext#commitContainmentOps()
 */
public class ContainmentDeferringProxy implements InvocationHandler {

    // -----------------------------------------------------------------------
    // Method classification cache
    // -----------------------------------------------------------------------

    /** Classifies what action invoke() should take for a given (EClass, Method) pair. */
    private enum MethodKind {
        PASSTHROUGH,                // delegate directly, no special handling
        GET_DELEGATE,               // ProxyMarker.getDelegate()
        PROXY_NOOP,                 // applyPendingValues / clearPendingState
        E_SET_CONTAINMENT,          // eSet(feature, value) where feature is single-valued containment
        E_SET_PASSTHROUGH,          // eSet(feature, value) for non-containment (unwrap arg)
        E_GET_CONTAINMENT_SINGLE,   // eGet(feature) where feature is single-valued containment
        E_GET_CONTAINMENT_MANY,     // eGet(feature) where feature is many-valued containment
        E_GET_PASSTHROUGH,          // eGet(feature) for non-containment
        SET_CONTAINMENT,            // setXxx(value) where feature is single-valued containment
        SET_PASSTHROUGH,            // setXxx(value) for non-containment (unwrap arg)
        GET_CONTAINMENT_SINGLE,     // getXxx() where feature is single-valued containment
        GET_CONTAINMENT_MANY,       // getXxx() where feature is many-valued containment
        GET_PASSTHROUGH,            // getXxx() for non-containment
    }

    /** Cache key: EClass + Method. Shared across all proxy instances of the same EClass. */
    private record MethodKey(EClass eClass, Method method) {}

    /**
     * Static cache mapping (EClass, Method) → MethodKind.
     * Populated lazily on first call; shared across all ContainmentDeferringProxy instances.
     * Thread-safe via ConcurrentHashMap.
     */
    private static final Map<MethodKey, MethodKind> METHOD_KIND_CACHE = new ConcurrentHashMap<>();

    /** Cache of proxy interface arrays per concrete EObject class, to avoid re-traversing class hierarchy. */
    private static final Map<Class<?>, Class<?>[]> PROXY_INTERFACES_CACHE = new ConcurrentHashMap<>();

    /** Cache of EClass → whether it has any containment EReferences (to skip proxy for attribute-only types). */
    private static final Map<EClass, Boolean> HAS_CONTAINMENT_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns true if the EClass has at least one containment EReference (single or many-valued).
     * Result is cached per EClass for O(1) subsequent lookups.
     */
    public static boolean hasContainmentFeatures(EClass eClass) {
        return HAS_CONTAINMENT_CACHE.computeIfAbsent(eClass, cls ->
                cls.getEAllStructuralFeatures().stream()
                        .anyMatch(f -> f instanceof EReference ref && ref.isContainment()));
    }

    /**
     * Also caches the EReference for methods that need it (SET_CONTAINMENT, GET_CONTAINMENT_*),
     * so we never call getEStructuralFeature() more than once per (EClass, Method).
     */
    private static final Map<MethodKey, EReference> METHOD_FEATURE_CACHE = new ConcurrentHashMap<>();

    private static MethodKind classify(EClass eClass, Method method) {
        String name = method.getName();
        int argCount = method.getParameterCount();

        // ProxyMarker methods
        if ("getDelegate".equals(name) && argCount == 0) return MethodKind.GET_DELEGATE;
        if (("applyPendingValues".equals(name) || "clearPendingState".equals(name)) && argCount == 0)
            return MethodKind.PROXY_NOOP;

        // eSet(EStructuralFeature, Object) — feature not known at classify time, defer to PASSTHROUGH
        // (eSet/eGet are handled specially below since feature is a runtime arg, not method-level)
        if ("eSet".equals(name) && argCount == 2) return MethodKind.E_SET_PASSTHROUGH; // placeholder
        if ("eGet".equals(name) && argCount >= 1) return MethodKind.E_GET_PASSTHROUGH; // placeholder

        if (name.startsWith("set") && argCount == 1) {
            String featureName = decapitalize(name.substring(3));
            EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
            if (feature instanceof EReference ref && ref.isContainment() && !ref.isMany()) {
                return MethodKind.SET_CONTAINMENT;
            }
            return MethodKind.SET_PASSTHROUGH;
        }

        if (name.startsWith("get") && argCount == 0) {
            String featureName = decapitalize(name.substring(3));
            EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
            if (feature instanceof EReference ref && ref.isContainment()) {
                return ref.isMany() ? MethodKind.GET_CONTAINMENT_MANY : MethodKind.GET_CONTAINMENT_SINGLE;
            }
            return MethodKind.GET_PASSTHROUGH;
        }

        return MethodKind.PASSTHROUGH;
    }

    private MethodKind getMethodKind(Method method) {
        EClass eClass = delegate.eClass();
        MethodKey key = new MethodKey(eClass, method);
        return METHOD_KIND_CACHE.computeIfAbsent(key, k -> classify(k.eClass(), k.method()));
    }

    private EReference getFeatureForMethod(Method method) {
        EClass eClass = delegate.eClass();
        MethodKey key = new MethodKey(eClass, method);
        return METHOD_FEATURE_CACHE.computeIfAbsent(key, k -> {
            String featureName = decapitalize(k.method().getName().substring(3));
            return (EReference) k.eClass().getEStructuralFeature(featureName);
        });
    }

    /**
     * Marker interface identifying objects wrapped by {@link ContainmentDeferringProxy}.
     * Extends {@link DeferredEObject.ProxyMarker} so existing unwrap code (e.g.,
     * {@link DeferredEObject#unwrap(Object)}, {@link TransformationContext#addToResource}) also
     * unwraps containment-deferring proxies to their delegate before adding to the EMF resource.
     */
    public interface ProxyMarker extends DeferredEObject.ProxyMarker {
        // applyPendingValues() and clearPendingState() no-ops — containment proxy
        // only defers containment mutations; attributes pass through immediately.
        @Override
        default void applyPendingValues() { /* no-op: attributes are immediate */ }
        @Override
        default void clearPendingState() { /* no-op: no pending attribute state to clear */ }
    }

    private final EObject delegate;
    private final TransformationContext ctx;

    /** Pending single-valued containment values for read-after-write consistency. */
    private final ConcurrentHashMap<EReference, EObject> pendingValues = new ConcurrentHashMap<>();

    /** Cached list wrappers per containment feature. */
    private final ConcurrentHashMap<EReference, DeferredContainmentEList> wrappedLists = new ConcurrentHashMap<>();

    private ContainmentDeferringProxy(EObject delegate, TransformationContext ctx) {
        this.delegate = delegate;
        this.ctx = ctx;
    }

    /**
     * Wrap an EMF object with a containment-deferring proxy.
     *
     * @param delegate the real EMF object
     * @param ctx      the transformation context (used to queue containment ops)
     * @param <T>      the EObject type
     * @return a proxy that defers containment mutations
     */
    @SuppressWarnings("unchecked")
    public static <T extends EObject> T createProxy(T delegate, TransformationContext ctx) {
        if (delegate == null) {
            return null;
        }
        // Don't double-wrap
        if (delegate instanceof ProxyMarker) {
            return delegate;
        }

        ContainmentDeferringProxy handler = new ContainmentDeferringProxy(delegate, ctx);

        Class<?>[] proxyInterfaces = PROXY_INTERFACES_CACHE.computeIfAbsent(delegate.getClass(), cls -> {
            Set<Class<?>> interfaces = new LinkedHashSet<>();
            Class<?> current = cls;
            while (current != null) {
                addInterfacesRecursively(current.getInterfaces(), interfaces);
                current = current.getSuperclass();
            }
            interfaces.add(ProxyMarker.class);
            return interfaces.toArray(new Class<?>[0]);
        });

        return (T) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                proxyInterfaces,
                handler
        );
    }

    /**
     * Unwrap a proxy to get the delegate, or return the object unchanged if not a proxy.
     */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(T object) {
        if (object instanceof ProxyMarker pm) {
            return (T) pm.getDelegate();
        }
        return object;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodKind kind = getMethodKind(method);

        switch (kind) {
            case GET_DELEGATE -> { return delegate; }
            case PROXY_NOOP -> { return null; }

            // eSet / eGet: feature is a runtime argument, classify at call time
            case E_SET_PASSTHROUGH -> {
                if (args != null && args.length == 2 && args[0] instanceof EStructuralFeature feature) {
                    if (ctx.isDeferredContainmentEnabled() && isContainment(feature)) {
                        handleContainmentSet((EReference) feature, (EObject) args[1]);
                        return null;
                    }
                    return method.invoke(delegate, new Object[]{ args[0], unwrapIfProxy(args[1]) });
                }
                return method.invoke(delegate, args);
            }
            case E_GET_PASSTHROUGH -> {
                if (args != null && args.length >= 1 && args[0] instanceof EStructuralFeature feature) {
                    if (ctx.isDeferredContainmentEnabled()) {
                        if (isContainment(feature)) return handleContainmentGet((EReference) feature);
                        if (isContainmentMany(feature)) return getWrappedList((EReference) feature);
                    }
                }
                return method.invoke(delegate, args);
            }

            case SET_CONTAINMENT -> {
                if (ctx.isDeferredContainmentEnabled()) {
                    handleContainmentSet(getFeatureForMethod(method), (EObject) args[0]);
                    return null;
                }
                return method.invoke(delegate, new Object[]{ unwrapIfProxy(args[0]) });
            }
            case SET_PASSTHROUGH -> {
                return method.invoke(delegate, new Object[]{ unwrapIfProxy(args[0]) });
            }

            case GET_CONTAINMENT_SINGLE -> {
                if (ctx.isDeferredContainmentEnabled()) {
                    return handleContainmentGet(getFeatureForMethod(method));
                }
                return method.invoke(delegate, args);
            }
            case GET_CONTAINMENT_MANY -> {
                if (ctx.isDeferredContainmentEnabled()) {
                    return getWrappedList(getFeatureForMethod(method));
                }
                return method.invoke(delegate, args);
            }

            default -> { return method.invoke(delegate, args); }
        }
    }

    // -----------------------------------------------------------------------
    // Containment handling
    // -----------------------------------------------------------------------

    private void handleContainmentSet(EReference feature, EObject child) {
        // Store pending value for read-after-write
        if (child != null) {
            EObject realChild = unwrap(child);
            pendingValues.put(feature, realChild);
            ctx.queueContainmentOp(new ContainmentOp.SetReference(
                    delegate, feature, realChild, ctx.nextContainmentOpSequence()));
        } else {
            pendingValues.remove(feature);
            // For null (unset), apply immediately — removing containment is safe single-threaded
            delegate.eUnset(feature);
        }
    }

    private EObject handleContainmentGet(EReference feature) {
        EObject pending = pendingValues.get(feature);
        if (pending != null) {
            return pending;
        }
        return (EObject) delegate.eGet(feature);
    }

    @SuppressWarnings("unchecked")
    private DeferredContainmentEList getWrappedList(EReference feature) {
        return wrappedLists.computeIfAbsent(feature, f -> {
            EList<EObject> realList = (EList<EObject>) delegate.eGet(f);
            return new DeferredContainmentEList(delegate, f, realList, ctx);
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isContainment(EStructuralFeature feature) {
        return feature instanceof EReference ref && ref.isContainment() && !ref.isMany();
    }

    private static boolean isContainmentMany(EStructuralFeature feature) {
        return feature instanceof EReference ref && ref.isContainment() && ref.isMany();
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Unwrap a single arg if it is a proxy (either ContainmentDeferring or DeferredEObject). */
    private static Object unwrapIfProxy(Object arg) {
        if (arg instanceof DeferredEObject.ProxyMarker pm) {
            return pm.getDelegate();
        }
        return arg;
    }

    private static void addInterfacesRecursively(Class<?>[] ifaces, Set<Class<?>> result) {
        for (Class<?> iface : ifaces) {
            if (result.add(iface)) {
                addInterfacesRecursively(iface.getInterfaces(), result);
            }
        }
    }

    // -----------------------------------------------------------------------
    // DeferredContainmentEList
    // -----------------------------------------------------------------------

    /**
     * A list wrapper returned by {@code getXxxList()} for containment EReferences.
     *
     * <p>Queues {@code add()} / {@code addAll()} calls as {@link ContainmentOp.AddToList} ops.
     * Read operations return a combined view of the committed delegate list PLUS any elements
     * pending in this wrapper, so that same-rule reads after same-rule adds see the new element.</p>
     *
     * <p>Thread-safety: adds are safe via lock-free {@link java.util.concurrent.CopyOnWriteArrayList}
     * for {@code pendingAdditions} and {@link TransformationContext#queueContainmentOp}.</p>
     */
    public static class DeferredContainmentEList implements EList<EObject> {

        private final EObject parent;
        private final EReference feature;
        private final EList<EObject> delegate;
        private final TransformationContext ctx;

        /** Pending additions visible within the same parallel phase for read-after-write. */
        private final java.util.concurrent.CopyOnWriteArrayList<EObject> pendingAdditions =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        DeferredContainmentEList(EObject parent, EReference feature,
                                 EList<EObject> delegate, TransformationContext ctx) {
            this.parent = parent;
            this.feature = feature;
            this.delegate = delegate;
            this.ctx = ctx;
        }

        /** Returns a combined view: committed elements first, then pending additions. */
        private List<EObject> combinedView() {
            List<EObject> result = new java.util.ArrayList<>(delegate.size() + pendingAdditions.size());
            result.addAll(delegate);
            result.addAll(pendingAdditions);
            return result;
        }

        // ---- Mutating ops that must be deferred ----

        @Override
        public boolean add(EObject child) {
            // Keep all proxy layers in pendingAdditions so readers in the same parallel phase
            // can access deferred values (e.g., containment setters queued on a ContainmentDeferringProxy)
            // through the proxy chain. Thread B calling combinedView() will get the proxy and
            // access pending values through it, avoiding a read-after-write race.
            pendingAdditions.add(child);
            // Fully unwrap through all proxy layers for the single-threaded replay op,
            // which must operate on the real EMF object.
            EObject real = fullyUnwrap(child);
            ctx.queueContainmentOp(new ContainmentOp.AddToList(
                    parent, feature, real, ctx.nextContainmentOpSequence()));
            return true;
        }

        /** Strip all proxy layers (ContainmentDeferringProxy, DeferredEObject) to reach the real EObject. */
        private static EObject fullyUnwrap(EObject obj) {
            while (obj instanceof DeferredEObject.ProxyMarker pm) {
                obj = (EObject) pm.getDelegate();
            }
            return obj;
        }

        @Override
        public boolean addAll(Collection<? extends EObject> children) {
            for (EObject child : children) {
                add(child);
            }
            return !children.isEmpty();
        }

        @Override
        public void add(int index, EObject child) {
            // Positional inserts not supported during parallel deferral; fall back to append
            add(child);
        }

        @Override
        public boolean addAll(int index, Collection<? extends EObject> children) {
            return addAll(children);
        }

        // ---- Read-only ops use combined view to include pending additions ----

        @Override public int size() { return delegate.size() + pendingAdditions.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty() && pendingAdditions.isEmpty(); }
        @Override public boolean contains(Object o) { return delegate.contains(o) || pendingAdditions.contains(o); }
        @Override public Iterator<EObject> iterator() { return combinedView().iterator(); }
        @Override public Object[] toArray() { return combinedView().toArray(); }
        @Override public <T> T[] toArray(T[] a) { return combinedView().toArray(a); }
        @Override public EObject get(int index) { return combinedView().get(index); }
        @Override public int indexOf(Object o) { return combinedView().indexOf(o); }
        @Override public int lastIndexOf(Object o) { return combinedView().lastIndexOf(o); }
        @Override public ListIterator<EObject> listIterator() { return combinedView().listIterator(); }
        @Override public ListIterator<EObject> listIterator(int index) { return combinedView().listIterator(index); }
        @Override public List<EObject> subList(int from, int to) { return combinedView().subList(from, to); }
        @Override public boolean containsAll(Collection<?> c) { return combinedView().containsAll(c); }

        // ---- Mutating ops that should not be called during parallel phase ----

        @Override
        public EObject set(int index, EObject element) {
            throw new UnsupportedOperationException(
                    "set(index, element) is not supported during deferred containment. Use add() instead.");
        }

        @Override
        public EObject remove(int index) {
            throw new UnsupportedOperationException(
                    "remove() is not supported during deferred containment.");
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException(
                    "remove() is not supported during deferred containment.");
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException(
                    "removeAll() is not supported during deferred containment.");
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException(
                    "retainAll() is not supported during deferred containment.");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException(
                    "clear() is not supported during deferred containment.");
        }

        // EList-specific: move() reorders elements. Queue as MoveInList; applied after all adds at commit.
        @Override
        public void move(int newPosition, EObject object) {
            EObject real = DeferredEObject.unwrap(object);
            ctx.queueContainmentOp(new ContainmentOp.MoveInList(
                    parent, feature, newPosition, real, -1, ctx.nextContainmentOpSequence()));
        }

        @Override
        public EObject move(int newPosition, int oldPosition) {
            ctx.queueContainmentOp(new ContainmentOp.MoveInList(
                    parent, feature, newPosition, null, oldPosition, ctx.nextContainmentOpSequence()));
            // Return current element at oldPosition from combined view (best-effort)
            List<EObject> combined = combinedView();
            return oldPosition < combined.size() ? combined.get(oldPosition) : null;
        }
    }
}
