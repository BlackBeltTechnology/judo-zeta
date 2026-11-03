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
 * Tests for source-level locking to prevent deadlocks when multiple @Greedy rules
 * for the same source type call ctx.equivalent() to look up each other's targets.
 *
 * <p>This test verifies the fix for the deadlock scenario:</p>
 * <pre>
 * Rule A (GREEDY): SourceType → TargetA (main target)
 * Rule B (GREEDY): SourceType → TargetB (calls ctx.equivalent(s, TargetA.class))
 *
 * Without source-level locking, if Rule B runs first and calls equivalent() to get
 * Rule A's target, deadlock can occur in parallel execution.
 *
 * With source-level locking, all rules for the same source element execute under
 * a single lock, preventing the deadlock.
 * </pre>
 */
@DisplayName("Source-Level Locking Tests")
class SourceLevelLockingTest {

    private static final Logger log = LoggerFactory.getLogger(SourceLevelLockingTest.class);

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
        MainTargetRule.executionCount.set(0);
        AnnotationRule.executionCount.set(0);
        SecondAnnotationRule.executionCount.set(0);
        ThirdAnnotationRule.executionCount.set(0);
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
     * Test that multiple @Greedy rules for the same source type don't deadlock
     * when one calls equivalent() for another's target.
     *
     * This pattern is common in judo-tatami where annotation rules look up the
     * main target to attach annotations to it.
     */
    @Nested
    @DisplayName("Same-Source Greedy Rule Deadlock Prevention")
    class SameSourceGreedyRuleTests {

        @Test
        @DisplayName("Multiple greedy rules calling equivalent() complete without deadlock")
        void testMultipleGreedyRulesWithEquivalentCallsNoDeadlock() {
            // Create 100 source elements to force parallel execution
            for (int i = 0; i < 100; i++) {
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("Source" + i);
                sourceResource.getContents().add(sourceClass);
            }

            // Register transformation with two greedy rules for same source type
            // Rule B calls equivalent() to look up Rule A's target
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(MainTargetRule.class);
            registry.register(AnnotationRule.class);

            // Create context and executor
            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .parallelThreshold(10) // Force parallel execution
                    .chunkSize(5)
                    .build();

            // Execute - should complete without deadlock (timeout would indicate deadlock)
            long startTime = System.currentTimeMillis();
            TransformationResult result = executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            log.info("Transformation completed in {}ms", duration);

            // Verify results
            // Count EPackages (main targets) in target
            long packageCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EPackage)
                    .count();

            // Count EAnnotations
            long annotationCount = targetResource.getContents().stream()
                    .flatMap(e -> e instanceof EPackage ?
                            ((EPackage) e).getEAnnotations().stream() :
                            java.util.stream.Stream.empty())
                    .count();

            log.info("MainTarget executions: {}, Annotation executions: {}",
                    MainTargetRule.executionCount.get(), AnnotationRule.executionCount.get());

            assertEquals(100, packageCount, "Should have 100 main targets (EPackages)");
            assertEquals(100, annotationCount, "Should have 100 annotations");

            // Verify no timeout occurred (deadlock would cause 30s timeout)
            assertTrue(duration < 10000, "Should complete in less than 10 seconds (deadlock would timeout at 30s)");
        }

        @RepeatedTest(5)
        @DisplayName("Stress test with many greedy rules calling equivalent()")
        void testStressWithManyGreedyRules() {
            // Create 200 source elements
            for (int i = 0; i < 200; i++) {
                EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
                sourceClass.setName("StressSource" + i);
                sourceResource.getContents().add(sourceClass);
            }

            // Reset counters
            MainTargetRule.executionCount.set(0);
            AnnotationRule.executionCount.set(0);
            SecondAnnotationRule.executionCount.set(0);
            ThirdAnnotationRule.executionCount.set(0);

            // Register multiple greedy rules that call equivalent()
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(MainTargetRule.class);
            registry.register(AnnotationRule.class);
            registry.register(SecondAnnotationRule.class);
            registry.register(ThirdAnnotationRule.class);

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

            log.info("Stress test completed in {}ms", duration);

            // Verify correct counts
            long packageCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EPackage)
                    .count();

            assertEquals(200, packageCount, "Should have 200 main targets");
            assertTrue(duration < 15000, "Should complete quickly (not timeout at 30s)");

            log.info("Executions - Main: {}, Ann1: {}, Ann2: {}, Ann3: {}",
                    MainTargetRule.executionCount.get(),
                    AnnotationRule.executionCount.get(),
                    SecondAnnotationRule.executionCount.get(),
                    ThirdAnnotationRule.executionCount.get());
        }
    }

    // ==================== Test Transformation Rules ====================

    /**
     * Main target rule - creates the primary transformation target.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MainTargetRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "CreateMainTarget")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createMainTarget() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_Package");
                pkg.setNsPrefix(source.getName().toLowerCase());
                pkg.setNsURI("http://test/" + source.getName());

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Annotation rule - looks up main target via equivalent() and adds annotation.
     * This pattern is common in judo-tatami.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class AnnotationRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "CreateAnnotation")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EAnnotation> createAnnotation() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                // Look up the main target via equivalent() - this is the pattern that can deadlock
                EPackage mainTarget = ctx.equivalent(source, EPackage.class);
                if (mainTarget == null) {
                    log.warn("MainTarget not found for {}", source.getName());
                    return null;
                }

                EAnnotation annotation = ctx.createTarget(EAnnotation.class);
                annotation.setSource("http://test/annotation");
                annotation.getDetails().put("type", "primary");

                // Add annotation to main target
                mainTarget.getEAnnotations().add(annotation);

                return annotation;
            };
        }
    }

    /**
     * Second annotation rule - also looks up main target via equivalent().
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class SecondAnnotationRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "CreateSecondAnnotation")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EAnnotation> createSecondAnnotation() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EPackage mainTarget = ctx.equivalent(source, EPackage.class);
                if (mainTarget == null) {
                    return null;
                }

                EAnnotation annotation = ctx.createTarget(EAnnotation.class);
                annotation.setSource("http://test/second");
                annotation.getDetails().put("type", "secondary");

                mainTarget.getEAnnotations().add(annotation);
                return annotation;
            };
        }
    }

    /**
     * Third annotation rule - also looks up main target via equivalent().
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ThirdAnnotationRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "CreateThirdAnnotation")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EAnnotation> createThirdAnnotation() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EPackage mainTarget = ctx.equivalent(source, EPackage.class);
                if (mainTarget == null) {
                    return null;
                }

                EAnnotation annotation = ctx.createTarget(EAnnotation.class);
                annotation.setSource("http://test/third");
                annotation.getDetails().put("type", "tertiary");

                mainTarget.getEAnnotations().add(annotation);
                return annotation;
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
