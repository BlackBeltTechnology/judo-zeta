package hu.blackbelt.judo.zeta.validation.core;

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

import hu.blackbelt.judo.zeta.validation.AbstractValidationTest;
import hu.blackbelt.judo.zeta.validation.TestModelFactory;
import hu.blackbelt.judo.zeta.validation.TestValidators;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that all validation annotations are correctly processed.
 * Verifies @Constraint, @Critique, @Guard, @Satisfies, @Cached,
 * @PreExecution, @PostExecution, @ExtensionMethod, and @ValidationContext.
 */
@DisplayName("Annotation Processing Tests")
class AnnotationProcessingTest extends AbstractValidationTest {

    @Nested
    @DisplayName("@Constraint Annotation Tests")
    class ConstraintAnnotationTests {

        @Test
        @DisplayName("Should process @Constraint annotation with name and message")
        void shouldProcessConstraintAnnotationWithNameAndMessage() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // Then
            assertNotNull(descriptor, "@Constraint should be registered");
            assertEquals("EClassMustHaveName", descriptor.getName());
            assertEquals("EClass must have a name", descriptor.getMessage());
            assertEquals(Severity.ERROR, descriptor.getSeverity());
        }

        @Test
        @DisplayName("Should execute @Constraint validation rule")
        void shouldExecuteConstraintValidationRule() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass invalidElement = TestModelFactory.createEClass(null);
            addToModel(invalidElement);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(invalidElement));

            // Then
            assertTrue(results.stream().anyMatch(r -> 
                r.getConstraintName().equals("EClassMustHaveName") &&
                r.getSeverity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("@Critique Annotation Tests")
    class CritiqueAnnotationTests {

        @Test
        @DisplayName("Should process @Critique annotation with WARNING severity")
        void shouldProcessCritiqueAnnotationWithWarningSeverity() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassShouldStartWithCapital");

            // Then
            assertNotNull(descriptor, "@Critique should be registered");
            assertEquals(Severity.WARNING, descriptor.getSeverity());
        }

        @Test
        @DisplayName("Should execute @Critique validation rule")
        void shouldExecuteCritiqueValidationRule() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass lowercaseElement = TestModelFactory.createEClass("lowercase");
            addToModel(lowercaseElement);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(lowercaseElement));

            // Then
            assertTrue(results.stream().anyMatch(r -> 
                r.getConstraintName().equals("EClassShouldStartWithCapital") &&
                r.getSeverity() == Severity.WARNING));
        }
    }

    @Nested
    @DisplayName("@Guard Annotation Tests")
    class GuardAnnotationTests {

        @Test
        @DisplayName("Should process @Guard annotation with method reference")
        void shouldProcessGuardAnnotationWithMethodReference() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");

            // Then
            assertNotNull(descriptor, "@Guard constraint should be registered");
            assertNotNull(descriptor.getGuard(), "Guard should be set");
        }

        @Test
        @DisplayName("Should skip validation when guard returns false")
        void shouldSkipValidationWhenGuardReturnsFalse() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            EClass concreteClass = TestModelFactory.createEClass("ConcreteClass");
            concreteClass.setAbstract(false);
            addToModel(concreteClass);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(concreteClass));

            // Then
            assertTrue(results.stream().noneMatch(r -> 
                r.getConstraintName().equals("GuardedConstraint")),
                "Guarded constraint should not run when guard returns false");
        }

        @Test
        @DisplayName("Should execute validation when guard returns true")
        void shouldExecuteValidationWhenGuardReturnsTrue() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            EClass abstractClass = TestModelFactory.createEClass("AbstractClass");
            abstractClass.setAbstract(true);
            addToModel(abstractClass);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(abstractClass));

            // Then
            assertTrue(results.stream().anyMatch(r -> 
                r.getConstraintName() != null && r.getConstraintName().contains("Guarded")),
                "Guarded constraint should run when guard returns true");
        }
    }

    @Nested
    @DisplayName("@Satisfies Annotation Tests")
    class SatisfiesAnnotationTests {

        @Test
        @DisplayName("Should process @Satisfies annotation with dependencies")
        void shouldProcessSatisfiesAnnotationWithDependencies() {
            // Given
            registry.register(TestValidators.DependentValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("DependentConstraint");

            // Then
            assertNotNull(descriptor, "@Satisfies constraint should be registered");
            List<String> dependencies = descriptor.getSatisfiesDependencies();
            assertEquals(1, dependencies.size());
            assertTrue(dependencies.contains("BaseConstraint"));
        }

        @Test
        @DisplayName("Should skip dependent constraint when prerequisite fails")
        void shouldSkipDependentConstraintWhenPrerequisiteFails() {
            // Given
            registry.register(TestValidators.DependentValidator.class);
            EClass elementWithoutName = TestModelFactory.createEClass(null);
            addToModel(elementWithoutName);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(elementWithoutName));

            // Then
            assertTrue(results.stream().anyMatch(r -> 
                "BaseConstraint".equals(r.getConstraintName())),
                "BaseConstraint should fail");
            assertTrue(results.stream().noneMatch(r -> 
                "DependentConstraint".equals(r.getConstraintName())),
                "DependentConstraint should be skipped when prerequisite fails");
        }

        @Test
        @DisplayName("Should execute dependent constraint when prerequisite passes")
        void shouldExecuteDependentConstraintWhenPrerequisitePasses() {
            // Given
            registry.register(TestValidators.DependentValidator.class);
            EClass elementWithShortName = TestModelFactory.createEClass("AB"); // Passes base, fails dependent
            addToModel(elementWithShortName);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            List<ValidationResult> results = executor.validate(Arrays.asList(elementWithShortName));

            // Then
            assertTrue(results.stream().noneMatch(r -> 
                "BaseConstraint".equals(r.getConstraintName())),
                "BaseConstraint should pass");
            assertTrue(results.stream().anyMatch(r -> 
                "DependentConstraint".equals(r.getConstraintName())),
                "DependentConstraint should execute and fail");
        }
    }

    @Nested
    @DisplayName("@PreExecution and @PostExecution Annotation Tests")
    class HookAnnotationTests {

        @Test
        @DisplayName("Should invoke @PreExecution hooks before validation")
        void shouldInvokePreExecutionHooksBeforeValidation() {
            // Given
            TestValidators.HookValidator.preHookCalled.set(false);
            registry.register(TestValidators.HookValidator.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            executor.validate(Arrays.asList(element));

            // Then
            assertTrue(TestValidators.HookValidator.preHookCalled.get(),
                "@PreExecution hook should be called");
        }

        @Test
        @DisplayName("Should invoke @PostExecution hooks after validation")
        void shouldInvokePostExecutionHooksAfterValidation() {
            // Given
            TestValidators.HookValidator.postHookCalled.set(false);
            registry.register(TestValidators.HookValidator.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            ValidationExecutor executor = new ValidationExecutor(registry, context, false);
            executor.validate(Arrays.asList(element));

            // Then
            assertTrue(TestValidators.HookValidator.postHookCalled.get(),
                "@PostExecution hook should be called");
        }
    }

    @Nested
    @DisplayName("@ExtensionMethod Annotation Tests")
    class ExtensionMethodAnnotationTests {

        @Test
        @DisplayName("Should process @ExtensionMethod class registration")
        void shouldProcessExtensionMethodClassRegistration() {
            // When
            extensionRegistry.register(TestValidators.EClassExtensions.class);

            // Then
            var extensions = extensionRegistry.getExtensionsFor(EClass.class);
            assertFalse(extensions.isEmpty(), "@ExtensionMethod class should be registered");
        }

        @Test
        @DisplayName("Should invoke extension methods via context")
        void shouldInvokeExtensionMethodsViaContext() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            String result = context.call(element, "getNameUpper");

            // Then
            assertEquals("TESTCLASS", result);
        }
    }

    @Nested
    @DisplayName("@Cached Annotation Tests")
    class CachedAnnotationTests {

        @Test
        @DisplayName("Should cache @Cached extension method results")
        void shouldCacheCachedExtensionMethodResults() {
            // Given
            TestValidators.EClassExtensions.cachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            context.call(element, "cachedGetName");
            context.call(element, "cachedGetName");
            context.call(element, "cachedGetName");

            // Then
            assertEquals(1, TestValidators.EClassExtensions.cachedCallCount.get(),
                "@Cached method should only be executed once");
        }

        @Test
        @DisplayName("Should not cache non-@Cached extension method results")
        void shouldNotCacheNonCachedExtensionMethodResults() {
            // Given
            TestValidators.EClassExtensions.nonCachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            context.call(element, "nonCachedGetName");
            context.call(element, "nonCachedGetName");
            context.call(element, "nonCachedGetName");

            // Then
            assertEquals(3, TestValidators.EClassExtensions.nonCachedCallCount.get(),
                "Non-@Cached method should be executed each time");
        }
    }

    @Nested
    @DisplayName("@ValidationContext Annotation Tests")
    class ValidationContextAnnotationTests {

        @Test
        @DisplayName("Should process @ValidationContext type binding")
        void shouldProcessValidationContextTypeBinding() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            Collection<ValidatorDescriptor> validators = registry.getValidatorsFor(EClass.class);

            // Then
            assertFalse(validators.isEmpty(), 
                "Validators should be found for type specified in @ValidationContext");
            assertTrue(validators.stream().anyMatch(v -> 
                v.getContextType() == EClass.class));
        }

        @Test
        @DisplayName("Should apply validators only to matching context types")
        void shouldApplyValidatorsOnlyToMatchingContextTypes() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            registry.register(TestValidators.EPackageValidator.class);

            // When
            Collection<ValidatorDescriptor> classValidators = registry.getValidatorsFor(EClass.class);
            Collection<ValidatorDescriptor> packageValidators = registry.getValidatorsFor(org.eclipse.emf.ecore.EPackage.class);

            // Then
            assertTrue(classValidators.stream().anyMatch(v -> 
                v.getName().equals("EClassMustHaveName")));
            assertTrue(classValidators.stream().noneMatch(v -> 
                v.getName().equals("EPackageMustHaveNsURI")));
            
            assertTrue(packageValidators.stream().anyMatch(v -> 
                v.getName().equals("EPackageMustHaveNsURI")));
            assertTrue(packageValidators.stream().noneMatch(v -> 
                v.getName().equals("EClassMustHaveName")));
        }
    }
}
