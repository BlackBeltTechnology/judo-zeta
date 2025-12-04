package hu.blackbelt.judo.zeta.validation.core;

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

import org.eclipse.emf.ecore.EObject;

/**
 * Functional interface for validation rules.
 *
 * <p>Validation rules take an EObject element and a ValidationContext,
 * and return a ValidationResult indicating success or failure.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * ValidationRule rule = (element, ctx) -> {
 *     EntityType e = (EntityType) element;
 *     return e.getMapping() != null
 *         ? ValidationResult.pass()
 *         : ValidationResult.fail("Entity type must have mapping");
 * };
 * }
 * </pre>
 */
@FunctionalInterface
public interface ValidationRule {
    /**
     * Validate the given element.
     *
     * @param element the element to validate
     * @param ctx the validation context
     * @return the validation result
     */
    ValidationResult validate(EObject element, ValidationContext ctx);

    /**
     * Create a builder for fluent validation rule construction.
     *
     * @return a new validation rule builder
     */
    static ValidationRuleBuilder builder() {
        return new ValidationRuleBuilder();
    }
}
