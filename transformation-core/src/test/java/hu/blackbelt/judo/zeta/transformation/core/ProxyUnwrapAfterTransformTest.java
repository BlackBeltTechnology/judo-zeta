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

import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.impl.EOperationImpl;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that proxies are unwrapped after parallel transformation completes.
 *
 * <p>This test verifies the fix for issue #4:</p>
 * <pre>
 * java.lang.ClassCastException: class jdk.proxy2.$Proxy26 cannot be cast to
 * class org.eclipse.emf.ecore.impl.EOperationImpl
 * </pre>
 *
 * <p>The issue occurs when:</p>
 * <ol>
 *   <li>Zeta creates proxy objects during parallel transformation</li>
 *   <li>Proxies are stored in containment references (e.g., getEOperations())</li>
 *   <li>After transformation, EMF internal code casts to *Impl classes</li>
 *   <li>JDK proxies implement interfaces but cannot extend *Impl classes</li>
 * </ol>
 *
 * <p>The fix adds a proxy unwrap phase after commit that replaces all proxies
 * with their underlying EMF delegates.</p>
 */
@DisplayName("Proxy Unwrap After Transform Tests")
class ProxyUnwrapAfterTransformTest {

    private static final Logger log = LoggerFactory.getLogger(ProxyUnwrapAfterTransformTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        return ctx;
    }

    @Test
    @DisplayName("No proxies remain in model after parallel transformation")
    void testNoProxiesAfterParallelTransform() {
        // Create source elements
        for (int i = 0; i < 50; i++) {
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("Source" + i);
            sourceResource.getContents().add(sourceClass);
        }

        // Register transformation that creates classes with operations
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(CreateClassWithOperations.class);

        TransformationContext context = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(true)
                .parallelThreshold(10)
                .chunkSize(5)
                .build();

        // Execute transformation
        executor.transform();

        // Verify no proxies remain in the model
        int proxyCount = 0;
        TreeIterator<EObject> iterator = targetResource.getAllContents();
        while (iterator.hasNext()) {
            EObject obj = iterator.next();
            if (Proxy.isProxyClass(obj.getClass())) {
                proxyCount++;
                log.warn("Found proxy: {} of type {}", obj, obj.getClass());
            }
        }

        assertEquals(0, proxyCount, "No proxy objects should remain in the model after transformation");
    }

    @Test
    @DisplayName("EMF getEAllOperations() works after parallel transformation")
    void testEMFGetEAllOperationsWorks() {
        // Create source elements
        for (int i = 0; i < 20; i++) {
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("Source" + i);
            sourceResource.getContents().add(sourceClass);
        }

        // Register transformation that creates classes with operations
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(CreateClassWithOperations.class);

        TransformationContext context = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(true)
                .parallelThreshold(5)
                .chunkSize(3)
                .build();

        // Execute transformation
        executor.transform();

        // Verify EMF internal iterator works (this is what failed before the fix)
        for (EObject obj : targetResource.getContents()) {
            if (obj instanceof EClass targetClass) {
                // This call internally casts to EOperationImpl - would fail with proxies
                assertDoesNotThrow(() -> {
                    EList<EOperation> allOps = targetClass.getEAllOperations();
                    for (EOperation op : allOps) {
                        // Verify each operation is a real object, not a proxy
                        assertFalse(Proxy.isProxyClass(op.getClass()),
                                "EOperation should not be a proxy: " + op.getClass());
                        // Should be castable to EOperationImpl
                        assertTrue(op instanceof EOperationImpl,
                                "EOperation should be EOperationImpl, got: " + op.getClass());
                    }
                }, "getEAllOperations() should work without ClassCastException");
            }
        }
    }

    @Test
    @DisplayName("Stress test: many containment proxies are unwrapped")
    void testStressManyContainmentProxies() {
        // Create 100 source elements
        for (int i = 0; i < 100; i++) {
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("Stress" + i);
            sourceResource.getContents().add(sourceClass);
        }

        // Register transformation that creates classes with multiple operations
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(CreateClassWithMultipleOperations.class);

        TransformationContext context = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(true)
                .parallelThreshold(10)
                .chunkSize(5)
                .build();

        // Execute transformation
        executor.transform();

        // Count total operations and verify none are proxies
        int totalOperations = 0;
        int proxyOperations = 0;
        for (EObject obj : targetResource.getContents()) {
            if (obj instanceof EClass targetClass) {
                for (EOperation op : targetClass.getEOperations()) {
                    totalOperations++;
                    if (Proxy.isProxyClass(op.getClass())) {
                        proxyOperations++;
                    }
                }
            }
        }

        log.info("Total operations: {}, proxy operations: {}", totalOperations, proxyOperations);
        assertEquals(0, proxyOperations, "No proxy operations should remain");
        assertTrue(totalOperations > 0, "Should have created some operations");
    }

    // ==================== Test Transformation Rules ====================

    /**
     * Creates an EClass target with EOperations (containment references).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CreateClassWithOperations {

        @TransformRule(name = "CreateClassWithOps")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> createClass() {
            return (source, ctx) -> {
                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_Target");

                // Create an operation (containment reference)
                EOperation op = ctx.createTarget(EOperation.class);
                op.setName("op_" + source.getName());
                targetClass.getEOperations().add(op);

                ctx.addToResource(targetClass);
                return targetClass;
            };
        }
    }

    /**
     * Creates an EClass target with multiple EOperations.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CreateClassWithMultipleOperations {

        @TransformRule(name = "CreateClassWithMultiOps")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> createClass() {
            return (source, ctx) -> {
                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_Target");

                // Create multiple operations (stress test containment)
                for (int i = 0; i < 5; i++) {
                    EOperation op = ctx.createTarget(EOperation.class);
                    op.setName("op_" + source.getName() + "_" + i);
                    targetClass.getEOperations().add(op);
                }

                ctx.addToResource(targetClass);
                return targetClass;
            };
        }
    }

    // ==================== Model Provider ====================

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
