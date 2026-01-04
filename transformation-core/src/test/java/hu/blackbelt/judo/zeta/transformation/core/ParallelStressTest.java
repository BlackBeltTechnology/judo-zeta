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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests for parallel transformation under high concurrency.
 *
 * <p>Tests with:
 * <ul>
 *   <li>16+ threads</li>
 *   <li>1000+ source elements</li>
 *   <li>Multiple runs for determinism verification</li>
 * </ul>
 */
class ParallelStressTest {

    private static final Logger log = LoggerFactory.getLogger(ParallelStressTest.class);

    private static final int HIGH_THREAD_COUNT = 16;
    private static final int ELEMENT_COUNT = 1000;
    private static final int DETERMINISM_RUNS = 5;

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
    void testHighConcurrencyNoExceptions() throws InterruptedException {
        log.info("Starting high concurrency test with {} threads and {} elements",
                HIGH_THREAD_COUNT, ELEMENT_COUNT);

        // Create many source elements
        for (int i = 0; i < ELEMENT_COUNT; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("StressElement" + i);
            sourceResource.getContents().add(ec);
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(StressTransformation.class);

        StressTransformation.executionCount.set(0);

        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(HIGH_THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(HIGH_THREAD_COUNT);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.currentTimeMillis();

        // Distribute elements across threads
        int elementsPerThread = ELEMENT_COUNT / HIGH_THREAD_COUNT;
        for (int t = 0; t < HIGH_THREAD_COUNT; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int start = threadId * elementsPerThread;
                    int end = (threadId == HIGH_THREAD_COUNT - 1)
                            ? ELEMENT_COUNT
                            : start + elementsPerThread;

                    for (int i = start; i < end; i++) {
                        EObject source = sourceResource.getContents().get(i);
                        ctx.equivalent(source, EClass.class);
                    }
                } catch (Throwable e) {
                    exceptions.add(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Stress test completed in {}ms, {} rule executions",
                duration, StressTransformation.executionCount.get());

        // Verify no exceptions
        if (!exceptions.isEmpty()) {
            StringBuilder sb = new StringBuilder("Exceptions during stress test:\n");
            for (Throwable e : exceptions) {
                sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
                e.printStackTrace();
            }
            fail(sb.toString());
        }

        // Each element should only be transformed once
        assertEquals(ELEMENT_COUNT, StressTransformation.executionCount.get(),
                "Each element should be transformed exactly once");
    }

    @Test
    void testDeterministicResultsAcrossMultipleRuns() throws InterruptedException {
        log.info("Starting determinism test with {} runs", DETERMINISM_RUNS);

        // Create source elements
        for (int i = 0; i < 100; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("DeterminismElement" + i);
            sourceResource.getContents().add(ec);
        }

        List<Set<String>> resultSets = new ArrayList<>();

        for (int run = 0; run < DETERMINISM_RUNS; run++) {
            // Reset target
            targetResourceSet = new ResourceSetImpl();
            targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(DeterminismTransformation.class);

            TransformationContext ctx = createContext(registry);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(8);

            int elementsPerThread = 100 / 8;
            for (int t = 0; t < 8; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        int start = threadId * elementsPerThread;
                        int end = (threadId == 7) ? 100 : start + elementsPerThread;

                        for (int i = start; i < end; i++) {
                            EObject source = sourceResource.getContents().get(i);
                            ctx.equivalent(source, EClass.class);
                        }
                    } catch (Exception e) {
                        // Ignore for this test
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // Collect result names
            Set<String> resultNames = new HashSet<>();
            for (EObject source : sourceResource.getContents()) {
                EClass result = ctx.equivalent((EClass) source, EClass.class);
                if (result != null) {
                    resultNames.add(result.getName());
                }
            }
            resultSets.add(resultNames);
        }

        // All runs should produce the same results
        Set<String> firstRun = resultSets.get(0);
        for (int i = 1; i < resultSets.size(); i++) {
            assertEquals(firstRun, resultSets.get(i),
                    "Run " + i + " should produce same results as run 0");
        }

        log.info("All {} runs produced identical results with {} elements",
                DETERMINISM_RUNS, firstRun.size());
    }

    @Test
    void testConcurrentAccessToSameElements() throws InterruptedException {
        log.info("Starting concurrent same-element access test");

        // Create fewer elements but have many threads access the same ones
        int elementCount = 50;
        int threadCount = 20;
        int accessesPerThread = 100;

        for (int i = 0; i < elementCount; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("SharedElement" + i);
            sourceResource.getContents().add(ec);
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(SharedAccessTransformation.class);

        SharedAccessTransformation.executionCount.set(0);

        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        Random random = new Random(42); // Fixed seed for reproducibility

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < accessesPerThread; i++) {
                        // Random access to elements
                        int idx = random.nextInt(elementCount);
                        EObject source = sourceResource.getContents().get(idx);
                        ctx.equivalent(source, EClass.class);
                    }
                } catch (Throwable e) {
                    exceptions.add(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        // No exceptions
        if (!exceptions.isEmpty()) {
            fail("Exceptions occurred during concurrent access: " + exceptions.get(0).getMessage());
        }

        // Each element should be transformed exactly once despite multiple accesses
        assertEquals(elementCount, SharedAccessTransformation.executionCount.get(),
                "Each element should be transformed exactly once");

        log.info("Concurrent access test completed: {} elements, {} total accesses, {} transformations",
                elementCount, threadCount * accessesPerThread, SharedAccessTransformation.executionCount.get());
    }

    // ========== Test Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class StressTransformation {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "StressRule")
        @Lazy
        public TransformFunction<EClass, EClass> stressRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Stress_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class DeterminismTransformation {
        @TransformRule(name = "DeterminismRule")
        @Lazy
        public TransformFunction<EClass, EClass> determinismRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Determinism_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SharedAccessTransformation {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "SharedAccessRule")
        @Lazy
        public TransformFunction<EClass, EClass> sharedAccessRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Shared_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Simple ModelProvider implementation for tests.
     */
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
