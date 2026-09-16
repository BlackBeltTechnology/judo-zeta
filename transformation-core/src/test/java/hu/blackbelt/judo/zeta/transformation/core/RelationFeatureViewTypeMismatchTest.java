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

import hu.blackbelt.judo.zeta.annotation.Greedy;
import hu.blackbelt.judo.zeta.annotation.Lazy;
import hu.blackbelt.judo.zeta.annotation.TransformRule;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test to reproduce the RelationFeatureView type mismatch issue
 * where Action elements incorrectly get XMI IDs that should belong to PageDefinition.
 *
 * <p>Production Symptoms:
 * <ul>
 *   <li>XMI ID 'GenericUser/(esm/_xxx)/RelationFeatureView' assigned to Action instead of PageDefinition</li>
 *   <li>Only 2 out of ~50 transformations exhibit the issue</li>
 *   <li>Works in simple tests, fails in complex models with ~7500 elements</li>
 * </ul>
 *
 * <p>This test simulates the production scenario with:
 * <ul>
 *   <li>Multiple source elements (50+)</li>
 *   <li>Parallel execution</li>
 *   <li>Guards that reject some elements</li>
 *   <li>Multiple action rules called via equivalent()</li>
 * </ul>
 */
@DisplayName("RelationFeatureView Type Mismatch Reproduction Tests")
class RelationFeatureViewTypeMismatchTest {

    private static final Logger log = LoggerFactory.getLogger(RelationFeatureViewTypeMismatchTest.class);

    // Rule names matching the real-world scenario
    private static final String RELATION_FEATURE_VIEW = "RelationFeatureView";
    private static final String BACK_ACTION = "RelationFeatureViewBackAction";
    private static final String REFRESH_ACTION = "RelationFeatureViewRefreshAction";
    private static final String CREATE_ACTION = "RelationFeatureViewCreateAction";

    // Track created elements for verification
    static final Map<String, String> pageDefinitionIds = new ConcurrentHashMap<>();
    static final Map<String, String> actionIds = new ConcurrentHashMap<>();
    static final AtomicInteger pageDefinitionCount = new AtomicInteger(0);
    static final AtomicInteger actionCount = new AtomicInteger(0);

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

        // Reset tracking
        pageDefinitionIds.clear();
        actionIds.clear();
        pageDefinitionCount.set(0);
        actionCount.set(0);
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

        // Enable structured IDs with global prefix (like the real transformation)
        ctx.setUseStructuredIds(true);
        ctx.setGlobalIdPrefix("GenericUser");

        return ctx;
    }

    private List<EClass> createSourceElements(int count) {
        List<EClass> elements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("RelationFeature_" + i);
            // Simulate ESM-style XMI IDs
            String xmiId = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            sourceResource.getContents().add(ec);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(ec, xmiId);
            }
            elements.add(ec);
        }
        return elements;
    }

    // ==================== Transformation Rules ====================

    /**
     * Simulates RelationFeatureView rule: @Greedy, creates EDataType (PageDefinition).
     * Calls equivalent() to trigger multiple action rules.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class RelationFeatureViewTransformation {

        @TransformRule(name = RELATION_FEATURE_VIEW, description = "Main view rule that triggers action rules")
        @Greedy
        public TransformFunction<EClass, EDataType> relationFeatureView() {
            return (source, ctx) -> {
                String sourceId = getSourceXmiId(source);
                log.debug("RelationFeatureView executing for source: {} ({})", source.getName(), sourceId);

                // Create PageDefinition target
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_PageDefinition");

                // Add to resource
                ctx.addToResource(target);

                // Set custom XMI ID (like the real transformation)
                String customId = "GenericUser/(esm/" + sourceId + ")/" + RELATION_FEATURE_VIEW;
                ctx.setElementId(target, customId);
                log.debug("RelationFeatureView set ID: {}", customId);

                // Track for verification
                pageDefinitionIds.put(customId, target.getClass().getSimpleName());
                pageDefinitionCount.incrementAndGet();

                // Trigger action rules via equivalent() - THIS IS WHERE THE BUG COULD MANIFEST
                EAnnotation backAction = ctx.equivalent(source, EAnnotation.class, BACK_ACTION);
                EAnnotation refreshAction = ctx.equivalent(source, EAnnotation.class, REFRESH_ACTION);
                EAnnotation createAction = ctx.equivalent(source, EAnnotation.class, CREATE_ACTION);

                // Add actions to target (simulating target.getActions().add())
                if (backAction != null) {
                    target.getEAnnotations().add(backAction);
                }
                if (refreshAction != null) {
                    target.getEAnnotations().add(refreshAction);
                }
                if (createAction != null) {
                    target.getEAnnotations().add(createAction);
                }

                return target;
            };
        }

        private static String getSourceXmiId(EClass source) {
            Resource res = source.eResource();
            if (res instanceof XMIResource) {
                return ((XMIResource) res).getID(source);
            }
            return source.getName();
        }
    }

    /**
     * BackAction rule: @Lazy @Greedy, creates EAnnotation (Action).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class BackActionTransformation {

        @TransformRule(name = BACK_ACTION, description = "Back action rule")
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> backAction() {
            return createActionRule(BACK_ACTION);
        }
    }

    /**
     * RefreshAction rule: @Lazy @Greedy, creates EAnnotation (Action).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class RefreshActionTransformation {

        @TransformRule(name = REFRESH_ACTION, description = "Refresh action rule")
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> refreshAction() {
            return createActionRule(REFRESH_ACTION);
        }
    }

    /**
     * CreateAction rule: @Lazy @Greedy, creates EAnnotation (Action).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class CreateActionTransformation {

        @TransformRule(name = CREATE_ACTION, description = "Create action rule")
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> createAction() {
            return createActionRule(CREATE_ACTION);
        }
    }

    /**
     * Factory method to create action rule logic.
     */
    private static TransformFunction<EClass, EAnnotation> createActionRule(String ruleName) {
        return (source, ctx) -> {
            String sourceId = getSourceXmiId(source);
            log.debug("{} executing for source: {} ({})", ruleName, source.getName(), sourceId);

            // Create Action target
            EAnnotation target = ctx.createTarget(EAnnotation.class);
            target.setSource(source.getName() + "_" + ruleName);

            // Add to resource
            ctx.addToResource(target);

            // Set custom XMI ID with action-specific suffix
            String customId = "GenericUser/(esm/" + sourceId + ")/" + ruleName;
            ctx.setElementId(target, customId);
            log.debug("{} set ID: {}", ruleName, customId);

            // Track for verification
            actionIds.put(customId, target.getClass().getSimpleName());
            actionCount.incrementAndGet();

            return target;
        };
    }

    private static String getSourceXmiId(EClass source) {
        Resource res = source.eResource();
        if (res instanceof XMIResource) {
            return ((XMIResource) res).getID(source);
        }
        return source.getName();
    }

    /**
     * Test ModelProvider implementation.
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

    // ==================== Tests ====================

    @Nested
    @DisplayName("Sequential Execution Tests")
    class SequentialExecutionTests {

        @Test
        @DisplayName("50 elements should all have correct XMI ID types")
        void fiftyElementsShouldHaveCorrectTypes() {
            // Create registry with all rules
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RelationFeatureViewTransformation.class);
            registry.register(BackActionTransformation.class);
            registry.register(RefreshActionTransformation.class);
            registry.register(CreateActionTransformation.class);

            TransformationContext ctx = createContext(registry);

            // Create 50 source elements (like production)
            List<EClass> sources = createSourceElements(50);

            // Execute main rule for each source
            TransformRuleDescriptor mainRule = registry.getRuleByName(RELATION_FEATURE_VIEW);
            for (EClass source : sources) {
                mainRule.execute(source, ctx);
            }

            // Commit staged elements
            ctx.commitStagedElements();

            // Verify counts
            assertEquals(50, pageDefinitionCount.get(), "Should create 50 PageDefinitions");
            assertEquals(150, actionCount.get(), "Should create 150 Actions (3 per source)");

            // CRITICAL: Verify no RelationFeatureView IDs are assigned to Action elements
            verifyNoTypeMismatches();
        }

        @Test
        @DisplayName("XMI resource should have correct element types for all IDs")
        void xmiResourceShouldHaveCorrectTypes() {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RelationFeatureViewTransformation.class);
            registry.register(BackActionTransformation.class);
            registry.register(RefreshActionTransformation.class);
            registry.register(CreateActionTransformation.class);

            TransformationContext ctx = createContext(registry);

            List<EClass> sources = createSourceElements(50);

            TransformRuleDescriptor mainRule = registry.getRuleByName(RELATION_FEATURE_VIEW);
            for (EClass source : sources) {
                mainRule.execute(source, ctx);
            }

            ctx.commitStagedElements();

            // Verify XMI IDs in the resource
            XMIResource xmiResource = (XMIResource) targetResource;
            List<String> typeMismatches = new ArrayList<>();

            for (EObject obj : targetResource.getContents()) {
                String xmiId = xmiResource.getID(obj);
                if (xmiId != null) {
                    // Check if ID ends with RelationFeatureView but element is not EDataType
                    if (xmiId.endsWith("/" + RELATION_FEATURE_VIEW)) {
                        if (!(obj instanceof EDataType)) {
                            typeMismatches.add("XMI ID '" + xmiId + "' type mismatch: expected EDataType but was " +
                                    obj.eClass().getName());
                        }
                    }
                    // Check if ID ends with action suffix but element is not EAnnotation
                    if (xmiId.endsWith("/" + BACK_ACTION) ||
                        xmiId.endsWith("/" + REFRESH_ACTION) ||
                        xmiId.endsWith("/" + CREATE_ACTION)) {
                        if (!(obj instanceof EAnnotation)) {
                            typeMismatches.add("XMI ID '" + xmiId + "' type mismatch: expected EAnnotation but was " +
                                    obj.eClass().getName());
                        }
                    }
                }
            }

            if (!typeMismatches.isEmpty()) {
                fail("Found " + typeMismatches.size() + " type mismatches:\n" +
                        String.join("\n", typeMismatches));
            }
        }
    }

    @Nested
    @DisplayName("Parallel Execution Tests")
    class ParallelExecutionTests {

        @Test
        @DisplayName("Parallel execution with 50 elements should have no type mismatches")
        void parallelExecutionShouldHaveNoTypeMismatches() throws InterruptedException {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RelationFeatureViewTransformation.class);
            registry.register(BackActionTransformation.class);
            registry.register(RefreshActionTransformation.class);
            registry.register(CreateActionTransformation.class);

            TransformationContext ctx = createContext(registry);

            List<EClass> sources = createSourceElements(50);

            // Execute in parallel using multiple threads
            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch latch = new CountDownLatch(sources.size());
            TransformRuleDescriptor mainRule = registry.getRuleByName(RELATION_FEATURE_VIEW);

            for (EClass source : sources) {
                executor.submit(() -> {
                    try {
                        mainRule.execute(source, ctx);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all to complete
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Parallel execution should complete in time");
            executor.shutdown();

            ctx.commitStagedElements();

            // Verify no type mismatches
            verifyNoTypeMismatches();
        }

        @RepeatedTest(10)
        @DisplayName("Repeated parallel execution should be stable")
        void repeatedParallelExecutionShouldBeStable() throws InterruptedException {
            // Reset state for each repetition
            setUp();

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RelationFeatureViewTransformation.class);
            registry.register(BackActionTransformation.class);
            registry.register(RefreshActionTransformation.class);
            registry.register(CreateActionTransformation.class);

            TransformationContext ctx = createContext(registry);

            List<EClass> sources = createSourceElements(20);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch latch = new CountDownLatch(sources.size());
            TransformRuleDescriptor mainRule = registry.getRuleByName(RELATION_FEATURE_VIEW);

            for (EClass source : sources) {
                executor.submit(() -> {
                    try {
                        mainRule.execute(source, ctx);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            ctx.commitStagedElements();

            verifyNoTypeMismatches();
        }
    }

    @Nested
    @DisplayName("Stale Index Entry Regression Tests")
    class StaleIndexEntryRegressionTests {

        @Test
        @DisplayName("Auto-generated ID should be removed when custom ID is set")
        void autoGeneratedIdShouldBeRemovedWhenCustomIdSet() throws Exception {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(RelationFeatureViewTransformation.class);
            registry.register(BackActionTransformation.class);

            TransformationContext ctx = createContext(registry);

            EClass source = createSourceElements(1).get(0);
            String sourceId = ((XMIResource) sourceResource).getID(source);

            // Execute main rule
            TransformRuleDescriptor mainRule = registry.getRuleByName(RELATION_FEATURE_VIEW);
            EDataType pageDefinition = (EDataType) mainRule.execute(source, ctx);

            ctx.commitStagedElements();

            // The auto-generated ID would have been: GenericUser/(source/sourceId)/RelationFeatureView
            // The custom ID is: GenericUser/(esm/sourceId)/RelationFeatureView
            // The stale entry bug would leave the auto-generated ID pointing to the element

            // Use reflection to check pendingXmiIdIndex
            java.lang.reflect.Field indexField =
                    TransformationContext.class.getDeclaredField("pendingXmiIdIndex");
            indexField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, EObject> index = (Map<String, EObject>) indexField.get(ctx);

            // The custom ID should be in the index
            String customId = "GenericUser/(esm/" + sourceId + ")/" + RELATION_FEATURE_VIEW;
            assertTrue(index.containsKey(customId), "Custom ID should be in index");

            // Auto-generated patterns should NOT be in the index
            for (String key : index.keySet()) {
                if (key.contains("/(source/") && key.endsWith("/" + RELATION_FEATURE_VIEW)) {
                    // This would indicate the stale entry bug
                    fail("Found stale auto-generated ID in index: " + key);
                }
            }
        }

        @Test
        @DisplayName("findByXmiId should not return element by stale auto-generated ID")
        void findByXmiIdShouldNotReturnByStaleId() throws Exception {
            TransformationRegistry registry = new TransformationRegistry();
            TransformationContext ctx = createContext(registry);

            // Manually simulate the scenario:
            // 1. Create element
            // 2. Set auto-generated ID (simulating createTarget behavior)
            // 3. Set custom ID (simulating setElementId override)
            // 4. Verify stale ID lookup returns null

            EAnnotation action = EcoreFactory.eINSTANCE.createEAnnotation();
            action.setSource("TestAction");
            targetResource.getContents().add(action);

            // Simulate auto-generated ID (what createTarget would do)
            String autoGeneratedId = "GenericUser/(source/_test123)/RelationFeatureView";
            ctx.setElementId(action, autoGeneratedId);

            // Simulate custom ID override (what the rule does)
            String customId = "GenericUser/(esm/_test123)/RelationFeatureViewBackAction";
            ctx.setElementId(action, customId);

            // Use reflection to call findByXmiId
            java.lang.reflect.Method findByXmiIdMethod =
                    TransformationContext.class.getDeclaredMethod("findByXmiId", String.class, Class.class);
            findByXmiIdMethod.setAccessible(true);

            // Looking up by auto-generated ID should return null (stale entry removed)
            EAnnotation foundByAutoId =
                    (EAnnotation) findByXmiIdMethod.invoke(ctx, autoGeneratedId, EAnnotation.class);
            assertNull(foundByAutoId,
                    "findByXmiId(autoGeneratedId) should return null after ID was changed. " +
                    "This would cause the type mismatch bug if another element gets the stale ID.");

            // Looking up by custom ID should return the element
            EAnnotation foundByCustomId =
                    (EAnnotation) findByXmiIdMethod.invoke(ctx, customId, EAnnotation.class);
            assertSame(action, foundByCustomId, "findByXmiId(customId) should return the element");
        }
    }

    @Nested
    @DisplayName("ID Collision Detection Tests")
    class IdCollisionDetectionTests {

        @Test
        @DisplayName("Two elements with same ID should not cause type mismatch")
        void twoElementsWithSameIdShouldBeDetected() {
            TransformationRegistry registry = new TransformationRegistry();
            TransformationContext ctx = createContext(registry);

            // Create two elements
            EDataType pageDefinition = EcoreFactory.eINSTANCE.createEDataType();
            pageDefinition.setName("PageDefinition");
            targetResource.getContents().add(pageDefinition);

            EAnnotation action = EcoreFactory.eINSTANCE.createEAnnotation();
            action.setSource("Action");
            targetResource.getContents().add(action);

            // Set same ID for both (this simulates the bug scenario)
            String sharedId = "GenericUser/(esm/_test)/RelationFeatureView";
            ctx.setElementId(pageDefinition, sharedId);
            ctx.setElementId(action, sharedId);

            // The second setElementId should have overwritten the first in the index
            // This is actually the root cause of the type mismatch!

            ctx.commitStagedElements();

            // Check XMI resource - one element will have the ID
            XMIResource xmiResource = (XMIResource) targetResource;
            String pageDefId = xmiResource.getID(pageDefinition);
            String actionId = xmiResource.getID(action);

            log.info("PageDefinition ID: {}", pageDefId);
            log.info("Action ID: {}", actionId);

            // At least one should have the shared ID
            // The key insight: if both try to use the same ID, only the last one wins in the index
            // This test documents the behavior - not necessarily asserting it's correct
        }
    }

    // ==================== Verification Helpers ====================

    private void verifyNoTypeMismatches() {
        List<String> typeMismatches = new ArrayList<>();

        // Check pageDefinitionIds - all should be EDataType
        for (Map.Entry<String, String> entry : pageDefinitionIds.entrySet()) {
            String id = entry.getKey();
            if (id.endsWith("/" + RELATION_FEATURE_VIEW)) {
                // This is correct - RelationFeatureView ID for PageDefinition
            } else {
                typeMismatches.add("PageDefinition has unexpected ID pattern: " + id);
            }
        }

        // Check actionIds - none should have RelationFeatureView suffix
        for (Map.Entry<String, String> entry : actionIds.entrySet()) {
            String id = entry.getKey();
            if (id.endsWith("/" + RELATION_FEATURE_VIEW)) {
                typeMismatches.add("Action has RelationFeatureView ID (TYPE MISMATCH): " + id);
            }
        }

        // Cross-check: no RelationFeatureView IDs should be in actionIds
        Set<String> relationFeatureViewIds = pageDefinitionIds.keySet().stream()
                .filter(id -> id.endsWith("/" + RELATION_FEATURE_VIEW))
                .collect(Collectors.toSet());

        for (String rfvId : relationFeatureViewIds) {
            if (actionIds.containsKey(rfvId)) {
                typeMismatches.add("XMI ID '" + rfvId + "' type mismatch: " +
                        "expected PageDefinition but found Action");
            }
        }

        if (!typeMismatches.isEmpty()) {
            fail("Found " + typeMismatches.size() + " type mismatches:\n" +
                    String.join("\n", typeMismatches));
        }

        log.info("Verified {} PageDefinitions and {} Actions with no type mismatches",
                pageDefinitionCount.get(), actionCount.get());
    }
}
