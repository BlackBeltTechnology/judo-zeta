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
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for entity-to-transfer-object projection patterns.
 *
 * <p>Verifies transformation framework correctly handles:</p>
 * <ul>
 *   <li>Subset attribute projection (selected attributes only)</li>
 *   <li>Attribute renaming in projections</li>
 *   <li>Subset reference projection</li>
 *   <li>Cardinality changes in projections</li>
 *   <li>Guard-based feature selection</li>
 *   <li>Derived/calculated features in projections</li>
 * </ul>
 */
class EcoreProjectionTest extends AbstractEcoreTransformationTest {

    // ==================== Subset Attribute Projection Tests ====================

    @Nested
    @DisplayName("Subset Attribute Projection")
    class SubsetAttributeProjectionTests {

        @Test
        @DisplayName("Project entity to TO with selected attributes")
        void projectEntityToTransferObjectWithSelectedAttributes() {
            // Create source entity with attributes
            EClass customer = createEClass("Customer");
            customer.getEStructuralFeatures().add(createStringAttribute("id"));
            customer.getEStructuralFeatures().add(createStringAttribute("name"));
            customer.getEStructuralFeatures().add(createStringAttribute("email"));
            customer.getEStructuralFeatures().add(createStringAttribute("password")); // Will be excluded
            customer.getEStructuralFeatures().add(createStringAttribute("createdAt"));

            // Create projection transfer object with only subset of attributes
            registry.register(ProjectionTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(1);

            // Find the CustomerTO in target
            EClass customerTO = findTargetClassByName("CustomerTO");
            assertNotNull(customerTO, "CustomerTO should be created");

            // Verify only selected attributes exist
            assertEClassAttributeCount(customerTO, 3);
            assertEClassHasAttribute(customerTO, "id");
            assertEClassHasAttribute(customerTO, "name");
            assertEClassHasAttribute(customerTO, "email");

            // Verify excluded attribute is NOT present
            assertEClassDoesNotHaveFeature(customerTO, "password");
            assertEClassDoesNotHaveFeature(customerTO, "createdAt");
        }

        @Test
        @DisplayName("Projection with renamed attributes")
        void projectionWithRenamedAttributes() {
            // Create source entity
            EClass person = createEClass("Person");
            person.getEStructuralFeatures().add(createStringAttribute("fullName"));

            // Register projection transformation with renaming
            registry.register(RenamingProjectionTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(1);

            EClass personTO = findTargetClassByName("PersonTO");
            assertNotNull(personTO, "PersonTO should be created");

            // Verify renamed attribute exists
            assertEClassHasAttribute(personTO, "name"); // renamed from fullName

            // Verify original attribute name is not present
            assertEClassDoesNotHaveFeature(personTO, "fullName");
        }
    }

    // ==================== Subset Reference Projection Tests ====================

    @Nested
    @DisplayName("Subset Reference Projection")
    class SubsetReferenceProjectionTests {

        @Test
        @DisplayName("Project entity references to TO with selected references")
        void projectEntityReferencesToTransferObjectWithSelectedReferences() {
            // Create source entity with references
            EClass order = createEClass("Order");
            EClass customer = createEClass("Customer");
            EClass lineItem = createEClass("LineItem");
            EClass shippingAddress = createEClass("ShippingAddress");

            // Add references
            order.getEStructuralFeatures().add(createEReference("customer", customer));
            order.getEStructuralFeatures().add(createEReference("items", lineItem));
            // Create a Payment class for payments reference
            EClass payment = createEClass("Payment");
            order.getEStructuralFeatures().add(createEReference("payments", payment)); // Will be excluded
            order.getEStructuralFeatures().add(createEReference("shippingAddress", shippingAddress));

            // Register projection transformation
            registry.register(ReferenceProjectionTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(5); // OrderTO + Customer, LineItem, ShippingAddress, Payment (via SimpleEClassRule)

            EClass orderTO = findTargetClassByName("OrderTO");
            EClass customerTO = findTargetClassByName("CustomerTO");

            assertNotNull(orderTO, "OrderTO should be created");
            assertNotNull(customerTO, "CustomerTO should be created");

            // Verify selected references exist
            assertEClassHasReference(orderTO, "customer");
            assertEClassHasReference(orderTO, "items");

            // Verify excluded references are not present
            assertEClassDoesNotHaveFeature(orderTO, "payments");
            assertEClassDoesNotHaveFeature(orderTO, "shippingAddress");
        }

        @Test
        @DisplayName("Projection with cardinality change")
        void projectionWithCardinalityChange() {
            // Create source with required collection
            EClass order = createEClass("Order");
            EClass lineItem = createEClass("LineItem");

            // Required collection reference (1..*)
            EReference items = createEReference("items", lineItem, 1, -1);
            order.getEStructuralFeatures().add(items);

            // Register projection transformation that changes cardinality
            registry.register(CardinalityChangeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass orderTO = findTargetClassByName("OrderTO");

            // Verify cardinality changed to optional (0..*)
            EReference itemsRef = findReferenceByName(orderTO, "items");
            assertReferenceCardinality(itemsRef, 0, -1);
        }
    }

    // ==================== Guard-Based Projection Tests ====================

    @Nested
    @DisplayName("Guard-Based Projection")
    class GuardBasedProjectionTests {

        @Test
        @DisplayName("Guard selects features by naming convention")
        void guardSelectsFeaturesByNamingConvention() {
            // Create entity with public/private attributes
            EClass entity = createEClass("Entity");
            entity.getEStructuralFeatures().add(createStringAttribute("publicId"));
            entity.getEStructuralFeatures().add(createStringAttribute("publicName"));
            entity.getEStructuralFeatures().add(createStringAttribute("internalSecret")); // private

            // Register guard-based projection
            registry.register(GuardedProjectionTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass entityTO = findTargetClassByName("EntityTO");

            // Verify only public attributes are projected
            assertEClassHasAttribute(entityTO, "publicId");
            assertEClassHasAttribute(entityTO, "publicName");

            // Verify private attribute is excluded
            assertEClassDoesNotHaveFeature(entityTO, "internalSecret");
        }
    }

    // ==================== Derived Feature Tests ====================

    @Nested
    @DisplayName("Derived Features in Projection")
    class DerivedFeatureTests {

        @Test
        @DisplayName("Derived attribute in projection")
        void derivedAttributeInProjection() {
            // Create source entity with firstName and lastName
            EClass person = createEClass("Person");
            person.getEStructuralFeatures().add(createStringAttribute("firstName"));
            person.getEStructuralFeatures().add(createStringAttribute("lastName"));

            // Register projection transformation with derived attribute
            registry.register(DerivedFeatureTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass personTO = findTargetClassByName("PersonTO");

            // Verify derived attribute exists
            assertEClassHasAttribute(personTO, "fullName");

            // Verify source attributes are also copied
            assertEClassHasAttribute(personTO, "firstName");
            assertEClassHasAttribute(personTO, "lastName");
        }

        @Test
        @DisplayName("Calculated reference in projection")
        void calculatedReferenceInProjection() {
            // Create source with items reference
            EClass order = createEClass("Order");
            EClass lineItem = createEClass("LineItem");

            EReference items = createEReference("items", lineItem, 0, -1);
            order.getEStructuralFeatures().add(items);

            // Register projection with calculated reference
            registry.register(CalculatedReferenceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass orderTO = findTargetClassByName("OrderTO");

            // Verify calculated reference exists (expensiveItems)
            assertEClassHasReference(orderTO, "expensiveItems");

            // Verify source reference is not present
            assertEClassDoesNotHaveFeature(orderTO, "items");
        }
    }

    // ==================== Nested Projection Tests ====================

    @Nested
    @DisplayName("Nested Projection")
    class NestedProjectionTests {

        @Test
        @DisplayName("Nested projection embeds projected references")
        void nestedProjectionEmbedsProjectedReferences() {
            // Create source entities - Customer first so it's processed before Order
            // This ensures equivalent() can find CustomerTO when Order is transformed
            EClass customer = createEClass("Customer");
            EClass order = createEClass("Order");

            // Order references Customer
            EReference customerRef = createEReference("customer", customer, 0, 1);
            order.getEStructuralFeatures().add(customerRef);

            // Register nested projection transformation
            registry.register(NestedProjectionTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify OrderTO exists
            EClass orderTO = findTargetClassByName("OrderTO");
            assertNotNull(orderTO, "OrderTO should be created");

            // Verify CustomerTO exists
            EClass customerTO = findTargetClassByName("CustomerTO");
            assertNotNull(customerTO, "CustomerTO should be created");

            // Verify OrderTO references CustomerTO (nested projection)
            EReference nestedRef = findReferenceByName(orderTO, "customer");
            assertNotNull(nestedRef, "OrderTO should reference CustomerTO");

            // Verify the reference points to the projected CustomerTO
            assertNotNull(nestedRef.getEType(), "Reference type should not be null");
            assertEquals("CustomerTO", ((EClass) nestedRef.getEType()).getName(),
                    "Reference should point to CustomerTO");
        }
    }

    // ==================== Helper Methods ====================

    private EClass findTargetClassByName(String name) {
        for (int i = 0; i < targetResource.getContents().size(); i++) {
            EObject obj = targetResource.getContents().get(i);
            if (obj instanceof EClass) {
                EClass eClass = (EClass) obj;
                if (name.equals(eClass.getName())) {
                    return eClass;
                }
            }
        }
        return null;
    }

    private EReference findReferenceByName(EClass eClass, String name) {
        for (EReference ref : eClass.getEReferences()) {
            if (name.equals(ref.getName())) {
                return ref;
            }
        }
        return null;
    }

    // ==================== Transformation Classes ====================

    /**
     * Simple transformation projecting selected attributes.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ProjectionTransformation {

        private static final Set<String> PROJECTED_ATTRIBUTES = Set.of("id", "name", "email");

        @TransformRule(name = "CustomerTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> customerToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Customer")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Project only selected attributes
                for (var attr : source.getEAttributes()) {
                    if (PROJECTED_ATTRIBUTES.contains(attr.getName())) {
                        var targetAttr = ctx.createTarget(EAttribute.class);
                        targetAttr.setName(attr.getName());
                        targetAttr.setEType(attr.getEType());
                        target.getEStructuralFeatures().add(targetAttr);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation with attribute renaming.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class RenamingProjectionTransformation {

        private static final Set<String> RENAMED_ATTRIBUTES = Set.of("fullName");

        @TransformRule(name = "PersonTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> personToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Person")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Rename fullName -> name
                for (var attr : source.getEAttributes()) {
                    if (RENAMED_ATTRIBUTES.contains(attr.getName())) {
                        var targetAttr = ctx.createTarget(EAttribute.class);
                        targetAttr.setName("name"); // Renamed from fullName
                        targetAttr.setEType(attr.getEType());
                        target.getEStructuralFeatures().add(targetAttr);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation projecting selected references.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ReferenceProjectionTransformation {

        private static final Set<String> PROJECTED_REFERENCES = Set.of("customer", "items");

        @TransformRule(name = "OrderTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> orderToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Project only selected references
                for (EReference ref : source.getEReferences()) {
                    if (PROJECTED_REFERENCES.contains(ref.getName())) {
                        EReference targetRef = ctx.createTarget(EReference.class);
                        targetRef.setName(ref.getName());
                        targetRef.setEType(ctx.equivalent((EClass) ref.getEType(), EClass.class));
                        targetRef.setLowerBound(ref.getLowerBound());
                        targetRef.setUpperBound(ref.getUpperBound());
                        target.getEStructuralFeatures().add(targetRef);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }

        @TransformRule(name = "SimpleEClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> simpleEClassRule() {
            return (source, ctx) -> {
                if (source.getName().equals("Order")) {
                    return null; // Handled by OrderTORule
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation with cardinality change.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CardinalityChangeTransformation {

        @TransformRule(name = "OrderTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> orderToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Transform references with changed cardinality
                for (EReference ref : source.getEReferences()) {
                    EReference targetRef = ctx.createTarget(EReference.class);
                    targetRef.setName(ref.getName());
                    targetRef.setEType(ctx.equivalent((EClass) ref.getEType(), EClass.class));
                    // Change cardinality: required -> optional
                    targetRef.setLowerBound(0); // Was 1
                    targetRef.setUpperBound(ref.getUpperBound());
                    target.getEStructuralFeatures().add(targetRef);
                }

                ctx.addToResource(target);
                return target;
            };
        }

        @TransformRule(name = "SimpleEClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> simpleEClassRule() {
            return (source, ctx) -> {
                if (source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Guard-based projection transformation.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class GuardedProjectionTransformation {

        @TransformRule(name = "EntityTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> entityToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Entity")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Only project attributes starting with "public"
                for (var attr : source.getEAttributes()) {
                    if (attr.getName().startsWith("public")) {
                        var targetAttr = ctx.createTarget(EAttribute.class);
                        targetAttr.setName(attr.getName());
                        targetAttr.setEType(attr.getEType());
                        target.getEStructuralFeatures().add(targetAttr);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation with derived attributes.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class DerivedFeatureTransformation {

        @TransformRule(name = "PersonTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> personToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Person")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Copy existing attributes
                for (var attr : source.getEAttributes()) {
                    var targetAttr = ctx.createTarget(EAttribute.class);
                    targetAttr.setName(attr.getName());
                    targetAttr.setEType(attr.getEType());
                    target.getEStructuralFeatures().add(targetAttr);
                }

                // Add derived attribute (fullName)
                var derivedAttr = ctx.createTarget(EAttribute.class);
                derivedAttr.setName("fullName");
                derivedAttr.setEType(EcorePackage.eINSTANCE.getEString());
                derivedAttr.setDerived(true);
                target.getEStructuralFeatures().add(derivedAttr);

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation with calculated references.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class CalculatedReferenceTransformation {

        @TransformRule(name = "OrderTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> orderToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Find the items reference target type for the calculated reference
                EClass lineItemType = null;
                for (EReference ref : source.getEReferences()) {
                    if (ref.getName().equals("items")) {
                        lineItemType = (EClass) ref.getEType();
                        break;
                    }
                }

                // Add only calculated reference (expensiveItems) - NOT the original items
                EReference expensiveRef = ctx.createTarget(EReference.class);
                expensiveRef.setName("expensiveItems");
                if (lineItemType != null) {
                    expensiveRef.setEType(ctx.equivalent(lineItemType, EClass.class));
                }
                expensiveRef.setLowerBound(0);
                expensiveRef.setUpperBound(-1);
                target.getEStructuralFeatures().add(expensiveRef);

                ctx.addToResource(target);
                return target;
            };
        }

        @TransformRule(name = "SimpleEClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> simpleEClassRule() {
            return (source, ctx) -> {
                if (source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Nested projection transformation.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class NestedProjectionTransformation {

        @TransformRule(name = "OrderTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> orderToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Order")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");

                // Transform customer reference to CustomerTO
                for (EReference ref : source.getEReferences()) {
                    EReference targetRef = ctx.createTarget(EReference.class);
                    targetRef.setName(ref.getName());
                    // Get the projected CustomerTO - equivalent triggers lazy transformation
                    EClass customerTO = ctx.equivalent((EClass) ref.getEType(), EClass.class);
                    if (customerTO != null) {
                        targetRef.setEType(customerTO);
                    }
                    targetRef.setLowerBound(ref.getLowerBound());
                    targetRef.setUpperBound(ref.getUpperBound());
                    target.getEStructuralFeatures().add(targetRef);
                }

                ctx.addToResource(target);
                return target;
            };
        }

        @TransformRule(name = "CustomerTORule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> customerToRule() {
            return (source, ctx) -> {
                if (!source.getName().equals("Customer")) {
                    return null;
                }
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "TO");
                ctx.addToResource(target);
                return target;
            };
        }
    }
}
