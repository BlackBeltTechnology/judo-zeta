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
 * Functional interface for transformation guard predicates.
 *
 * <p>Guards determine whether a transformation rule should be executed for a given element.
 * If the guard returns false, the transformation is skipped.</p>
 */
@FunctionalInterface
public interface TransformGuard {
    /**
     * Evaluate whether the transformation rule should be executed for the given element.
     *
     * @param source the source element to check
     * @param context the transformation context
     * @return true if the transformation should proceed, false to skip
     */
    boolean evaluate(EObject source, TransformationContext context);
}
