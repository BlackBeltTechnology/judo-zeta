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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidationExecutor}.
 * Tests sequential and parallel validation execution.
 */
@DisplayName("ValidationExecutor Tests")
class ValidationExecutorTest extends AbstractValidationTest {

    @Test
    @DisplayName("Should validate elements sequentially")
    void shouldValidateElementsSequentially() {
        // Given
        registry.register(TestValidators.AlwaysPassValidator.class);
        EClass element = TestModelFactory.createEClass("TestClass");
        addToModel(element);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Collections.singletonList(element));

        // Then
        assertTrue(results.isEmpty(), "Should have no failures for passing validator");
    }

    @Test
    @DisplayName("Should return failures for failing validators")
    void shouldReturnFailuresForFailingValidators() {
        // Given
        registry.register(TestValidators.AlwaysFailValidator.class);
        EClass element = TestModelFactory.createEClass("TestClass");
        addToModel(element);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Collections.singletonList(element));

        // Then
        assertFalse(results.isEmpty(), "Should have failures");
        assertEquals("AlwaysFails", results.get(0).getConstraintName());
        assertEquals("Always fails", results.get(0).getMessage());
    }

    @Test
    @DisplayName("Should filter out passing results")
    void shouldFilterOutPassingResults() {
        // Given
        registry.register(TestValidators.EClassValidator.class);
        EClass elementWithName = TestModelFactory.createEClass("ValidName");
        addToModel(elementWithName);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Collections.singletonList(elementWithName));

        // Then
        assertTrue(results.isEmpty(), "Should filter out passing results");
    }

    @Test
    @DisplayName("Should handle empty collections")
    void shouldHandleEmptyCollections() {
        // Given
        registry.register(TestValidators.AlwaysFailValidator.class);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Collections.emptyList());

        // Then
        assertTrue(results.isEmpty(), "Should handle empty collections");
    }

    @Test
    @DisplayName("Should validate elements in parallel")
    void shouldValidateElementsInParallel() {
        // Given: Use a validator specific to EClass to avoid duplicate registration issue
        registry.register(TestValidators.EClassAlwaysFailValidator.class);
        EClass element1 = TestModelFactory.createEClass("Class1");
        EClass element2 = TestModelFactory.createEClass("Class2");
        addToModel(element1);
        addToModel(element2);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, true);
        try {
            List<ValidationResult> results = executor.validate(Arrays.asList(element1, element2));

            // Then
            assertEquals(2, results.size(), "Should validate both elements");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Should shutdown executor properly")
    void shouldShutdownExecutorProperly() {
        // Given
        ValidationExecutor executor = new ValidationExecutor(registry, context, true);

        // When/Then
        assertDoesNotThrow(executor::shutdown, "Should shutdown without errors");
    }

    @Test
    @DisplayName("Should invoke pre-validation hooks")
    void shouldInvokePreValidationHooks() {
        // Given
        TestValidators.HookValidator.preHookCalled.set(false);
        registry.register(TestValidators.HookValidator.class);
        EClass element = TestModelFactory.createEClass("TestClass");
        addToModel(element);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        executor.validate(Collections.singletonList(element));

        // Then
        assertTrue(TestValidators.HookValidator.preHookCalled.get(),
            "Pre-validation hook should be called");
    }

    @Test
    @DisplayName("Should invoke post-validation hooks")
    void shouldInvokePostValidationHooks() {
        // Given
        TestValidators.HookValidator.postHookCalled.set(false);
        registry.register(TestValidators.HookValidator.class);
        EClass element = TestModelFactory.createEClass("TestClass");
        addToModel(element);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        executor.validate(Collections.singletonList(element));

        // Then
        assertTrue(TestValidators.HookValidator.postHookCalled.get(),
            "Post-validation hook should be called");
    }

    @Test
    @DisplayName("Should clear caches after validation")
    void shouldClearCachesAfterValidation() {
        // Given
        TestValidators.EClassCountingValidator.callCount.set(0);
        registry.register(TestValidators.EClassCountingValidator.class);
        EClass element = TestModelFactory.createEClass("TestClass");
        addToModel(element);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        executor.validate(Collections.singletonList(element));
        
        // Validate again - cache should be cleared between runs
        executor.validate(Collections.singletonList(element));

        // Then
        assertEquals(2, TestValidators.EClassCountingValidator.callCount.get(),
            "Should execute twice because cache is cleared between validations");
    }

    @Test
    @DisplayName("Should execute all applicable validators")
    void shouldExecuteAllApplicableValidators() {
        // Given
        registry.register(TestValidators.EClassValidator.class);
        EClass elementWithoutName = TestModelFactory.createEClass(null);
        EClass elementWithLowercase = TestModelFactory.createEClass("lowercase");
        addToModel(elementWithoutName);
        addToModel(elementWithLowercase);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Arrays.asList(elementWithoutName, elementWithLowercase));

        // Then
        assertEquals(2, results.size(), "Should have 2 failures");
        assertTrue(results.stream().anyMatch(r -> r.getConstraintName().equals("EClassMustHaveName")));
        assertTrue(results.stream().anyMatch(r -> r.getConstraintName().equals("EClassShouldStartWithCapital")));
    }

    @Test
    @DisplayName("Should validate different element types")
    void shouldValidateDifferentElementTypes() {
        // Given
        registry.register(TestValidators.EClassValidator.class);
        registry.register(TestValidators.EPackageValidator.class);
        
        EClass eClass = TestModelFactory.createEClass(null);
        EPackage ePackage = TestModelFactory.createEPackage("test", null);
        addToModel(eClass);
        addToModel(ePackage);

        // When
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Arrays.asList(eClass, ePackage));

        // Then
        assertEquals(2, results.size(), "Should validate both types");
        assertTrue(results.stream().anyMatch(r -> r.getConstraintName().equals("EClassMustHaveName")));
        assertTrue(results.stream().anyMatch(r -> r.getConstraintName().equals("EPackageMustHaveNsURI")));
    }
}
