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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bug #2: Multiple createTarget() XMI ID Collisions.
 *
 * <p>When a single rule calls createTarget() multiple times (for different types or
 * multiple instances of the same type), all elements get the SAME XMI ID based on
 * source + ruleName. The framework doesn't distinguish between:</p>
 * <ul>
 *   <li>Different target types (e.g., Filter vs Icon)</li>
 *   <li>Multiple instances of the same type</li>
 *   <li>Parent elements vs inline child elements</li>
 * </ul>
 *
 * <p>Real-world collision example:</p>
 * <pre>
 * XMI ID COLLISION DETECTED: ID '.../TabularFilter' is being reassigned from Filter to Icon
 * </pre>
 *
 * <p>Test Specification Reference: ZETA Framework JUnit Test Specification: XMI ID Collision Bugs</p>
 */
@DisplayName("Multiple createTarget() XMI ID Collision Tests (Bug #2)")
class MultipleCreateTargetCollisionTest {

    private static final Logger log = LoggerFactory.getLogger(MultipleCreateTargetCollisionTest.class);

    // Rule names
    private static final String MULTI_TARGET_RULE = "MultiTargetRule";
    private static final String MULTI_INSTANCE_RULE = "MultiInstanceRule";
    private static final String NESTED_INLINE_RULE = "NestedInlineRule";
    private static final String TABULAR_FILTER_RULE = "TabularFilterRule";

    // Track collision detection
    private static final List<String> detectedCollisions = Collections.synchronizedList(new ArrayList<>());

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

        detectedCollisions.clear();
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

    // ==================== Test 6: Multiple createTarget() - Different Types ====================

    /**
     * Simulates TabularFilter rule: creates Filter + Icon (different types).
     * Target: EDataType (simulating Filter) + EAnnotation (simulating Icon)
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class MultiTargetDifferentTypesTransformation {

        @TransformRule(name = MULTI_TARGET_RULE)
        @Greedy
        public TransformFunction<EClass, EDataType> multiTargetRule() {
            return (source, ctx) -> {
                log.debug("[MULTI-TARGET] Creating first element (Filter/EDataType)");

                // First createTarget call - simulating Filter
                EDataType filter = ctx.createTarget(EDataType.class);
                filter.setName(source.getName() + "_Filter");
                ctx.addToResource(filter);

                log.debug("[MULTI-TARGET] Creating second element (Icon/EAnnotation)");

                // Second createTarget call - different type, simulating Icon
                // BUG: This will get the SAME XMI ID as filter
                EAnnotation icon = ctx.createTarget(EAnnotation.class);
                icon.setSource(source.getName() + "_Icon");
                ctx.addToResource(icon);

                // Set icon as detail on filter (containment)
                filter.getEAnnotations().add(icon);

                return filter;
            };
        }
    }

    @Test
    @DisplayName("Test 6: Multiple createTarget() with different types should have unique XMI IDs")
    void testMultipleCreateTargetDifferentTypes() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(MultiTargetDifferentTypesTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("DataFilter", "_sourceId");

        // Execute rule
        TransformRuleDescriptor rule = registry.getRuleByName(MULTI_TARGET_RULE);
        EDataType filter = (EDataType) rule.execute(source, ctx);

        // Get the icon that was created
        EAnnotation icon = filter.getEAnnotations().isEmpty() ? null : filter.getEAnnotations().get(0);
        assertNotNull(icon, "Icon should have been created and added to filter");

        // Commit and get XMI IDs
        ctx.commitStagedElements();
        XMIResource xmiResource = (XMIResource) targetResource;
        String filterId = xmiResource.getID(filter);
        String iconId = xmiResource.getID(icon);

        log.info("Filter XMI ID: {}", filterId);
        log.info("Icon XMI ID: {}", iconId);

        // CRITICAL: These IDs should be DIFFERENT
        // BUG: Currently they are the SAME
        assertNotEquals(filterId, iconId,
                "Filter and Icon created in same rule should have different XMI IDs. " +
                "This is Bug #2: multiple createTarget() calls get same ID.");
    }

    // ==================== Test 7: Multiple createTarget() - Same Type, Multiple Instances ====================

    /**
     * Creates multiple instances of the same type (simulating EnumerationMembers).
     * Target: Multiple EEnumLiteral instances
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class MultiInstanceSameTypeTransformation {

        @TransformRule(name = MULTI_INSTANCE_RULE)
        @Greedy
        public TransformFunction<EClass, EEnum> multiInstanceRule() {
            return (source, ctx) -> {
                log.debug("[MULTI-INSTANCE] Creating container (EnumerationType/EEnum)");

                // Create container
                EEnum enumType = ctx.createTarget(EEnum.class);
                enumType.setName(source.getName() + "_Enum");
                ctx.addToResource(enumType);

                // Create multiple instances of same type (EnumerationMembers)
                log.debug("[MULTI-INSTANCE] Creating 3 enum literals");

                EEnumLiteral literal1 = ctx.createTarget(EEnumLiteral.class);
                literal1.setName("VALUE_1");
                enumType.getELiterals().add(literal1);

                EEnumLiteral literal2 = ctx.createTarget(EEnumLiteral.class);
                literal2.setName("VALUE_2");
                enumType.getELiterals().add(literal2);

                EEnumLiteral literal3 = ctx.createTarget(EEnumLiteral.class);
                literal3.setName("VALUE_3");
                enumType.getELiterals().add(literal3);

                return enumType;
            };
        }
    }

    @Test
    @DisplayName("Test 7: Multiple createTarget() same type should have unique XMI IDs")
    void testMultipleCreateTargetSameType() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(MultiInstanceSameTypeTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("StringOperation", "_sourceId");

        // Execute rule
        TransformRuleDescriptor rule = registry.getRuleByName(MULTI_INSTANCE_RULE);
        EEnum enumType = (EEnum) rule.execute(source, ctx);

        // Get all literals
        List<EEnumLiteral> literals = enumType.getELiterals();
        assertEquals(3, literals.size(), "Should have 3 enum literals");

        // Commit and apply XMI IDs
        ctx.commitStagedElements();
        ctx.applyAllPendingXmiIds();  // Apply IDs to contained elements
        XMIResource xmiResource = (XMIResource) targetResource;

        // Collect all XMI IDs
        Set<String> ids = new HashSet<>();
        for (EEnumLiteral literal : literals) {
            String id = xmiResource.getID(literal);
            log.info("Literal '{}' XMI ID: {}", literal.getName(), id);
            ids.add(id);
        }

        // All 3 literals should have DIFFERENT IDs
        // BUG: Currently they all get the SAME ID
        assertEquals(3, ids.size(),
                "Each EEnumLiteral instance should have a unique XMI ID. " +
                "Found " + ids.size() + " unique IDs for 3 elements. " +
                "This is Bug #2: multiple createTarget() calls get same ID.");
    }

    // ==================== Test 8: Nested Inline Element Creation ====================

    /**
     * Creates nested inline elements: grandparent → parent → child.
     * All created in the same rule execution.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class NestedInlineTransformation {

        @TransformRule(name = NESTED_INLINE_RULE)
        @Greedy
        public TransformFunction<EClass, EPackage> nestedInlineRule() {
            return (source, ctx) -> {
                log.debug("[NESTED-INLINE] Creating grandparent (EPackage)");

                // Grandparent
                EPackage grandparent = ctx.createTarget(EPackage.class);
                grandparent.setName(source.getName() + "_Package");
                ctx.addToResource(grandparent);

                log.debug("[NESTED-INLINE] Creating parent (EClass)");

                // Parent (contained by grandparent)
                EClass parent = ctx.createTarget(EClass.class);
                parent.setName(source.getName() + "_Class");
                grandparent.getEClassifiers().add(parent);

                log.debug("[NESTED-INLINE] Creating child (EAttribute)");

                // Child (contained by parent)
                EAttribute child = ctx.createTarget(EAttribute.class);
                child.setName(source.getName() + "_Attribute");
                child.setEType(EcorePackage.Literals.ESTRING);
                parent.getEStructuralFeatures().add(child);

                return grandparent;
            };
        }
    }

    @Test
    @DisplayName("Test 8: Nested inline element creation should have unique XMI IDs")
    void testNestedInlineElementCreation() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(NestedInlineTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("Entity", "_sourceId");

        // Execute rule
        TransformRuleDescriptor rule = registry.getRuleByName(NESTED_INLINE_RULE);
        EPackage grandparent = (EPackage) rule.execute(source, ctx);

        // Get nested elements
        EClass parent = (EClass) grandparent.getEClassifiers().get(0);
        EAttribute child = (EAttribute) parent.getEStructuralFeatures().get(0);

        // Commit and apply XMI IDs
        ctx.commitStagedElements();
        ctx.applyAllPendingXmiIds();  // Apply IDs to contained elements
        XMIResource xmiResource = (XMIResource) targetResource;

        String grandparentId = xmiResource.getID(grandparent);
        String parentId = xmiResource.getID(parent);
        String childId = xmiResource.getID(child);

        log.info("Grandparent (EPackage) XMI ID: {}", grandparentId);
        log.info("Parent (EClass) XMI ID: {}", parentId);
        log.info("Child (EAttribute) XMI ID: {}", childId);

        // All 3 should have DIFFERENT IDs
        Set<String> ids = new HashSet<>();
        ids.add(grandparentId);
        ids.add(parentId);
        ids.add(childId);

        assertEquals(3, ids.size(),
                "All 3 nested inline elements should have unique XMI IDs. " +
                "Found " + ids.size() + " unique IDs. " +
                "This is Bug #2: multiple createTarget() calls get same ID.");
    }

    // ==================== Test 9: Real-World TabularFilter Scenario ====================

    /**
     * Simulates the exact TabularFilter pattern from ESM2UI.
     * This is the pattern that causes the collision:
     * XMI ID COLLISION DETECTED: ID '.../TabularFilter' is being reassigned from Filter to Icon
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class TabularFilterTransformation {

        @TransformRule(name = TABULAR_FILTER_RULE)
        @Greedy
        public TransformFunction<EClass, EDataType> tabularFilterRule() {
            return (source, ctx) -> {
                // Simulating:
                // Filter target = ctx.createTarget(Filter.class);
                // Icon icon = ctx.createTarget(Icon.class);
                // target.getIcons().add(icon);

                log.debug("[TABULAR-FILTER] Creating Filter");
                EDataType filter = ctx.createTarget(EDataType.class);
                filter.setName("TabularFilter_" + source.getName());
                ctx.addToResource(filter);

                log.debug("[TABULAR-FILTER] Creating Icon (will collide with Filter ID)");
                EAnnotation icon = ctx.createTarget(EAnnotation.class);
                icon.setSource("FilterIcon_" + source.getName());
                // Don't add to resource - it's contained by filter
                filter.getEAnnotations().add(icon);

                return filter;
            };
        }
    }

    @Test
    @DisplayName("Test 9: TabularFilter real-world scenario - Filter + Icon collision detected")
    void testTabularFilterScenario() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TabularFilterTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source (simulating DataFilter)
        EClass source = createSourceElement("CustomerFilter", "_5hR_YM65EfC5HrpMtxV6Lw");

        // Execute rule
        TransformRuleDescriptor rule = registry.getRuleByName(TABULAR_FILTER_RULE);
        EDataType filter = (EDataType) rule.execute(source, ctx);

        // Get the icon
        EAnnotation icon = filter.getEAnnotations().get(0);

        // Commit and apply pending XMI IDs to all contained elements
        ctx.commitStagedElements();
        ctx.applyAllPendingXmiIds();

        XMIResource xmiResource = (XMIResource) targetResource;
        String filterId = xmiResource.getID(filter);
        String iconId = xmiResource.getID(icon);

        log.info("=== TabularFilter Scenario ===");
        log.info("Filter XMI ID: {}", filterId);
        log.info("Icon XMI ID: {}", iconId);

        // BUG #2: Both elements get the SAME ID from createTarget()
        // The collision is already logged during createTarget() call:
        // "XMI ID COLLISION DETECTED: ID '.../TabularFilterRule' is being reassigned from Filter to Icon"
        //
        // After applyAllPendingXmiIds(), the icon gets the colliding ID
        // which overwrites the filter's ID in the XMI resource, OR
        // the icon's ID matches the filter's ID (depending on application order)

        // CRITICAL assertion: These IDs should be DIFFERENT, but they are the SAME
        // This test documents the bug - it will fail when the bug is fixed
        assertNotEquals(filterId, iconId,
                "Filter and Icon should have different XMI IDs. " +
                "Bug #2: multiple createTarget() calls in same rule get same ID. " +
                "Collision was logged: 'ID .../TabularFilterRule is being reassigned from Filter to Icon'");
    }

    // ==================== Test 10: Workaround with explicit setElementId() ====================

    /**
     * Tests the workaround: using explicit setElementId() immediately after createTarget().
     * Note: For contained elements (not added to resource directly), applyAllPendingXmiIds()
     * must be called after transformation to apply IDs to elements in containment trees.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class WorkaroundTransformation {

        @TransformRule(name = "WorkaroundRule")
        @Greedy
        public TransformFunction<EClass, EDataType> workaroundRule() {
            return (source, ctx) -> {
                String baseId = "(source/_sourceId)";

                // Create filter with explicit ID
                EDataType filter = ctx.createTarget(EDataType.class);
                ctx.setElementId(filter, baseId + "/TabularFilter");
                filter.setName("Filter");
                ctx.addToResource(filter);

                // Create icon with explicit ID (different from filter)
                EAnnotation icon = ctx.createTarget(EAnnotation.class);
                ctx.setElementId(icon, baseId + "/TabularFilterIcon");
                icon.setSource("Icon");
                filter.getEAnnotations().add(icon);

                return filter;
            };
        }
    }

    @Test
    @DisplayName("Test 10: Workaround with explicit setElementId() prevents collision")
    void testWorkaroundWithExplicitSetElementId() {
        // Setup
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(WorkaroundTransformation.class);
        TransformationContext ctx = createContext(registry);

        // Create source
        EClass source = createSourceElement("Test", "_sourceId");

        // Execute rule
        TransformRuleDescriptor rule = registry.getRuleByName("WorkaroundRule");
        EDataType filter = (EDataType) rule.execute(source, ctx);
        EAnnotation icon = filter.getEAnnotations().get(0);

        // Commit staged elements first
        ctx.commitStagedElements();

        // Apply pending XMI IDs to all elements in the resource tree
        // This is needed for contained elements that weren't added directly to the resource
        ctx.applyAllPendingXmiIds();

        XMIResource xmiResource = (XMIResource) targetResource;
        String filterId = xmiResource.getID(filter);
        String iconId = xmiResource.getID(icon);

        log.info("=== Workaround Test ===");
        log.info("Filter XMI ID: {}", filterId);
        log.info("Icon XMI ID: {}", iconId);

        // With explicit setElementId(), IDs should be different
        assertNotEquals(filterId, iconId,
                "With explicit setElementId(), Filter and Icon should have different XMI IDs");

        // Verify the IDs are what we set
        assertEquals("(source/_sourceId)/TabularFilter", filterId);
        assertEquals("(source/_sourceId)/TabularFilterIcon", iconId);
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
