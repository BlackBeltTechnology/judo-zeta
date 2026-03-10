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
 * Strategy for how {@code equivalentDiscriminated()} handles cloning behavior.
 *
 * <p>ETL's custom {@code equivalentDiscriminated} (defined in {@code id.eol}) has specific
 * cloning semantics that differ from ZETA's default behavior. This enum
 * allows selecting which semantics to use.</p>
 */
public enum EquivalentDiscriminatedStrategy {

    /**
     * ZETA default behavior: Always clone from the pristine original.
     *
     * <ul>
     *   <li>First caller: gets a CLONE of the pristine original</li>
     *   <li>Subsequent callers: get CLONEs of the same pristine original</li>
     *   <li>Mutations are ISOLATED per clone</li>
     * </ul>
     */
    CLONE_PRISTINE,

    /**
     * ETL-compatible behavior: First caller gets original, subsequent callers
     * clone from the current (possibly mutated) state of the original.
     *
     * <ul>
     *   <li>First caller: gets the ORIGINAL object directly (no clone)</li>
     *   <li>Subsequent callers: get CLONEs of the CURRENT state of the original</li>
     *   <li>Mutations by first caller PROPAGATE to subsequent clones</li>
     * </ul>
     *
     * <p><b>Requires sequential execution.</b> ETL's equivalentDiscriminated depends on
     * mutation ordering which is non-deterministic in parallel mode. Using this strategy
     * with deferred writes enabled will throw {@link IllegalStateException}.</p>
     */
    CLONE_CURRENT_STATE
}
