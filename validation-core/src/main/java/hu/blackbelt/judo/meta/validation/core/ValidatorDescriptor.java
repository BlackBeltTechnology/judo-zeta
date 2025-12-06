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

import java.lang.reflect.Method;
import java.util.List;
import org.eclipse.emf.ecore.EObject;

/**
 * Metadata descriptor for a validation rule.
 *
 * <p>Holds information about the constraint/critique annotation, guard method,
 * dependencies, and provides rule invocation capability.</p>
 */
public class ValidatorDescriptor {

    private final Object instance;
    private final Method ruleMethod;
    private final String name;
    private final String message;
    private final Severity severity;
    private final Class<? extends EObject> contextType;
    private final Method guardMethod;
    private final List<String> satisfiesDependencies;
    private ValidationRule cachedRule;
    private Guard cachedGuard;

    public ValidatorDescriptor(
        Object instance,
        Method ruleMethod,
        String name,
        String message,
        Severity severity,
        Class<? extends EObject> contextType,
        Method guardMethod,
        List<String> satisfiesDependencies
    ) {
        this.instance = instance;
        this.ruleMethod = ruleMethod;
        this.name = name;
        this.message = message;
        this.severity = severity;
        this.contextType = contextType;
        this.guardMethod = guardMethod;
        this.satisfiesDependencies = satisfiesDependencies;

        ruleMethod.setAccessible(true);
        if (guardMethod != null) {
            guardMethod.setAccessible(true);
        }
    }

    public String getName() {
        return name;
    }

    public String getMessage() {
        return message;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Class<? extends EObject> getContextType() {
        return contextType;
    }

    public List<String> getSatisfiesDependencies() {
        return satisfiesDependencies;
    }

    /**
     * Get the validation rule (lazily initialized).
     */
    public ValidationRule getRule() {
        if (cachedRule == null) {
            try {
                cachedRule = (ValidationRule) ruleMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to get validation rule: " + name,
                    e
                );
            }
        }
        return cachedRule;
    }

    /**
     * Get the guard predicate (lazily initialized).
     */
    public Guard getGuard() {
        if (guardMethod == null) {
            return null;
        }

        if (cachedGuard == null) {
            cachedGuard = (element, ctx) -> {
                try {
                    return (Boolean) guardMethod.invoke(instance, element, ctx);
                } catch (Exception e) {
                    throw new RuntimeException(
                        "Failed to evaluate guard for: " + name,
                        e
                    );
                }
            };
        }

        return cachedGuard;
    }

    /**
     * Check if this validator applies to the given element type.
     */
    public boolean appliesTo(EObject element) {
        return contextType.isInstance(element);
    }

    /**
     * Validate an element with this rule.
     */
    public ValidationResult validate(EObject element, ValidationContext ctx) {
        // Check @Satisfies dependencies - only run if all dependencies pass
        if (!satisfiesDependencies.isEmpty()) {
            for (String dependency : satisfiesDependencies) {
                if (!ctx.satisfies(element, dependency)) {
                    // Dependency constraint failed, skip this rule
                    return ValidationResult.pass();
                }
            }
        }

        // Check guard
        Guard guard = getGuard();
        if (guard != null && !guard.evaluate(element, ctx)) {
            return ValidationResult.pass();
        }

        // Execute rule
        ValidationResult result = getRule().validate(element, ctx);

        // Enrich result with metadata if it failed
        if (result.isFailed() && result.getConstraintName() == null) {
            return ValidationResult.fail(
                name,
                interpolateMessage(element),
                severity,
                element
            );
        }

        return result;
    }

    /**
     * Interpolate message placeholders with actual values.
     * Supports {element.property} syntax.
     */
    private String interpolateMessage(EObject element) {
        String interpolated = message;

        // Simple placeholder replacement for {element.name}
        if (interpolated.contains("{element.name}")) {
            try {
                Method nameMethod = element.getClass().getMethod("getName");
                Object name = nameMethod.invoke(element);
                interpolated = interpolated.replace(
                    "{element.name}",
                    String.valueOf(name)
                );
            } catch (Exception e) {
                // Ignore if property doesn't exist
            }
        }

        // Add more placeholder types as needed

        return interpolated;
    }

    @Override
    public String toString() {
        return (
            "ValidatorDescriptor{" +
            "name='" +
            name +
            '\'' +
            ", severity=" +
            severity +
            ", contextType=" +
            contextType.getSimpleName() +
            '}'
        );
    }
}
