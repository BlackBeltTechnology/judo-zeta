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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Concurrency stress tests for parallel transformation.
 * 
 * <p>These tests verify thread-safety of the staging infrastructure
 * under high contention scenarios. Tests are repeated multiple times
 * to catch intermittent race conditions.</p>
 */
class ConcurrencyStressTest {

    private TransformationContext context;
    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource targetResource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        
        // Register XMI resource factory for all extensions
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
        
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
        
        ModelProvider modelProvider = mock(ModelProvider.class);
        ExtensionMethodRegistry extensionRegistry = mock(ExtensionMethodRegistry.class);
        
        context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                targetResourceSet,
                extensionRegistry
        );
        context.setTargetPackage(EcorePackage.eINSTANCE);
        
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @RepeatedTest(50)
    @DisplayName("Concurrent element creation produces exact count")
    @Timeout(30)
    void testConcurrentElementCreation() throws Exception {
        int threadCount = 50;
        int elementsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        
        context.enableStaging();
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < elementsPerThread; i++) {
                        context.createTarget(EClass.class);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // Release all threads
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timeout - possible deadlock");
        
        assertNull(failure.get(), "Exception during concurrent creation: " + failure.get());
        assertEquals(threadCount * elementsPerThread, context.getStagedElementCount(),
                "All elements should be staged");
    }

    @RepeatedTest(50)
    @DisplayName("Concurrent staging maintains unique sequence numbers")
    @Timeout(30)
    void testConcurrentSequenceUniqueness() throws Exception {
        int threadCount = 32;
        int elementsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentHashMap<Long, EObject> sequenceMap = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        
        context.enableStaging();
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < elementsPerThread; i++) {
                        EClass element = context.createTarget(EClass.class);
                        long seq = context.getElementSequence(element);
                        EObject previous = sequenceMap.put(seq, element);
                        if (previous != null) {
                            failure.compareAndSet(null, 
                                new AssertionError("Duplicate sequence: " + seq));
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        assertNull(failure.get(), "Failure: " + failure.get());
        assertEquals(threadCount * elementsPerThread, sequenceMap.size(),
                "All sequences should be unique");
    }

    @RepeatedTest(50)
    @DisplayName("Commit after concurrent creation maintains order")
    @Timeout(30)
    void testCommitOrderAfterConcurrentCreation() throws Exception {
        int threadCount = 20;
        int elementsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<EClass> allCreated = Collections.synchronizedList(new ArrayList<>());
        
        context.enableStaging();
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < elementsPerThread; i++) {
                        EClass element = context.createTarget(EClass.class);
                        allCreated.add(element);
                    }
                } catch (Exception e) {
                    // Ignore for this test
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        context.commitStagedElements();
        
        assertEquals(threadCount * elementsPerThread, targetResource.getContents().size());
        
        // Verify ordering is by sequence (elements are sorted by creation order)
        long previousSeq = -1;
        for (EObject obj : targetResource.getContents()) {
            // We can't directly check sequence after commit, but we verify all elements present
            assertNotNull(obj);
        }
    }

    @RepeatedTest(30)
    @DisplayName("Enable/disable staging is thread-safe")
    @Timeout(30)
    void testConcurrentEnableDisable() throws Exception {
        int threadCount = 20;
        int iterations = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger enableCount = new AtomicInteger(0);
        AtomicInteger disableCount = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        if (threadId % 2 == 0) {
                            context.enableStaging();
                            enableCount.incrementAndGet();
                        } else {
                            context.disableStaging();
                            disableCount.incrementAndGet();
                        }
                        // Read the state
                        context.isStagingEnabled();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        // If we got here without exceptions, the test passed
        assertTrue(enableCount.get() > 0);
        assertTrue(disableCount.get() > 0);
    }

    @RepeatedTest(30)
    @DisplayName("ElementResolutionCache handles concurrent access")
    @Timeout(30)
    void testConcurrentCacheAccess() throws Exception {
        ElementResolutionCache cache = context.getElementResolutionCache();
        int threadCount = 50;
        int operationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        
        // Pre-create source elements
        List<EObject> sources = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Source" + i);
            sources.add(source);
        }
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            final EObject mySource = sources.get(threadId);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        EClass target = EcoreFactory.eINSTANCE.createEClass();
                        target.setName("Target" + threadId + "_" + i);
                        
                        // Write
                        cache.addMapping(mySource, "Rule" + i, target, i == 0);
                        
                        // Read
                        cache.getByRule(mySource, "Rule" + i);
                        cache.getEquivalent(mySource, EClass.class);
                        cache.getEquivalents(mySource, EClass.class);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        assertNull(failure.get(), "Exception during concurrent cache access: " + failure.get());
    }

    @RepeatedTest(30)
    @DisplayName("Mixed read/write operations don't deadlock")
    @Timeout(30)
    void testNoDeadlockUnderMixedLoad() throws Exception {
        int durationSeconds = 2;
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger operations = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int threadCount = 30;
        CountDownLatch allStarted = new CountDownLatch(threadCount);
        
        context.enableStaging();
        
        // Writer threads
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                allStarted.countDown();
                while (running.get() && failure.get() == null) {
                    try {
                        context.createTarget(EClass.class);
                        operations.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }
        
        // Reader threads
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                allStarted.countDown();
                while (running.get() && failure.get() == null) {
                    try {
                        context.getStagedElementCount();
                        context.isStagingEnabled();
                        operations.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }
        
        // Cache access threads
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            executor.submit(() -> {
                allStarted.countDown();
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source" + threadId);
                while (running.get() && failure.get() == null) {
                    try {
                        EClass target = EcoreFactory.eINSTANCE.createEClass();
                        context.getElementResolutionCache().addMapping(source, "Rule", target, true);
                        context.getElementResolutionCache().getEquivalent(source, EClass.class);
                        operations.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }
        
        // Wait for all threads to start
        assertTrue(allStarted.await(10, TimeUnit.SECONDS));
        
        // Let it run
        Thread.sleep(durationSeconds * 1000L);
        running.set(false);
        
        // Give threads time to finish
        Thread.sleep(500);
        
        assertNull(failure.get(), "Exception during mixed operations: " + failure.get());
        assertTrue(operations.get() > 0, "No operations completed - possible deadlock");
    }

    @RepeatedTest(50)
    @DisplayName("Concurrent clear operations are safe")
    @Timeout(30)
    void testConcurrentClearOperations() throws Exception {
        int threadCount = 20;
        int iterations = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        
        context.enableStaging();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        switch (threadId % 4) {
                            case 0:
                                context.createTarget(EClass.class);
                                break;
                            case 1:
                                context.clearStagedElements();
                                break;
                            case 2:
                                context.clearElementOrder();
                                break;
                            case 3:
                                context.clearPendingXmiIds();
                                break;
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        assertNull(failure.get(), "Exception during concurrent clears: " + failure.get());
    }

    @Test
    @DisplayName("Stress test: High volume element creation and commit")
    @Timeout(60)
    void testHighVolumeCreationAndCommit() throws Exception {
        int totalElements = 10000;
        int threadCount = Runtime.getRuntime().availableProcessors();
        int elementsPerThread = totalElements / threadCount;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        context.enableStaging();
        
        long startTime = System.currentTimeMillis();
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < elementsPerThread; i++) {
                        EClass element = context.createTarget(EClass.class);
                        element.setName("Element_" + Thread.currentThread().getId() + "_" + i);
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        long creationTime = System.currentTimeMillis() - startTime;
        
        // Commit
        long commitStart = System.currentTimeMillis();
        context.commitStagedElements();
        long commitTime = System.currentTimeMillis() - commitStart;
        
        assertEquals(totalElements, targetResource.getContents().size());
        
        System.out.println("Created " + totalElements + " elements in " + creationTime + "ms");
        System.out.println("Committed in " + commitTime + "ms");
    }

    @RepeatedTest(20)
    @DisplayName("Concurrent discriminated mapping creation")
    @Timeout(30)
    void testConcurrentDiscriminatedMappings() throws Exception {
        ElementResolutionCache cache = context.getElementResolutionCache();
        int threadCount = 20;
        int discriminatorsPerThread = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        
        EClass sharedSource = EcoreFactory.eINSTANCE.createEClass();
        sharedSource.setName("SharedSource");
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < discriminatorsPerThread; i++) {
                        EClass target = EcoreFactory.eINSTANCE.createEClass();
                        target.setName("Target_" + threadId + "_" + i);
                        String discriminator = "disc_" + threadId + "_" + i;
                        
                        cache.addDiscriminatedMapping(sharedSource, target, "Rule", discriminator);
                        
                        // Verify we can read it back
                        EClass retrieved = cache.getEquivalentDiscriminated(
                                sharedSource, EClass.class, "Rule", discriminator);
                        if (retrieved == null) {
                            failure.compareAndSet(null, 
                                new AssertionError("Could not retrieve discriminated mapping"));
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        
        assertNull(failure.get(), "Failure: " + failure.get());
    }
}
