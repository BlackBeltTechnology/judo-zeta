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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for ETL-style structured XMI ID generation.
 *
 * <p>Structured IDs follow the ETL pattern:
 * {@code <source-container>/(esm/<source-id>)/<rule-name>/(discriminator/<discriminator-value>)}</p>
 */
class StructuredIdTest {

    private TransformationContext context;
    private TransformationRegistry registry;
    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    @BeforeEach
    void setUp() {
        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ModelProvider modelProvider = new TestModelProvider();
        ExtensionMethodRegistry extensionRegistry = mock(ExtensionMethodRegistry.class);

        context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                targetResourceSet,
                extensionRegistry
        );
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.setAutoAddRootElements(true);
        context.setUseStructuredIds(true); // Enable structured IDs (default)

        registry = new TransformationRegistry();
    }

    private EClass createEClass(String name, String xmiId) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        // Set XMI ID on source element
        if (sourceResource instanceof XMIResource && xmiId != null) {
            ((XMIResource) sourceResource).setID(eClass, xmiId);
        }
        return eClass;
    }

    // ==================== Structured ID Tests ====================

    @Nested
    @DisplayName("Structured ID Generation Tests")
    class StructuredIdGenerationTests {

        @Test
        @DisplayName("Structured ID includes source element name and ID")
        void structuredIdIncludesSourceInfo() {
            EClass source = createEClass("Customer", "_abc123");

            registry.register(SimpleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Check the target element's ID
            assertEquals(1, targetResource.getContents().size());
            EPackage target = (EPackage) targetResource.getContents().get(0);

            String targetId = context.getPendingXmiId(target);
            if (targetId == null && targetResource instanceof XMIResource) {
                targetId = ((XMIResource) targetResource).getID(target);
            }

            assertNotNull(targetId, "Target should have an XMI ID");
            // ID should contain source name, alias, and rule name
            // The alias is "source" by default (registered in TransformationContext constructor)
            assertTrue(targetId.contains("Customer"), "ID should contain source name: " + targetId);
            assertTrue(targetId.contains("source/_abc123"), "ID should contain alias and source ID: " + targetId);
            assertTrue(targetId.contains("Entity2Package"), "ID should contain rule name: " + targetId);
        }

        @Test
        @DisplayName("Structured ID format follows ETL pattern with source alias")
        void structuredIdFollowsEtlPattern() {
            EClass source = createEClass("Order", "_def456");

            registry.register(SimpleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EPackage target = (EPackage) targetResource.getContents().get(0);
            String targetId = context.getPendingXmiId(target);
            if (targetId == null && targetResource instanceof XMIResource) {
                targetId = ((XMIResource) targetResource).getID(target);
            }

            // Expected format: <name>/(<alias>/<id>)/<rule-name>
            // The alias is "source" by default (registered in constructor)
            // Example: Order/(source/_def456)/Entity2Package
            assertNotNull(targetId);
            assertTrue(targetId.matches("Order/\\(source/_def456\\)/Entity2Package"),
                    "ID should match ETL pattern with source alias: " + targetId);
        }

        @Test
        @DisplayName("Discriminated ID includes discriminator suffix")
        void discriminatedIdIncludesDiscriminator() {
            EClass source = createEClass("Product", "_ghi789");

            registry.register(DiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);

            // Get discriminated equivalent
            EAnnotation ann1 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "variant1");

            assertNotNull(ann1);
            String id1 = context.getPendingXmiId(ann1);
            if (id1 == null && targetResource instanceof XMIResource) {
                id1 = ((XMIResource) targetResource).getID(ann1);
            }

            assertNotNull(id1, "Discriminated element should have ID");
            assertTrue(id1.contains("discriminator/variant1"),
                    "ID should contain discriminator: " + id1);
            assertTrue(id1.contains("LazyAnnotation"),
                    "ID should contain rule name: " + id1);
        }

        @Test
        @DisplayName("Different discriminators produce different IDs")
        void differentDiscriminatorsProduceDifferentIds() {
            EClass source = createEClass("Invoice", "_jkl012");

            registry.register(DiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);

            // Get two discriminated equivalents with different discriminators
            EAnnotation ann1 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "relation1");
            EAnnotation ann2 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "relation2");

            String id1 = context.getPendingXmiId(ann1);
            String id2 = context.getPendingXmiId(ann2);

            assertNotNull(id1);
            assertNotNull(id2);
            assertNotEquals(id1, id2, "Different discriminators should produce different IDs");
            assertTrue(id1.contains("discriminator/relation1"));
            assertTrue(id2.contains("discriminator/relation2"));
        }

        @Test
        @DisplayName("Same discriminator returns same cached instance")
        void sameDiscriminatorReturnsSameInstance() {
            EClass source = createEClass("Account", "_mno345");

            registry.register(DiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);

            // Call twice with same discriminator
            EAnnotation ann1 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "sameDisc");
            EAnnotation ann2 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "sameDisc");

            assertSame(ann1, ann2, "Same discriminator should return same cached instance");
        }

        @Test
        @DisplayName("XMI ID-based lookup finds existing element with structured IDs")
        void xmiIdBasedLookupFindsExistingElement() {
            EClass source = createEClass("Order", "_xyz999");

            registry.register(DiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            // First call - creates the element
            EAnnotation first = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "lookup-test");

            assertNotNull(first);
            String firstId = context.getPendingXmiId(first);
            assertNotNull(firstId, "First element should have XMI ID");
            assertTrue(firstId.contains("discriminator/lookup-test"),
                    "ID should contain discriminator: " + firstId);

            // Second call with same discriminator - should find by XMI ID
            EAnnotation second = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "lookup-test");

            assertSame(first, second, "Should return same instance via XMI ID lookup");
        }

        @Test
        @DisplayName("XMI ID-based lookup disabled when useStructuredIds is false")
        void xmiIdLookupDisabledWithoutStructuredIds() {
            EClass source = createEClass("Product", "_abc000");

            registry.register(DiscriminatedTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(false);

            // First call
            EAnnotation first = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "no-structured");

            // Second call - uses cache, not XMI ID lookup
            EAnnotation second = context.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyAnnotation", "no-structured");

            assertSame(first, second, "Should return same instance via object cache");

            // Verify IDs are sequence-based, not structured
            String firstId = context.getPendingXmiId(first);
            assertTrue(firstId.startsWith("_seq"), "ID should be sequence-based: " + firstId);
        }

        @Test
        @DisplayName("Disabled structured IDs uses sequence-based format")
        void disabledStructuredIdsUsesSequence() {
            context.setUseStructuredIds(false);

            EClass source = createEClass("User", "_pqr678");

            registry.register(SimpleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EPackage target = (EPackage) targetResource.getContents().get(0);
            String targetId = context.getPendingXmiId(target);

            assertNotNull(targetId);
            // Sequence-based format: _seqN (deterministic for reproducibility)
            assertTrue(targetId.startsWith("_seq"), "Should use sequence format: " + targetId);
            assertFalse(targetId.contains("/"), "Should not contain slash: " + targetId);
            assertFalse(targetId.contains("("), "Should not contain parentheses: " + targetId);
        }

        @Test
        @DisplayName("Structured ID works for source without name attribute")
        void structuredIdWorksWithoutName() {
            // Create an EAnnotation (no name attribute) as source
            EAnnotation source = EcoreFactory.eINSTANCE.createEAnnotation();
            source.setSource("test-annotation");
            sourceResource.getContents().add(source);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(source, "_ann001");
            }

            registry.register(AnnotationTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Should still work with just the ID
            assertEquals(1, targetResource.getContents().size());
            EPackage target = (EPackage) targetResource.getContents().get(0);
            String targetId = context.getPendingXmiId(target);

            assertNotNull(targetId);
            assertTrue(targetId.contains("source/_ann001"), "ID should contain alias and source ID: " + targetId);
        }
    }

    // ==================== Nested Equivalent ID Tests ====================

    /**
     * Tests for the Company/Customer inheritance bug fix.
     *
     * <p>When Company's rule calls ctx.equivalent(customer, ...) to get Customer's
     * equivalent, the XMI IDs must be generated from the CORRECT source elements:</p>
     * <ul>
     *   <li>Customer's target: ID generated from Customer source</li>
     *   <li>Company's target: ID generated from Company source</li>
     * </ul>
     *
     * <p>Before the fix, both would incorrectly use Company's source ID because
     * currentSource was not properly scoped during nested rule execution.</p>
     */
    @Nested
    @DisplayName("Nested Equivalent ID Generation Tests (Company/Customer Bug)")
    class NestedEquivalentIdTests {

        @Test
        @DisplayName("Nested equivalent() generates ID from correct source element")
        void nestedEquivalentUsesCorrectSourceForId() {
            // Create Customer (base) and Company (extends Customer)
            EClass customer = createEClass("Customer", "_JYmqeeq1EemUZMITjXqp8w");
            EClass company = createEClass("Company", "_JYmDhOq1EemUZMITjXqp8w");

            // Set up inheritance: Company extends Customer
            company.getESuperTypes().add(customer);

            registry.register(InheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get the transformed targets
            EPackage customerPkg = context.equivalent(customer, EPackage.class);
            EPackage companyPkg = context.equivalent(company, EPackage.class);

            assertNotNull(customerPkg, "Customer should have been transformed");
            assertNotNull(companyPkg, "Company should have been transformed");

            // Get their XMI IDs
            String customerId = context.getPendingXmiId(customerPkg);
            String companyId = context.getPendingXmiId(companyPkg);

            if (customerId == null && targetResource instanceof XMIResource) {
                customerId = ((XMIResource) targetResource).getID(customerPkg);
            }
            if (companyId == null && targetResource instanceof XMIResource) {
                companyId = ((XMIResource) targetResource).getID(companyPkg);
            }

            assertNotNull(customerId, "Customer target should have XMI ID");
            assertNotNull(companyId, "Company target should have XMI ID");

            // CRITICAL: IDs must be DIFFERENT (generated from respective sources)
            assertNotEquals(customerId, companyId,
                    "Customer and Company must have different XMI IDs! " +
                    "Customer ID: " + customerId + ", Company ID: " + companyId);

            // Verify IDs contain correct source element IDs
            assertTrue(customerId.contains("_JYmqeeq1EemUZMITjXqp8w"),
                    "Customer ID should contain Customer's source ID: " + customerId);
            assertTrue(companyId.contains("_JYmDhOq1EemUZMITjXqp8w"),
                    "Company ID should contain Company's source ID: " + companyId);

            // Verify IDs contain correct source names
            assertTrue(customerId.contains("Customer"),
                    "Customer ID should contain 'Customer': " + customerId);
            assertTrue(companyId.contains("Company"),
                    "Company ID should contain 'Company': " + companyId);
        }

        @Test
        @DisplayName("Supertype reference resolves to correct target (not self-reference)")
        void supertypeReferenceResolvesCorrectly() {
            // Create Customer (base) and Company (extends Customer)
            EClass customer = createEClass("Customer", "_customer123");
            EClass company = createEClass("Company", "_company456");

            // Set up inheritance: Company extends Customer
            company.getESuperTypes().add(customer);

            registry.register(InheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get the transformed targets
            EPackage customerPkg = context.equivalent(customer, EPackage.class);
            EPackage companyPkg = context.equivalent(company, EPackage.class);

            // Verify they are different instances
            assertNotSame(customerPkg, companyPkg,
                    "Customer and Company targets must be different instances");

            // Verify no duplicate XMI IDs in target resource
            java.util.Set<String> xmiIds = new java.util.HashSet<>();
            for (EObject obj : targetResource.getContents()) {
                String id = context.getPendingXmiId(obj);
                if (id == null && targetResource instanceof XMIResource) {
                    id = ((XMIResource) targetResource).getID(obj);
                }
                if (id != null) {
                    assertTrue(xmiIds.add(id),
                            "Duplicate XMI ID found: " + id);
                }
            }
        }

        @Test
        @DisplayName("Deeply nested equivalent() calls maintain correct source context")
        void deeplyNestedEquivalentMaintainsContext() {
            // Create 3-level inheritance: Person -> Customer -> Company
            EClass person = createEClass("Person", "_person001");
            EClass customer = createEClass("Customer", "_customer002");
            EClass company = createEClass("Company", "_company003");

            customer.getESuperTypes().add(person);
            company.getESuperTypes().add(customer);

            registry.register(DeepInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get all transformed targets
            EPackage personPkg = context.equivalent(person, EPackage.class);
            EPackage customerPkg = context.equivalent(customer, EPackage.class);
            EPackage companyPkg = context.equivalent(company, EPackage.class);

            // Get their XMI IDs
            String personId = getXmiId(personPkg);
            String customerId = getXmiId(customerPkg);
            String companyId = getXmiId(companyPkg);

            // All three must have different IDs
            assertNotEquals(personId, customerId, "Person and Customer must have different IDs");
            assertNotEquals(customerId, companyId, "Customer and Company must have different IDs");
            assertNotEquals(personId, companyId, "Person and Company must have different IDs");

            // Verify each ID contains correct source element ID
            assertTrue(personId.contains("_person001"), "Person ID incorrect: " + personId);
            assertTrue(customerId.contains("_customer002"), "Customer ID incorrect: " + customerId);
            assertTrue(companyId.contains("_company003"), "Company ID incorrect: " + companyId);
        }

        private String getXmiId(EObject obj) {
            String id = context.getPendingXmiId(obj);
            if (id == null && targetResource instanceof XMIResource) {
                id = ((XMIResource) targetResource).getID(obj);
            }
            return id;
        }

        /**
         * Test for TransferObjectRelation bug pattern:
         * Rule processes RelationMember, calls equivalent() on target EntityType.
         * IDs must use the correct source element for each target.
         */
        @Test
        @DisplayName("Relation member rule resolving target type uses correct source for ID")
        void relationMemberResolvingTargetTypeUsesCorrectSource() {
            // Simulate: RelationMember "items" referencing EntityType "Product"
            EClass productEntity = createEClass("Product", "_sRDt4PKfEeqHK7TZJcAcOA");
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            itemsRelation.setEType(productEntity);
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_s8FI9v8MEem4dONaAfrVDg");
            }

            registry.register(RelationMemberTransformation.class);
            registry.register(EntityTypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get the transformed relation and entity type
            EAnnotation relationTarget = context.equivalent(itemsRelation, EAnnotation.class);
            EPackage entityTarget = context.equivalent(productEntity, EPackage.class);

            assertNotNull(relationTarget, "Relation should be transformed");
            assertNotNull(entityTarget, "EntityType should be transformed");

            // Get their XMI IDs
            String relationId = getXmiId(relationTarget);
            String entityId = getXmiId(entityTarget);

            assertNotNull(relationId, "Relation target should have XMI ID");
            assertNotNull(entityId, "EntityType target should have XMI ID");

            // CRITICAL: IDs must be DIFFERENT
            assertNotEquals(relationId, entityId,
                    "Relation and EntityType targets must have different XMI IDs! " +
                    "Relation ID: " + relationId + ", Entity ID: " + entityId);

            // Relation ID should contain relation's source ID
            assertTrue(relationId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Relation ID should contain relation's source ID: " + relationId);

            // Entity ID should contain entity's source ID (NOT relation's ID!)
            assertTrue(entityId.contains("_sRDt4PKfEeqHK7TZJcAcOA"),
                    "Entity ID should contain entity's source ID: " + entityId);
            assertFalse(entityId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Entity ID must NOT contain relation's source ID: " + entityId);
        }

        /**
         * Test multiple relations referencing the same target type.
         * All relation targets should have unique IDs, and target type should have its own ID.
         */
        @Test
        @DisplayName("Multiple relations to same target type all get unique IDs")
        void multipleRelationsToSameTargetGetUniqueIds() {
            // Create target entity type
            EClass productEntity = createEClass("Product", "_product123");

            // Create two relations both pointing to Product
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            itemsRelation.setEType(productEntity);
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_items456");
            }

            EReference productsRelation = EcoreFactory.eINSTANCE.createEReference();
            productsRelation.setName("products");
            productsRelation.setEType(productEntity);
            sourceResource.getContents().add(productsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(productsRelation, "_products789");
            }

            registry.register(RelationMemberTransformation.class);
            registry.register(EntityTypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get all targets
            EAnnotation itemsTarget = context.equivalent(itemsRelation, EAnnotation.class);
            EAnnotation productsTarget = context.equivalent(productsRelation, EAnnotation.class);
            EPackage productTarget = context.equivalent(productEntity, EPackage.class);

            // Get IDs
            String itemsId = getXmiId(itemsTarget);
            String productsId = getXmiId(productsTarget);
            String productId = getXmiId(productTarget);

            // All three must be different
            assertNotEquals(itemsId, productsId, "items and products must have different IDs");
            assertNotEquals(itemsId, productId, "items and Product must have different IDs");
            assertNotEquals(productsId, productId, "products and Product must have different IDs");

            // Each ID should contain its own source ID
            assertTrue(itemsId.contains("_items456"), "items ID incorrect: " + itemsId);
            assertTrue(productsId.contains("_products789"), "products ID incorrect: " + productsId);
            assertTrue(productId.contains("_product123"), "Product ID incorrect: " + productId);
        }

        /**
         * Test for executeParentRule() bug pattern:
         * Outer rule calls executeParentRule() with a DIFFERENT source element.
         * The parent rule's target should have ID generated from the passed source,
         * not from the outer rule's source.
         *
         * NOTE: This test has OrderItem in sourceResource.getContents(), so it gets
         * processed as a primary element BEFORE the relation calls executeParentRule.
         * See executeParentRuleCreatesNewElementWithCorrectContext for the failing case.
         */
        @Test
        @DisplayName("executeParentRule() with different source uses correct source for ID")
        void executeParentRuleWithDifferentSourceUsesCorrectId() {
            // Create the outer rule's source (relation member "items")
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_s8FI9v8MEem4dONaAfrVDg");
            }

            // Create the target type (OrderItem) that executeParentRule will be called with
            EClass orderItemEntity = createEClass("OrderItem", "_orderItem123");
            itemsRelation.setEType(orderItemEntity);

            registry.register(OuterRuleWithExecuteParentRule.class);
            registry.register(ParentRuleForExecuteParentRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get targets - outer rule creates an annotation, parent rule creates a package
            // The outer rule calls executeParentRule for the target type
            EAnnotation outerTarget = null;
            EPackage parentTarget = null;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EAnnotation) {
                    outerTarget = (EAnnotation) obj;
                } else if (obj instanceof EPackage) {
                    parentTarget = (EPackage) obj;
                }
            }

            assertNotNull(outerTarget, "Outer rule should create annotation");
            assertNotNull(parentTarget, "Parent rule should create package");

            // Get their XMI IDs
            String outerId = getXmiId(outerTarget);
            String parentId = getXmiId(parentTarget);

            assertNotNull(outerId, "Outer target should have XMI ID");
            assertNotNull(parentId, "Parent target should have XMI ID");

            // CRITICAL: IDs must be DIFFERENT
            assertNotEquals(outerId, parentId,
                    "Outer and parent targets must have different XMI IDs!");

            // Outer ID should contain outer rule's source ID and rule name
            assertTrue(outerId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Outer ID should contain outer source ID: " + outerId);
            assertTrue(outerId.contains("OuterRule"),
                    "Outer ID should contain outer rule name: " + outerId);

            // Parent ID should contain PARENT RULE's source ID (OrderItem) and rule name
            // NOT the outer rule's source ID!
            assertTrue(parentId.contains("_orderItem123"),
                    "Parent ID should contain OrderItem's source ID: " + parentId);
            assertTrue(parentId.contains("ParentRule"),
                    "Parent ID should contain parent rule name: " + parentId);

            // Parent ID must NOT contain outer rule's source ID or name
            assertFalse(parentId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Parent ID must NOT contain outer source ID: " + parentId);
            assertFalse(parentId.contains("OuterRule"),
                    "Parent ID must NOT contain outer rule name: " + parentId);
        }

        /**
         * CRITICAL TEST: Reproduces the Northwind bug where:
         * 1. Only the relation is processed as a primary element
         * 2. The target type is NOT in sourceResource.getContents() (not a primary element)
         * 3. executeParentRule creates it on-demand
         * 4. The ID must use the NEW source's context, not the caller's context
         *
         * This is the exact scenario that fails in Northwind where:
         * - TransferObjectRelation 'items' is processed
         * - It calls executeParentRule("CreateMappedTransferObjectType", orderItemTO)
         * - OrderItem TO wasn't processed yet as a primary element
         * - The created element incorrectly gets items' ID instead of OrderItem's ID
         */
        @Test
        @DisplayName("executeParentRule() creating NEW element uses newSource context (not caller's)")
        void executeParentRuleCreatesNewElementWithCorrectContext() {
            // Create the outer rule's source (relation member "items") - THIS is the primary element
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_s8FI9v8MEem4dONaAfrVDg");
            }

            // Create the target type (OrderItem) - NOT added to sourceResource.getContents()!
            // It's only reachable via the relation's eType reference
            EClass orderItemEntity = EcoreFactory.eINSTANCE.createEClass();
            orderItemEntity.setName("OrderItem");
            // Add to a separate container, not sourceResource.getContents()
            EPackage container = EcoreFactory.eINSTANCE.createEPackage();
            container.setName("types");
            container.getEClassifiers().add(orderItemEntity);
            sourceResource.getContents().add(container);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(orderItemEntity, "_s8FwAv8MEem4dONaAfrVDg");
            }
            itemsRelation.setEType(orderItemEntity);

            // Register ONLY the outer rule - no rule for EClass directly
            // This ensures OrderItem is NOT processed as a primary element
            registry.register(OuterRuleWithExecuteParentRule.class);
            registry.register(ParentRuleForExecuteParentRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get targets
            EAnnotation outerTarget = null;
            EPackage parentTarget = null;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EAnnotation) {
                    outerTarget = (EAnnotation) obj;
                } else if (obj instanceof EPackage) {
                    parentTarget = (EPackage) obj;
                }
            }

            assertNotNull(outerTarget, "Outer rule should create annotation");
            assertNotNull(parentTarget, "Parent rule should create package (via executeParentRule)");

            // Get their XMI IDs
            String outerId = getXmiId(outerTarget);
            String parentId = getXmiId(parentTarget);

            assertNotNull(outerId, "Outer target should have XMI ID");
            assertNotNull(parentId, "Parent target should have XMI ID");

            // CRITICAL: IDs must be DIFFERENT
            assertNotEquals(outerId, parentId,
                    "Outer (items) and parent (OrderItem) must have different XMI IDs!");

            // Outer ID should contain outer rule's source ID and rule name
            assertTrue(outerId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Outer ID should contain items' source ID: " + outerId);
            assertTrue(outerId.contains("OuterRule"),
                    "Outer ID should contain OuterRule: " + outerId);

            // CRITICAL: Parent ID must contain OrderItem's source ID and ParentRule name
            // NOT the caller's (items) context!
            assertTrue(parentId.contains("_s8FwAv8MEem4dONaAfrVDg"),
                    "Parent ID should contain OrderItem's source ID (_s8FwAv8MEem4dONaAfrVDg): " + parentId);
            assertTrue(parentId.contains("ParentRule"),
                    "Parent ID should contain ParentRule: " + parentId);
            assertTrue(parentId.contains("OrderItem"),
                    "Parent ID should contain 'OrderItem' name: " + parentId);

            // Parent ID must NOT contain the caller's (items) context
            assertFalse(parentId.contains("_s8FI9v8MEem4dONaAfrVDg"),
                    "Parent ID must NOT contain items' source ID: " + parentId);
            assertFalse(parentId.contains("OuterRule"),
                    "Parent ID must NOT contain OuterRule: " + parentId);
            assertFalse(parentId.contains("items"),
                    "Parent ID must NOT contain 'items' name: " + parentId);
        }

        /**
         * Test executeParentRule with a parent rule that has @Extends.
         * This exercises the executeWithInheritance() code path.
         */
        @Test
        @DisplayName("executeParentRule() with @Extends parent rule uses correct source for ID")
        void executeParentRuleWithExtendsUsesCorrectContext() {
            // Create the outer rule's source (relation member "items")
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_rel123");
            }

            // Create the target type - NOT a primary element
            EClass orderItemEntity = EcoreFactory.eINSTANCE.createEClass();
            orderItemEntity.setName("OrderItem");
            EPackage container = EcoreFactory.eINSTANCE.createEPackage();
            container.setName("types");
            container.getEClassifiers().add(orderItemEntity);
            sourceResource.getContents().add(container);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(orderItemEntity, "_orderItem789");
            }
            itemsRelation.setEType(orderItemEntity);

            registry.register(OuterRuleCallingExtendsParent.class);
            registry.register(BaseRuleForExtends.class);
            registry.register(ChildRuleWithExtends.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get targets - package may be in references if rule is detached
            EAnnotation outerTarget = null;
            EPackage parentTarget = null;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EAnnotation) {
                    outerTarget = (EAnnotation) obj;
                } else if (obj instanceof EPackage) {
                    parentTarget = (EPackage) obj;
                }
            }

            assertNotNull(outerTarget, "Outer rule should create annotation");
            // Check references for the package (may not be in resource contents)
            if (parentTarget == null && outerTarget != null) {
                for (EObject ref : outerTarget.getReferences()) {
                    if (ref instanceof EPackage) {
                        parentTarget = (EPackage) ref;
                    }
                }
            }
            assertNotNull(parentTarget, "Parent rule with @Extends should create package");

            String outerId = getXmiId(outerTarget);
            String parentId = getXmiId(parentTarget);

            // Parent ID must use OrderItem's context, not items' context
            assertTrue(parentId.contains("_orderItem789"),
                    "Parent ID should contain OrderItem's source ID: " + parentId);
            assertTrue(parentId.contains("ChildRule"),
                    "Parent ID should contain ChildRule: " + parentId);
            assertFalse(parentId.contains("_rel123"),
                    "Parent ID must NOT contain relation's source ID: " + parentId);
        }

        /**
         * EXACT BUG REPRODUCTION: executeParentRule called through static helper method.
         * This matches the judo-tatami bug where @ExtensionMethod calls executeParentRule.
         *
         * Call chain:
         * Rule A (processing items) -> helper method -> static method -> executeParentRule(RuleB, orderItemTO)
         */
        @Test
        @DisplayName("executeParentRule via static helper method uses correct source context")
        void executeParentRuleViaStaticHelperUsesCorrectContext() {
            // Create the outer rule's source (relation member "items")
            EReference itemsRelation = EcoreFactory.eINSTANCE.createEReference();
            itemsRelation.setName("items");
            sourceResource.getContents().add(itemsRelation);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(itemsRelation, "_items_source_id");
            }

            // Create the target type - NOT a primary element
            EClass orderItemTO = EcoreFactory.eINSTANCE.createEClass();
            orderItemTO.setName("OrderItem");
            EPackage container = EcoreFactory.eINSTANCE.createEPackage();
            container.setName("types");
            container.getEClassifiers().add(orderItemTO);
            sourceResource.getContents().add(container);
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(orderItemTO, "_orderitem_source_id");
            }
            itemsRelation.setEType(orderItemTO);

            registry.register(RuleCallingStaticHelper.class);
            registry.register(ParentRuleForStaticHelper.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Get targets
            EAnnotation outerTarget = null;
            EPackage parentTarget = null;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EAnnotation) {
                    outerTarget = (EAnnotation) obj;
                } else if (obj instanceof EPackage) {
                    parentTarget = (EPackage) obj;
                }
            }

            assertNotNull(outerTarget, "Outer rule should create annotation");
            if (parentTarget == null && outerTarget != null) {
                for (EObject ref : outerTarget.getReferences()) {
                    if (ref instanceof EPackage) {
                        parentTarget = (EPackage) ref;
                    }
                }
            }
            assertNotNull(parentTarget, "Parent rule should create package via static helper");

            String outerId = getXmiId(outerTarget);
            String parentId = getXmiId(parentTarget);

            // Outer ID should contain items' source ID and outer rule name
            assertTrue(outerId.contains("_items_source_id"),
                    "Outer ID should contain items' source ID: " + outerId);
            assertTrue(outerId.contains("RuleCallingHelper"),
                    "Outer ID should contain outer rule name: " + outerId);

            // Parent ID MUST contain OrderItem's source ID and parent rule name
            // NOT the caller's (items) context!
            assertTrue(parentId.contains("_orderitem_source_id"),
                    "Parent ID should contain OrderItem's source ID: " + parentId);
            assertTrue(parentId.contains("ParentRuleViaHelper"),
                    "Parent ID should contain ParentRuleViaHelper: " + parentId);
            assertTrue(parentId.contains("OrderItem"),
                    "Parent ID should contain 'OrderItem' name: " + parentId);

            // Parent ID must NOT contain the caller's (items) context
            assertFalse(parentId.contains("_items_source_id"),
                    "Parent ID must NOT contain items' source ID: " + parentId);
            assertFalse(parentId.contains("RuleCallingHelper"),
                    "Parent ID must NOT contain outer rule name: " + parentId);
            assertFalse(parentId.contains("items"),
                    "Parent ID must NOT contain 'items' name: " + parentId);
        }
    }

    // ==================== Transformation Classes ====================

    /**
     * Static helper simulating @ExtensionMethod behavior.
     * This is the pattern that caused the bug in judo-tatami.
     */
    static class ExtensionMethodHelper {
        /**
         * Simulates @Cached @ExtensionMethod getPSMTransferObjectTypeEquivalent.
         */
        public static EPackage getEquivalent(EClass self, TransformationContext ctx) {
            return ctx.executeParentRule("ParentRuleViaHelper", self);
        }
    }

    /**
     * Rule that calls executeParentRule through a static helper method.
     * Simulates the judo-tatami pattern where @ExtensionMethod calls executeParentRule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class RuleCallingStaticHelper {
        @TransformRule(name = "RuleCallingHelper")
        @Transform(type = EReference.class)
        public TransformFunction<EReference, EAnnotation> ruleCallingHelper() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("outer_" + source.getName());

                EClassifier targetType = source.getEType();
                if (targetType instanceof EClass) {
                    // Call through static helper - simulates @ExtensionMethod
                    EPackage result = ExtensionMethodHelper.getEquivalent((EClass) targetType, ctx);
                    if (result != null) {
                        ann.getReferences().add(result);
                    }
                }

                return ann;
            };
        }
    }

    /**
     * Parent rule called via the static helper.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentRuleForStaticHelper {
        @TransformRule(name = "ParentRuleViaHelper")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("mapped_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Outer rule that calls executeParentRule on a @Lazy rule with @Extends.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class OuterRuleCallingExtendsParent {
        @TransformRule(name = "OuterRuleForExtends")
        @Transform(type = EReference.class)
        public TransformFunction<EReference, EAnnotation> outerRule() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("outer_" + source.getName());

                EClassifier targetType = source.getEType();
                if (targetType instanceof EClass) {
                    // Call executeParentRule on a rule that has @Extends
                    EPackage parentResult = ctx.executeParentRule("ChildRule", (EClass) targetType);
                    if (parentResult != null) {
                        ann.getReferences().add(parentResult);
                    }
                }

                return ann;
            };
        }
    }

    /**
     * Base rule for testing @Extends inheritance.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class BaseRuleForExtends {
        @TransformRule(name = "BaseRule")
        @Transform(type = EClass.class)
        @Lazy
        @Abstract
        public TransformFunction<EClass, EPackage> baseRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("base_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Child rule with @Extends that should use the correct source context.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ChildRuleWithExtends {
        @TransformRule(name = "ChildRule")
        @Transform(type = EClass.class)
        @Lazy
        @Extends("BaseRule")
        public TransformFunction<EClass, EPackage> childRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("child_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Simulates outer rule that calls executeParentRule() with a different source.
     * This is the pattern in CreateTransferObjectRelationFromBoundTo* rules.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class OuterRuleWithExecuteParentRule {
        @TransformRule(name = "OuterRule")
        @Transform(type = EReference.class)
        public TransformFunction<EReference, EAnnotation> outerRule() {
            return (source, ctx) -> {
                // Create outer rule's own target
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("outer_" + source.getName());

                // Call executeParentRule with a DIFFERENT source element
                // This is where the bug manifested - the parent rule's target
                // should get ID from targetType, not from this rule's source
                EClassifier targetType = source.getEType();
                if (targetType instanceof EClass) {
                    EPackage parentResult = ctx.executeParentRule("ParentRule", (EClass) targetType);
                    if (parentResult != null) {
                        ann.getReferences().add(parentResult);
                    }
                }

                return ann;
            };
        }
    }

    /**
     * Parent rule called via executeParentRule().
     * Marked @Lazy so it's NOT automatically executed by the executor -
     * it's only invoked via executeParentRule() from the outer rule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentRuleForExecuteParentRule {
        @TransformRule(name = "ParentRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("parent_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Simulates CreateTransferObjectRelationFromBoundTo* rules.
     * Rule processes EReference (relation member), calls equivalent() on target EClass.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class RelationMemberTransformation {
        @TransformRule(name = "RelationMember2Annotation")
        @Transform(type = EReference.class)
        @Lazy
        public TransformFunction<EReference, EAnnotation> relationMember2Annotation() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("relation_" + source.getName());

                // This is where the bug manifested:
                // Call equivalent() on a DIFFERENT source element (the target type)
                // The ID generated for targetPkg should use targetType's ID, not source's ID
                EClassifier targetType = source.getEType();
                if (targetType instanceof EClass) {
                    EPackage targetPkg = ctx.equivalent((EClass) targetType, EPackage.class);
                    if (targetPkg != null) {
                        ann.getReferences().add(targetPkg);
                    }
                }

                return ann;
            };
        }
    }

    /**
     * Rule for transforming EntityType to MappedTransferObjectType equivalent.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class EntityTypeTransformation {
        @TransformRule(name = "EntityType2Package")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> entityType2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("mapped_" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Transformation that calls equivalent() on supertype during transformation.
     * Simulates the Company/Customer scenario where Company's rule looks up Customer's equivalent.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class InheritanceTransformation {
        @TransformRule(name = "Entity2Package")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> entity2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());

                // If this entity has a supertype, get its equivalent
                // This is where the bug manifested: equivalent() would use
                // the wrong source for ID generation
                for (EClass superType : source.getESuperTypes()) {
                    EPackage superPkg = ctx.equivalent(superType, EPackage.class);
                    if (superPkg != null) {
                        // Store reference via annotation (EPackage has no direct super reference)
                        EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                        ann.setSource("superPackage");
                        ann.getReferences().add(superPkg);
                        pkg.getEAnnotations().add(ann);
                    }
                }

                return pkg;
            };
        }
    }

    /**
     * Transformation for 3-level deep inheritance testing.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DeepInheritanceTransformation {
        @TransformRule(name = "DeepEntity2Package")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> deepEntity2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());

                // Recursively get supertype equivalents
                for (EClass superType : source.getESuperTypes()) {
                    EPackage superPkg = ctx.equivalent(superType, EPackage.class);
                    if (superPkg != null) {
                        // Store reference via annotation (EPackage has no direct super reference)
                        EAnnotation ann = EcoreFactory.eINSTANCE.createEAnnotation();
                        ann.setSource("superPackage");
                        ann.getReferences().add(superPkg);
                        pkg.getEAnnotations().add(ann);
                    }
                }

                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SimpleTransformation {
        @TransformRule(name = "Entity2Package")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> entity2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DiscriminatedTransformation {
        @TransformRule(name = "LazyAnnotation")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> lazyAnnotation() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("lazy_" + source.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAnnotation.class, target = EPackage.class)
    public static class AnnotationTransformation {
        @TransformRule(name = "Annotation2Package")
        @Transform(type = EAnnotation.class)
        public TransformFunction<EAnnotation, EPackage> annotation2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("pkg_" + source.getSource());
                return pkg;
            };
        }
    }

    // ==================== Helper Classes ====================

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
