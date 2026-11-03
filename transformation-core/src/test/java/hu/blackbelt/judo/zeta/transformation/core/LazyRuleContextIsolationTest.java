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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for @Lazy rule context isolation.
 *
 * <p>Verifies that when a @Lazy rule is triggered via ctx.equivalent() from within
 * another rule's transform function, the currentExecutingRule ThreadLocal is properly
 * updated before the target rule's transform function executes.</p>
 *
 * <p>Issue: When a parent rule calls ctx.equivalent(source, "ChildRuleName"), the child
 * rule's createTarget() should generate XMI IDs using the CHILD rule's name, not the
 * caller's rule name.</p>
 *
 * <p>Test Specification Reference: ZETA Framework JUnit Test Specification: @Lazy Rule Context Pollution</p>
 */
@DisplayName("@Lazy Rule Context Isolation Tests")
class LazyRuleContextIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(LazyRuleContextIsolationTest.class);

    // Rule names for tests
    private static final String PARENT_RULE = "ParentRule";
    private static final String CHILD_RULE = "ChildRule";
    private static final String GRANDPARENT_RULE = "GrandparentRule";
    private static final String CHILD_RULE_1 = "ChildRule1";
    private static final String CHILD_RULE_2 = "ChildRule2";
    private static final String CHILD_RULE_3 = "ChildRule3";

    // Capture currentExecutingRule values during execution
    private static final AtomicReference<String> capturedParentRule = new AtomicReference<>();
    private static final AtomicReference<String> capturedChildRule = new AtomicReference<>();
    private static final AtomicReference<String> capturedGrandparentRule = new AtomicReference<>();
    private static final AtomicReference<String> capturedChildRule1 = new AtomicReference<>();
    private static final AtomicReference<String> capturedChildRule2 = new AtomicReference<>();
    private static final AtomicReference<String> capturedChildRule3 = new AtomicReference<>();

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

        // Reset all captured values
        capturedParentRule.set(null);
        capturedChildRule.set(null);
        capturedGrandparentRule.set(null);
        capturedChildRule1.set(null);
        capturedChildRule2.set(null);
        capturedChildRule3.set(null);
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
        ctx.setUseStructuredIds(true);

        return ctx;
    }

    private EClass createSourceElement(String name, String xmiId) {
        EClass ec = EcoreFactory.eINSTANCE.createEClass();
        ec.setName(name);
        sourceResource.getContents().add(ec);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(ec, xmiId);
        }
        return ec;
    }

    // ==================== Test 1: Basic @Lazy Rule Context Isolation ====================

    /**
     * Parent rule that calls child rule via equivalent().
     * Target: EDataType (simulating ParentTarget)
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class BasicParentTransformation {

        @TransformRule(name = PARENT_RULE)
        @Greedy
        public TransformFunction<EClass, EDataType> parentRule() {
            return (source, ctx) -> {
                // Capture currentExecutingRule
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedParentRule.set(current != null ? current.getName() : "null");
                log.debug("[PARENT] currentExecutingRule = {}", capturedParentRule.get());

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Parent");
                ctx.addToResource(target);

                // Trigger child rule via equivalent()
                EAnnotation child = ctx.equivalent(source, EAnnotation.class, CHILD_RULE);
                log.debug("[PARENT] Got child: {}", child != null ? child.getSource() : "null");

                return target;
            };
        }
    }

    /**
     * Child rule triggered by parent via equivalent().
     * Target: EAnnotation (simulating ChildTarget)
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class BasicChildTransformation {

        @TransformRule(name = CHILD_RULE)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> childRule() {
            return (source, ctx) -> {
                // CRITICAL: Capture currentExecutingRule - should be CHILD_RULE, not PARENT_RULE
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedChildRule.set(current != null ? current.getName() : "null");
                log.debug("[CHILD] currentExecutingRule = {}", capturedChildRule.get());

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource(source.getName() + "_Child");
                ctx.addToResource(target);

                return target;
            };
        }
    }

    @Test
    @DisplayName("Test 1: Basic @Lazy Rule Context Isolation")
    void testBasicLazyRuleContextIsolation() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(BasicParentTransformation.class);
        registry.register(BasicChildTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("TestSource", "_testId");

        // Execute parent rule (which triggers child rule via equivalent())
        TransformRuleDescriptor parentRule = registry.getRuleByName(PARENT_RULE);
        EDataType parent = (EDataType) parentRule.execute(source, ctx);

        // Get child that was created
        EAnnotation child = ctx.equivalent(source, EAnnotation.class, CHILD_RULE);

        // Commit and get XMI IDs
        ctx.commitStagedElements();
        XMIResource xmiResource = (XMIResource) targetResource;
        String parentId = xmiResource.getID(parent);
        String childId = xmiResource.getID(child);

        log.info("Parent XMI ID: {}", parentId);
        log.info("Child XMI ID: {}", childId);
        log.info("Captured parent rule context: {}", capturedParentRule.get());
        log.info("Captured child rule context: {}", capturedChildRule.get());

        // Verify XMI IDs are DIFFERENT
        assertNotEquals(parentId, childId,
                "Parent and Child should have different XMI IDs");

        // Verify XMI IDs contain correct rule names
        assertTrue(parentId.contains(PARENT_RULE),
                "Parent ID should contain 'ParentRule', but was: " + parentId);
        assertTrue(childId.contains(CHILD_RULE),
                "Child ID should contain 'ChildRule', but was: " + childId);

        // Verify captured currentExecutingRule values
        assertEquals(PARENT_RULE, capturedParentRule.get(),
                "currentExecutingRule should be 'ParentRule' during ParentRule execution");
        assertEquals(CHILD_RULE, capturedChildRule.get(),
                "currentExecutingRule should be 'ChildRule' during ChildRule execution");
    }

    // ==================== Test 2: Nested @Lazy Rule Calls ====================

    /**
     * Grandparent rule (top level).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClassifier.class)
    public static class NestedGrandparentTransformation {

        @TransformRule(name = GRANDPARENT_RULE)
        @Greedy
        public TransformFunction<EClass, EClassifier> grandparentRule() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedGrandparentRule.set(current != null ? current.getName() : "null");
                log.debug("[GRANDPARENT] currentExecutingRule = {}", capturedGrandparentRule.get());

                EClassifier target = ctx.createTarget(EClass.class);
                ((EClass) target).setName(source.getName() + "_Grandparent");
                ctx.addToResource(target);

                // Trigger parent rule (which will trigger child rule)
                ctx.equivalent(source, EDataType.class, PARENT_RULE);

                return target;
            };
        }
    }

    /**
     * Parent rule (middle level) - @Lazy.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class NestedParentTransformation {

        @TransformRule(name = PARENT_RULE)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EDataType> parentRule() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedParentRule.set(current != null ? current.getName() : "null");
                log.debug("[PARENT-NESTED] currentExecutingRule = {}", capturedParentRule.get());

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Parent");
                ctx.addToResource(target);

                // Trigger child rule
                ctx.equivalent(source, EAnnotation.class, CHILD_RULE);

                return target;
            };
        }
    }

    /**
     * Child rule (bottom level) - @Lazy.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class NestedChildTransformation {

        @TransformRule(name = CHILD_RULE)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> childRule() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedChildRule.set(current != null ? current.getName() : "null");
                log.debug("[CHILD-NESTED] currentExecutingRule = {}", capturedChildRule.get());

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource(source.getName() + "_Child");
                ctx.addToResource(target);

                return target;
            };
        }
    }

    @Test
    @DisplayName("Test 2: Nested @Lazy Rule Calls (3 levels)")
    void testNestedLazyRuleContextIsolation() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(NestedGrandparentTransformation.class);
        registry.register(NestedParentTransformation.class);
        registry.register(NestedChildTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("TestSource", "_testId");

        // Execute grandparent rule (triggers parent → child)
        TransformRuleDescriptor grandparentRule = registry.getRuleByName(GRANDPARENT_RULE);
        EClassifier grandparent = (EClassifier) grandparentRule.execute(source, ctx);

        // Get all elements
        EDataType parent = ctx.equivalent(source, EDataType.class, PARENT_RULE);
        EAnnotation child = ctx.equivalent(source, EAnnotation.class, CHILD_RULE);

        // Commit and get XMI IDs
        ctx.commitStagedElements();
        XMIResource xmiResource = (XMIResource) targetResource;
        String grandparentId = xmiResource.getID(grandparent);
        String parentId = xmiResource.getID(parent);
        String childId = xmiResource.getID(child);

        log.info("Grandparent XMI ID: {}", grandparentId);
        log.info("Parent XMI ID: {}", parentId);
        log.info("Child XMI ID: {}", childId);

        // Verify all XMI IDs are DIFFERENT
        Set<String> ids = new HashSet<>();
        ids.add(grandparentId);
        ids.add(parentId);
        ids.add(childId);
        assertEquals(3, ids.size(), "All 3 elements should have unique XMI IDs");

        // Verify each ID contains its own rule name
        assertTrue(grandparentId.contains(GRANDPARENT_RULE),
                "Grandparent ID should contain 'GrandparentRule'");
        assertTrue(parentId.contains(PARENT_RULE),
                "Parent ID should contain 'ParentRule'");
        assertTrue(childId.contains(CHILD_RULE),
                "Child ID should contain 'ChildRule'");

        // Verify captured currentExecutingRule values
        assertEquals(GRANDPARENT_RULE, capturedGrandparentRule.get(),
                "currentExecutingRule should be 'GrandparentRule' during GrandparentRule execution");
        assertEquals(PARENT_RULE, capturedParentRule.get(),
                "currentExecutingRule should be 'ParentRule' during ParentRule execution");
        assertEquals(CHILD_RULE, capturedChildRule.get(),
                "currentExecutingRule should be 'ChildRule' during ChildRule execution");
    }

    // ==================== Test 3: Multiple @Lazy Rules from Same Parent ====================

    /**
     * Parent rule that calls multiple child rules via equivalent().
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClassifier.class)
    public static class MultiChildParentTransformation {

        @TransformRule(name = PARENT_RULE)
        @Greedy
        public TransformFunction<EClass, EClassifier> parentRule() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedParentRule.set(current != null ? current.getName() : "null");

                EClassifier target = ctx.createTarget(EClass.class);
                ((EClass) target).setName(source.getName() + "_Parent");
                ctx.addToResource(target);

                // Trigger multiple child rules
                ctx.equivalent(source, EDataType.class, CHILD_RULE_1);
                ctx.equivalent(source, EAnnotation.class, CHILD_RULE_2);
                ctx.equivalent(source, EEnum.class, CHILD_RULE_3);

                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class MultiChild1Transformation {
        @TransformRule(name = CHILD_RULE_1)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EDataType> childRule1() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedChildRule1.set(current != null ? current.getName() : "null");
                log.debug("[CHILD1] currentExecutingRule = {}", capturedChildRule1.get());

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Child1");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class MultiChild2Transformation {
        @TransformRule(name = CHILD_RULE_2)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> childRule2() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedChildRule2.set(current != null ? current.getName() : "null");
                log.debug("[CHILD2] currentExecutingRule = {}", capturedChildRule2.get());

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource(source.getName() + "_Child2");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class MultiChild3Transformation {
        @TransformRule(name = CHILD_RULE_3)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EEnum> childRule3() {
            return (source, ctx) -> {
                TransformRuleDescriptor current = ctx.getCurrentExecutingRule();
                capturedChildRule3.set(current != null ? current.getName() : "null");
                log.debug("[CHILD3] currentExecutingRule = {}", capturedChildRule3.get());

                EEnum target = ctx.createTarget(EEnum.class);
                target.setName(source.getName() + "_Child3");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @Test
    @DisplayName("Test 3: Multiple @Lazy Rules from Same Parent")
    void testMultipleLazyRulesFromSameParent() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(MultiChildParentTransformation.class);
        registry.register(MultiChild1Transformation.class);
        registry.register(MultiChild2Transformation.class);
        registry.register(MultiChild3Transformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("TestSource", "_testId");

        // Execute parent rule (triggers all 3 child rules)
        TransformRuleDescriptor parentRule = registry.getRuleByName(PARENT_RULE);
        EClassifier parent = (EClassifier) parentRule.execute(source, ctx);

        // Get all children
        EDataType child1 = ctx.equivalent(source, EDataType.class, CHILD_RULE_1);
        EAnnotation child2 = ctx.equivalent(source, EAnnotation.class, CHILD_RULE_2);
        EEnum child3 = ctx.equivalent(source, EEnum.class, CHILD_RULE_3);

        // Commit and get XMI IDs
        ctx.commitStagedElements();
        XMIResource xmiResource = (XMIResource) targetResource;

        String parentId = xmiResource.getID(parent);
        String child1Id = xmiResource.getID(child1);
        String child2Id = xmiResource.getID(child2);
        String child3Id = xmiResource.getID(child3);

        log.info("Parent XMI ID: {}", parentId);
        log.info("Child1 XMI ID: {}", child1Id);
        log.info("Child2 XMI ID: {}", child2Id);
        log.info("Child3 XMI ID: {}", child3Id);

        // Verify all XMI IDs are DIFFERENT
        Set<String> ids = new HashSet<>();
        ids.add(parentId);
        ids.add(child1Id);
        ids.add(child2Id);
        ids.add(child3Id);
        assertEquals(4, ids.size(), "All 4 elements should have unique XMI IDs");

        // Verify each ID contains its own rule name
        assertTrue(child1Id.contains(CHILD_RULE_1),
                "Child1 ID should contain 'ChildRule1', but was: " + child1Id);
        assertTrue(child2Id.contains(CHILD_RULE_2),
                "Child2 ID should contain 'ChildRule2', but was: " + child2Id);
        assertTrue(child3Id.contains(CHILD_RULE_3),
                "Child3 ID should contain 'ChildRule3', but was: " + child3Id);

        // Verify captured currentExecutingRule values
        assertEquals(PARENT_RULE, capturedParentRule.get());
        assertEquals(CHILD_RULE_1, capturedChildRule1.get());
        assertEquals(CHILD_RULE_2, capturedChildRule2.get());
        assertEquals(CHILD_RULE_3, capturedChildRule3.get());
    }

    // ==================== Test 4: Verify currentExecutingRule ThreadLocal ====================

    @Test
    @DisplayName("Test 4: Verify currentExecutingRule ThreadLocal is correctly set")
    void testCurrentExecutingRuleThreadLocal() {
        // This test directly verifies that getCurrentExecutingRule() returns
        // the correct rule during transform function execution.

        // Reuse the basic test setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(BasicParentTransformation.class);
        registry.register(BasicChildTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("TestSource", "_testId");

        // Execute parent rule (which triggers child rule via equivalent())
        TransformRuleDescriptor parentRule = registry.getRuleByName(PARENT_RULE);
        parentRule.execute(source, ctx);

        // Verify captured values
        assertEquals(PARENT_RULE, capturedParentRule.get(),
                "currentExecutingRule should be 'ParentRule' during ParentRule execution");
        assertEquals(CHILD_RULE, capturedChildRule.get(),
                "currentExecutingRule should be 'ChildRule' during ChildRule execution");

        // Additional verification: after execution, currentExecutingRule should be reset
        // (though this might be null or the parent rule depending on implementation)
        log.info("After transformation:");
        log.info("  Captured parent rule context: {}", capturedParentRule.get());
        log.info("  Captured child rule context: {}", capturedChildRule.get());
    }

    // ==================== Test 5: Context Restoration After Nested Calls ====================

    // Static list for tracking context sequence across transformation classes
    private static final List<String> contextSequence = Collections.synchronizedList(new ArrayList<>());
    private static final String TRACKING_PARENT_RULE = "TrackingParentRule";
    private static final String TRACKING_CHILD_RULE = "TrackingChildRule";

    /**
     * Tracking parent transformation for Test 5.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class TrackingParentTransformation {
        @TransformRule(name = TRACKING_PARENT_RULE)
        @Greedy
        public TransformFunction<EClass, EDataType> parentRule() {
            return (source, ctx) -> {
                String before = ctx.getCurrentExecutingRule() != null ?
                        ctx.getCurrentExecutingRule().getName() : "null";
                contextSequence.add("PARENT_BEFORE: " + before);

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Parent");
                ctx.addToResource(target);

                // Trigger child rule
                ctx.equivalent(source, EAnnotation.class, TRACKING_CHILD_RULE);

                // CRITICAL: After child completes, context should be restored
                String after = ctx.getCurrentExecutingRule() != null ?
                        ctx.getCurrentExecutingRule().getName() : "null";
                contextSequence.add("PARENT_AFTER: " + after);

                return target;
            };
        }
    }

    /**
     * Tracking child transformation for Test 5.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class TrackingChildTransformation {
        @TransformRule(name = TRACKING_CHILD_RULE)
        @Lazy
        @Greedy
        public TransformFunction<EClass, EAnnotation> childRule() {
            return (source, ctx) -> {
                String current = ctx.getCurrentExecutingRule() != null ?
                        ctx.getCurrentExecutingRule().getName() : "null";
                contextSequence.add("CHILD: " + current);

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource(source.getName() + "_Child");
                ctx.addToResource(target);

                return target;
            };
        }
    }

    @Test
    @DisplayName("Test 5: Context is properly restored after nested equivalent() calls complete")
    void testContextRestorationAfterNestedCalls() {
        // Clear the sequence for this test
        contextSequence.clear();

        // Register and execute
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TrackingParentTransformation.class);
        registry.register(TrackingChildTransformation.class);
        TransformationContext ctx = createContext(registry);

        EClass source = createSourceElement("TestSource", "_testId");
        TransformRuleDescriptor parentRule = registry.getRuleByName(TRACKING_PARENT_RULE);
        parentRule.execute(source, ctx);

        // Log sequence
        log.info("Context sequence: {}", contextSequence);

        // Verify sequence
        assertTrue(contextSequence.size() >= 3, "Should have at least 3 context captures");
        assertEquals("PARENT_BEFORE: " + TRACKING_PARENT_RULE, contextSequence.get(0));
        assertEquals("CHILD: " + TRACKING_CHILD_RULE, contextSequence.get(1));
        assertEquals("PARENT_AFTER: " + TRACKING_PARENT_RULE, contextSequence.get(2),
                "Context should be restored to TRACKING_PARENT_RULE after child completes");
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
}
