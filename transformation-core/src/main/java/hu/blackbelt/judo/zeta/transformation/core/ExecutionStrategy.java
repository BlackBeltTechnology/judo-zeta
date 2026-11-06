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

/**
 * Strategy for how the transformation executor iterates over rules and source elements.
 *
 * <p>ETL processes rules one at a time (all matching elements per rule), while ZETA's
 * default processes elements one at a time (all matching rules per element). This enum
 * allows selecting which execution model to use.</p>
 */
public enum ExecutionStrategy {

    /**
     * ZETA default: for each source element, execute all matching eager rules.
     *
     * <ul>
     *   <li>Outer loop: source elements</li>
     *   <li>Inner loop: matching rules per element</li>
     *   <li>Cross-element outputs visible only via equivalent() cache</li>
     * </ul>
     */
    ELEMENT_BY_ELEMENT,

    /**
     * ETL-compatible: for each eager rule, execute it for all matching source elements.
     *
     * <ul>
     *   <li>Outer loop: rules in registration order</li>
     *   <li>Inner loop: all matching source elements</li>
     *   <li>Later rules see ALL outputs from earlier rules</li>
     *   <li>Supports both sequential and parallel execution within each rule</li>
     * </ul>
     */
    RULE_BY_RULE
}
