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
import org.eclipse.emf.ecore.EReference;

/**
 * Sealed interface representing a deferred EMF containment operation.
 * Used by {@link ContainmentDeferringProxy} to queue containment mutations
 * during parallel execution and replay them single-threaded at the rule barrier.
 */
public sealed interface ContainmentOp permits ContainmentOp.AddToList, ContainmentOp.SetReference,
        ContainmentOp.MoveInList {

    /** Returns the sequence number assigned at queue time, used for deterministic ordering. */
    long sequence();

    /** Applies this containment operation directly to the real EMF model. */
    void apply();

    /**
     * A deferred {@code parent.getFeatureList().add(child)} operation.
     *
     * @param parent      the parent EMF object whose containment list is being mutated
     * @param feature     the containment EReference
     * @param child       the child element to add
     * @param sequence    monotonically increasing sequence number for ordering
     */
    record AddToList(EObject parent, EReference feature, EObject child, long sequence)
            implements ContainmentOp {

        @SuppressWarnings("unchecked")
        @Override
        public void apply() {
            EList<EObject> list = (EList<EObject>) parent.eGet(feature);
            list.add(child);
        }
    }

    /**
     * A deferred {@code parent.setFeature(child)} operation for a single-valued containment reference.
     *
     * @param parent      the parent EMF object
     * @param feature     the single-valued containment EReference
     * @param child       the child element to set
     * @param sequence    monotonically increasing sequence number for ordering
     */
    record SetReference(EObject parent, EReference feature, EObject child, long sequence)
            implements ContainmentOp {

        @Override
        public void apply() {
            parent.eSet(feature, child);
        }
    }

    /**
     * A deferred {@code parent.getFeatureList().move(newPosition, object)} operation.
     *
     * <p>Moves are applied AFTER all {@link AddToList} ops so that the target position is valid.</p>
     *
     * @param parent      the parent EMF object whose containment list is being reordered
     * @param feature     the containment EReference
     * @param newPosition the target index
     * @param object      the element to move (may be null if using positional variant)
     * @param oldPosition the source index (-1 if using the object-based variant)
     * @param sequence    monotonically increasing sequence number (moves are sorted after adds)
     */
    record MoveInList(EObject parent, EReference feature, int newPosition,
                      EObject object, int oldPosition, long sequence)
            implements ContainmentOp {

        @SuppressWarnings("unchecked")
        @Override
        public void apply() {
            EList<EObject> list = (EList<EObject>) parent.eGet(feature);
            if (object != null) {
                list.move(newPosition, object);
            } else {
                list.move(newPosition, oldPosition);
            }
        }
    }
}
