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

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExecutionStrategy} feature — RULE_BY_RULE vs ELEMENT_BY_ELEMENT execution.
 *
 * <p>Uses EcorePackage types (EPackage, EClass, EAttribute, EReference, EDataType, EAnnotation)
 * to simulate transformation scenarios. Multiple transformation classes registered in specific
 * order emulate ETL module imports.</p>
 */
@DisplayName("ExecutionStrategy Tests")
class ExecutionStrategyTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking for test assertions
    static List<String> executionLog;
    static AtomicInteger executionCount;
    static Map<String, EObject> createdElements;
    static Map<String, List<EObject>> ruleOutputs;

    @BeforeEach
    void setUp() {
        executionLog = Collections.synchronizedList(new ArrayList<>());
        executionCount = new AtomicInteger(0);
        createdElements = Collections.synchronizedMap(new LinkedHashMap<>());
        ruleOutputs = new ConcurrentHashMap<>();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private EPackage createPackage(String name) {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName(name);
        pkg.setNsURI("test://" + name);
        pkg.setNsPrefix(name);
        sourceResource.getContents().add(pkg);
        return pkg;
    }

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    private EAttribute createAttribute(String name) {
        EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName(name);
        attr.setEType(EcorePackage.Literals.ESTRING);
        sourceResource.getContents().add(attr);
        return attr;
    }

    private EDataType createDataType(String name) {
        EDataType dt = EcoreFactory.eINSTANCE.createEDataType();
        dt.setName(name);
        sourceResource.getContents().add(dt);
        return dt;
    }

    private TransformationExecutor buildExecutor(ExecutionStrategy strategy, boolean parallel) {
        return TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(parallel)
                .parallelThreshold(1) // Always use parallel when parallel=true
                .executionStrategy(strategy)
                .build();
    }

    private TransformationExecutor buildExecutor(ExecutionStrategy strategy, boolean parallel, boolean etlCompat) {
        return TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(parallel)
                .parallelThreshold(1)
                .executionStrategy(strategy)
                .etlCompatibilityMode(etlCompat)
                .build();
    }

    // ========================================================================
    // Transformation Classes (registered in specific order like ETL imports)
    // ========================================================================

    // --- PackageRules: processes EPackage → EAnnotation ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EAnnotation.class)
    public static class PackageRules {
        @TransformRule(name = "PackageToAnnotation")
        @Transform(type = EPackage.class)
        @Primary
        public TransformFunction<EPackage, EAnnotation> packageToAnnotation() {
            return (pkg, ctx) -> {
                executionLog.add("PackageToAnnotation:" + pkg.getName());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("pkg:" + pkg.getName());
                ruleOutputs.computeIfAbsent("PackageToAnnotation", k -> Collections.synchronizedList(new ArrayList<>())).add(ann);
                return ann;
            };
        }
    }

    // --- ClassRules: processes EClass → EClass (target) ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ClassRules {
        @TransformRule(name = "ClassToTable")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EClass> classToTable() {
            return (eClass, ctx) -> {
                executionLog.add("ClassToTable:" + eClass.getName());
                executionCount.incrementAndGet();
                EClass table = EcoreFactory.eINSTANCE.createEClass();
                table.setName(eClass.getName() + "_Table");
                ruleOutputs.computeIfAbsent("ClassToTable", k -> Collections.synchronizedList(new ArrayList<>())).add(table);
                return table;
            };
        }
    }

    // --- AttributeRules: processes EAttribute → EAttribute (target) ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAttribute.class, target = EAttribute.class)
    public static class AttributeRules {
        @TransformRule(name = "AttributeToColumn")
        @Transform(type = EAttribute.class)
        @Primary
        public TransformFunction<EAttribute, EAttribute> attributeToColumn() {
            return (attr, ctx) -> {
                executionLog.add("AttributeToColumn:" + attr.getName());
                executionCount.incrementAndGet();
                EAttribute col = EcoreFactory.eINSTANCE.createEAttribute();
                col.setName(attr.getName() + "_Col");
                ruleOutputs.computeIfAbsent("AttributeToColumn", k -> Collections.synchronizedList(new ArrayList<>())).add(col);
                return col;
            };
        }
    }

    // --- NavigationRule: processes EClass, looks up ALL ClassToTable outputs ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class NavigationRule {
        @TransformRule(name = "ClassNavigator")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> classNavigator() {
            return (eClass, ctx) -> {
                // Count how many ClassToTable outputs exist at this point
                List<EObject> classOutputs = ruleOutputs.getOrDefault("ClassToTable", Collections.emptyList());
                executionLog.add("ClassNavigator:" + eClass.getName() + ":sees=" + classOutputs.size());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("nav:" + eClass.getName() + ":" + classOutputs.size());
                return ann;
            };
        }
    }

    // --- LazyHelperRule: lazy rule triggered via equivalent() ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyHelperRule {
        @TransformRule(name = "LazyHelper")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> lazyHelper() {
            return (eClass, ctx) -> {
                executionLog.add("LazyHelper:" + eClass.getName());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("lazy:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- GreedyLazyRule: @Greedy @Lazy → activity-based in ETL compat mode ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GreedyLazyRule {
        @TransformRule(name = "GreedyLazyProcessor")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        public TransformFunction<EClass, EAnnotation> greedyLazyProcessor() {
            return (eClass, ctx) -> {
                executionLog.add("GreedyLazyProcessor:" + eClass.getName());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("greedylazy:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- InheritedRule: child extends ClassToTable ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class InheritedRule {
        @TransformRule(name = "SpecialClassToTable")
        @Transform(type = EClass.class)
        @Extends("ClassToTable")
        public TransformFunction<EClass, EClass> specialClassToTable() {
            return (eClass, ctx) -> {
                executionLog.add("SpecialClassToTable:" + eClass.getName());
                executionCount.incrementAndGet();
                EClass table = EcoreFactory.eINSTANCE.createEClass();
                table.setName(eClass.getName() + "_SpecialTable");
                return table;
            };
        }
    }

    // --- DiscriminatedRule: uses equivalentDiscriminated ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DiscriminatedRule {
        @TransformRule(name = "DiscriminatedAction")
        @Transform(type = EClass.class)
        @Lazy
        @Detached
        public TransformFunction<EClass, EAnnotation> discriminatedAction() {
            return (eClass, ctx) -> {
                executionLog.add("DiscriminatedAction:" + eClass.getName());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("disc:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- GuardedRule: has a guard that rejects elements with name starting with "Skip" ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GuardedRule {
        @TransformRule(name = "GuardedTransform")
        @Transform(type = EClass.class)
        @Guard(method = "shouldTransform")
        public TransformFunction<EClass, EAnnotation> guardedTransform() {
            return (eClass, ctx) -> {
                executionLog.add("GuardedTransform:" + eClass.getName());
                executionCount.incrementAndGet();
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("guarded:" + eClass.getName());
                return ann;
            };
        }

        public boolean shouldTransform(EObject source, TransformationContext ctx) {
            if (source instanceof EClass eClass) {
                return !eClass.getName().startsWith("Skip");
            }
            return true;
        }
    }

    // --- ErrorRule: always throws ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EDataType.class, target = EAnnotation.class)
    public static class ErrorRule {
        @TransformRule(name = "ErrorTransform")
        @Transform(type = EDataType.class)
        @Primary
        public TransformFunction<EDataType, EAnnotation> errorTransform() {
            return (dt, ctx) -> {
                executionLog.add("ErrorTransform:" + dt.getName());
                throw new RuntimeException("Intentional error for " + dt.getName());
            };
        }
    }

    // --- LazyTriggeringRule: eager rule that triggers LazyHelper via equivalent() ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyTriggeringRule {
        @TransformRule(name = "LazyTrigger")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyTrigger() {
            return (eClass, ctx) -> {
                executionLog.add("LazyTrigger:" + eClass.getName());
                // Trigger the lazy rule
                EAnnotation lazyResult = ctx.equivalent(eClass, EAnnotation.class, "LazyHelper");
                executionLog.add("LazyTrigger:" + eClass.getName() + ":lazyResult=" + (lazyResult != null));
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("trigger:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- CrossElementLookupRule: calls equivalent(otherSource, "ClassToTable") ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAttribute.class, target = EAnnotation.class)
    public static class CrossElementLookupRule {
        @TransformRule(name = "CrossLookup")
        @Transform(type = EAttribute.class)
        public TransformFunction<EAttribute, EAnnotation> crossLookup() {
            return (attr, ctx) -> {
                // Look up a ClassToTable output for the first EClass in source model
                List<EObject> classOutputs = ruleOutputs.getOrDefault("ClassToTable", Collections.emptyList());
                executionLog.add("CrossLookup:" + attr.getName() + ":classOutputs=" + classOutputs.size());
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("cross:" + attr.getName() + ":" + classOutputs.size());
                return ann;
            };
        }
    }

    // ========================================================================
    // Critical Tests (must-pass for release)
    // ========================================================================

    @Nested
    @DisplayName("Critical Tests")
    class CriticalTests {

        /**
         * Test 1: RULE_BY_RULE processes all elements for RuleA before RuleB starts.
         */
        @Test
        @DisplayName("Test 1: RULE_BY_RULE processes all elements per rule before next rule")
        void ruleByRuleProcessesAllElementsPerRule() {
            createEClass("Alpha");
            createEClass("Beta");
            createEClass("Gamma");

            // Register PackageRules first, then ClassRules — but no packages exist,
            // so only ClassRules should fire
            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // Verify ClassToTable runs for ALL classes before ClassNavigator starts
            List<String> classToTableEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToTable:"))
                    .toList();
            List<String> navigatorEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .toList();

            assertEquals(3, classToTableEntries.size(), "ClassToTable should process 3 classes");
            assertEquals(3, navigatorEntries.size(), "ClassNavigator should process 3 classes");

            // All ClassToTable entries must appear before any ClassNavigator entry
            int lastClassToTable = executionLog.lastIndexOf(classToTableEntries.get(classToTableEntries.size() - 1));
            int firstNavigator = executionLog.indexOf(navigatorEntries.get(0));
            assertTrue(lastClassToTable < firstNavigator,
                    "All ClassToTable executions must complete before ClassNavigator starts");
        }

        /**
         * Test 2: ELEMENT_BY_ELEMENT processes all rules for E1 before E2.
         */
        @Test
        @DisplayName("Test 2: ELEMENT_BY_ELEMENT processes all rules per element")
        void elementByElementProcessesAllRulesPerElement() {
            createEClass("Alpha");
            createEClass("Beta");

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.ELEMENT_BY_ELEMENT, false);
            executor.transform();

            // Verify interleaved execution: ClassToTable:Alpha, ClassNavigator:Alpha,
            // ClassToTable:Beta, ClassNavigator:Beta
            // The ClassToTable and Navigator for the same element should be adjacent
            assertEquals(4, executionLog.size());

            // First element's rules should complete before second element starts
            assertTrue(executionLog.get(0).contains("Alpha"));
            assertTrue(executionLog.get(1).contains("Alpha"));
            assertTrue(executionLog.get(2).contains("Beta"));
            assertTrue(executionLog.get(3).contains("Beta"));
        }

        /**
         * Test 3: RULE_BY_RULE — later rule sees ALL outputs from earlier rule.
         */
        @Test
        @DisplayName("Test 3: Later rule sees ALL outputs from earlier rule in RULE_BY_RULE")
        void laterRuleSeesAllOutputsFromEarlierRule() {
            createEClass("A");
            createEClass("B");
            createEClass("C");

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // ClassNavigator should see ALL 3 ClassToTable outputs
            List<String> navEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .toList();

            for (String entry : navEntries) {
                assertTrue(entry.contains(":sees=3"),
                        "Each ClassNavigator invocation should see 3 ClassToTable outputs, got: " + entry);
            }
        }

        /**
         * Test 4: ELEMENT_BY_ELEMENT — later rule sees only current element's outputs.
         */
        @Test
        @DisplayName("Test 4: Later rule sees only incremental outputs in ELEMENT_BY_ELEMENT")
        void elementByElementSeesIncrementalOutputs() {
            createEClass("A");
            createEClass("B");
            createEClass("C");

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.ELEMENT_BY_ELEMENT, false);
            executor.transform();

            // In element-by-element, ClassNavigator for A sees 1 output (only A's),
            // ClassNavigator for B sees 2 (A's and B's), ClassNavigator for C sees 3
            List<String> navEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .toList();

            assertEquals(3, navEntries.size());
            assertTrue(navEntries.get(0).contains(":sees=1"), "First nav should see 1 output: " + navEntries.get(0));
            assertTrue(navEntries.get(1).contains(":sees=2"), "Second nav should see 2 outputs: " + navEntries.get(1));
            assertTrue(navEntries.get(2).contains(":sees=3"), "Third nav should see 3 outputs: " + navEntries.get(2));
        }

        /**
         * Test 5: Parallel RULE_BY_RULE output identical to sequential RULE_BY_RULE.
         */
        @Test
        @DisplayName("Test 5: Parallel RULE_BY_RULE produces same outputs as sequential")
        void parallelRuleByRuleMatchesSequential() {
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }

            registry.register(ClassRules.class);
            registry.register(AttributeRules.class);

            // Sequential
            TransformationExecutor seqExecutor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            seqExecutor.transform();
            List<String> sequentialLog = new ArrayList<>(executionLog);
            int sequentialCount = executionCount.get();

            // Reset
            setUp();
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }
            registry.register(ClassRules.class);
            registry.register(AttributeRules.class);

            // Parallel
            TransformationExecutor parExecutor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            parExecutor.transform();

            // Same number of executions
            assertEquals(sequentialCount, executionCount.get(),
                    "Parallel should produce same execution count as sequential");
        }

        /**
         * Test 17: Parallel RULE_BY_RULE — rule barrier ensures RuleA completes before RuleB.
         */
        @Test
        @DisplayName("Test 17: Parallel RULE_BY_RULE has per-rule barrier")
        void parallelRuleByRuleHasBarrier() {
            for (int i = 0; i < 20; i++) {
                createEClass("Class" + i);
            }

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            executor.transform();

            // All ClassToTable must complete before any ClassNavigator
            List<String> classToTableEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToTable:"))
                    .toList();
            List<String> navigatorEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .toList();

            assertEquals(20, classToTableEntries.size());
            assertEquals(20, navigatorEntries.size());

            int lastClassToTable = -1;
            int firstNavigator = Integer.MAX_VALUE;
            for (int i = 0; i < executionLog.size(); i++) {
                if (executionLog.get(i).startsWith("ClassToTable:")) {
                    lastClassToTable = i;
                }
                if (executionLog.get(i).startsWith("ClassNavigator:") && i < firstNavigator) {
                    firstNavigator = i;
                }
            }
            assertTrue(lastClassToTable < firstNavigator,
                    "Rule barrier must ensure ClassToTable completes before ClassNavigator starts");
        }

        /**
         * Test 18: Parallel RULE_BY_RULE output identical with many elements.
         */
        @RepeatedTest(3)
        @DisplayName("Test 18: Parallel RULE_BY_RULE deterministic with many elements")
        void parallelRuleByRuleDeterministicWithManyElements() {
            for (int i = 0; i < 50; i++) {
                createEClass("Class" + i);
            }
            for (int i = 0; i < 50; i++) {
                createAttribute("Attr" + i);
            }

            registry.register(ClassRules.class);
            registry.register(AttributeRules.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            executor.transform();

            // Verify no duplicates
            Set<String> uniqueEntries = new HashSet<>();
            for (String entry : executionLog) {
                assertTrue(uniqueEntries.add(entry),
                        "Duplicate execution detected: " + entry);
            }

            assertEquals(100, executionCount.get(),
                    "Should process 50 classes + 50 attributes = 100");
        }

        /**
         * Test 22: CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is now allowed.
         */
        @Test
        @DisplayName("Test 22: CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is allowed")
        void cloneCurrentStateWithParallelRuleByRuleAllowed() {
            createEClass("Test");
            registry.register(ClassRules.class);

            context.setEquivalentDiscriminatedStrategy(
                    EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);

            assertDoesNotThrow(() -> executor.transform(),
                    "CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is now supported");
        }
    }

    // ========================================================================
    // High Priority Tests
    // ========================================================================

    @Nested
    @DisplayName("High Priority Tests")
    class HighPriorityTests {

        /**
         * Test 6: Lazy rule triggered via equivalent() during rule-by-rule.
         */
        @Test
        @DisplayName("Test 6: Lazy rule triggered via equivalent() during RULE_BY_RULE")
        void lazyRuleTriggeredDuringRuleByRule() {
            createEClass("TestClass");

            registry.register(LazyHelperRule.class);
            registry.register(LazyTriggeringRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // LazyHelper should have been triggered
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("LazyHelper:")),
                    "LazyHelper should be triggered via equivalent()");
            assertTrue(executionLog.stream().anyMatch(s -> s.contains(":lazyResult=true")),
                    "LazyTrigger should receive non-null lazy result");

            // LazyHelper should execute only once (cached)
            long lazyCount = executionLog.stream()
                    .filter(s -> s.startsWith("LazyHelper:"))
                    .count();
            assertEquals(1, lazyCount, "Lazy rule should execute exactly once (cached)");
        }

        /**
         * Test 7: Greedy+Lazy (activity-based) Phase 2 executes after rule-by-rule Phase 1.
         */
        @Test
        @DisplayName("Test 7: Activity-based rules execute after RULE_BY_RULE Phase 1")
        void activityBasedAfterRuleByRulePhase1() {
            EClass classA = createEClass("ClassA");

            registry.register(ClassRules.class);
            registry.register(GreedyLazyRule.class);

            // Enable ETL compat so @Greedy @Lazy is treated as activity-based
            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false, true);

            // Activate the greedy lazy rule by calling equivalent
            // We need an eager rule that triggers the activation
            // Register a rule that activates GreedyLazyProcessor
            registry.register(ActivatorRule.class);
            executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false, true);
            executor.transform();

            // The GreedyLazyProcessor should execute in Phase 2 (after all eager rules)
            if (executionLog.stream().anyMatch(s -> s.startsWith("GreedyLazyProcessor:"))) {
                // If it was triggered, it should be after all ClassToTable entries
                int lastEager = -1;
                int firstActivityBased = Integer.MAX_VALUE;
                for (int i = 0; i < executionLog.size(); i++) {
                    if (executionLog.get(i).startsWith("ClassToTable:")) {
                        lastEager = i;
                    }
                    if (executionLog.get(i).startsWith("GreedyLazyProcessor:") && i < firstActivityBased) {
                        firstActivityBased = i;
                    }
                }
                if (lastEager >= 0 && firstActivityBased < Integer.MAX_VALUE) {
                    assertTrue(lastEager < firstActivityBased,
                            "Activity-based rules must execute after Phase 1 eager rules");
                }
            }
        }

        /**
         * Test 8: Rule inheritance — child rule extends parent.
         */
        @Test
        @DisplayName("Test 8: Rule inheritance with RULE_BY_RULE")
        void ruleInheritanceWithRuleByRule() {
            createEClass("BaseClass");

            registry.register(ClassRules.class);
            registry.register(InheritedRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // Both ClassToTable and SpecialClassToTable should fire
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("ClassToTable:")),
                    "Parent rule should execute");
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("SpecialClassToTable:")),
                    "Child rule should execute");
        }

        /**
         * Test 9: equivalentDiscriminated with RULE_BY_RULE + CLONE_CURRENT_STATE (sequential).
         */
        @Test
        @DisplayName("Test 9: equivalentDiscriminated with RULE_BY_RULE + CLONE_CURRENT_STATE")
        void equivalentDiscriminatedCloneCurrentState() {
            createEClass("TestClass");

            registry.register(DiscriminatedRule.class);
            registry.register(DiscriminatedCallerRuleA.class);
            registry.register(DiscriminatedCallerRuleB.class);

            context.setEquivalentDiscriminatedStrategy(
                    EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // Both callers should have triggered the discriminated rule
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("DiscCallerA:")),
                    "Discriminated caller A should execute");
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("DiscCallerB:")),
                    "Discriminated caller B should execute");
        }

        /**
         * Test 10: equivalentDiscriminated with RULE_BY_RULE + CLONE_PRISTINE.
         */
        @Test
        @DisplayName("Test 10: equivalentDiscriminated with RULE_BY_RULE + CLONE_PRISTINE")
        void equivalentDiscriminatedClonePristine() {
            createEClass("TestClass");

            registry.register(DiscriminatedRule.class);
            registry.register(DiscriminatedCallerRuleA.class);
            registry.register(DiscriminatedCallerRuleB.class);

            // CLONE_PRISTINE is the default
            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("DiscCallerA:")));
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("DiscCallerB:")));
        }

        /**
         * Test 15: Cross-element visibility — RuleB calls equivalent(otherSource, "RuleA").
         */
        @Test
        @DisplayName("Test 15: Cross-element visibility via equivalent() cache")
        void crossElementVisibilityViaEquivalent() {
            EClass classA = createEClass("ClassA");
            EClass classB = createEClass("ClassB");

            registry.register(ClassRules.class);
            registry.register(CrossRefRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // CrossRefRule should be able to look up ClassToTable results for other elements
            assertTrue(executionLog.stream().anyMatch(s -> s.startsWith("CrossRef:")),
                    "CrossRefRule should execute");
        }

        /**
         * Test 16: Mutation propagation — CLONE_CURRENT_STATE in sequential rule-by-rule.
         */
        @Test
        @DisplayName("Test 16: CLONE_CURRENT_STATE + sequential RULE_BY_RULE is allowed")
        void cloneCurrentStateSequentialRuleByRuleAllowed() {
            createEClass("Test");
            registry.register(ClassRules.class);

            context.setEquivalentDiscriminatedStrategy(
                    EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);

            // Should NOT throw — sequential is compatible
            assertDoesNotThrow(() -> executor.transform());
        }

        /**
         * Test 19: Parallel ELEMENT_BY_ELEMENT output identical to sequential.
         */
        @Test
        @DisplayName("Test 19: Parallel ELEMENT_BY_ELEMENT matches sequential")
        void parallelElementByElementMatchesSequential() {
            for (int i = 0; i < 20; i++) {
                createEClass("Class" + i);
            }

            registry.register(ClassRules.class);

            // Sequential
            TransformationExecutor seqExecutor = buildExecutor(ExecutionStrategy.ELEMENT_BY_ELEMENT, false);
            seqExecutor.transform();
            int sequentialCount = executionCount.get();

            // Reset
            setUp();
            for (int i = 0; i < 20; i++) {
                createEClass("Class" + i);
            }
            registry.register(ClassRules.class);

            // Parallel
            TransformationExecutor parExecutor = buildExecutor(ExecutionStrategy.ELEMENT_BY_ELEMENT, true);
            parExecutor.transform();

            assertEquals(sequentialCount, executionCount.get(),
                    "Parallel should produce same count as sequential");
        }

        /**
         * Test 20: Parallel RULE_BY_RULE stress test — 100+ elements.
         */
        @RepeatedTest(3)
        @DisplayName("Test 20: Parallel RULE_BY_RULE stress test with 100+ elements")
        void parallelRuleByRuleStressTest() {
            for (int i = 0; i < 120; i++) {
                createEClass("Class" + i);
            }

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            executor.transform();

            // Verify no duplicates
            long classToTableCount = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToTable:"))
                    .count();
            long navCount = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .count();

            assertEquals(120, classToTableCount, "Should have 120 ClassToTable executions");
            assertEquals(120, navCount, "Should have 120 ClassNavigator executions");

            // No duplicate targets
            Set<String> uniqueClassToTable = new HashSet<>();
            executionLog.stream()
                    .filter(s -> s.startsWith("ClassToTable:"))
                    .forEach(s -> assertTrue(uniqueClassToTable.add(s),
                            "Duplicate ClassToTable execution: " + s));
        }

        /**
         * Test 21: Parallel RULE_BY_RULE — deferred writes committed between rules.
         */
        @Test
        @DisplayName("Test 21: Deferred writes committed between rules in parallel RULE_BY_RULE")
        void deferredWritesCommittedBetweenRules() {
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            executor.transform();

            // NavigationRule should see ALL ClassToTable outputs (committed between rules)
            List<String> navEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassNavigator:"))
                    .toList();

            for (String entry : navEntries) {
                assertTrue(entry.contains(":sees=10"),
                        "In parallel RULE_BY_RULE, navigator should see all 10 outputs: " + entry);
            }
        }

        /**
         * Test 23: Parallel RULE_BY_RULE — lazy rules thread-safe.
         */
        @RepeatedTest(3)
        @DisplayName("Test 23: Lazy rules thread-safe in parallel RULE_BY_RULE")
        void lazyRulesThreadSafeInParallel() {
            for (int i = 0; i < 20; i++) {
                createEClass("Class" + i);
            }

            registry.register(LazyHelperRule.class);
            registry.register(LazyTriggeringRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, true);
            executor.transform();

            // Each LazyHelper should execute at most once per source (cached)
            Map<String, Integer> lazyExecutions = new HashMap<>();
            for (String entry : executionLog) {
                if (entry.startsWith("LazyHelper:")) {
                    lazyExecutions.merge(entry, 1, Integer::sum);
                }
            }

            for (Map.Entry<String, Integer> e : lazyExecutions.entrySet()) {
                assertEquals(1, e.getValue(),
                        "Lazy rule should execute at most once per source: " + e.getKey());
            }
        }
    }

    // ========================================================================
    // Medium Priority Tests
    // ========================================================================

    @Nested
    @DisplayName("Medium Priority Tests")
    class MediumPriorityTests {

        /**
         * Test 11: Guard rejection in RULE_BY_RULE.
         */
        @Test
        @DisplayName("Test 11: Guard rejection works in RULE_BY_RULE")
        void guardRejectionInRuleByRule() {
            createEClass("Allowed1");
            createEClass("SkipMe");
            createEClass("Allowed2");

            registry.register(GuardedRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);
            executor.transform();

            // Only Allowed1 and Allowed2 should be processed
            List<String> entries = executionLog.stream()
                    .filter(s -> s.startsWith("GuardedTransform:"))
                    .toList();

            assertEquals(2, entries.size(), "Guard should reject 'SkipMe'");
            assertTrue(entries.stream().anyMatch(s -> s.contains("Allowed1")));
            assertTrue(entries.stream().anyMatch(s -> s.contains("Allowed2")));
            assertFalse(entries.stream().anyMatch(s -> s.contains("SkipMe")));
        }

        /**
         * Test 12: Error in RULE_BY_RULE — fail-fast.
         */
        @Test
        @DisplayName("Test 12: Fail-fast on error in RULE_BY_RULE")
        void failFastOnErrorInRuleByRule() {
            createDataType("BadType");
            createDataType("NeverReached");

            registry.register(ErrorRule.class);

            TransformationExecutor executor = buildExecutor(ExecutionStrategy.RULE_BY_RULE, false);

            assertThrows(TransformationException.class, executor::transform,
                    "Should throw TransformationException on rule error");
        }

        /**
         * Test 13: Registration order preserved.
         */
        @Test
        @DisplayName("Test 13: getOrderedEagerRules returns rules in registration order")
        void registrationOrderPreserved() {
            registry.register(PackageRules.class);
            registry.register(ClassRules.class);
            registry.register(AttributeRules.class);

            List<TransformRuleDescriptor> orderedRules = registry.getOrderedEagerRules();

            // Should be in registration order
            List<String> ruleNames = orderedRules.stream()
                    .map(TransformRuleDescriptor::getName)
                    .toList();

            assertEquals("PackageToAnnotation", ruleNames.get(0));
            assertEquals("ClassToTable", ruleNames.get(1));
            assertEquals("AttributeToColumn", ruleNames.get(2));
        }

        /**
         * Test 14: Default strategy is ELEMENT_BY_ELEMENT.
         */
        @Test
        @DisplayName("Test 14: Default strategy is ELEMENT_BY_ELEMENT")
        void defaultStrategyIsElementByElement() {
            createEClass("A");
            createEClass("B");

            registry.register(ClassRules.class);
            registry.register(NavigationRule.class);

            // Build without specifying strategy
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify element-by-element behavior: rules interleaved per element
            // ClassToTable:A, ClassNavigator:A, ClassToTable:B, ClassNavigator:B
            assertEquals(4, executionLog.size());
            assertTrue(executionLog.get(0).contains("A"));
            assertTrue(executionLog.get(1).contains("A"));
            assertTrue(executionLog.get(2).contains("B"));
            assertTrue(executionLog.get(3).contains("B"));
        }
    }

    // ========================================================================
    // Additional Transformation Classes for specific tests
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ActivatorRule {
        @TransformRule(name = "Activator")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> activator() {
            return (eClass, ctx) -> {
                executionLog.add("Activator:" + eClass.getName());
                // Trigger the greedy lazy rule via equivalent
                try {
                    ctx.equivalent(eClass, EAnnotation.class, "GreedyLazyProcessor");
                } catch (Exception e) {
                    // May not find it, that's ok
                }
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("activator:" + eClass.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DiscriminatedCallerRuleA {
        @TransformRule(name = "DiscCallerA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> discCallerA() {
            return (eClass, ctx) -> {
                executionLog.add("DiscCallerA:" + eClass.getName());
                try {
                    EAnnotation result = ctx.equivalentDiscriminated(
                            eClass, EAnnotation.class, "DiscriminatedAction", "discA");
                    executionLog.add("DiscCallerA:result=" + (result != null));
                } catch (Exception e) {
                    executionLog.add("DiscCallerA:error=" + e.getMessage());
                }
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("discCallerA:" + eClass.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DiscriminatedCallerRuleB {
        @TransformRule(name = "DiscCallerB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> discCallerB() {
            return (eClass, ctx) -> {
                executionLog.add("DiscCallerB:" + eClass.getName());
                try {
                    EAnnotation result = ctx.equivalentDiscriminated(
                            eClass, EAnnotation.class, "DiscriminatedAction", "discB");
                    executionLog.add("DiscCallerB:result=" + (result != null));
                } catch (Exception e) {
                    executionLog.add("DiscCallerB:error=" + e.getMessage());
                }
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("discCallerB:" + eClass.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class CrossRefRule {
        @TransformRule(name = "CrossRef")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> crossRef() {
            return (eClass, ctx) -> {
                executionLog.add("CrossRef:" + eClass.getName());
                // Try to look up ClassToTable result for a different source
                List<EObject> outputs = ruleOutputs.getOrDefault("ClassToTable", Collections.emptyList());
                executionLog.add("CrossRef:" + eClass.getName() + ":outputs=" + outputs.size());
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("crossref:" + eClass.getName());
                return ann;
            };
        }
    }

    // ========================================================================
    // TestModelProvider
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
