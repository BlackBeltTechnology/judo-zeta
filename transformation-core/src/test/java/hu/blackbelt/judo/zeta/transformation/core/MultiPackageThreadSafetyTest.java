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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for thread-safety of multi-package operations in TransformationContext.
 */
@DisplayName("Multi-Package Thread-Safety Tests")
class MultiPackageThreadSafetyTest {

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
    @DisplayName("Concurrent createTarget Operations")
    class ConcurrentCreateTargetTests {

        @Test
        @DisplayName("Concurrent createTarget with EcorePackage does not throw")
        void concurrentCreateTargetDoesNotThrow() throws Exception {
            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.enableStaging();

            int threadCount = 10;
            int operationsPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            try {
                                // Alternate between different types
                                if (j % 3 == 0) {
                                    context.createTarget(EClass.class);
                                } else if (j % 3 == 1) {
                                    context.createTarget(EAttribute.class);
                                } else {
                                    context.createTarget(EPackage.class);
                                }
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                errors.add(e);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(),
                    "No errors should occur. Errors: " + errors);
            assertEquals(threadCount * operationsPerThread, successCount.get(),
                    "All operations should succeed");
        }

        @Test
        @DisplayName("Concurrent createTarget resolves correct package")
        void concurrentCreateTargetResolvesCorrectPackage() throws Exception {
            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.enableStaging();

            int threadCount = 5;
            int operationsPerThread = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<EObject> createdElements = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            EClass created = context.createTarget(EClass.class);
                            createdElements.add(created);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * operationsPerThread, createdElements.size());

            // Verify all elements are from the correct package
            for (EObject element : createdElements) {
                assertEquals(EcorePackage.eINSTANCE, element.eClass().getEPackage(),
                        "All elements should be from EcorePackage");
                assertEquals("EClass", element.eClass().getName());
            }
        }
    }

    @Nested
    @DisplayName("Package Visibility Across Threads")
    class PackageVisibilityTests {

        @Test
        @DisplayName("Packages registered before parallel phase visible to all threads")
        void packagesVisibleAcrossThreads() throws Exception {
            context.registerTargetPackage(EcorePackage.eINSTANCE);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<List<EPackage>> packageLists = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        List<EPackage> packages = context.getTargetPackages();
                        packageLists.add(new ArrayList<>(packages));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, packageLists.size());
            for (List<EPackage> packages : packageLists) {
                assertEquals(1, packages.size(),
                        "Each thread should see 1 registered package");
                assertTrue(packages.contains(EcorePackage.eINSTANCE),
                        "Each thread should see EcorePackage");
            }
        }
    }

    @Nested
    @DisplayName("No ConcurrentModificationException")
    class NoConcurrentModificationTests {

        @Test
        @DisplayName("No ConcurrentModificationException during iteration and modification")
        void noConcurrentModificationException() throws Exception {
            context.registerTargetPackage(EcorePackage.eINSTANCE);

            int readerCount = 5;
            int writerCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(readerCount + writerCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(readerCount + writerCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            // Readers
            for (int i = 0; i < readerCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 100; j++) {
                            try {
                                for (EPackage pkg : context.getTargetPackages()) {
                                    pkg.getNsURI();
                                }
                            } catch (ConcurrentModificationException e) {
                                errorCount.incrementAndGet();
                                errors.add(e);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Writers
            for (int i = 0; i < writerCount; i++) {
                final int writerIndex = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 50; j++) {
                            try {
                                EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
                                pkg.setName("Package" + writerIndex + "_" + j);
                                pkg.setNsURI("http://test/pkg" + writerIndex + "/" + j);
                                context.registerTargetPackage(pkg);
                            } catch (ConcurrentModificationException e) {
                                errorCount.incrementAndGet();
                                errors.add(e);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(),
                    "No ConcurrentModificationException should occur. Errors: " + errors);
        }
    }

    @Nested
    @DisplayName("Thread-Safe Type Resolution")
    class ThreadSafeTypeResolutionTests {

        @Test
        @DisplayName("Type resolution is thread-safe with explicit package")
        void typeResolutionIsThreadSafeWithExplicitPackage() throws Exception {
            context.registerTargetPackage(EcorePackage.eINSTANCE);
            context.enableStaging();

            int threadCount = 6;
            int operationsPerThread = 30;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            try {
                                // Use explicit package to avoid any resolution ambiguity
                                EClass element = context.createTarget(EClass.class, EcorePackage.eINSTANCE);
                                if (element != null &&
                                        element.eClass().getEPackage() == EcorePackage.eINSTANCE) {
                                    successCount.incrementAndGet();
                                } else {
                                    errorCount.incrementAndGet();
                                }
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, errorCount.get(), "No errors should occur");
            assertEquals(threadCount * operationsPerThread, successCount.get(),
                    "All operations should succeed");
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
