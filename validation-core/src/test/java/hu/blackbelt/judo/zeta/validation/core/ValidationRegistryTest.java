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
import hu.blackbelt.judo.zeta.validation.TestValidators;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidationRegistry}.
 * Tests validator registration, lookup, and hook invocation.
 */
@DisplayName("ValidationRegistry Tests")
class ValidationRegistryTest extends AbstractValidationTest {

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {

        @Test
        @DisplayName("Should register validator class")
        void shouldRegisterValidatorClass() {
            // When
            registry.register(TestValidators.EClassValidator.class);

            // Then
            assertNotNull(registry.getValidatorByName("EClassMustHaveName"));
            assertNotNull(registry.getValidatorByName("EClassShouldStartWithCapital"));
        }

        @Test
        @DisplayName("Should register multiple validator classes")
        void shouldRegisterMultipleValidatorClasses() {
            // When
            registry.register(TestValidators.EClassValidator.class);
            registry.register(TestValidators.EPackageValidator.class);

            // Then
            Collection<ValidatorDescriptor> all = registry.getAllValidators();
            assertTrue(all.size() >= 3, "Should have at least 3 validators registered");
        }

        @Test
        @DisplayName("Should warn for class without @ValidationContext annotation")
        void shouldWarnForNonAnnotatedClass() {
            // When/Then
            assertDoesNotThrow(() -> {
                // The registry logs a warning but doesn't throw
                registry.register(String.class);
            });
        }
    }

    @Nested
    @DisplayName("Lookup Tests")
    class LookupTests {

        @Test
        @DisplayName("Should find validators for exact type")
        void shouldFindValidatorsForExactType() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            Collection<ValidatorDescriptor> validators = registry.getValidatorsFor(EClass.class);

            // Then
            assertFalse(validators.isEmpty());
            assertTrue(validators.stream()
                .anyMatch(v -> v.getName().equals("EClassMustHaveName")));
        }

        @Test
        @DisplayName("Should find validators for supertype")
        void shouldFindValidatorsForSupertype() {
            // Given
            registry.register(TestValidators.AlwaysPassValidator.class);

            // When - EClass extends EObject
            Collection<ValidatorDescriptor> validators = registry.getValidatorsFor(EClass.class);

            // Then
            assertTrue(validators.stream()
                .anyMatch(v -> v.getName().equals("AlwaysPasses")),
                "Should find validators registered for EObject when querying for EClass");
        }

        @Test
        @DisplayName("Should get validator by name")
        void shouldGetValidatorByName() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            ValidatorDescriptor validator = registry.getValidatorByName("EClassMustHaveName");

            // Then
            assertNotNull(validator);
            assertEquals("EClassMustHaveName", validator.getName());
            assertEquals("EClass must have a name", validator.getMessage());
            assertEquals(Severity.ERROR, validator.getSeverity());
        }

        @Test
        @DisplayName("Should return null for non-existent validator name")
        void shouldReturnNullForNonExistentValidatorName() {
            // When
            ValidatorDescriptor validator = registry.getValidatorByName("NonExistent");

            // Then
            assertNull(validator);
        }

        @Test
        @DisplayName("Should get all validators")
        void shouldGetAllValidators() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            registry.register(TestValidators.EPackageValidator.class);

            // When
            Collection<ValidatorDescriptor> all = registry.getAllValidators();

            // Then
            assertTrue(all.size() >= 3, "Should have at least 3 validators");
            assertTrue(all.stream().anyMatch(v -> v.getName().equals("EClassMustHaveName")));
            assertTrue(all.stream().anyMatch(v -> v.getName().equals("EPackageMustHaveNsURI")));
        }

        @Test
        @DisplayName("Should find validators for multiple inheritance levels")
        void shouldFindValidatorsForMultipleInheritanceLevels() {
            // Given
            registry.register(TestValidators.AlwaysPassValidator.class); // Registered for EObject

            // When - EPackage extends EObject
            Collection<ValidatorDescriptor> validators = registry.getValidatorsFor(EPackage.class);

            // Then
            assertTrue(validators.stream()
                .anyMatch(v -> v.getName().equals("AlwaysPasses")),
                "Should find validators from supertype hierarchy");
        }
    }

    @Nested
    @DisplayName("Hook Tests")
    class HookTests {

        @Test
        @DisplayName("Should invoke pre-validation hooks")
        void shouldInvokePreValidationHooks() {
            // Given
            TestValidators.HookValidator.preHookCalled.set(false);
            registry.register(TestValidators.HookValidator.class);

            // When
            registry.invokePreValidationHooks(context);

            // Then
            assertTrue(TestValidators.HookValidator.preHookCalled.get(),
                "Pre-validation hook should have been called");
        }

        @Test
        @DisplayName("Should invoke post-validation hooks")
        void shouldInvokePostValidationHooks() {
            // Given
            TestValidators.HookValidator.postHookCalled.set(false);
            registry.register(TestValidators.HookValidator.class);

            // When
            registry.invokePostValidationHooks(context);

            // Then
            assertTrue(TestValidators.HookValidator.postHookCalled.get(),
                "Post-validation hook should have been called");
        }

        @Test
        @DisplayName("Should handle multiple hooks from multiple validators")
        void shouldHandleMultipleHooks() {
            // Given
            TestValidators.HookValidator.preHookCalled.set(false);
            TestValidators.HookValidator.postHookCalled.set(false);
            registry.register(TestValidators.HookValidator.class);

            // When
            registry.invokePreValidationHooks(context);
            registry.invokePostValidationHooks(context);

            // Then
            assertTrue(TestValidators.HookValidator.preHookCalled.get());
            assertTrue(TestValidators.HookValidator.postHookCalled.get());
        }
    }

    @Nested
    @DisplayName("Validator Descriptor Metadata Tests")
    class ValidatorDescriptorMetadataTests {

        @Test
        @DisplayName("Should create descriptor with correct constraint metadata")
        void shouldCreateDescriptorWithCorrectConstraintMetadata() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassMustHaveName");

            // Then
            assertNotNull(descriptor);
            assertEquals("EClassMustHaveName", descriptor.getName());
            assertEquals("EClass must have a name", descriptor.getMessage());
            assertEquals(Severity.ERROR, descriptor.getSeverity());
            assertEquals(EClass.class, descriptor.getContextType());
        }

        @Test
        @DisplayName("Should create critique descriptor with WARNING severity")
        void shouldCreateCritiqueDescriptorWithWarningSeverity() {
            // Given
            registry.register(TestValidators.EClassValidator.class);

            // When
            ValidatorDescriptor descriptor = registry.getValidatorByName("EClassShouldStartWithCapital");

            // Then
            assertNotNull(descriptor);
            assertEquals(Severity.WARNING, descriptor.getSeverity());
            assertEquals("EClass name should start with capital letter", descriptor.getMessage());
        }
    }
}
