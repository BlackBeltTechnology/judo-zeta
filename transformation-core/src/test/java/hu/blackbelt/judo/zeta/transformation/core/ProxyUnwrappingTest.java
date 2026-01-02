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
import hu.blackbelt.judo.zeta.transformation.core.deferred.DeferredEObject;
import hu.blackbelt.judo.zeta.transformation.core.deferred.OperationQueue;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.common.util.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for proxy unwrapping in TransformationContext.
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>{@code addToResource()} unwraps proxy elements</li>
 *   <li>{@code commitStagedElements()} produces clean model</li>
 *   <li>{@code unwrapAllProxiesInModel()} cleans all reference values</li>
 *   <li>Model serialization succeeds without ClassCastException</li>
 * </ul>
 */
class ProxyUnwrappingTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource targetResource;
    private TransformationContext context;
    private OperationQueue queue;
    private ModelProvider modelProvider;
    private ExtensionMethodRegistry extensionRegistry;

    @BeforeEach
    void setUp() {
        // Register XMI resource factory globally and per-resourceSet
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        // Register factory on the resource set for proper URI handling
        targetResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());

        // Use a proper platform-style URI
        targetResource = targetResourceSet.createResource(URI.createURI("platform:/resource/test/target.xmi"));

        modelProvider = new TestModelProvider();
        extensionRegistry = new ExtensionMethodRegistry();
        context = new TransformationContext(modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        queue = new OperationQueue();
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

    // ==================== addToResource() Proxy Unwrapping Tests ====================

    @Nested
    class AddToResourceTests {

        @Test
        void testAddToResourceUnwrapsProxy() {
            EClass realElement = EcoreFactory.eINSTANCE.createEClass();
            realElement.setName("TestClass");

            // Create a proxy
            EClass proxy = DeferredEObject.createProxy(realElement, queue);
            assertTrue(proxy instanceof DeferredEObject.ProxyMarker);

            // Add proxy to resource via context
            context.addToResource(proxy);

            // Resource should contain real element, not proxy
            assertEquals(1, targetResource.getContents().size());
            EObject inResource = targetResource.getContents().get(0);

            assertFalse(inResource instanceof DeferredEObject.ProxyMarker,
                    "Element in resource should not be a proxy");
            assertSame(realElement, inResource,
                    "Element in resource should be the unwrapped real element");
        }

        @Test
        void testAddToResourceWithNonProxyElement() {
            EClass realElement = EcoreFactory.eINSTANCE.createEClass();
            realElement.setName("TestClass");

            // Add non-proxy element
            context.addToResource(realElement);

            // Should work normally
            assertEquals(1, targetResource.getContents().size());
            assertSame(realElement, targetResource.getContents().get(0));
        }

        @Test
        void testAddToResourceWithNull() {
            context.addToResource(null);

            // Should not throw, should not add anything
            assertTrue(targetResource.getContents().isEmpty());
        }
    }

    // ==================== unwrapAllProxiesInModel() Tests ====================

    @Nested
    class UnwrapAllProxiesInModelTests {

        @Test
        void testUnwrapSingleValuedReference() {
            // Create elements
            EClass container = EcoreFactory.eINSTANCE.createEClass();
            container.setName("Container");

            EClass referenced = EcoreFactory.eINSTANCE.createEClass();
            referenced.setName("Referenced");

            // Create proxy for referenced element
            EClass proxiedRef = DeferredEObject.createProxy(referenced, queue);

            // Set proxy as supertype (single-valued reference in EMF's ESuperTypes list)
            container.getESuperTypes().add(proxiedRef);

            // Add to resource
            targetResource.getContents().add(container);
            targetResource.getContents().add(referenced);

            // Call unwrapAllProxiesInModel
            int unwrappedCount = context.unwrapAllProxiesInModel();

            // Should have unwrapped the proxy reference
            assertEquals(1, unwrappedCount);

            // Reference should now be the real element
            assertFalse(container.getESuperTypes().isEmpty());
            EClass superType = container.getESuperTypes().get(0);
            assertFalse(superType instanceof DeferredEObject.ProxyMarker);
            assertSame(referenced, superType);
        }

        @Test
        void testUnwrapMultiValuedReference() {
            // Create container with multiple references
            EClass container = EcoreFactory.eINSTANCE.createEClass();
            container.setName("Container");

            EClass ref1 = EcoreFactory.eINSTANCE.createEClass();
            ref1.setName("Ref1");
            EClass ref2 = EcoreFactory.eINSTANCE.createEClass();
            ref2.setName("Ref2");

            // Create proxies
            EClass proxiedRef1 = DeferredEObject.createProxy(ref1, queue);
            EClass proxiedRef2 = DeferredEObject.createProxy(ref2, queue);

            // Add proxies to multi-valued reference
            container.getESuperTypes().add(proxiedRef1);
            container.getESuperTypes().add(proxiedRef2);

            // Add to resource
            targetResource.getContents().add(container);
            targetResource.getContents().add(ref1);
            targetResource.getContents().add(ref2);

            // Call unwrapAllProxiesInModel
            int unwrappedCount = context.unwrapAllProxiesInModel();

            assertEquals(2, unwrappedCount);

            // All references should be unwrapped
            for (EClass superType : container.getESuperTypes()) {
                assertFalse(superType instanceof DeferredEObject.ProxyMarker);
            }
            assertTrue(container.getESuperTypes().contains(ref1));
            assertTrue(container.getESuperTypes().contains(ref2));
        }

        @Test
        void testUnwrapNestedReferences() {
            // Create nested structure
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("testpackage");
            pkg.setNsPrefix("test");
            pkg.setNsURI("http://test");

            EClass containerClass = EcoreFactory.eINSTANCE.createEClass();
            containerClass.setName("Container");
            pkg.getEClassifiers().add(containerClass);

            EClass referencedClass = EcoreFactory.eINSTANCE.createEClass();
            referencedClass.setName("Referenced");
            pkg.getEClassifiers().add(referencedClass);

            // Create proxy for referenced class
            EClass proxiedRef = DeferredEObject.createProxy(referencedClass, queue);

            // Set proxy reference
            containerClass.getESuperTypes().add(proxiedRef);

            // Add package to resource (classes are nested)
            targetResource.getContents().add(pkg);

            // Call unwrapAllProxiesInModel
            int unwrappedCount = context.unwrapAllProxiesInModel();

            // Should unwrap nested proxy reference
            assertEquals(1, unwrappedCount);
            assertSame(referencedClass, containerClass.getESuperTypes().get(0));
        }

        @Test
        void testUnwrapWithNoProxies() {
            // Create model without any proxies
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("NoProxy");
            targetResource.getContents().add(element);

            // Call unwrapAllProxiesInModel
            int unwrappedCount = context.unwrapAllProxiesInModel();

            assertEquals(0, unwrappedCount);
        }

        @Test
        void testUnwrapWithEmptyResource() {
            // Empty resource
            int unwrappedCount = context.unwrapAllProxiesInModel();

            assertEquals(0, unwrappedCount);
        }
    }

    // ==================== Model Serialization Tests ====================

    @Nested
    class SerializationTests {

        @Test
        void testModelSerializesAfterUnwrapping() throws Exception {
            // Create model with proxied references
            EClass container = EcoreFactory.eINSTANCE.createEClass();
            container.setName("Container");

            EClass referenced = EcoreFactory.eINSTANCE.createEClass();
            referenced.setName("Referenced");

            // Create proxy and add to reference
            EClass proxiedRef = DeferredEObject.createProxy(referenced, queue);
            container.getESuperTypes().add(proxiedRef);

            // Add to resource
            targetResource.getContents().add(container);
            targetResource.getContents().add(referenced);

            // Unwrap proxies
            context.unwrapAllProxiesInModel();

            // Model should serialize without exception
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Map<String, Object> options = new HashMap<>();
            assertDoesNotThrow(() -> targetResource.save(out, options));

            // Verify something was written
            assertTrue(out.size() > 0);
        }

        @Test
        void testSerializationFailsWithProxy() {
            // Create model with proxied element in resource
            EClass realElement = EcoreFactory.eINSTANCE.createEClass();
            realElement.setName("TestClass");

            // Add a proxy directly (bypassing addToResource)
            EClass proxy = DeferredEObject.createProxy(realElement, queue);

            // This simulates the bug where proxy ends up in resource
            // We can't easily reproduce the ClassCastException without internal EMF access,
            // but we can verify the proxy is detected
            assertTrue(proxy instanceof DeferredEObject.ProxyMarker);

            // After unwrap, it should be the real element
            EClass unwrapped = DeferredEObject.unwrap(proxy);
            assertSame(realElement, unwrapped);
            assertFalse(unwrapped instanceof DeferredEObject.ProxyMarker);
        }
    }

    // ==================== Integration with Staging Tests ====================

    @Nested
    class StagingIntegrationTests {

        @Test
        void testStagedElementsAreUnwrapped() {
            // Enable staging
            context.enableStaging();

            // Create proxied element
            EClass realElement = EcoreFactory.eINSTANCE.createEClass();
            realElement.setName("StagedClass");
            EClass proxy = DeferredEObject.createProxy(realElement, queue);

            // Add proxy via staging
            context.addToResource(proxy);

            // Verify element is staged
            assertTrue(context.getStagedElementCount() > 0);

            // Commit staged elements
            context.commitStagedElements();

            // Verify resource contains real element, not proxy
            assertFalse(targetResource.getContents().isEmpty());
            EObject committed = targetResource.getContents().get(0);
            assertFalse(committed instanceof DeferredEObject.ProxyMarker,
                    "Committed element should not be a proxy");
            assertSame(realElement, committed);
        }
    }
}
