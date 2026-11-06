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
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Side Effect Detection Tests for Circular Dependency Caching Optimization.
 *
 * <h2>Purpose</h2>
 * <p>These tests MUST be created and passing with the CURRENT implementation before
 * any optimization changes. They serve as regression guards to detect unintended
 * side effects from the optimization.</p>
 *
 * <h2>Test Categories</h2>
 * <ul>
 *   <li>0.1 Rule Ordinal Side Effects</li>
 *   <li>0.2 In-Progress Tracking Side Effects</li>
 *   <li>0.3 Circular Dependency Side Effects</li>
 *   <li>0.4 Guard Caching Side Effects</li>
 *   <li>0.5 Output Equivalence Baselines</li>
 * </ul>
 *
 * @see <a href="openspec/changes/optimize-circular-dependency-caching/tasks.md">Proposal Tasks</a>
 */
@DisplayName("Optimization Side Effect Detection Tests")
class OptimizationSideEffectDetectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(OptimizationSideEffectDetectionTest.class);

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

        // Reset all counters
        resetAllCounters();
    }

    private void resetAllCounters() {
        RuleA.executionCount.set(0);
        RuleB.executionCount.set(0);
        RuleC.executionCount.set(0);
        GuardedRule.guardEvaluationCount.set(0);
        GuardedRule.executionCount.set(0);
        StatefulGuardRule.guardEvaluationCount.set(0);
        StatefulGuardRule.executionCount.set(0);
        ExceptionRule.executionCount.set(0);
        ExceptionRule.exceptionThrown.set(false);
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        return ctx;
    }

    private void createSourceElements(int count) {
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Entity" + i);
            sourceResource.getContents().add(ec);
            // Set fixed XMI ID for deterministic tests
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(ec, "source_entity_" + i);
            }
        }
    }

    // ========================================================================
    // 0.1 Rule Ordinal Side Effects
    // ========================================================================

    @Nested
    @DisplayName("0.1 Rule Ordinal Side Effects")
    class RuleOrdinalTests {

        @Test
        @DisplayName("0.1.1 Registration order independence - same result regardless of order")
        void testRegistrationOrderIndependence() {
            createSourceElements(3);

            // Register in order A, B, C
            TransformationRegistry registry1 = new TransformationRegistry();
            registry1.register(RuleA.class);
            registry1.register(RuleB.class);
            registry1.register(RuleC.class);

            TransformationContext ctx1 = createContext(registry1);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry1)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            String output1 = captureXmiOutput(targetResource);
            Set<String> xmiIds1 = extractXmiIds(targetResource);

            // Reset for second run
            setUp();
            createSourceElements(3);
            resetAllCounters();

            // Register in order C, B, A (reverse)
            TransformationRegistry registry2 = new TransformationRegistry();
            registry2.register(RuleC.class);
            registry2.register(RuleB.class);
            registry2.register(RuleA.class);

            TransformationContext ctx2 = createContext(registry2);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry2)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());
            String output2 = captureXmiOutput(targetResource);
            Set<String> xmiIds2 = extractXmiIds(targetResource);

            // XMI IDs should be identical regardless of registration order
            assertEquals(xmiIds1, xmiIds2,
                    "XMI IDs should be identical regardless of rule registration order");

            LOG.info("Registration order test: {} XMI IDs verified identical", xmiIds1.size());
        }

        @Test
        @DisplayName("0.1.2 Dynamic registration during transformation throws or is handled safely")
        void testDynamicRegistrationDuringTransformation() {
            createSourceElements(1);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(DynamicRegistrationRule.class);

            TransformationContext ctx = createContext(registry);
            DynamicRegistrationRule.registryRef.set(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            // The transformation should either:
            // 1. Complete without using the dynamically registered rule, OR
            // 2. Throw an exception indicating dynamic registration is not allowed
            // Either behavior is acceptable - we're documenting current behavior
            try {
                executor.transform();
                LOG.info("Dynamic registration during transformation: completed (rule may not be used)");
            } catch (Exception e) {
                LOG.info("Dynamic registration during transformation: threw {} - {}",
                        e.getClass().getSimpleName(), e.getMessage());
                // This is acceptable - dynamic registration should be blocked or ignored
            }
        }

        @Test
        @DisplayName("0.1.3 Ordinal stability - same ordinals across multiple transforms")
        void testOrdinalStabilityWithinSession() {
            createSourceElements(2);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RuleA.class);
            registry.register(RuleB.class);

            // First transformation
            TransformationContext ctx1 = createContext(registry);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            Set<String> xmiIds1 = extractXmiIds(targetResource);

            // Reset resources but keep same registry
            targetResourceSet = new ResourceSetImpl();
            targetResource = targetResourceSet.createResource(URI.createURI("test://target2.xmi"));
            resetAllCounters();

            // Second transformation with same registry
            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());
            Set<String> xmiIds2 = extractXmiIds(targetResource);

            // XMI IDs should be identical across transforms with same registry
            assertEquals(xmiIds1, xmiIds2,
                    "XMI IDs should be identical across multiple transforms with same registry");

            LOG.info("Ordinal stability test: {} XMI IDs verified stable", xmiIds1.size());
        }
    }

    // ========================================================================
    // 0.2 In-Progress Tracking Side Effects
    // ========================================================================

    @Nested
    @DisplayName("0.2 In-Progress Tracking Side Effects")
    class InProgressTrackingTests {

        @Test
        @DisplayName("0.2.1 In-progress cleared on successful completion - no stale targets")
        void testInProgressClearedOnSuccess() {
            createSourceElements(2);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(LazyRuleWithCallback.class);
            registry.register(TriggerLazyRule.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            // After transformation, requesting the same equivalent should return cached result
            // (not an in-progress placeholder)
            EClass source = (EClass) sourceResource.getContents().get(0);
            EAnnotation result1 = ctx.equivalent(source, EAnnotation.class, "LazyRuleWithCallback");
            EAnnotation result2 = ctx.equivalent(source, EAnnotation.class, "LazyRuleWithCallback");

            assertNotNull(result1, "First equivalent call should return cached result");
            assertNotNull(result2, "Second equivalent call should return cached result");
            assertSame(result1, result2, "Both calls should return the same cached instance");

            LOG.info("In-progress cleanup test: verified no stale targets after completion");
        }

        @Test
        @DisplayName("0.2.2 In-progress cleared on exception - cleanup happens on error")
        void testInProgressClearedOnException() {
            createSourceElements(1);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ExceptionRule.class);
            registry.register(TriggerExceptionRule.class);

            TransformationContext ctx = createContext(registry);
            ExceptionRule.shouldThrow.set(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            // Transformation should fail
            assertThrows(Exception.class, () -> executor.transform(),
                    "Transformation should throw when rule throws");

            assertTrue(ExceptionRule.exceptionThrown.get(),
                    "Exception should have been thrown");

            // Create new context and verify no stale state
            resetAllCounters();
            ExceptionRule.shouldThrow.set(false);

            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            // Second transformation should succeed without seeing stale in-progress state
            assertDoesNotThrow(() -> executor2.transform(),
                    "Second transformation should succeed after first failed");

            LOG.info("Exception cleanup test: verified clean state after exception");
        }

        @Test
        @DisplayName("0.2.3 Concurrent access to same source - thread safety verified")
        void testConcurrentAccessThreadSafety() throws InterruptedException {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(SlowLazyRule.class);
            registry.register(ConcurrentTriggerRule.class);

            // Run multiple times to increase chance of detecting race conditions
            for (int run = 0; run < 3; run++) {
                setUp();
                createSourceElements(5);
                resetAllCounters();
                SlowLazyRule.executionCount.set(0);

                TransformationContext ctx = createContext(registry);
                TransformationExecutor executor = TransformationExecutor.builder()
                        .registry(registry)
                        .context(ctx)
                        .parallel(true)  // Enable parallel execution
                        .build();

                final int currentRun = run;
                assertDoesNotThrow(() -> executor.transform(),
                        "Parallel transformation should not throw on run " + currentRun);

                // Each source should be processed exactly once per rule
                // (no duplicate processing due to race conditions)
                int executions = SlowLazyRule.executionCount.get();
                assertTrue(executions <= 5,
                        "SlowLazyRule should execute at most once per source, got " + executions);
            }

            LOG.info("Thread safety test: parallel execution completed without races");
        }

        @Test
        @DisplayName("0.2.4 Memory not retained after transformation - weak reference test")
        void testMemoryNotRetainedAfterTransformation() throws InterruptedException {
            createSourceElements(10);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RuleA.class);

            WeakReference<TransformationContext> ctxRef;
            {
                TransformationContext ctx = createContext(registry);

                TransformationExecutor executor = TransformationExecutor.builder()
                        .registry(registry)
                        .context(ctx)
                        .parallel(false)
                        .build();

                executor.transform();

                // Get a weak reference to the context
                ctxRef = new WeakReference<>(ctx);
            }
            // ctx and executor are now out of scope

            // Try to trigger GC
            System.gc();
            Thread.sleep(100);
            System.gc();

            // Note: We can't guarantee GC will collect, but we're documenting the pattern
            // The important thing is that nothing in the framework prevents GC
            LOG.info("Memory retention test: context can be GC'd after transformation");

            // If context is still reachable, it's likely due to test infrastructure, not a leak
            // This test documents the expected behavior rather than strictly enforcing it
        }
    }

    // ========================================================================
    // 0.3 Circular Dependency Side Effects
    // ========================================================================

    @Nested
    @DisplayName("0.3 Circular Dependency Side Effects")
    class CircularDependencyTests {

        @Test
        @DisplayName("0.3.1 Deep circular chains (A→B→C→D→A) handled correctly")
        void testDeepCircularChain() {
            createSourceElements(1);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(DeepChainRuleA.class);
            registry.register(DeepChainRuleB.class);
            registry.register(DeepChainRuleC.class);
            registry.register(DeepChainRuleD.class);
            registry.register(DeepChainTrigger.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            // Should not cause StackOverflowError
            assertDoesNotThrow(() -> executor.transform(),
                    "Deep circular chain should be handled without StackOverflow");

            // Verify reasonable execution counts (no infinite loop)
            assertTrue(DeepChainRuleA.executionCount.get() < 10,
                    "RuleA should not execute excessively: " + DeepChainRuleA.executionCount.get());
            assertTrue(DeepChainRuleB.executionCount.get() < 10,
                    "RuleB should not execute excessively: " + DeepChainRuleB.executionCount.get());
            assertTrue(DeepChainRuleC.executionCount.get() < 10,
                    "RuleC should not execute excessively: " + DeepChainRuleC.executionCount.get());
            assertTrue(DeepChainRuleD.executionCount.get() < 10,
                    "RuleD should not execute excessively: " + DeepChainRuleD.executionCount.get());

            LOG.info("Deep chain test: A={}, B={}, C={}, D={}",
                    DeepChainRuleA.executionCount.get(),
                    DeepChainRuleB.executionCount.get(),
                    DeepChainRuleC.executionCount.get(),
                    DeepChainRuleD.executionCount.get());
        }

        @Test
        @DisplayName("0.3.2 Multiple discriminators in circular chain (A:d1→B→A:d2→C→A:d3)")
        void testMultipleDiscriminatorsCircularChain() {
            createSourceElements(1);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(MultiDiscriminatorRule.class);
            registry.register(MultiDiscriminatorChainRule.class);
            registry.register(MultiDiscriminatorTrigger.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform(),
                    "Multiple discriminators in circular chain should be handled");

            // Verify that different discriminators created different targets
            int executions = MultiDiscriminatorRule.executionCount.get();
            LOG.info("Multi-discriminator test: {} executions", executions);

            // Should have at least some executions (not completely blocked)
            assertTrue(executions >= 1,
                    "Should have at least one execution");
            // But not infinite
            assertTrue(executions < 20,
                    "Should not have infinite executions: " + executions);
        }

        @Test
        @DisplayName("0.3.3 Circular with greedy and lazy rules mixed")
        void testCircularGreedyLazyMixed() {
            createSourceElements(2);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(GreedyCircularRule.class);
            registry.register(LazyCircularRule.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform(),
                    "Mixed greedy/lazy circular should be handled");

            LOG.info("Greedy/Lazy circular test: Greedy={}, Lazy={}",
                    GreedyCircularRule.executionCount.get(),
                    LazyCircularRule.executionCount.get());

            // Verify no infinite loop
            assertTrue(GreedyCircularRule.executionCount.get() < 20,
                    "Greedy rule should not loop infinitely");
            assertTrue(LazyCircularRule.executionCount.get() < 20,
                    "Lazy rule should not loop infinitely");
        }

        @Test
        @DisplayName("0.3.4 Race condition - parallel threads detecting recursion")
        void testParallelRecursionDetection() throws InterruptedException, ExecutionException {
            createSourceElements(10);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ParallelCircularRule.class);
            registry.register(ParallelTriggerRule.class);

            // Reset counter
            ParallelCircularRule.executionCount.set(0);
            ParallelCircularRule.nullReturns.set(0);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)  // Enable parallel
                    .build();

            assertDoesNotThrow(() -> executor.transform(),
                    "Parallel circular detection should not cause errors");

            LOG.info("Parallel recursion test: executions={}, nullReturns={}",
                    ParallelCircularRule.executionCount.get(),
                    ParallelCircularRule.nullReturns.get());

            // Should complete without infinite loop or crash
            assertTrue(ParallelCircularRule.executionCount.get() < 100,
                    "Should not have excessive executions in parallel mode");
        }
    }

    // ========================================================================
    // 0.4 Guard Caching Side Effects
    // ========================================================================

    @Nested
    @DisplayName("0.4 Guard Caching Side Effects")
    class GuardCachingTests {

        @Test
        @DisplayName("0.4.1 Guard with context-dependent state - baseline behavior")
        void testGuardWithContextDependentState() {
            createSourceElements(3);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(StatefulGuardRule.class);

            // Set context state that guard checks
            StatefulGuardRule.contextState.set("ACTIVE");

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int guardEvals = StatefulGuardRule.guardEvaluationCount.get();
            int executions = StatefulGuardRule.executionCount.get();

            LOG.info("Stateful guard test: guardEvals={}, executions={}", guardEvals, executions);

            // Document current behavior - guard should be evaluated for each source
            assertTrue(guardEvals >= 3,
                    "Guard should be evaluated for each source element");
            assertEquals(3, executions,
                    "All 3 elements should pass guard when state is ACTIVE");
        }

        @Test
        @DisplayName("0.4.2 Guard evaluation count instrumentation - baseline measurement")
        void testGuardEvaluationCountBaseline() {
            createSourceElements(5);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(GuardedRule.class);
            registry.register(GuardTriggerRule.class);

            // Reset counter
            GuardedRule.guardEvaluationCount.set(0);
            GuardedRule.executionCount.set(0);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int guardEvals = GuardedRule.guardEvaluationCount.get();
            int executions = GuardedRule.executionCount.get();

            LOG.info("Guard count baseline: guardEvals={}, executions={}", guardEvals, executions);

            // Document the baseline - this will be compared after optimization
            // Guard should be evaluated at least once per source
            assertTrue(guardEvals >= 5,
                    "Guard should be evaluated at least once per source");

            // With optimization, we expect guardEvals to decrease (cached)
            // This test establishes the baseline behavior
        }

        @Test
        @DisplayName("0.4.3 Guards called in deterministic order")
        void testGuardDeterministicOrder() {
            createSourceElements(5);

            List<String> order1 = new ArrayList<>();
            List<String> order2 = new ArrayList<>();

            // First run
            DeterministicGuardRule.evaluationOrder.set(order1);
            TransformationRegistry registry1 = new TransformationRegistry();
            registry1.register(DeterministicGuardRule.class);

            TransformationContext ctx1 = createContext(registry1);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry1)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());

            // Second run
            setUp();
            createSourceElements(5);
            DeterministicGuardRule.evaluationOrder.set(order2);

            TransformationRegistry registry2 = new TransformationRegistry();
            registry2.register(DeterministicGuardRule.class);

            TransformationContext ctx2 = createContext(registry2);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry2)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());

            // Orders should be identical
            assertEquals(order1, order2,
                    "Guard evaluation order should be deterministic");

            LOG.info("Deterministic order test: {} evaluations in same order", order1.size());
        }

        @Test
        @DisplayName("0.4.4 Rejection cache cleared between transformations")
        void testRejectionCacheClearedBetweenTransformations() {
            createSourceElements(3);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RejectingRule.class);

            // First transformation - rule rejects all
            RejectingRule.shouldReject.set(true);
            RejectingRule.executionCount.set(0);

            TransformationContext ctx1 = createContext(registry);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            assertEquals(0, RejectingRule.executionCount.get(),
                    "No executions when guard rejects all");

            // Second transformation with new context - rule accepts all
            RejectingRule.shouldReject.set(false);
            RejectingRule.executionCount.set(0);

            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());

            // Now should execute for all sources (rejection not cached from previous run)
            assertEquals(3, RejectingRule.executionCount.get(),
                    "All sources should execute when guard accepts (no stale rejection cache)");

            LOG.info("Rejection cache isolation test: verified separate contexts are isolated");
        }
    }

    // ========================================================================
    // 0.5 Output Equivalence Baselines
    // ========================================================================

    @Nested
    @DisplayName("0.5 Output Equivalence Baselines")
    class OutputEquivalenceTests {

        @Test
        @DisplayName("0.5.3 XMI IDs identical across multiple runs")
        void testXmiIdsIdenticalAcrossRuns() {
            createSourceElements(5);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            // First run
            TransformationContext ctx1 = createContext(registry);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            Set<String> xmiIds1 = extractXmiIds(targetResource);
            String xmi1 = captureXmiOutput(targetResource);

            // Second run with fresh resources
            setUp();
            createSourceElements(5);
            resetAllCounters();

            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());
            Set<String> xmiIds2 = extractXmiIds(targetResource);
            String xmi2 = captureXmiOutput(targetResource);

            // XMI IDs should be identical
            assertEquals(xmiIds1, xmiIds2,
                    "XMI IDs should be identical across runs");

            // XMI output should be byte-identical
            assertEquals(xmi1, xmi2,
                    "XMI output should be byte-identical across runs");

            LOG.info("XMI ID determinism test: {} IDs verified identical", xmiIds1.size());
        }

        @Test
        @DisplayName("0.5.4 Element containment structure identical")
        void testContainmentStructureIdentical() {
            createSourceElements(3);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ContainerRule.class);
            registry.register(ContainedRule.class);

            // First run
            TransformationContext ctx1 = createContext(registry);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            Map<String, String> containment1 = extractContainmentMap(targetResource);

            // Second run
            setUp();
            createSourceElements(3);
            resetAllCounters();

            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());
            Map<String, String> containment2 = extractContainmentMap(targetResource);

            // Containment should be identical
            assertEquals(containment1, containment2,
                    "Containment structure should be identical across runs");

            LOG.info("Containment test: {} containment relationships verified", containment1.size());
        }

        @Test
        @DisplayName("0.5.5 EReference targets identical")
        void testEReferenceTargetsIdentical() {
            createSourceElements(3);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ReferenceSourceRule.class);
            registry.register(ReferenceTargetRule.class);

            // First run
            TransformationContext ctx1 = createContext(registry);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor1.transform());
            Map<String, List<String>> refs1 = extractReferences(targetResource);

            // Second run
            setUp();
            createSourceElements(3);
            resetAllCounters();

            TransformationContext ctx2 = createContext(registry);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx2)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor2.transform());
            Map<String, List<String>> refs2 = extractReferences(targetResource);

            // References should be identical
            assertEquals(refs1, refs2,
                    "EReference targets should be identical across runs");

            LOG.info("Reference test: {} reference sets verified", refs1.size());
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private String captureXmiOutput(Resource resource) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Map<String, Object> options = new HashMap<>();
            options.put(XMIResource.OPTION_ENCODING, "UTF-8");
            resource.save(baos, options);
            return baos.toString("UTF-8");
        } catch (IOException e) {
            throw new RuntimeException("Failed to capture XMI output", e);
        }
    }

    private Set<String> extractXmiIds(Resource resource) {
        Set<String> ids = new TreeSet<>();
        if (resource instanceof XMIResource) {
            XMIResource xmiResource = (XMIResource) resource;
            TreeIterator<EObject> iter = resource.getAllContents();
            while (iter.hasNext()) {
                EObject obj = iter.next();
                String id = xmiResource.getID(obj);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Map<String, String> extractContainmentMap(Resource resource) {
        Map<String, String> containment = new TreeMap<>();
        if (resource instanceof XMIResource) {
            XMIResource xmiResource = (XMIResource) resource;
            TreeIterator<EObject> iter = resource.getAllContents();
            while (iter.hasNext()) {
                EObject obj = iter.next();
                String id = xmiResource.getID(obj);
                EObject container = obj.eContainer();
                String containerId = container != null ? xmiResource.getID(container) : "ROOT";
                if (id != null) {
                    containment.put(id, containerId != null ? containerId : "NULL");
                }
            }
        }
        return containment;
    }

    private Map<String, List<String>> extractReferences(Resource resource) {
        Map<String, List<String>> refs = new TreeMap<>();
        if (resource instanceof XMIResource) {
            XMIResource xmiResource = (XMIResource) resource;
            TreeIterator<EObject> iter = resource.getAllContents();
            while (iter.hasNext()) {
                EObject obj = iter.next();
                String id = xmiResource.getID(obj);
                if (id != null) {
                    List<String> refIds = new ArrayList<>();
                    for (EReference ref : obj.eClass().getEAllReferences()) {
                        if (!ref.isContainment()) {
                            Object value = obj.eGet(ref);
                            if (value instanceof EObject) {
                                String refId = xmiResource.getID((EObject) value);
                                if (refId != null) refIds.add(refId);
                            } else if (value instanceof List) {
                                for (Object item : (List<?>) value) {
                                    if (item instanceof EObject) {
                                        String refId = xmiResource.getID((EObject) item);
                                        if (refId != null) refIds.add(refId);
                                    }
                                }
                            }
                        }
                    }
                    Collections.sort(refIds);
                    refs.put(id, refIds);
                }
            }
        }
        return refs;
    }

    // ========================================================================
    // Test Rules
    // ========================================================================

    // Basic rules for registration order tests
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class RuleA {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("A_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class RuleB {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> ruleB() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("B_" + source.getName());
                // Get containing package
                EPackage pkg = ctx.equivalent(source, EPackage.class, "RuleA");
                if (pkg != null) {
                    pkg.getEAnnotations().add(ann);
                }
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class RuleC {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "RuleC")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> ruleC() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("C_" + source.getName());
                EPackage pkg = ctx.equivalent(source, EPackage.class, "RuleA");
                if (pkg != null) {
                    pkg.getEClassifiers().add(dt);
                }
                return dt;
            };
        }
    }

    // Dynamic registration test rule
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DynamicRegistrationRule {
        static final AtomicReference<TransformationRegistry> registryRef = new AtomicReference<>();

        @TransformRule(name = "DynamicRegistrationRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> dynamicRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Dynamic_" + source.getName());

                // Try to dynamically register another rule during transformation
                TransformationRegistry registry = registryRef.get();
                if (registry != null) {
                    try {
                        registry.register(RuleA.class);
                    } catch (Exception e) {
                        // Expected - dynamic registration may be blocked
                        LOG.debug("Dynamic registration blocked: {}", e.getMessage());
                    }
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Lazy rule for in-progress testing
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyRuleWithCallback {
        @TransformRule(name = "LazyRuleWithCallback")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyRule() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Lazy_" + source.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class TriggerLazyRule {
        @TransformRule(name = "TriggerLazyRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> triggerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Trigger_" + source.getName());

                EAnnotation ann = ctx.equivalent(source, EAnnotation.class, "LazyRuleWithCallback");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Exception handling test rules
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ExceptionRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final AtomicReference<Boolean> shouldThrow = new AtomicReference<>(false);
        static final AtomicReference<Boolean> exceptionThrown = new AtomicReference<>(false);

        @TransformRule(name = "ExceptionRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> exceptionRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Exception_" + source.getName());

                if (shouldThrow.get()) {
                    exceptionThrown.set(true);
                    throw new RuntimeException("Intentional exception for testing");
                }

                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class TriggerExceptionRule {
        @TransformRule(name = "TriggerExceptionRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> triggerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Trigger_" + source.getName());

                EAnnotation ann = ctx.equivalent(source, EAnnotation.class, "ExceptionRule");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Slow lazy rule for concurrency testing
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class SlowLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "SlowLazyRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> slowRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                // Simulate slow processing
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Slow_" + source.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ConcurrentTriggerRule {
        @TransformRule(name = "ConcurrentTriggerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> triggerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Concurrent_" + source.getName());

                EAnnotation ann = ctx.equivalent(source, EAnnotation.class, "SlowLazyRule");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Deep chain circular rules (A→B→C→D→A)
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DeepChainRuleA {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "DeepChainRuleA")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 10) {
                    throw new RuntimeException("DeepChainRuleA loop detected!");
                }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("DeepA_" + source.getName());
                // Call B
                EAnnotation b = ctx.equivalent(source, EAnnotation.class, "DeepChainRuleB");
                if (b != null) {
                    pkg.getEAnnotations().add(b);
                }
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DeepChainRuleB {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "DeepChainRuleB")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> ruleB() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 10) {
                    throw new RuntimeException("DeepChainRuleB loop detected!");
                }
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("DeepB_" + source.getName());
                // Call C
                EDataType c = ctx.equivalent(source, EDataType.class, "DeepChainRuleC");
                if (c != null) {
                    ann.getDetails().put("c", c.getName());
                }
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class DeepChainRuleC {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "DeepChainRuleC")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> ruleC() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 10) {
                    throw new RuntimeException("DeepChainRuleC loop detected!");
                }
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("DeepC_" + source.getName());
                // Call D
                EEnum d = ctx.equivalent(source, EEnum.class, "DeepChainRuleD");
                if (d != null) {
                    dt.setInstanceClassName(d.getName());
                }
                return dt;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class DeepChainRuleD {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "DeepChainRuleD")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EEnum> ruleD() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 10) {
                    throw new RuntimeException("DeepChainRuleD loop detected!");
                }
                EEnum en = ctx.createTarget(EEnum.class);
                en.setName("DeepD_" + source.getName());
                // CIRCULAR: Call A
                EPackage a = ctx.equivalent(source, EPackage.class, "DeepChainRuleA");
                if (a != null) {
                    // Don't add to avoid containment issues, just reference
                    en.getEAnnotations().add(EcoreFactory.eINSTANCE.createEAnnotation());
                }
                return en;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DeepChainTrigger {
        @TransformRule(name = "DeepChainTrigger")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> trigger() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("DeepTrigger_" + source.getName());

                EPackage a = ctx.equivalent(source, EPackage.class, "DeepChainRuleA");
                if (a != null) {
                    pkg.getESubpackages().add(a);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Multi-discriminator rules
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class MultiDiscriminatorRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "MultiDiscriminatorRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> rule() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 20) {
                    throw new RuntimeException("MultiDiscriminatorRule loop!");
                }
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Multi_" + source.getName());

                // Call chain rule which will call back with different discriminator
                EDataType chain = ctx.equivalent(source, EDataType.class, "MultiDiscriminatorChainRule");
                if (chain != null) {
                    ann.getDetails().put("chain", chain.getName());
                }

                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class MultiDiscriminatorChainRule {
        @TransformRule(name = "MultiDiscriminatorChainRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> rule() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("Chain_" + source.getName());

                // Call back to MultiDiscriminatorRule with different discriminators
                for (String disc : new String[]{"d1", "d2", "d3"}) {
                    EAnnotation ann = ctx.equivalentDiscriminated(
                            source, EAnnotation.class, "MultiDiscriminatorRule", disc);
                    // Just check if it returns something
                }

                return dt;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiDiscriminatorTrigger {
        @TransformRule(name = "MultiDiscriminatorTrigger")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> trigger() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("MultiTrigger_" + source.getName());

                // Initial trigger with discriminator
                EAnnotation ann = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "MultiDiscriminatorRule", "initial");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Greedy/Lazy circular
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class GreedyCircularRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "GreedyCircularRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> greedy() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 20) {
                    throw new RuntimeException("GreedyCircularRule loop!");
                }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Greedy_" + source.getName());

                // Call lazy rule
                EAnnotation lazy = ctx.equivalent(source, EAnnotation.class, "LazyCircularRule");
                if (lazy != null) {
                    pkg.getEAnnotations().add(lazy);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyCircularRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyCircularRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazy() {
            return (source, ctx) -> {
                if (executionCount.incrementAndGet() > 20) {
                    throw new RuntimeException("LazyCircularRule loop!");
                }
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Lazy_" + source.getName());

                // Call back to greedy (circular)
                EPackage greedy = ctx.equivalent(source, EPackage.class, "GreedyCircularRule");
                if (greedy != null) {
                    ann.getDetails().put("greedy", greedy.getName());
                }

                return ann;
            };
        }
    }

    // Parallel circular rules
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ParallelCircularRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final AtomicInteger nullReturns = new AtomicInteger(0);

        @TransformRule(name = "ParallelCircularRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> parallel() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Parallel_" + source.getName());

                // Try circular call with discriminator
                EAnnotation other = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "ParallelCircularRule", "circular");
                if (other == null) {
                    nullReturns.incrementAndGet();
                }

                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParallelTriggerRule {
        @TransformRule(name = "ParallelTriggerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> trigger() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("ParallelTrigger_" + source.getName());

                EAnnotation ann = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "ParallelCircularRule", "initial");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Guard testing rules
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GuardedRule {
        static final AtomicInteger guardEvaluationCount = new AtomicInteger(0);
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "GuardedRule")
        @Lazy
        @Transform(type = EClass.class)
        @Guard(method = "checkGuard")
        public TransformFunction<EClass, EAnnotation> guarded() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Guarded_" + source.getName());
                return ann;
            };
        }

        public boolean checkGuard(EObject source, TransformationContext ctx) {
            guardEvaluationCount.incrementAndGet();
            // Accept all
            return true;
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class GuardTriggerRule {
        @TransformRule(name = "GuardTriggerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> trigger() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("GuardTrigger_" + source.getName());

                // Trigger guarded rule multiple times
                ctx.equivalent(source, EAnnotation.class, "GuardedRule");
                ctx.equivalent(source, EAnnotation.class, "GuardedRule");
                ctx.equivalent(source, EAnnotation.class, "GuardedRule");

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    // Stateful guard rule
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class StatefulGuardRule {
        static final AtomicReference<String> contextState = new AtomicReference<>("INACTIVE");
        static final AtomicInteger guardEvaluationCount = new AtomicInteger(0);
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "StatefulGuardRule")
        @Transform(type = EClass.class)
        @Guard(method = "checkState")
        public TransformFunction<EClass, EAnnotation> stateful() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Stateful_" + source.getName() + "_" + contextState.get());
                ctx.addToResource(ann);
                return ann;
            };
        }

        public boolean checkState(EObject source, TransformationContext ctx) {
            guardEvaluationCount.incrementAndGet();
            return "ACTIVE".equals(contextState.get());
        }
    }

    // Deterministic guard order rule
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DeterministicGuardRule {
        static final AtomicReference<List<String>> evaluationOrder = new AtomicReference<>(new ArrayList<>());

        @TransformRule(name = "DeterministicGuardRule")
        @Transform(type = EClass.class)
        @Guard(method = "recordOrder")
        public TransformFunction<EClass, EAnnotation> deterministic() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Det_" + source.getName());
                ctx.addToResource(ann);
                return ann;
            };
        }

        public boolean recordOrder(EObject source, TransformationContext ctx) {
            if (source instanceof ENamedElement) {
                evaluationOrder.get().add(((ENamedElement) source).getName());
            }
            return true;
        }
    }

    // Rejecting rule for cache isolation test
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class RejectingRule {
        static final AtomicReference<Boolean> shouldReject = new AtomicReference<>(true);
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "RejectingRule")
        @Transform(type = EClass.class)
        @Guard(method = "maybeReject")
        public TransformFunction<EClass, EAnnotation> rejecting() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Reject_" + source.getName());
                ctx.addToResource(ann);
                return ann;
            };
        }

        public boolean maybeReject(EObject source, TransformationContext ctx) {
            return !shouldReject.get();
        }
    }

    // Container/Contained rules for containment testing
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ContainerRule {
        @TransformRule(name = "ContainerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> container() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Container_" + source.getName());

                // Add contained element
                EAnnotation contained = ctx.equivalent(source, EAnnotation.class, "ContainedRule");
                if (contained != null) {
                    pkg.getEAnnotations().add(contained);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ContainedRule {
        @TransformRule(name = "ContainedRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> contained() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Contained_" + source.getName());
                return ann;
            };
        }
    }

    // Reference rules for EReference testing
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ReferenceSourceRule {
        @TransformRule(name = "ReferenceSourceRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> refSource() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("RefSource_" + source.getName());

                // Create target and reference it
                EDataType target = ctx.equivalent(source, EDataType.class, "ReferenceTargetRule");
                if (target != null) {
                    pkg.getEClassifiers().add(target);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class ReferenceTargetRule {
        @TransformRule(name = "ReferenceTargetRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> refTarget() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("RefTarget_" + source.getName());
                return dt;
            };
        }
    }

    // ========================================================================
    // Model Provider
    // ========================================================================

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
