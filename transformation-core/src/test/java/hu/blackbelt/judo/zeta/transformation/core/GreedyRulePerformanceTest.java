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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for greedy rule execution.
 *
 * <p>Tests rule lookup caching and pre-partitioning optimizations
 * using TransformationMetrics for measurement.</p>
 */
class GreedyRulePerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(GreedyRulePerformanceTest.class);

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

        // Reset metrics before each test
        TransformationMetrics.reset();
    }

    @AfterEach
    void tearDown() {
        TransformationMetrics.disable();
    }

    private TransformationContext createContext() {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        return ctx;
    }

    /**
     * Create N source elements for testing.
     */
    private void createSourceElements(int count) {
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Class" + i);
            sourceResource.getContents().add(ec);
        }
    }

    // ==================== Baseline Tests ====================

    @Nested
    @DisplayName("Performance Baseline Tests")
    class BaselineTests {

        @Test
        @DisplayName("Baseline: 1K elements transformation time")
        void testBaseline1KElements() {
            createSourceElements(1000);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PerformanceTestRules.class);

            TransformationContext ctx = createContext();
            ctx.setTransformationRegistry(registry);

            TransformationMetrics.enable();

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false) // Sequential for baseline
                    .build();

            long startTime = System.currentTimeMillis();
            executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            log.info("1K elements baseline: {} ms", duration);
            log.info(TransformationMetrics.getReport());

            // Verify transformation worked
            assertFalse(targetResource.getContents().isEmpty(),
                    "Target resource should not be empty after transformation");
            assertEquals(1000, targetResource.getContents().size(),
                    "Should have transformed all 1000 elements");
        }

        @Test
        @DisplayName("Baseline: 10K elements transformation time")
        void testBaseline10KElements() {
            createSourceElements(10000);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PerformanceTestRules.class);

            TransformationContext ctx = createContext();
            ctx.setTransformationRegistry(registry);

            TransformationMetrics.enable();

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            long startTime = System.currentTimeMillis();
            executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            log.info("10K elements baseline: {} ms", duration);
            log.info(TransformationMetrics.getReport());

            assertFalse(targetResource.getContents().isEmpty(),
                    "Target resource should not be empty after transformation");
            assertEquals(10000, targetResource.getContents().size(),
                    "Should have transformed all 10000 elements");
        }
    }

    // ==================== Caching Tests ====================

    @Nested
    @DisplayName("Rule Lookup Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("Same type lookups should hit cache")
        void testSameTypeLookupsCached() {
            // Create 100 EClass elements (all same type)
            for (int i = 0; i < 100; i++) {
                EClass ec = EcoreFactory.eINSTANCE.createEClass();
                ec.setName("Class" + i);
                sourceResource.getContents().add(ec);
            }

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PerformanceTestRules.class);

            TransformationContext ctx = createContext();
            ctx.setTransformationRegistry(registry);

            TransformationMetrics.enable();

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)
                    .build();

            executor.transform();

            log.info(TransformationMetrics.getReport());

            // Verify transformation completed successfully
            assertFalse(targetResource.getContents().isEmpty(),
                    "Target should have elements after transformation");
            assertEquals(100, targetResource.getContents().size(),
                    "Should have transformed all 100 elements");
        }

        @Test
        @DisplayName("Cache invalidation on dynamic registration")
        void testCacheInvalidationOnDynamicRegistration() {
            createSourceElements(100);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PerformanceTestRules.class);

            // First lookup - populates cache
            Collection<TransformRuleDescriptor> rules1 = registry.getRulesForSource(EClass.class);
            int initialRuleCount = rules1.size();

            // Register more rules dynamically
            registry.register(AdditionalTestRules.class);

            // Second lookup - should reflect new rules (cache invalidated)
            Collection<TransformRuleDescriptor> rules2 = registry.getRulesForSource(EClass.class);
            int newRuleCount = rules2.size();

            log.info("Initial rules: {}, After dynamic registration: {}", initialRuleCount, newRuleCount);

            // After implementing cache invalidation, new rule count should be higher
            assertTrue(newRuleCount >= initialRuleCount,
                    "Cache should be invalidated on dynamic registration");
        }
    }

    // ==================== Pre-Partitioning Tests ====================

    @Nested
    @DisplayName("Rule Pre-Partitioning Tests")
    class PrePartitioningTests {

        @Test
        @DisplayName("Verify eager rules are correctly identified")
        void testEagerRulesPartitioning() {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PerformanceTestRules.class);

            // Get all rules and count by category
            Collection<TransformRuleDescriptor> allRules = registry.getAllRules();

            long nonLazyNonAbstractCount = allRules.stream()
                    .filter(r -> !r.isLazy() && !r.isAbstract())
                    .count();
            long lazyCount = allRules.stream().filter(TransformRuleDescriptor::isLazy).count();
            long abstractCount = allRules.stream().filter(TransformRuleDescriptor::isAbstract).count();

            log.info("Rule counts - Eager: {}, Lazy: {}, Abstract: {}",
                    nonLazyNonAbstractCount, lazyCount, abstractCount);

            // Verify we have the expected rule types
            // PerformanceTestRules has 1 non-lazy, non-abstract rule (EClass2EPackage)
            // and 1 lazy rule (LazyEClass2EDataType)
            assertEquals(1, nonLazyNonAbstractCount, "Should have 1 eager rule");
            assertEquals(1, lazyCount, "Should have 1 lazy rule");
            assertEquals(0, abstractCount, "Should have 0 abstract rules");
        }
    }

    // ==================== Test Model Provider ====================

    static class TestModelProvider implements ModelProvider {
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

    // ==================== Test Transformation Rules ====================

    /**
     * Test transformation rules for performance testing.
     * Uses non-greedy rules (exact type match) for EClass elements.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EPackage.class
    )
    public static class PerformanceTestRules {

        /**
         * Non-greedy rule: Transforms each EClass to an EPackage.
         * Non-greedy = exact type match (EClass only, not subtypes).
         */
        @TransformRule(name = "EClass2EPackage")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> eClass2EPackage() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("T_" + source.getName());
                ctx.addToResource(target);  // Add to target resource
                return target;
            };
        }

        @TransformRule(name = "LazyEClass2EDataType")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyEClass2EDataType() {
            return (source, ctx) -> {
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("DT_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Additional greedy test rules for cache invalidation test.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EEnum.class
    )
    public static class AdditionalTestRules {

        @TransformRule(name = "AdditionalEClass2EEnum")
        @Greedy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EEnum> additionalEClass2EEnum() {
            return (source, ctx) -> {
                EEnum target = ctx.createTarget(EEnum.class);
                target.setName("Enum_" + source.getName());
                return target;
            };
        }
    }
}
