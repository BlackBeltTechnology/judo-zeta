package hu.blackbelt.judo.zeta.transformation.core;

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

import org.eclipse.emf.ecore.EObject;

/**
 * Guard interface for multi-source transformation rules.
 *
 * <p>Used when a rule has multiple @Transform annotations and the guard needs
 * to evaluate conditions across all source elements in the Cartesian product tuple.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * public boolean shouldApplyMapping(EObject[] sources, TransformationContext ctx) {
 *     EClass entity = (EClass) sources[0];
 *     EAnnotation mapping = (EAnnotation) sources[1];
 *     // Guard can evaluate conditions on all source elements
 *     return mapping.getSource().equals(entity.getName());
 * }
 * }</pre>
 *
 * @see TransformGuard for single-source guards
 */
@FunctionalInterface
public interface MultiSourceTransformGuard {
    /**
     * Evaluate whether the rule should execute for this source tuple.
     *
     * @param sources array of source elements from Cartesian product
     * @param context the transformation context
     * @return true if the rule should execute, false to skip
     */
    boolean evaluate(EObject[] sources, TransformationContext context);
}
