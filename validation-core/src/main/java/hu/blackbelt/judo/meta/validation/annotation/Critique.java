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
 * Method-level annotation that defines a warning-level validation rule (critique).
 *
 * <p>Critiques represent recommendations or best practices that should be followed
 * but are not critical errors.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * @Critique(
 *     name = "EntityTypeNamesAreUnique",
 *     message = "There are two or more entity types of the same name: {element.name}"
 * )
 * public ValidationRule entityTypeNamesAreUnique() {
 *     return (element, ctx) -> {
 *         // Validation logic
 *     };
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Critique {
    /**
     * The unique name of this critique.
     * Used for cross-referencing in {@link Satisfies} and {@link Guard} annotations.
     *
     * @return the critique name
     */
    String name();

    /**
     * The warning message template.
     * Supports placeholder syntax for dynamic values (e.g., "{element.name}").
     *
     * @return the message template
     */
    String message();
}
