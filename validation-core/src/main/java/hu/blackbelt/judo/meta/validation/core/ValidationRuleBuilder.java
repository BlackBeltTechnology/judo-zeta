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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Fluent builder for constructing validation rules with inline guards.
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * ValidationRule rule = ValidationRule.builder()
 *     .guard((self, ctx) -> ((EntityType) self).getContainer() != null)
 *     .check((self, ctx) -> {
 *         EntityType e = (EntityType) self;
 *         return e.getMapping() != null
 *             ? ValidationResult.pass()
 *             : ValidationResult.fail("Entity type must have mapping");
 *     })
 *     .build();
 * }
 * </pre>
 */
public class ValidationRuleBuilder {
    private final List<Guard> guards = new ArrayList<>();
    private BiFunction<EObject, ValidationContext, ValidationResult> check;

    /**
     * Add a guard predicate (can be called multiple times for AND composition).
     *
     * @param guard the guard predicate
     * @return this builder
     */
    public ValidationRuleBuilder guard(Guard guard) {
        guards.add(guard);
        return this;
    }

    /**
     * Set the validation check logic.
     *
     * @param check the validation logic
     * @return this builder
     */
    public ValidationRuleBuilder check(BiFunction<EObject, ValidationContext, ValidationResult> check) {
        this.check = check;
        return this;
    }

    /**
     * Build the validation rule.
     *
     * @return the constructed validation rule
     */
    public ValidationRule build() {
        if (check == null) {
            throw new IllegalStateException("Validation check must be set");
        }

        return (element, ctx) -> {
            // Evaluate all guards (AND composition)
            for (Guard guard : guards) {
                if (!guard.evaluate(element, ctx)) {
                    // Guard failed, skip validation
                    return ValidationResult.pass();
                }
            }

            // All guards passed, execute check
            return check.apply(element, ctx);
        };
    }

    /**
     * Convenience method to add guard with AND composition.
     *
     * @param guard the guard to add
     * @return this builder
     */
    public ValidationRuleBuilder and(Guard guard) {
        return guard(guard);
    }
}
