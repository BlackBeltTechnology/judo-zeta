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

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidationContext}.
 * Tests context operations, caching, element queries, and thread safety.
 */
@DisplayName("ValidationContext Tests")
class ValidationContextTest extends AbstractValidationTest {

    @Nested
    @DisplayName("Current Element Tests")
    class CurrentElementTests {

        @Test
        @DisplayName("Should get and set current element")
        void shouldGetAndSetCurrentElement() {
            // Given
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            context.setCurrentElement(element);

            // Then
            assertSame(element, context.getCurrentElement());
        }

        @Test
        @DisplayName("Should return null when no current element set")
        void shouldReturnNullWhenNoCurrentElementSet() {
            // When/Then
            assertNull(context.getCurrentElement());
        }

        @Test
        @DisplayName("Should clear current element")
        void shouldClearCurrentElement() {
            // Given
            EClass element = TestModelFactory.createEClass("TestClass");
            context.setCurrentElement(element);

            // When
            context.clearCurrentElement();

            // Then
            assertNull(context.getCurrentElement());
        }

        @Test
        @DisplayName("Current element should be thread-local")
        void currentElementShouldBeThreadLocal() throws InterruptedException {
            // Given
            EClass element1 = TestModelFactory.createEClass("Class1");
            EClass element2 = TestModelFactory.createEClass("Class2");
            addToModel(element1);
            addToModel(element2);

            AtomicReference<EClass> threadElement = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            // When
            context.setCurrentElement(element1);

            Thread otherThread = new Thread(() -> {
                context.setCurrentElement(element2);
                threadElement.set((EClass) context.getCurrentElement());
                latch.countDown();
            });
            otherThread.start();
            latch.await();

            // Then
            assertSame(element1, context.getCurrentElement(), "Main thread should have element1");
            assertSame(element2, threadElement.get(), "Other thread should have element2");
        }
    }

    @Nested
    @DisplayName("Satisfies Cache Tests")
    class SatisfiesCacheTests {

        @Test
        @DisplayName("Should evaluate satisfies for passing constraint")
        void shouldEvaluateSatisfiesForPassingConstraint() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass element = TestModelFactory.createEClass("ValidName");
            addToModel(element);
            context.setCurrentElement(element);

            // When
            boolean result = context.satisfies("EClassMustHaveName");

            // Then
            assertTrue(result, "Constraint should be satisfied for element with name");
        }

        @Test
        @DisplayName("Should evaluate satisfies for failing constraint")
        void shouldEvaluateSatisfiesForFailingConstraint() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass element = TestModelFactory.createEClass(null);
            addToModel(element);
            context.setCurrentElement(element);

            // When
            boolean result = context.satisfies("EClassMustHaveName");

            // Then
            assertFalse(result, "Constraint should NOT be satisfied for element without name");
        }

        @Test
        @DisplayName("Should cache satisfies results")
        void shouldCacheSatisfiesResults() {
            // Given
            TestValidators.CountingValidator.callCount.set(0);
            registry.register(TestValidators.CountingValidator.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);
            context.setCurrentElement(element);

            // When
            context.satisfies(element, "CountingConstraint");
            context.satisfies(element, "CountingConstraint");
            context.satisfies(element, "CountingConstraint");

            // Then
            assertEquals(1, TestValidators.CountingValidator.callCount.get(),
                "Should only execute once due to caching");
        }

        @Test
        @DisplayName("Should clear satisfies cache")
        void shouldClearSatisfiesCache() {
            // Given
            TestValidators.CountingValidator.callCount.set(0);
            registry.register(TestValidators.CountingValidator.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);
            context.setCurrentElement(element);

            // When
            context.satisfies(element, "CountingConstraint");
            context.clearSatisfiesCache();
            context.satisfies(element, "CountingConstraint");

            // Then
            assertEquals(2, TestValidators.CountingValidator.callCount.get(),
                "Should execute twice after cache clear");
        }

        @Test
        @DisplayName("Should evaluate satisfies for specific element")
        void shouldEvaluateSatisfiesForSpecificElement() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass validElement = TestModelFactory.createEClass("ValidName");
            EClass invalidElement = TestModelFactory.createEClass(null);
            addToModel(validElement);
            addToModel(invalidElement);

            // When
            boolean validResult = context.satisfies(validElement, "EClassMustHaveName");
            boolean invalidResult = context.satisfies(invalidElement, "EClassMustHaveName");

            // Then
            assertTrue(validResult, "Valid element should satisfy constraint");
            assertFalse(invalidResult, "Invalid element should NOT satisfy constraint");
        }

        @Test
        @DisplayName("Should return true for non-existent constraint")
        void shouldReturnTrueForNonExistentConstraint() {
            // Given
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);
            context.setCurrentElement(element);

            // When
            boolean result = context.satisfies("NonExistentConstraint");

            // Then
            assertTrue(result, "Non-existent constraint should be assumed satisfied");
        }
    }

    @Nested
    @DisplayName("AllSatisfy Tests")
    class AllSatisfyTests {

        @Test
        @DisplayName("Should return true when all elements satisfy constraint")
        void shouldReturnTrueWhenAllElementsSatisfyConstraint() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass element1 = TestModelFactory.createEClass("Class1");
            EClass element2 = TestModelFactory.createEClass("Class2");
            EClass element3 = TestModelFactory.createEClass("Class3");
            addToModel(element1);
            addToModel(element2);
            addToModel(element3);

            // When
            boolean result = context.allSatisfy(
                Arrays.asList(element1, element2, element3),
                "EClassMustHaveName"
            );

            // Then
            assertTrue(result, "All elements should satisfy constraint");
        }

        @Test
        @DisplayName("Should return false when any element fails constraint")
        void shouldReturnFalseWhenAnyElementFailsConstraint() {
            // Given
            registry.register(TestValidators.EClassValidator.class);
            EClass element1 = TestModelFactory.createEClass("Class1");
            EClass element2 = TestModelFactory.createEClass(null); // No name
            EClass element3 = TestModelFactory.createEClass("Class3");
            addToModel(element1);
            addToModel(element2);
            addToModel(element3);

            // When
            boolean result = context.allSatisfy(
                Arrays.asList(element1, element2, element3),
                "EClassMustHaveName"
            );

            // Then
            assertFalse(result, "Should return false when any element fails");
        }
    }

    @Nested
    @DisplayName("GetAllInstances Tests")
    class GetAllInstancesTests {

        @Test
        @DisplayName("Should get all instances of type from ResourceSet")
        void shouldGetAllInstancesOfTypeFromResourceSet() {
            // Given
            EClass class1 = TestModelFactory.createEClass("Class1");
            EClass class2 = TestModelFactory.createEClass("Class2");
            EClass class3 = TestModelFactory.createEClass("Class3");
            addToModel(class1);
            addToModel(class2);
            addToModel(class3);

            // When
            Collection<EClass> instances = context.getAllInstances(EClass.class);

            // Then
            assertEquals(3, instances.size(), "Should find all 3 EClass instances");
            assertTrue(instances.contains(class1));
            assertTrue(instances.contains(class2));
            assertTrue(instances.contains(class3));
        }

        @Test
        @DisplayName("Should return empty collection when no instances exist")
        void shouldReturnEmptyCollectionWhenNoInstancesExist() {
            // When
            Collection<EPackage> instances = context.getAllInstances(EPackage.class);

            // Then
            assertTrue(instances.isEmpty(), "Should return empty collection");
        }

        @Test
        @DisplayName("Should only return instances of exact type")
        void shouldOnlyReturnInstancesOfExactType() {
            // Given
            EClass eClass = TestModelFactory.createEClass("TestClass");
            EPackage ePackage = TestModelFactory.createEPackage("test", "http://test");
            addToModel(eClass);
            addToModel(ePackage);

            // When
            Collection<EClass> classInstances = context.getAllInstances(EClass.class);
            Collection<EPackage> packageInstances = context.getAllInstances(EPackage.class);

            // Then
            assertEquals(1, classInstances.size());
            assertEquals(1, packageInstances.size());
            assertTrue(classInstances.contains(eClass));
            assertTrue(packageInstances.contains(ePackage));
        }
    }

    @Nested
    @DisplayName("Extension Method Invocation Tests")
    class ExtensionMethodInvocationTests {

        @Test
        @DisplayName("Should invoke extension method via context")
        void shouldInvokeExtensionMethodViaContext() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            String result = context.call(element, "getNameUpper");

            // Then
            assertEquals("TESTCLASS", result);
        }

        @Test
        @DisplayName("Should invoke extension method on current element")
        void shouldInvokeExtensionMethodOnCurrentElement() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);
            context.setCurrentElement(element);

            // When
            String result = context.call("getNameUpper");

            // Then
            assertEquals("TESTCLASS", result);
        }

        @Test
        @DisplayName("Should clear extension cache")
        void shouldClearExtensionCache() {
            // Given
            TestValidators.EClassExtensions.cachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            context.call(element, "cachedGetName");
            context.call(element, "cachedGetName");
            context.clearExtensionCache();
            context.call(element, "cachedGetName");

            // Then
            assertEquals(2, TestValidators.EClassExtensions.cachedCallCount.get(),
                "Should execute twice - once before clear, once after");
        }
    }

    @Nested
    @DisplayName("Attribute Tests")
    class AttributeTests {

        @Test
        @DisplayName("Should set and get custom attributes")
        void shouldSetAndGetCustomAttributes() {
            // When
            context.setAttribute("key1", "value1");
            context.setAttribute("key2", 42);

            // Then
            assertEquals("value1", context.getAttribute("key1"));
            assertEquals(42, (Integer) context.getAttribute("key2"));
        }

        @Test
        @DisplayName("Should return null for non-existent attribute")
        void shouldReturnNullForNonExistentAttribute() {
            // When
            Object result = context.getAttribute("nonExistent");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("Should overwrite existing attribute")
        void shouldOverwriteExistingAttribute() {
            // Given
            context.setAttribute("key", "original");

            // When
            context.setAttribute("key", "updated");

            // Then
            assertEquals("updated", context.getAttribute("key"));
        }
    }

    @Nested
    @DisplayName("Accessor Tests")
    class AccessorTests {

        @Test
        @DisplayName("Should return model provider")
        void shouldReturnModelProvider() {
            // When/Then
            assertNotNull(context.getModelProvider());
        }

        @Test
        @DisplayName("Should return resource set")
        void shouldReturnResourceSet() {
            // When/Then
            assertNotNull(context.getResourceSet());
            assertSame(resourceSet, context.getResourceSet());
        }
    }
}
