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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to reproduce the dual locking race condition between equivalent() and executeParentRule().
 *
 * <h2>Problem:</h2>
 * <p>equivalent() and executeParentRule() use DIFFERENT locking mechanisms:</p>
 * <ul>
 *   <li>equivalent() uses ruleLocks (per-key ReentrantLock)</li>
 *   <li>executeParentRule() uses lockStripes in ElementResolutionCache (1024 striped locks)</li>
 * </ul>
 *
 * <p>When the same (source, ruleName) pair is accessed through both methods concurrently,
 * they acquire different locks and can execute the rule twice simultaneously.</p>
 *
 * <h2>Test Strategy:</h2>
 * <ul>
 *   <li>Thread A calls equivalent(source, TargetType) which triggers rule "SharedRule"</li>
 *   <li>Thread B calls executeParentRule("SharedRule", source) for the same source</li>
 *   <li>Both threads start simultaneously using CountDownLatch</li>
 *   <li>Track rule execution count - should be 1, but will be >1 if race condition exists</li>
 * </ul>
 *
 * <h2>Expected Behavior:</h2>
 * <ul>
 *   <li>BEFORE FIX: executionCount > 1 (duplicate execution detected)</li>
 *   <li>AFTER FIX: executionCount == 1 (single execution, second thread gets cached result)</li>
 * </ul>
 */
@DisplayName("Dual Locking Race Condition Tests")
class DualLockingRaceConditionTest {

    private static final Logger log = LoggerFactory.getLogger(DualLockingRaceConditionTest.class);

    /**
     * Number of iterations to increase chance of catching the race condition.
     */
    private static final int ITERATIONS = 20;

    /**
     * Tracks how many times the shared rule was executed.
     */
    static final AtomicInteger sharedRuleExecutionCount = new AtomicInteger(0);

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

        // Reset counter before each test
        sharedRuleExecutionCount.set(0);
    }

    @Nested
    @DisplayName("Dual Locking Race Condition")
    class DualLockingRaceTests {

        /**
         * Test that reproduces the race condition between equivalent() and executeParentRule().
         *
         * BEFORE FIX: This test should detect executionCount > 1 (proving the bug exists)
         * AFTER FIX: Change assertion to expect executionCount == 1
         */
        @Test
        @DisplayName("Mixed equivalent() and executeParentRule() calls should not cause duplicate execution")
        void testMixedEquivalentAndExecuteParentRuleCalls() throws Exception {
            int duplicateExecutionCount = 0;

            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                // Reset for each iteration
                sharedRuleExecutionCount.set(0);

                // Create fresh source element for each iteration
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("TestSource_" + iteration);
                sourceResource.getContents().clear();
                sourceResource.getContents().add(sourceClass);
                targetResource.getContents().clear();

                // Create transformation context
                TransformationContext ctx = new TransformationContext(
                        new TestModelProvider(),
                        sourceResourceSet,
                        targetResourceSet,
                        new ExtensionMethodRegistry()
                );

                // Register transformation
                TransformationRegistry registry = new TransformationRegistry();
                registry.register(SharedLazyRuleTransformation.class);
                ctx.setTransformationRegistry(registry);

                // Use CountDownLatch to synchronize thread start
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(2);

                ExecutorService executor = Executors.newFixedThreadPool(2);

                final EClass source = sourceClass;

                // Thread A: calls equivalent()
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // Small random delay to vary timing
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.equivalent(source, EPackage.class);
                        log.debug("Thread A (equivalent): got result {}", result != null ? result.getName() : "null");
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
                        // Small random delay to vary timing
                        Thread.sleep((long) (Math.random() * 5));
                        EPackage result = ctx.executeParentRule("SharedRule", source);
                        log.debug("Thread B (executeParentRule): got result {}", result != null ? result.getName() : "null");
                    } catch (Exception e) {
                        log.error("Thread B failed", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Start both threads simultaneously
                startLatch.countDown();

                // Wait for both to complete
                boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
                assertTrue(completed, "Threads should complete within timeout");

                executor.shutdown();

                int execCount = sharedRuleExecutionCount.get();
                log.info("Iteration {}: rule executed {} time(s)", iteration, execCount);

                if (execCount > 1) {
                    duplicateExecutionCount++;
                    log.warn("RACE CONDITION DETECTED in iteration {}: rule executed {} times", iteration, execCount);
                }
            }

            // AFTER FIX: No duplicate executions should occur
            // The unified locking mechanism ensures that concurrent calls via equivalent()
            // and executeParentRule() for the same (source, ruleName) are properly synchronized
            assertEquals(0, duplicateExecutionCount,
                    "No duplicate executions should occur after fix. " +
                    "Both equivalent() and executeParentRule() now use the same ruleLocks mechanism.");

            log.info("Test passed: No race conditions detected in {} iterations", ITERATIONS);
        }
    }

    /**
     * Transformation with a lazy rule that tracks execution count.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SharedLazyRuleTransformation {

        /**
         * Lazy rule that increments execution counter.
         * Both equivalent() and executeParentRule() should trigger this rule,
         * but it should only execute ONCE per source element.
         */
        @TransformRule(name = "SharedRule")
        @Lazy
        @Primary
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> sharedRule() {
            return (source, ctx) -> {
                // Increment counter to track how many times this rule is executed
                int count = sharedRuleExecutionCount.incrementAndGet();
                log.debug("SharedRule executing for {} (execution #{})", source.getName(), count);

                // Add artificial delay to widen the race window
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Create target
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Target_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Simple model provider for tests.
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
