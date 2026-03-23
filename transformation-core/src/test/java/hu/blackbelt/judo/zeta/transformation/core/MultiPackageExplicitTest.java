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
 * Tests for explicit package specification in TransformationContext.
 */
@DisplayName("Multi-Package Explicit Specification Tests")
class MultiPackageExplicitTest {

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

    @Nested
    @DisplayName("createTarget with Explicit Package")
    class CreateTargetExplicitPackageTests {

        @Test
        @DisplayName("createTarget(Class, EPackage) creates element in specified package")
        void createTargetWithExplicitPackageSuccess() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EClass result = context.createTarget(EClass.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }

        @Test
        @DisplayName("createTarget(Class, EPackage) resolves ambiguity")
        void createTargetWithExplicitPackageResolvesAmbiguity() {
            // Create a package with EClass (same name as in EcorePackage)
            EPackage customPkg = EcoreFactory.eINSTANCE.createEPackage();
            customPkg.setName("CustomPackage");
            customPkg.setNsURI("http://test/custom");
            EClass customEClass = EcoreFactory.eINSTANCE.createEClass();
            customEClass.setName("EClass");
            customPkg.getEClassifiers().add(customEClass);

            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.registerTargetPackage(customPkg);

            // Without explicit package, this would be ambiguous
            // With explicit package, it should work
            EClass result = context.createTarget(EClass.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage(),
                    "Element should be created from the explicitly specified package");
        }

        @Test
        @DisplayName("createTarget(Class, EPackage) with wrong package throws exception")
        void createTargetWithWrongPackageThrowsException() {
            // Create an empty package
            EPackage emptyPkg = EcoreFactory.eINSTANCE.createEPackage();
            emptyPkg.setName("EmptyPackage");
            emptyPkg.setNsURI("http://test/empty");

            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.registerTargetPackage(emptyPkg);

            // Try to create EClass from emptyPkg (which doesn't contain it)
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.createTarget(EClass.class, emptyPkg));

            assertTrue(ex.getMessage().contains("EClass"),
                    "Error should mention the type: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("http://test/empty"),
                    "Error should mention the package: " + ex.getMessage());
        }

        @Test
        @DisplayName("createTarget(Class, null EPackage) throws IllegalArgumentException")
        void createTargetWithNullPackageThrowsException() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.createTarget(EClass.class, (EPackage) null));

            assertTrue(ex.getMessage().toLowerCase().contains("null") ||
                            ex.getMessage().toLowerCase().contains("package"),
                    "Error message should indicate null package: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("create with Explicit Package")
    class CreateExplicitPackageTests {

        @Test
        @DisplayName("create(Class, EPackage) creates element in specified package")
        void createWithExplicitPackageSuccess() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            EAttribute result = context.create(EAttribute.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EAttribute", result.eClass().getName());
            // create() should NOT add to any resource
            assertNull(result.eContainer(), "create() should not set containment");
        }

        @Test
        @DisplayName("create(Class, EPackage) resolves ambiguity")
        void createWithExplicitPackageResolvesAmbiguity() {
            // Create a package with EAttribute (same name as in EcorePackage)
            EPackage customPkg = EcoreFactory.eINSTANCE.createEPackage();
            customPkg.setName("CustomPackage");
            customPkg.setNsURI("http://test/custom");
            EClass customEAttr = EcoreFactory.eINSTANCE.createEClass();
            customEAttr.setName("EAttribute");
            customPkg.getEClassifiers().add(customEAttr);

            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.registerTargetPackage(customPkg);

            // With explicit package, it should work
            EAttribute result = context.create(EAttribute.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EAttribute", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage(),
                    "Element should be created from the explicitly specified package");
        }

        @Test
        @DisplayName("create(Class, null) throws IllegalArgumentException")
        void createWithNullPackageThrowsException() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.create(EClass.class, null));

            assertTrue(ex.getMessage().toLowerCase().contains("null") ||
                            ex.getMessage().toLowerCase().contains("package"),
                    "Error message should indicate null package: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Explicit Package Without Registration")
    class ExplicitPackageWithoutRegistrationTests {

        @Test
        @DisplayName("createTarget works with explicit package even when different package registered")
        void createTargetWorksWithExplicitPackageEvenIfNotRegistered() {
            // Register an empty package
            EPackage emptyPkg = EcoreFactory.eINSTANCE.createEPackage();
            emptyPkg.setName("EmptyPackage");
            emptyPkg.setNsURI("http://test/empty");
            context.registerTargetPackage(emptyPkg);

            // Can still create from EcorePackage by specifying it explicitly
            EClass result = context.createTarget(EClass.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EClass", result.eClass().getName());
            assertEquals(EcorePackage.eINSTANCE, result.eClass().getEPackage());
        }

        @Test
        @DisplayName("create works with explicit package even when different package registered")
        void createWorksWithExplicitPackageEvenIfNotRegistered() {
            EPackage emptyPkg = EcoreFactory.eINSTANCE.createEPackage();
            emptyPkg.setName("EmptyPackage");
            emptyPkg.setNsURI("http://test/empty");
            context.registerTargetPackage(emptyPkg);

            EAttribute result = context.create(EAttribute.class, EcorePackage.eINSTANCE);

            assertNotNull(result);
            assertEquals("EAttribute", result.eClass().getName());
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
