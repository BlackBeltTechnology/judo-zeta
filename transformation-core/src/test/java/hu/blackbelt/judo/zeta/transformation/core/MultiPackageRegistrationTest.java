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
 * Tests for multi-package registration in TransformationContext.
 */
@DisplayName("Multi-Package Registration Tests")
class MultiPackageRegistrationTest {

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
    @DisplayName("setTargetPackage")
    class SetTargetPackageTests {

        @Test
        @DisplayName("Single package via setTargetPackage does not include sub-packages")
        void singlePackageViaSetTargetPackageDoesNotIncludeSubpackages() {
            // Create a package with sub-packages
            EPackage rootPkg = createPackageWithSubpackages();

            context.setTargetPackage(rootPkg);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "Only root package should be registered");
            assertTrue(packages.contains(rootPkg), "Root package should be registered");
        }

        @Test
        @DisplayName("setTargetPackage clears previous packages")
        void setTargetPackageClearsPreviousPackages() {
            EPackage pkg1 = EcoreFactory.eINSTANCE.createEPackage();
            pkg1.setName("Package1");
            pkg1.setNsURI("http://test/package1");

            EPackage pkg2 = EcoreFactory.eINSTANCE.createEPackage();
            pkg2.setName("Package2");
            pkg2.setNsURI("http://test/package2");

            context.registerTargetPackage(pkg1);
            context.registerTargetPackage(pkg2);

            assertEquals(2, context.getTargetPackages().size());

            EPackage pkg3 = EcoreFactory.eINSTANCE.createEPackage();
            pkg3.setName("Package3");
            pkg3.setNsURI("http://test/package3");

            context.setTargetPackage(pkg3);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "setTargetPackage should clear previous packages");
            assertTrue(packages.contains(pkg3));
            assertFalse(packages.contains(pkg1));
            assertFalse(packages.contains(pkg2));
        }

        @Test
        @DisplayName("setTargetPackage with null clears all packages")
        void setTargetPackageWithNullClearsAllPackages() {
            context.setTargetPackage(EcorePackage.eINSTANCE);
            assertEquals(1, context.getTargetPackages().size());

            context.setTargetPackage(null);

            assertTrue(context.getTargetPackages().isEmpty(),
                    "setTargetPackage(null) should clear all packages");
        }
    }

    @Nested
    @DisplayName("registerTargetPackage")
    class RegisterTargetPackageTests {

        @Test
        @DisplayName("Register multiple packages via registerTargetPackage")
        void registerMultiplePackagesViaRegisterTargetPackage() {
            EPackage pkg1 = EcoreFactory.eINSTANCE.createEPackage();
            pkg1.setName("Package1");
            pkg1.setNsURI("http://test/package1");

            EPackage pkg2 = EcoreFactory.eINSTANCE.createEPackage();
            pkg2.setName("Package2");
            pkg2.setNsURI("http://test/package2");

            context.registerTargetPackage(pkg1);
            context.registerTargetPackage(pkg2);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(2, packages.size());
            assertTrue(packages.contains(pkg1));
            assertTrue(packages.contains(pkg2));
        }

        @Test
        @DisplayName("registerTargetPackage does not include sub-packages by default")
        void registerTargetPackageDoesNotIncludeSubpackagesByDefault() {
            EPackage rootPkg = createPackageWithSubpackages();

            context.registerTargetPackage(rootPkg);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "Only root package should be registered");
            assertTrue(packages.contains(rootPkg));
        }

        @Test
        @DisplayName("registerTargetPackage with null throws IllegalArgumentException")
        void registerTargetPackageWithNullThrowsException() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> context.registerTargetPackage(null));
            assertTrue(ex.getMessage().contains("null"),
                    "Error message should indicate null package");
        }
    }

    @Nested
    @DisplayName("registerTargetPackage with includeSubpackages")
    class RegisterTargetPackageWithSubpackagesTests {

        @Test
        @DisplayName("registerTargetPackage(pkg, true) includes sub-packages")
        void registerTargetPackageWithTrueIncludesSubpackages() {
            EPackage rootPkg = createPackageWithSubpackages();
            EPackage subPkg1 = rootPkg.getESubpackages().get(0);
            EPackage subPkg2 = rootPkg.getESubpackages().get(1);
            EPackage deepPkg = subPkg1.getESubpackages().get(0);

            context.registerTargetPackage(rootPkg, true);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(4, packages.size(),
                    "All packages should be registered (root + 2 sub + 1 deep)");
            assertTrue(packages.contains(rootPkg));
            assertTrue(packages.contains(subPkg1));
            assertTrue(packages.contains(subPkg2));
            assertTrue(packages.contains(deepPkg));
        }

        @Test
        @DisplayName("registerTargetPackage(pkg, false) excludes sub-packages")
        void registerTargetPackageWithFalseExcludesSubpackages() {
            EPackage rootPkg = createPackageWithSubpackages();

            context.registerTargetPackage(rootPkg, false);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "Only root package should be registered");
            assertTrue(packages.contains(rootPkg));
        }

        @Test
        @DisplayName("registerTargetPackage(null, true) throws IllegalArgumentException")
        void registerTargetPackageWithNullAndTrueThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> context.registerTargetPackage(null, true));
        }

        @Test
        @DisplayName("registerTargetPackage(null, false) throws IllegalArgumentException")
        void registerTargetPackageWithNullAndFalseThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> context.registerTargetPackage(null, false));
        }
    }

    @Nested
    @DisplayName("getTargetPackages")
    class GetTargetPackagesTests {

        @Test
        @DisplayName("getTargetPackages returns unmodifiable list")
        void getTargetPackagesReturnsUnmodifiableList() {
            context.setTargetPackage(EcorePackage.eINSTANCE);

            List<EPackage> packages = context.getTargetPackages();

            assertThrows(UnsupportedOperationException.class,
                    () -> packages.add(EcorePackage.eINSTANCE));
        }

        @Test
        @DisplayName("getTargetPackages returns empty list when none registered")
        void getTargetPackagesReturnsEmptyListWhenNoneRegistered() {
            List<EPackage> packages = context.getTargetPackages();

            assertNotNull(packages);
            assertTrue(packages.isEmpty());
        }

        @Test
        @DisplayName("getTargetPackages returns correct packages in registration order")
        void getTargetPackagesReturnsCorrectPackagesInOrder() {
            EPackage pkg1 = EcoreFactory.eINSTANCE.createEPackage();
            pkg1.setName("Package1");
            pkg1.setNsURI("http://test/package1");

            EPackage pkg2 = EcoreFactory.eINSTANCE.createEPackage();
            pkg2.setName("Package2");
            pkg2.setNsURI("http://test/package2");

            context.registerTargetPackage(pkg1);
            context.registerTargetPackage(pkg2);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(2, packages.size());
            assertEquals(pkg1, packages.get(0));
            assertEquals(pkg2, packages.get(1));
        }
    }

    /**
     * Create a package hierarchy:
     * RootPackage
     * ├── SubPackage1
     * │   └── DeepPackage
     * └── SubPackage2
     */
    private EPackage createPackageWithSubpackages() {
        EPackage rootPkg = EcoreFactory.eINSTANCE.createEPackage();
        rootPkg.setName("RootPackage");
        rootPkg.setNsURI("http://test/root");
        rootPkg.setNsPrefix("root");

        EPackage subPkg1 = EcoreFactory.eINSTANCE.createEPackage();
        subPkg1.setName("SubPackage1");
        subPkg1.setNsURI("http://test/root/sub1");
        subPkg1.setNsPrefix("sub1");
        rootPkg.getESubpackages().add(subPkg1);

        EPackage subPkg2 = EcoreFactory.eINSTANCE.createEPackage();
        subPkg2.setName("SubPackage2");
        subPkg2.setNsURI("http://test/root/sub2");
        subPkg2.setNsPrefix("sub2");
        rootPkg.getESubpackages().add(subPkg2);

        EPackage deepPkg = EcoreFactory.eINSTANCE.createEPackage();
        deepPkg.setName("DeepPackage");
        deepPkg.setNsURI("http://test/root/sub1/deep");
        deepPkg.setNsPrefix("deep");
        subPkg1.getESubpackages().add(deepPkg);

        return rootPkg;
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
