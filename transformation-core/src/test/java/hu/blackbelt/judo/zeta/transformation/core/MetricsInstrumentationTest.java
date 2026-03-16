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
 * SPDX-License-Identifier: EPL-2.0
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying that {@link TransformationMetrics} counters are correctly
 * incremented across all execution strategies.
 */
@DisplayName("Metrics Instrumentation Tests")
class MetricsInstrumentationTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();

        TransformationMetrics.reset();
        TransformationMetrics.enable();
    }

    @AfterEach
    void tearDown() {
        TransformationMetrics.disable();
        TransformationMetrics.reset();
    }

    // ========================================================================
    // Task 4.1: ruleIterations > 0 and ruleExecutions > 0 after RULE_BY_RULE
    // ========================================================================

    @Test
    @DisplayName("RULE_BY_RULE increments ruleIterations and ruleExecutions")
    void ruleByRuleIncrementsRuleCounters() {
        createEClass("Alpha");
        createEClass("Beta");
        createEClass("Gamma");

        registry.register(SimpleClassRule.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
        executor.transform();

        assertTrue(TransformationMetrics.getRuleIterations() > 0,
                "ruleIterations should be > 0 after RULE_BY_RULE transformation, got: "
                        + TransformationMetrics.getRuleIterations());
        assertTrue(TransformationMetrics.getRuleExecutions() > 0,
                "ruleExecutions should be > 0 after RULE_BY_RULE transformation, got: "
                        + TransformationMetrics.getRuleExecutions());
        assertTrue(TransformationMetrics.getGuardEvaluations() >= 0,
                "guardEvaluations should be >= 0");
    }

    // ========================================================================
    // Task 4.2: equivalentCacheHits + equivalentCacheMisses <= equivalentCalls
    // ========================================================================

    @Test
    @DisplayName("equivalent() cache hits + misses do not exceed total calls")
    void equivalentCacheCountsDoNotExceedTotalCalls() {
        createEClass("TestClass");

        registry.register(LazyTargetRule.class);
        registry.register(EquivalentCallerRule.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
        executor.transform();

        long totalCalls = TransformationMetrics.getEquivalentCalls();
        long hits = TransformationMetrics.getEquivalentCacheHits();
        long misses = TransformationMetrics.getEquivalentCacheMisses();

        assertTrue(totalCalls > 0,
                "equivalentCalls should be > 0 (equivalent() was called), got: " + totalCalls);
        assertTrue(hits + misses <= totalCalls,
                String.format("cacheHits (%d) + cacheMisses (%d) = %d should be <= equivalentCalls (%d)",
                        hits, misses, hits + misses, totalCalls));
    }

    // ========================================================================
    // Transformation rule classes
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SimpleClassRule {
        @TransformRule(name = "SimpleClassToTarget")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EClass> classToTarget() {
            return (eClass, ctx) -> {
                EClass target = EcoreFactory.eINSTANCE.createEClass();
                target.setName(eClass.getName() + "_Target");
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyTargetRule {
        @TransformRule(name = "LazyTarget")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> lazyTarget() {
            return (eClass, ctx) -> {
                EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                ann.setSource("lazy:" + eClass.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class EquivalentCallerRule {
        @TransformRule(name = "EquivalentCaller")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> equivalentCaller() {
            return (eClass, ctx) -> {
                // Use the 2-arg equivalent(source, ruleName) — this is the method
                // whose double-counting was fixed. First call triggers lazy execution
                // (cache miss), second call hits cache.
                EObject first = ctx.equivalent(eClass, "LazyTarget");
                EObject second = ctx.equivalent(eClass, "LazyTarget");
                EAnnotation result = EcoreFactory.eINSTANCE.createEAnnotation();
                result.setSource("caller:" + eClass.getName()
                        + ":first=" + (first != null) + ":second=" + (second != null));
                return result;
            };
        }
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
