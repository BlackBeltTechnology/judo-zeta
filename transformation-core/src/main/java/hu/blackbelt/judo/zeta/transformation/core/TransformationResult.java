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

import org.eclipse.emf.ecore.resource.ResourceSet;

/**
 * Result of a transformation execution.
 *
 * <p>Contains the transformed target model and execution metadata.</p>
 */
public class TransformationResult {

    private final TransformationContext context;
    private final long durationMs;

    public TransformationResult(TransformationContext context, long durationMs) {
        this.context = context;
        this.durationMs = durationMs;
    }

    /**
     * Get the target resource set containing transformed elements.
     */
    public ResourceSet getTargetResourceSet() {
        return context.getTargetResourceSet();
    }

    /**
     * Get the transformation context.
     */
    public TransformationContext getContext() {
        return context;
    }

    /**
     * Get the transformation duration in milliseconds.
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Get the transformation trace for export.
     */
    public TransformationTrace getTrace() {
        return new TransformationTrace(context.getElementResolutionCache());
    }
}
