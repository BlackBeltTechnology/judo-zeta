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
 * Tests to reproduce race conditions in equivalentDiscriminated() and equivalent().
 *
 * <h2>Production Bug Analysis:</h2>
 * <ul>
 *   <li>Sequential: 23,335 elements (correct)</li>
 *   <li>Parallel: 23,347 elements (12 extra = 0.05% duplication)</li>
 * </ul>
 *
 * <h2>Root Cause:</h2>
 * <p>{@code equivalentDiscriminated()} in TransformationContext.java has NO locking,
 * unlike {@code equivalent()} which uses double-checked locking with per-key ReentrantLock.</p>
 *
 * <h2>Race Condition in equivalentDiscriminated():</h2>
 * <pre>
 * Thread A: cache.check() → MISS
 * Thread B: cache.check() → MISS (before A caches result)
 * Thread A: execute rule → create clone → cache result
 * Thread B: execute rule → create clone → cache result
 * Result: TWO clones created, ONE cached (other is orphan in stagedElements)
 * </pre>
 */
@DisplayName("equivalentDiscriminated() Race Condition Tests")
class EquivalentDiscriminatedRaceTest {

    private static final Logger log = LoggerFactory.getLogger(EquivalentDiscriminatedRaceTest.class);

    /**
     * Number of source elements to create.
     * Using moderate count to increase contention probability.
     */
    private static final int ELEMENT_COUNT = 500;

    /**
     * Number of discriminators per source element.
     * More discriminators = more equivalentDiscriminated() calls = higher contention.
     */
    private static final int DISCRIMINATORS_PER_ELEMENT = 6;

    /**
     * Number of test iterations to catch intermittent race conditions.
     * Reduced to 3 for faster CI runs; increase to 30+ for thorough testing.
     */
    private static final int STRESS_ITERATIONS = 3;

    /**
     * Very small chunk size to maximize thread interleaving.
     */
    private static final int SMALL_CHUNK_SIZE = 5;

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

        // Reset counters
        DiscriminatedLazyRule.executionCount.set(0);
        DiscriminatedLazyRule.sourceDiscriminatorPairs.clear();
        MultiCallerEagerRule.callCount.set(0);
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

    private void createSourceElements(int count) {
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Entity" + i);
            sourceResource.getContents().add(ec);
        }
    }

    // ==================== equivalentDiscriminated() Race Condition Tests ====================

    @Nested
    @DisplayName("equivalentDiscriminated() Race Conditions")
    class EquivalentDiscriminatedRaceTests {

        /**
         * Reproduces the production bug where multiple threads call equivalentDiscriminated()
         * for the same (source, ruleName, discriminator) tuple.
         *
         * <p>The eager rule calls equivalentDiscriminated() multiple times with different
         * discriminators. equivalentDiscriminated() creates CLONES from the lazy rule result.
         * The race condition occurs when multiple threads try to create clones for the same
         * (source, discriminator) pair - both may create clones before the cache is updated.</p>
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("equivalentDiscriminated() should not create duplicate clones under parallel contention")
        void testEquivalentDiscriminatedNoDuplicates() {
            createSourceElements(ELEMENT_COUNT);

            DiscriminatedLazyRule.executionCount.set(0);
            DiscriminatedLazyRule.sourceDiscriminatorPairs.clear();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(DiscriminatedLazyRule.class);
            registry.register(MultiCallerEagerRule.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            // Lazy rule executes once per source (not per discriminator)
            int expectedLazyExecutions = ELEMENT_COUNT;
            int actualLazyExecutions = DiscriminatedLazyRule.executionCount.get();

            // Count how many times we tracked each (source, discriminator) pair
            // The eager rule tracks this before calling equivalentDiscriminated()
            // If there are duplicates, it means the cache returned different objects
            int duplicateCalls = 0;
            for (Map.Entry<String, AtomicInteger> entry : DiscriminatedLazyRule.sourceDiscriminatorPairs.entrySet()) {
                int count = entry.getValue().get();
                if (count > 1) {
                    duplicateCalls += (count - 1);
                }
            }

            // Count EAnnotation elements in target (the discriminated clones)
            int annotationCount = 0;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EPackage) {
                    annotationCount += ((EPackage) obj).getEAnnotations().size();
                }
            }

            // Expected: Each source × each discriminator = one annotation per eager rule output
            int expectedAnnotations = ELEMENT_COUNT * DISCRIMINATORS_PER_ELEMENT;

            log.info("equivalentDiscriminated test - LazyExec: {} (expected {}), Annotations: {} (expected {}), DuplicateCalls: {}",
                    actualLazyExecutions, expectedLazyExecutions, annotationCount, expectedAnnotations, duplicateCalls);

            // Verify lazy rule executed once per source
            assertEquals(expectedLazyExecutions, actualLazyExecutions,
                    "Lazy rule should execute once per source element");

            // CRITICAL: Check for race condition by comparing annotation counts
            // If there's a race, we may have MORE or FEWER annotations than expected
            assertEquals(expectedAnnotations, annotationCount,
                    "Expected " + expectedAnnotations + " discriminated clones but got " + annotationCount + ". " +
                    "Difference of " + (annotationCount - expectedAnnotations) + " indicates race condition!");
        }

        /**
         * Test that simulates the exact pattern from production:
         * Multiple threads accessing the SAME source element via cross-entity references.
         *
         * <p>Production scenario: Entity A has a reference to shared Entity X.
         * Entity B also has a reference to shared Entity X. When A and B are
         * processed in parallel, both call equivalentDiscriminated(X, ...)
         * simultaneously, causing a race condition.</p>
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Cross-entity references cause race on shared source")
        void testCrossEntityReferenceRace() {
            // Create a "shared" entity that many other entities reference
            EClass sharedEntity = EcoreFactory.eINSTANCE.createEClass();
            sharedEntity.setName("SharedEntity");
            sourceResource.getContents().add(sharedEntity);

            // Create many entities that all reference the shared entity
            for (int i = 0; i < ELEMENT_COUNT; i++) {
                EClass referrer = EcoreFactory.eINSTANCE.createEClass();
                referrer.setName("Referrer" + i);
                // Add annotation to link to shared entity (simulating cross-reference)
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("referencesShared");
                ann.getReferences().add(sharedEntity);
                referrer.getEAnnotations().add(ann);
                sourceResource.getContents().add(referrer);
            }

            CrossRefDiscriminatedRule.cloneCount.set(0);
            CrossRefDiscriminatedRule.cloneSourcePairs.clear();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(CrossRefLazyRule.class);
            registry.register(CrossRefDiscriminatedRule.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            // Count actual EAnnotation elements that came from the shared entity
            // These are clones created by equivalentDiscriminated()
            // Expected: 6 (one per discriminator) - cached and shared by all referrers
            // Race condition would create MORE than 6
            int cloneCount = 0;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EAnnotation) {
                    EAnnotation ann = (EAnnotation) obj;
                    if (ann.getSource() != null && ann.getSource().startsWith("CrossRef_SharedEntity")) {
                        cloneCount++;
                    }
                }
            }

            // Also count total invocations (should be 500 * 6 = 3000)
            int totalInvocations = CrossRefDiscriminatedRule.cloneCount.get();

            log.info("Cross-entity test - Clones in resource: {}, Total invocations: {}, Expected clones: {}",
                    cloneCount, totalInvocations, DISCRIMINATORS_PER_ELEMENT);

            // CRITICAL: If there are MORE than 6 clones, it means duplicate creation
            int extraClones = cloneCount - DISCRIMINATORS_PER_ELEMENT;
            if (extraClones > 0) {
                log.error("RACE CONDITION DETECTED: {} extra clones created!", extraClones);
            }

            assertEquals(DISCRIMINATORS_PER_ELEMENT, cloneCount,
                    "Race condition detected! Expected " + DISCRIMINATORS_PER_ELEMENT +
                    " discriminated clones for shared entity, but found " + cloneCount +
                    " (" + extraClones + " extra due to race condition).");
        }

        /**
         * Sequential vs Parallel comparison for equivalentDiscriminated().
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Sequential and parallel should produce same element count for discriminated")
        void testSequentialVsParallelElementCount() {
            createSourceElements(ELEMENT_COUNT);

            // Run sequential
            DiscriminatedLazyRule.executionCount.set(0);
            DiscriminatedLazyRule.sourceDiscriminatorPairs.clear();

            TransformationRegistry seqRegistry = new TransformationRegistry();
            seqRegistry.register(DiscriminatedLazyRule.class);
            seqRegistry.register(MultiCallerEagerRule.class);

            TransformationContext seqCtx = createContext(seqRegistry);

            TransformationExecutor seqExecutor = TransformationExecutor.builder()
                    .registry(seqRegistry)
                    .context(seqCtx)
                    .parallel(false)
                    .build();

            seqExecutor.transform();
            int seqCount = targetResource.getContents().size();
            int seqLazyExecutions = DiscriminatedLazyRule.executionCount.get();

            // Reset for parallel
            targetResourceSet = new ResourceSetImpl();
            targetResource = targetResourceSet.createResource(URI.createURI("test://target2.xmi"));
            DiscriminatedLazyRule.executionCount.set(0);
            DiscriminatedLazyRule.sourceDiscriminatorPairs.clear();

            TransformationRegistry parRegistry = new TransformationRegistry();
            parRegistry.register(DiscriminatedLazyRule.class);
            parRegistry.register(MultiCallerEagerRule.class);

            TransformationContext parCtx = createContext(parRegistry);

            TransformationExecutor parExecutor = TransformationExecutor.builder()
                    .registry(parRegistry)
                    .context(parCtx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            parExecutor.transform();
            int parCount = targetResource.getContents().size();
            int parLazyExecutions = DiscriminatedLazyRule.executionCount.get();

            int diff = parCount - seqCount;
            log.info("Seq vs Par - Seq: {} elements ({} lazy), Par: {} elements ({} lazy), Diff: {}",
                    seqCount, seqLazyExecutions, parCount, parLazyExecutions, diff);

            if (diff != 0) {
                log.error("ELEMENT COUNT MISMATCH: {} extra elements in parallel mode ({}%)",
                        diff, String.format("%.2f", (diff * 100.0 / seqCount)));
            }

            assertEquals(seqCount, parCount,
                    "Parallel mode created " + diff + " extra elements compared to sequential! " +
                    "(Sequential: " + seqCount + ", Parallel: " + parCount + ")");

            assertEquals(seqLazyExecutions, parLazyExecutions,
                    "Lazy rule execution count mismatch: Sequential=" + seqLazyExecutions +
                    ", Parallel=" + parLazyExecutions);
        }
    }

    // ==================== equivalent() with Class Type Tests ====================

    @Nested
    @DisplayName("equivalent() with Class Type Race Conditions")
    class EquivalentWithClassTypeTests {

        /**
         * Tests that equivalent(source, Class) properly uses locking.
         * Multiple eager rules call equivalent() on the same source elements.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("equivalent(source, Class) should use proper locking")
        void testEquivalentWithClassHasLocking() {
            createSourceElements(ELEMENT_COUNT);

            ClassTypeLazyRule.executionCount.set(0);
            ClassTypeLazyRule.sourcesSeen.clear();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeLazyRule.class);
            registry.register(ClassTypeCallerRule1.class);
            registry.register(ClassTypeCallerRule2.class);
            registry.register(ClassTypeCallerRule3.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            int lazyExecutions = ClassTypeLazyRule.executionCount.get();

            // Find duplicates
            int duplicates = 0;
            for (Map.Entry<String, AtomicInteger> entry : ClassTypeLazyRule.sourcesSeen.entrySet()) {
                int count = entry.getValue().get();
                if (count > 1) {
                    duplicates += (count - 1);
                }
            }

            log.info("equivalent(Class) test - Lazy executions: {}, Duplicates: {}",
                    lazyExecutions, duplicates);

            // equivalent() with Class SHOULD have proper locking, so no duplicates
            assertEquals(0, duplicates,
                    "equivalent(source, Class) created " + duplicates + " duplicates! " +
                    "This indicates locking is not working correctly.");

            assertEquals(ELEMENT_COUNT, lazyExecutions,
                    "Lazy rule should execute once per source element");
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Lazy rule that creates discriminated targets.
     * Tracks how many times each (source, discriminator) pair is executed.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DiscriminatedLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final ConcurrentHashMap<String, AtomicInteger> sourceDiscriminatorPairs = new ConcurrentHashMap<>();

        @TransformRule(name = "DiscriminatedLazy")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("Lazy_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Eager rule that calls equivalentDiscriminated() multiple times per source.
     * This simulates the production pattern where UI transformation rules
     * create multiple actions/columns per entity.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiCallerEagerRule {
        static final AtomicInteger callCount = new AtomicInteger(0);

        // Discriminators simulating action types like "create", "update", "delete", etc.
        static final String[] DISCRIMINATORS = {"create", "update", "delete", "view", "list", "export"};

        @TransformRule(name = "MultiCallerEager")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                callCount.incrementAndGet();

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Eager_" + source.getName());

                // Call equivalentDiscriminated() multiple times with different discriminators
                // This is the pattern that causes race conditions in production
                for (String discriminator : DISCRIMINATORS) {
                    String key = source.getName() + "/" + discriminator;

                    // Track before call
                    DiscriminatedLazyRule.sourceDiscriminatorPairs
                            .computeIfAbsent(key, k -> new AtomicInteger(0))
                            .incrementAndGet();

                    // This call may race with other threads
                    EAnnotation ann = ctx.equivalentDiscriminated(
                            source, EAnnotation.class, "DiscriminatedLazy", discriminator);

                    if (ann != null) {
                        pkg.getEAnnotations().add(ann);
                    }
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Second eager rule that also calls equivalentDiscriminated() on same sources.
     * This increases contention by having multiple rules compete for the same cache entries.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class AnotherCallerEagerRule {
        @TransformRule(name = "AnotherCallerEager")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EEnum> anotherEagerRule() {
            return (source, ctx) -> {
                EEnum target = ctx.createTarget(EEnum.class);
                target.setName("Another_" + source.getName());

                // Also call equivalentDiscriminated with same discriminators
                for (String discriminator : MultiCallerEagerRule.DISCRIMINATORS) {
                    // Note: Don't increment counter here - we're checking if caching works
                    EAnnotation ann = ctx.equivalentDiscriminated(
                            source, EAnnotation.class, "DiscriminatedLazy", discriminator);
                    // Just access, don't store
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    // ==================== equivalent() with Class Type Rules ====================

    /**
     * Lazy rule for testing equivalent(source, Class).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class ClassTypeLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final ConcurrentHashMap<String, AtomicInteger> sourcesSeen = new ConcurrentHashMap<>();

        @TransformRule(name = "ClassTypeLazy")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                String key = source.getName();
                sourcesSeen.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("Lazy_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Eager rules that call equivalent(source, Class).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ClassTypeCallerRule1 {
        @TransformRule(name = "ClassTypeCaller1")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> caller() {
            return (source, ctx) -> {
                ctx.equivalent(source, EDataType.class);  // Trigger lazy rule
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Caller1_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClassifier.class)
    public static class ClassTypeCallerRule2 {
        @TransformRule(name = "ClassTypeCaller2")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClassifier> caller() {
            return (source, ctx) -> {
                ctx.equivalent(source, EDataType.class);  // Same lazy rule
                EClass target = ctx.createTarget(EClass.class);
                target.setName("Caller2_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ClassTypeCallerRule3 {
        @TransformRule(name = "ClassTypeCaller3")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> caller() {
            return (source, ctx) -> {
                ctx.equivalent(source, EDataType.class);  // Same lazy rule
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Caller3_" + source.getName());
                ctx.addToResource(ann);
                return ann;
            };
        }
    }

    // ==================== Cross-Entity Reference Rules ====================

    /**
     * Lazy rule for cross-entity reference test.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class CrossRefLazyRule {
        @TransformRule(name = "CrossRefLazy")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyRule() {
            return (source, ctx) -> {
                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("CrossRef_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Eager rule that processes "referrer" entities and calls equivalentDiscriminated()
     * on the SHARED entity they reference. This causes all threads to race on the same
     * shared entity.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CrossRefDiscriminatedRule {
        static final AtomicInteger cloneCount = new AtomicInteger(0);
        static final ConcurrentHashMap<String, AtomicInteger> cloneSourcePairs = new ConcurrentHashMap<>();

        @TransformRule(name = "CrossRefDiscriminated")
        @Transform(type = EClass.class)
        @Guard(method = "isReferrer")
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Pkg_" + source.getName());

                // Find the shared entity this referrer points to
                EClass sharedEntity = null;
                for (EAnnotation ann : source.getEAnnotations()) {
                    if ("referencesShared".equals(ann.getSource()) && !ann.getReferences().isEmpty()) {
                        sharedEntity = (EClass) ann.getReferences().get(0);
                        break;
                    }
                }

                if (sharedEntity != null) {
                    // All referrers call equivalentDiscriminated on the SAME shared entity
                    // This creates maximum contention!
                    for (String discriminator : MultiCallerEagerRule.DISCRIMINATORS) {
                        String key = sharedEntity.getName() + "/" + discriminator;

                        // Track call count BEFORE the call
                        cloneSourcePairs.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
                        cloneCount.incrementAndGet();

                        // Call equivalentDiscriminated - the race condition is HERE
                        // Multiple threads may create duplicate clones before cache is updated
                        EAnnotation ann = ctx.equivalentDiscriminated(
                                sharedEntity, EAnnotation.class, "CrossRefLazy", discriminator);

                        // Don't add to pkg - EMF containment only allows one container
                        // Just verify we got a result
                        if (ann == null) {
                            log.warn("equivalentDiscriminated returned null for {}", key);
                        }
                    }
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }

        public boolean isReferrer(EObject source, TransformationContext ctx) {
            // Only process "Referrer" entities, not the "SharedEntity"
            return source instanceof EClass && ((EClass) source).getName().startsWith("Referrer");
        }
    }

    // ==================== Model Provider ====================

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
