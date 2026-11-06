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
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License, v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import hu.blackbelt.judo.zeta.annotation.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EReference cardinality variations in transformations.
 *
 * <p>Verifies transformation framework correctly handles:</p>
 * <ul>
 *   <li>Single-valued references (0..1, 1..1)</li>
 *   <li>Collection-valued references (0..N, 0..*, N..*)</li>
 *   <li>Containment vs association references</li>
 *   <li>Bidirectional references with opposites</li>
 *   <li>Cross-package references</li>
 *   <li>Self-references and circular chains</li>
 *   <li>Reference resolution via equivalent()</li>
 * </ul>
 */
class EcoreRelationCardinalityTest extends AbstractEcoreTransformationTest {

    // ==================== Single-Valued Reference Tests ====================

    @Nested
    @DisplayName("Single-Valued References")
    class SingleValuedReferenceTests {

        @Test
        @DisplayName("Optional single reference (0..1) preserves cardinality")
        void optionalSingleReferencePreservesCardinality() {
            // Create classes
            EClass person = createEClass("Person");
            EClass address = createEClass("Address");

            // Create optional single reference
            EReference ref = createEReference("address", address, 0, 1);
            person.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(2);

            EClass targetPerson = findTargetClassByName("Person");
            EReference targetRef = findReferenceByName(targetPerson, "address");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceCardinality(targetRef, 0, 1);
        }

        @Test
        @DisplayName("Required single reference (1..1) preserves cardinality")
        void requiredSingleReferencePreservesCardinality() {
            EClass order = createEClass("Order");
            EClass customer = createEClass("Customer");

            EReference ref = createEReference("customer", customer, 1, 1);
            order.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetOrder = findTargetClassByName("Order");
            EReference targetRef = findReferenceByName(targetOrder, "customer");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceCardinality(targetRef, 1, 1);
        }
    }

    // ==================== Collection Reference Tests ====================

    @Nested
    @DisplayName("Collection References")
    class CollectionReferenceTests {

        @Test
        @DisplayName("Bounded collection reference (0..N) preserves cardinality")
        void boundedCollectionReferencePreservesCardinality() {
            EClass team = createEClass("Team");
            EClass member = createEClass("Member");

            EReference ref = createEReference("members", member, 0, 10);
            team.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetTeam = findTargetClassByName("Team");
            EReference targetRef = findReferenceByName(targetTeam, "members");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceCardinality(targetRef, 0, 10);
        }

        @Test
        @DisplayName("Unbounded collection reference (0..*) preserves cardinality")
        void unboundedCollectionReferencePreservesCardinality() {
            EClass library = createEClass("Library");
            EClass book = createEClass("Book");

            EReference ref = createEReference("books", book, 0, -1);
            library.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetLibrary = findTargetClassByName("Library");
            EReference targetRef = findReferenceByName(targetLibrary, "books");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceCardinality(targetRef, 0, -1);
        }

        @Test
        @DisplayName("Required collection reference (N..*) preserves cardinality")
        void requiredCollectionReferencePreservesCardinality() {
            EClass order = createEClass("Order");
            EClass lineItem = createEClass("LineItem");

            EReference ref = createEReference("items", lineItem, 1, -1);
            order.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetOrder = findTargetClassByName("Order");
            EReference targetRef = findReferenceByName(targetOrder, "items");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceCardinality(targetRef, 1, -1);
        }
    }

    // ==================== Containment Reference Tests ====================

    @Nested
    @DisplayName("Containment References")
    class ContainmentReferenceTests {

        @Test
        @DisplayName("Containment reference preserves containment flag")
        void containmentReferencePreservesFlag() {
            EClass parent = createEClass("Parent");
            EClass child = createEClass("Child");

            EReference ref = createContainmentReference("children", child);
            ref.setUpperBound(-1);
            parent.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetParent = findTargetClassByName("Parent");
            EReference targetRef = findReferenceByName(targetParent, "children");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceContainment(targetRef, true);
        }

        @Test
        @DisplayName("Containment with bidirectional opposite")
        void containmentWithBidirectionalOpposite() {
            EClass parent = createEClass("Parent");
            EClass child = createEClass("Child");

            // Create bidirectional containment
            EReference childrenRef = createContainmentReference("children", child);
            childrenRef.setUpperBound(-1);
            parent.getEStructuralFeatures().add(childrenRef);

            EReference parentRef = createEReference("parent", parent, 0, 1);
            child.getEStructuralFeatures().add(parentRef);

            childrenRef.setEOpposite(parentRef);
            parentRef.setEOpposite(childrenRef);

            registry.register(BidirectionalTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetParent = findTargetClassByName("Parent");
            EClass targetChild = findTargetClassByName("Child");

            EReference targetChildren = findReferenceByName(targetParent, "children");
            EReference targetParentRef = findReferenceByName(targetChild, "parent");

            assertNotNull(targetChildren, "children reference should exist");
            assertNotNull(targetParentRef, "parent reference should exist");
            assertReferenceContainment(targetChildren, true);
            assertBidirectionalPair(targetChildren, targetParentRef);
        }

        @Test
        @DisplayName("Association (non-containment) reference preserves flag")
        void associationReferencePreservesFlag() {
            EClass employee = createEClass("Employee");
            EClass department = createEClass("Department");

            EReference ref = createEReference("department", department, 0, 1);
            ref.setContainment(false);
            employee.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetEmployee = findTargetClassByName("Employee");
            EReference targetRef = findReferenceByName(targetEmployee, "department");

            assertNotNull(targetRef, "Reference should exist");
            assertReferenceContainment(targetRef, false);
        }
    }

    // ==================== Cross-Package Reference Tests ====================

    @Nested
    @DisplayName("Cross-Package References")
    class CrossPackageReferenceTests {

        @Test
        @DisplayName("Cross-package association resolves correctly")
        void crossPackageAssociationResolvesCorrectly() {
            // Create two packages
            EPackage pkg1 = createEPackage("orders", "http://orders.example.com");
            EPackage pkg2 = createEPackage("customers", "http://customers.example.com");

            // Create classes in different packages
            EClass order = createEClass("Order");
            pkg1.getEClassifiers().add(order);

            EClass customer = createEClass("Customer");
            pkg2.getEClassifiers().add(customer);

            // Cross-package reference
            EReference ref = createEReference("customer", customer, 0, 1);
            order.getEStructuralFeatures().add(ref);

            registry.register(CrossPackageTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify both packages exist in target
            EPackage targetOrders = findTargetPackageByName("orders");
            EPackage targetCustomers = findTargetPackageByName("customers");

            assertNotNull(targetOrders, "orders package should exist");
            assertNotNull(targetCustomers, "customers package should exist");

            // Find order class and verify reference
            EClass targetOrder = findClassInPackage(targetOrders, "Order");
            EReference targetRef = findReferenceByName(targetOrder, "customer");

            assertNotNull(targetRef, "Reference should exist");

            // Verify target type is in correct package
            EClass targetCustomer = (EClass) targetRef.getEType();
            assertNotNull(targetCustomer, "Reference target should be resolved");
            assertEquals("Customer", targetCustomer.getName());
        }
    }

    // ==================== Bidirectional Reference Tests ====================

    @Nested
    @DisplayName("Bidirectional References")
    class BidirectionalReferenceTests {

        @Test
        @DisplayName("One-to-one bidirectional reference")
        void oneToOneBidirectionalReference() {
            EClass person = createEClass("Person");
            EClass spouseRef = createEClass("Person"); // Self-referencing for spouse

            EReference spouse = createEReference("spouse", person, 0, 1);
            person.getEStructuralFeatures().add(spouse);

            // Make it bidirectional (same class, same reference effectively)
            spouse.setEOpposite(spouse);

            registry.register(BidirectionalTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetPerson = findTargetClassByName("Person");
            EReference targetSpouse = findReferenceByName(targetPerson, "spouse");

            assertNotNull(targetSpouse, "spouse reference should exist");
            assertReferenceCardinality(targetSpouse, 0, 1);
            // Self-referential bidirectional
            assertEquals(targetSpouse, targetSpouse.getEOpposite());
        }

        @Test
        @DisplayName("One-to-many bidirectional reference")
        void oneToManyBidirectionalReference() {
            EClass order = createEClass("Order");
            EClass lineItem = createEClass("LineItem");

            // Order has many LineItems
            EReference items = createEReference("items", lineItem, 0, -1);
            order.getEStructuralFeatures().add(items);

            // LineItem has one Order
            EReference orderRef = createEReference("order", order, 0, 1);
            lineItem.getEStructuralFeatures().add(orderRef);

            items.setEOpposite(orderRef);
            orderRef.setEOpposite(items);

            registry.register(BidirectionalTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetOrder = findTargetClassByName("Order");
            EClass targetLineItem = findTargetClassByName("LineItem");

            EReference targetItems = findReferenceByName(targetOrder, "items");
            EReference targetOrderRef = findReferenceByName(targetLineItem, "order");

            assertNotNull(targetItems, "items reference should exist");
            assertNotNull(targetOrderRef, "order reference should exist");

            assertReferenceCardinality(targetItems, 0, -1);
            assertReferenceCardinality(targetOrderRef, 0, 1);
            assertBidirectionalPair(targetItems, targetOrderRef);
        }

        @Test
        @DisplayName("Many-to-many bidirectional reference")
        void manyToManyBidirectionalReference() {
            EClass student = createEClass("Student");
            EClass course = createEClass("Course");

            // Student has many Courses
            EReference courses = createEReference("courses", course, 0, -1);
            student.getEStructuralFeatures().add(courses);

            // Course has many Students
            EReference students = createEReference("students", student, 0, -1);
            course.getEStructuralFeatures().add(students);

            courses.setEOpposite(students);
            students.setEOpposite(courses);

            registry.register(BidirectionalTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetStudent = findTargetClassByName("Student");
            EClass targetCourse = findTargetClassByName("Course");

            EReference targetCourses = findReferenceByName(targetStudent, "courses");
            EReference targetStudents = findReferenceByName(targetCourse, "students");

            assertNotNull(targetCourses, "courses reference should exist");
            assertNotNull(targetStudents, "students reference should exist");

            assertReferenceCardinality(targetCourses, 0, -1);
            assertReferenceCardinality(targetStudents, 0, -1);
            assertBidirectionalPair(targetCourses, targetStudents);
        }
    }

    // ==================== Reference Resolution Tests ====================

    @Nested
    @DisplayName("Reference Resolution")
    class ReferenceResolutionTests {

        @Test
        @DisplayName("Reference target resolved through equivalent()")
        void referenceTargetResolvedThroughEquivalent() {
            EClass source = createEClass("Source");
            EClass target = createEClass("Target");

            EReference ref = createEReference("targetRef", target, 0, 1);
            source.getEStructuralFeatures().add(ref);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetSource = findTargetClassByName("Source");
            EReference targetRef = findReferenceByName(targetSource, "targetRef");

            assertNotNull(targetRef, "Reference should exist");

            // Verify target is the transformed class, not the source
            EClass targetType = (EClass) targetRef.getEType();
            assertNotNull(targetType, "Reference type should be resolved");
            assertEquals("Target", targetType.getName());

            // Verify it's from target resource
            assertTrue(isInTargetResource(targetType), "Target should be in target resource");
        }

        @Test
        @DisplayName("Lazy resolution of reference targets")
        void lazyResolutionOfReferenceTargets() {
            // Create a chain where A references B, and B references C
            EClass classA = createEClass("ClassA");
            EClass classB = createEClass("ClassB");
            EClass classC = createEClass("ClassC");

            EReference refAtoB = createEReference("refB", classB, 0, 1);
            classA.getEStructuralFeatures().add(refAtoB);

            EReference refBtoC = createEReference("refC", classC, 0, 1);
            classB.getEStructuralFeatures().add(refBtoC);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            // Should not throw - lazy resolution handles any order
            assertDoesNotThrow(() -> executor.transform());

            assertTargetSize(3);
        }
    }

    // ==================== Self-Reference and Circular Tests ====================

    @Nested
    @DisplayName("Self-References and Circular Chains")
    class SelfReferenceTests {

        @Test
        @DisplayName("Self-reference (same class) resolves correctly")
        void selfReferenceResolvesCorrectly() {
            EClass treeNode = createEClass("TreeNode");

            // Parent reference (self-reference)
            EReference parent = createEReference("parent", treeNode, 0, 1);
            treeNode.getEStructuralFeatures().add(parent);

            // Children reference (self-reference, collection)
            EReference children = createEReference("children", treeNode, 0, -1);
            treeNode.getEStructuralFeatures().add(children);

            // Make bidirectional
            parent.setEOpposite(children);
            children.setEOpposite(parent);

            registry.register(BidirectionalTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(1);

            EClass targetTreeNode = findTargetClassByName("TreeNode");
            EReference targetParent = findReferenceByName(targetTreeNode, "parent");
            EReference targetChildren = findReferenceByName(targetTreeNode, "children");

            assertNotNull(targetParent, "parent reference should exist");
            assertNotNull(targetChildren, "children reference should exist");

            // Verify self-reference
            assertEquals(targetTreeNode, targetParent.getEType(), "parent should reference same class");
            assertEquals(targetTreeNode, targetChildren.getEType(), "children should reference same class");

            // Verify bidirectionality
            assertBidirectionalPair(targetParent, targetChildren);
        }

        @Test
        @DisplayName("Circular reference chain (A→B→C→A) resolves correctly")
        void circularReferenceChainResolvesCorrectly() {
            EClass classA = createEClass("ClassA");
            EClass classB = createEClass("ClassB");
            EClass classC = createEClass("ClassC");

            // A -> B
            EReference refAtoB = createEReference("refB", classB, 0, 1);
            classA.getEStructuralFeatures().add(refAtoB);

            // B -> C
            EReference refBtoC = createEReference("refC", classC, 0, 1);
            classB.getEStructuralFeatures().add(refBtoC);

            // C -> A (completes the cycle)
            EReference refCtoA = createEReference("refA", classA, 0, 1);
            classC.getEStructuralFeatures().add(refCtoA);

            registry.register(CardinalityTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            // Should not throw - circular references handled correctly
            assertDoesNotThrow(() -> executor.transform());

            assertTargetSize(3);

            // Verify all references resolved correctly
            EClass targetA = findTargetClassByName("ClassA");
            EClass targetB = findTargetClassByName("ClassB");
            EClass targetC = findTargetClassByName("ClassC");

            EReference targetRefAtoB = findReferenceByName(targetA, "refB");
            EReference targetRefBtoC = findReferenceByName(targetB, "refC");
            EReference targetRefCtoA = findReferenceByName(targetC, "refA");

            assertEquals(targetB, targetRefAtoB.getEType(), "A.refB should point to B");
            assertEquals(targetC, targetRefBtoC.getEType(), "B.refC should point to C");
            assertEquals(targetA, targetRefCtoA.getEType(), "C.refA should point to A");
        }
    }

    // ==================== Helper Methods ====================

    private EClass findTargetClassByName(String name) {
        for (int i = 0; i < targetResource.getContents().size(); i++) {
            var obj = targetResource.getContents().get(i);
            if (obj instanceof EPackage) {
                EClass found = findClassInPackage((EPackage) obj, name);
                if (found != null) return found;
            } else if (obj instanceof EClass) {
                EClass eClass = (EClass) obj;
                if (name.equals(eClass.getName())) {
                    return eClass;
                }
            }
        }
        return null;
    }

    private EPackage findTargetPackageByName(String name) {
        for (int i = 0; i < targetResource.getContents().size(); i++) {
            var obj = targetResource.getContents().get(i);
            if (obj instanceof EPackage) {
                EPackage pkg = (EPackage) obj;
                if (name.equals(pkg.getName())) {
                    return pkg;
                }
            }
        }
        return null;
    }

    private EClass findClassInPackage(EPackage pkg, String name) {
        for (var classifier : pkg.getEClassifiers()) {
            if (classifier instanceof EClass && name.equals(classifier.getName())) {
                return (EClass) classifier;
            }
        }
        return null;
    }

    private EReference findReferenceByName(EClass eClass, String name) {
        for (var ref : eClass.getEReferences()) {
            if (name.equals(ref.getName())) {
                return ref;
            }
        }
        return null;
    }

    private boolean isInTargetResource(EClass eClass) {
        return targetResource.getContents().contains(eClass) ||
                (eClass.eContainer() instanceof EPackage &&
                        targetResource.getContents().contains(eClass.eContainer()));
    }

    // ==================== Transformation Classes ====================

    /**
     * Transformation preserving reference cardinality and containment.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CardinalityTransformation {

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Transform references with cardinality
                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof EReference ref) {
                        EReference targetRef = ctx.createTarget(EReference.class);
                        targetRef.setName(ref.getName());
                        targetRef.setLowerBound(ref.getLowerBound());
                        targetRef.setUpperBound(ref.getUpperBound());
                        targetRef.setContainment(ref.isContainment());

                        // Resolve target type via equivalent
                        EClass targetType = ctx.equivalent((EClass) ref.getEType(), EClass.class);
                        if (targetType != null) {
                            targetRef.setEType(targetType);
                        }

                        target.getEStructuralFeatures().add(targetRef);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation handling bidirectional references.
     * Uses post-execution hook to set eOpposite after all references are created.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class BidirectionalTransformation {

        // Track source-to-target reference mappings for eOpposite setup
        private static final java.util.Map<EReference, EReference> sourceToTargetRef = new java.util.HashMap<>();

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());

                // Transform references
                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof EReference ref) {
                        EReference targetRef = ctx.createTarget(EReference.class);
                        targetRef.setName(ref.getName());
                        targetRef.setLowerBound(ref.getLowerBound());
                        targetRef.setUpperBound(ref.getUpperBound());
                        targetRef.setContainment(ref.isContainment());

                        EClass targetType = ctx.equivalent((EClass) ref.getEType(), EClass.class);
                        if (targetType != null) {
                            targetRef.setEType(targetType);
                        }

                        target.getEStructuralFeatures().add(targetRef);
                        sourceToTargetRef.put(ref, targetRef);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }

        @PostExecution
        public void setupBidirectionalReferences(TransformationContext ctx) {
            // Set up eOpposite relationships after all transformations are complete
            for (java.util.Map.Entry<EReference, EReference> entry : sourceToTargetRef.entrySet()) {
                EReference sourceRef = entry.getKey();
                EReference targetRef = entry.getValue();

                if (sourceRef.getEOpposite() != null) {
                    EReference sourceOpposite = sourceRef.getEOpposite();
                    EReference targetOpposite = sourceToTargetRef.get(sourceOpposite);
                    if (targetOpposite != null && targetRef.getEOpposite() == null) {
                        targetRef.setEOpposite(targetOpposite);
                    }
                }
            }
            // Clear for next test
            sourceToTargetRef.clear();
        }
    }

    /**
     * Transformation for cross-package references.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EPackage.class)
    public static class CrossPackageTransformation {

        @TransformRule(name = "EPackageRule")
        @Transform(type = EPackage.class)
        public TransformFunction<EPackage, EPackage> ePackageRule() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName(source.getName());
                target.setNsURI(source.getNsURI());
                target.setNsPrefix(source.getNsPrefix());

                // Transform classifiers
                for (var classifier : source.getEClassifiers()) {
                    if (classifier instanceof EClass) {
                        EClass targetClass = ctx.equivalent((EClass) classifier, EClass.class);
                        if (targetClass != null) {
                            target.getEClassifiers().add(targetClass);
                        }
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());

                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof EReference ref) {
                        EReference targetRef = ctx.createTarget(EReference.class);
                        targetRef.setName(ref.getName());
                        targetRef.setLowerBound(ref.getLowerBound());
                        targetRef.setUpperBound(ref.getUpperBound());
                        targetRef.setContainment(ref.isContainment());

                        EClass targetType = ctx.equivalent((EClass) ref.getEType(), EClass.class);
                        if (targetType != null) {
                            targetRef.setEType(targetType);
                        }

                        target.getEStructuralFeatures().add(targetRef);
                    }
                }

                // Don't add to resource - package rule will do that
                return target;
            };
        }
    }
}
