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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for inheritance state reset during nested transformations.
 *
 * <p>These tests verify the fix for a bug where nested transformations triggered
 * via equivalent() would inherit the outer transformation's inheritance state,
 * causing:</p>
 * <ul>
 *   <li>Nested transformation reusing outer's preCreatedTarget</li>
 *   <li>Property overwrites between unrelated transformations</li>
 *   <li>Wrong target types being used</li>
 * </ul>
 *
 * <p>The fix ensures each transformation starts with a fresh inheritance state,
 * regardless of the calling context.</p>
 */
@DisplayName("Inheritance State Reset Tests")
class InheritanceStateResetTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Tracking for test verification
    static List<String> createdTargetNames = Collections.synchronizedList(new ArrayList<>());
    static Map<String, EObject> createdTargets = Collections.synchronizedMap(new HashMap<>());
    static List<String> executionLog = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        createdTargetNames.clear();
        createdTargets.clear();
        executionLog.clear();

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

    private EReference createEReference(String name, EClass container, EClass type) {
        EReference ref = EcoreFactory.eINSTANCE.createEReference();
        ref.setName(name);
        ref.setEType(type);
        container.getEStructuralFeatures().add(ref);
        return ref;
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

    // ==================== Inheritance State Reset Tests ====================

    @Nested
    @DisplayName("Nested equivalent() Inheritance State Reset")
    class NestedEquivalentInheritanceTests {

        /**
         * CRITICAL BUG FIX TEST: When a rule with @Extends calls equivalent() to look up
         * a related element, the nested transformation should NOT inherit the outer's
         * inheritance state (preCreatedTarget, inInheritanceExecution).
         *
         * <p>Scenario: AssociationEnd transformation</p>
         * <ul>
         *   <li>Rule transforms EReference to EAnnotation</li>
         *   <li>Rule has @Extends, so it's in inheritance execution mode</li>
         *   <li>Rule calls equivalent() to look up the bidirectional partner</li>
         *   <li>Partner lookup should create its OWN target, not reuse outer's</li>
         * </ul>
         */
        @Test
        @DisplayName("Nested equivalent() creates independent target, not sharing outer's preCreatedTarget")
        void nestedEquivalentCreatesIndependentTarget() {
            // Create bidirectional references (like Order.items <-> OrderItem.order)
            EClass order = createEClass("Order");
            EClass orderItem = createEClass("OrderItem");

            EReference items = createEReference("items", order, orderItem);
            EReference orderRef = createEReference("order", orderItem, order);

            // Set up bidirectional relationship
            items.setEOpposite(orderRef);
            orderRef.setEOpposite(items);

            registry.register(BaseAssociationEndRule.class);
            registry.register(AssociationEndWithPartnerLookup.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Both references should have their own targets
            assertEquals(2, createdTargets.size(),
                    "Both AssociationEnds should create their own targets");

            EObject itemsTarget = createdTargets.get("items");
            EObject orderTarget = createdTargets.get("order");

            assertNotNull(itemsTarget, "items reference should have target");
            assertNotNull(orderTarget, "order reference should have target");
            assertNotSame(itemsTarget, orderTarget,
                    "Each reference MUST have its own target, not share the same one");

            // Verify names weren't overwritten
            assertTrue(createdTargetNames.contains("assoc_items"),
                    "items target should have correct name");
            assertTrue(createdTargetNames.contains("assoc_order"),
                    "order target should have correct name");
        }

        /**
         * Test that multiple nested equivalent() calls from within inheritance context
         * each create their own targets.
         */
        @Test
        @DisplayName("Multiple nested equivalent() calls each create independent targets")
        void multipleNestedEquivalentCallsCreateIndependentTargets() {
            // Create a class with multiple references
            EClass container = createEClass("Container");
            EClass refA = createEClass("RefA");
            EClass refB = createEClass("RefB");
            EClass refC = createEClass("RefC");

            createEReference("refToA", container, refA);
            createEReference("refToB", container, refB);
            createEReference("refToC", container, refC);

            registry.register(BaseContainerRule.class);
            registry.register(ContainerRuleWithMultipleLookups.class);
            registry.register(ClassToPackageRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Container + 3 referenced classes = 4 targets
            // But we're tracking the 3 class lookups specifically
            EObject refATarget = createdTargets.get("RefA");
            EObject refBTarget = createdTargets.get("RefB");
            EObject refCTarget = createdTargets.get("RefC");

            assertNotNull(refATarget, "RefA should have target");
            assertNotNull(refBTarget, "RefB should have target");
            assertNotNull(refCTarget, "RefC should have target");

            // All three must be different objects
            assertNotSame(refATarget, refBTarget, "RefA and RefB must have different targets");
            assertNotSame(refBTarget, refCTarget, "RefB and RefC must have different targets");
            assertNotSame(refATarget, refCTarget, "RefA and RefC must have different targets");
        }

        /**
         * Test that inheritance state is properly restored after nested equivalent().
         * The outer rule should continue with its original inheritance state.
         */
        @Test
        @DisplayName("Inheritance state restored after nested equivalent() completes")
        void inheritanceStateRestoredAfterNestedEquivalent() {
            EClass parent = createEClass("Parent");
            EClass child = createEClass("Child");
            createEReference("childRef", parent, child);

            registry.register(BaseParentRule.class);
            registry.register(ParentRuleWithNestedLookup.class);
            registry.register(ClassToPackageRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Check execution log to verify state was properly managed
            assertTrue(executionLog.contains("parent_start"),
                    "Parent rule should start");
            assertTrue(executionLog.contains("nested_lookup"),
                    "Nested lookup should execute");
            assertTrue(executionLog.contains("parent_after_nested"),
                    "Parent rule should continue after nested lookup");
            assertTrue(executionLog.contains("parent_end"),
                    "Parent rule should complete");

            // Verify the order
            int parentStart = executionLog.indexOf("parent_start");
            int nestedLookup = executionLog.indexOf("nested_lookup");
            int parentAfter = executionLog.indexOf("parent_after_nested");
            int parentEnd = executionLog.indexOf("parent_end");

            assertTrue(parentStart < nestedLookup, "Parent starts before nested lookup");
            assertTrue(nestedLookup < parentAfter, "Nested lookup before parent continues");
            assertTrue(parentAfter < parentEnd, "Parent continues before parent ends");
        }
    }

    // ==================== Transformation Classes ====================

    /**
     * Base rule for AssociationEnd - sets up inheritance context.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class BaseAssociationEndRule {
        @TransformRule(name = "BaseAssociationEnd")
        @Transform(type = EReference.class)
        @Abstract
        public TransformFunction<EReference, EAnnotation> baseAssociationEnd() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("base_" + source.getName());
                return ann;
            };
        }
    }

    /**
     * AssociationEnd rule with @Extends that looks up bidirectional partner via equivalent().
     * This is the exact scenario where the bug occurred.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class AssociationEndWithPartnerLookup {
        @TransformRule(name = "AssociationEndWithPartner")
        @Transform(type = EReference.class)
        @Greedy
        @Extends("BaseAssociationEnd")
        public TransformFunction<EReference, EAnnotation> associationEndWithPartner() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                String name = "assoc_" + source.getName();
                ann.setSource(name);

                createdTargetNames.add(name);
                createdTargets.put(source.getName(), ann);

                // Look up bidirectional partner via equivalent()
                // This nested transformation should NOT inherit our inheritance state
                EReference opposite = source.getEOpposite();
                if (opposite != null) {
                    // This equivalent() call was causing the bug:
                    // The partner lookup would inherit our preCreatedTarget and
                    // inInheritanceExecution state, causing it to reuse our target
                    EAnnotation partnerAnn = ctx.equivalent(opposite, EAnnotation.class);
                    if (partnerAnn != null) {
                        ann.getReferences().add(partnerAnn);
                    }
                }

                return ann;
            };
        }
    }

    /**
     * Base rule for Container.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class BaseContainerRule {
        @TransformRule(name = "BaseContainer")
        @Transform(type = EClass.class)
        @Abstract
        @Guard(method = "isContainer")
        public TransformFunction<EClass, EPackage> baseContainer() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("base_" + source.getName());
                return pkg;
            };
        }

        public boolean isContainer(EObject obj, TransformationContext ctx) {
            return obj instanceof EClass && ((EClass) obj).getName().equals("Container");
        }
    }

    /**
     * Container rule that performs multiple equivalent() lookups.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ContainerRuleWithMultipleLookups {
        @TransformRule(name = "ContainerWithLookups")
        @Transform(type = EClass.class)
        @Greedy
        @Extends("BaseContainer")
        @Guard(method = "isContainer")
        public TransformFunction<EClass, EPackage> containerWithLookups() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("container_" + source.getName());

                // Look up all referenced types via equivalent()
                // Each lookup should create its own independent target
                for (EStructuralFeature feature : source.getEStructuralFeatures()) {
                    if (feature instanceof EReference) {
                        EReference ref = (EReference) feature;
                        EClassifier refType = ref.getEType();
                        if (refType instanceof EClass) {
                            EPackage refPkg = ctx.equivalent((EClass) refType, EPackage.class);
                            if (refPkg != null) {
                                pkg.getESubpackages().add(refPkg);
                            }
                        }
                    }
                }

                return pkg;
            };
        }

        public boolean isContainer(EObject obj, TransformationContext ctx) {
            return obj instanceof EClass && ((EClass) obj).getName().equals("Container");
        }
    }

    /**
     * Simple class to package rule for lookups.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ClassToPackageRule {
        @TransformRule(name = "ClassToPackage")
        @Transform(type = EClass.class)
        @Lazy
        @Guard(method = "isNotContainer")
        public TransformFunction<EClass, EPackage> classToPackage() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("pkg_" + source.getName());
                createdTargets.put(source.getName(), pkg);
                return pkg;
            };
        }

        public boolean isNotContainer(EObject obj, TransformationContext ctx) {
            return obj instanceof EClass && !((EClass) obj).getName().equals("Container");
        }
    }

    /**
     * Base rule for Parent class.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class BaseParentRule {
        @TransformRule(name = "BaseParent")
        @Transform(type = EClass.class)
        @Abstract
        @Guard(method = "isParent")
        public TransformFunction<EClass, EPackage> baseParent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("base_" + source.getName());
                return pkg;
            };
        }

        public boolean isParent(EObject obj, TransformationContext ctx) {
            return obj instanceof EClass && ((EClass) obj).getName().equals("Parent");
        }
    }

    /**
     * Parent rule that tests state restoration after nested equivalent().
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentRuleWithNestedLookup {
        @TransformRule(name = "ParentWithLookup")
        @Transform(type = EClass.class)
        @Greedy
        @Extends("BaseParent")
        @Guard(method = "isParent")
        public TransformFunction<EClass, EPackage> parentWithLookup() {
            return (source, ctx) -> {
                executionLog.add("parent_start");

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("parent_" + source.getName());

                // Perform nested lookup
                for (EStructuralFeature feature : source.getEStructuralFeatures()) {
                    if (feature instanceof EReference) {
                        EReference ref = (EReference) feature;
                        EClassifier refType = ref.getEType();
                        if (refType instanceof EClass) {
                            executionLog.add("nested_lookup");
                            EPackage childPkg = ctx.equivalent((EClass) refType, EPackage.class);
                            if (childPkg != null) {
                                pkg.getESubpackages().add(childPkg);
                            }
                        }
                    }
                }

                executionLog.add("parent_after_nested");

                // Continue processing after nested lookup
                pkg.setNsPrefix("ns_" + source.getName());

                executionLog.add("parent_end");
                return pkg;
            };
        }

        public boolean isParent(EObject obj, TransformationContext ctx) {
            return obj instanceof EClass && ((EClass) obj).getName().equals("Parent");
        }
    }
}
