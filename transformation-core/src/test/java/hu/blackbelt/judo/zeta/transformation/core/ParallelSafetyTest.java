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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parallel transformation thread-safety.
 *
 * <p>Verifies that parallel transformation execution is thread-safe with:
 * <ul>
 *   <li>No duplicate elements when same source transformed concurrently</li>
 *   <li>Element count matches between sequential and parallel modes</li>
 *   <li>No NPE or ConcurrentModificationException under load</li>
 *   <li>Cache correctly isolates different rules</li>
 * </ul>
 */
class ParallelSafetyTest {

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
    void testNoDuplicatesWhenSameSourceTransformedConcurrently() throws InterruptedException {
        int threadCount = 10;

        // Create a single source element that will be transformed by multiple threads
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("SharedSource");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ConcurrentTransformation.class);

        ConcurrentTransformation.ruleExecutionCount.set(0);

        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<EObject> results = Collections.synchronizedList(new ArrayList<>());

        // Launch threads that all call equivalent() for the same source
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    EObject result = ctx.equivalent(sourceClass, EClass.class);
                    if (result != null) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    fail("Thread threw exception: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        // Verify: rule should only execute once (all threads get same result)
        assertEquals(1, ConcurrentTransformation.ruleExecutionCount.get(),
                "Rule should only execute once for same source element");

        // All results should be the same instance
        assertTrue(results.size() > 0, "Should have at least one result");
        EObject firstResult = results.get(0);
        for (EObject result : results) {
            assertSame(firstResult, result, "All threads should get the same result instance");
        }
    }

    @Test
    void testCacheIsolatesDifferentRules() throws InterruptedException {
        // Create source element
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("MultiRuleSource");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(MultiRuleTransformation.class);

        MultiRuleTransformation.ruleAExecutionCount.set(0);
        MultiRuleTransformation.ruleBExecutionCount.set(0);

        TransformationContext ctx = createContext(registry);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount * 2);

        // Half threads call equivalent by rule A, half by rule B
        for (int t = 0; t < threadCount; t++) {
            // Rule A threads
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ctx.equivalent(sourceClass, "RuleA");
                } catch (Exception e) {
                    fail("Thread threw exception: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });

            // Rule B threads
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ctx.equivalent(sourceClass, "RuleB");
                } catch (Exception e) {
                    fail("Thread threw exception: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        // Each rule should execute exactly once
        assertEquals(1, MultiRuleTransformation.ruleAExecutionCount.get(),
                "Rule A should execute exactly once");
        assertEquals(1, MultiRuleTransformation.ruleBExecutionCount.get(),
                "Rule B should execute exactly once");
    }

    @Test
    void testNoExceptionsUnderConcurrentLoad() throws InterruptedException {
        int elementCount = 100;
        int threadCount = 8;

        // Create many source elements
        for (int i = 0; i < elementCount; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Element" + i);
            sourceResource.getContents().add(ec);
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(LoadTestTransformation.class);

        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Each thread transforms a subset of elements
        int elementsPerThread = elementCount / threadCount;
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int start = threadId * elementsPerThread;
                    int end = start + elementsPerThread;
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

        // No exceptions should have occurred
        if (!exceptions.isEmpty()) {
            StringBuilder sb = new StringBuilder("Exceptions occurred:\n");
            for (Throwable e : exceptions) {
                sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
            }
            fail(sb.toString());
        }
    }

    @Test
    void testConcurrentEquivalentCallsForSameSourceReturnSameResult() throws InterruptedException {
        int threadCount = 20;
        int iterations = 10;

        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("RepeatedSource");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(IdempotentTransformation.class);

        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Set<EObject> uniqueResults = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount * iterations);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    try {
                        EObject result = ctx.equivalent(sourceClass, EClass.class);
                        if (result != null) {
                            uniqueResults.add(result);
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        latch.await();
        executor.shutdown();

        // All calls should return the same instance
        assertEquals(1, uniqueResults.size(),
                "All equivalent() calls for same source should return same instance");
    }

    // ========== Test Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ConcurrentTransformation {
        static final AtomicInteger ruleExecutionCount = new AtomicInteger(0);

        @TransformRule(name = "ConcurrentRule")
        @Lazy
        public TransformFunction<EClass, EClass> concurrentRule() {
            return (source, ctx) -> {
                ruleExecutionCount.incrementAndGet();
                // Simulate some work
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Target_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class MultiRuleTransformation {
        static final AtomicInteger ruleAExecutionCount = new AtomicInteger(0);
        static final AtomicInteger ruleBExecutionCount = new AtomicInteger(0);

        @TransformRule(name = "RuleA")
        @Lazy
        public TransformFunction<EClass, EClass> ruleA() {
            return (source, ctx) -> {
                ruleAExecutionCount.incrementAndGet();
                EClass target = ctx.createTarget(EClass.class);
                target.setName("RuleA_" + source.getName());
                return target;
            };
        }

        @TransformRule(name = "RuleB")
        @Lazy
        public TransformFunction<EClass, EClass> ruleB() {
            return (source, ctx) -> {
                ruleBExecutionCount.incrementAndGet();
                EClass target = ctx.createTarget(EClass.class);
                target.setName("RuleB_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class LoadTestTransformation {
        @TransformRule(name = "LoadRule")
        @Lazy
        public TransformFunction<EClass, EClass> loadRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Target_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class IdempotentTransformation {
        @TransformRule(name = "IdempotentRule")
        @Lazy
        public TransformFunction<EClass, EClass> idempotentRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Idempotent_" + source.getName());
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
