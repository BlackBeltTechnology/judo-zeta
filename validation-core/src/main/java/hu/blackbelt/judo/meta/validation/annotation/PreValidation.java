package hu.blackbelt.judo.zeta.validation.annotation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level annotation that marks a pre-validation hook.
 *
 * <p>Pre-validation hooks are invoked before any validation rules are executed.
 * They can be used to initialize shared resources or perform setup operations.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * @PreValidation
 * public void setup(hu.blackbelt.judo.zeta.validation.core.ValidationContext ctx) {
 *     // Initialize shared resources
 *     ctx.setAttribute("cache", new HashMap<>());
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreValidation {
}
