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

import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.validation.AbstractValidationTest;
import hu.blackbelt.judo.zeta.validation.TestModelFactory;
import hu.blackbelt.judo.zeta.validation.TestValidators;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExtensionMethodRegistry}.
 * Tests extension method registration, invocation, and caching.
 */
@DisplayName("ExtensionMethodRegistry Tests")
class ExtensionMethodRegistryTest extends AbstractValidationTest {

    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {

        @Test
        @DisplayName("Should register extension methods from annotated class")
        void shouldRegisterExtensionMethodsFromAnnotatedClass() {
            // When
            extensionRegistry.register(TestValidators.EClassExtensions.class);

            // Then
            var extensions = extensionRegistry.getExtensionsFor(EClass.class);
            assertFalse(extensions.isEmpty(), "Should have registered extension methods");
        }

        @Test
        @DisplayName("Should throw exception for class without @ExtensionMethod annotation")
        void shouldThrowExceptionForNonAnnotatedClass() {
            // When/Then
            assertThrows(IllegalArgumentException.class, () -> {
                extensionRegistry.register(TestValidators.InvalidExtensions.class);
            });
        }
    }

    @Nested
    @DisplayName("Invocation Tests")
    class InvocationTests {

        @Test
        @DisplayName("Should invoke extension method")
        void shouldInvokeExtensionMethod() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            String result = extensionRegistry.invoke(element, "getNameUpper");

            // Then
            assertEquals("TESTCLASS", result);
        }

        @Test
        @DisplayName("Should invoke extension method with computed result")
        void shouldInvokeExtensionMethodWithComputedResult() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            EAttribute attr1 = TestModelFactory.createEAttribute("attr1", TestModelFactory.getEString());
            EAttribute attr2 = TestModelFactory.createEAttribute("attr2", TestModelFactory.getEInt());
            element.getEStructuralFeatures().add(attr1);
            element.getEStructuralFeatures().add(attr2);
            addToModel(element);

            // When
            int count = extensionRegistry.invoke(element, "getAttributeCount");

            // Then
            assertEquals(2, count);
        }

        @Test
        @DisplayName("Should handle null return values")
        void shouldHandleNullReturnValues() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass(null); // No name
            addToModel(element);

            // When
            String result = extensionRegistry.invoke(element, "getNameUpper");

            // Then
            assertNull(result);
        }

        @Test
        @DisplayName("Should throw exception for non-existent method")
        void shouldThrowExceptionForNonExistentMethod() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When/Then
            assertThrows(IllegalArgumentException.class, () -> {
                extensionRegistry.invoke(element, "nonExistentMethod");
            });
        }
    }

    @Nested
    @DisplayName("Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("Should cache results for @Cached methods")
        void shouldCacheResultsForCachedMethods() {
            // Given
            TestValidators.EClassExtensions.cachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            extensionRegistry.invoke(element, "cachedGetName");
            extensionRegistry.invoke(element, "cachedGetName");
            extensionRegistry.invoke(element, "cachedGetName");

            // Then
            assertEquals(1, TestValidators.EClassExtensions.cachedCallCount.get(),
                "Cached method should only be called once");
        }

        @Test
        @DisplayName("Should not cache results for non-cached methods")
        void shouldNotCacheResultsForNonCachedMethods() {
            // Given
            TestValidators.EClassExtensions.nonCachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            extensionRegistry.invoke(element, "nonCachedGetName");
            extensionRegistry.invoke(element, "nonCachedGetName");
            extensionRegistry.invoke(element, "nonCachedGetName");

            // Then
            assertEquals(3, TestValidators.EClassExtensions.nonCachedCallCount.get(),
                "Non-cached method should be called each time");
        }

        @Test
        @DisplayName("Should clear cache")
        void shouldClearCache() {
            // Given
            TestValidators.EClassExtensions.cachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            extensionRegistry.invoke(element, "cachedGetName");
            extensionRegistry.clearCache();
            extensionRegistry.invoke(element, "cachedGetName");

            // Then
            assertEquals(2, TestValidators.EClassExtensions.cachedCallCount.get(),
                "Method should be called again after cache clear");
        }

        @Test
        @DisplayName("Should cache per element")
        void shouldCachePerElement() {
            // Given
            TestValidators.EClassExtensions.cachedCallCount.set(0);
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element1 = TestModelFactory.createEClass("Class1");
            EClass element2 = TestModelFactory.createEClass("Class2");
            addToModel(element1);
            addToModel(element2);

            // When
            extensionRegistry.invoke(element1, "cachedGetName");
            extensionRegistry.invoke(element2, "cachedGetName");
            extensionRegistry.invoke(element1, "cachedGetName"); // Should use cache
            extensionRegistry.invoke(element2, "cachedGetName"); // Should use cache

            // Then
            assertEquals(2, TestValidators.EClassExtensions.cachedCallCount.get(),
                "Method should be called once per unique element");
        }
    }

    @Nested
    @DisplayName("Type Hierarchy Tests")
    class TypeHierarchyTests {

        @Test
        @DisplayName("Should find extension methods for exact type")
        void shouldFindExtensionMethodsForExactType() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);
            EClass element = TestModelFactory.createEClass("TestClass");
            addToModel(element);

            // When
            String result = extensionRegistry.invoke(element, "getNameUpper");

            // Then
            assertEquals("TESTCLASS", result);
        }

        @Test
        @DisplayName("Should return extensions for registered type")
        void shouldReturnExtensionsForRegisteredType() {
            // Given
            extensionRegistry.register(TestValidators.EClassExtensions.class);

            // When
            var extensions = extensionRegistry.getExtensionsFor(EClass.class);

            // Then
            assertFalse(extensions.isEmpty(), "Should have extensions for EClass");
            assertTrue(extensions.stream().anyMatch(e -> e.getName().equals("getNameUpper")));
            assertTrue(extensions.stream().anyMatch(e -> e.getName().equals("getAttributeCount")));
        }
    }
}
