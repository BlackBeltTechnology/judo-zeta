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
 * Functional interface for multi-source transformation rules.
 *
 * <p>Used when a rule has multiple @Transform annotations, creating a Cartesian product
 * of source elements. The sources array contains one element from each @Transform 
 * annotation in declaration order.</p>
 *
 * <p>Example usage with Cartesian product:</p>
 * <pre>{@code
 * @TransformRule(name = "ApplyMapping")
 * @Transform(alias = "asm", type = EClass.class)
 * @Transform(alias = "mapping", type = EAnnotation.class)
 * @To(alias = "rdbms", type = EClass.class)
 * public MultiSourceTransformFunction<EClass> applyMapping() {
 *     return (sources, ctx) -> {
 *         EClass entity = (EClass) sources[0];       // From "asm"
 *         EAnnotation mapping = (EAnnotation) sources[1]; // From "mapping"
 *         EClass table = ctx.create(EClass.class);
 *         table.setName(mapping.getDetails().get(entity.getName()));
 *         return table;
 *     };
 * }
 * }</pre>
 *
 * <p>If "asm" has elements [A, B, C] and "mapping" has [M1, M2], the rule fires 
 * 6 times: (A,M1), (A,M2), (B,M1), (B,M2), (C,M1), (C,M2).</p>
 *
 * @param <T> the target type
 * @see TransformFunction for single-source rules
 */
@FunctionalInterface
public interface MultiSourceTransformFunction<T extends EObject> {
    /**
     * Transform multiple source elements into a target element.
     *
     * @param sources array of source elements, one from each @Transform annotation in order
     * @param context the transformation context
     * @return the target element
     */
    T transform(EObject[] sources, TransformationContext context);
}
