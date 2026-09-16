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
 * Regression tests for parallel execution in the Zeta transformation framework.
 *
 * <h2>Scenarios Tested:</h2>
 * <ol>
 *   <li><b>NPE in EMF Model Iteration</b>: Verifies no NPE during parallel transformation</li>
 *   <li><b>Element Count Consistency</b>: Verifies sequential and parallel produce same results</li>
 *   <li><b>Container References</b>: Verifies containment relationships are correct</li>
 *   <li><b>Lazy Rule Atomicity</b>: Verifies equivalent() caching prevents duplicates</li>
 * </ol>
 *
 * <p>These tests validate that the current parallel implementation handles common
 * scenarios correctly. More complex production transformations may reveal edge cases
 * not covered by these tests.</p>
 *
 * <p>If any of these tests fail, it indicates a regression in parallel execution safety.</p>
 */
@DisplayName("Parallel Execution Issues Reproduction Tests")
class ParallelExecutionIssuesTest {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutionIssuesTest.class);

    /**
     * Element count high enough to trigger parallel execution.
     * Default threshold is 1000, so we use 2000+ to ensure parallel mode is used.
     */
    private static final int LARGE_ELEMENT_COUNT = 2000;

    /**
     * Number of times to repeat tests to catch intermittent race conditions.
     */
    private static final int REPEAT_COUNT = 5;

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

        // Reset static counters
        ContainmentTransformation.executionCount.set(0);
        ContainmentTransformation.childrenCreated.set(0);
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
            ec.setName("Element" + i);
            sourceResource.getContents().add(ec);
        }
    }

    // ==================== Issue 1: NPE in EMF Model Iteration ====================

    @Nested
    @DisplayName("Issue 1: NPE in EMF Model Iteration")
    class NpeInEmfIterationTests {

        /**
         * Reproduces NPE when parallel threads access EMF model concurrently.
         *
         * <p>The error occurs because EMF's internal model traversal state is not
         * thread-safe. When multiple threads iterate over model elements, internal
         * state becomes corrupted.</p>
         */
        @RepeatedTest(REPEAT_COUNT)
        @DisplayName("Parallel transformation should not throw NPE")
        void testParallelTransformationNpe() {
            createSourceElements(LARGE_ELEMENT_COUNT);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(SimpleTransformation.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(100)  // Low threshold to ensure parallel execution
                    .build();

            // This should not throw NullPointerException
            assertDoesNotThrow(() -> executor.transform(),
                    "Parallel transformation should complete without NPE");

            log.info("Parallel transformation completed with {} target elements",
                    targetResource.getContents().size());
        }
    }

    // ==================== Issue 2: Element Count Mismatch ====================

    @Nested
    @DisplayName("Issue 2: Element Count Mismatch")
    class ElementCountMismatchTests {

        /**
         * Reproduces element count mismatch between sequential and parallel execution.
         *
         * <p>Race conditions in cache operations can cause duplicate elements to be
         * created when multiple threads process the same source concurrently.</p>
         */
        @RepeatedTest(REPEAT_COUNT)
        @DisplayName("Parallel and sequential should produce same element count")
        void testElementCountMismatch() {
            createSourceElements(LARGE_ELEMENT_COUNT);

            // Run sequential transformation
            TransformationRegistry sequentialRegistry = new TransformationRegistry();
            sequentialRegistry.register(SimpleTransformation.class);
            TransformationContext sequentialCtx = createContext(sequentialRegistry);

            TransformationExecutor sequentialExecutor = TransformationExecutor.builder()
                    .registry(sequentialRegistry)
                    .context(sequentialCtx)
                    .parallel(false)
                    .build();

            sequentialExecutor.transform();
            int sequentialCount = targetResource.getContents().size();

            // Reset and run parallel transformation with SAME source elements
            targetResourceSet = new ResourceSetImpl();
            targetResource = targetResourceSet.createResource(URI.createURI("test://target2.xmi"));

            TransformationRegistry parallelRegistry = new TransformationRegistry();
            parallelRegistry.register(SimpleTransformation.class);
            TransformationContext parallelCtx = createContext(parallelRegistry);

            TransformationExecutor parallelExecutor = TransformationExecutor.builder()
                    .registry(parallelRegistry)
                    .context(parallelCtx)
                    .parallel(true)
                    .parallelThreshold(100)
                    .build();

            parallelExecutor.transform();
            int parallelCount = targetResource.getContents().size();

            log.info("Element counts - Sequential: {}, Parallel: {}", sequentialCount, parallelCount);

            // Parallel should produce EXACTLY the same number of elements as sequential
            assertEquals(sequentialCount, parallelCount,
                    "Parallel transformation should produce same element count as sequential. " +
                    "Difference of " + Math.abs(parallelCount - sequentialCount) + " indicates race condition.");
        }

        private int countAllElements(ResourceSet resourceSet) {
            int count = 0;
            for (Resource resource : resourceSet.getResources()) {
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    iterator.next();
                    count++;
                }
            }
            return count;
        }
    }

    // ==================== Issue 3: Null Container References ====================

    @Nested
    @DisplayName("Issue 3: Null Container References")
    class NullContainerTests {

        /**
         * Reproduces null container references in parallel transformation.
         *
         * <p>When containment relationships are established in parallel, race conditions
         * can cause elements to have null containers even when they were added to
         * containment lists.</p>
         */
        @RepeatedTest(REPEAT_COUNT)
        @DisplayName("All child elements should have valid container references")
        void testNullContainerReferences() {
            // Create parent elements that will have children
            for (int i = 0; i < 500; i++) {
                EClass parent = EcoreFactory.eINSTANCE.createEClass();
                parent.setName("Parent" + i);

                // Add 3 attributes to each class (these become children)
                for (int j = 0; j < 3; j++) {
                    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                    attr.setName("attr" + j);
                    attr.setEType(EcorePackage.Literals.ESTRING);
                    parent.getEStructuralFeatures().add(attr);
                }

                sourceResource.getContents().add(parent);
            }

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ContainmentTransformation.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(100)
                    .build();

            executor.transform();

            // Verify all children have valid container references
            List<String> nullContainerElements = new ArrayList<>();
            for (Resource resource : targetResourceSet.getResources()) {
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject element = iterator.next();

                    // Skip root elements - they shouldn't have containers
                    if (element.eContainer() == null && !resource.getContents().contains(element)) {
                        // This element is not a root but has null container - BUG!
                        nullContainerElements.add(getElementDescription(element));
                    }
                }
            }

            // Also check that all created children are properly contained
            int totalChildren = ContainmentTransformation.childrenCreated.get();

            // Count children in target resource only (EAttributes within EClasses)
            int containedChildren = 0;
            for (EObject root : targetResource.getContents()) {
                if (root instanceof EClass) {
                    containedChildren += ((EClass) root).getEStructuralFeatures().size();
                }
            }

            log.info("Containment check - Created: {}, Properly contained: {}, Null containers: {}",
                    totalChildren, containedChildren, nullContainerElements.size());

            assertTrue(nullContainerElements.isEmpty(),
                    "Found " + nullContainerElements.size() + " elements with null container: " +
                    nullContainerElements.subList(0, Math.min(5, nullContainerElements.size())));

            assertEquals(totalChildren, containedChildren,
                    "All created children should be properly contained. " +
                    "Expected " + totalChildren + " but found " + containedChildren);
        }

        /**
         * Reproduces ordering issues in containment lists.
         *
         * <p>When multiple threads add children to the same parent's containment list,
         * the order may become non-deterministic or elements may be lost.</p>
         */
        @RepeatedTest(REPEAT_COUNT)
        @DisplayName("Containment list ordering should be deterministic")
        void testContainmentListOrdering() {
            // Create parents with many children
            for (int i = 0; i < 100; i++) {
                EClass parent = EcoreFactory.eINSTANCE.createEClass();
                parent.setName("OrderParent" + i);

                for (int j = 0; j < 10; j++) {
                    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                    attr.setName("orderAttr" + j);
                    attr.setEType(EcorePackage.Literals.ESTRING);
                    parent.getEStructuralFeatures().add(attr);
                }

                sourceResource.getContents().add(parent);
            }

            // Run transformation twice and compare results
            List<String> run1Order = runTransformationAndGetOrder();

            // Reset
            setUp();
            for (int i = 0; i < 100; i++) {
                EClass parent = EcoreFactory.eINSTANCE.createEClass();
                parent.setName("OrderParent" + i);
                for (int j = 0; j < 10; j++) {
                    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                    attr.setName("orderAttr" + j);
                    attr.setEType(EcorePackage.Literals.ESTRING);
                    parent.getEStructuralFeatures().add(attr);
                }
                sourceResource.getContents().add(parent);
            }

            List<String> run2Order = runTransformationAndGetOrder();

            assertEquals(run1Order, run2Order,
                    "Multiple parallel runs should produce identical element ordering");
        }

        private List<String> runTransformationAndGetOrder() {
            ContainmentTransformation.executionCount.set(0);
            ContainmentTransformation.childrenCreated.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ContainmentTransformation.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .build();

            executor.transform();

            // Collect element names in order
            List<String> order = new ArrayList<>();
            for (EObject root : targetResource.getContents()) {
                collectElementOrder(root, order);
            }
            return order;
        }

        private void collectElementOrder(EObject element, List<String> order) {
            if (element instanceof ENamedElement) {
                order.add(((ENamedElement) element).getName());
            }
            for (EObject child : element.eContents()) {
                collectElementOrder(child, order);
            }
        }

        private int countContainedChildren(ResourceSet resourceSet) {
            int count = 0;
            for (Resource resource : resourceSet.getResources()) {
                for (EObject root : resource.getContents()) {
                    count += countDescendants(root);
                }
            }
            return count;
        }

        private int countDescendants(EObject parent) {
            int count = 0;
            for (EObject child : parent.eContents()) {
                count++;
                count += countDescendants(child);
            }
            return count;
        }

        private String getElementDescription(EObject element) {
            if (element instanceof ENamedElement) {
                return element.eClass().getName() + ":" + ((ENamedElement) element).getName();
            }
            return element.eClass().getName() + "@" + System.identityHashCode(element);
        }
    }

    // ==================== Issue 4: Lazy Rule Race Conditions ====================

    @Nested
    @DisplayName("Issue 4: Lazy Rule Race Conditions")
    class LazyRuleRaceConditionTests {

        /**
         * Reproduces race conditions when multiple threads call equivalent() for same source.
         *
         * <p>This is the most common cause of element count mismatches in real transformations.
         * Multiple eager rules may reference the same source via equivalent(), triggering
         * concurrent lazy rule execution.</p>
         */
        @RepeatedTest(REPEAT_COUNT)
        @DisplayName("Concurrent equivalent() calls should not create duplicates")
        void testConcurrentEquivalentCalls() {
            // Create source elements
            for (int i = 0; i < 500; i++) {
                EClass ec = EcoreFactory.eINSTANCE.createEClass();
                ec.setName("LazySource" + i);
                sourceResource.getContents().add(ec);
            }

            LazyRuleTransformation.executionCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(LazyRuleTransformation.class);
            registry.register(EagerRuleCallingLazy.class);

            TransformationContext ctx = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(50)
                    .build();

            executor.transform();

            int lazyExecutions = LazyRuleTransformation.executionCount.get();
            int sourceCount = 500;

            log.info("Lazy rule executions: {} (expected: {})", lazyExecutions, sourceCount);

            // Each source should trigger lazy rule exactly once
            assertEquals(sourceCount, lazyExecutions,
                    "Each source should trigger lazy rule exactly once. " +
                    "Got " + lazyExecutions + " executions for " + sourceCount + " sources. " +
                    "Difference indicates race condition in equivalent() caching.");
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Simple transformation that creates one target per source.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SimpleTransformation {

        @TransformRule(name = "SimpleRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> simpleRule() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("T_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Lazy rule that should only execute once per source.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class LazyRuleTransformation {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("Lazy_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Eager rule that calls equivalent() to trigger lazy rule execution.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class EagerRuleCallingLazy {

        @TransformRule(name = "EagerCallingLazy")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eagerRule() {
            return (source, ctx) -> {
                // Trigger lazy rule via equivalent()
                EDataType lazyResult = ctx.equivalent(source, EDataType.class);

                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Eager_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation that creates containment relationships.
     * Parent elements get child elements added to their containment lists.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ContainmentTransformation {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final AtomicInteger childrenCreated = new AtomicInteger(0);

        @TransformRule(name = "ContainmentRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> containmentRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EClass targetClass = ctx.createTarget(EClass.class);
                targetClass.setName("T_" + source.getName());

                // Transform and add children (EAttributes -> EAttributes)
                for (EStructuralFeature sourceFeature : source.getEStructuralFeatures()) {
                    if (sourceFeature instanceof EAttribute) {
                        EAttribute sourceAttr = (EAttribute) sourceFeature;
                        EAttribute targetAttr = EcoreFactory.eINSTANCE.createEAttribute();
                        targetAttr.setName("T_" + sourceAttr.getName());
                        targetAttr.setEType(EcorePackage.Literals.ESTRING);

                        // Add to containment list - this is the critical operation for Issue 3
                        targetClass.getEStructuralFeatures().add(targetAttr);
                        childrenCreated.incrementAndGet();
                    }
                }

                ctx.addToResource(targetClass);
                return targetClass;
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
