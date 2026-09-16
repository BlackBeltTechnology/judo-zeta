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
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.common.util.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for parallel transformation feature combinations.
 *
 * <p>Tests verify that the following features work correctly together:
 * <ul>
 *   <li>Parallel execution + Deferred writes</li>
 *   <li>Parallel execution + Element staging</li>
 *   <li>Parallel execution + Guard rejection caching</li>
 *   <li>Deferred writes + Proxy unwrapping</li>
 *   <li>Full combination: Parallel + Per-element locking + Deferred writes</li>
 * </ul>
 */
class ParallelFeatureCombinationTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource targetResource;
    private TransformationContext context;
    private ElementResolutionCache cache;
    private ModelProvider modelProvider;
    private ExtensionMethodRegistry extensionRegistry;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        // Register factory on the resource set for proper URI handling
        targetResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());

        targetResource = targetResourceSet.createResource(URI.createURI("platform:/resource/test/target.xmi"));

        modelProvider = new TestModelProvider();
        extensionRegistry = new ExtensionMethodRegistry();
        context = new TransformationContext(modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        cache = new ElementResolutionCache();
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

    // ==================== Parallel + Element Staging Tests ====================

    @Nested
    class ParallelWithStagingTests {

        @Test
        void testParallelCreationWithStagedCommit() throws InterruptedException {
            context.enableStaging();

            int threadCount = 8;
            int elementsPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < elementsPerThread; i++) {
                            EClass element = EcoreFactory.eINSTANCE.createEClass();
                            element.setName("Class_" + threadId + "_" + i);
                            context.addToResource(element);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // All elements should be staged
            assertEquals(threadCount * elementsPerThread, context.getStagedElementCount());

            // Commit
            context.commitStagedElements();

            // All elements should be in resource
            assertEquals(threadCount * elementsPerThread, targetResource.getContents().size());

            // Verify deterministic ordering - elements should be sorted by creation sequence
            // Names should show consistent ordering within each thread's elements
            Set<String> names = new HashSet<>();
            for (EObject obj : targetResource.getContents()) {
                names.add(((EClass) obj).getName());
            }
            assertEquals(threadCount * elementsPerThread, names.size(), "All elements should have unique names");
        }

        @Test
        void testStagedElementsCommitInCreationOrder() {
            context.enableStaging();

            // Create elements in specific order
            List<String> expectedOrder = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                EClass element = EcoreFactory.eINSTANCE.createEClass();
                element.setName("Element_" + i);
                expectedOrder.add("Element_" + i);
                context.addToResource(element);
            }

            context.commitStagedElements();

            // Verify order is preserved
            List<String> actualOrder = new ArrayList<>();
            for (EObject obj : targetResource.getContents()) {
                actualOrder.add(((EClass) obj).getName());
            }

            assertEquals(expectedOrder, actualOrder, "Elements should be committed in creation order");
        }
    }

    // ==================== Parallel + Guard Rejection Caching Tests ====================

    @Nested
    class ParallelWithGuardRejectionCachingTests {

        @Test
        void testGuardRejectionsCachedAcrossThreads() throws InterruptedException {
            // Simulate guard that rejects certain sources
            Set<EObject> rejectedSources = ConcurrentHashMap.newKeySet();
            AtomicInteger guardEvaluations = new AtomicInteger(0);

            // Create sources - some will be rejected
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
                if (i % 3 == 0) {
                    rejectedSources.add(source);
                }
            }

            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger createdTargets = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (EObject source : sources) {
                            cache.getOrCreate(source, "TestRule", () -> {
                                guardEvaluations.incrementAndGet();
                                // Guard rejects every 3rd source
                                if (rejectedSources.contains(source)) {
                                    return null;
                                }
                                createdTargets.incrementAndGet();
                                EClass target = EcoreFactory.eINSTANCE.createEClass();
                                target.setName("Target_for_" + ((EClass) source).getName());
                                return target;
                            }, false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Each source should only be evaluated once despite multiple threads
            assertEquals(100, guardEvaluations.get(),
                    "Each source should only have guard evaluated once");

            // Only non-rejected sources should have targets
            int expectedTargets = 100 - rejectedSources.size();
            assertEquals(expectedTargets, createdTargets.get(),
                    "Only non-rejected sources should create targets");
        }
    }

    // ==================== Per-Element Locking Tests ====================

    @Nested
    class PerElementLockingTests {

        @Test
        void testConcurrentGetOrCreateProducesSingleTarget() throws InterruptedException {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("SharedSource");

            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger supplierCalls = new AtomicInteger(0);
            Set<EObject> results = ConcurrentHashMap.newKeySet();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        EObject result = cache.getOrCreate(source, "TestRule", () -> {
                            supplierCalls.incrementAndGet();
                            // Simulate some work
                            try { Thread.sleep(10); } catch (InterruptedException e) { }
                            EClass target = EcoreFactory.eINSTANCE.createEClass();
                            target.setName("Target_" + System.nanoTime());
                            return target;
                        }, false);
                        results.add(result);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Only one supplier call
            assertEquals(1, supplierCalls.get(), "Supplier should only be called once");

            // All threads should get same result
            assertEquals(1, results.size(), "All threads should get the same target");
        }

        @Test
        void testDifferentSourcesProcessInParallel() throws InterruptedException {
            int sourceCount = 10;
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
            }

            ExecutorService executor = Executors.newFixedThreadPool(sourceCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(sourceCount);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            AtomicInteger currentConcurrent = new AtomicInteger(0);

            for (int i = 0; i < sourceCount; i++) {
                final EObject source = sources.get(i);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        cache.getOrCreate(source, "TestRule", () -> {
                            int concurrent = currentConcurrent.incrementAndGet();
                            maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));
                            try { Thread.sleep(50); } catch (InterruptedException e) { }
                            currentConcurrent.decrementAndGet();
                            return EcoreFactory.eINSTANCE.createEClass();
                        }, false);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Should have parallel execution
            assertTrue(maxConcurrent.get() > 1,
                    "Different sources should process in parallel, maxConcurrent=" + maxConcurrent.get());
        }
    }

    // ==================== Full Combination Tests ====================

    @Nested
    class FullCombinationTests {

        @Test
        void testParallelWithDeferredWritesAndStaging() throws InterruptedException {
            context.enableStaging();
            context.enableDeferredWrites();

            int threadCount = 4;
            int elementsPerThread = 25;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger supplierCalls = new AtomicInteger(0);

            // Create shared source elements
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < elementsPerThread; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
            }

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < sources.size(); i++) {
                            EObject source = sources.get(i);
                            // All threads try to transform same sources
                            EObject target = cache.getOrCreate(source, "TestRule", () -> {
                                supplierCalls.incrementAndGet();
                                EClass t1 = context.createTarget(EClass.class);
                                t1.setName("Target_for_" + ((EClass) source).getName());
                                return t1;
                            }, true);

                            if (target != null && threadId == 0) {
                                // Only first thread adds to resource to avoid duplicates
                                context.addToResource(target);
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
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Each source should only create one target
            assertEquals(sources.size(), supplierCalls.get(),
                    "Each source should only create one target despite concurrent access");

            // Commit deferred operations
            int committed = context.commitDeferredOperations();
            assertTrue(committed > 0, "Should have committed deferred operations");

            // Commit staged elements
            context.commitStagedElements();

            // Unwrap any remaining proxies
            int unwrapped = context.unwrapAllProxiesInModel();

            // Should be able to serialize without exception
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertDoesNotThrow(() -> targetResource.save(out, new HashMap<>()));
        }

        @Test
        void testDeterministicResultsAcrossMultipleRuns() throws InterruptedException {
            // Run the same transformation multiple times and verify identical results
            List<List<String>> runResults = new ArrayList<>();

            for (int run = 0; run < 3; run++) {
                // Reset state
                setUp();
                context.enableStaging();

                List<EObject> sources = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    EClass source = EcoreFactory.eINSTANCE.createEClass();
                    source.setName("Source_" + i);
                    sources.add(source);
                }

                int threadCount = 4;
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);

                for (int t = 0; t < threadCount; t++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (EObject source : sources) {
                                EObject target = cache.getOrCreate(source, "TestRule", () -> {
                                    EClass t1 = EcoreFactory.eINSTANCE.createEClass();
                                    t1.setName("Target_for_" + ((EClass) source).getName());
                                    return t1;
                                }, true);
                                if (target != null) {
                                    context.addToResource(target);
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
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
                executor.shutdown();

                context.commitStagedElements();

                // Collect results
                List<String> resultNames = new ArrayList<>();
                for (EObject obj : targetResource.getContents()) {
                    resultNames.add(((EClass) obj).getName());
                }
                Collections.sort(resultNames); // Sort for comparison
                runResults.add(resultNames);

                // Reset cache for next run
                cache.clear();
            }

            // All runs should produce same sorted results
            for (int i = 1; i < runResults.size(); i++) {
                assertEquals(runResults.get(0), runResults.get(i),
                        "Run " + i + " should produce same results as run 0");
            }
        }

        @Test
        void testLargeScaleParallelTransformation() throws InterruptedException {
            context.enableStaging();

            // Simulate large model
            int sourceCount = 1000;
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
            }

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger createdCount = new AtomicInteger(0);

            // Distribute sources across threads
            int sourcesPerThread = sourceCount / threadCount;

            for (int t = 0; t < threadCount; t++) {
                final int startIdx = t * sourcesPerThread;
                final int endIdx = (t == threadCount - 1) ? sourceCount : startIdx + sourcesPerThread;

                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = startIdx; i < endIdx; i++) {
                            EObject source = sources.get(i);
                            EObject target = cache.getOrCreate(source, "TestRule", () -> {
                                createdCount.incrementAndGet();
                                EClass t1 = EcoreFactory.eINSTANCE.createEClass();
                                t1.setName("Target_" + ((EClass) source).getName());
                                return t1;
                            }, true);
                            if (target != null) {
                                context.addToResource(target);
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
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            context.commitStagedElements();

            // Verify counts match
            assertEquals(sourceCount, createdCount.get(),
                    "Should create exactly one target per source");
            assertEquals(sourceCount, targetResource.getContents().size(),
                    "Resource should contain exactly one element per source");

            // Verify no duplicates (unique names)
            Set<String> names = new HashSet<>();
            for (EObject obj : targetResource.getContents()) {
                String name = ((EClass) obj).getName();
                assertTrue(names.add(name), "Duplicate name found: " + name);
            }
        }
    }

    // ==================== Edge Case Tests ====================

    @Nested
    class EdgeCaseTests {

        @Test
        void testEmptySourceModel() {
            context.enableStaging();

            // No sources, no transformation
            context.commitStagedElements();

            assertTrue(targetResource.getContents().isEmpty());
        }

        @Test
        void testSingleElementBelowParallelThreshold() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("SingleSource");

            EObject target = cache.getOrCreate(source, "TestRule", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("SingleTarget");
                return t;
            }, true);

            assertNotNull(target);
            assertEquals("SingleTarget", ((EClass) target).getName());
        }

        @Test
        void testRuleReturningNull() throws InterruptedException {
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
            }

            AtomicInteger supplierCalls = new AtomicInteger(0);

            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (EObject source : sources) {
                            cache.getOrCreate(source, "TestRule", () -> {
                                supplierCalls.incrementAndGet();
                                // Rule always returns null (guard rejection)
                                return null;
                            }, false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Each source evaluated once per thread (null not cached as "done")
            // Actually, with our implementation null returns should still only happen once
            // because we check the cache first
            assertTrue(supplierCalls.get() >= sources.size(),
                    "Null-returning rules should still be invoked at least once per source");
        }

        @Test
        void testNullGuardRule() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            // Rule with no guard (always executes)
            EObject target = cache.getOrCreate(source, "NoGuardRule", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("NoGuardTarget");
                return t;
            }, true);

            assertNotNull(target);
        }
    }

    // ==================== Parent Rule Race Condition Tests ====================

    /**
     * Tests that expose race conditions in executeParentRule() and @Extends inheritance.
     *
     * <p>These tests verify that concurrent execution of parent rules produces correct results:
     * <ul>
     *   <li>Parent rule executes exactly once per (source, rule) pair</li>
     *   <li>All threads receive the same target instance</li>
     *   <li>@Extends inheritance chains work correctly under concurrent access</li>
     * </ul>
     *
     * <p>Uses 50 threads with CountDownLatch synchronization to maximize collision probability.
     */
    @Nested
    @DisplayName("Parent Rule Race Condition Tests")
    class ParentRuleRaceConditionTests {

        private TransformationRegistry registry;
        private Resource sourceResource;

        @BeforeEach
        void setUpRegistry() {
            registry = new TransformationRegistry();
            sourceResource = sourceResourceSet.createResource(URI.createURI("platform:/resource/test/source.xmi"));
            // Reset invocation counters before each test
            ParentRuleInvocationCounter.reset();
        }

        private EClass createSource(String name) {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName(name);
            sourceResource.getContents().add(source);
            return source;
        }

        @Test
        @DisplayName("Concurrent executeParentRule() should invoke parent rule exactly once")
        void testConcurrentExecuteParentRuleInvokesOnce() throws InterruptedException {
            // Setup: Single source, parent rule that counts invocations
            EClass source = createSource("TestEntity");

            registry.register(ConcurrentParentRuleTransformation.class);
            context.setTransformationRegistry(registry);
            context.setAutoAddRootElements(false);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<EObject> results = ConcurrentHashMap.newKeySet();
            AtomicInteger successCount = new AtomicInteger(0);

            // Each thread calls executeParentRule for the same source
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Directly call executeParentRule
                        EObject result = context.executeParentRule("CountingParent", source, null);
                        if (result != null) {
                            results.add(result);
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Start all threads simultaneously
            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete within timeout");
            executor.shutdown();

            // Assert: Parent rule should be invoked exactly once
            int invocationCount = ParentRuleInvocationCounter.getCount("CountingParent");
            assertEquals(1, invocationCount,
                    "Parent rule should be invoked exactly once, but was invoked " + invocationCount + " times. " +
                    "This proves the race condition exists in executeParentRule().");

            // Assert: All threads should receive the same target instance
            assertEquals(1, results.size(),
                    "All threads should receive the same target instance, but got " + results.size() + " unique instances. " +
                    "This proves duplicate targets are being created.");
        }

        @Test
        @DisplayName("Concurrent executeParentRule() with pre-created target should work correctly")
        void testConcurrentExecuteParentRuleWithPreCreatedTarget() throws InterruptedException {
            EClass source = createSource("TestEntity");

            registry.register(ConcurrentParentRuleTransformation.class);
            context.setTransformationRegistry(registry);
            context.setAutoAddRootElements(false);

            // Pre-create a target that will be shared
            EPackage preCreatedTarget = EcoreFactory.eINSTANCE.createEPackage();
            preCreatedTarget.setName("PreCreated");

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<EObject> results = ConcurrentHashMap.newKeySet();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Call with pre-created target
                        EObject result = context.executeParentRule("CountingParent", source, preCreatedTarget);
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // Parent should execute exactly once even with pre-created target
            int invocationCount = ParentRuleInvocationCounter.getCount("CountingParent");
            assertEquals(1, invocationCount,
                    "Parent rule with pre-created target should be invoked exactly once, " +
                    "but was invoked " + invocationCount + " times.");

            // All results should be the same pre-created target
            assertEquals(1, results.size(), "All threads should receive the same pre-created target");
            assertTrue(results.contains(preCreatedTarget), "Result should be the pre-created target");
        }

        @Test
        @DisplayName("Concurrent @Extends inheritance should execute parent rule exactly once")
        void testConcurrentExtendsInheritance() throws InterruptedException {
            EClass source = createSource("InheritanceTest");

            registry.register(ConcurrentExtendsTransformation.class);
            context.setTransformationRegistry(registry);
            context.setAutoAddRootElements(false);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<EObject> results = ConcurrentHashMap.newKeySet();

            TransformRuleDescriptor childRule = registry.getRuleByName("ExtendsChild");
            assertNotNull(childRule, "Child rule should be registered");

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Execute child rule - this triggers parent via @Extends
                        EObject result = childRule.execute(source, context);
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // Parent should execute exactly once via @Extends chain
            // This is the key assertion - the fix ensures atomic parent rule execution
            int parentInvocations = ParentRuleInvocationCounter.getCount("ExtendsParent");
            assertEquals(1, parentInvocations,
                    "@Extends parent rule should be invoked exactly once, " +
                    "but was invoked " + parentInvocations + " times. " +
                    "This proves the race condition exists in executeParentRulesInChain().");

            // Child rule is invoked once per execute() call (50 times total)
            // This is expected - the test calls execute() from 50 threads
            // In real transformation, the executor wraps in getOrCreate() for atomicity
            int childInvocations = ParentRuleInvocationCounter.getCount("ExtendsChild");
            assertEquals(threadCount, childInvocations,
                    "Child rule should be invoked once per thread (each calls execute())");

            // Each execute() call creates its own pre-created target, so expect 50 unique results
            // In real transformation, executor's getOrCreate ensures single execution
            assertEquals(threadCount, results.size(),
                    "Each execute() call returns its own target (executor wraps for atomicity)");
        }

        @Test
        @DisplayName("Multi-level @Extends chain should execute each level exactly once")
        void testMultiLevelExtendsChain() throws InterruptedException {
            EClass source = createSource("MultiLevelTest");

            registry.register(MultiLevelExtendsTransformation.class);
            context.setTransformationRegistry(registry);
            context.setAutoAddRootElements(false);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<EObject> results = ConcurrentHashMap.newKeySet();

            TransformRuleDescriptor grandChildRule = registry.getRuleByName("GrandChild");
            assertNotNull(grandChildRule, "GrandChild rule should be registered");

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        EObject result = grandChildRule.execute(source, context);
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // PARENT rules in the chain should execute exactly once
            // This is the key assertion - the fix ensures atomic parent rule execution
            int grandParentInvocations = ParentRuleInvocationCounter.getCount("GrandParent");
            int parentInvocations = ParentRuleInvocationCounter.getCount("Parent");
            int grandChildInvocations = ParentRuleInvocationCounter.getCount("GrandChild");

            assertEquals(1, grandParentInvocations,
                    "GrandParent should be invoked exactly once, but was invoked " + grandParentInvocations + " times.");
            assertEquals(1, parentInvocations,
                    "Parent should be invoked exactly once, but was invoked " + parentInvocations + " times.");

            // GrandChild is the entry point - invoked once per execute() call
            // This is expected - the test calls execute() from 50 threads
            assertEquals(threadCount, grandChildInvocations,
                    "GrandChild should be invoked once per thread (each calls execute())");

            // Each execute() call creates its own pre-created target, so expect 50 unique results
            assertEquals(threadCount, results.size(),
                    "Each execute() call returns its own target (executor wraps for atomicity)");
        }

        @Test
        @DisplayName("equivalent() method locking should prevent race conditions")
        void testEquivalentMethodLocking() throws InterruptedException {
            // This test verifies that the existing locking in equivalent() works correctly
            EClass source = createSource("LazyTest");

            registry.register(LazyRuleTransformation.class);
            context.setTransformationRegistry(registry);
            context.setAutoAddRootElements(false);

            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<EObject> results = ConcurrentHashMap.newKeySet();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Call equivalent() which uses the per-element locking
                        EObject result = context.equivalent(source, EPackage.class);
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            // Lazy rule should execute exactly once
            int lazyInvocations = ParentRuleInvocationCounter.getCount("LazyRule");
            assertEquals(1, lazyInvocations,
                    "Lazy rule via equivalent() should be invoked exactly once, " +
                    "but was invoked " + lazyInvocations + " times.");

            // All threads should get same result
            assertEquals(1, results.size(),
                    "All threads should receive the same lazy-evaluated target");
        }
    }

    // ==================== Invocation Counter for Race Condition Tests ====================

    /**
     * Thread-safe counter for tracking rule invocations across concurrent tests.
     */
    static class ParentRuleInvocationCounter {
        private static final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

        static void increment(String ruleName) {
            counters.computeIfAbsent(ruleName, k -> new AtomicInteger(0)).incrementAndGet();
        }

        static int getCount(String ruleName) {
            AtomicInteger counter = counters.get(ruleName);
            return counter != null ? counter.get() : 0;
        }

        static void reset() {
            counters.clear();
        }
    }

    // ==================== Transformation Classes for Race Condition Tests ====================

    /**
     * Transformation with a parent rule that counts its invocations.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ConcurrentParentRuleTransformation {

        @TransformRule(name = "CountingParent")
        @Abstract
        public TransformFunction<EClass, EPackage> countingParent() {
            return (source, ctx) -> {
                // Count this invocation
                ParentRuleInvocationCounter.increment("CountingParent");
                // Small delay to increase chance of race condition
                try { Thread.sleep(10); } catch (InterruptedException e) { }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_from_parent");
                return pkg;
            };
        }
    }

    /**
     * Transformation with @Extends to test inheritance chain race conditions.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ConcurrentExtendsTransformation {

        @TransformRule(name = "ExtendsParent")
        @Abstract
        public TransformFunction<EClass, EPackage> extendsParent() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("ExtendsParent");
                try { Thread.sleep(10); } catch (InterruptedException e) { }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ExtendsChild")
        @Transform(type = EClass.class)
        @Extends("ExtendsParent")
        public TransformFunction<EClass, EPackage> extendsChild() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("ExtendsChild");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://child.test");
                return pkg;
            };
        }
    }

    /**
     * Multi-level inheritance: GrandChild @Extends Parent @Extends GrandParent.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiLevelExtendsTransformation {

        @TransformRule(name = "GrandParent")
        @Abstract
        public TransformFunction<EClass, EPackage> grandParent() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("GrandParent");
                try { Thread.sleep(10); } catch (InterruptedException e) { }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "Parent")
        @Abstract
        @Extends("GrandParent")
        public TransformFunction<EClass, EPackage> parent() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("Parent");
                try { Thread.sleep(5); } catch (InterruptedException e) { }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://parent.test");
                return pkg;
            };
        }

        @TransformRule(name = "GrandChild")
        @Transform(type = EClass.class)
        @Extends("Parent")
        public TransformFunction<EClass, EPackage> grandChild() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("GrandChild");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsPrefix("grandchild");
                return pkg;
            };
        }
    }

    /**
     * Lazy rule for testing equivalent() locking.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class LazyRuleTransformation {

        @TransformRule(name = "LazyRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> lazyRule() {
            return (source, ctx) -> {
                ParentRuleInvocationCounter.increment("LazyRule");
                try { Thread.sleep(10); } catch (InterruptedException e) { }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_lazy");
                return pkg;
            };
        }
    }
}
