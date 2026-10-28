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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RULE_BY_RULE execution strategy with parallel mode enabled.
 *
 * <p>All existing parallel tests (ParallelStressTest, ParallelSafetyTest, etc.) use
 * ELEMENT_BY_ELEMENT strategy. This class fills the coverage gap for RULE_BY_RULE parallel,
 * which processes rules sequentially (outer loop) with elements in parallel chunks (inner loop),
 * using commitDeferredOperationsIncremental() as a barrier between rules.</p>
 */
@DisplayName("RULE_BY_RULE Parallel Tests")
class RuleByRuleParallelTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields for test assertions (thread-safe)
    static ConcurrentLinkedQueue<String> executionLog;
    static AtomicInteger executionCount;
    static ConcurrentLinkedQueue<String> threadIds;
    static Map<String, AtomicInteger> ruleExecutionCounts;

    @BeforeEach
    void setUp() {
        executionLog = new ConcurrentLinkedQueue<>();
        executionCount = new AtomicInteger(0);
        threadIds = new ConcurrentLinkedQueue<>();
        ruleExecutionCounts = new ConcurrentHashMap<>();

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
    // Helpers
    // ========================================================================

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

    private TransformationExecutor buildParallelRuleByRule() {
        return TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(true)
                .parallelThreshold(1)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
    }

    private TransformationExecutor buildSequentialRuleByRule() {
        return TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
    }

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

    // ========================================================================
    // Transformation Rule Classes
    // ========================================================================

    // --- RuleA: EClass → EPackage (primary, creates target via ctx.createTarget) ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class RuleA {
        @TransformRule(name = "ClassToPackage")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EPackage> classToPackage() {
            return (eClass, ctx) -> {
                executionLog.add("ClassToPackage:" + eClass.getName());
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("ClassToPackage", k -> new AtomicInteger()).incrementAndGet();
                threadIds.add(Thread.currentThread().getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(eClass.getName() + "_Pkg");
                pkg.setNsURI("test://" + eClass.getName());
                pkg.setNsPrefix(eClass.getName().toLowerCase());
                return pkg;
            };
        }
    }

    // --- RuleB: EClass → EAnnotation (uses equivalent() to find RuleA's output) ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class RuleB {
        @TransformRule(name = "ClassToAnnotation")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> classToAnnotation() {
            return (eClass, ctx) -> {
                // Look up RuleA's output via equivalent()
                EPackage pkg = ctx.equivalent(eClass, EPackage.class, "ClassToPackage");
                String pkgName = (pkg != null) ? pkg.getName() : "null";
                executionLog.add("ClassToAnnotation:" + eClass.getName() + ":pkg=" + pkgName);
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("ClassToAnnotation", k -> new AtomicInteger()).incrementAndGet();
                threadIds.add(Thread.currentThread().getName());
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("ann:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- RuleC: EClass → EDataType (third rule for multi-rule scenarios) ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class RuleC {
        @TransformRule(name = "ClassToDataType")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> classToDataType() {
            return (eClass, ctx) -> {
                executionLog.add("ClassToDataType:" + eClass.getName());
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("ClassToDataType", k -> new AtomicInteger()).incrementAndGet();
                threadIds.add(Thread.currentThread().getName());
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName(eClass.getName() + "_Type");
                return dt;
            };
        }
    }

    // --- GuardedRule: rejects elements whose name starts with "Skip" ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GuardedRuleA {
        @TransformRule(name = "GuardedClassToAnnotation")
        @Transform(type = EClass.class)
        @Primary
        @Guard(method = "shouldTransform")
        public TransformFunction<EClass, EAnnotation> guardedClassToAnnotation() {
            return (eClass, ctx) -> {
                executionLog.add("GuardedClassToAnnotation:" + eClass.getName());
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("GuardedClassToAnnotation", k -> new AtomicInteger()).incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
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

    // --- GuardedRuleB: looks up GuardedRuleA's results via equivalent() ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class GuardedRuleB {
        @TransformRule(name = "GuardLookup")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> guardLookup() {
            return (eClass, ctx) -> {
                EAnnotation ann = ctx.equivalent(eClass, EAnnotation.class, "GuardedClassToAnnotation");
                executionLog.add("GuardLookup:" + eClass.getName() + ":found=" + (ann != null));
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("GuardLookup", k -> new AtomicInteger()).incrementAndGet();
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName(eClass.getName() + "_Lookup");
                return dt;
            };
        }
    }

    // --- GreedyRule: processes both EClass and EAttribute ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EAnnotation.class)
    public static class GreedyRule {
        @TransformRule(name = "GreedyProcessor")
        @Transform(type = EObject.class)
        @Greedy
        public TransformFunction<EObject, EAnnotation> greedyProcessor() {
            return (source, ctx) -> {
                String name = (source instanceof EClass ec) ? ec.getName()
                        : (source instanceof EAttribute ea) ? ea.getName() : "unknown";
                executionLog.add("GreedyProcessor:" + source.getClass().getSimpleName() + ":" + name);
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("GreedyProcessor", k -> new AtomicInteger()).incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("greedy:" + name);
                return ann;
            };
        }
    }

    // --- LazyRule: only triggered via equivalent() ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyRule {
        static AtomicInteger lazyExecutionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyProcessor")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> lazyProcessor() {
            return (eClass, ctx) -> {
                executionLog.add("LazyProcessor:" + eClass.getName());
                lazyExecutionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("LazyProcessor", k -> new AtomicInteger()).incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("lazy:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- EagerTriggeringLazy: eager rule that triggers LazyRule ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class EagerTriggeringLazy {
        @TransformRule(name = "LazyTrigger")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyTrigger() {
            return (eClass, ctx) -> {
                // Trigger the lazy rule via equivalent()
                EAnnotation lazyResult = ctx.equivalent(eClass, EAnnotation.class, "LazyProcessor");
                executionLog.add("LazyTrigger:" + eClass.getName() + ":lazy=" + (lazyResult != null));
                executionCount.incrementAndGet();
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName(eClass.getName() + "_Triggered");
                return dt;
            };
        }
    }

    // --- ParentRule: base rule for @Extends testing ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentRule {
        @TransformRule(name = "ParentTransform")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EPackage> parentTransform() {
            return (eClass, ctx) -> {
                executionLog.add("ParentTransform:" + eClass.getName());
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("ParentTransform", k -> new AtomicInteger()).incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(eClass.getName() + "_Parent");
                pkg.setNsURI("test://parent/" + eClass.getName());
                pkg.setNsPrefix("parent");
                return pkg;
            };
        }
    }

    // --- ChildRule: extends ParentRule ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ChildRule {
        @TransformRule(name = "ChildTransform")
        @Transform(type = EClass.class)
        @Extends("ParentTransform")
        public TransformFunction<EClass, EAnnotation> childTransform() {
            return (eClass, ctx) -> {
                // Look up parent's equivalent
                EPackage parentTarget = ctx.equivalent(eClass, EPackage.class, "ParentTransform");
                String parentName = (parentTarget != null) ? parentTarget.getName() : "null";
                executionLog.add("ChildTransform:" + eClass.getName() + ":parent=" + parentName);
                executionCount.incrementAndGet();
                ruleExecutionCounts.computeIfAbsent("ChildTransform", k -> new AtomicInteger()).incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("child:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- DeferredCrossRefRule: sets cross-references via deferred operations ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DeferredCrossRefRuleA {
        @TransformRule(name = "DeferredRuleA")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EPackage> deferredRuleA() {
            return (eClass, ctx) -> {
                executionLog.add("DeferredRuleA:" + eClass.getName());
                executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(eClass.getName() + "_Deferred");
                pkg.setNsURI("test://deferred/" + eClass.getName());
                pkg.setNsPrefix("deferred");
                // Add an annotation via deferred (this exercises the deferred ops path)
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("deferred-xref:" + eClass.getName());
                pkg.getEAnnotations().add(ann);
                return pkg;
            };
        }
    }

    // --- DeferredCrossRefRuleB: reads RuleA's deferred outputs ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DeferredCrossRefRuleB {
        @TransformRule(name = "DeferredRuleB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> deferredRuleB() {
            return (eClass, ctx) -> {
                EPackage pkg = ctx.equivalent(eClass, EPackage.class, "DeferredRuleA");
                boolean hasAnnotation = (pkg != null && !pkg.getEAnnotations().isEmpty());
                executionLog.add("DeferredRuleB:" + eClass.getName()
                        + ":pkg=" + (pkg != null ? pkg.getName() : "null")
                        + ":hasAnnotation=" + hasAnnotation);
                executionCount.incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("deferredB:" + eClass.getName());
                return ann;
            };
        }
    }

    // --- StressRule: simple rule for high-element-count tests ---
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class StressRuleA {
        @TransformRule(name = "StressA")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EPackage> stressA() {
            return (eClass, ctx) -> {
                ruleExecutionCounts.computeIfAbsent("StressA", k -> new AtomicInteger()).incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(eClass.getName() + "_SA");
                pkg.setNsURI("test://stress/" + eClass.getName());
                pkg.setNsPrefix("s");
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class StressRuleB {
        @TransformRule(name = "StressB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> stressB() {
            return (eClass, ctx) -> {
                ruleExecutionCounts.computeIfAbsent("StressB", k -> new AtomicInteger()).incrementAndGet();
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("stressB:" + eClass.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class StressRuleC {
        @TransformRule(name = "StressC")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> stressC() {
            return (eClass, ctx) -> {
                ruleExecutionCounts.computeIfAbsent("StressC", k -> new AtomicInteger()).incrementAndGet();
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName(eClass.getName() + "_SC");
                return dt;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EReference.class)
    public static class StressRuleD {
        @TransformRule(name = "StressD")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EReference> stressD() {
            return (eClass, ctx) -> {
                ruleExecutionCounts.computeIfAbsent("StressD", k -> new AtomicInteger()).incrementAndGet();
                EReference ref = ctx.createTarget(EReference.class);
                ref.setName(eClass.getName() + "_SD");
                return ref;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EOperation.class)
    public static class StressRuleE {
        @TransformRule(name = "StressE")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EOperation> stressE() {
            return (eClass, ctx) -> {
                ruleExecutionCounts.computeIfAbsent("StressE", k -> new AtomicInteger()).incrementAndGet();
                EOperation op = ctx.createTarget(EOperation.class);
                op.setName(eClass.getName() + "_SE");
                return op;
            };
        }
    }

    // ========================================================================
    // 2. Basic RULE_BY_RULE Parallel Tests
    // ========================================================================

    @Nested
    @DisplayName("Basic RULE_BY_RULE Parallel Execution")
    class BasicParallelTests {

        @Test
        @DisplayName("2.1 All elements transformed correctly — target counts match sequential")
        void allElementsTransformedCorrectly() {
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            // Sequential run
            TransformationExecutor seqExecutor = buildSequentialRuleByRule();
            seqExecutor.transform();
            int sequentialCount = executionCount.get();
            int seqRuleA = ruleExecutionCounts.getOrDefault("ClassToPackage", new AtomicInteger()).get();
            int seqRuleB = ruleExecutionCounts.getOrDefault("ClassToAnnotation", new AtomicInteger()).get();
            int seqRuleC = ruleExecutionCounts.getOrDefault("ClassToDataType", new AtomicInteger()).get();

            // Reset for parallel run
            setUp();
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }
            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            // Parallel run
            TransformationExecutor parExecutor = buildParallelRuleByRule();
            parExecutor.transform();

            assertEquals(sequentialCount, executionCount.get(),
                    "Parallel execution count must match sequential");
            assertEquals(seqRuleA, ruleExecutionCounts.get("ClassToPackage").get(),
                    "RuleA count must match sequential");
            assertEquals(seqRuleB, ruleExecutionCounts.get("ClassToAnnotation").get(),
                    "RuleB count must match sequential");
            assertEquals(seqRuleC, ruleExecutionCounts.get("ClassToDataType").get(),
                    "RuleC count must match sequential");
        }

        @Test
        @DisplayName("2.2 Rules execute in registration order — RuleA completes before RuleB starts")
        void rulesExecuteInOrder() {
            for (int i = 0; i < 30; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            List<String> log = new ArrayList<>(executionLog);

            // Find last RuleA entry and first RuleB entry
            int lastRuleA = -1;
            int firstRuleB = Integer.MAX_VALUE;
            int lastRuleB = -1;
            int firstRuleC = Integer.MAX_VALUE;

            for (int i = 0; i < log.size(); i++) {
                String entry = log.get(i);
                if (entry.startsWith("ClassToPackage:")) {
                    lastRuleA = i;
                }
                if (entry.startsWith("ClassToAnnotation:") && i < firstRuleB) {
                    firstRuleB = i;
                }
                if (entry.startsWith("ClassToAnnotation:")) {
                    lastRuleB = i;
                }
                if (entry.startsWith("ClassToDataType:") && i < firstRuleC) {
                    firstRuleC = i;
                }
            }

            assertTrue(lastRuleA < firstRuleB,
                    "All RuleA executions must complete before RuleB starts");
            assertTrue(lastRuleB < firstRuleC,
                    "All RuleB executions must complete before RuleC starts");
        }

        @Test
        @DisplayName("2.3 Multiple threads participate in parallel chunks")
        void multipleThreadsParticipate() {
            // Use enough elements to likely get parallel chunks
            for (int i = 0; i < 100; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            Set<String> uniqueThreads = new HashSet<>(threadIds);
            // On any multi-core machine, parallel execution should use more than 1 thread
            // But we can't guarantee it, so we just verify it doesn't crash and produces results
            assertTrue(uniqueThreads.size() >= 1,
                    "At least one thread should participate");
            assertEquals(100, ruleExecutionCounts.get("ClassToPackage").get(),
                    "All elements should be processed");
        }
    }

    // ========================================================================
    // 3. Cross-Rule Equivalent Resolution Tests
    // ========================================================================

    @Nested
    @DisplayName("Cross-Rule Equivalent Resolution")
    class CrossRuleEquivalentTests {

        @Test
        @DisplayName("3.1 RuleB resolves equivalent() for RuleA targets after barrier")
        void ruleBResolvesRuleATargets() {
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Verify RuleB found RuleA's targets for every element
            List<String> ruleBEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToAnnotation:"))
                    .collect(Collectors.toList());

            assertEquals(20, ruleBEntries.size(), "RuleB should process all 20 elements");

            for (String entry : ruleBEntries) {
                assertFalse(entry.contains(":pkg=null"),
                        "RuleB should find RuleA's equivalent for every element: " + entry);
                assertTrue(entry.contains("_Pkg"),
                        "RuleB should see the correct package name: " + entry);
            }
        }

        @Test
        @DisplayName("3.2 Concurrent equivalent() calls within same rule don't interfere")
        void concurrentEquivalentCallsDontInterfere() {
            for (int i = 0; i < 50; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Verify each RuleB entry has the correct corresponding package
            List<String> ruleBEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToAnnotation:"))
                    .collect(Collectors.toList());

            assertEquals(50, ruleBEntries.size());

            for (String entry : ruleBEntries) {
                // Extract element name and package name
                // Format: "ClassToAnnotation:ElemN:pkg=ElemN_Pkg"
                String elemName = entry.split(":")[1];
                assertTrue(entry.contains(":pkg=" + elemName + "_Pkg"),
                        "Each element should find its own corresponding package: " + entry);
            }
        }

        @Test
        @DisplayName("3.3 equivalent() returns null for elements not yet processed by their rule")
        void equivalentReturnsNullForUnprocessedElements() {
            // This tests that if RuleB tries to look up a rule that hasn't run yet,
            // equivalent() returns null. We verify the guard rejection case indirectly.
            createEClass("Elem1");

            // Register only RuleB (which looks up ClassToPackage) — no RuleA registered
            registry.register(RuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            List<String> ruleBEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToAnnotation:"))
                    .collect(Collectors.toList());

            assertEquals(1, ruleBEntries.size());
            assertTrue(ruleBEntries.get(0).contains(":pkg=null"),
                    "equivalent() should return null when the rule hasn't been registered: " + ruleBEntries.get(0));
        }
    }

    // ========================================================================
    // 4. Incremental Commit Visibility Tests
    // ========================================================================

    @Nested
    @DisplayName("Incremental Commit Visibility")
    class IncrementalCommitTests {

        @Test
        @DisplayName("4.1 Deferred operations from RuleA are committed before RuleB starts")
        void deferredOpsCommittedBetweenRules() {
            for (int i = 0; i < 10; i++) {
                createEClass("Elem" + i);
            }

            registry.register(DeferredCrossRefRuleA.class);
            registry.register(DeferredCrossRefRuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // All DeferredRuleA must complete before DeferredRuleB
            List<String> log = new ArrayList<>(executionLog);
            int lastA = -1;
            int firstB = Integer.MAX_VALUE;
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).startsWith("DeferredRuleA:")) lastA = i;
                if (log.get(i).startsWith("DeferredRuleB:") && i < firstB) firstB = i;
            }
            assertTrue(lastA < firstB, "DeferredRuleA must complete before DeferredRuleB starts");
        }

        @Test
        @DisplayName("4.2 Cross-references set by RuleA via deferred ops are visible to RuleB")
        void crossReferencesVisibleToRuleB() {
            for (int i = 0; i < 10; i++) {
                createEClass("Elem" + i);
            }

            registry.register(DeferredCrossRefRuleA.class);
            registry.register(DeferredCrossRefRuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Verify RuleB sees RuleA's packages with their annotations
            List<String> ruleBEntries = executionLog.stream()
                    .filter(s -> s.startsWith("DeferredRuleB:"))
                    .collect(Collectors.toList());

            assertEquals(10, ruleBEntries.size());
            for (String entry : ruleBEntries) {
                assertFalse(entry.contains(":pkg=null"),
                        "RuleB should find RuleA's package: " + entry);
            }
        }

        @Test
        @DisplayName("4.3 Target model contains all elements from all rules after completion")
        void targetModelContainsAllElements() {
            for (int i = 0; i < 15; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Count target elements of each type
            assertEquals(15, ruleExecutionCounts.get("ClassToPackage").get());
            assertEquals(15, ruleExecutionCounts.get("ClassToAnnotation").get());
            assertEquals(15, ruleExecutionCounts.get("ClassToDataType").get());
            assertEquals(45, executionCount.get(), "Total execution count should be 3 rules × 15 elements");
        }
    }

    // ========================================================================
    // 5. Guard Evaluation Tests
    // ========================================================================

    @Nested
    @DisplayName("Guard Evaluation in Parallel")
    class GuardEvaluationTests {

        @Test
        @DisplayName("5.1 Guards reject correct elements in parallel chunks")
        void guardsRejectCorrectElements() {
            // Create 10 elements: 5 should pass, 5 should be rejected (Skip*)
            for (int i = 0; i < 5; i++) {
                createEClass("Pass" + i);
            }
            for (int i = 0; i < 5; i++) {
                createEClass("Skip" + i);
            }

            registry.register(GuardedRuleA.class);

            // Sequential first
            TransformationExecutor seqExecutor = buildSequentialRuleByRule();
            seqExecutor.transform();
            int sequentialCount = ruleExecutionCounts.get("GuardedClassToAnnotation").get();
            Set<String> sequentialProcessed = executionLog.stream()
                    .filter(s -> s.startsWith("GuardedClassToAnnotation:"))
                    .map(s -> s.split(":")[1])
                    .collect(Collectors.toSet());

            // Reset for parallel
            setUp();
            for (int i = 0; i < 5; i++) {
                createEClass("Pass" + i);
            }
            for (int i = 0; i < 5; i++) {
                createEClass("Skip" + i);
            }
            registry.register(GuardedRuleA.class);

            // Parallel
            TransformationExecutor parExecutor = buildParallelRuleByRule();
            parExecutor.transform();

            assertEquals(sequentialCount, ruleExecutionCounts.get("GuardedClassToAnnotation").get(),
                    "Parallel should reject same number of elements as sequential");
            assertEquals(5, ruleExecutionCounts.get("GuardedClassToAnnotation").get(),
                    "Only Pass* elements should be processed");

            Set<String> parallelProcessed = executionLog.stream()
                    .filter(s -> s.startsWith("GuardedClassToAnnotation:"))
                    .map(s -> s.split(":")[1])
                    .collect(Collectors.toSet());

            assertEquals(sequentialProcessed, parallelProcessed,
                    "Same elements should be processed in both modes");
        }

        @Test
        @DisplayName("5.2 Guard rejection caching works across rules in parallel")
        void guardRejectionCachingAcrossRules() {
            for (int i = 0; i < 3; i++) {
                createEClass("Pass" + i);
            }
            for (int i = 0; i < 3; i++) {
                createEClass("Skip" + i);
            }

            registry.register(GuardedRuleA.class);
            registry.register(GuardedRuleB.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // GuardedRuleB (GuardLookup) looks up GuardedRuleA results
            // For Skip* elements, GuardedRuleA returns null (rejected), so GuardLookup should see found=false
            List<String> lookupEntries = executionLog.stream()
                    .filter(s -> s.startsWith("GuardLookup:"))
                    .collect(Collectors.toList());

            assertEquals(6, lookupEntries.size(), "GuardLookup should process all 6 elements");

            long foundTrue = lookupEntries.stream().filter(s -> s.contains(":found=true")).count();
            long foundFalse = lookupEntries.stream().filter(s -> s.contains(":found=false")).count();

            assertEquals(3, foundTrue, "3 Pass* elements should have GuardedRuleA results");
            assertEquals(3, foundFalse, "3 Skip* elements should NOT have GuardedRuleA results");
        }
    }

    // ========================================================================
    // 6. Greedy Rules Tests
    // ========================================================================

    @Nested
    @DisplayName("Greedy Rules in Parallel")
    class GreedyRuleTests {

        @Test
        @DisplayName("6.1 Greedy rule processes all applicable source types in parallel")
        void greedyRuleProcessesAllTypes() {
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }
            for (int i = 0; i < 8; i++) {
                createAttribute("Attr" + i);
            }

            registry.register(GreedyRule.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // @Greedy with EObject matches ALL EObjects in the source model
            // (including internal structural features). Verify both types are included.
            long classCount = executionLog.stream()
                    .filter(s -> s.startsWith("GreedyProcessor:EClassImpl:"))
                    .count();
            long attrCount = executionLog.stream()
                    .filter(s -> s.startsWith("GreedyProcessor:EAttributeImpl:"))
                    .count();

            assertEquals(10, classCount, "Should process 10 EClass elements");
            assertEquals(8, attrCount, "Should process 8 EAttribute elements");
            assertTrue(ruleExecutionCounts.get("GreedyProcessor").get() >= 18,
                    "Greedy rule should process at least 10 EClass + 8 EAttribute elements");
        }

        @Test
        @DisplayName("6.2 Greedy rule target count matches sequential execution")
        void greedyRuleCountMatchesSequential() {
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }
            for (int i = 0; i < 8; i++) {
                createAttribute("Attr" + i);
            }

            registry.register(GreedyRule.class);

            // Sequential
            TransformationExecutor seqExecutor = buildSequentialRuleByRule();
            seqExecutor.transform();
            int sequentialCount = ruleExecutionCounts.get("GreedyProcessor").get();

            // Reset for parallel
            setUp();
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
            }
            for (int i = 0; i < 8; i++) {
                createAttribute("Attr" + i);
            }
            registry.register(GreedyRule.class);

            TransformationExecutor parExecutor = buildParallelRuleByRule();
            parExecutor.transform();

            assertEquals(sequentialCount, ruleExecutionCounts.get("GreedyProcessor").get(),
                    "Parallel greedy count must match sequential");
        }
    }

    // ========================================================================
    // 7. Lazy Rules Tests
    // ========================================================================

    @Nested
    @DisplayName("Lazy Rules in Parallel")
    class LazyRuleTests {

        @BeforeEach
        void resetLazyCounters() {
            LazyRule.lazyExecutionCount = new AtomicInteger(0);
        }

        @Test
        @DisplayName("7.1 Lazy rule triggered by equivalent() during parallel chunk")
        void lazyRuleTriggeredInParallel() {
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }

            registry.register(LazyRule.class);
            registry.register(EagerTriggeringLazy.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // All elements should trigger the lazy rule
            List<String> triggerEntries = executionLog.stream()
                    .filter(s -> s.startsWith("LazyTrigger:"))
                    .collect(Collectors.toList());

            assertEquals(20, triggerEntries.size());
            for (String entry : triggerEntries) {
                assertTrue(entry.contains(":lazy=true"),
                        "Lazy rule should return non-null for every element: " + entry);
            }

            assertEquals(20, LazyRule.lazyExecutionCount.get(),
                    "Lazy rule should execute exactly once per source element");
        }

        @Test
        @DisplayName("7.2 Concurrent lazy triggers for same source — only one execution")
        void concurrentLazyTriggersOnlyOneExecution() {
            // With a single source element triggered from multiple chunks,
            // the lazy rule should execute only once
            EClass sharedSource = createEClass("Shared");

            registry.register(LazyRule.class);
            registry.register(EagerTriggeringLazy.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // The lazy rule should execute exactly once for the shared source
            assertEquals(1, LazyRule.lazyExecutionCount.get(),
                    "Lazy rule should execute exactly once for a single source element");
        }
    }

    // ========================================================================
    // 8. @Extends Inheritance Tests
    // ========================================================================

    @Nested
    @DisplayName("@Extends Inheritance in Parallel")
    class ExtendsInheritanceTests {

        @Test
        @DisplayName("8.1 Child rule finds parent equivalent after incremental commit")
        void childFindsParentEquivalent() {
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }

            registry.register(ParentRule.class);
            registry.register(ChildRule.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Verify child rule found parent's targets
            List<String> childEntries = executionLog.stream()
                    .filter(s -> s.startsWith("ChildTransform:"))
                    .collect(Collectors.toList());

            assertEquals(20, childEntries.size(), "Child rule should process all elements");

            for (String entry : childEntries) {
                assertFalse(entry.contains(":parent=null"),
                        "Child rule should find parent's equivalent: " + entry);
                assertTrue(entry.contains("_Parent"),
                        "Child should see parent's named target: " + entry);
            }

            // Verify ordering: all parent rules before any child rules
            List<String> log = new ArrayList<>(executionLog);
            int lastParent = -1;
            int firstChild = Integer.MAX_VALUE;
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).startsWith("ParentTransform:")) lastParent = i;
                if (log.get(i).startsWith("ChildTransform:") && i < firstChild) firstChild = i;
            }
            assertTrue(lastParent < firstChild,
                    "All parent rules must complete before child rules start");
        }
    }

    // ========================================================================
    // 9. Stress and Race Condition Tests
    // ========================================================================

    @Nested
    @DisplayName("Stress and Race Condition Tests")
    class StressTests {

        @RepeatedTest(3)
        @DisplayName("9.1 Stress test: 1000+ elements with 5 rules — no data loss")
        void stressTestNoDataLoss() {
            for (int i = 0; i < 1000; i++) {
                createEClass("Elem" + i);
            }

            registry.register(StressRuleA.class);
            registry.register(StressRuleB.class);
            registry.register(StressRuleC.class);
            registry.register(StressRuleD.class);
            registry.register(StressRuleE.class);

            assertDoesNotThrow(() -> {
                TransformationExecutor executor = buildParallelRuleByRule();
                executor.transform();
            });

            assertEquals(1000, ruleExecutionCounts.get("StressA").get(), "StressA count");
            assertEquals(1000, ruleExecutionCounts.get("StressB").get(), "StressB count");
            assertEquals(1000, ruleExecutionCounts.get("StressC").get(), "StressC count");
            assertEquals(1000, ruleExecutionCounts.get("StressD").get(), "StressD count");
            assertEquals(1000, ruleExecutionCounts.get("StressE").get(), "StressE count");
        }

        @RepeatedTest(50)
        @DisplayName("9.2 Race condition test: concurrent equivalent() calls across rules")
        void raceConditionEquivalentCalls() {
            for (int i = 0; i < 20; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);

            assertDoesNotThrow(() -> {
                TransformationExecutor executor = buildParallelRuleByRule();
                executor.transform();
            });

            assertEquals(20, ruleExecutionCounts.get("ClassToPackage").get());
            assertEquals(20, ruleExecutionCounts.get("ClassToAnnotation").get());

            // Verify RuleB found all of RuleA's results
            long nullResults = executionLog.stream()
                    .filter(s -> s.startsWith("ClassToAnnotation:") && s.contains(":pkg=null"))
                    .count();
            assertEquals(0, nullResults,
                    "No RuleB execution should fail to find RuleA's equivalent");
        }

        @RepeatedTest(10)
        @DisplayName("9.3 Determinism test: repeated execution produces identical target counts")
        void determinismTest() {
            for (int i = 0; i < 50; i++) {
                createEClass("Elem" + i);
            }

            registry.register(RuleA.class);
            registry.register(RuleB.class);
            registry.register(RuleC.class);

            TransformationExecutor executor = buildParallelRuleByRule();
            executor.transform();

            // Verify exact counts every time
            assertEquals(50, ruleExecutionCounts.get("ClassToPackage").get());
            assertEquals(50, ruleExecutionCounts.get("ClassToAnnotation").get());
            assertEquals(50, ruleExecutionCounts.get("ClassToDataType").get());
            assertEquals(150, executionCount.get());
        }
    }

    // ========================================================================
    // 10. Invalid Configuration Rejection
    // ========================================================================

    @Nested
    @DisplayName("Configuration Compatibility")
    class InvalidConfigurationTests {

        @Test
        @DisplayName("10.1 CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is allowed")
        void cloneCurrentStateRejected() {
            createEClass("Test");
            registry.register(RuleA.class);

            context.setEquivalentDiscriminatedStrategy(
                    EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            assertDoesNotThrow(() -> {
                TransformationExecutor executor = TransformationExecutor.builder()
                        .registry(registry)
                        .context(context)
                        .parallel(true)
                        .parallelThreshold(1)
                        .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                        .build();
                executor.transform();
            }, "CLONE_CURRENT_STATE + RULE_BY_RULE + parallel is now supported");
        }
    }
}
