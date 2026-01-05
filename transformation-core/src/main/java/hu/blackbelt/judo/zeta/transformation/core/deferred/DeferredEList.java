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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wrapper for EMF EList that defers modifications until commit.
 *
 * <p>Write operations (add, remove, set, clear) are recorded as operations
 * in the queue instead of being applied directly. Read operations return
 * a combined view of the original list plus pending additions.</p>
 *
 * <h3>Thread Safety:</h3>
 * <p>This class is thread-safe for concurrent add operations. The pending
 * additions list uses CopyOnWriteArrayList for safe concurrent access.</p>
 *
 * <h3>Read Consistency:</h3>
 * <p>Reads include pending additions so that code like this works:</p>
 * <pre>{@code
 * table.getColumns().add(column);
 * boolean contains = table.getColumns().contains(column);  // true
 * }</pre>
 *
 * @param <E> the element type
 * @see EMFOperation
 * @see DeferredEObject
 */
public class DeferredEList<E> implements EList<E> {

    private final EList<E> delegate;
    private final EObject container;
    private final EStructuralFeature feature;
    private final OperationQueue queue;

    /**
     * Pending additions that haven't been committed yet.
     * Used for read-after-write consistency.
     */
    private final List<E> pendingAdditions = new CopyOnWriteArrayList<>();

    /**
     * Pending removals (by identity) for consistent reads.
     */
    private final Set<Object> pendingRemovals = ConcurrentHashMap.newKeySet();

    /**
     * Create a deferred EList wrapper.
     *
     * @param delegate the actual EMF list
     * @param container the containing EObject
     * @param feature the structural feature
     * @param queue the operation queue
     */
    public DeferredEList(EList<E> delegate, EObject container, EStructuralFeature feature, OperationQueue queue) {
        this.delegate = delegate;
        this.container = container;
        this.feature = feature;
        this.queue = queue;
    }

    // ==================== Write Operations (Deferred) ====================

    @Override
    public boolean add(E element) {
        E unwrapped = unwrap(element);
        pendingAdditions.add(unwrapped);
        queue.add(new EMFOperation.AddToListOp(
                container, feature, unwrapped, -1, queue.nextSequence()
        ));
        return true;
    }

    @Override
    public void add(int index, E element) {
        E unwrapped = unwrap(element);
        pendingAdditions.add(unwrapped);
        queue.add(new EMFOperation.AddToListOp(
                container, feature, unwrapped, index, queue.nextSequence()
        ));
    }

    @Override
    public boolean addAll(Collection<? extends E> collection) {
        if (collection == null || collection.isEmpty()) {
            return false;
        }
        Collection<E> unwrapped = unwrapCollection(collection);
        pendingAdditions.addAll(unwrapped);
        queue.add(new EMFOperation.AddAllToListOp(
                container, feature, unwrapped, -1, queue.nextSequence()
        ));
        return true;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> collection) {
        if (collection == null || collection.isEmpty()) {
            return false;
        }
        Collection<E> unwrapped = unwrapCollection(collection);
        pendingAdditions.addAll(unwrapped);
        queue.add(new EMFOperation.AddAllToListOp(
                container, feature, unwrapped, index, queue.nextSequence()
        ));
        return true;
    }

    @Override
    public boolean remove(Object element) {
        Object unwrapped = unwrap(element);
        pendingAdditions.remove(unwrapped);
        pendingRemovals.add(unwrapped);
        queue.add(new EMFOperation.RemoveFromListOp(
                container, feature, unwrapped, queue.nextSequence()
        ));
        return true;
    }

    @Override
    public E remove(int index) {
        // Get the element to return
        E element = get(index);
        Object unwrapped = unwrap(element);
        pendingRemovals.add(unwrapped);
        queue.add(new EMFOperation.RemoveFromListOp(
                container, feature, unwrapped, queue.nextSequence()
        ));
        return element;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        if (collection == null || collection.isEmpty()) {
            return false;
        }
        Collection<Object> unwrapped = unwrapObjectCollection(collection);
        pendingAdditions.removeAll(unwrapped);
        pendingRemovals.addAll(unwrapped);
        queue.add(new EMFOperation.RemoveAllFromListOp(
                container, feature, unwrapped, queue.nextSequence()
        ));
        return true;
    }

    @Override
    public void clear() {
        pendingAdditions.clear();
        // Mark all current elements as removed
        pendingRemovals.addAll(delegate);
        queue.add(new EMFOperation.ClearListOp(
                container, feature, queue.nextSequence()
        ));
    }

    @Override
    public E set(int index, E element) {
        E old = get(index);
        E unwrapped = unwrap(element);
        queue.add(new EMFOperation.SetListElementOp(
                container, feature, index, unwrapped, queue.nextSequence()
        ));
        return old;
    }

    @Override
    public void move(int newPosition, E element) {
        int oldPosition = indexOf(element);
        if (oldPosition >= 0) {
            queue.add(new EMFOperation.MoveListElementOp(
                    container, feature, newPosition, oldPosition, queue.nextSequence()
            ));
        }
    }

    @Override
    public E move(int newPosition, int oldPosition) {
        E element = get(oldPosition);
        queue.add(new EMFOperation.MoveListElementOp(
                container, feature, newPosition, oldPosition, queue.nextSequence()
        ));
        return element;
    }

    // ==================== Read Operations (Combined View) ====================

    @Override
    public int size() {
        return getCombinedView().size();
    }

    @Override
    public boolean isEmpty() {
        return getCombinedView().isEmpty();
    }

    @Override
    public boolean contains(Object element) {
        Object unwrapped = unwrap(element);
        if (pendingRemovals.contains(unwrapped)) {
            return false;
        }
        return pendingAdditions.contains(unwrapped) || delegate.contains(unwrapped);
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        for (Object element : collection) {
            if (!contains(element)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public E get(int index) {
        return getCombinedView().get(index);
    }

    @Override
    public int indexOf(Object element) {
        return getCombinedView().indexOf(unwrap(element));
    }

    @Override
    public int lastIndexOf(Object element) {
        return getCombinedView().lastIndexOf(unwrap(element));
    }

    @Override
    public Object[] toArray() {
        return getCombinedView().toArray();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] array) {
        return getCombinedView().toArray(array);
    }

    @Override
    public Iterator<E> iterator() {
        return getCombinedView().iterator();
    }

    @Override
    public ListIterator<E> listIterator() {
        return getCombinedView().listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return getCombinedView().listIterator(index);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        return getCombinedView().subList(fromIndex, toIndex);
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        // Complex operation - defer as remove of non-retained elements
        List<E> toRemove = new ArrayList<>();
        for (E element : getCombinedView()) {
            if (!collection.contains(element)) {
                toRemove.add(element);
            }
        }
        return removeAll(toRemove);
    }

    // ==================== Lifecycle Methods ====================

    /**
     * Clear all pending state after commit.
     *
     * <p>After {@link OperationQueue#commit()} is called, all pending additions
     * have been applied to the delegate list. This method clears the pending
     * state so that subsequent reads only see the delegate list.</p>
     *
     * <p>This is important to prevent double-counting: without clearing,
     * {@link #getCombinedView()} would return both delegate elements AND
     * pending additions, resulting in 2x the actual element count.</p>
     */
    public void clearPendingState() {
        pendingAdditions.clear();
        pendingRemovals.clear();
    }

    // ==================== Helper Methods ====================

    /**
     * Get a combined view of the delegate list plus pending additions,
     * minus pending removals.
     */
    private List<E> getCombinedView() {
        List<E> combined = new ArrayList<>(delegate.size() + pendingAdditions.size());

        // Add delegate elements (excluding pending removals)
        for (E element : delegate) {
            if (!pendingRemovals.contains(element)) {
                combined.add(element);
            }
        }

        // Add pending additions
        combined.addAll(pendingAdditions);

        return combined;
    }

    @SuppressWarnings("unchecked")
    private E unwrap(Object element) {
        if (element instanceof DeferredEObject.ProxyMarker) {
            return (E) ((DeferredEObject.ProxyMarker) element).getDelegate();
        }
        return (E) element;
    }

    @SuppressWarnings("unchecked")
    private Collection<E> unwrapCollection(Collection<? extends E> collection) {
        List<E> result = new ArrayList<>(collection.size());
        for (E element : collection) {
            result.add(unwrap(element));
        }
        return result;
    }

    private Collection<Object> unwrapObjectCollection(Collection<?> collection) {
        List<Object> result = new ArrayList<>(collection.size());
        for (Object element : collection) {
            result.add(unwrap(element));
        }
        return result;
    }
}
