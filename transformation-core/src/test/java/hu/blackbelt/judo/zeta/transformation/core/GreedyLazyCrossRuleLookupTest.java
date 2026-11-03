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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for fix-greedy-rule-target-lookup proposal.
 *
 * <p>This test verifies that ctx.equivalent(source, "RuleName") returns non-null
 * when called from one @Greedy rule to look up targets created by another @Greedy @Lazy rule
 * during the same transformation pass.</p>
 *
 * <h2>Problem Scenario (Before Fix)</h2>
 * <pre>
 * Rule A (@Greedy @Lazy): Creates ClassType targets
 * Rule B (@Greedy): Calls ctx.equivalent(source, "ClassType") to look up Rule A's targets
 *
 * Before the fix, Rule B would get null because:
 * 1. equivalent() found the lazy rule
 * 2. isEffectivelyActivityBased(rule) returned true
 * 3. It only recorded activation and returned null
 * 4. Rule B set target.setTarget(null) - BUG!
 * </pre>
 *
 * <h2>Expected Behavior (After Fix)</h2>
 * <p>When equivalent() is called explicitly with a rule name, lazy rules execute
 * immediately rather than just recording activation for Phase 2.</p>
 *
 * @see <a href="openspec/changes/fix-greedy-rule-target-lookup/proposal.md">Proposal</a>
 */
@DisplayName("Greedy-Lazy Cross-Rule Target Lookup Tests")
class GreedyLazyCrossRuleLookupTest {

    private static final Logger log = LoggerFactory.getLogger(GreedyLazyCrossRuleLookupTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    // Tracking counters for test verification
    static final AtomicInteger classTypeExecutions = new AtomicInteger(0);
    static final AtomicInteger relationTypeExecutions = new AtomicInteger(0);
    static final AtomicInteger nullTargetCount = new AtomicInteger(0);
    static final AtomicInteger successfulLookupCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        // Reset counters
        classTypeExecutions.set(0);
        relationTypeExecutions.set(0);
        nullTargetCount.set(0);
        successfulLookupCount.set(0);
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

    /**
     * Creates a source model simulating the ESM pattern:
     * - EClass represents a "Class" (source for ClassType rule)
     * - EReference represents a "RelationFeature" with a target EClass
     */
    private void createSourceModel(int classCount) {
        List<EClass> classes = new ArrayList<>();

        // Create classes
        for (int i = 0; i < classCount; i++) {
            EClass eClass = EcoreFactory.eINSTANCE.createEClass();
            eClass.setName("Class" + i);
            sourceResource.getContents().add(eClass);
            classes.add(eClass);
        }

        // Create references between classes (each class references the next one)
        for (int i = 0; i < classCount - 1; i++) {
            EReference ref = EcoreFactory.eINSTANCE.createEReference();
            ref.setName("refTo" + (i + 1));
            ref.setEType(classes.get(i + 1)); // Target is next class
            classes.get(i).getEStructuralFeatures().add(ref);
        }

        // Last class references first (circular)
        if (classCount > 1) {
            EReference ref = EcoreFactory.eINSTANCE.createEReference();
            ref.setName("refTo0");
            ref.setEType(classes.get(0));
            classes.get(classCount - 1).getEStructuralFeatures().add(ref);
        }
    }

    // ==================== Test Cases ====================

    @Nested
    @DisplayName("Cross-Rule Target Lookup with Explicit Rule Name")
    class ExplicitRuleNameLookupTests {

        /**
         * Core test case from the proposal:
         * - ClassType rule (@Greedy @Lazy) creates targets
         * - RelationType rule (@Greedy) calls ctx.equivalent(source.getTarget(), "ClassType")
         * - RelationType should get non-null result
         */
        @Test
        @DisplayName("equivalent(source, ruleName) returns non-null for @Greedy @Lazy rule targets - Sequential")
        void testCrossRuleLookupSequential() {
            createSourceModel(10);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeRule.class);
            registry.register(RelationTypeRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false) // Sequential mode
                    .build();

            TransformationResult result = executor.transform();

            log.info("Sequential - ClassType executions: {}, RelationType executions: {}",
                    classTypeExecutions.get(), relationTypeExecutions.get());
            log.info("Sequential - Successful lookups: {}, Null targets: {}",
                    successfulLookupCount.get(), nullTargetCount.get());

            // Verify no null targets from equivalent() calls
            assertEquals(0, nullTargetCount.get(),
                    "equivalent(source, 'ClassType') should never return null for valid sources");

            // Verify all relations got their targets
            assertTrue(successfulLookupCount.get() > 0,
                    "Should have at least one successful cross-rule lookup");

            // Count EDataTypes (ClassType targets) in result
            long classTypeCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EDataType)
                    .count();

            // Count EAttributes (RelationType targets) with non-null eType
            long relationTypeWithTargetCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EAttribute)
                    .map(e -> (EAttribute) e)
                    .filter(attr -> attr.getEType() != null)
                    .count();

            log.info("ClassTypes created: {}, RelationTypes with targets: {}",
                    classTypeCount, relationTypeWithTargetCount);

            assertEquals(10, classTypeCount, "Should have 10 ClassType targets");
            assertTrue(relationTypeWithTargetCount >= 9,
                    "RelationTypes should have non-null targets from cross-rule lookup");
        }

        /**
         * Same test in parallel mode - verifies thread-safety of the fix.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) returns non-null for @Greedy @Lazy rule targets - Parallel")
        void testCrossRuleLookupParallel() {
            createSourceModel(50); // More elements for parallel

            // Reset counters
            classTypeExecutions.set(0);
            relationTypeExecutions.set(0);
            nullTargetCount.set(0);
            successfulLookupCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeRule.class);
            registry.register(RelationTypeRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(5)
                    .build();

            long startTime = System.currentTimeMillis();
            TransformationResult result = executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            log.info("Parallel completed in {}ms", duration);
            log.info("Parallel - ClassType executions: {}, RelationType executions: {}",
                    classTypeExecutions.get(), relationTypeExecutions.get());
            log.info("Parallel - Successful lookups: {}, Null targets: {}",
                    successfulLookupCount.get(), nullTargetCount.get());

            // Verify no null targets from equivalent() calls
            assertEquals(0, nullTargetCount.get(),
                    "equivalent(source, 'ClassType') should never return null in parallel mode");

            // Count results
            long classTypeCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EDataType)
                    .count();

            long relationTypeWithTargetCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EAttribute)
                    .map(e -> (EAttribute) e)
                    .filter(attr -> attr.getEType() != null)
                    .count();

            assertEquals(50, classTypeCount, "Should have 50 ClassType targets in parallel mode");
            assertTrue(relationTypeWithTargetCount >= 49,
                    "RelationTypes should have non-null targets in parallel mode");

            // Verify no timeout (would indicate deadlock)
            assertTrue(duration < 10000, "Should complete quickly without deadlock");
        }

        /**
         * Stress test with many elements to verify consistency.
         */
        @RepeatedTest(3)
        @DisplayName("Stress test: Sequential and parallel produce identical non-null results")
        void testSequentialParallelConsistency() {
            createSourceModel(100);

            // Reset counters
            classTypeExecutions.set(0);
            relationTypeExecutions.set(0);
            nullTargetCount.set(0);
            successfulLookupCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeRule.class);
            registry.register(RelationTypeRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .parallelThreshold(20)
                    .chunkSize(10)
                    .build();

            executor.transform();

            // The key assertion: no null targets
            assertEquals(0, nullTargetCount.get(),
                    "Stress test: equivalent() must never return null for valid cross-rule lookups");

            log.info("Stress test - Successful lookups: {}, Null targets: {}",
                    successfulLookupCount.get(), nullTargetCount.get());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        /**
         * Verify that equivalent() still returns null for non-existent rules.
         */
        @Test
        @DisplayName("equivalent(source, 'NonExistentRule') returns null")
        void testNonExistentRuleReturnsNull() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("TestClass");
            sourceResource.getContents().add(source);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Try to look up with non-existent rule name
            EObject result = context.equivalent(source, "NonExistentRule");
            assertNull(result, "equivalent() should return null for non-existent rule");
        }

        /**
         * Verify that cached results are returned on subsequent calls.
         */
        @Test
        @DisplayName("Subsequent equivalent() calls return cached result")
        void testEquivalentReturnsCachedResult() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("CacheTestClass");
            sourceResource.getContents().add(source);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ClassTypeRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // First call triggers execution
            EDataType result1 = context.equivalent(source, "ClassType");
            assertNotNull(result1, "First call should execute and return result");

            int executionsAfterFirst = classTypeExecutions.get();

            // Second call should return cached result
            EDataType result2 = context.equivalent(source, "ClassType");
            assertSame(result1, result2, "Second call should return same cached instance");

            // Verify no additional execution occurred
            assertEquals(executionsAfterFirst, classTypeExecutions.get(),
                    "Second call should not trigger another execution");
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Simulates the ClassType rule from judo-tatami-esm2ui:
     * - @Greedy: Processes all matching elements
     * - @Lazy: Executes in Phase 2 / on-demand
     * - @Primary: Is the primary target for EClass sources
     *
     * Creates an EDataType (simulating ClassType) for each EClass.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class ClassTypeRule {

        @TransformRule(name = "ClassType")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        @Primary
        public TransformFunction<EClass, EDataType> classType() {
            return (source, ctx) -> {
                classTypeExecutions.incrementAndGet();

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Type");

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Simulates the RelationType rule from judo-tatami-esm2ui:
     * - @Greedy: Processes all matching elements
     * - Calls ctx.equivalent(source.getTarget(), "ClassType") to look up the target type
     *
     * Creates an EAttribute (simulating RelationType) for each EReference,
     * and sets its eType to the ClassType of the reference target.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAttribute.class)
    public static class RelationTypeRule {

        @TransformRule(name = "RelationType")
        @Transform(type = EReference.class)
        @Greedy
        public TransformFunction<EReference, EAttribute> relationType() {
            return (source, ctx) -> {
                relationTypeExecutions.incrementAndGet();

                EAttribute target = ctx.createTarget(EAttribute.class);
                target.setName(source.getName() + "_Relation");

                // THIS IS THE KEY PATTERN BEING TESTED:
                // Look up the ClassType target for this reference's target class
                EClassifier targetClass = source.getEType();
                if (targetClass instanceof EClass) {
                    // Call equivalent with explicit rule name - THIS MUST NOT RETURN NULL
                    EDataType targetType = ctx.equivalent((EClass) targetClass, "ClassType");

                    if (targetType == null) {
                        nullTargetCount.incrementAndGet();
                        log.warn("BUG: ctx.equivalent(source, 'ClassType') returned null for {}",
                                targetClass.getName());
                    } else {
                        successfulLookupCount.incrementAndGet();
                        target.setEType(targetType);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
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
