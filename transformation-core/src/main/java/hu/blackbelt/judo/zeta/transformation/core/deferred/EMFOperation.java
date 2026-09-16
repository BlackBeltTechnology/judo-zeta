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

import java.util.Collection;

/**
 * Sealed interface for deferred EMF operations.
 *
 * <p>During parallel transformation phase, all EMF modifications are captured
 * as immutable operation records instead of being applied directly. This prevents
 * race conditions and ensures thread-safety without locking on EMF objects.</p>
 *
 * <p>Operations are replayed sequentially during the commit phase, sorted by
 * sequence number to ensure deterministic ordering.</p>
 *
 * @see OperationQueue
 * @see DeferredEObject
 */
public sealed interface EMFOperation permits
        EMFOperation.SetAttributeOp,
        EMFOperation.SetReferenceOp,
        EMFOperation.UnsetFeatureOp,
        EMFOperation.AddToListOp,
        EMFOperation.AddAllToListOp,
        EMFOperation.RemoveFromListOp,
        EMFOperation.RemoveAllFromListOp,
        EMFOperation.ClearListOp,
        EMFOperation.SetListElementOp,
        EMFOperation.MoveListElementOp {

    /**
     * Get the sequence number for ordering operations.
     *
     * @return the sequence number (monotonically increasing)
     */
    long sequence();

    /**
     * Apply this operation to the EMF model.
     *
     * <p>Called during the single-threaded commit phase.</p>
     */
    void apply();

    /**
     * Get the target object of this operation.
     *
     * @return the target EObject
     */
    EObject target();

    /**
     * Get the feature being modified.
     *
     * @return the structural feature
     */
    EStructuralFeature feature();

    // ==================== Operation Records ====================

    /**
     * Set an attribute value.
     */
    record SetAttributeOp(
            EObject target,
            EStructuralFeature feature,
            Object value,
            long sequence
    ) implements EMFOperation {
        @Override
        public void apply() {
            target.eSet(feature, value);
        }
    }

    /**
     * Set a reference value.
     * Note: value is already unwrapped at queue time (in DeferredEObject.handleSet)
     */
    record SetReferenceOp(
            EObject target,
            EStructuralFeature feature,
            EObject value,
            long sequence
    ) implements EMFOperation {
        @Override
        public void apply() {
            // Value is already unwrapped at queue time - apply directly
            target.eSet(feature, value);
        }
    }

    /**
     * Unset a feature (restore to default).
     */
    record UnsetFeatureOp(
            EObject target,
            EStructuralFeature feature,
            long sequence
    ) implements EMFOperation {
        @Override
        public void apply() {
            target.eUnset(feature);
        }
    }

    /**
     * Add an element to a list.
     * Note: element is already unwrapped at queue time (in DeferredEList.add)
     */
    record AddToListOp(
            EObject target,
            EStructuralFeature feature,
            Object element,
            int index,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            // Element is already unwrapped at queue time
            if (index < 0 || index >= list.size()) {
                list.add(element);
            } else {
                list.add(index, element);
            }
        }
    }

    /**
     * Add all elements from a collection to a list.
     * Note: elements are already unwrapped at queue time (in DeferredEList.addAll)
     */
    record AddAllToListOp(
            EObject target,
            EStructuralFeature feature,
            Collection<?> elements,
            int index,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            // Elements are already unwrapped at queue time
            if (index < 0 || index >= list.size()) {
                list.addAll((Collection<Object>) elements);
            } else {
                list.addAll(index, (Collection<Object>) elements);
            }
        }
    }

    /**
     * Remove an element from a list.
     * Note: element is already unwrapped at queue time (in DeferredEList.remove)
     */
    record RemoveFromListOp(
            EObject target,
            EStructuralFeature feature,
            Object element,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            // Element is already unwrapped at queue time
            list.remove(element);
        }
    }

    /**
     * Remove all elements from a collection from a list.
     * Note: elements are already unwrapped at queue time (in DeferredEList.removeAll)
     */
    record RemoveAllFromListOp(
            EObject target,
            EStructuralFeature feature,
            Collection<?> elements,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            // Elements are already unwrapped at queue time
            list.removeAll((Collection<Object>) elements);
        }
    }

    /**
     * Clear all elements from a list.
     */
    record ClearListOp(
            EObject target,
            EStructuralFeature feature,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            list.clear();
        }
    }

    /**
     * Set an element at a specific index in a list.
     * Note: element is already unwrapped at queue time (in DeferredEList.set)
     */
    record SetListElementOp(
            EObject target,
            EStructuralFeature feature,
            int index,
            Object element,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            // Element is already unwrapped at queue time
            list.set(index, element);
        }
    }

    /**
     * Move an element within a list.
     */
    record MoveListElementOp(
            EObject target,
            EStructuralFeature feature,
            int newIndex,
            int oldIndex,
            long sequence
    ) implements EMFOperation {
        @Override
        @SuppressWarnings("unchecked")
        public void apply() {
            EList<Object> list = (EList<Object>) target.eGet(feature);
            list.move(newIndex, oldIndex);
        }
    }

    // Note: unwrapIfProxy and unwrapCollection removed - unwrapping now happens
    // at queue time in DeferredEObject.handleSet() and DeferredEList methods.
}
