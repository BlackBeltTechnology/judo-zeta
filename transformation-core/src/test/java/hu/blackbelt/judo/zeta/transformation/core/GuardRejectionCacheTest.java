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
 * Tests for guard-method-level caching in {@link TransformationContext}.
 *
 * <p>Verifies that guard results are cached by {@code (guardMethod, source)} and shared
 * across all rules referencing the same guard method, avoiding redundant evaluations.</p>
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
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Rejected");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(RejectingGuardTransformation.class);

        RejectingGuardTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("RejectingRule");
        assertNotNull(rule);

        TransformationContext ctx = createContext(registry);

        // First call - guard evaluates and rejects
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
    void testSharedGuardMethodCacheIsSharedAcrossRules() {
        // Task 4.1: Verify that rules sharing the same guard method share cached results
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Shared");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(SharedGuardTransformation.class);

        SharedGuardTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule1 = registry.getRuleByName("SharedGuardRule1");
        TransformRuleDescriptor rule2 = registry.getRuleByName("SharedGuardRule2");

        TransformationContext ctx = createContext(registry);

        // Rule1 evaluates guard (should invoke it once)
        boolean result1 = rule1.evaluateGuard(sourceClass, ctx);
        assertFalse(result1, "Rule1 should reject");
        assertEquals(1, SharedGuardTransformation.guardCallCount.get(), "Guard invoked once for Rule1");

        // Rule2 evaluates same guard method for same source — should get cache hit
        boolean result2 = rule2.evaluateGuard(sourceClass, ctx);
        assertFalse(result2, "Rule2 should also reject (shared guard method cache)");
        assertEquals(1, SharedGuardTransformation.guardCallCount.get(),
                "Guard should NOT be invoked again — Rule2 shares the cache with Rule1");
    }

    @Test
    void testDifferentGuardMethodsAreCachedIndependently() {
        // Task 4.2: Rules with different guard methods have independent cache entries
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

        // Rule1 rejects — uses its own guard method
        boolean result1 = rule1.evaluateGuard(sourceClass, ctx);
        assertFalse(result1, "Rule1 should reject");
        assertEquals(1, IndependentRulesTransformation.rule1GuardCallCount.get());

        // Rule2 accepts — uses a different guard method, evaluated independently
        boolean result2 = rule2.evaluateGuard(sourceClass, ctx);
        assertTrue(result2, "Rule2 should accept (different guard method)");
        assertEquals(1, IndependentRulesTransformation.rule2GuardCallCount.get());

        // Both results are now cached — subsequent calls use cache
        rule1.evaluateGuard(sourceClass, ctx);
        assertEquals(1, IndependentRulesTransformation.rule1GuardCallCount.get(), "Rule1 guard cached");

        rule2.evaluateGuard(sourceClass, ctx);
        assertEquals(1, IndependentRulesTransformation.rule2GuardCallCount.get(),
                "Rule2 guard also cached (true results are cached too)");
    }

    @Test
    void testConcurrentRejectionRecordingIsThreadSafe() throws InterruptedException {
        // Task 4.3: Thread-safety of guard method cache
        int threadCount = 10;
        int elementsPerThread = 100;

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

        // Each element should have guard called at most once (benign race: may be called slightly
        // more than once if two threads race before either can cache, but practically once)
        int totalElements = threadCount * elementsPerThread;
        int calls = ConcurrentRejectTransformation.guardCallCount.get();
        assertTrue(calls >= 1 && calls <= totalElements,
                "Guard calls should be between 1 and total elements, but was: " + calls);
    }

    @Test
    void testExecutorResetClearsGuardCache() {
        // Task 4.4: Fresh context (executor reset) clears guard cache
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

        // Second transformation (executor reused) — reset() clears guard cache
        executor.transform();
        int callsAfterSecond = ExecutorResetTransformation.guardCallCount.get();
        assertEquals(callsAfterFirst * 2, callsAfterSecond,
                "Guard should be called again after executor reset (guard cache cleared)");
    }

    // ========== Functional-Interface Guard Tests ==========

    @Test
    void testFunctionalGuardIsResolvedAndCached() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Rejected");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(FunctionalGuardTransformation.class);

        FunctionalGuardTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("FunctionalGuardRule");
        assertNotNull(rule);

        TransformationContext ctx = createContext(registry);

        // First call - guard evaluates and rejects
        boolean result1 = rule.evaluateGuard(sourceClass, ctx);
        assertFalse(result1, "Functional guard should reject element");
        assertEquals(1, FunctionalGuardTransformation.guardCallCount.get(), "Guard lambda should be called once");

        // Second call - should use guard method cache
        boolean result2 = rule.evaluateGuard(sourceClass, ctx);
        assertFalse(result2, "Guard should still reject (cached)");
        assertEquals(1, FunctionalGuardTransformation.guardCallCount.get(), "Guard should NOT be called again (cached)");
    }

    @Test
    void testFunctionalGuardAcceptsElements() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Accepted");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(FunctionalAcceptGuardTransformation.class);

        FunctionalAcceptGuardTransformation.guardCallCount.set(0);

        TransformRuleDescriptor rule = registry.getRuleByName("FunctionalAcceptRule");
        TransformationContext ctx = createContext(registry);

        boolean result = rule.evaluateGuard(sourceClass, ctx);
        assertTrue(result, "Functional guard should accept element");
        assertEquals(1, FunctionalAcceptGuardTransformation.guardCallCount.get());
    }

    @Test
    void testMixedOldAndNewGuardStyles() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Test");
        sourceResource.getContents().add(sourceClass);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(MixedGuardStylesTransformation.class);

        MixedGuardStylesTransformation.oldStyleCallCount.set(0);
        MixedGuardStylesTransformation.newStyleCallCount.set(0);

        TransformRuleDescriptor oldRule = registry.getRuleByName("OldStyleRule");
        TransformRuleDescriptor newRule = registry.getRuleByName("NewStyleRule");

        TransformationContext ctx = createContext(registry);

        // Both styles should work
        boolean oldResult = oldRule.evaluateGuard(sourceClass, ctx);
        boolean newResult = newRule.evaluateGuard(sourceClass, ctx);

        assertFalse(oldResult, "Old-style guard should reject");
        assertFalse(newResult, "New-style guard should reject");
        assertEquals(1, MixedGuardStylesTransformation.oldStyleCallCount.get());
        assertEquals(1, MixedGuardStylesTransformation.newStyleCallCount.get());

        // Both should cache rejections
        oldRule.evaluateGuard(sourceClass, ctx);
        newRule.evaluateGuard(sourceClass, ctx);
        assertEquals(1, MixedGuardStylesTransformation.oldStyleCallCount.get(), "Old-style cached");
        assertEquals(1, MixedGuardStylesTransformation.newStyleCallCount.get(), "New-style cached");
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

    /**
     * Two rules share the same guard method name and the same guard method object.
     * The guard method cache should be a hit for Rule2 after Rule1 evaluates it.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SharedGuardTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "SharedGuardRule1")
        @Guard(method = "sharedGuard")
        public TransformFunction<EClass, EClass> rule1() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        @TransformRule(name = "SharedGuardRule2")
        @Guard(method = "sharedGuard")
        public TransformFunction<EClass, EClass> rule2() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        public boolean sharedGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return false; // Reject all
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

    // ========== Functional-Interface Guard Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class FunctionalGuardTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "FunctionalGuardRule")
        @Guard(method = "functionalRejectGuard")
        public TransformFunction<EClass, EClass> functionalGuardRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        // New-style: returns TransformGuard instead of boolean
        public TransformGuard functionalRejectGuard() {
            return (source, ctx) -> {
                guardCallCount.incrementAndGet();
                return false; // Always reject
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class FunctionalAcceptGuardTransformation {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "FunctionalAcceptRule")
        @Guard(method = "functionalAcceptGuard")
        public TransformFunction<EClass, EClass> functionalAcceptRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        // New-style: returns TransformGuard
        public TransformGuard functionalAcceptGuard() {
            return (source, ctx) -> {
                guardCallCount.incrementAndGet();
                return true; // Always accept
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class MixedGuardStylesTransformation {
        static final AtomicInteger oldStyleCallCount = new AtomicInteger(0);
        static final AtomicInteger newStyleCallCount = new AtomicInteger(0);

        @TransformRule(name = "OldStyleRule")
        @Guard(method = "oldStyleGuard")
        public TransformFunction<EClass, EClass> oldStyleRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        @TransformRule(name = "NewStyleRule")
        @Guard(method = "newStyleGuard")
        public TransformFunction<EClass, EClass> newStyleRule() {
            return (source, ctx) -> ctx.createTarget(EClass.class);
        }

        // Old-style: boolean return, takes (EObject, TransformationContext)
        public boolean oldStyleGuard(EObject source, TransformationContext ctx) {
            oldStyleCallCount.incrementAndGet();
            return false;
        }

        // New-style: returns TransformGuard, no parameters
        public TransformGuard newStyleGuard() {
            return (source, ctx) -> {
                newStyleCallCount.incrementAndGet();
                return false;
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
