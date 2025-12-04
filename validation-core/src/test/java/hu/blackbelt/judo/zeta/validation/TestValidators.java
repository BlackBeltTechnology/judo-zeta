package hu.blackbelt.judo.zeta.validation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2025 BlackBelt Technology
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

import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.Severity;
import hu.blackbelt.judo.zeta.validation.core.ValidationContext;
import hu.blackbelt.judo.zeta.validation.core.ValidationResult;
import hu.blackbelt.judo.zeta.validation.core.ValidationRule;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample validator classes for testing purposes.
 * These validators test various validation framework features.
 */
public class TestValidators {

    /**
     * Simple validator for EClass elements.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EClass.class)
    public static class EClassValidator {

        @Constraint(
            name = "EClassMustHaveName",
            message = "EClass must have a name"
        )
        public ValidationRule eClassMustHaveName() {
            return (element, ctx) -> {
                EClass eClass = (EClass) element;
                return eClass.getName() != null && !eClass.getName().isEmpty()
                    ? ValidationResult.pass()
                    : ValidationResult.fail(
                        "EClassMustHaveName",
                        "EClass must have a name",
                        Severity.ERROR,
                        element
                    );
            };
        }

        @Critique(
            name = "EClassShouldStartWithCapital",
            message = "EClass name should start with capital letter"
        )
        public ValidationRule eClassShouldStartWithCapital() {
            return (element, ctx) -> {
                EClass eClass = (EClass) element;
                if (eClass.getName() == null || eClass.getName().isEmpty()) {
                    return ValidationResult.pass();
                }
                return Character.isUpperCase(eClass.getName().charAt(0))
                    ? ValidationResult.pass()
                    : ValidationResult.warn(
                        "EClassShouldStartWithCapital",
                        "EClass name '" + eClass.getName() + "' should start with capital letter",
                        element
                    );
            };
        }
    }

    /**
     * Validator for EPackage elements.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EPackage.class)
    public static class EPackageValidator {

        @Constraint(
            name = "EPackageMustHaveNsURI",
            message = "EPackage must have namespace URI"
        )
        public ValidationRule ePackageMustHaveNsURI() {
            return (element, ctx) -> {
                EPackage pkg = (EPackage) element;
                return pkg.getNsURI() != null && !pkg.getNsURI().isEmpty()
                    ? ValidationResult.pass()
                    : ValidationResult.fail(
                        "EPackageMustHaveNsURI",
                        "EPackage must have namespace URI",
                        Severity.ERROR,
                        element
                    );
            };
        }
    }

    /**
     * Validator that always passes (for testing passing results).
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EObject.class)
    public static class AlwaysPassValidator {

        @Constraint(name = "AlwaysPasses", message = "Always passes")
        public ValidationRule alwaysPasses() {
            return (element, ctx) -> ValidationResult.pass();
        }
    }

    /**
     * Validator that always fails (for testing failing results).
     * Note: Uses EObject.class which will match all EMF objects, causing duplicate
     * registrations in the validator hierarchy (known issue in ValidationRegistry).
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EObject.class)
    public static class AlwaysFailValidator {

        @Constraint(name = "AlwaysFails", message = "Always fails")
        public ValidationRule alwaysFails() {
            return (element, ctx) ->
                ValidationResult.fail(
                    "AlwaysFails",
                    "Always fails",
                    Severity.ERROR,
                    element
                );
        }
    }

    /**
     * EClass-specific validator that always fails.
     * Used to avoid duplicate registration issues when testing parallel validation.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EClass.class)
    public static class EClassAlwaysFailValidator {

        @Constraint(name = "EClassAlwaysFails", message = "EClass always fails")
        public ValidationRule eClassAlwaysFails() {
            return (element, ctx) ->
                ValidationResult.fail(
                    "EClassAlwaysFails",
                    "EClass always fails",
                    Severity.ERROR,
                    element
                );
        }
    }

    /**
     * Validator with guard condition.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EClass.class)
    public static class GuardedValidator {

        @Constraint(name = "GuardedConstraint", message = "Guarded constraint")
        @Guard(method = "isAbstract")
        public ValidationRule guardedConstraint() {
            return (element, ctx) ->
                ValidationResult.fail("Should not execute when guard fails");
        }

        public boolean isAbstract(EObject element, ValidationContext ctx) {
            return ((EClass) element).isAbstract();
        }
    }

    /**
     * Validator with counting for cache testing.
     * Note: Uses EObject.class which causes duplicate registrations.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EObject.class)
    public static class CountingValidator {

        public static final AtomicInteger callCount = new AtomicInteger(0);

        @Constraint(name = "CountingConstraint", message = "Counting constraint")
        public ValidationRule countingConstraint() {
            return (element, ctx) -> {
                callCount.incrementAndGet();
                return ValidationResult.pass();
            };
        }
    }

    /**
     * EClass-specific validator with counting for cache testing.
     * Avoids duplicate registration issues.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EClass.class)
    public static class EClassCountingValidator {

        public static final AtomicInteger callCount = new AtomicInteger(0);

        @Constraint(name = "EClassCountingConstraint", message = "EClass counting constraint")
        public ValidationRule eClassCountingConstraint() {
            return (element, ctx) -> {
                callCount.incrementAndGet();
                return ValidationResult.pass();
            };
        }
    }

    /**
     * Validator with pre and post-validation hooks.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EObject.class)
    public static class HookValidator {

        public static final AtomicBoolean preHookCalled = new AtomicBoolean(false);
        public static final AtomicBoolean postHookCalled = new AtomicBoolean(false);

        @PreValidation
        public void preHook(ValidationContext ctx) {
            preHookCalled.set(true);
        }

        @PostValidation
        public void postHook(ValidationContext ctx) {
            postHookCalled.set(true);
        }

        @Constraint(name = "HookConstraint", message = "Hook constraint")
        public ValidationRule hookConstraint() {
            return (element, ctx) -> ValidationResult.pass();
        }
    }

    /**
     * Validator with @Satisfies dependencies.
     */
    @hu.blackbelt.judo.zeta.validation.annotation.ValidationContext(EClass.class)
    public static class DependentValidator {

        @Constraint(name = "BaseConstraint", message = "Base constraint")
        public ValidationRule baseConstraint() {
            return (element, ctx) -> {
                EClass eClass = (EClass) element;
                return eClass.getName() != null
                    ? ValidationResult.pass()
                    : ValidationResult.fail("BaseConstraint", "Name is null", Severity.ERROR, element);
            };
        }

        @Constraint(name = "DependentConstraint", message = "Dependent constraint")
        @Satisfies(constraints = "BaseConstraint")
        public ValidationRule dependentConstraint() {
            return (element, ctx) -> {
                EClass eClass = (EClass) element;
                // This should only run if BaseConstraint passes
                return eClass.getName().length() > 3
                    ? ValidationResult.pass()
                    : ValidationResult.fail("DependentConstraint", "Name too short", Severity.ERROR, element);
            };
        }
    }

    /**
     * Extension methods for EClass testing.
     */
    @ExtensionMethod(EClass.class)
    public static class EClassExtensions {

        public static final AtomicInteger cachedCallCount = new AtomicInteger(0);
        public static final AtomicInteger nonCachedCallCount = new AtomicInteger(0);

        public String getNameUpper(EClass self) {
            return self.getName() != null ? self.getName().toUpperCase() : null;
        }

        @Cached
        public String cachedGetName(EClass self) {
            cachedCallCount.incrementAndGet();
            return self.getName();
        }

        public String nonCachedGetName(EClass self) {
            nonCachedCallCount.incrementAndGet();
            return self.getName();
        }

        public int getAttributeCount(EClass self) {
            return (int) self.getEStructuralFeatures().stream()
                .filter(f -> f instanceof org.eclipse.emf.ecore.EAttribute)
                .count();
        }
    }

    /**
     * Invalid extension class without @ExtensionMethod annotation.
     */
    public static class InvalidExtensions {

        public String someMethod(EClass self) {
            return self.getName();
        }
    }
}
