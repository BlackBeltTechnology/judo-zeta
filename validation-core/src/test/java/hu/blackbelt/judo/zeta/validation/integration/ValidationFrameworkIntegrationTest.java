package hu.blackbelt.judo.zeta.validation.integration;

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
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the validation framework.
 * Demonstrates end-to-end validation workflows with ECore metamodel elements.
 */
@DisplayName("Validation Framework Integration Tests")
class ValidationFrameworkIntegrationTest extends AbstractValidationTest {

    @Test
    @DisplayName("Complete validation workflow with ECore model")
    void completeValidationWorkflowWithECoreModel() {
        // STEP 1: Create an ECore model
        EPackage testPackage = TestModelFactory.createEPackage("testmodel", "http://test.model");
        
        EClass personClass = TestModelFactory.createEClass("Person");
        EAttribute nameAttr = TestModelFactory.createEAttribute("name", TestModelFactory.getEString());
        EAttribute ageAttr = TestModelFactory.createEAttribute("age", TestModelFactory.getEInt());
        
        personClass.getEStructuralFeatures().add(nameAttr);
        personClass.getEStructuralFeatures().add(ageAttr);
        testPackage.getEClassifiers().add(personClass);
        
        // Add elements with issues
        EClass invalidClass = TestModelFactory.createEClass(null); // No name
        EClass lowercaseClass = TestModelFactory.createEClass("invalid"); // Lowercase name
        testPackage.getEClassifiers().add(invalidClass);
        testPackage.getEClassifiers().add(lowercaseClass);
        
        addToModel(testPackage);

        // STEP 2: Register validators
        registry.register(TestValidators.EClassValidator.class);
        registry.register(TestValidators.EPackageValidator.class);

        // STEP 3: Execute validation
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(
            Arrays.asList(testPackage, personClass, invalidClass, lowercaseClass)
        );

        // STEP 4: Verify results
        assertFalse(results.isEmpty(), "Should have validation failures");
        
        // Verify constraint name and message for missing name
        ValidationResult missingNameResult = results.stream()
            .filter(r -> r.getConstraintName().equals("EClassMustHaveName"))
            .findFirst()
            .orElse(null);
        assertNotNull(missingNameResult, "Should have EClassMustHaveName failure");
        assertEquals("EClass must have a name", missingNameResult.getMessage());
        assertEquals(Severity.ERROR, missingNameResult.getSeverity());
        
        // Verify critique for lowercase name
        ValidationResult lowercaseResult = results.stream()
            .filter(r -> r.getConstraintName().equals("EClassShouldStartWithCapital"))
            .findFirst()
            .orElse(null);
        assertNotNull(lowercaseResult, "Should have EClassShouldStartWithCapital warning");
        assertTrue(lowercaseResult.getMessage().contains("invalid"));
        assertEquals(Severity.WARNING, lowercaseResult.getSeverity());
    }

    @Test
    @DisplayName("Validation with extension methods")
    void validationWithExtensionMethods() {
        // STEP 1: Register extension methods
        extensionRegistry.register(TestValidators.EClassExtensions.class);

        // STEP 2: Create test element
        EClass testClass = TestModelFactory.createEClass("TestClass");
        EAttribute attr1 = TestModelFactory.createEAttribute("attr1", TestModelFactory.getEString());
        EAttribute attr2 = TestModelFactory.createEAttribute("attr2", TestModelFactory.getEInt());
        testClass.getEStructuralFeatures().add(attr1);
        testClass.getEStructuralFeatures().add(attr2);
        addToModel(testClass);

        // STEP 3: Invoke extension methods
        String upperName = extensionRegistry.invoke(testClass, "getNameUpper");
        int attrCount = extensionRegistry.invoke(testClass, "getAttributeCount");

        // STEP 4: Verify results
        assertEquals("TESTCLASS", upperName);
        assertEquals(2, attrCount);
    }

    @Test
    @DisplayName("Validation with caching")
    void validationWithCaching() {
        // STEP 1: Reset counter and register validator
        TestValidators.CountingValidator.callCount.set(0);
        registry.register(TestValidators.CountingValidator.class);

        // STEP 2: Create test element
        EClass testClass = TestModelFactory.createEClass("TestClass");
        addToModel(testClass);
        context.setCurrentElement(testClass);

        // STEP 3: Call satisfies multiple times (should cache)
        boolean result1 = context.satisfies(testClass, "CountingConstraint");
        boolean result2 = context.satisfies(testClass, "CountingConstraint");
        boolean result3 = context.satisfies(testClass, "CountingConstraint");

        // STEP 4: Verify caching
        assertEquals(1, TestValidators.CountingValidator.callCount.get(),
            "Should only execute once due to caching");
        assertTrue(result1);
        assertTrue(result2);
        assertTrue(result3);

        // STEP 5: Clear cache and verify re-execution
        context.clearSatisfiesCache();
        context.satisfies(testClass, "CountingConstraint");
        assertEquals(2, TestValidators.CountingValidator.callCount.get(),
            "Should execute again after cache clear");
    }

    @Test
    @DisplayName("Validation with hooks")
    void validationWithHooks() {
        // STEP 1: Reset hook flags
        TestValidators.HookValidator.preHookCalled.set(false);
        TestValidators.HookValidator.postHookCalled.set(false);

        // STEP 2: Register validator with hooks
        registry.register(TestValidators.HookValidator.class);

        // STEP 3: Create test element and validate
        EClass testClass = TestModelFactory.createEClass("TestClass");
        addToModel(testClass);

        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        executor.validate(Arrays.asList(testClass));

        // STEP 4: Verify hooks were called
        assertTrue(TestValidators.HookValidator.preHookCalled.get(),
            "Pre-validation hook should be called");
        assertTrue(TestValidators.HookValidator.postHookCalled.get(),
            "Post-validation hook should be called");
    }

    @Test
    @DisplayName("Validation with guards")
    void validationWithGuards() {
        // STEP 1: Register guarded validator
        registry.register(TestValidators.GuardedValidator.class);

        // STEP 2: Create non-abstract class (guard should fail, validation should not run)
        EClass concreteClass = TestModelFactory.createEClass("ConcreteClass");
        concreteClass.setAbstract(false);
        addToModel(concreteClass);

        // STEP 3: Validate
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Arrays.asList(concreteClass));

        // STEP 4: Verify guard prevented validation
        assertTrue(results.isEmpty(), 
            "Guard should prevent validation from running on non-abstract class");

        // STEP 5: Create abstract class (guard should pass, but our test validator passes too)
        EClass abstractClass = TestModelFactory.createEClass("AbstractClass");
        abstractClass.setAbstract(true);
        addToModel(abstractClass);

        results = executor.validate(Arrays.asList(abstractClass));
        
        // Note: GuardedValidator returns a failing result when guard passes,
        // but since it's a test validator we expect it to fail
        assertFalse(results.isEmpty(), 
            "Validation should run when guard passes for abstract class");
    }

    @Test
    @DisplayName("Query all instances of type")
    void queryAllInstancesOfType() {
        // STEP 1: Create multiple EClass instances
        EClass class1 = TestModelFactory.createEClass("Class1");
        EClass class2 = TestModelFactory.createEClass("Class2");
        EClass class3 = TestModelFactory.createEClass("Class3");
        
        addToModel(class1);
        addToModel(class2);
        addToModel(class3);

        // STEP 2: Query all EClass instances
        java.util.Collection<EClass> allClasses = context.getAllInstances(EClass.class);

        // STEP 3: Verify
        assertEquals(3, allClasses.size(), "Should find all 3 EClass instances");
        assertTrue(allClasses.contains(class1));
        assertTrue(allClasses.contains(class2));
        assertTrue(allClasses.contains(class3));
    }

    @Test
    @DisplayName("Mixed constraints and critiques")
    void mixedConstraintsAndCritiques() {
        // STEP 1: Register validators with both constraints and critiques
        registry.register(TestValidators.EClassValidator.class);

        // STEP 2: Create element that triggers both
        EClass lowercaseClass = TestModelFactory.createEClass("lowercase");
        addToModel(lowercaseClass);

        // STEP 3: Validate
        ValidationExecutor executor = new ValidationExecutor(registry, context, false);
        List<ValidationResult> results = executor.validate(Arrays.asList(lowercaseClass));

        // STEP 4: Verify we get the critique (warning)
        assertEquals(1, results.size());
        ValidationResult result = results.get(0);
        assertEquals("EClassShouldStartWithCapital", result.getConstraintName());
        assertEquals(Severity.WARNING, result.getSeverity());
        assertTrue(result.getMessage().contains("lowercase"));
        assertEquals("EClass name 'lowercase' should start with capital letter", result.getMessage());
    }
}
