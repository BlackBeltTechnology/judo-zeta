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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ElementResolutionCache.
 */
class ElementResolutionCacheTest {

    private ElementResolutionCache cache;
    private EObject sourceElement;
    private EObject targetElement1;
    private EObject targetElement2;

    @BeforeEach
    void setUp() {
        cache = new ElementResolutionCache();
        
        // Create test EObjects using Ecore metamodel
        sourceElement = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) sourceElement).setName("SourceClass");
        
        targetElement1 = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) targetElement1).setName("TargetClass1");
        
        targetElement2 = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) targetElement2).setName("TargetClass2");
    }

    @Test
    void testAddAndGetByRule() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject result = cache.getByRule(sourceElement, "Rule1");
        
        assertNotNull(result);
        assertSame(targetElement1, result);
    }

    @Test
    void testGetByRuleReturnsNullForUnknownRule() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject result = cache.getByRule(sourceElement, "UnknownRule");
        
        assertNull(result);
    }

    @Test
    void testGetByRuleReturnsNullForUnknownSource() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject unknownSource = EcoreFactory.eINSTANCE.createEClass();
        EObject result = cache.getByRule(unknownSource, "Rule1");
        
        assertNull(result);
    }

    @Test
    void testGetEquivalentReturnsPrimaryFirst() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, true); // primary
        
        EClass result = cache.getEquivalent(sourceElement, EClass.class);
        
        assertNotNull(result);
        assertSame(targetElement2, result); // Primary should be returned
    }

    @Test
    void testGetEquivalentReturnsFirstWhenNoPrimary() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        
        EClass result = cache.getEquivalent(sourceElement, EClass.class);
        
        assertNotNull(result);
        assertSame(targetElement1, result); // First added should be returned
    }

    @Test
    void testGetEquivalents() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        
        List<EClass> results = cache.getEquivalents(sourceElement, EClass.class);
        
        assertEquals(2, results.size());
        assertTrue(results.contains(targetElement1));
        assertTrue(results.contains(targetElement2));
    }

    @Test
    void testGetEquivalentsReturnsEmptyListForUnknownSource() {
        EObject unknownSource = EcoreFactory.eINSTANCE.createEClass();
        
        List<EClass> results = cache.getEquivalents(unknownSource, EClass.class);
        
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testDiscriminatedMapping() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "create");
        cache.addDiscriminatedMapping(sourceElement, targetElement2, "Rule1", "update");
        
        EClass createResult = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "create");
        EClass updateResult = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "update");
        
        assertSame(targetElement1, createResult);
        assertSame(targetElement2, updateResult);
    }

    @Test
    void testDiscriminatedMappingReturnsNullForUnknownDiscriminator() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "create");
        
        EClass result = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "delete");
        
        assertNull(result);
    }

    @Test
    void testGetAllMappings() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, true);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule3", "disc1");
        
        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        
        assertEquals(3, mappings.size());
    }

    @Test
    void testClear() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addDiscriminatedMapping(sourceElement, targetElement2, "Rule2", "disc");
        
        cache.clear();
        
        assertNull(cache.getByRule(sourceElement, "Rule1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule2", "disc"));
        assertTrue(cache.getAllMappings().isEmpty());
    }

    @Test
    void testTraceEntryProperties() {
        cache.addMapping(sourceElement, "TestRule", targetElement1, true);
        
        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        ElementResolutionCache.TraceEntry entry = mappings.iterator().next();
        
        assertSame(sourceElement, entry.getSource());
        assertSame(targetElement1, entry.getTarget());
        assertEquals("TestRule", entry.getRuleName());
        assertTrue(entry.isPrimary());
        assertNull(entry.getDiscriminator());
    }

    @Test
    void testDiscriminatedTraceEntryProperties() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "DiscRule", "myDisc");

        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        ElementResolutionCache.TraceEntry entry = mappings.iterator().next();

        assertSame(sourceElement, entry.getSource());
        assertSame(targetElement1, entry.getTarget());
        assertEquals("DiscRule", entry.getRuleName());
        assertEquals("myDisc", entry.getDiscriminator());
        assertFalse(entry.isPrimary());
    }

    // ==================== Null Parameter Handling Tests ====================

    @Test
    void testAddDiscriminatedMappingWithNullSourceDoesNotThrow() {
        // Should not throw NPE - ConcurrentHashMap doesn't allow null keys
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(null, targetElement1, "Rule1", "disc1"));

        // Should not be cached
        assertNull(cache.getEquivalentDiscriminated(null, EClass.class, "Rule1", "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullTargetDoesNotThrow() {
        // Should not throw NPE - null targets are silently ignored
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, null, "Rule1", "disc1"));

        // Should not be cached
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullRuleNameDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, targetElement1, null, "disc1"));

        // Should not be cached under null rule name
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, null, "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullDiscriminatorDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", null));

        // Should not be cached under null discriminator
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", null));
    }

    @Test
    void testAddDiscriminatedMappingWithAllNullsDoesNotThrow() {
        // Should not throw NPE even with all nulls
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(null, null, null, null));
    }

    @Test
    void testAddMappingWithNullSourceDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(null, "Rule1", targetElement1, false));
    }

    @Test
    void testAddMappingWithNullTargetDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(sourceElement, "Rule1", null, false));
    }

    @Test
    void testAddMappingWithNullRuleNameDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(sourceElement, null, targetElement1, false));
    }

    @Test
    void testGetByRuleWithNullsReturnsNull() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);

        assertNull(cache.getByRule(null, "Rule1"));
        assertNull(cache.getByRule(sourceElement, null));
        assertNull(cache.getByRule(null, null));
    }

    @Test
    void testGetEquivalentDiscriminatedWithNullsReturnsNull() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "disc1");

        assertNull(cache.getEquivalentDiscriminated(null, EClass.class, "Rule1", "disc1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, null, "disc1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", null));
    }

    // ==================== getOrCreate() Atomic Cache Tests ====================

    @Test
    void testGetOrCreateReturnsSupplierResultOnCacheMiss() {
        EObject result = cache.getOrCreate(sourceElement, "Rule1", () -> targetElement1, false);

        assertSame(targetElement1, result);
        // Verify it was cached
        assertSame(targetElement1, cache.getByRule(sourceElement, "Rule1"));
    }

    @Test
    void testGetOrCreateReturnsCachedValueOnHit() {
        // Pre-populate cache
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);

        AtomicInteger supplierCalls = new AtomicInteger(0);
        EObject result = cache.getOrCreate(sourceElement, "Rule1", () -> {
            supplierCalls.incrementAndGet();
            return targetElement2; // Would return different element
        }, false);

        // Should return cached value, not call supplier
        assertSame(targetElement1, result);
        assertEquals(0, supplierCalls.get());
    }

    @Test
    void testGetOrCreateCachesNullSupplierResult() {
        EObject result = cache.getOrCreate(sourceElement, "Rule1", () -> null, false);

        assertNull(result);
        // Null should NOT be cached
        assertNull(cache.getByRule(sourceElement, "Rule1"));
    }

    @Test
    void testGetOrCreateWithNullSourceReturnsNull() {
        EObject result = cache.getOrCreate(null, "Rule1", () -> targetElement1, false);
        assertNull(result);
    }

    @Test
    void testGetOrCreateWithNullRuleNameReturnsNull() {
        EObject result = cache.getOrCreate(sourceElement, null, () -> targetElement1, false);
        assertNull(result);
    }

    @Test
    void testGetOrCreateConcurrentCallsReturnSameInstance() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger supplierCalls = new AtomicInteger(0);
        AtomicReference<EObject>[] results = new AtomicReference[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            results[index] = new AtomicReference<>();
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to start together
                    EObject result = cache.getOrCreate(sourceElement, "Rule1", () -> {
                        supplierCalls.incrementAndGet();
                        // Create a new object each time to detect if supplier runs multiple times
                        EObject newTarget = EcoreFactory.eINSTANCE.createEClass();
                        ((EClass) newTarget).setName("Target_" + System.nanoTime());
                        return newTarget;
                    }, false);
                    results[index].set(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads at once
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // All threads should get the same instance
        EObject firstResult = results[0].get();
        assertNotNull(firstResult);
        for (int i = 1; i < threadCount; i++) {
            assertSame(firstResult, results[i].get(),
                    "Thread " + i + " should get same instance as thread 0");
        }

        // Supplier should only be called once
        assertEquals(1, supplierCalls.get(),
                "Supplier should only be invoked once despite concurrent calls");
    }

    @Test
    void testGetOrCreateDifferentKeysProceedInParallel() throws InterruptedException {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // Create different source elements for each thread
        EObject[] sources = new EObject[threadCount];
        for (int i = 0; i < threadCount; i++) {
            sources[i] = EcoreFactory.eINSTANCE.createEClass();
            ((EClass) sources[i]).setName("Source" + i);
        }

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    cache.getOrCreate(sources[index], "Rule1", () -> {
                        // Track concurrent executions
                        int current = concurrentExecutions.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));

                        // Simulate some work
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        concurrentExecutions.decrementAndGet();
                        EObject target = EcoreFactory.eINSTANCE.createEClass();
                        ((EClass) target).setName("Target" + index);
                        return target;
                    }, false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Different keys should execute in parallel (max concurrent > 1)
        assertTrue(maxConcurrent.get() > 1,
                "Different keys should execute concurrently, but maxConcurrent=" + maxConcurrent.get());
    }

    @Test
    void testGetOrCreateSetsPrimaryFlag() {
        cache.getOrCreate(sourceElement, "Rule1", () -> targetElement1, true);

        // Verify primary flag was set correctly
        EClass equivalent = cache.getEquivalent(sourceElement, EClass.class);
        assertSame(targetElement1, equivalent);

        Collection<ElementResolutionCache.TraceEntry> entries = cache.getAllMappings();
        assertEquals(1, entries.size());
        assertTrue(entries.iterator().next().isPrimary());
    }

    @Test
    void testGetOrCreateCacheIsClearedProperly() {
        cache.getOrCreate(sourceElement, "Rule1", () -> targetElement1, false);
        assertNotNull(cache.getByRule(sourceElement, "Rule1"));

        cache.clear();

        // After clear, cache should be empty and locks cleared
        assertNull(cache.getByRule(sourceElement, "Rule1"));

        // Should be able to create new entry
        EObject newResult = cache.getOrCreate(sourceElement, "Rule1", () -> targetElement2, false);
        assertSame(targetElement2, newResult);
    }
}
