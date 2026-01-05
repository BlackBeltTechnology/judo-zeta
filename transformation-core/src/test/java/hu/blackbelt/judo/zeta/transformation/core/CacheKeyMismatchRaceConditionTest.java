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
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that reproduce the cache key mismatch race condition between equivalent() and executeParentRule().
 *
 * <h2>Root Cause:</h2>
 * <p>When multiple rules produce the same target type, {@code equivalent(source, TargetType.class)}
 * iterates rules in <b>registration order</b> and uses the first matching rule's name for the lock key.
 * Meanwhile, {@code executeParentRule("RuleName", source)} uses the explicit rule name for the lock key.</p>
 *
 * <p>If these rule names differ (e.g., "BaseModel" registered first vs "Model" registered second),
 * the two methods acquire <b>different locks</b>, causing a race condition where both can execute
 * the same logical transformation simultaneously, creating duplicate elements.</p>
 *
 * <h2>Example Scenario:</h2>
 * <pre>
 * Rule "BaseModel" registered first, produces Model.class (NOT @Primary)
 * Rule "Model" registered second, produces Model.class (@Primary)
 *
 * Thread A: equivalent(source, Model.class)
 *   → Finds "BaseModel" first → lock key = (source, "BaseModel")
 *
 * Thread B: executeParentRule("Model", source)
 *   → Explicit name → lock key = (source, "Model")
 *
 * Result: DIFFERENT LOCKS → Both threads execute → DUPLICATE ELEMENTS!
 * </pre>
 *
 * <h2>Test Strategy:</h2>
 * <p>All Phase 0 tests are designed to <b>FAIL before the fix</b>, proving the bug exists.
 * After the fix is implemented, these tests should <b>PASS</b>.</p>
 */
@DisplayName("Cache Key Mismatch Race Condition Tests")
class CacheKeyMismatchRaceConditionTest {

    private static final Logger log = LoggerFactory.getLogger(CacheKeyMismatchRaceConditionTest.class);

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

        // Reset all execution counters
        BaseModelRule.executionCount.set(0);
        ModelRule.executionCount.set(0);
        ModelARule.executionCount.set(0);
        ModelBRule.executionCount.set(0);
        ModelCRule.executionCount.set(0);
        StressPrimaryRule.executionCount.set(0);
        DiscriminatedBaseRule.executionCount.set(0);
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

    // ========================================================================
    // Phase 0.2: Dual-Access Pattern Tests (Original Problem)
    // Expected: These tests should FAIL before the fix
    // ========================================================================

    @Nested
    @DisplayName("0.2 Dual-Access Pattern Tests")
    class DualAccessPatternTests {

        /**
         * Test 0.2.1: equivalent() and executeParentRule() accessing same source with same target type.
         *
         * <p><b>Setup:</b></p>
         * <ul>
         *   <li>Rule "Model" produces Model.class and is @Primary</li>
         *   <li>Thread A calls {@code equivalent(source, EPackage.class)}</li>
         *   <li>Thread B calls {@code executeParentRule("Model", source)}</li>
         * </ul>
         *
         * <p><b>Expected before fix:</b> FAILS with duplicate Model elements</p>
         * <p><b>Expected after fix:</b> PASSES - single element created</p>
         */
        @Test
        @DisplayName("0.2.1 equivalent() and executeParentRule() same source should not create duplicates")
        void equivalentAndExecuteParentRuleSameSource() throws Exception {
            final int ITERATIONS = 20;
            int duplicateCount = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                // Reset counters
                ModelRule.executionCount.set(0);

                // Create fresh source element
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("TestSource_" + iteration);
                sourceResource.getContents().clear();
                sourceResource.getContents().add(sourceClass);
                targetResource.getContents().clear();

                // Register transformation with single @Primary rule
                TransformationRegistry registry = new TransformationRegistry();
                registry.register(SinglePrimaryTransformation.class);

                TransformationContext ctx = createContext(registry);

                // Synchronize thread start
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);

                ExecutorService executor = Executors.newFixedThreadPool(2);
                final EClass source = sourceClass;

                // Thread A: calls equivalent()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.equivalent(source, EPackage.class);
                        log.debug("Thread A (equivalent): {}", result != null ? result.getName() : "null");
                    } catch (Exception e) {
                        log.error("Thread A failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread B: calls executeParentRule()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.executeParentRule("Model", source);
                        log.debug("Thread B (executeParentRule): {}", result != null ? result.getName() : "null");
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int execCount = ModelRule.executionCount.get();
                if (execCount > 1) {
                    duplicateCount++;
                    log.warn("RACE DETECTED iteration {}: Model rule executed {} times", iteration, execCount);
                }
            }

            // AFTER FIX: This assertion should pass (0 duplicates)
            // BEFORE FIX: This should fail (duplicates detected)
            assertEquals(0, duplicateCount,
                    "No duplicate executions should occur. Both equivalent() and executeParentRule() " +
                    "should use the same lock key for the same (source, targetType) pair.");
        }

        /**
         * Test 0.2.2: equivalent() finds non-@Primary rule first due to registration order.
         *
         * <p><b>Setup:</b></p>
         * <ul>
         *   <li>Rule "BaseModel" registered FIRST, produces Model.class (NOT @Primary)</li>
         *   <li>Rule "Model" registered SECOND, produces Model.class (@Primary)</li>
         *   <li>{@code equivalent(source, EPackage.class)} finds "BaseModel" first</li>
         *   <li>{@code executeParentRule("Model", source)} uses "Model"</li>
         * </ul>
         *
         * <p><b>Expected before fix:</b> FAILS - different lock keys cause duplicates</p>
         * <p><b>Expected after fix:</b> PASSES - @Primary rule name used for lock key</p>
         */
        @Test
        @DisplayName("0.2.2 equivalent() finding non-primary rule first should still use consistent lock key")
        void equivalentFindsNonPrimaryRuleFirst() throws Exception {
            final int ITERATIONS = 20;
            int duplicateCount = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                // Reset counters
                BaseModelRule.executionCount.set(0);
                ModelRule.executionCount.set(0);

                // Create fresh source element
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("TestSource_" + iteration);
                sourceResource.getContents().clear();
                sourceResource.getContents().add(sourceClass);
                targetResource.getContents().clear();

                // Register transformation with BaseModel first (non-primary), then Model (primary)
                // This is the key: registration order affects iteration order in equivalent()
                TransformationRegistry registry = new TransformationRegistry();
                registry.register(BaseModelFirstTransformation.class);

                TransformationContext ctx = createContext(registry);

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);

                ExecutorService executor = Executors.newFixedThreadPool(2);
                final EClass source = sourceClass;

                // Thread A: calls equivalent() - will find BaseModel first due to registration order
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.equivalent(source, EPackage.class);
                        log.debug("Thread A (equivalent): {}", result != null ? result.getName() : "null");
                    } catch (Exception e) {
                        log.error("Thread A failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread B: calls executeParentRule("Model") - explicit rule name
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.executeParentRule("Model", source);
                        log.debug("Thread B (executeParentRule): {}", result != null ? result.getName() : "null");
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                // Check total executions across both rules
                int totalExec = BaseModelRule.executionCount.get() + ModelRule.executionCount.get();
                if (totalExec > 1) {
                    duplicateCount++;
                    log.warn("RACE DETECTED iteration {}: BaseModel={}, Model={}",
                            iteration, BaseModelRule.executionCount.get(), ModelRule.executionCount.get());
                }
            }

            // AFTER FIX: This assertion should pass (0 duplicates)
            // BEFORE FIX: This should fail (different lock keys cause race)
            assertEquals(0, duplicateCount,
                    "No duplicate executions should occur. The @Primary rule's name ('Model') should be " +
                    "used as the canonical lock key even when equivalent() finds 'BaseModel' first.");
        }

        /**
         * Test 0.2.3: Multiple rules producing same target type with mixed access patterns.
         *
         * <p><b>Semantics:</b></p>
         * <ul>
         *   <li>Rules "ModelA" (@Primary), "ModelB", "ModelC" all produce Model.class</li>
         *   <li>{@code equivalent(source, Model.class)} → uses @Primary ("ModelA") lock key</li>
         *   <li>{@code executeParentRule("ModelB", source)} → uses "ModelB" lock key (explicit name)</li>
         *   <li>{@code executeParentRule("ModelC", source)} → uses "ModelC" lock key (explicit name)</li>
         * </ul>
         *
         * <p><b>Expected behavior:</b> Each explicitly named rule executes independently.
         * When the caller explicitly requests a specific rule by name, they want THAT rule,
         * not the @Primary. Only {@code equivalent()} uses @Primary for consistency.</p>
         */
        @Test
        @DisplayName("0.2.3 Explicitly named rules execute independently from equivalent()")
        void multipleRulesSameTargetType() throws Exception {
            final int ITERATIONS = 20;
            int failedIterations = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                // Reset counters
                ModelARule.executionCount.set(0);
                ModelBRule.executionCount.set(0);
                ModelCRule.executionCount.set(0);

                // Create source element
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("TestSource_" + iteration);
                sourceResource.getContents().clear();
                sourceResource.getContents().add(sourceClass);
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(MultipleRulesTransformation.class);

                TransformationContext ctx = createContext(registry);

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(3);

                ExecutorService executor = Executors.newFixedThreadPool(3);
                final EClass source = sourceClass;

                // Thread A: calls equivalent() - uses @Primary ("ModelA") lock key
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        ctx.equivalent(source, EPackage.class);
                    } catch (Exception e) {
                        log.error("Thread A failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread B: calls executeParentRule("ModelB") - uses "ModelB" lock key
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        ctx.executeParentRule("ModelB", source);
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread C: calls executeParentRule("ModelC") - uses "ModelC" lock key
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        ctx.executeParentRule("ModelC", source);
                    } catch (Exception e) {
                        log.error("Thread C failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                // Each explicitly named rule should execute exactly once
                // equivalent() uses @Primary ("ModelA"), so ModelA executes once
                // executeParentRule("ModelB") executes ModelB once
                // executeParentRule("ModelC") executes ModelC once
                int modelACount = ModelARule.executionCount.get();
                int modelBCount = ModelBRule.executionCount.get();
                int modelCCount = ModelCRule.executionCount.get();

                // Each rule should execute exactly once (no duplicates within same rule)
                if (modelACount != 1 || modelBCount != 1 || modelCCount != 1) {
                    failedIterations++;
                    log.warn("UNEXPECTED iteration {}: A={}, B={}, C={} (expected 1,1,1)",
                            iteration, modelACount, modelBCount, modelCCount);
                }
            }

            // Each explicitly named rule should execute exactly once
            assertEquals(0, failedIterations,
                    "Each rule should execute exactly once. equivalent() uses @Primary, " +
                    "while executeParentRule() uses the explicit rule name.");
        }
    }

    // ========================================================================
    // Phase 0.3: Stress Tests (Original Problem)
    // Expected: These tests should FAIL before the fix
    // ========================================================================

    @Nested
    @DisplayName("0.3 Stress Tests")
    class StressTests {

        /**
         * Test 0.3.1: Stress test with 100 elements, 8 threads, mixed access patterns.
         *
         * <p><b>Expected before fix:</b> FAILS with inconsistent element counts</p>
         * <p><b>Expected after fix:</b> PASSES with exactly 100 elements</p>
         */
        @Test
        @DisplayName("0.3.1 Mixed access patterns stress test should produce consistent counts")
        void stressTestMixedAccessPatterns() throws Exception {
            final int ELEMENT_COUNT = 100;
            final int THREAD_COUNT = 8;
            final int RUNS = 20;

            int failedRuns = 0;

            for (int run = 0; run < RUNS; run++) {
                StressPrimaryRule.executionCount.set(0);

                // Create source elements
                sourceResource.getContents().clear();
                for (int i = 0; i < ELEMENT_COUNT; i++) {
                    EClass ec = EcoreFactory.eINSTANCE.createEClass();
                    ec.setName("Element" + i);
                    sourceResource.getContents().add(ec);
                }
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(StressTransformation.class);

                TransformationContext ctx = createContext(registry);

                ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

                Random random = new Random(run); // Reproducible randomness

                for (int t = 0; t < THREAD_COUNT; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = threadId; i < ELEMENT_COUNT; i += THREAD_COUNT) {
                                EObject source = sourceResource.getContents().get(i);
                                // Random mix of equivalent() and executeParentRule()
                                if (random.nextBoolean()) {
                                    ctx.equivalent(source, EPackage.class);
                                } else {
                                    ctx.executeParentRule("StressPrimary", source);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Thread {} failed", threadId, e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int execCount = StressPrimaryRule.executionCount.get();
                if (execCount != ELEMENT_COUNT) {
                    failedRuns++;
                    log.warn("Run {}: Expected {} executions, got {} (diff: {})",
                            run, ELEMENT_COUNT, execCount, execCount - ELEMENT_COUNT);
                }
            }

            // AFTER FIX: Should pass with 0 failed runs
            // BEFORE FIX: Should fail with inconsistent counts (~25% failure rate)
            assertEquals(0, failedRuns,
                    "All runs should produce exactly " + ELEMENT_COUNT + " executions. " +
                    "Cache key mismatch causes duplicate executions under contention.");
        }

        /**
         * Test 0.3.2: High contention with 10 elements, 50 threads.
         *
         * <p><b>Expected before fix:</b> FAILS with race condition duplicates</p>
         * <p><b>Expected after fix:</b> PASSES with exactly 10 elements</p>
         */
        @Test
        @DisplayName("0.3.2 High contention should not cause duplicates")
        void stressTestHighContention() throws Exception {
            final int ELEMENT_COUNT = 10;
            final int THREAD_COUNT = 50;
            final int RUNS = 20;

            int failedRuns = 0;

            for (int run = 0; run < RUNS; run++) {
                StressPrimaryRule.executionCount.set(0);

                // Create source elements
                sourceResource.getContents().clear();
                for (int i = 0; i < ELEMENT_COUNT; i++) {
                    EClass ec = EcoreFactory.eINSTANCE.createEClass();
                    ec.setName("HighContention" + i);
                    sourceResource.getContents().add(ec);
                }
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(StressTransformation.class);

                TransformationContext ctx = createContext(registry);

                ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

                Random random = new Random(run);

                // All threads access the same elements
                for (int t = 0; t < THREAD_COUNT; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            // Each thread accesses random elements
                            for (int i = 0; i < ELEMENT_COUNT; i++) {
                                int idx = random.nextInt(ELEMENT_COUNT);
                                EObject source = sourceResource.getContents().get(idx);
                                if (random.nextBoolean()) {
                                    ctx.equivalent(source, EPackage.class);
                                } else {
                                    ctx.executeParentRule("StressPrimary", source);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Thread {} failed", threadId, e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int execCount = StressPrimaryRule.executionCount.get();
                // Each element should be executed exactly once
                if (execCount != ELEMENT_COUNT) {
                    failedRuns++;
                    log.warn("Run {}: Expected {} executions, got {} (diff: {})",
                            run, ELEMENT_COUNT, execCount, execCount - ELEMENT_COUNT);
                }
            }

            assertEquals(0, failedRuns,
                    "High contention should not cause duplicate executions. " +
                    "All access patterns should use the same lock key per (source, targetType).");
        }

        /**
         * Test 0.3.3: Reproduce the reported Cardinality +7 duplicates issue.
         *
         * <p><b>Expected before fix:</b> FAILS with extra elements</p>
         * <p><b>Expected after fix:</b> PASSES with consistent counts</p>
         */
        @Test
        @DisplayName("0.3.3 Cardinality-like duplicates should not occur")
        void stressTestCardinalityDuplicates() throws Exception {
            final int ELEMENT_COUNT = 50;
            final int THREAD_COUNT = 16;
            final int RUNS = 20;

            int failedRuns = 0;
            int totalExtraDuplicates = 0;

            for (int run = 0; run < RUNS; run++) {
                StressPrimaryRule.executionCount.set(0);

                sourceResource.getContents().clear();
                for (int i = 0; i < ELEMENT_COUNT; i++) {
                    EClass ec = EcoreFactory.eINSTANCE.createEClass();
                    ec.setName("Cardinality" + i);
                    sourceResource.getContents().add(ec);
                }
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(StressTransformation.class);

                TransformationContext ctx = createContext(registry);

                ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

                // Simulate the pattern that causes Cardinality duplicates:
                // Some threads call equivalent(), others call executeParentRule()
                for (int t = 0; t < THREAD_COUNT; t++) {
                    final int threadId = t;
                    final boolean useEquivalent = (t % 2 == 0);
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            int elementsPerThread = ELEMENT_COUNT / THREAD_COUNT;
                            int start = threadId * elementsPerThread;
                            int end = (threadId == THREAD_COUNT - 1) ? ELEMENT_COUNT : start + elementsPerThread;

                            for (int i = start; i < end; i++) {
                                EObject source = sourceResource.getContents().get(i);
                                if (useEquivalent) {
                                    ctx.equivalent(source, EPackage.class);
                                } else {
                                    ctx.executeParentRule("StressPrimary", source);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Thread {} failed", threadId, e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int execCount = StressPrimaryRule.executionCount.get();
                if (execCount != ELEMENT_COUNT) {
                    failedRuns++;
                    int extraDuplicates = execCount - ELEMENT_COUNT;
                    totalExtraDuplicates += extraDuplicates;
                    log.warn("Run {}: +{} extra executions (Cardinality-like duplicates)",
                            run, extraDuplicates);
                }
            }

            log.info("Total extra duplicates across {} runs: {}", RUNS, totalExtraDuplicates);

            assertEquals(0, failedRuns,
                    "No Cardinality-like duplicates should occur. " +
                    "Total extra duplicates: " + totalExtraDuplicates);
        }
    }

    // ========================================================================
    // Phase 0.4: Determinism Tests (Original Problem)
    // Expected: These tests should FAIL before the fix
    // ========================================================================

    @Nested
    @DisplayName("0.4 Determinism Tests")
    class DeterminismTests {

        /**
         * Test 0.4.1: Parallel results should match sequential results.
         *
         * <p><b>Expected before fix:</b> FAILS - parallel has more elements</p>
         * <p><b>Expected after fix:</b> PASSES - identical counts</p>
         */
        @Test
        @DisplayName("0.4.1 Parallel results should match sequential results")
        void parallelResultsMatchSequential() throws Exception {
            final int ELEMENT_COUNT = 50;

            // Create source elements
            sourceResource.getContents().clear();
            for (int i = 0; i < ELEMENT_COUNT; i++) {
                EClass ec = EcoreFactory.eINSTANCE.createEClass();
                ec.setName("DeterminismElement" + i);
                sourceResource.getContents().add(ec);
            }

            // Run 1: Sequential execution
            StressPrimaryRule.executionCount.set(0);
            targetResource.getContents().clear();

            TransformationRegistry registry1 = new TransformationRegistry();
            registry1.register(StressTransformation.class);
            TransformationContext ctx1 = createContext(registry1);

            for (int i = 0; i < ELEMENT_COUNT; i++) {
                EObject source = sourceResource.getContents().get(i);
                // Alternate between equivalent() and executeParentRule()
                if (i % 2 == 0) {
                    ctx1.equivalent(source, EPackage.class);
                } else {
                    ctx1.executeParentRule("StressPrimary", source);
                }
            }

            int sequentialCount = StressPrimaryRule.executionCount.get();
            log.info("Sequential execution count: {}", sequentialCount);

            // Run 2: Parallel execution
            StressPrimaryRule.executionCount.set(0);
            targetResource.getContents().clear();

            TransformationRegistry registry2 = new TransformationRegistry();
            registry2.register(StressTransformation.class);
            TransformationContext ctx2 = createContext(registry2);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(8);

            for (int t = 0; t < 8; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = threadId; i < ELEMENT_COUNT; i += 8) {
                            EObject source = sourceResource.getContents().get(i);
                            if (i % 2 == 0) {
                                ctx2.equivalent(source, EPackage.class);
                            } else {
                                ctx2.executeParentRule("StressPrimary", source);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Thread {} failed", threadId, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
            executor.shutdown();

            int parallelCount = StressPrimaryRule.executionCount.get();
            log.info("Parallel execution count: {} (expected: {})", parallelCount, sequentialCount);

            // AFTER FIX: Should pass - counts match
            // BEFORE FIX: Should fail - parallel has more elements due to race
            assertEquals(sequentialCount, parallelCount,
                    "Parallel execution should produce the same count as sequential. " +
                    "Difference indicates race condition duplicates.");
        }

        /**
         * Test 0.4.2: Multiple parallel runs should produce consistent results.
         *
         * <p><b>Expected before fix:</b> FAILS - counts vary between runs</p>
         * <p><b>Expected after fix:</b> PASSES - all runs have same count</p>
         */
        @Test
        @DisplayName("0.4.2 Multiple parallel runs should be consistent")
        void multipleParallelRunsConsistent() throws Exception {
            final int ELEMENT_COUNT = 50;
            final int RUNS = 20;

            // Create source elements (reused across runs)
            sourceResource.getContents().clear();
            for (int i = 0; i < ELEMENT_COUNT; i++) {
                EClass ec = EcoreFactory.eINSTANCE.createEClass();
                ec.setName("ConsistencyElement" + i);
                sourceResource.getContents().add(ec);
            }

            List<Integer> executionCounts = new ArrayList<>();

            for (int run = 0; run < RUNS; run++) {
                StressPrimaryRule.executionCount.set(0);
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(StressTransformation.class);
                TransformationContext ctx = createContext(registry);

                ExecutorService executor = Executors.newFixedThreadPool(8);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(8);

                Random random = new Random(run);

                for (int t = 0; t < 8; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = threadId; i < ELEMENT_COUNT; i += 8) {
                                EObject source = sourceResource.getContents().get(i);
                                if (random.nextBoolean()) {
                                    ctx.equivalent(source, EPackage.class);
                                } else {
                                    ctx.executeParentRule("StressPrimary", source);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Thread {} failed", threadId, e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                executionCounts.add(StressPrimaryRule.executionCount.get());
            }

            // All runs should have the same count
            Set<Integer> uniqueCounts = new HashSet<>(executionCounts);
            log.info("Execution counts across {} runs: {} unique values: {}", RUNS, uniqueCounts.size(), uniqueCounts);

            // AFTER FIX: Should pass - all counts equal ELEMENT_COUNT
            // BEFORE FIX: Should fail - counts vary
            assertEquals(1, uniqueCounts.size(),
                    "All parallel runs should produce the same execution count. " +
                    "Found " + uniqueCounts.size() + " different values: " + uniqueCounts);
            assertEquals(ELEMENT_COUNT, executionCounts.get(0),
                    "Execution count should equal element count");
        }

        /**
         * Test 0.4.3: Specific element types should have consistent counts.
         *
         * <p><b>Expected before fix:</b> FAILS - specific types have duplicates</p>
         * <p><b>Expected after fix:</b> PASSES - counts match</p>
         */
        @Test
        @DisplayName("0.4.3 Specific element type counts should be consistent")
        void specificElementTypeCounts() throws Exception {
            final int ELEMENT_COUNT = 30;
            final int RUNS = 10;

            sourceResource.getContents().clear();
            for (int i = 0; i < ELEMENT_COUNT; i++) {
                EClass ec = EcoreFactory.eINSTANCE.createEClass();
                ec.setName("TypeCountElement" + i);
                sourceResource.getContents().add(ec);
            }

            int expectedCount = ELEMENT_COUNT;
            int failedRuns = 0;

            for (int run = 0; run < RUNS; run++) {
                StressPrimaryRule.executionCount.set(0);
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(StressTransformation.class);
                TransformationContext ctx = createContext(registry);

                ExecutorService executor = Executors.newFixedThreadPool(8);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(8);

                for (int t = 0; t < 8; t++) {
                    final int threadId = t;
                    final boolean useEquivalent = (threadId % 2 == 0);
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = threadId; i < ELEMENT_COUNT; i += 8) {
                                EObject source = sourceResource.getContents().get(i);
                                if (useEquivalent) {
                                    ctx.equivalent(source, EPackage.class);
                                } else {
                                    ctx.executeParentRule("StressPrimary", source);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Thread {} failed", threadId, e);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int actualCount = StressPrimaryRule.executionCount.get();
                if (actualCount != expectedCount) {
                    failedRuns++;
                    log.warn("Run {}: Expected {} elements, got {} (+{} duplicates)",
                            run, expectedCount, actualCount, actualCount - expectedCount);
                }
            }

            assertEquals(0, failedRuns,
                    "All runs should produce exactly " + expectedCount + " elements. " +
                    "Duplicates indicate race condition.");
        }
    }

    // ========================================================================
    // Phase 0.5: equivalentDiscriminated Tests (Original Problem)
    // Expected: These tests should FAIL before the fix
    // ========================================================================

    @Nested
    @DisplayName("0.5 equivalentDiscriminated Tests")
    class EquivalentDiscriminatedTests {

        /**
         * Test 0.5.1: equivalentDiscriminated() and executeParentRule() mixed access.
         *
         * <p><b>Expected before fix:</b> FAILS with duplicates</p>
         * <p><b>Expected after fix:</b> PASSES</p>
         */
        @Test
        @DisplayName("0.5.1 equivalentDiscriminated() and executeParentRule() should share lock")
        void equivalentDiscriminatedAndExecuteParentRule() throws Exception {
            final int ITERATIONS = 20;
            int duplicateCount = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                DiscriminatedBaseRule.executionCount.set(0);

                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("DiscTest_" + iteration);
                sourceResource.getContents().clear();
                sourceResource.getContents().add(sourceClass);
                targetResource.getContents().clear();

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(DiscriminatedTransformation.class);

                TransformationContext ctx = createContext(registry);

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);

                ExecutorService executor = Executors.newFixedThreadPool(2);
                final EClass source = sourceClass;

                // Thread A: calls equivalentDiscriminated()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        ctx.equivalentDiscriminated(source, EPackage.class, "DiscriminatedBase", "disc1");
                    } catch (Exception e) {
                        log.error("Thread A failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread B: calls executeParentRule()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.sleep((long) (Math.random() * 5));
                        ctx.executeParentRule("DiscriminatedBase", source);
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                int execCount = DiscriminatedBaseRule.executionCount.get();
                if (execCount > 1) {
                    duplicateCount++;
                    log.warn("RACE DETECTED iteration {}: DiscriminatedBase executed {} times",
                            iteration, execCount);
                }
            }

            assertEquals(0, duplicateCount,
                    "equivalentDiscriminated() and executeParentRule() should share the same lock key. " +
                    "No duplicates should occur.");
        }

        /**
         * Test 0.5.2: Multiple discriminators with mixed access patterns.
         *
         * <p><b>Expected before fix:</b> FAILS with race conditions</p>
         * <p><b>Expected after fix:</b> PASSES</p>
         */
        @Test
        @DisplayName("0.5.2 Multiple discriminators should not cause race conditions")
        void equivalentDiscriminatedMixedPatterns() throws Exception {
            final int ITERATIONS = 20;
            int failedIterations = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                DiscriminatedBaseRule.executionCount.set(0);

                // Create fresh resources for each iteration to avoid EMF containment race
                sourceResourceSet = new ResourceSetImpl();
                targetResourceSet = new ResourceSetImpl();
                sourceResource = sourceResourceSet.createResource(URI.createURI("test://source_" + iteration + ".xmi"));
                targetResource = targetResourceSet.createResource(URI.createURI("test://target_" + iteration + ".xmi"));

                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("MultiDisc_" + iteration);
                sourceResource.getContents().add(sourceClass);

                TransformationRegistry registry = new TransformationRegistry();
                registry.register(DiscriminatedTransformation.class);

                TransformationContext ctx = createContext(registry);

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(4);

                ExecutorService executor = Executors.newFixedThreadPool(4);
                final EClass source = sourceClass;

                // Thread A: equivalentDiscriminated with disc1
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ctx.equivalentDiscriminated(source, EPackage.class, "DiscriminatedBase", "disc1");
                    } catch (Exception e) {
                        log.error("Thread A failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread B: equivalentDiscriminated with disc2
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ctx.equivalentDiscriminated(source, EPackage.class, "DiscriminatedBase", "disc2");
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread C: equivalent() (no discriminator)
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ctx.equivalent(source, EPackage.class);
                    } catch (Exception e) {
                        log.error("Thread C failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Thread D: executeParentRule()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        ctx.executeParentRule("DiscriminatedBase", source);
                    } catch (Exception e) {
                        log.error("Thread D failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads should complete");
                executor.shutdown();

                // The base rule should only execute ONCE (for the original, non-discriminated target)
                // Discriminated calls clone from the original
                int execCount = DiscriminatedBaseRule.executionCount.get();
                if (execCount > 1) {
                    failedIterations++;
                    log.warn("RACE DETECTED iteration {}: base rule executed {} times", iteration, execCount);
                }
            }

            assertEquals(0, failedIterations,
                    "Base rule should execute only once per source. " +
                    "Discriminated variants should clone from the cached original.");
        }
    }

    // ========================================================================
    // Transformation Classes
    // ========================================================================

    /**
     * Single @Primary rule transformation.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SinglePrimaryTransformation {
        @TransformRule(name = "Model")
        @Lazy
        @Primary
        public TransformFunction<EClass, EPackage> modelRule() {
            return (source, ctx) -> {
                ModelRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Model_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * BaseModel registered first (non-primary), Model registered second (primary).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class BaseModelFirstTransformation {
        // Note: Registration order in LinkedHashSet depends on method discovery order.
        // We control this by naming methods alphabetically.

        @TransformRule(name = "BaseModel")
        @Lazy
        public TransformFunction<EClass, EPackage> aBaseModelRule() {
            return (source, ctx) -> {
                BaseModelRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("BaseModel_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "Model")
        @Lazy
        @Primary
        public TransformFunction<EClass, EPackage> bModelRule() {
            return (source, ctx) -> {
                ModelRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Model_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Multiple rules producing the same target type.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultipleRulesTransformation {
        @TransformRule(name = "ModelA")
        @Lazy
        @Primary
        public TransformFunction<EClass, EPackage> modelARule() {
            return (source, ctx) -> {
                ModelARule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("ModelA_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ModelB")
        @Lazy
        public TransformFunction<EClass, EPackage> modelBRule() {
            return (source, ctx) -> {
                ModelBRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("ModelB_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ModelC")
        @Lazy
        public TransformFunction<EClass, EPackage> modelCRule() {
            return (source, ctx) -> {
                ModelCRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("ModelC_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Stress test transformation with @Primary rule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class StressTransformation {
        @TransformRule(name = "StressPrimary")
        @Lazy
        @Primary
        public TransformFunction<EClass, EPackage> stressPrimaryRule() {
            return (source, ctx) -> {
                StressPrimaryRule.executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Stress_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Discriminated transformation.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DiscriminatedTransformation {
        @TransformRule(name = "DiscriminatedBase")
        @Lazy
        @Primary
        public TransformFunction<EClass, EPackage> discriminatedBaseRule() {
            return (source, ctx) -> {
                DiscriminatedBaseRule.executionCount.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Discriminated_" + source.getName());
                return pkg;
            };
        }
    }

    // Execution count trackers
    static class BaseModelRule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class ModelRule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class ModelARule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class ModelBRule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class ModelCRule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class StressPrimaryRule { static final AtomicInteger executionCount = new AtomicInteger(0); }
    static class DiscriminatedBaseRule { static final AtomicInteger executionCount = new AtomicInteger(0); }

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
