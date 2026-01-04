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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ETL-compatible per-rule guard rejection caching.
 *
 * <p>Verifies that guards are cached per (rule, source) pair to avoid
 * redundant guard evaluations, matching ETL's TransformationRule.rejected behavior.</p>
 */
class GuardRejectionCacheTest {

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
    void testRejectionIsRecordedWhenGuardFails() {
        // Create source element
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Rejected");
        sourceResource.getContents().add(sourceClass);

        // Register transformation with guard that rejects "Rejected" elements
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(RejectingGuardTransformation.class);

        // Reset guard call counter
        RejectingGuardTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("RejectingRule");
        assertNotNull(rule);

        // First call - guard evaluates and rejects
        TransformationContext ctx = createContext(registry);

        boolean result1 = rule.evaluateGuard(sourceClass, ctx);
        assertFalse(result1, "Guard should reject element");
        assertEquals(1, RejectingGuardTransformation.guardCallCount.get(), "Guard should be called once");

        // Second call - should use cache, not re-evaluate guard
        boolean result2 = rule.evaluateGuard(sourceClass, ctx);
        assertFalse(result2, "Guard should still reject (cached)");
        assertEquals(1, RejectingGuardTransformation.guardCallCount.get(), "Guard should NOT be called again (cached)");
    }

    @Test
    void testSubsequentCallsReturnFalseImmediately() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("AlwaysReject");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(AlwaysRejectTransformation.class);

        AlwaysRejectTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("AlwaysRejectRule");
        TransformationContext ctx = createContext(registry);

        // Call multiple times
        for (int i = 0; i < 10; i++) {
            boolean result = rule.evaluateGuard(sourceClass, ctx);
            assertFalse(result);
        }

        // Guard should only be called once
        assertEquals(1, AlwaysRejectTransformation.guardCallCount.get(),
                "Guard should only be called once, rest should be cache hits");
    }

    @Test
    void testDifferentRulesHaveIndependentRejectedSets() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Test");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(IndependentRulesTransformation.class);

        IndependentRulesTransformation.rule1GuardCallCount.set(0);
        IndependentRulesTransformation.rule2GuardCallCount.set(0);

        TransformRuleDescriptor rule1 = registry.getRuleByName("Rule1");
        TransformRuleDescriptor rule2 = registry.getRuleByName("Rule2");

        TransformationContext ctx = createContext(registry);

        // Rule1 rejects
        boolean result1 = rule1.evaluateGuard(sourceClass, ctx);
        assertFalse(result1, "Rule1 should reject");
        assertEquals(1, IndependentRulesTransformation.rule1GuardCallCount.get());

        // Rule2 accepts (independent rejected set)
        boolean result2 = rule2.evaluateGuard(sourceClass, ctx);
        assertTrue(result2, "Rule2 should accept (independent)");
        assertEquals(1, IndependentRulesTransformation.rule2GuardCallCount.get());

        // Rule1 cached
        rule1.evaluateGuard(sourceClass, ctx);
        assertEquals(1, IndependentRulesTransformation.rule1GuardCallCount.get(), "Rule1 guard should be cached");

        // Rule2 should NOT be cached (it passed)
        rule2.evaluateGuard(sourceClass, ctx);
        assertEquals(2, IndependentRulesTransformation.rule2GuardCallCount.get(), "Rule2 guard should NOT be cached (passed)");
    }

    @Test
    void testConcurrentRejectionRecordingIsThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int elementsPerThread = 100;

        // Create many source elements
        for (int i = 0; i < threadCount * elementsPerThread; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Element" + i);
            sourceResource.getContents().add(ec);
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ConcurrentRejectTransformation.class);

        ConcurrentRejectTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("ConcurrentRejectRule");
        TransformationContext ctx = createContext(registry);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < elementsPerThread; i++) {
                        int idx = threadId * elementsPerThread + i;
                        EObject element = sourceResource.getContents().get(idx);
                        // Each thread evaluates guards for different elements
                        rule.evaluateGuard((EClass) element, ctx);
                        // And also tries to re-evaluate (should hit cache)
                        rule.evaluateGuard((EClass) element, ctx);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Each element should only have guard called once
        assertEquals(threadCount * elementsPerThread, ConcurrentRejectTransformation.guardCallCount.get(),
                "Guard should be called exactly once per element");
    }

    @Test
    void testClearRejectedClearsTheSet() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Cleared");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ClearableTransformation.class);

        ClearableTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("ClearableRule");
        TransformationContext ctx = createContext(registry);

        // First evaluation - guard called, rejected
        rule.evaluateGuard(sourceClass, ctx);
        assertEquals(1, ClearableTransformation.guardCallCount.get());

        // Second evaluation - cached
        rule.evaluateGuard(sourceClass, ctx);
        assertEquals(1, ClearableTransformation.guardCallCount.get());

        // Clear rejected set
        rule.clearRejected();

        // Third evaluation - guard called again (cache cleared)
        rule.evaluateGuard(sourceClass, ctx);
        assertEquals(2, ClearableTransformation.guardCallCount.get(), "Guard should be called again after clear");
    }

    @Test
    void testExecutorResetClearsAllRulesRejectedSets() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("ExecutorReset");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ExecutorResetTransformation.class);

        ExecutorResetTransformation.guardCallCount.set(0);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .build();

        TransformRuleDescriptor rule = registry.getRuleByName("ExecutorResetRule");

        // First transformation
        executor.transform();
        int callsAfterFirst = ExecutorResetTransformation.guardCallCount.get();
        assertTrue(callsAfterFirst > 0, "Guard should be called during transformation");

        // Second transformation (executor reused) - should clear rejected sets
        executor.transform();
        int callsAfterSecond = ExecutorResetTransformation.guardCallCount.get();
        assertEquals(callsAfterFirst * 2, callsAfterSecond,
                "Guard should be called again after executor reset (rejected sets cleared)");
    }

    // ========== Test Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class RejectingGuardTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "RejectingRule")
        @Guard(method = "rejectGuard")
        public TransformFunction<EClass, EClass> rejectingRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean rejectGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false; // Always reject
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class AlwaysRejectTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "AlwaysRejectRule")
        @Guard(method = "alwaysReject")
        public TransformFunction<EClass, EClass> alwaysRejectRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean alwaysReject(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false;
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class IndependentRulesTransformation {
        static final AtomicInteger rule1GuardCallCount = new AtomicInteger(0);
        static final AtomicInteger rule2GuardCallCount = new AtomicInteger(0);

        @TransformRule(name = "Rule1")
        @Guard(method = "rule1Guard")
        public TransformFunction<EClass, EClass> rule1() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        @TransformRule(name = "Rule2")
        @Guard(method = "rule2Guard")
        public TransformFunction<EClass, EClass> rule2() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean rule1Guard(EObject source, TransformationContext ctx) {
            rule1GuardCallCount.incrementAndGet();
            return false; // Reject
        }

        public boolean rule2Guard(EObject source, TransformationContext ctx) {
            rule2GuardCallCount.incrementAndGet();
            return true; // Accept
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ConcurrentRejectTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "ConcurrentRejectRule")
        @Guard(method = "concurrentGuard")
        public TransformFunction<EClass, EClass> concurrentRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean concurrentGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false; // Reject all
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ClearableTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "ClearableRule")
        @Guard(method = "clearableGuard")
        public TransformFunction<EClass, EClass> clearableRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean clearableGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false;
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ExecutorResetTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "ExecutorResetRule")
        @Guard(method = "executorResetGuard")
        public TransformFunction<EClass, EClass> executorResetRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean executorResetGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false;
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
