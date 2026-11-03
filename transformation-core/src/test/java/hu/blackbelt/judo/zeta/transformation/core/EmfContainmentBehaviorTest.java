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

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test EMF containment behavior to understand the baseline behavior.
 */
@DisplayName("EMF Containment Behavior Tests")
class EmfContainmentBehaviorTest {

    private static final Logger log = LoggerFactory.getLogger(EmfContainmentBehaviorTest.class);

    private ResourceSet resourceSet;
    private Resource resource;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
        resourceSet = new ResourceSetImpl();
        resource = resourceSet.createResource(URI.createURI("test://test.xmi"));
    }

    /**
     * IMPORTANT DISCOVERY: EMF does NOT automatically remove elements from Resource.contents
     * when they are added to a containment reference!
     *
     * This test documents the actual EMF behavior, which impacts how autoAddRootElements
     * should be implemented in the transformation framework.
     */
    @Test
    @DisplayName("EMF does NOT remove element from Resource.contents when added to containment")
    void emfDoesNotRemoveFromResourceWhenAddedToContainment() {
        // Create elements
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("TestPackage");

        EClass cls = EcoreFactory.eINSTANCE.createEClass();
        cls.setName("TestClass");

        // First add class to resource contents (simulating autoAddRootElements)
        resource.getContents().add(cls);
        log.info("After adding class to resource.contents: size={}", resource.getContents().size());
        assertEquals(1, resource.getContents().size(), "Class should be in resource");
        assertNull(cls.eContainer(), "Class should have no container yet");

        // Now add class to package's containment reference
        pkg.getEClassifiers().add(cls);
        log.info("After adding class to package.eClassifiers: resource.contents size={}",
                resource.getContents().size());
        log.info("  class.eContainer={}", cls.eContainer());
        log.info("  Is class in resource.contents? {}", resource.getContents().contains(cls));

        // ACTUAL EMF BEHAVIOR: class is STILL in resource.contents even though it has a container
        // This is by design in EMF - Resource.contents is not automatically cleaned up
        assertTrue(resource.getContents().contains(cls),
                "EMF does NOT automatically remove from resource.contents when added to containment");
        assertEquals(pkg, cls.eContainer(),
                "Class is contained by package (eContainer is set)");

        // Add package to resource
        resource.getContents().add(pkg);
        log.info("After adding package to resource: size={}", resource.getContents().size());

        // Resource now contains BOTH the class AND the package!
        assertEquals(2, resource.getContents().size(),
                "Both class and package are in resource.contents (EMF does not clean up)");

        // This means transformation framework MUST manually handle cleanup
    }

    /**
     * Demonstrates that the same element can be in both Resource.contents AND
     * have an eContainer - EMF allows this inconsistent state.
     */
    @Test
    @DisplayName("EMF allows inconsistent state - element in contents with eContainer")
    void emfAllowsInconsistentState() {
        // Create elements
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("TestPackage");

        EClass cls = EcoreFactory.eINSTANCE.createEClass();
        cls.setName("TestClass");

        // Add package and class to resource
        resource.getContents().add(pkg);
        resource.getContents().add(cls);

        // Set containment
        pkg.getEClassifiers().add(cls);

        log.info("After containment: resource.contents size={}", resource.getContents().size());

        // EMF allows this "inconsistent" state
        assertEquals(2, resource.getContents().size(),
                "Both elements still in resource.contents");
        assertEquals(pkg, cls.eContainer(),
                "Class has package as eContainer");
        assertTrue(resource.getContents().contains(cls),
                "Class is still in resource.contents despite having eContainer");

        // This is why the transformation framework must manually remove elements
        // from resource.contents when they become contained
    }
}
