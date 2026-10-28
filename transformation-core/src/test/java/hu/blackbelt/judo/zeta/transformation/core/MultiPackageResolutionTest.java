package hu.blackbelt.judo.zeta.transformation.core;

/*-
 * #%L
 * Judo :: Zeta :: Transformation Core
 * %%
 * Copyright (C) 2018 - 2024 BlackBelt Technology
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
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for automatic package resolution in TransformationContext.
 *
 * <p>The framework supports two resolution modes:</p>
 * <ul>
 *   <li>Auto-discovery: For generated EMF types (e.g., EClass.class), the EPackage
 *       is discovered automatically from the Java package structure.</li>
 *   <li>Registered packages: For dynamic EMF models, packages must be registered
 *       explicitly.</li>
 * </ul>
 */
@DisplayName("Multi-Package Resolution Tests")
class MultiPackageResolutionTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
    }

    /**
     * Fake interface that doesn't have a corresponding EPackage.
     * Used to test error handling when auto-discovery fails.
     */
    interface NonExistentType extends EObject {}

    @Nested
    @DisplayName("Single Package Resolution")
    class SinglePackageResolutionTests {

        @Test
        @DisplayName("createTarget resolves type from single registered package")
        void createTargetResolvesTypeFromSinglePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EClass result = context.createTarget(EClass.class);

            assertNotNull(result);
            assertTrue(result instanceof EClass);
        }

        @Test
        @DisplayName("create resolves type from single registered package")
        void createResolvesTypeFromSinglePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EAttribute result = context.create(EAttribute.class);

            assertNotNull(result);
            assertTrue(result instanceof EAttribute);
        }
    }

    @Nested
    @DisplayName("Multi-Package Automatic Resolution")
    class MultiPackageAutomaticResolutionTests {

        @Test
        @DisplayName("createTarget resolves type from correct package when type is unique")
        void createTargetResolvesTypeFromCorrectPackageWhenUnique() {
            // Register EcorePackage
            context.registerTargetPackage(EcorePackage.eINSTANCE);

            // EClass should be created from EcorePackage
            EClass result = context.createTarget(EClass.class);
            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }

        @Test
        @DisplayName("create resolves type from correct package when type is unique")
        void createResolvesTypeFromCorrectPackageWhenUnique() {
            context.registerTargetPackage(EcorePackage.eINSTANCE);

            EReference result = context.create(EReference.class);
            assertNotNull(result);
            assertEquals("EReference", result.eClass().getName());
        }
    }

    @Nested
    @DisplayName("Auto-Discovery (Generated Metamodels)")
    class AutoDiscoveryTests {

        @Test
        @DisplayName("createTarget auto-discovers EPackage without registration")
        void createTargetAutoDiscoversEPackageWithoutRegistration() {
            // No package registered - auto-discovery should find EcorePackage for EClass.class
            EClass result = context.createTarget(EClass.class);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }

        @Test
        @DisplayName("create auto-discovers EPackage without registration")
        void createAutoDiscoversEPackageWithoutRegistration() {
            // No package registered - auto-discovery should work
            EAttribute result = context.create(EAttribute.class);

            assertNotNull(result);
            assertEquals("EAttribute", result.eClass().getName());
        }

        @Test
        @DisplayName("Auto-discovery caches discovered packages")
        void autoDiscoveryCachesPackages() {
            // Create multiple elements - should all use cached EPackage
            EClass c1 = context.createTarget(EClass.class);
            EClass c2 = context.createTarget(EClass.class);
            EAttribute a1 = context.createTarget(EAttribute.class);

            assertNotNull(c1);
            assertNotNull(c2);
            assertNotNull(a1);
        }
    }

    @Nested
    @DisplayName("Error Handling (Dynamic Models)")
    class ErrorHandlingTests {

        @Test
        @DisplayName("createTarget throws when type cannot be auto-discovered and no packages registered")
        void createTargetThrowsWhenAutoDiscoveryFailsAndNoPackages() {
            // NonExistentType can't be auto-discovered and no packages are registered
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.createTarget(NonExistentType.class));

            assertTrue(ex.getMessage().contains("NonExistentType"),
                    "Error message should mention the type name: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("registerTargetPackage") ||
                            ex.getMessage().contains("For generated metamodels"),
                    "Error message should suggest solution: " + ex.getMessage());
        }

        @Test
        @DisplayName("create throws when type cannot be auto-discovered and no packages registered")
        void createThrowsWhenAutoDiscoveryFailsAndNoPackages() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.create(NonExistentType.class));

            assertTrue(ex.getMessage().contains("NonExistentType"));
        }

        @Test
        @DisplayName("createTarget throws when type not found in single registered package")
        void createTargetThrowsWhenTypeNotFoundInSinglePackage() {
            // Create an empty package with no classifiers
            EPackage emptyPkg = EcoreFactory.eINSTANCE.createEPackage();
            emptyPkg.setName("EmptyPackage");
            emptyPkg.setNsURI("http://test/empty");

            context.registerTargetPackage(emptyPkg);

            // NonExistentType can't be auto-discovered and doesn't exist in emptyPkg
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.createTarget(NonExistentType.class));

            assertTrue(ex.getMessage().contains("NonExistentType"),
                    "Error message should mention the type name");
            assertTrue(ex.getMessage().contains("http://test/empty"),
                    "Error message should mention the package that was searched");
        }

        @Test
        @DisplayName("createTarget throws when type not found in any registered package")
        void createTargetThrowsWhenTypeNotFoundInAnyPackage() {
            // Create two empty packages
            EPackage pkg1 = EcoreFactory.eINSTANCE.createEPackage();
            pkg1.setName("Package1");
            pkg1.setNsURI("http://test/pkg1");

            EPackage pkg2 = EcoreFactory.eINSTANCE.createEPackage();
            pkg2.setName("Package2");
            pkg2.setNsURI("http://test/pkg2");

            context.registerTargetPackage(pkg1);
            context.registerTargetPackage(pkg2);

            // NonExistentType can't be auto-discovered and doesn't exist in either package
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.createTarget(NonExistentType.class));

            assertTrue(ex.getMessage().contains("NonExistentType"),
                    "Error message should mention the type name");
            assertTrue(ex.getMessage().contains("http://test/pkg1"),
                    "Error message should list first package");
            assertTrue(ex.getMessage().contains("http://test/pkg2"),
                    "Error message should list second package");
        }

        @Test
        @DisplayName("createTarget uses auto-discovery even when incompatible packages are registered")
        void createTargetUsesAutoDiscoveryEvenWithIncompatiblePackages() {
            // Register an empty package that doesn't contain EClass
            EPackage emptyPkg = EcoreFactory.eINSTANCE.createEPackage();
            emptyPkg.setName("EmptyPackage");
            emptyPkg.setNsURI("http://test/empty");
            context.registerTargetPackage(emptyPkg);

            // Auto-discovery should still find EcorePackage for EClass.class
            EClass result = context.createTarget(EClass.class);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }

        @Test
        @DisplayName("Ambiguity in registered packages doesn't affect auto-discovered types")
        void ambiguityInRegisteredPackagesDoesNotAffectAutoDiscovery() {
            // Create a custom package with an EClass named "EClass"
            EPackage customPkg = EcoreFactory.eINSTANCE.createEPackage();
            customPkg.setName("CustomPackage");
            customPkg.setNsURI("http://test/custom");
            EClass customEClass = EcoreFactory.eINSTANCE.createEClass();
            customEClass.setName("EClass"); // Same name as in EcorePackage
            customPkg.getEClassifiers().add(customEClass);

            context.registerTargetPackage(customPkg);

            // Auto-discovery finds EcorePackage for EClass.class, registered packages ignored
            EClass result = context.createTarget(EClass.class);

            assertNotNull(result);
            // Result is from EcorePackage (auto-discovered), not customPkg
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }
    }

    @Nested
    @DisplayName("Using Built-in EcorePackage")
    class BuiltInEcorePackageTests {

        @Test
        @DisplayName("Can create EClass from EcorePackage")
        void canCreateEClassFromEcorePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EClass result = context.createTarget(EClass.class);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
        }

        @Test
        @DisplayName("Can create EAttribute from EcorePackage")
        void canCreateEAttributeFromEcorePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EAttribute result = context.createTarget(EAttribute.class);

            assertNotNull(result);
            assertEquals("EAttribute", result.eClass().getName());
        }

        @Test
        @DisplayName("Can create EPackage from EcorePackage")
        void canCreateEPackageFromEcorePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EPackage result = context.createTarget(EPackage.class);

            assertNotNull(result);
            assertEquals("EPackage", result.eClass().getName());
        }

        @Test
        @DisplayName("Can create EReference from EcorePackage")
        void canCreateEReferenceFromEcorePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EReference result = context.createTarget(EReference.class);

            assertNotNull(result);
            assertEquals("EReference", result.eClass().getName());
        }

        @Test
        @DisplayName("Can create EDataType from EcorePackage")
        void canCreateEDataTypeFromEcorePackage() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EDataType result = context.createTarget(EDataType.class);

            assertNotNull(result);
            assertEquals("EDataType", result.eClass().getName());
        }
    }

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    if (type.isInstance(obj)) {
                        results.add((T) obj);
                    }
                }
            }
            return results;
        }
    }
}
