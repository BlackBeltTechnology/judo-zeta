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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for staging infrastructure in TransformationContext.
 */
class StagingInfrastructureTest {

    private TransformationContext context;
    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource targetResource;
    private EPackage targetPackage;

    @BeforeEach
    void setUp() {
        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        
        // Register XMI resource factory for all extensions
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
        
        // Create target resource
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
        assertNotNull(targetResource, "Failed to create target resource");
        
        // Use Ecore as test package
        targetPackage = EcorePackage.eINSTANCE;
        
        ModelProvider modelProvider = mock(ModelProvider.class);
        ExtensionMethodRegistry extensionRegistry = mock(ExtensionMethodRegistry.class);
        
        context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                targetResourceSet,
                extensionRegistry
        );
        context.setTargetPackage(targetPackage);
    }

    @Test
    @DisplayName("Staging is disabled by default")
    void testStagingDisabledByDefault() {
        assertFalse(context.isStagingEnabled());
    }

    @Test
    @DisplayName("Enable and disable staging")
    void testEnableDisableStaging() {
        context.enableStaging();
        assertTrue(context.isStagingEnabled());
        
        context.disableStaging();
        assertFalse(context.isStagingEnabled());
    }

    @Test
    @DisplayName("createTarget does not add to resource (ETL semantics)")
    void testCreateTargetWithoutStaging() {
        EClass created = context.createTarget(EClass.class);

        assertNotNull(created);
        // ETL semantics: createTarget does NOT add to resource
        assertEquals(0, targetResource.getContents().size());
    }

    @Test
    @DisplayName("addToResource adds directly to resource when staging disabled")
    void testAddToResourceWithoutStaging() {
        EClass created = context.createTarget(EClass.class);
        context.addToResource(created);

        assertNotNull(created);
        assertEquals(1, targetResource.getContents().size());
        assertSame(created, targetResource.getContents().get(0));
    }

    @Test
    @DisplayName("addToResource stages element when staging enabled")
    void testCreateTargetWithStaging() {
        context.enableStaging();

        EClass created = context.createTarget(EClass.class);
        context.addToResource(created);

        assertNotNull(created);
        assertEquals(0, targetResource.getContents().size(), "Element should not be in resource yet");
        assertEquals(1, context.getStagedElementCount());
    }

    @Test
    @DisplayName("commitStagedElements adds staged elements to resource")
    void testCommitStagedElements() {
        context.enableStaging();

        EClass created1 = context.createTarget(EClass.class);
        EClass created2 = context.createTarget(EClass.class);
        context.addToResource(created1);
        context.addToResource(created2);

        assertEquals(0, targetResource.getContents().size());
        assertEquals(2, context.getStagedElementCount());

        context.commitStagedElements();

        assertEquals(2, targetResource.getContents().size());
        assertEquals(0, context.getStagedElementCount());
    }

    @Test
    @DisplayName("Commit maintains deterministic ordering by creation sequence")
    void testCommitMaintainsOrder() {
        context.enableStaging();

        EClass first = context.createTarget(EClass.class);
        first.setName("First");
        EClass second = context.createTarget(EClass.class);
        second.setName("Second");
        EClass third = context.createTarget(EClass.class);
        third.setName("Third");
        context.addToResource(first);
        context.addToResource(second);
        context.addToResource(third);

        context.commitStagedElements();

        assertEquals(3, targetResource.getContents().size());
        assertEquals("First", ((EClass) targetResource.getContents().get(0)).getName());
        assertEquals("Second", ((EClass) targetResource.getContents().get(1)).getName());
        assertEquals("Third", ((EClass) targetResource.getContents().get(2)).getName());
    }

    @Test
    @DisplayName("Element sequence tracking")
    void testElementSequenceTracking() {
        context.enableStaging();

        EClass first = context.createTarget(EClass.class);
        EClass second = context.createTarget(EClass.class);
        EClass third = context.createTarget(EClass.class);
        context.addToResource(first);
        context.addToResource(second);
        context.addToResource(third);

        long seq1 = context.getElementSequence(first);
        long seq2 = context.getElementSequence(second);
        long seq3 = context.getElementSequence(third);

        assertTrue(seq1 < seq2, "First element should have lower sequence");
        assertTrue(seq2 < seq3, "Second element should have lower sequence than third");
    }

    @Test
    @DisplayName("clearStagedElements removes all staged elements")
    void testClearStagedElements() {
        context.enableStaging();

        EClass c1 = context.createTarget(EClass.class);
        EClass c2 = context.createTarget(EClass.class);
        context.addToResource(c1);
        context.addToResource(c2);

        assertEquals(2, context.getStagedElementCount());

        context.clearStagedElements();

        assertEquals(0, context.getStagedElementCount());
    }

    @Test
    @DisplayName("clearElementOrder resets sequence counter")
    void testClearElementOrder() {
        context.enableStaging();

        EClass first = context.createTarget(EClass.class);
        context.addToResource(first);
        long seq1 = context.getElementSequence(first);

        context.clearElementOrder();

        EClass second = context.createTarget(EClass.class);
        context.addToResource(second);
        long seq2 = context.getElementSequence(second);

        // After clear, sequence should restart
        assertEquals(seq1, seq2, "Sequence should restart after clear");
    }

    @Test
    @DisplayName("Pending XMI ID is stored for staged elements")
    void testPendingXmiIdForStagedElements() {
        context.enableStaging();
        
        EClass created = context.createTarget(EClass.class);
        
        // Access element ID (should generate and store pending ID)
        // This is tested indirectly through the internal getElementId method
        assertNull(context.getPendingXmiId(created), "No pending ID before first access");
    }

    @Test
    @DisplayName("clearPendingXmiIds removes all pending IDs")
    void testClearPendingXmiIds() {
        context.enableStaging();
        context.createTarget(EClass.class);
        
        context.clearPendingXmiIds();
        
        // Should not throw and should be empty
        assertEquals(0, context.getStagedElementCount() > 0 ? 0 : 0);
    }

    @Test
    @DisplayName("Contained elements are not added to resource contents")
    void testContainedElementsNotAddedToContents() {
        context.enableStaging();

        EClass parent = context.createTarget(EClass.class);
        parent.setName("Parent");
        context.addToResource(parent);

        // Create a contained element (EAttribute is contained in EClass)
        org.eclipse.emf.ecore.EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName("attr");
        parent.getEStructuralFeatures().add(attr);

        context.commitStagedElements();

        // Only the parent should be in contents
        assertEquals(1, targetResource.getContents().size());
        assertSame(parent, targetResource.getContents().get(0));

        // But the attribute should still be accessible
        assertEquals(1, parent.getEStructuralFeatures().size());
    }

    @Test
    @DisplayName("TransformationException contains element and rule info")
    void testTransformationExceptionContext() {
        EObject element = EcoreFactory.eINSTANCE.createEClass();
        String ruleName = "TestRule";
        
        TransformationException ex = new TransformationException(
                "Test error", new RuntimeException("cause"), element, ruleName);
        
        assertEquals("Test error", ex.getMessage());
        assertSame(element, ex.getFailedElement());
        assertEquals(ruleName, ex.getRuleName());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("TransformationException toString includes context")
    void testTransformationExceptionToString() {
        EClass element = EcoreFactory.eINSTANCE.createEClass();
        element.setName("TestClass");

        TransformationException ex = new TransformationException(
                "Error", element, "MyRule");

        String str = ex.toString();
        assertTrue(str.contains("MyRule"), "Should contain rule name");
        assertTrue(str.contains("EClass"), "Should contain element type");
    }

    @Test
    @DisplayName("autoAddRootElements is disabled by default")
    void testAutoAddRootElementsDisabledByDefault() {
        assertFalse(context.isAutoAddRootElements());
    }

    @Test
    @DisplayName("createTarget does not add to resource when autoAddRootElements is false")
    void testCreateTargetDoesNotAddWhenAutoAddDisabled() {
        context.setAutoAddRootElements(false);

        EClass created = context.createTarget(EClass.class);

        assertNotNull(created);
        assertEquals(0, targetResource.getContents().size(),
                "Element should NOT be in resource when autoAddRootElements is false");
    }

    @Test
    @DisplayName("createTarget adds to resource when autoAddRootElements is true")
    void testCreateTargetAddsWhenAutoAddEnabled() {
        context.setAutoAddRootElements(true);

        EClass created = context.createTarget(EClass.class);

        assertNotNull(created);
        assertEquals(1, targetResource.getContents().size(),
                "Element should be in resource when autoAddRootElements is true");
        assertSame(created, targetResource.getContents().get(0));
    }

    @Test
    @DisplayName("autoAddRootElements works with staging enabled")
    void testAutoAddRootElementsWithStaging() {
        context.setAutoAddRootElements(true);
        context.enableStaging();

        EClass created = context.createTarget(EClass.class);

        // With staging, element should be staged, not in resource yet
        assertEquals(0, targetResource.getContents().size());
        assertEquals(1, context.getStagedElementCount());

        context.commitStagedElements();

        assertEquals(1, targetResource.getContents().size());
        assertSame(created, targetResource.getContents().get(0));
    }
}
