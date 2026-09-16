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
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests to reproduce and fix the equivalentDiscriminated() bug where
 * the SAME instance is returned for different discriminators.
 *
 * <h2>Bug Description:</h2>
 * <p>When calculateActions (or any utility method) is called multiple times
 * with the same source element but different discriminators:</p>
 * <ol>
 *   <li>First call with discriminator "d1" → creates Action, sets XMI ID</li>
 *   <li>Second call with discriminator "d2" → SHOULD create NEW Action clone</li>
 *   <li>ACTUAL: Returns the SAME Action, overwriting XMI ID</li>
 * </ol>
 *
 * <h2>Expected Behavior (ETL):</h2>
 * <p>Creates separate Action instances for each unique (source, ruleName, discriminator) tuple.</p>
 *
 * <h2>Actual Behavior (ZETA bug):</h2>
 * <p>Returns the SAME Action instance for different discriminators of the same source.</p>
 */
@DisplayName("equivalentDiscriminated() Uniqueness Tests (TDD)")
class EquivalentDiscriminatedUniquenessTest {

    private static final Logger log = LoggerFactory.getLogger(EquivalentDiscriminatedUniquenessTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext ctx;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        // Reset counters
        ActionCreatorRule.executionCount.set(0);
        ActionCreatorRule.createdActions.clear();
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        ctx.setUseStructuredIds(true); // Enable structured IDs for XMI ID tracking
        return ctx;
    }

    // ==================== Core Bug Reproduction Tests ====================

    /**
     * Test P0: Different discriminators MUST return different object instances.
     *
     * This is the core bug: calling equivalentDiscriminated with the same source
     * and ruleName but different discriminators should return different cloned objects.
     */
    @Test
    @DisplayName("P0: Different discriminators return different instances")
    void differentDiscriminatorsReturnDifferentInstances() {
        // Create source element
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("Entity1");
        sourceResource.getContents().add(sourceEntity);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // First call - discriminator "page1"
        EAnnotation action1 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page1");

        // Second call - discriminator "page2" (DIFFERENT discriminator)
        EAnnotation action2 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page2");

        // Third call - discriminator "page3" (ANOTHER different discriminator)
        EAnnotation action3 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page3");

        // CRITICAL ASSERTION: Different discriminators MUST return different instances
        assertNotNull(action1, "First discriminator should return non-null");
        assertNotNull(action2, "Second discriminator should return non-null");
        assertNotNull(action3, "Third discriminator should return non-null");

        assertNotSame(action1, action2,
                "BUG: Different discriminators ('page1' vs 'page2') returned the SAME object instance! " +
                "Expected separate cloned instances.");
        assertNotSame(action1, action3,
                "BUG: Different discriminators ('page1' vs 'page3') returned the SAME object instance!");
        assertNotSame(action2, action3,
                "BUG: Different discriminators ('page2' vs 'page3') returned the SAME object instance!");

        log.info("Action1 identity: {}", System.identityHashCode(action1));
        log.info("Action2 identity: {}", System.identityHashCode(action2));
        log.info("Action3 identity: {}", System.identityHashCode(action3));
    }

    /**
     * Test P0: Same discriminator MUST return the same cached instance.
     */
    @Test
    @DisplayName("P0: Same discriminator returns same cached instance")
    void sameDiscriminatorReturnsSameCachedInstance() {
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("Entity1");
        sourceResource.getContents().add(sourceEntity);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // First call with discriminator "page1"
        EAnnotation action1 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page1");

        // Second call with SAME discriminator "page1"
        EAnnotation action1Cached = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page1");

        // SAME discriminator should return SAME cached instance
        assertSame(action1, action1Cached,
                "Same discriminator should return the same cached instance");
    }

    /**
     * Test P0: XMI IDs should be unique per discriminator.
     */
    @Test
    @DisplayName("P0: XMI IDs are unique per discriminator")
    void xmiIdsAreUniquePerDiscriminator() {
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("Entity1");
        sourceResource.getContents().add(sourceEntity);

        // Set source XMI ID for structured ID generation
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(sourceEntity, "source_entity_1");
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Create actions with different discriminators
        EAnnotation action1 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page1_discriminator");
        EAnnotation action2 = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "page2_discriminator");

        // Get XMI IDs
        String id1 = getXmiId(action1);
        String id2 = getXmiId(action2);

        assertNotNull(id1, "Action1 should have an XMI ID");
        assertNotNull(id2, "Action2 should have an XMI ID");

        // XMI IDs must be different for different discriminators
        assertNotEquals(id1, id2,
                "BUG: XMI IDs are the same for different discriminators! " +
                "ID1='" + id1 + "', ID2='" + id2 + "'. " +
                "This happens when the same object is returned for different discriminators.");

        // IDs should contain the discriminator
        assertTrue(id1.contains("page1_discriminator") || id1.contains("discriminator"),
                "XMI ID should contain discriminator info: " + id1);
        assertTrue(id2.contains("page2_discriminator") || id2.contains("discriminator"),
                "XMI ID should contain discriminator info: " + id2);

        log.info("Action1 XMI ID: {}", id1);
        log.info("Action2 XMI ID: {}", id2);
    }

    private String getXmiId(EObject obj) {
        String id = ctx.getPendingXmiId(obj);
        if (id == null && targetResource instanceof XMIResource) {
            id = ((XMIResource) targetResource).getID(obj);
        }
        return id;
    }

    /**
     * Test P0: Simulates the exact production scenario - utility method called
     * multiple times with different page discriminators.
     */
    @Test
    @DisplayName("P0: Production scenario - calculateActions pattern")
    void productionScenarioCalculateActionsPattern() {
        // Create the source entity that will be transformed
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("SharedEntity");
        sourceResource.getContents().add(sourceEntity);

        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(sourceEntity, "shared_entity_id");
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Simulate calling calculateActions from different page contexts
        // Each page has its own discriminator (typically the page's XMI ID)
        String[] pageDiscriminators = {
            "_jMBmQHHqEe-ioL1NC0oO1A",  // Page 1's XMI ID
            "_F3_n8JhTEe6Lt9urGIH1JA",  // Page 2's XMI ID
            "_K9pRwHHqEe-ioL1NC0oO1A"   // Page 3's XMI ID
        };

        // Utility method pattern: calculateActions(entity, pageDiscriminator)
        List<EAnnotation> actions = new ArrayList<>();
        Map<String, String> actionToXmiId = new HashMap<>();

        for (String pageDiscriminator : pageDiscriminators) {
            // This simulates: Action action = calculateActions(entity, page);
            // Which internally calls: ctx.equivalentDiscriminated(entity, Action.class, "CreateAction", page.getXmiId());
            EAnnotation action = ctx.equivalentDiscriminated(
                    sourceEntity, EAnnotation.class, "CreateAction", pageDiscriminator);

            actions.add(action);
            String xmiId = getXmiId(action);
            actionToXmiId.put(pageDiscriminator, xmiId);

            log.info("Page discriminator: {} -> Action identity: {}, XMI ID: {}",
                    pageDiscriminator, System.identityHashCode(action), xmiId);
        }

        // CRITICAL: Each page should get a DIFFERENT action instance
        Set<Integer> uniqueIdentities = new HashSet<>();
        for (EAnnotation action : actions) {
            uniqueIdentities.add(System.identityHashCode(action));
        }

        assertEquals(pageDiscriminators.length, uniqueIdentities.size(),
                "BUG: Expected " + pageDiscriminators.length + " unique Action instances " +
                "(one per page discriminator), but got " + uniqueIdentities.size() + ". " +
                "This means the same Action object is being reused for different pages!");

        // CRITICAL: Each page should have a UNIQUE XMI ID
        Set<String> uniqueXmiIds = new HashSet<>(actionToXmiId.values());
        assertEquals(pageDiscriminators.length, uniqueXmiIds.size(),
                "BUG: Expected " + pageDiscriminators.length + " unique XMI IDs, but got " +
                uniqueXmiIds.size() + ". XMI IDs: " + actionToXmiId);
    }

    /**
     * Test P1: Verify the lazy rule executes only ONCE per source.
     * The discriminated calls should clone from the single lazy rule result.
     */
    @Test
    @DisplayName("P1: Lazy rule executes once, discriminated calls clone")
    void lazyRuleExecutesOnceDiscriminatedClonesClone() {
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("Entity1");
        sourceResource.getContents().add(sourceEntity);

        ActionCreatorRule.executionCount.set(0);
        ActionCreatorRule.createdActions.clear();

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Call with 5 different discriminators
        for (int i = 1; i <= 5; i++) {
            ctx.equivalentDiscriminated(
                    sourceEntity, EAnnotation.class, "CreateAction", "disc" + i);
        }

        // Lazy rule should execute ONLY ONCE (to create the original)
        // The discriminated variants are CLONES of that original
        assertEquals(1, ActionCreatorRule.executionCount.get(),
                "Lazy rule should execute exactly once per source element, " +
                "discriminated variants should be clones");
    }

    /**
     * Test P1: Multiple source elements with multiple discriminators.
     */
    @Test
    @DisplayName("P1: Multiple sources with multiple discriminators")
    void multipleSourcesWithMultipleDiscriminators() {
        // Create 3 source elements
        List<EClass> sources = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Entity" + i);
            sourceResource.getContents().add(source);
            sources.add(source);
        }

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // For each source, create actions with different discriminators
        String[] discriminators = {"pageA", "pageB", "pageC"};

        Map<String, EAnnotation> allActions = new HashMap<>();

        for (EClass source : sources) {
            for (String disc : discriminators) {
                String key = source.getName() + "_" + disc;
                EAnnotation action = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "CreateAction", disc);
                allActions.put(key, action);
            }
        }

        // Verify: Each (source, discriminator) combination should have unique instance
        Set<Integer> uniqueIdentities = new HashSet<>();
        for (EAnnotation action : allActions.values()) {
            uniqueIdentities.add(System.identityHashCode(action));
        }

        int expectedUnique = sources.size() * discriminators.length;
        assertEquals(expectedUnique, uniqueIdentities.size(),
                "Expected " + expectedUnique + " unique Action instances " +
                "(3 sources × 3 discriminators), but got " + uniqueIdentities.size());
    }

    /**
     * Test P1: Interleaved calls - mix of same and different discriminators.
     */
    @Test
    @DisplayName("P1: Interleaved discriminator calls")
    void interleavedDiscriminatorCalls() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Entity1");
        sourceResource.getContents().add(source);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Interleaved pattern: d1, d2, d1, d3, d2, d1
        EAnnotation a1_first = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d1");
        EAnnotation a2_first = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d2");
        EAnnotation a1_second = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d1");
        EAnnotation a3_first = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d3");
        EAnnotation a2_second = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d2");
        EAnnotation a1_third = ctx.equivalentDiscriminated(source, EAnnotation.class, "CreateAction", "d1");

        // Same discriminator = same instance (caching)
        assertSame(a1_first, a1_second, "Same discriminator 'd1' should return cached instance");
        assertSame(a1_first, a1_third, "Same discriminator 'd1' should return cached instance");
        assertSame(a2_first, a2_second, "Same discriminator 'd2' should return cached instance");

        // Different discriminators = different instances
        assertNotSame(a1_first, a2_first, "Different discriminators should return different instances");
        assertNotSame(a1_first, a3_first, "Different discriminators should return different instances");
        assertNotSame(a2_first, a3_first, "Different discriminators should return different instances");
    }

    /**
     * Test P0: Verify mutation of returned object doesn't affect other discriminators.
     * This is the EXACT production bug pattern.
     */
    @Test
    @DisplayName("P0: Mutation of discriminated result doesn't affect other discriminators")
    void mutationOfDiscriminatedResultDoesNotAffectOtherDiscriminators() {
        EClass sourceEntity = EcoreFactory.eINSTANCE.createEClass();
        sourceEntity.setName("SharedOperationForm");
        sourceResource.getContents().add(sourceEntity);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Get action for discriminator "pageA"
        EAnnotation actionA = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "pageA");
        String originalSourceA = actionA.getSource();

        // Mutate actionA (production pattern: action.setName(action.getName() + "::relationName"))
        actionA.setSource(actionA.getSource() + "::relationA");

        // Get action for discriminator "pageB" (DIFFERENT discriminator)
        EAnnotation actionB = ctx.equivalentDiscriminated(
                sourceEntity, EAnnotation.class, "CreateAction", "pageB");

        // CRITICAL: actionB should NOT have the mutation from actionA
        assertNotSame(actionA, actionB,
                "BUG: Different discriminators returned the SAME object instance! " +
                "Mutations on one discriminator affect others.");

        assertNotEquals(actionA.getSource(), actionB.getSource(),
                "BUG: actionB has the mutation from actionA! " +
                "Expected actionB.source to be unchanged, but got: " + actionB.getSource());

        // Verify actionA has mutation
        assertTrue(actionA.getSource().contains("::relationA"),
                "actionA should have the mutation");

        // Verify actionB does NOT have mutation
        assertFalse(actionB.getSource().contains("::relationA"),
                "actionB should NOT have the mutation from actionA. Source: " + actionB.getSource());

        log.info("actionA.source: {}", actionA.getSource());
        log.info("actionB.source: {}", actionB.getSource());
    }

    /**
     * Test P0: Multiple accumulators - simulates ETL pattern where names accumulate.
     */
    @Test
    @DisplayName("P0: ETL accumulation pattern - multiple callers append to name")
    void etlAccumulationPattern() {
        EClass sharedOperationForm = EcoreFactory.eINSTANCE.createEClass();
        sharedOperationForm.setName("SharedOperationForm");
        sourceResource.getContents().add(sharedOperationForm);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Same page discriminator, but called from different contexts (different relations)
        // This simulates the ETL pattern:
        //   action = self.equivalentDiscriminated("Rule", discriminator)
        //   action.name += "::" + relation.name
        String pageDiscriminator = "_page123";

        // First context: relation "customers"
        EAnnotation action1 = ctx.equivalentDiscriminated(
                sharedOperationForm, EAnnotation.class, "CreateAction", pageDiscriminator);
        String beforeMutation1 = action1.getSource();
        action1.setSource(action1.getSource() + "::customers");
        String afterMutation1 = action1.getSource();

        // Second context: relation "orders" (SAME page discriminator!)
        EAnnotation action2 = ctx.equivalentDiscriminated(
                sharedOperationForm, EAnnotation.class, "CreateAction", pageDiscriminator);

        // With same discriminator, we expect the SAME cached object
        assertSame(action1, action2,
                "Same discriminator should return the same cached object");

        // But this means action2 already has the "::customers" mutation!
        // This is the expected behavior for SAME discriminator - they share the object
        assertEquals(action1.getSource(), action2.getSource(),
                "Same object, same source value");

        // Now use a DIFFERENT discriminator (different page)
        String otherPageDiscriminator = "_page456";
        EAnnotation action3 = ctx.equivalentDiscriminated(
                sharedOperationForm, EAnnotation.class, "CreateAction", otherPageDiscriminator);

        // action3 should be a DIFFERENT object (cloned)
        assertNotSame(action1, action3,
                "BUG: Different discriminators returned the SAME object!");

        // action3 should NOT have the mutations from action1/action2
        assertFalse(action3.getSource().contains("::customers"),
                "BUG: action3 has mutation from action1! This means equivalentDiscriminated " +
                "returned the same object for different discriminators. Source: " + action3.getSource());

        log.info("action1 (pageDiscriminator={}) source: {}", pageDiscriminator, action1.getSource());
        log.info("action3 (otherPageDiscriminator={}) source: {}", otherPageDiscriminator, action3.getSource());
    }

    /**
     * Test P0: Null discriminator returns the original without cloning (ETL semantics).
     * This is documented behavior: "If no discriminator, return the original without cloning"
     *
     * IMPORTANT: Clones are made from the CURRENT state of the original. If you mutate
     * the original before creating clones, those mutations propagate to all clones!
     */
    @Test
    @DisplayName("P0: Null discriminator returns original (no clone)")
    void nullDiscriminatorReturnsOriginal() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Entity");
        sourceResource.getContents().add(source);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // First call with NULL discriminator - should return the original
        EAnnotation originalAction = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", null);
        assertNotNull(originalAction);

        // Second call with NULL discriminator - should return SAME original
        EAnnotation sameOriginal = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", null);
        assertSame(originalAction, sameOriginal,
                "Null discriminator should return the same original instance");

        // Create clones BEFORE mutating the original - they should be clean
        EAnnotation cloneBeforeMutation = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", "disc_before");

        // Now mutate the original
        originalAction.setSource(originalAction.getSource() + "::mutated");

        // Third call with NULL discriminator - returns same mutated object
        EAnnotation stillSame = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", null);
        assertSame(originalAction, stillSame);
        assertTrue(stillSame.getSource().contains("::mutated"),
                "Null discriminator returns original, which is now mutated");

        // Clone created BEFORE mutation should NOT have the mutation
        assertFalse(cloneBeforeMutation.getSource().contains("::mutated"),
                "Clone created before mutation should not have the mutation");

        // Clone created AFTER mutation WILL have the mutation (clones from current state)
        EAnnotation cloneAfterMutation = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", "disc_after");
        assertNotSame(originalAction, cloneAfterMutation,
                "Non-null discriminator should return a different (cloned) object");
        assertTrue(cloneAfterMutation.getSource().contains("::mutated"),
                "Clone created AFTER mutation inherits the mutation (expected behavior)");

        // But each clone is independent - mutating one doesn't affect others
        cloneAfterMutation.setSource(cloneAfterMutation.getSource() + "::extra");
        assertFalse(cloneBeforeMutation.getSource().contains("::extra"),
                "Clones are independent - mutations don't cross");

        log.info("Original (null disc): {}", originalAction.getSource());
        log.info("Clone before mutation: {}", cloneBeforeMutation.getSource());
        log.info("Clone after mutation: {}", cloneAfterMutation.getSource());
    }

    /**
     * Test P1: Empty string discriminator behaves like regular discriminator (not null).
     */
    @Test
    @DisplayName("P1: Empty string discriminator is NOT null")
    void emptyStringDiscriminatorIsNotNull() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Entity");
        sourceResource.getContents().add(source);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActionCreatorRule.class);
        createContext(registry);

        // Empty string discriminator
        EAnnotation emptyDisc = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", "");

        // Null discriminator (returns original)
        EAnnotation nullDisc = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", null);

        // They should be different objects (empty string creates clone, null returns original)
        assertNotSame(emptyDisc, nullDisc,
                "Empty string discriminator should NOT be treated as null");

        // Another empty string should return same cached clone
        EAnnotation emptyDisc2 = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "CreateAction", "");
        assertSame(emptyDisc, emptyDisc2,
                "Same (empty) discriminator should return cached object");

        log.info("Empty disc identity: {}", System.identityHashCode(emptyDisc));
        log.info("Null disc identity: {}", System.identityHashCode(nullDisc));
    }

    // ==================== Transformation Rules ====================

    /**
     * Lazy rule that creates the base Action (EAnnotation) which will be cloned
     * for discriminated variants.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ActionCreatorRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final List<EAnnotation> createdActions = Collections.synchronizedList(new ArrayList<>());

        @TransformRule(name = "CreateAction")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> createActionRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("Action_" + source.getName());
                createdActions.add(action);

                return action;
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
