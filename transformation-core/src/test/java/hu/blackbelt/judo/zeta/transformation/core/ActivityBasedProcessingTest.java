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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for activity-based processing mode.
 */
@DisplayName("Activity-Based Processing Tests")
class ActivityBasedProcessingTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields - reset before each test
    static AtomicInteger activityBasedExecutionCount = new AtomicInteger(0);
    static AtomicInteger eagerExecutionCount = new AtomicInteger(0);
    static Set<String> processedElements = ConcurrentHashMap.newKeySet();
    static Set<EClass> referencedClasses = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        // Reset tracking
        activityBasedExecutionCount.set(0);
        eagerExecutionCount.set(0);
        processedElements.clear();
        referencedClasses.clear();

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

    // ==================== Activity-Based Processing Tests ====================

    @Nested
    @DisplayName("Basic Activity-Based Processing")
    class BasicActivityBasedTests {

        /**
         * Test that @ActivityBased rules only process elements activated via equivalent().
         */
        @Test
        @DisplayName("Activity-based rule processes only activated elements")
        void activityBasedRuleProcessesOnlyActivated() {
            // Create 5 classes, but only reference 2 via equivalent()
            EClass class1 = createEClass("Referenced1");
            EClass class2 = createEClass("Referenced2");
            createEClass("NotReferenced1");
            createEClass("NotReferenced2");
            createEClass("NotReferenced3");

            registry.register(ActivityBasedTransformation.class);
            context.setTransformationRegistry(registry);

            // The eager rule will call equivalent() for class1 and class2
            referencedClasses.clear();
            referencedClasses.add(class1);
            referencedClasses.add(class2);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Eager rule should execute for each source element (5 times)
            assertTrue(eagerExecutionCount.get() >= 1,
                    "Eager rule should execute at least once, actual: " + eagerExecutionCount.get());

            // Activity-based rule should only process the 2 activated elements
            assertEquals(2, activityBasedExecutionCount.get(),
                    "Activity-based rule should only process activated elements");
            assertTrue(processedElements.contains("Referenced1"));
            assertTrue(processedElements.contains("Referenced2"));
            assertFalse(processedElements.contains("NotReferenced1"));
            assertFalse(processedElements.contains("NotReferenced2"));
            assertFalse(processedElements.contains("NotReferenced3"));
        }
    }

    @Nested
    @DisplayName("ETL Compatibility Mode Tests")
    class ETLCompatibilityModeTests {

        /**
         * Test that etlCompatibilityMode treats @Greedy @Lazy rules as activity-based.
         */
        @Test
        @DisplayName("ETL compatibility mode makes greedy lazy rules activity-based")
        void etlCompatibilityModeEffective() {
            // Create 5 classes, eager rule references 2
            EClass class1 = createEClass("Referenced1");
            EClass class2 = createEClass("Referenced2");
            createEClass("NotReferenced1");
            createEClass("NotReferenced2");
            createEClass("NotReferenced3");

            registry.register(ETLCompatibilityTransformation.class);
            context.setTransformationRegistry(registry);

            referencedClasses.clear();
            referencedClasses.add(class1);
            referencedClasses.add(class2);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .etlCompatibilityMode(true)  // Enable ETL compatibility
                    .parallel(false)
                    .build();

            executor.transform();

            // With etlCompatibilityMode, only 2 activated elements should be processed
            assertEquals(2, activityBasedExecutionCount.get(),
                    "ETL compatibility mode should make greedy lazy rules process only activated elements");
            assertTrue(processedElements.contains("Referenced1"));
            assertTrue(processedElements.contains("Referenced2"));
        }
    }

    @Nested
    @DisplayName("Guard Tests")
    class GuardTests {

        /**
         * Test that guards still filter activated elements in Phase 2.
         */
        @Test
        @DisplayName("Guards filter activated elements")
        void guardsFilterActivatedElements() {
            // Create classes with different prefixes
            EClass allowed1 = createEClass("Allowed_Class1");
            EClass allowed2 = createEClass("Allowed_Class2");
            EClass blocked1 = createEClass("Blocked_Class1");
            EClass blocked2 = createEClass("Blocked_Class2");

            registry.register(GuardedActivityBasedTransformation.class);
            context.setTransformationRegistry(registry);

            // Reference all classes via equivalent()
            referencedClasses.clear();
            referencedClasses.add(allowed1);
            referencedClasses.add(allowed2);
            referencedClasses.add(blocked1);
            referencedClasses.add(blocked2);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Only "Allowed_*" classes should pass the guard
            assertEquals(2, activityBasedExecutionCount.get(),
                    "Guards should filter activated elements");
            assertTrue(processedElements.contains("Allowed_Class1"));
            assertTrue(processedElements.contains("Allowed_Class2"));
            assertFalse(processedElements.contains("Blocked_Class1"));
            assertFalse(processedElements.contains("Blocked_Class2"));
        }
    }

    @Nested
    @DisplayName("Late Activation (Fixpoint) Tests")
    class LateActivationTests {

        /**
         * Test that Phase 2 handles late activations from activity-based rules.
         * When an activity-based rule activates another element, it should be processed.
         */
        @Test
        @DisplayName("Late activations are processed in fixpoint loop")
        void lateActivationsProcessed() {
            // Create a chain: Class1 -> Class2 -> Class3
            EClass class1 = createEClass("ChainStart");
            EClass class2 = createEClass("ChainMiddle");
            EClass class3 = createEClass("ChainEnd");

            registry.register(ChainedActivityBasedTransformation.class);
            context.setTransformationRegistry(registry);

            // Set up the chain - eager rule activates Class1
            // ActivityBased rule for Class1 activates Class2
            // ActivityBased rule for Class2 activates Class3
            ChainedActivityBasedTransformation.chainMap.clear();
            ChainedActivityBasedTransformation.chainMap.put(class1, class2);
            ChainedActivityBasedTransformation.chainMap.put(class2, class3);
            ChainedActivityBasedTransformation.initialActivation = class1;

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // All 3 classes should be processed through the chain
            assertEquals(3, activityBasedExecutionCount.get(),
                    "Late activations should be processed in fixpoint loop");
            assertTrue(processedElements.contains("ChainStart"));
            assertTrue(processedElements.contains("ChainMiddle"));
            assertTrue(processedElements.contains("ChainEnd"));
        }
    }

    @Nested
    @DisplayName("Parallel Execution Tests")
    class ParallelExecutionTests {

        /**
         * Test activity-based processing with parallel execution.
         */
        @Test
        @DisplayName("Activity-based processing works with parallel execution")
        void activityBasedWithParallel() {
            // Create many classes, reference half
            List<EClass> referenced = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                EClass eClass = createEClass("Referenced" + i);
                referenced.add(eClass);
            }
            for (int i = 0; i < 50; i++) {
                createEClass("NotReferenced" + i);
            }

            registry.register(ActivityBasedTransformation.class);
            context.setTransformationRegistry(registry);

            referencedClasses.clear();
            referencedClasses.addAll(referenced);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .parallelThreshold(10)  // Force parallel execution
                    .build();

            executor.transform();

            // Only the 50 referenced classes should be processed
            assertEquals(50, activityBasedExecutionCount.get(),
                    "Parallel activity-based should process only activated elements");

            for (int i = 0; i < 50; i++) {
                assertTrue(processedElements.contains("Referenced" + i),
                        "Referenced" + i + " should be processed");
            }
            for (int i = 0; i < 50; i++) {
                assertFalse(processedElements.contains("NotReferenced" + i),
                        "NotReferenced" + i + " should NOT be processed");
            }
        }
    }

    // ==================== Transformation Classes ====================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ActivityBasedTransformation {

        @TransformRule(name = "EagerClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                eagerExecutionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("eager_" + source.getName());

                // Activate referenced classes via equivalent()
                for (EClass ref : referencedClasses) {
                    ctx.equivalent(ref, "ActivityBasedClass");
                }

                return pkg;
            };
        }

        @TransformRule(name = "ActivityBasedClass")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        @ActivityBased
        public TransformFunction<EClass, EPackage> activityBasedRule() {
            return (source, ctx) -> {
                activityBasedExecutionCount.incrementAndGet();
                processedElements.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("activity_" + source.getName());
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ETLCompatibilityTransformation {

        @TransformRule(name = "CompatEagerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                eagerExecutionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("eager_" + source.getName());

                // Activate referenced classes
                for (EClass ref : referencedClasses) {
                    ctx.equivalent(ref, "CompatibilityRule");
                }

                return pkg;
            };
        }

        @TransformRule(name = "CompatibilityRule")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        // Note: NO @ActivityBased - relies on etlCompatibilityMode
        public TransformFunction<EClass, EPackage> compatibilityRule() {
            return (source, ctx) -> {
                activityBasedExecutionCount.incrementAndGet();
                processedElements.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("compat_" + source.getName());
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class GuardedActivityBasedTransformation {

        @TransformRule(name = "GuardedEagerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                eagerExecutionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("eager_" + source.getName());

                // Activate all referenced classes
                for (EClass ref : referencedClasses) {
                    ctx.equivalent(ref, "GuardedActivityBasedRule");
                }

                return pkg;
            };
        }

        @TransformRule(name = "GuardedActivityBasedRule")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        @ActivityBased
        @Guard(method = "allowedGuard")
        public TransformFunction<EClass, EPackage> guardedRule() {
            return (source, ctx) -> {
                activityBasedExecutionCount.incrementAndGet();
                processedElements.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("guarded_" + source.getName());
                return pkg;
            };
        }

        public boolean allowedGuard(EObject source, TransformationContext ctx) {
            if (source instanceof ENamedElement) {
                return ((ENamedElement) source).getName().startsWith("Allowed_");
            }
            return false;
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ChainedActivityBasedTransformation {
        static Map<EClass, EClass> chainMap = new ConcurrentHashMap<>();
        static EClass initialActivation;

        @TransformRule(name = "ChainEagerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                eagerExecutionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("eager_" + source.getName());

                // Start the chain by activating the initial element
                if (initialActivation != null) {
                    ctx.equivalent(initialActivation, "ChainedRule");
                }

                return pkg;
            };
        }

        @TransformRule(name = "ChainedRule")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        @ActivityBased
        public TransformFunction<EClass, EPackage> chainedRule() {
            return (source, ctx) -> {
                activityBasedExecutionCount.incrementAndGet();
                processedElements.add(source.getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("chain_" + source.getName());

                // Activate next in chain if exists (late activation)
                EClass next = chainMap.get(source);
                if (next != null) {
                    ctx.equivalent(next, "ChainedRule");
                }

                return pkg;
            };
        }
    }

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                // Include root contents directly
                for (EObject root : resource.getContents()) {
                    if (type.isInstance(root)) {
                        results.add((T) root);
                    }
                }
                // Also iterate nested contents
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    if (type.isInstance(obj) && !results.contains(obj)) {
                        results.add((T) obj);
                    }
                }
            }
            return results;
        }
    }
}
