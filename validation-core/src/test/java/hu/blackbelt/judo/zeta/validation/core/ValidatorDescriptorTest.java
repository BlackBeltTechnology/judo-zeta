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
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidatorDescriptor}.
 * Tests descriptor creation, lazy rule loading, guard evaluation, and validation execution.
 */
@DisplayName("ValidatorDescriptor Tests")
class ValidatorDescriptorTest extends AbstractValidationTest {

    @Nested
    @DisplayName("AppliesTo Tests")
    class AppliesToTests {

        @Test
        @DisplayName("Should return true when element type matches context type")
        void shouldReturnTrueWhenElementTypeMatchesContextType() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");
            EClass element = TestModelFactory.createEClass("TestClass");

            // When
            boolean applies = descriptor.appliesTo(element);

            // Then
            assertTrue(applies, "Validator should apply to EClass elements");
        }

        @Test
        @DisplayName("Should return false when element type does not match context type")
        void shouldReturnFalseWhenElementTypeDoesNotMatchContextType() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");
            EPackage element = TestModelFactory.createEPackage("test", "http://test");

            // When
            boolean applies = descriptor.appliesTo(element);

            // Then
            assertFalse(applies, "Validator should NOT apply to EPackage elements");
        }

        @Test
        @DisplayName("Should return true for subtype when registered for supertype")
        void shouldReturnTrueForSubtypeWhenRegisteredForSupertype() {
            // Given - AlwaysPassValidator is registered for EObject
            registry.register(TestValidators.AlwaysPassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("AlwaysPasses");
            EClass element = TestModelFactory.createEClass("TestClass");

            // When
            boolean applies = descriptor.appliesTo(element);

            // Then
            assertTrue(applies, "Validator should apply to subtypes of registered type");
        }
    }

    @Nested
    @DisplayName("Metadata Tests")
    class MetadataTests {

        @Test
        @DisplayName("Should return correct constraint name")
        void shouldReturnCorrectConstraintName() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When/Then
            assertEquals("EClassMustHaveName", descriptor.getName());
        }

        @Test
        @DisplayName("Should return correct message")
        void shouldReturnCorrectMessage() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When/Then
            assertEquals("EClass must have a name", descriptor.getMessage());
        }

        @Test
        @DisplayName("Should return ERROR severity for @Constraint")
        void shouldReturnErrorSeverityForConstraint() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When/Then
            assertEquals(Severity.ERROR, descriptor.getSeverity());
        }

        @Test
        @DisplayName("Should return WARNING severity for @Critique")
        void shouldReturnWarningSeverityForCritique() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassShouldStartWithCapital");

            // When/Then
            assertEquals(Severity.WARNING, descriptor.getSeverity());
        }

        @Test
        @DisplayName("Should return correct context type")
        void shouldReturnCorrectContextType() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When/Then
            assertEquals(EClass.class, descriptor.getContextType());
        }
    }

    @Nested
    @DisplayName("Rule Loading Tests")
    class RuleLoadingTests {

        @Test
        @DisplayName("Should get validation rule lazily")
        void shouldGetValidationRuleLazily() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When
            ValidationRule rule = descriptor.getRule();

            // Then
            assertNotNull(rule, "Rule should be loaded");
        }

        @Test
        @DisplayName("Should cache validation rule instance")
        void shouldCacheValidationRuleInstance() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When
            ValidationRule rule1 = descriptor.getRule();
            ValidationRule rule2 = descriptor.getRule();

            // Then
            assertSame(rule1, rule2, "Rule should be cached and return same instance");
        }
    }

    @Nested
    @DisplayName("Guard Evaluation Tests")
    class GuardEvaluationTests {

        @Test
        @DisplayName("Should return null guard when no guard method specified")
        void shouldReturnNullGuardWhenNoGuardMethodSpecified() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When
            Guard guard = descriptor.getGuard();

            // Then
            assertNull(guard, "Guard should be null when no @Guard annotation");
        }

        @Test
        @DisplayName("Should return guard when guard method specified")
        void shouldReturnGuardWhenGuardMethodSpecified() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");

            // When
            Guard guard = descriptor.getGuard();

            // Then
            assertNotNull(guard, "Guard should not be null when @Guard annotation present");
        }

        @Test
        @DisplayName("Guard should return false for non-abstract class")
        void guardShouldReturnFalseForNonAbstractClass() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");
            EClass concreteClass = TestModelFactory.createEClass("ConcreteClass");
            concreteClass.setAbstract(false);
            addToModel(concreteClass);

            // When
            Guard guard = descriptor.getGuard();
            boolean result = guard.evaluate(concreteClass, context);

            // Then
            assertFalse(result, "Guard should return false for non-abstract class");
        }

        @Test
        @DisplayName("Guard should return true for abstract class")
        void guardShouldReturnTrueForAbstractClass() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");
            EClass abstractClass = TestModelFactory.createEClass("AbstractClass");
            abstractClass.setAbstract(true);
            addToModel(abstractClass);

            // When
            Guard guard = descriptor.getGuard();
            boolean result = guard.evaluate(abstractClass, context);

            // Then
            assertTrue(result, "Guard should return true for abstract class");
        }
    }

    @Nested
    @DisplayName("Validation Execution Tests")
    class ValidationExecutionTests {

        @Test
        @DisplayName("Should return pass when validation passes")
        void shouldReturnPassWhenValidationPasses() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");
            EClass element = TestModelFactory.createEClass("ValidName");
            addToModel(element);

            // When
            ValidationResult result = descriptor.validate(element, context);

            // Then
            assertTrue(result.isPassed(), "Validation should pass for valid element");
        }

        @Test
        @DisplayName("Should return failure when validation fails")
        void shouldReturnFailureWhenValidationFails() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");
            EClass element = TestModelFactory.createEClass(null);
            addToModel(element);

            // When
            ValidationResult result = descriptor.validate(element, context);

            // Then
            assertTrue(result.isFailed(), "Validation should fail for invalid element");
            assertEquals("EClassMustHaveName", result.getConstraintName());
        }

        @Test
        @DisplayName("Should skip validation when guard fails")
        void shouldSkipValidationWhenGuardFails() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");
            EClass concreteClass = TestModelFactory.createEClass("ConcreteClass");
            concreteClass.setAbstract(false); // Guard will fail
            addToModel(concreteClass);

            // When
            ValidationResult result = descriptor.validate(concreteClass, context);

            // Then
            assertTrue(result.isPassed(), "Validation should be skipped when guard fails");
        }

        @Test
        @DisplayName("Should execute validation when guard passes")
        void shouldExecuteValidationWhenGuardPasses() {
            // Given
            registry.register(TestValidators.GuardedValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("GuardedConstraint");
            EClass abstractClass = TestModelFactory.createEClass("AbstractClass");
            abstractClass.setAbstract(true); // Guard will pass
            addToModel(abstractClass);

            // When
            ValidationResult result = descriptor.validate(abstractClass, context);

            // Then
            assertTrue(result.isFailed(), "Validation should execute and fail when guard passes");
        }
    }

    @Nested
    @DisplayName("Satisfies Dependencies Tests")
    class SatisfiesDependenciesTests {

        @Test
        @DisplayName("Should return empty list when no dependencies")
        void shouldReturnEmptyListWhenNoDependencies() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When
            var dependencies = descriptor.getSatisfiesDependencies();

            // Then
            assertTrue(dependencies.isEmpty(), "Should have no dependencies");
        }

        @Test
        @DisplayName("Should return dependencies when @Satisfies present")
        void shouldReturnDependenciesWhenSatisfiesPresent() {
            // Given
            registry.register(TestValidators.DependentValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("DependentConstraint");

            // When
            var dependencies = descriptor.getSatisfiesDependencies();

            // Then
            assertEquals(1, dependencies.size());
            assertTrue(dependencies.contains("BaseConstraint"));
        }

        @Test
        @DisplayName("Should skip dependent validation when prerequisite fails")
        void shouldSkipDependentValidationWhenPrerequisiteFails() {
            // Given
            registry.register(TestValidators.DependentValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("DependentConstraint");
            EClass element = TestModelFactory.createEClass(null); // BaseConstraint will fail
            addToModel(element);
            context.setCurrentElement(element);

            // When
            ValidationResult result = descriptor.validate(element, context);

            // Then
            assertTrue(result.isPassed(), 
                "Dependent validation should be skipped when prerequisite fails");
        }

        @Test
        @DisplayName("Should execute dependent validation when prerequisite passes")
        void shouldExecuteDependentValidationWhenPrerequisitePasses() {
            // Given
            registry.register(TestValidators.DependentValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("DependentConstraint");
            EClass element = TestModelFactory.createEClass("AB"); // BaseConstraint passes, but name too short
            addToModel(element);
            context.setCurrentElement(element);

            // When
            ValidationResult result = descriptor.validate(element, context);

            // Then
            assertTrue(result.isFailed(), 
                "Dependent validation should execute and fail when prerequisite passes");
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should return descriptive toString")
        void shouldReturnDescriptiveToString() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // When
            String result = descriptor.toString();

            // Then
            assertTrue(result.contains("EClassMustHaveName"));
            assertTrue(result.contains("ERROR"));
            assertTrue(result.contains("EClass"));
        }
    }
}
