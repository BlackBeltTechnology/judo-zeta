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
 * Regression tests for the rule registry optimization (commit 0cd0bc0).
 *
 * <p>These tests reproduce the issues found in version 131508:</p>
 * <ul>
 *   <li>Missing operations: Eager rules not executing for some elements</li>
 *   <li>eType: null: equivalent() returning null when it should find a target</li>
 *   <li>Zero metrics: Rule iteration counts showing 0</li>
 * </ul>
 *
 * <p>Root cause: The optimization changed rule lookup from inline filtering to
 * pre-filtered indexes. The issue is that computeRulesForSource() may return
 * empty lists for certain EMF types, causing the filtered indexes to be empty.</p>
 */
@DisplayName("Rule Registry Regression Tests")
class RuleRegistryRegressionTest {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistryRegressionTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields
    static AtomicInteger eagerRuleExecutionCount = new AtomicInteger(0);
    static AtomicInteger lazyRuleExecutionCount = new AtomicInteger(0);
    static List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
    static List<EObject> equivalentResults = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        // Reset tracking
        eagerRuleExecutionCount.set(0);
        lazyRuleExecutionCount.set(0);
        executionLog.clear();
        equivalentResults.clear();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new RegressionTestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();

        // Reset metrics before each test
        TransformationMetrics.reset();
    }

    @AfterEach
    void tearDown() {
        TransformationMetrics.disable();
    }

    // ==================== Test Model Provider ====================

    static class RegressionTestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                org.eclipse.emf.common.util.TreeIterator<EObject> iterator = resource.getAllContents();
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

    // ==================== Phase 0.1: Missing Operations Test ====================

    @Nested
    @DisplayName("Phase 0.1: Missing Operations (Eager Rules Not Executing)")
    class MissingOperationsTests {

        /**
         * Reproduces: 6 missing operations for User entity.
         *
         * <p>The issue is that GREEDY rules registered for a supertype (EClassifier)
         * are not being found when transforming elements of a subtype (EClass).</p>
         *
         * <p>Expected behavior: All greedy rules should execute for all matching subtypes.</p>
         */
        @Test
        @DisplayName("Greedy rules should execute for all subtypes of registered source type")
        void greedyRulesExecuteForAllSubtypes() {
            // Create multiple elements of different subtypes
            // EClassifier is supertype of EClass, EDataType, EEnum
            EClass class1 = EcoreFactory.eINSTANCE.createEClass();
            class1.setName("Class1");
            sourceResource.getContents().add(class1);

            EClass class2 = EcoreFactory.eINSTANCE.createEClass();
            class2.setName("Class2");
            sourceResource.getContents().add(class2);

            EDataType dataType1 = EcoreFactory.eINSTANCE.createEDataType();
            dataType1.setName("DataType1");
            sourceResource.getContents().add(dataType1);

            // Register a greedy rule for EClassifier (supertype)
            registry.register(GreedySupertypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // ALL elements should be transformed (greedy matches all subtypes)
            assertEquals(3, eagerRuleExecutionCount.get(),
                    "Greedy rule should execute for all 3 elements (2 EClass + 1 EDataType). " +
                    "If this fails with 0, the pre-filtered eager rules index is returning empty.");

            // Verify execution log
            assertTrue(executionLog.contains("Class1"), "Class1 should be transformed");
            assertTrue(executionLog.contains("Class2"), "Class2 should be transformed");
            assertTrue(executionLog.contains("DataType1"), "DataType1 should be transformed");
        }

        /**
         * Reproduces: Multiple rules for same source type, some not executing.
         *
         * <p>Tests that multiple greedy rules can all execute for the same element.</p>
         */
        @Test
        @DisplayName("Multiple greedy rules should all execute for same source element")
        void multipleGreedyRulesAllExecute() {
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("TestClass");
            sourceResource.getContents().add(sourceClass);

            // Register transformation with multiple greedy rules for same source type
            registry.register(MultipleGreedyRulesTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // All rules should execute
            assertTrue(executionLog.contains("Rule1:TestClass"), "Rule1 should execute");
            assertTrue(executionLog.contains("Rule2:TestClass"), "Rule2 should execute");
            assertTrue(executionLog.contains("Rule3:TestClass"), "Rule3 should execute");
            assertEquals(3, eagerRuleExecutionCount.get(),
                    "All 3 greedy rules should execute for the same element");
        }
    }

    // ==================== Phase 0.2: eType: null Test ====================

    @Nested
    @DisplayName("Phase 0.2: eType: null (equivalent() Returns Null)")
    class ETypeNullTests {

        /**
         * Reproduces: 18 operations with eType: null.
         *
         * <p>The issue is that inside an eager rule, calling ctx.equivalent() to
         * look up a related element returns null because the lazy rules index is empty.</p>
         *
         * <p>Expected behavior: equivalent() should find and return the target element.</p>
         */
        @Test
        @DisplayName("equivalent() should return non-null when lazy rule exists")
        void equivalentReturnsNonNull() {
            // Create source elements: an EClass and an EAttribute
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("Person");
            sourceResource.getContents().add(sourceClass);

            EAttribute sourceAttr = EcoreFactory.eINSTANCE.createEAttribute();
            sourceAttr.setName("name");
            sourceAttr.setEType(EcorePackage.Literals.ESTRING);
            sourceClass.getEStructuralFeatures().add(sourceAttr);

            // Register transformation with:
            // 1. Greedy eager rule that transforms EClass and calls equivalent() for attributes
            // 2. Lazy rule that transforms EAttribute
            registry.register(EagerWithEquivalentTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // The eager rule should have executed
            assertEquals(1, eagerRuleExecutionCount.get(),
                    "Eager rule should execute for the EClass");

            // The lazy rule should have been called via equivalent()
            assertEquals(1, lazyRuleExecutionCount.get(),
                    "Lazy rule should execute when called via equivalent(). " +
                    "If this is 0, getLazyRulesForType() is returning empty list.");

            // The equivalent() call should have returned non-null
            assertFalse(equivalentResults.isEmpty(),
                    "equivalent() should return non-null results");
            assertNotNull(equivalentResults.get(0),
                    "equivalent() returned null when it should have found the target. " +
                    "This reproduces the eType: null issue.");
        }
    }

    // ==================== Phase 0.3: Zero Metrics Test ====================

    @Nested
    @DisplayName("Phase 0.3: Zero Metrics (Rule Iteration Counts)")
    class ZeroMetricsTests {

        /**
         * Reproduces: All metrics showing 0.
         *
         * <p>The zero metrics indicate that the rule loops are not being entered,
         * which means the pre-filtered rule indexes are returning empty lists.</p>
         */
        @Test
        @DisplayName("Metrics should be non-zero when rules execute")
        void metricsAreNonZero() {
            // Create source elements
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("TestClass");
            sourceResource.getContents().add(sourceClass);

            EAttribute sourceAttr = EcoreFactory.eINSTANCE.createEAttribute();
            sourceAttr.setName("testAttr");
            sourceAttr.setEType(EcorePackage.Literals.ESTRING);
            sourceClass.getEStructuralFeatures().add(sourceAttr);

            // Register transformation with lazy rule
            registry.register(EagerWithEquivalentTransformation.class);
            context.setTransformationRegistry(registry);

            // Enable metrics
            TransformationMetrics.enable();

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get metrics
            String report = TransformationMetrics.getReport();
            log.info("Metrics report:\n{}", report);

            // Verify lazy rule metrics are non-zero by checking the report
            // These metrics are recorded in equivalent() when lazy rules execute
            // Parse the report to check for non-zero values
            assertTrue(report.contains("Rule iterations:") && !report.contains("Rule iterations:              0"),
                    "Rule iterations should be > 0. " +
                    "If 0, the lazy rules loop in equivalent() is not being entered.\n" +
                    "Report:\n" + report);
            assertTrue(report.contains("Guard evaluations:") && !report.contains("Guard evaluations:            0"),
                    "Guard evaluations should be > 0. " +
                    "If 0, no rules passed the appliesTo() check.\n" +
                    "Report:\n" + report);
        }

        /**
         * Test that getEagerRulesForType returns non-empty list for greedy rules.
         */
        @Test
        @DisplayName("getEagerRulesForType should return rules for subtype elements")
        void getEagerRulesForTypeReturnsRules() {
            // Register a greedy rule for EClassifier
            registry.register(GreedySupertypeTransformation.class);

            // Query for EClass (subtype of EClassifier)
            List<TransformRuleDescriptor> rules = registry.getEagerRulesForType(EClass.class);

            assertFalse(rules.isEmpty(),
                    "getEagerRulesForType(EClass.class) should return rules registered for EClassifier. " +
                    "If empty, the pre-filtered index is not finding supertype rules.");

            log.info("Found {} eager rules for EClass", rules.size());
            for (TransformRuleDescriptor rule : rules) {
                log.info("  - {} (source: {}, greedy: {})",
                        rule.getName(), rule.getSourceType().getSimpleName(), rule.isGreedy());
            }
        }

        /**
         * Test that getLazyRulesForType returns non-empty list for lazy rules.
         */
        @Test
        @DisplayName("getLazyRulesForType should return rules for subtype elements")
        void getLazyRulesForTypeReturnsRules() {
            // Register transformation with lazy rule for EAttribute
            registry.register(EagerWithEquivalentTransformation.class);

            // Query for EAttribute
            List<TransformRuleDescriptor> rules = registry.getLazyRulesForType(EAttribute.class);

            assertFalse(rules.isEmpty(),
                    "getLazyRulesForType(EAttribute.class) should return lazy rules. " +
                    "If empty, the pre-filtered lazy index is not finding rules.");

            log.info("Found {} lazy rules for EAttribute", rules.size());
            for (TransformRuleDescriptor rule : rules) {
                log.info("  - {} (source: {}, lazy: {})",
                        rule.getName(), rule.getSourceType().getSimpleName(), rule.isLazy());
            }
        }
    }

    // ==================== Phase 0.4: Cross-Element Dependency Test ====================

    @Nested
    @DisplayName("Phase 0.4: Cross-Element Dependencies in Parallel Execution")
    class CrossElementDependencyTests {

        /**
         * Reproduces: eType: null due to cross-element dependencies.
         *
         * <p>The actual issue: An eager rule for Source A calls equivalent(Source B)
         * where Source B is transformed by a DIFFERENT eager rule. In parallel
         * execution, Source B may not have been transformed yet.</p>
         *
         * <p>This is different from the lazy rule test - here BOTH rules are eager/greedy.</p>
         */
        @Test
        @DisplayName("equivalent() for different source element should work in parallel")
        void equivalentFromDifferentSourceElementsWorks() {
            // Create two EClasses where one references the other as its supertype
            EClass parentClass = EcoreFactory.eINSTANCE.createEClass();
            parentClass.setName("Parent");
            sourceResource.getContents().add(parentClass);

            EClass childClass = EcoreFactory.eINSTANCE.createEClass();
            childClass.setName("Child");
            childClass.getESuperTypes().add(parentClass);  // Child extends Parent
            sourceResource.getContents().add(childClass);

            // Register transformation where Child rule calls equivalent(Parent)
            registry.register(CrossElementDependencyTransformation.class);
            context.setTransformationRegistry(registry);

            // Run in PARALLEL to expose the race condition
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)  // Parallel execution exposes the issue
                    .build();

            executor.transform();

            // Both classes should be transformed
            assertEquals(2, eagerRuleExecutionCount.get(),
                    "Both EClasses should be transformed");

            // The equivalent() call should have returned non-null
            // This is the actual regression: equivalent() returns null for the parent
            assertFalse(equivalentResults.isEmpty(),
                    "equivalent() should have been called");

            for (int i = 0; i < equivalentResults.size(); i++) {
                assertNotNull(equivalentResults.get(i),
                        "equivalent() returned null at index " + i + ". " +
                        "This indicates the cross-element dependency failed - " +
                        "the target for the other element wasn't created yet.");
            }
        }

        /**
         * Run the cross-element test multiple times to catch intermittent failures.
         * Race conditions may not manifest on every run.
         */
        @RepeatedTest(value = 10, name = "Cross-element dependency attempt {currentRepetition}/{totalRepetitions}")
        @DisplayName("Cross-element dependency should be consistent across multiple runs")
        void crossElementDependencyIsConsistent() {
            // Reset for each attempt
            eagerRuleExecutionCount.set(0);
            equivalentResults.clear();

            // Create fresh resources for each run
            sourceResource.getContents().clear();
            targetResource.getContents().clear();

            // Create two EClasses
            EClass typeA = EcoreFactory.eINSTANCE.createEClass();
            typeA.setName("TypeA");
            sourceResource.getContents().add(typeA);

            EClass typeB = EcoreFactory.eINSTANCE.createEClass();
            typeB.setName("TypeB");
            typeB.getESuperTypes().add(typeA);
            sourceResource.getContents().add(typeB);

            // Fresh registry for each run
            TransformationRegistry freshRegistry = new TransformationRegistry();
            freshRegistry.register(CrossElementDependencyTransformation.class);
            context.setTransformationRegistry(freshRegistry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(freshRegistry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            assertEquals(2, eagerRuleExecutionCount.get(),
                    "Both elements should be transformed");

            // Check all equivalent() calls returned non-null
            for (EObject result : equivalentResults) {
                assertNotNull(result,
                        "equivalent() returned null - race condition detected!");
            }
        }

        /**
         * Test with many elements to increase likelihood of race condition.
         */
        @Test
        @DisplayName("Cross-element dependencies with many elements")
        void crossElementWithManyElements() {
            // Create a type hierarchy: Base -> Level1_1, Level1_2, ... -> Level2_1, ...
            EClass baseClass = EcoreFactory.eINSTANCE.createEClass();
            baseClass.setName("Base");
            sourceResource.getContents().add(baseClass);

            // Create 20 classes that all extend Base
            for (int i = 0; i < 20; i++) {
                EClass derived = EcoreFactory.eINSTANCE.createEClass();
                derived.setName("Derived" + i);
                derived.getESuperTypes().add(baseClass);
                sourceResource.getContents().add(derived);
            }

            registry.register(CrossElementDependencyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            // 21 classes should be transformed
            assertEquals(21, eagerRuleExecutionCount.get(),
                    "All 21 classes should be transformed");

            // All equivalent() calls for supertypes should return non-null
            int nullCount = 0;
            for (EObject result : equivalentResults) {
                if (result == null) nullCount++;
            }

            assertEquals(0, nullCount,
                    "Found " + nullCount + " null results from equivalent(). " +
                    "This indicates cross-element dependencies are failing.");
        }
    }

    // ==================== Phase 0.5: Separate Eager Rules - No Lazy Fallback ====================

    @Nested
    @DisplayName("Phase 0.5: Separate Eager Rules with No Lazy Rule")
    class SeparateEagerRulesTests {

        // Track results specifically for this test
        static List<EObject> operationETypeResults = Collections.synchronizedList(new ArrayList<>());
        static AtomicInteger operationRuleCount = new AtomicInteger(0);
        static AtomicInteger dataTypeRuleCount = new AtomicInteger(0);

        @BeforeEach
        void resetCounters() {
            operationETypeResults.clear();
            operationRuleCount.set(0);
            dataTypeRuleCount.set(0);
        }

        /**
         * THIS IS THE ACTUAL BUG REPRODUCTION.
         *
         * <p>Scenario that matches production:</p>
         * <ul>
         *   <li>Eager rule A transforms EOperation → target EOperation (sets eType via equivalent)</li>
         *   <li>Eager rule B transforms EDataType → target EDataType (the return type)</li>
         *   <li>Rule A calls ctx.equivalent(returnType, EDataType.class) to set eType</li>
         *   <li>NO lazy rule for EDataType exists</li>
         * </ul>
         *
         * <p>In parallel execution:</p>
         * <ol>
         *   <li>Thread 1 starts Rule A for EOperation</li>
         *   <li>Thread 2 starts Rule B for EDataType</li>
         *   <li>Thread 1 calls equivalent(returnType, EDataType.class)</li>
         *   <li>equivalent() checks resolution cache - MISS (Thread 2 not done)</li>
         *   <li>equivalent() looks for LAZY rules for EDataType - NONE EXIST</li>
         *   <li>XMI ID lookup is INSIDE the lazy rule loop - NEVER EXECUTED</li>
         *   <li>equivalent() returns null → eType: null!</li>
         * </ol>
         */
        @Test
        @DisplayName("equivalent() should work when target is from separate eager rule (no lazy rule)")
        void equivalentWithSeparateEagerRulesNoLazyRule() {
            // Create an EDataType (the return type)
            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("MyReturnType");
            returnType.setInstanceClassName("java.lang.String");
            sourceResource.getContents().add(returnType);

            // Create an EOperation that uses this type as its return type
            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("OwningClass");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("myOperation");
            operation.setEType(returnType);  // Operation returns MyReturnType
            owningClass.getEOperations().add(operation);

            // Register transformation with SEPARATE eager rules:
            // - One for EOperation (calls equivalent for return type)
            // - One for EDataType (transforms the return type)
            // - NO lazy rule for EDataType!
            registry.register(SeparateEagerRulesTransformation.class);
            context.setTransformationRegistry(registry);

            // Enable structured IDs (like production)
            context.setUseStructuredIds(true);

            // Run in PARALLEL to expose the race condition
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            // Both rules should execute
            assertTrue(operationRuleCount.get() >= 1,
                    "Operation rule should execute at least once");
            assertTrue(dataTypeRuleCount.get() >= 1,
                    "DataType rule should execute at least once");

            // The equivalent() call for the return type should NOT return null
            assertFalse(operationETypeResults.isEmpty(),
                    "equivalent() should have been called for return type");

            for (int i = 0; i < operationETypeResults.size(); i++) {
                EObject result = operationETypeResults.get(i);
                assertNotNull(result,
                        "equivalent() returned null at index " + i + "!\n" +
                        "This reproduces the eType: null bug.\n" +
                        "Root cause: XMI ID lookup is inside the lazy rule loop.\n" +
                        "When no lazy rules exist, the loop never executes,\n" +
                        "so XMI ID lookup is skipped and equivalent() returns null.");
            }
        }

        /**
         * Run the test repeatedly to catch race conditions.
         */
        @RepeatedTest(value = 10, name = "Separate eager rules attempt {currentRepetition}/{totalRepetitions}")
        @DisplayName("Separate eager rules should work consistently")
        void separateEagerRulesConsistent() {
            resetCounters();
            sourceResource.getContents().clear();
            targetResource.getContents().clear();

            // Create return type
            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("ReturnType");
            sourceResource.getContents().add(returnType);

            // Create owning class with operation
            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("Owner");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("op");
            operation.setEType(returnType);
            owningClass.getEOperations().add(operation);

            TransformationRegistry freshRegistry = new TransformationRegistry();
            freshRegistry.register(SeparateEagerRulesTransformation.class);
            context.setTransformationRegistry(freshRegistry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(freshRegistry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            for (EObject result : operationETypeResults) {
                assertNotNull(result,
                        "Race condition detected! equivalent() returned null.\n" +
                        "The DataType wasn't found because XMI ID lookup was skipped.");
            }
        }

        /**
         * Test with many operations to increase race condition likelihood.
         */
        @Test
        @DisplayName("Many operations with separate return types")
        void manyOperationsWithSeparateReturnTypes() {
            // Create 10 different return types
            List<EDataType> returnTypes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
                returnType.setName("ReturnType" + i);
                sourceResource.getContents().add(returnType);
                returnTypes.add(returnType);
            }

            // Create 10 classes, each with 2 operations using different return types
            for (int i = 0; i < 10; i++) {
                EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
                owningClass.setName("Class" + i);
                sourceResource.getContents().add(owningClass);

                for (int j = 0; j < 2; j++) {
                    EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
                    operation.setName("operation" + i + "_" + j);
                    operation.setEType(returnTypes.get((i + j) % returnTypes.size()));
                    owningClass.getEOperations().add(operation);
                }
            }

            registry.register(SeparateEagerRulesTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            // Count null results
            int nullCount = 0;
            for (EObject result : operationETypeResults) {
                if (result == null) nullCount++;
            }

            assertEquals(0, nullCount,
                    "Found " + nullCount + " null results out of " + operationETypeResults.size() + ".\n" +
                    "This confirms the eType: null bug with separate eager rules.");
        }
    }

    // ==================== Phase 0.6: equivalent(source, ruleName) Tests ====================

    @Nested
    @DisplayName("Phase 0.6: equivalent(source, ruleName) with Eager Rules")
    class EquivalentByRuleNameTests {

        static List<EObject> ruleNameEquivalentResults = Collections.synchronizedList(new ArrayList<>());
        static AtomicInteger operationRuleCount = new AtomicInteger(0);
        static AtomicInteger dataTypeRuleCount = new AtomicInteger(0);

        @BeforeEach
        void resetCounters() {
            ruleNameEquivalentResults.clear();
            operationRuleCount.set(0);
            dataTypeRuleCount.set(0);
        }

        /**
         * Test equivalent(source, ruleName) with an eager rule.
         * This variant looks up by specific rule name.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) should work with eager rules")
        void equivalentByRuleNameWithEagerRule() {
            // Create return type
            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("MyReturnType");
            sourceResource.getContents().add(returnType);

            // Create operation
            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("Owner");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("myOp");
            operation.setEType(returnType);
            owningClass.getEOperations().add(operation);

            registry.register(EquivalentByRuleNameTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            assertTrue(operationRuleCount.get() >= 1, "Operation rule should execute");
            assertTrue(dataTypeRuleCount.get() >= 1, "DataType rule should execute");

            for (int i = 0; i < ruleNameEquivalentResults.size(); i++) {
                assertNotNull(ruleNameEquivalentResults.get(i),
                        "equivalent(source, ruleName) returned null at index " + i);
            }
        }

        @RepeatedTest(value = 10, name = "equivalent(source, ruleName) attempt {currentRepetition}/{totalRepetitions}")
        @DisplayName("equivalent(source, ruleName) should work consistently")
        void equivalentByRuleNameConsistent() {
            resetCounters();
            sourceResource.getContents().clear();
            targetResource.getContents().clear();

            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("ReturnType");
            sourceResource.getContents().add(returnType);

            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("Owner");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("op");
            operation.setEType(returnType);
            owningClass.getEOperations().add(operation);

            TransformationRegistry freshRegistry = new TransformationRegistry();
            freshRegistry.register(EquivalentByRuleNameTransformation.class);
            context.setTransformationRegistry(freshRegistry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(freshRegistry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            for (EObject result : ruleNameEquivalentResults) {
                assertNotNull(result, "equivalent(source, ruleName) race condition - returned null");
            }
        }
    }

    // ==================== Phase 0.7: equivalentDiscriminated() Tests ====================

    @Nested
    @DisplayName("Phase 0.7: equivalentDiscriminated() with Eager Rules")
    class EquivalentDiscriminatedTests {

        static List<EObject> discriminatedResults = Collections.synchronizedList(new ArrayList<>());
        static AtomicInteger operationRuleCount = new AtomicInteger(0);
        static AtomicInteger dataTypeRuleCount = new AtomicInteger(0);

        @BeforeEach
        void resetCounters() {
            discriminatedResults.clear();
            operationRuleCount.set(0);
            dataTypeRuleCount.set(0);
        }

        /**
         * Test equivalentDiscriminated() with an eager rule.
         */
        @Test
        @DisplayName("equivalentDiscriminated() should work with eager rules")
        void equivalentDiscriminatedWithEagerRule() {
            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("MyReturnType");
            sourceResource.getContents().add(returnType);

            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("Owner");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("myOp");
            operation.setEType(returnType);
            owningClass.getEOperations().add(operation);

            registry.register(EquivalentDiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            assertTrue(operationRuleCount.get() >= 1, "Operation rule should execute");
            assertTrue(dataTypeRuleCount.get() >= 1, "DataType rule should execute");

            for (int i = 0; i < discriminatedResults.size(); i++) {
                assertNotNull(discriminatedResults.get(i),
                        "equivalentDiscriminated() returned null at index " + i);
            }
        }

        @RepeatedTest(value = 10, name = "equivalentDiscriminated() attempt {currentRepetition}/{totalRepetitions}")
        @DisplayName("equivalentDiscriminated() should work consistently")
        void equivalentDiscriminatedConsistent() {
            resetCounters();
            sourceResource.getContents().clear();
            targetResource.getContents().clear();

            EDataType returnType = EcoreFactory.eINSTANCE.createEDataType();
            returnType.setName("ReturnType");
            sourceResource.getContents().add(returnType);

            EClass owningClass = EcoreFactory.eINSTANCE.createEClass();
            owningClass.setName("Owner");
            sourceResource.getContents().add(owningClass);

            EOperation operation = EcoreFactory.eINSTANCE.createEOperation();
            operation.setName("op");
            operation.setEType(returnType);
            owningClass.getEOperations().add(operation);

            TransformationRegistry freshRegistry = new TransformationRegistry();
            freshRegistry.register(EquivalentDiscriminatedTransformation.class);
            context.setTransformationRegistry(freshRegistry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(freshRegistry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            for (EObject result : discriminatedResults) {
                assertNotNull(result, "equivalentDiscriminated() race condition - returned null");
            }
        }
    }

    // ==================== Phase 0.8: executeParentRule() Tests ====================

    @Nested
    @DisplayName("Phase 0.8: executeParentRule() with Eager Rules")
    class ExecuteParentRuleTests {

        static List<EObject> parentRuleResults = Collections.synchronizedList(new ArrayList<>());
        static AtomicInteger childRuleCount = new AtomicInteger(0);
        static AtomicInteger parentRuleCount = new AtomicInteger(0);

        @BeforeEach
        void resetCounters() {
            parentRuleResults.clear();
            childRuleCount.set(0);
            parentRuleCount.set(0);
        }

        /**
         * Test executeParentRule() where parent is an eager rule.
         * Child rule calls executeParentRule() to delegate to parent.
         */
        @Test
        @DisplayName("executeParentRule() should work with eager parent rules")
        void executeParentRuleWithEagerParent() {
            // Create two classes - child will call parent rule for supertype
            EClass parentClass = EcoreFactory.eINSTANCE.createEClass();
            parentClass.setName("ParentClass");
            sourceResource.getContents().add(parentClass);

            EClass childClass = EcoreFactory.eINSTANCE.createEClass();
            childClass.setName("ChildClass");
            childClass.getESuperTypes().add(parentClass);
            sourceResource.getContents().add(childClass);

            registry.register(ExecuteParentRuleTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            assertTrue(childRuleCount.get() >= 1, "Child rule should execute");
            assertTrue(parentRuleCount.get() >= 1, "Parent rule should execute");

            for (int i = 0; i < parentRuleResults.size(); i++) {
                assertNotNull(parentRuleResults.get(i),
                        "executeParentRule() returned null at index " + i);
            }
        }

        @RepeatedTest(value = 10, name = "executeParentRule() attempt {currentRepetition}/{totalRepetitions}")
        @DisplayName("executeParentRule() should work consistently")
        void executeParentRuleConsistent() {
            resetCounters();
            sourceResource.getContents().clear();
            targetResource.getContents().clear();

            EClass parentClass = EcoreFactory.eINSTANCE.createEClass();
            parentClass.setName("Parent");
            sourceResource.getContents().add(parentClass);

            EClass childClass = EcoreFactory.eINSTANCE.createEClass();
            childClass.setName("Child");
            childClass.getESuperTypes().add(parentClass);
            sourceResource.getContents().add(childClass);

            TransformationRegistry freshRegistry = new TransformationRegistry();
            freshRegistry.register(ExecuteParentRuleTransformation.class);
            context.setTransformationRegistry(freshRegistry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(freshRegistry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            for (EObject result : parentRuleResults) {
                assertNotNull(result, "executeParentRule() race condition - returned null");
            }
        }

        /**
         * Test with many elements to stress test parallel execution.
         */
        @Test
        @DisplayName("executeParentRule() with many elements")
        void executeParentRuleWithManyElements() {
            // Create 10 parent classes
            List<EClass> parentClasses = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                EClass parentClass = EcoreFactory.eINSTANCE.createEClass();
                parentClass.setName("Parent" + i);
                sourceResource.getContents().add(parentClass);
                parentClasses.add(parentClass);
            }

            // Create 20 child classes, each extending a parent
            for (int i = 0; i < 20; i++) {
                EClass childClass = EcoreFactory.eINSTANCE.createEClass();
                childClass.setName("Child" + i);
                childClass.getESuperTypes().add(parentClasses.get(i % 10));
                sourceResource.getContents().add(childClass);
            }

            registry.register(ExecuteParentRuleTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .build();

            executor.transform();

            int nullCount = 0;
            for (EObject result : parentRuleResults) {
                if (result == null) nullCount++;
            }

            assertEquals(0, nullCount,
                    "Found " + nullCount + " null results out of " + parentRuleResults.size() +
                    " executeParentRule() calls.");
        }
    }

    // ==================== Transformation Classes ====================

    /**
     * Transformation using equivalent(source, ruleName) with eager rules.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EOperation.class, target = EOperation.class)
    public static class EquivalentByRuleNameTransformation {

        @TransformRule(name = "TransformOperationByRuleName")
        @Transform(type = EOperation.class)
        @Greedy
        public TransformFunction<EOperation, EOperation> transformOperation() {
            return (source, ctx) -> {
                EquivalentByRuleNameTests.operationRuleCount.incrementAndGet();

                EOperation targetOp = ctx.createTarget(EOperation.class);
                targetOp.setName(source.getName() + "_transformed");

                // Use equivalent(source, ruleName) - specifies exact rule name
                if (source.getEType() != null) {
                    EClassifier transformedType = ctx.equivalent(source.getEType(), "TransformDataTypeByRuleName");
                    EquivalentByRuleNameTests.ruleNameEquivalentResults.add(transformedType);
                    targetOp.setEType(transformedType);
                }

                return targetOp;
            };
        }

        @TransformRule(name = "TransformDataTypeByRuleName")
        @Transform(type = EDataType.class)
        @Greedy
        public TransformFunction<EDataType, EDataType> transformDataType() {
            return (source, ctx) -> {
                EquivalentByRuleNameTests.dataTypeRuleCount.incrementAndGet();

                EDataType targetType = ctx.createTarget(EDataType.class);
                targetType.setName(source.getName() + "_transformed");
                return targetType;
            };
        }
    }

    /**
     * Transformation using equivalentDiscriminated() with eager rules.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EOperation.class, target = EOperation.class)
    public static class EquivalentDiscriminatedTransformation {

        @TransformRule(name = "TransformOperationDiscriminated")
        @Transform(type = EOperation.class)
        @Greedy
        public TransformFunction<EOperation, EOperation> transformOperation() {
            return (source, ctx) -> {
                EquivalentDiscriminatedTests.operationRuleCount.incrementAndGet();

                EOperation targetOp = ctx.createTarget(EOperation.class);
                targetOp.setName(source.getName() + "_transformed");

                // Use equivalentDiscriminated() - with discriminator
                if (source.getEType() != null) {
                    EClassifier transformedType = ctx.equivalentDiscriminated(
                            source.getEType(),
                            EClassifier.class,
                            "TransformDataTypeDiscriminated",
                            "variant1"
                    );
                    EquivalentDiscriminatedTests.discriminatedResults.add(transformedType);
                    targetOp.setEType(transformedType);
                }

                return targetOp;
            };
        }

        @TransformRule(name = "TransformDataTypeDiscriminated")
        @Transform(type = EDataType.class)
        @Greedy
        public TransformFunction<EDataType, EDataType> transformDataType() {
            return (source, ctx) -> {
                EquivalentDiscriminatedTests.dataTypeRuleCount.incrementAndGet();

                EDataType targetType = ctx.createTarget(EDataType.class);
                targetType.setName(source.getName() + "_transformed");
                return targetType;
            };
        }
    }

    /**
     * Transformation using executeParentRule() with eager rules.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ExecuteParentRuleTransformation {

        @TransformRule(name = "TransformParentClass")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EClass> transformParentClass() {
            return (source, ctx) -> {
                ExecuteParentRuleTests.parentRuleCount.incrementAndGet();

                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_parent_transformed");
                return targetClass;
            };
        }

        @TransformRule(name = "TransformChildClass")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EClass> transformChildClass() {
            return (source, ctx) -> {
                // Only process classes that have supertypes (children)
                if (source.getESuperTypes().isEmpty()) {
                    return null; // Let TransformParentClass handle it
                }

                ExecuteParentRuleTests.childRuleCount.incrementAndGet();

                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_child_transformed");

                // Call executeParentRule for each supertype
                for (EClass supertype : source.getESuperTypes()) {
                    EClass transformedParent = ctx.executeParentRule("TransformParentClass", supertype);
                    ExecuteParentRuleTests.parentRuleResults.add(transformedParent);
                    if (transformedParent != null) {
                        targetClass.getESuperTypes().add(transformedParent);
                    }
                }

                return targetClass;
            };
        }
    }

    /**
     * Transformation with SEPARATE eager rules for different types.
     * This is the actual production scenario that triggers the bug.
     *
     * - EOperation rule: eager, calls equivalent() for return type
     * - EDataType rule: eager, transforms the return type
     * - NO lazy rule for EDataType!
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EOperation.class, target = EOperation.class)
    public static class SeparateEagerRulesTransformation {

        /**
         * Eager rule for EOperation.
         * Calls equivalent(eType) to get the transformed return type.
         */
        @TransformRule(name = "TransformOperation")
        @Transform(type = EOperation.class)
        @Greedy
        public TransformFunction<EOperation, EOperation> transformOperation() {
            return (source, ctx) -> {
                SeparateEagerRulesTests.operationRuleCount.incrementAndGet();

                EOperation targetOp = ctx.createTarget(EOperation.class);
                targetOp.setName(source.getName() + "_transformed");

                // This is the critical call!
                // In production: equivalent() for return type set by SEPARATE eager rule
                // If no lazy rule exists for EDataType, XMI ID lookup is skipped
                if (source.getEType() != null) {
                    EClassifier transformedType = ctx.equivalent(source.getEType(), EClassifier.class);
                    SeparateEagerRulesTests.operationETypeResults.add(transformedType);
                    targetOp.setEType(transformedType);  // May be null - the bug!
                }

                return targetOp;
            };
        }

        /**
         * Eager rule for EDataType (the return type).
         * This is a SEPARATE rule - NOT lazy!
         */
        @TransformRule(name = "TransformDataType")
        @Transform(type = EDataType.class)
        @Greedy
        public TransformFunction<EDataType, EDataType> transformDataType() {
            return (source, ctx) -> {
                SeparateEagerRulesTests.dataTypeRuleCount.incrementAndGet();

                EDataType targetType = ctx.createTarget(EDataType.class);
                targetType.setName(source.getName() + "_transformed");
                targetType.setInstanceClassName(source.getInstanceClassName());

                return targetType;
            };
        }

        // NOTE: No @Lazy rule for EDataType!
        // This is intentional - in production, not all types have lazy rules.
        // The equivalent() method should still work via XMI ID lookup.
    }

    /**
     * Transformation with cross-element dependencies.
     * The rule for Child calls equivalent(Parent) - both are eager.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CrossElementDependencyTransformation {
        @TransformRule(name = "TransformClassWithSupertype")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EClass> transformClass() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_transformed");

                // For each supertype, call equivalent() to get the transformed supertype
                // This is a CROSS-ELEMENT dependency - we're looking up a DIFFERENT source element
                for (EClass supertype : source.getESuperTypes()) {
                    EClass transformedSupertype = ctx.equivalent(supertype, EClass.class);
                    equivalentResults.add(transformedSupertype);
                    if (transformedSupertype != null) {
                        targetClass.getESuperTypes().add(transformedSupertype);
                    }
                }

                return targetClass;
            };
        }
    }

    /**
     * Greedy rule registered for supertype (EClassifier).
     * Should match all subtypes: EClass, EDataType, EEnum.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClassifier.class, target = EPackage.class)
    public static class GreedySupertypeTransformation {
        @TransformRule(name = "ClassifierToPackage")
        @Transform(type = EClassifier.class)
        @Greedy
        public TransformFunction<EClassifier, EPackage> classifierToPackage() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                executionLog.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    /**
     * Multiple greedy rules for same source type.
     * All should execute for each matching element.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultipleGreedyRulesTransformation {
        @TransformRule(name = "Rule1")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule1() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                executionLog.add("Rule1:" + source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_1");
                return pkg;
            };
        }

        @TransformRule(name = "Rule2")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule2() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                executionLog.add("Rule2:" + source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_2");
                return pkg;
            };
        }

        @TransformRule(name = "Rule3")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule3() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                executionLog.add("Rule3:" + source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_3");
                return pkg;
            };
        }
    }

    /**
     * Eager rule that calls equivalent() to look up lazy rule results.
     * Reproduces the eType: null issue where equivalent() returns null.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class EagerWithEquivalentTransformation {

        @TransformRule(name = "TransformClass")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EClass> transformClass() {
            return (source, ctx) -> {
                eagerRuleExecutionCount.incrementAndGet();
                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName(source.getName() + "_transformed");

                // For each attribute, call equivalent() to get the transformed attribute
                // This reproduces the eType: null issue where equivalent() returns null
                for (EAttribute attr : source.getEAttributes()) {
                    EAttribute targetAttr = ctx.equivalent(attr, EAttribute.class);
                    equivalentResults.add(targetAttr);
                    if (targetAttr != null) {
                        targetClass.getEStructuralFeatures().add(targetAttr);
                    }
                }

                return targetClass;
            };
        }

        @TransformRule(name = "TransformAttribute")
        @Transform(type = EAttribute.class)
        @Lazy
        @Greedy
        public TransformFunction<EAttribute, EAttribute> transformAttribute() {
            return (source, ctx) -> {
                lazyRuleExecutionCount.incrementAndGet();
                EAttribute targetAttr = ctx.createTarget(EAttribute.class);
                targetAttr.setName(source.getName() + "_transformed");
                targetAttr.setEType(source.getEType());
                return targetAttr;
            };
        }
    }
}
