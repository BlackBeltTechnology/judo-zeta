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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying ETL-compatible semantics in the Zeta transformation framework.
 * 
 * <p>These tests ensure that Zeta behaves identically to Epsilon ETL for:</p>
 * <ul>
 *   <li>Type matching (greedy vs non-greedy)</li>
 *   <li>Rule execution order (deterministic)</li>
 *   <li>Parent rule idempotency (@Extends)</li>
 *   <li>Multiple rules for same source</li>
 * </ul>
 */
@DisplayName("ETL Semantics Compatibility Tests")
class ETLSemanticsTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields
    static AtomicInteger executionCount = new AtomicInteger(0);
    static List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
    static Set<String> transformedNames = Collections.synchronizedSet(new HashSet<>());
    static List<EObject> capturedInstances = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        // Reset tracking
        executionCount.set(0);
        executionLog.clear();
        transformedNames.clear();
        capturedInstances.clear();

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

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    private EDataType createEDataType(String name) {
        EDataType dataType = EcoreFactory.eINSTANCE.createEDataType();
        dataType.setName(name);
        sourceResource.getContents().add(dataType);
        return dataType;
    }

    // ==================== Type Matching Tests ====================

    @Nested
    @DisplayName("Non-Greedy Type Matching")
    class NonGreedyTypeMatchingTests {

        /**
         * ETL Semantics: Non-greedy rules match ONLY the exact declared type.
         * A rule declared for EClass should NOT match EDataType.
         */
        @Test
        @DisplayName("Non-greedy rule matches only exact type, not subtypes")
        void nonGreedyRuleMatchesExactTypeOnly() {
            // Create an EClass and an EDataType (both are EClassifiers)
            createEClass("TestClass");
            createEDataType("TestDataType");

            registry.register(NonGreedyEClassTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Should only transform EClass, not EDataType
            assertEquals(1, executionCount.get(),
                    "Non-greedy rule should execute only for exact type match (EClass)");
            assertTrue(transformedNames.contains("TestClass"));
            assertFalse(transformedNames.contains("TestDataType"));
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class NonGreedyEClassTransformation {
        @TransformRule(name = "EClassToPackage")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eClassToPackage() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                transformedNames.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    @Nested
    @DisplayName("Greedy Type Matching")
    class GreedyTypeMatchingTests {

        /**
         * ETL Semantics: Greedy rules match the declared type AND all subtypes.
         */
        @Test
        @DisplayName("Greedy rule matches declared type and all subtypes")
        void greedyRuleMatchesSubtypes() {
            // EClassifier is supertype of both EClass and EDataType
            createEClass("TestClass");
            createEDataType("TestDataType");

            registry.register(GreedyClassifierTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Greedy rule should match both EClass and EDataType
            assertEquals(2, executionCount.get(),
                    "Greedy rule should execute for all subtypes of EClassifier");
            assertTrue(transformedNames.contains("TestClass"));
            assertTrue(transformedNames.contains("TestDataType"));
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClassifier.class, target = EPackage.class)
    public static class GreedyClassifierTransformation {
        @TransformRule(name = "ClassifierToPackage")
        @Transform(type = EClassifier.class)
        @Greedy
        public TransformFunction<EClassifier, EPackage> classifierToPackage() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                transformedNames.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    // ==================== Rule Execution Order Tests ====================

    @Nested
    @DisplayName("Deterministic Rule Execution Order")
    class RuleExecutionOrderTests {

        /**
         * ETL Semantics: Rules execute in deterministic order across multiple runs.
         */
        @Test
        @DisplayName("Rules execute in deterministic order across multiple runs")
        void rulesExecuteInDeterministicOrder() {
            List<String> run1Order = executeAndGetOrder();
            List<String> run2Order = executeAndGetOrder();
            List<String> run3Order = executeAndGetOrder();

            assertEquals(run1Order, run2Order, "Execution order should be identical across runs");
            assertEquals(run2Order, run3Order, "Execution order should be identical across runs");
            assertFalse(run1Order.isEmpty(), "Should have executed some transformations");
        }

        private List<String> executeAndGetOrder() {
            ResourceSet src = new ResourceSetImpl();
            ResourceSet tgt = new ResourceSetImpl();
            Resource srcRes = src.createResource(URI.createURI("test://source.xmi"));
            tgt.createResource(URI.createURI("test://target.xmi"));

            for (int i = 0; i < 5; i++) {
                EClass eClass = EcoreFactory.eINSTANCE.createEClass();
                eClass.setName("Class" + i);
                srcRes.getContents().add(eClass);
            }

            List<String> order = Collections.synchronizedList(new ArrayList<>());

            TransformationRegistry reg = new TransformationRegistry();
            reg.register(OrderTrackingTransformation.class);

            ExtensionMethodRegistry extReg = new ExtensionMethodRegistry();
            TransformationContext ctx = new TransformationContext(
                    new TestModelProvider(), src, tgt, extReg);
            ctx.setTargetPackage(EcorePackage.eINSTANCE);
            ctx.setTransformationRegistry(reg);
            ctx.registerResource("source", src);

            // Use static field to capture order
            OrderTrackingTransformation.orderCapture = order;

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(reg)
                    .context(ctx)
                    .parallel(false)
                    .build();

            executor.transform();

            return new ArrayList<>(order);
        }

        /**
         * ETL Semantics: Multiple rules for same source all execute.
         * Note: Order is not guaranteed as Java reflection doesn't guarantee method ordering.
         */
        @Test
        @DisplayName("Multiple rules for same source all execute")
        void multipleRulesForSameSourceAllExecute() {
            createEClass("TestEntity");

            registry.register(MultiRuleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // All three rules should execute (order not guaranteed by Java reflection)
            assertEquals(3, executionLog.size(), "All matching rules should execute");
            assertTrue(executionLog.containsAll(Arrays.asList("RuleA", "RuleB", "RuleC")),
                    "All rules should execute");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class OrderTrackingTransformation {
        static List<String> orderCapture;

        @TransformRule(name = "TrackOrder")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> trackOrder() {
            return (source, ctx) -> {
                if (orderCapture != null) {
                    orderCapture.add(source.getName());
                }
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiRuleTransformation {
        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                executionLog.add("RuleA");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("A_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> ruleB() {
            return (source, ctx) -> {
                executionLog.add("RuleB");
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("B_" + source.getName());
                return ann;
            };
        }

        @TransformRule(name = "RuleC")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAttribute> ruleC() {
            return (source, ctx) -> {
                executionLog.add("RuleC");
                EAttribute attr = ctx.createTarget(EAttribute.class);
                attr.setName("C_" + source.getName());
                return attr;
            };
        }
    }

    // ==================== Parent Rule Idempotency Tests ====================

    @Nested
    @DisplayName("Parent Rule Idempotency (@Extends)")
    class ParentRuleIdempotencyTests {

        /**
         * ETL Semantics: Parent rule executes only once when multiple children extend it.
         */
        @Test
        @DisplayName("Parent rule executes only once when multiple children extend it")
        void parentRuleExecutesOnlyOnce() {
            createEClass("Entity");

            registry.register(ParentChildTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Parent should execute only once
            long parentCount = executionLog.stream().filter(s -> s.equals("Parent")).count();
            long childACount = executionLog.stream().filter(s -> s.equals("ChildA")).count();
            long childBCount = executionLog.stream().filter(s -> s.equals("ChildB")).count();

            assertEquals(1, parentCount, "Parent rule should execute only once (idempotent)");
            assertEquals(1, childACount, "ChildA should execute once");
            assertEquals(1, childBCount, "ChildB should execute once");
        }

        /**
         * ETL Semantics: executeParentRule returns same instance on repeated calls.
         */
        @Test
        @DisplayName("executeParentRule returns same instance on repeated calls")
        void executeParentRuleReturnsSameInstance() {
            createEClass("Entity");

            registry.register(SameInstanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Both children should receive the SAME parent target instance
            assertEquals(2, capturedInstances.size(), "Two instances should be captured");
            assertSame(capturedInstances.get(0), capturedInstances.get(1),
                    "Both children should receive the same parent target instance");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentChildTransformation {
        @TransformRule(name = "ParentRule")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                executionLog.add("Parent");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ChildA")
        @Transform(type = EClass.class)
        @Extends("ParentRule")
        public TransformFunction<EClass, EPackage> childA() {
            return (source, ctx) -> {
                executionLog.add("ChildA");
                EPackage pkg = ctx.executeParentRule("ParentRule", source);
                pkg.setNsPrefix("A_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ChildB")
        @Transform(type = EClass.class)
        @Extends("ParentRule")
        public TransformFunction<EClass, EAnnotation> childB() {
            return (source, ctx) -> {
                executionLog.add("ChildB");
                // Call parent rule - should return cached result
                EPackage pkg = ctx.executeParentRule("ParentRule", source);
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource(pkg.getName() + "_annotation");
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SameInstanceTransformation {
        @TransformRule(name = "Parent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> parent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "Child1")
        @Transform(type = EClass.class)
        @Extends("Parent")
        public TransformFunction<EClass, EPackage> child1() {
            return (source, ctx) -> {
                EPackage pkg = ctx.executeParentRule("Parent", source);
                capturedInstances.add(pkg);
                return pkg;
            };
        }

        @TransformRule(name = "Child2")
        @Transform(type = EClass.class)
        @Extends("Parent")
        public TransformFunction<EClass, EAnnotation> child2() {
            return (source, ctx) -> {
                EPackage pkg = ctx.executeParentRule("Parent", source);
                capturedInstances.add(pkg);
                return ctx.createTarget(EAnnotation.class);
            };
        }
    }

    // ==================== Guard Failure Tests ====================

    @Nested
    @DisplayName("Guard Failure Handling")
    class GuardFailureTests {

        /**
         * ETL Semantics: When a guard fails, equivalent() returns null without NPE.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) returns null when guard fails")
        void equivalentByRuleNameReturnsNullWhenGuardFails() {
            EClass testClass = createEClass("NonAbstractClass");
            testClass.setAbstract(false); // Guard will fail

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            // Should return null without throwing NPE
            EPackage result = assertDoesNotThrow(() ->
                context.equivalent(testClass, "AbstractOnly"));

            assertNull(result, "Should return null when guard fails");
            assertEquals(0, executionCount.get(), "Rule should not execute when guard fails");
        }

        /**
         * ETL Semantics: When a guard fails, equivalentDiscriminated() returns null without NPE.
         */
        @Test
        @DisplayName("equivalentDiscriminated returns null when guard fails")
        void equivalentDiscriminatedReturnsNullWhenGuardFails() {
            EClass testClass = createEClass("NonAbstractClass");
            testClass.setAbstract(false); // Guard will fail

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            // Should return null without throwing NPE
            EPackage result = assertDoesNotThrow(() ->
                context.equivalentDiscriminated(testClass, EPackage.class, "AbstractOnly", "testDisc"));

            assertNull(result, "Should return null when guard fails");
            assertEquals(0, executionCount.get(), "Rule should not execute when guard fails");
        }

        /**
         * ETL Semantics: Guard passes, rule executes successfully.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) executes rule when guard passes")
        void equivalentByRuleNameExecutesWhenGuardPasses() {
            EClass testClass = createEClass("AbstractClass");
            testClass.setAbstract(true); // Guard will pass

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            EPackage result = context.equivalent(testClass, "AbstractOnly");

            assertNotNull(result, "Should return result when guard passes");
            assertEquals("AbstractClass", result.getName());
            assertEquals(1, executionCount.get(), "Rule should execute when guard passes");
        }

        /**
         * ETL Semantics: equivalentDiscriminated with passing guard creates discriminated clone.
         */
        @Test
        @DisplayName("equivalentDiscriminated creates clone when guard passes")
        void equivalentDiscriminatedCreatesCloneWhenGuardPasses() {
            EClass testClass = createEClass("AbstractClass");
            testClass.setAbstract(true); // Guard will pass

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            EPackage result1 = context.equivalentDiscriminated(testClass, EPackage.class, "AbstractOnly", "disc1");
            EPackage result2 = context.equivalentDiscriminated(testClass, EPackage.class, "AbstractOnly", "disc2");

            assertNotNull(result1);
            assertNotNull(result2);
            assertNotSame(result1, result2, "Discriminated equivalents should be different instances");
            assertEquals(1, executionCount.get(), "Rule should execute only once (cached)");
        }

        /**
         * Verify no NPE when calling equivalentDiscriminated with unknown rule name.
         */
        @Test
        @DisplayName("equivalentDiscriminated returns null for unknown rule without NPE")
        void equivalentDiscriminatedReturnsNullForUnknownRule() {
            EClass testClass = createEClass("TestClass");

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            EPackage result = assertDoesNotThrow(() ->
                context.equivalentDiscriminated(testClass, EPackage.class, "NonExistentRule", "disc"));

            assertNull(result, "Should return null for unknown rule");
        }

        /**
         * Verify no NPE when calling equivalent(source, ruleName) with unknown rule name.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) returns null for unknown rule without NPE")
        void equivalentByRuleNameReturnsNullForUnknownRule() {
            EClass testClass = createEClass("TestClass");

            registry.register(GuardedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            EPackage result = assertDoesNotThrow(() ->
                context.equivalent(testClass, "NonExistentRule"));

            assertNull(result, "Should return null for unknown rule");
        }

        /**
         * Verify no StackOverflowError when rule calls equivalentDiscriminated recursively.
         */
        @Test
        @DisplayName("equivalentDiscriminated prevents infinite recursion")
        void equivalentDiscriminatedPreventsInfiniteRecursion() {
            EClass testClass = createEClass("RecursiveClass");

            registry.register(RecursiveTransformation.class);
            context.setTransformationRegistry(registry);

            // Should not throw StackOverflowError
            EPackage result = assertDoesNotThrow(() ->
                context.equivalentDiscriminated(testClass, EPackage.class, "RecursiveRule", "disc1"));

            // Result should be non-null (rule executed once)
            assertNotNull(result, "Should return result despite recursive call");
            assertEquals(1, executionCount.get(), "Rule should execute only once");
        }

        /**
         * Verify no StackOverflowError when rule calls equivalent(source, ruleName) recursively.
         */
        @Test
        @DisplayName("equivalent(source, ruleName) prevents infinite recursion")
        void equivalentByRuleNamePreventsInfiniteRecursion() {
            EClass testClass = createEClass("RecursiveClass");

            registry.register(RecursiveEquivalentTransformation.class);
            context.setTransformationRegistry(registry);

            // Should not throw StackOverflowError
            EPackage result = assertDoesNotThrow(() ->
                context.equivalent(testClass, "RecursiveEquivalentRule"));

            // Result should be non-null (rule executed once)
            assertNotNull(result, "Should return result despite recursive call");
            assertEquals(1, executionCount.get(), "Rule should execute only once");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class RecursiveTransformation {
        @TransformRule(name = "RecursiveRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> recursiveRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                // This would cause infinite recursion without protection
                EPackage recursive = ctx.equivalentDiscriminated(source, EPackage.class, "RecursiveRule", "nested");

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class RecursiveEquivalentTransformation {
        @TransformRule(name = "RecursiveEquivalentRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> recursiveEquivalentRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                // This would cause infinite recursion without protection
                EPackage recursive = ctx.equivalent(source, "RecursiveEquivalentRule");

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class GuardedLazyTransformation {
        @TransformRule(name = "AbstractOnly")
        @Transform(type = EClass.class)
        @Lazy
        @Guard(method = "isAbstract")
        public TransformFunction<EClass, EPackage> abstractOnly() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }

        public boolean isAbstract(EObject element, TransformationContext ctx) {
            return element instanceof EClass && ((EClass) element).isAbstract();
        }
    }

    // ==================== Multiple Rules Same Source Tests ====================

    @Nested
    @DisplayName("Multiple Rules for Same Source")
    class MultipleRulesSameSourceTests {

        /**
         * ETL Semantics: Multiple rules with different target types all execute.
         */
        @Test
        @DisplayName("Multiple rules with different target types all execute")
        void multipleRulesWithDifferentTargetsAllExecute() {
            createEClass("Customer");

            registry.register(MultiTargetTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // All three rules should have executed
            assertTrue(transformedNames.contains("TABLE"), "Entity2Table should execute");
            assertTrue(transformedNames.contains("AUDIT"), "Entity2Audit should execute");
            assertTrue(transformedNames.contains("INDEX"), "Entity2Index should execute");
            assertEquals(3, transformedNames.size(), "Three rules should execute");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiTargetTransformation {
        @TransformRule(name = "Entity2Table")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> entity2Table() {
            return (source, ctx) -> {
                transformedNames.add("TABLE");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_TABLE");
                return pkg;
            };
        }

        @TransformRule(name = "Entity2Audit")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> entity2Audit() {
            return (source, ctx) -> {
                transformedNames.add("AUDIT");
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource(source.getName() + "_AUDIT");
                return ann;
            };
        }

        @TransformRule(name = "Entity2Index")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAttribute> entity2Index() {
            return (source, ctx) -> {
                transformedNames.add("INDEX");
                EAttribute attr = ctx.createTarget(EAttribute.class);
                attr.setName(source.getName() + "_INDEX");
                return attr;
            };
        }
    }

    // ==================== Automatic @Extends Inheritance Tests ====================

    @Nested
    @DisplayName("Automatic @Extends Inheritance (ETL Semantics)")
    class AutomaticExtendsTests {

        /**
         * ETL Semantics: Parent rule's body executes automatically before child rule.
         * No explicit executeParentRule() call needed.
         */
        @Test
        @DisplayName("Parent rule executes automatically - no explicit call needed")
        void parentRuleExecutesAutomatically() {
            createEClass("Entity");
            executionLog.clear();

            registry.register(AutomaticExtendsTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Parent should execute BEFORE child (automatic)
            assertTrue(executionLog.contains("AutoParent"), "Parent should execute automatically");
            assertTrue(executionLog.contains("AutoChild"), "Child should execute");
            int parentIndex = executionLog.indexOf("AutoParent");
            int childIndex = executionLog.indexOf("AutoChild");
            assertTrue(parentIndex < childIndex, "Parent should execute BEFORE child");
        }

        /**
         * ETL Semantics: Parent and child operate on the SAME target instance.
         * Child's target type is created, parent's createTarget() returns that instance.
         */
        @Test
        @DisplayName("Parent and child share the same target instance")
        void parentAndChildShareSameTarget() {
            createEClass("Entity");
            capturedInstances.clear();

            registry.register(SharedTargetTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(2, capturedInstances.size(), "Both parent and child should capture instance");
            assertSame(capturedInstances.get(0), capturedInstances.get(1),
                    "Parent and child should operate on SAME target instance");
        }

        /**
         * ETL Semantics: Parent initializes common properties, child adds specific ones.
         */
        @Test
        @DisplayName("Parent initializes properties, child adds to them")
        void parentInitializesChildAdds() {
            EClass source = createEClass("TestEntity");

            registry.register(PropertyInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get the result from target resource
            Resource targetResource = targetResourceSet.getResources().get(0);
            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Parent should have set name, child should have set nsPrefix
            assertEquals("TestEntity", result.getName(), "Name should be set by parent");
            assertEquals("child_prefix", result.getNsPrefix(), "NsPrefix should be set by child");
            assertEquals("http://child.nsuri", result.getNsURI(), "NsURI should be set by child");
        }

        /**
         * ETL Semantics: Multi-level inheritance chain (grandparent → parent → child).
         */
        @Test
        @DisplayName("Multi-level inheritance chain executes in order")
        void multiLevelInheritanceChain() {
            createEClass("Entity");
            executionLog.clear();

            registry.register(MultiLevelExtendsTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // All three should execute in order: Grandparent → Parent → Child
            assertTrue(executionLog.contains("Grandparent"), "Grandparent should execute");
            assertTrue(executionLog.contains("Parent"), "Parent should execute");
            assertTrue(executionLog.contains("Child"), "Child should execute");

            int grandparentIndex = executionLog.indexOf("Grandparent");
            int parentIndex = executionLog.indexOf("Parent");
            int childIndex = executionLog.indexOf("Child");

            assertTrue(grandparentIndex < parentIndex, "Grandparent before Parent");
            assertTrue(parentIndex < childIndex, "Parent before Child");
        }

        /**
         * Backward compatibility: Explicit executeParentRule() still works.
         */
        @Test
        @DisplayName("Explicit executeParentRule() still works for backward compatibility")
        void explicitParentRuleStillWorks() {
            createEClass("Entity");

            registry.register(ExplicitParentCallTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            // Should not throw and should work correctly
            assertDoesNotThrow(() -> executor.transform());
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class AutomaticExtendsTransformation {
        @TransformRule(name = "AutoParent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> autoParent() {
            return (source, ctx) -> {
                executionLog.add("AutoParent");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "AutoChild")
        @Transform(type = EClass.class)
        @Extends("AutoParent")
        public TransformFunction<EClass, EPackage> autoChild() {
            return (source, ctx) -> {
                executionLog.add("AutoChild");
                // NO explicit executeParentRule() call - framework does it automatically
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsPrefix("child_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SharedTargetTransformation {
        @TransformRule(name = "SharedParent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> sharedParent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                capturedInstances.add(pkg); // Capture in parent
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "SharedChild")
        @Transform(type = EClass.class)
        @Extends("SharedParent")
        public TransformFunction<EClass, EPackage> sharedChild() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                capturedInstances.add(pkg); // Capture in child - should be SAME instance
                pkg.setNsPrefix("child");
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class PropertyInheritanceTransformation {
        @TransformRule(name = "PropertyParent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> propertyParent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                // Parent sets common properties
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "PropertyChild")
        @Transform(type = EClass.class)
        @Extends("PropertyParent")
        public TransformFunction<EClass, EPackage> propertyChild() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                // Child adds specific properties (name already set by parent)
                pkg.setNsPrefix("child_prefix");
                pkg.setNsURI("http://child.nsuri");
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiLevelExtendsTransformation {
        @TransformRule(name = "GrandparentRule")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> grandparent() {
            return (source, ctx) -> {
                executionLog.add("Grandparent");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("grandparent_" + source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ParentRule")
        @Transform(type = EClass.class)
        @Abstract
        @Extends("GrandparentRule")
        public TransformFunction<EClass, EPackage> parent() {
            return (source, ctx) -> {
                executionLog.add("Parent");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsPrefix("parent_prefix");
                return pkg;
            };
        }

        @TransformRule(name = "ChildRule")
        @Transform(type = EClass.class)
        @Extends("ParentRule")
        public TransformFunction<EClass, EPackage> child() {
            return (source, ctx) -> {
                executionLog.add("Child");
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://child.uri");
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ExplicitParentCallTransformation {
        @TransformRule(name = "ExplicitParent")
        @Transform(type = EClass.class)
        @Abstract
        public TransformFunction<EClass, EPackage> explicitParent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ExplicitChild")
        @Transform(type = EClass.class)
        @Extends("ExplicitParent")
        public TransformFunction<EClass, EPackage> explicitChild() {
            return (source, ctx) -> {
                // Explicit call still works for backward compatibility
                EPackage pkg = ctx.executeParentRule("ExplicitParent", source);
                pkg.setNsPrefix("explicit_child");
                ctx.addToResource(pkg);
                return pkg;
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
