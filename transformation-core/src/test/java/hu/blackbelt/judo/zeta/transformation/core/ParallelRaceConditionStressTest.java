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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests designed to reproduce production race conditions in parallel execution.
 *
 * <h2>Production Issue:</h2>
 * <ul>
 *   <li>Sequential: 9,236 ms, 23,335 elements (valid)</li>
 *   <li>Parallel: 2,239 ms, 23,347 elements (INVALID - 12 extra)</li>
 * </ul>
 *
 * <h2>Test Results:</h2>
 * <p>All tests pass, confirming the current implementation handles these scenarios correctly:</p>
 * <ul>
 *   <li>High contention: 6 eager rules calling equivalent() on same sources - NO duplicates</li>
 *   <li>Cross-rule reference chains (A→B→C via equivalent()) - correct execution count</li>
 *   <li>@Extends inheritance under parallel execution - correct target count</li>
 *   <li>Sequential vs parallel comparison - identical element counts</li>
 * </ul>
 *
 * <h2>Conclusion:</h2>
 * <p>The production 0.05% error rate suggests race conditions triggered by specific
 * patterns not covered here. The thread-isolated architecture proposed in
 * fix-parallel-execution-race-conditions will eliminate these edge cases by design.</p>
 *
 * <p>These tests serve as regression tests to ensure parallel execution remains safe.</p>
 */
@DisplayName("Parallel Race Condition Stress Tests")
class ParallelRaceConditionStressTest {

    private static final Logger log = LoggerFactory.getLogger(ParallelRaceConditionStressTest.class);

    /**
     * Large element count to stress the parallel system.
     */
    private static final int ELEMENT_COUNT = 5000;

    /**
     * Many iterations to catch intermittent race conditions.
     * Production had 0.05% error rate, so we need many runs to catch it.
     */
    private static final int STRESS_ITERATIONS = 20;

    /**
     * Very small chunk size to maximize thread interleaving.
     */
    private static final int SMALL_CHUNK_SIZE = 10;

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

        // Reset all static counters
        HighContentionLazyRule.executionCount.set(0);
        HighContentionLazyRule.sourcesSeen.clear();
        ContentionEagerRule1.executionCount.set(0);
        ContentionEagerRule2.executionCount.set(0);
        ContentionEagerRule3.executionCount.set(0);
        CrossRuleChainA.executionCount.set(0);
        CrossRuleChainB.executionCount.set(0);
        CrossRuleChainC.executionCount.set(0);
        ExtendsParentRule.executionCount.set(0);
        ExtendsChildRule.executionCount.set(0);
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new StressTestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        return ctx;
    }

    private void createSourceElements(int count) {
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Element" + i);
            sourceResource.getContents().add(ec);
        }
    }

    // ==================== High Contention Tests ====================

    @Nested
    @DisplayName("High Contention on Same Source")
    class HighContentionTests {

        /**
         * Multiple eager rules all call equivalent() on the SAME source elements.
         * This creates maximum contention on the cache for each source.
         *
         * Expected: Lazy rule executes exactly once per source.
         * Bug: Race condition causes duplicate lazy rule execution.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Multiple rules calling equivalent() on same source - no duplicates")
        void testMultipleRulesCallingEquivalentOnSameSource() {
            createSourceElements(ELEMENT_COUNT);

            HighContentionLazyRule.executionCount.set(0);
            HighContentionLazyRule.sourcesSeen.clear();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(HighContentionLazyRule.class);
            registry.register(ContentionEagerRule1.class);
            registry.register(ContentionEagerRule2.class);
            registry.register(ContentionEagerRule3.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .chunkSize(SMALL_CHUNK_SIZE)  // Small chunks = more interleaving
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int lazyExecutions = HighContentionLazyRule.executionCount.get();

            log.info("High contention test - Lazy executions: {} (expected: {}), " +
                            "Eager1: {}, Eager2: {}, Eager3: {}",
                    lazyExecutions, ELEMENT_COUNT,
                    ContentionEagerRule1.executionCount.get(),
                    ContentionEagerRule2.executionCount.get(),
                    ContentionEagerRule3.executionCount.get());

            // Find duplicates
            List<String> duplicates = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : HighContentionLazyRule.sourcesSeen.entrySet()) {
                if (entry.getValue() > 1) {
                    duplicates.add(entry.getKey() + " (x" + entry.getValue() + ")");
                }
            }

            if (!duplicates.isEmpty()) {
                log.error("DUPLICATES FOUND: {} sources executed multiple times: {}",
                        duplicates.size(), duplicates.subList(0, Math.min(10, duplicates.size())));
            }

            assertEquals(ELEMENT_COUNT, lazyExecutions,
                    "Lazy rule should execute exactly once per source. " +
                    "Executed " + lazyExecutions + " times for " + ELEMENT_COUNT + " sources. " +
                    "Duplicates: " + duplicates.size());
        }

        /**
         * Same test but with even more eager rules to increase contention.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Six eager rules calling equivalent() - extreme contention")
        void testExtremeContention() {
            createSourceElements(ELEMENT_COUNT);

            HighContentionLazyRule.executionCount.set(0);
            HighContentionLazyRule.sourcesSeen.clear();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(HighContentionLazyRule.class);
            // Register 6 eager rules all calling equivalent on same sources
            registry.register(ContentionEagerRule1.class);
            registry.register(ContentionEagerRule2.class);
            registry.register(ContentionEagerRule3.class);
            registry.register(ContentionEagerRule4.class);
            registry.register(ContentionEagerRule5.class);
            registry.register(ContentionEagerRule6.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)  // Very low threshold
                    .chunkSize(5)  // Very small chunks
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int lazyExecutions = HighContentionLazyRule.executionCount.get();

            assertEquals(ELEMENT_COUNT, lazyExecutions,
                    "Lazy rule should execute exactly once per source under extreme contention. " +
                    "Got " + lazyExecutions + " for " + ELEMENT_COUNT + " sources.");
        }
    }

    // ==================== Cross-Rule Reference Chain Tests ====================

    @Nested
    @DisplayName("Cross-Rule Reference Chains")
    class CrossRuleChainTests {

        /**
         * Tests complex cross-rule reference patterns:
         * - Rule A transforms source and calls equivalent for Rule B
         * - Rule B transforms source and calls equivalent for Rule C
         * - Rule C transforms source
         *
         * All three rules process the same sources, creating cross-rule dependencies.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Cross-rule equivalent() chains should not cause duplicates")
        void testCrossRuleEquivalentChains() {
            createSourceElements(ELEMENT_COUNT);

            CrossRuleChainA.executionCount.set(0);
            CrossRuleChainB.executionCount.set(0);
            CrossRuleChainC.executionCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(CrossRuleChainA.class);
            registry.register(CrossRuleChainB.class);
            registry.register(CrossRuleChainC.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int countA = CrossRuleChainA.executionCount.get();
            int countB = CrossRuleChainB.executionCount.get();
            int countC = CrossRuleChainC.executionCount.get();

            log.info("Cross-rule chain - A: {}, B: {}, C: {} (expected: {} each)",
                    countA, countB, countC, ELEMENT_COUNT);

            assertAll(
                    () -> assertEquals(ELEMENT_COUNT, countA,
                            "Rule A should execute once per source"),
                    () -> assertEquals(ELEMENT_COUNT, countB,
                            "Rule B should execute once per source"),
                    () -> assertEquals(ELEMENT_COUNT, countC,
                            "Rule C should execute once per source")
            );
        }
    }

    // ==================== @Extends with Parallel Execution ====================

    @Nested
    @DisplayName("@Extends Inheritance under Parallel Execution")
    class ExtendsParallelTests {

        /**
         * Tests @Extends inheritance combined with parallel execution.
         * Child rule extends parent rule; both execute on same source.
         * This tests the pre-created target sharing across threads.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("@Extends should work correctly under parallel execution")
        void testExtendsUnderParallelExecution() {
            createSourceElements(ELEMENT_COUNT);

            ExtendsParentRule.executionCount.set(0);
            ExtendsChildRule.executionCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ExtendsParentRule.class);
            registry.register(ExtendsChildRule.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int parentCount = ExtendsParentRule.executionCount.get();
            int childCount = ExtendsChildRule.executionCount.get();
            int targetCount = targetResource.getContents().size();

            log.info("@Extends parallel - Parent: {}, Child: {}, Targets: {} (expected: {})",
                    parentCount, childCount, targetCount, ELEMENT_COUNT);

            // Each source should produce exactly ONE target (child creates, parent populates)
            assertEquals(ELEMENT_COUNT, targetCount,
                    "@Extends should produce exactly one target per source. " +
                    "Got " + targetCount + " targets for " + ELEMENT_COUNT + " sources.");

            // Child should execute once per source
            assertEquals(ELEMENT_COUNT, childCount,
                    "Child rule should execute once per source");

            // Parent should execute once per source (via @Extends)
            assertEquals(ELEMENT_COUNT, parentCount,
                    "Parent rule should execute once per source via @Extends");
        }
    }

    // ==================== Element Count Comparison ====================

    @Nested
    @DisplayName("Sequential vs Parallel Element Count")
    class ElementCountComparisonTests {

        /**
         * Runs the same transformation sequentially and in parallel, comparing results.
         * This is the direct reproduction of the production bug.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Element count must match between sequential and parallel")
        void testElementCountMatchesSequentialVsParallel() {
            createSourceElements(ELEMENT_COUNT);

            // Run sequential
            TransformationRegistry seqRegistry = new TransformationRegistry();
            seqRegistry.register(HighContentionLazyRule.class);
            seqRegistry.register(ContentionEagerRule1.class);
            seqRegistry.register(ContentionEagerRule2.class);
            seqRegistry.register(ContentionEagerRule3.class);

            TransformationContext seqCtx = createContext(seqRegistry);

            TransformationExecutor seqExecutor = TransformationExecutor.builder()
                    .registry(seqRegistry)
                    .context(seqCtx)
                    .parallel(false)
                    .build();

            HighContentionLazyRule.executionCount.set(0);
            seqExecutor.transform();
            int seqCount = targetResource.getContents().size();
            int seqLazyCount = HighContentionLazyRule.executionCount.get();

            // Reset for parallel
            targetResourceSet = new ResourceSetImpl();
            targetResource = targetResourceSet.createResource(URI.createURI("test://target2.xmi"));

            TransformationRegistry parRegistry = new TransformationRegistry();
            parRegistry.register(HighContentionLazyRule.class);
            parRegistry.register(ContentionEagerRule1.class);
            parRegistry.register(ContentionEagerRule2.class);
            parRegistry.register(ContentionEagerRule3.class);

            TransformationContext parCtx = createContext(parRegistry);

            TransformationExecutor parExecutor = TransformationExecutor.builder()
                    .registry(parRegistry)
                    .context(parCtx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            HighContentionLazyRule.executionCount.set(0);
            parExecutor.transform();
            int parCount = targetResource.getContents().size();
            int parLazyCount = HighContentionLazyRule.executionCount.get();

            log.info("Sequential vs Parallel - Seq: {} elements ({} lazy), Par: {} elements ({} lazy)",
                    seqCount, seqLazyCount, parCount, parLazyCount);

            int diff = Math.abs(parCount - seqCount);
            if (diff > 0) {
                log.error("ELEMENT COUNT MISMATCH: {} extra/missing elements ({}%)",
                        diff, (diff * 100.0 / seqCount));
            }

            assertEquals(seqCount, parCount,
                    "Parallel transformation produced " + diff + " extra/missing elements. " +
                    "Sequential: " + seqCount + ", Parallel: " + parCount);

            assertEquals(seqLazyCount, parLazyCount,
                    "Lazy rule execution count mismatch. " +
                    "Sequential: " + seqLazyCount + ", Parallel: " + parLazyCount);
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Lazy rule that tracks how many times it's executed per source.
     * Used to detect duplicate executions.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class HighContentionLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final ConcurrentHashMap<String, Integer> sourcesSeen = new ConcurrentHashMap<>();

        @TransformRule(name = "HighContentionLazy")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                // Track how many times each source is processed
                String sourceName = source.getName();
                sourcesSeen.compute(sourceName, (k, v) -> v == null ? 1 : v + 1);

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("Lazy_" + sourceName);
                return target;
            };
        }
    }

    // Six eager rules that all call equivalent() on the same source elements

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ContentionEagerRule1 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager1")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);  // Trigger lazy rule
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Eager1_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClassifier.class)
    public static class ContentionEagerRule2 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager2")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClassifier> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);  // Same lazy rule
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Eager2_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class ContentionEagerRule3 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager3")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EEnum> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);  // Same lazy rule
                EEnum target = ctx.createTarget(EEnum.class);
                target.setName("Eager3_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAttribute.class)
    public static class ContentionEagerRule4 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager4")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAttribute> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);
                EAttribute target = ctx.createTarget(EAttribute.class);
                target.setName("Eager4_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EReference.class)
    public static class ContentionEagerRule5 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager5")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EReference> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);
                EReference target = ctx.createTarget(EReference.class);
                target.setName("Eager5_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EOperation.class)
    public static class ContentionEagerRule6 {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ContentionEager6")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EOperation> eagerRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                ctx.equivalent(source, EDataType.class);
                EOperation target = ctx.createTarget(EOperation.class);
                target.setName("Eager6_" + source.getName());
                return target;
            };
        }
    }

    // Cross-rule chain: A calls B's equivalent, B calls C's equivalent

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CrossRuleChainA {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ChainRuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                // Call equivalent for rule B's target type
                ctx.equivalent(source, EClass.class);
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("ChainA_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CrossRuleChainB {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ChainRuleB")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> ruleB() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                // Call equivalent for rule C's target type
                ctx.equivalent(source, EDataType.class);
                EClass target = ctx.createTarget(EClass.class);
                target.setName("ChainB_" + source.getName());
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class CrossRuleChainC {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ChainRuleC")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> ruleC() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("ChainC_" + source.getName());
                return target;
            };
        }
    }

    // @Extends rules for parallel inheritance testing

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ExtendsParentRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ExtendsParent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Parent_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ExtendsChildRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ExtendsChild")
        @Transform(type = EClass.class)
        @Extends("ExtendsParent")
        public TransformFunction<EClass, EPackage> childRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EPackage pkg = ctx.executeParentRule("ExtendsParent", source);
                if (pkg != null) {
                    pkg.setNsPrefix("Child_" + source.getName());
                }
                return pkg;
            };
        }
    }

    // ==================== Model Provider ====================

    static class StressTestModelProvider implements ModelProvider {
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
