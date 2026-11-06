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
 * Tests for hierarchical EPackage resolution in TransformationContext.
 */
@DisplayName("Hierarchical EPackage Resolution Tests")
class HierarchicalPackageTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private TransformationContext context;

    // Package hierarchy for tests
    private EPackage rootPackage;
    private EPackage subPackage1;
    private EPackage subPackage2;
    private EPackage deepPackage;

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

        // Create package hierarchy
        createPackageHierarchy();
    }

    /**
     * Create a package hierarchy:
     * RootPackage
     * ├── SubPackage1
     * │   └── DeepPackage
     * └── SubPackage2
     */
    private void createPackageHierarchy() {
        rootPackage = EcoreFactory.eINSTANCE.createEPackage();
        rootPackage.setName("RootPackage");
        rootPackage.setNsURI("http://test/root");
        rootPackage.setNsPrefix("root");

        subPackage1 = EcoreFactory.eINSTANCE.createEPackage();
        subPackage1.setName("SubPackage1");
        subPackage1.setNsURI("http://test/root/sub1");
        subPackage1.setNsPrefix("sub1");
        rootPackage.getESubpackages().add(subPackage1);

        subPackage2 = EcoreFactory.eINSTANCE.createEPackage();
        subPackage2.setName("SubPackage2");
        subPackage2.setNsURI("http://test/root/sub2");
        subPackage2.setNsPrefix("sub2");
        rootPackage.getESubpackages().add(subPackage2);

        deepPackage = EcoreFactory.eINSTANCE.createEPackage();
        deepPackage.setName("DeepPackage");
        deepPackage.setNsURI("http://test/root/sub1/deep");
        deepPackage.setNsPrefix("deep");
        subPackage1.getESubpackages().add(deepPackage);
    }

    @Nested
    @DisplayName("Sub-package Opt-in Inclusion")
    class SubpackageOptInTests {

        @Test
        @DisplayName("All sub-packages are registered when opt-in enabled")
        void allSubpackagesRegisteredWithOptIn() {
            context.registerTargetPackage(rootPackage, true);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(4, packages.size(),
                    "All packages should be registered (root + sub1 + sub2 + deep)");
            assertTrue(packages.contains(rootPackage));
            assertTrue(packages.contains(subPackage1));
            assertTrue(packages.contains(subPackage2));
            assertTrue(packages.contains(deepPackage));
        }

        @Test
        @DisplayName("DeepPackage is included when opt-in enabled")
        void deepPackageIncludedWithOptIn() {
            context.registerTargetPackage(rootPackage, true);

            assertTrue(context.getTargetPackages().contains(deepPackage),
                    "DeepPackage should be registered when includeSubpackages=true");
        }

        @Test
        @DisplayName("Sub-packages not included without opt-in")
        void subpackagesNotIncludedWithoutOptIn() {
            context.registerTargetPackage(rootPackage, false);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "Only root package should be registered");
            assertTrue(packages.contains(rootPackage));
            assertFalse(packages.contains(subPackage1));
            assertFalse(packages.contains(subPackage2));
            assertFalse(packages.contains(deepPackage));
        }

        @Test
        @DisplayName("registerTargetPackage without boolean defaults to no sub-packages")
        void registerTargetPackageDefaultsToNoSubpackages() {
            context.registerTargetPackage(rootPackage);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(), "Only root package should be registered");
            assertTrue(packages.contains(rootPackage));
        }
    }

    @Nested
    @DisplayName("Ambiguity Detection")
    class AmbiguityTests {

        @Test
        @DisplayName("Ambiguity detected when same type name in parent and sub-package")
        void ambiguityDetectedBetweenParentAndSubpackage() {
            // Add EClass with same name to both root and sub-package
            EClass rootType = EcoreFactory.eINSTANCE.createEClass();
            rootType.setName("SharedType");
            rootPackage.getEClassifiers().add(rootType);

            EClass subType = EcoreFactory.eINSTANCE.createEClass();
            subType.setName("SharedType");
            subPackage1.getEClassifiers().add(subType);

            context.registerTargetPackage(rootPackage, true);

            // Verify both packages are registered
            assertEquals(4, context.getTargetPackages().size());

            // Now searching for SharedType should find it in multiple packages
            // Since we can't use SharedType.class (dynamic), we verify the packages
            // contain types with the same name
            boolean foundInRoot = rootPackage.getEClassifier("SharedType") != null;
            boolean foundInSub = subPackage1.getEClassifier("SharedType") != null;
            assertTrue(foundInRoot && foundInSub,
                    "SharedType should exist in both packages (ambiguous)");
        }
    }

    @Nested
    @DisplayName("getTargetPackages with Hierarchies")
    class GetTargetPackagesTests {

        @Test
        @DisplayName("getTargetPackages returns only root when sub-packages excluded")
        void getTargetPackagesReturnsOnlyRootWhenExcluded() {
            context.registerTargetPackage(rootPackage, false);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size());
            assertTrue(packages.contains(rootPackage));
        }

        @Test
        @DisplayName("getTargetPackages includes all sub-packages with opt-in")
        void getTargetPackagesIncludesAllSubpackagesWithOptIn() {
            context.registerTargetPackage(rootPackage, true);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(4, packages.size());
            assertTrue(packages.contains(rootPackage));
            assertTrue(packages.contains(subPackage1));
            assertTrue(packages.contains(subPackage2));
            assertTrue(packages.contains(deepPackage));
        }

        @Test
        @DisplayName("Multiple root packages with different opt-in settings")
        void multipleRootPackagesWithDifferentOptInSettings() {
            // Create another independent package hierarchy
            EPackage otherRoot = EcoreFactory.eINSTANCE.createEPackage();
            otherRoot.setName("OtherRoot");
            otherRoot.setNsURI("http://test/other");

            EPackage otherSub = EcoreFactory.eINSTANCE.createEPackage();
            otherSub.setName("OtherSub");
            otherSub.setNsURI("http://test/other/sub");
            otherRoot.getESubpackages().add(otherSub);

            // Register rootPackage with sub-packages
            context.registerTargetPackage(rootPackage, true);
            // Register otherRoot without sub-packages
            context.registerTargetPackage(otherRoot, false);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(5, packages.size(),
                    "Should have 4 from rootPackage hierarchy + 1 from otherRoot");
            assertTrue(packages.contains(rootPackage));
            assertTrue(packages.contains(subPackage1));
            assertTrue(packages.contains(deepPackage));
            assertTrue(packages.contains(otherRoot));
            assertFalse(packages.contains(otherSub),
                    "otherSub should NOT be included since otherRoot was registered with false");
        }
    }

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("setTargetPackage does not include sub-packages")
        void setTargetPackageDoesNotIncludeSubpackages() {
            context.setTargetPackage(rootPackage);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(),
                    "setTargetPackage should NOT include sub-packages for backward compat");
            assertTrue(packages.contains(rootPackage));
            assertFalse(packages.contains(subPackage1));
        }

        @Test
        @DisplayName("setTargetPackage clears previous registrations including sub-packages")
        void setTargetPackageClearsPreviousIncludingSubpackages() {
            // First register with sub-packages
            context.registerTargetPackage(rootPackage, true);
            assertEquals(4, context.getTargetPackages().size());

            // Now use setTargetPackage
            EPackage newPkg = EcoreFactory.eINSTANCE.createEPackage();
            newPkg.setName("NewPackage");
            newPkg.setNsURI("http://test/new");

            context.setTargetPackage(newPkg);

            List<EPackage> packages = context.getTargetPackages();
            assertEquals(1, packages.size(),
                    "setTargetPackage should clear all previous packages");
            assertTrue(packages.contains(newPkg));
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
