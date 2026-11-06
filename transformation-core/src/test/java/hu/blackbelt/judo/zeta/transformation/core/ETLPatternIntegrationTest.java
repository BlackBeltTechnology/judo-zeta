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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ETL patterns using resource aliases.
 *
 * <p>These tests validate the resource alias functionality against real-world
 * ETL patterns found in judo-tatami transformations. All tests use the Ecore
 * metamodel to simulate transformation scenarios.</p>
 *
 * <p>Simulated Models:</p>
 * <ul>
 *   <li><b>asm</b> - Source model (simulates ASM/EntityType)</li>
 *   <li><b>rdbms</b> - Target model (simulates RDBMS/Table)</li>
 *   <li><b>mapping</b> - Mapping rules (simulates type mappings)</li>
 *   <li><b>rules</b> - Rule metadata (simulates FK/Junction rules)</li>
 * </ul>
 */
class ETLPatternIntegrationTest {

    // Resource sets for different model aliases
    private ResourceSet asmResourceSet;
    private ResourceSet rdbmsResourceSet;
    private ResourceSet mappingResourceSet;
    private ResourceSet rulesResourceSet;

    // Resources within each resource set
    private Resource asmResource;
    private Resource rdbmsResource;
    private Resource mappingResource;
    private Resource rulesResource;

    // Transformation infrastructure
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking for test assertions
    static AtomicInteger executionCount;
    static List<String> executionLog;
    static Map<String, EObject> createdElements;

    @BeforeEach
    void setUp() {
        // Reset static tracking fields
        executionCount = new AtomicInteger(0);
        executionLog = Collections.synchronizedList(new ArrayList<>());
        createdElements = Collections.synchronizedMap(new LinkedHashMap<>());

        // Register XMI resource factory for all extensions
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        // Create source model (asm)
        asmResourceSet = new ResourceSetImpl();
        asmResource = asmResourceSet.createResource(URI.createURI("test://asm.xmi"));

        // Create target model (rdbms)
        rdbmsResourceSet = new ResourceSetImpl();
        rdbmsResource = rdbmsResourceSet.createResource(URI.createURI("test://rdbms.xmi"));

        // Create mapping model
        mappingResourceSet = new ResourceSetImpl();
        mappingResource = mappingResourceSet.createResource(URI.createURI("test://mapping.xmi"));

        // Create rules model
        rulesResourceSet = new ResourceSetImpl();
        rulesResource = rulesResourceSet.createResource(URI.createURI("test://rules.xmi"));

        // Create transformation context
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();
        context = new TransformationContext(
                modelProvider, asmResourceSet, rdbmsResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);

        // Register all resource aliases
        context.registerResource("asm", asmResourceSet);
        context.registerResource("rdbms", rdbmsResourceSet);
        context.registerResource("mapping", mappingResourceSet);
        context.registerResource("rules", rulesResourceSet);

        registry = new TransformationRegistry();
    }

    // ========================================================================
    // Helper Methods for Test Data Creation
    // ========================================================================

    /**
     * Create an EClass in the ASM (source) model.
     * Simulates an EntityType.
     */
    private EClass createEntity(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        asmResource.getContents().add(eClass);
        return eClass;
    }

    /**
     * Create an EAttribute in an EClass.
     * Simulates an Attribute.
     */
    private EAttribute createAttribute(EClass owner, String name, EDataType type) {
        EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName(name);
        attr.setEType(type);
        owner.getEStructuralFeatures().add(attr);
        return attr;
    }

    /**
     * Create an EReference between two EClasses.
     * Simulates a Reference/Association.
     */
    private EReference createReference(EClass source, String name, EClass target, boolean isMany) {
        EReference ref = EcoreFactory.eINSTANCE.createEReference();
        ref.setName(name);
        ref.setEType(target);
        ref.setUpperBound(isMany ? -1 : 1);
        source.getEStructuralFeatures().add(ref);
        return ref;
    }

    /**
     * Create a mapping entry in the mapping model.
     * Simulates a type mapping rule.
     */
    private EAnnotation createTypeMapping(String sourceType, String targetType) {
        EAnnotation mapping = EcoreFactory.eINSTANCE.createEAnnotation();
        mapping.setSource("typeMapping");
        mapping.getDetails().put("sourceType", sourceType);
        mapping.getDetails().put("targetType", targetType);
        mappingResource.getContents().add(mapping);
        return mapping;
    }

    /**
     * Create a name mapping entry.
     * Simulates SQL name mapping.
     */
    private EAnnotation createNameMapping(String entityName, String tableName) {
        EAnnotation mapping = EcoreFactory.eINSTANCE.createEAnnotation();
        mapping.setSource("nameMapping");
        mapping.getDetails().put("entityName", entityName);
        mapping.getDetails().put("tableName", tableName);
        mappingResource.getContents().add(mapping);
        return mapping;
    }

    /**
     * Create a rule entry in the rules model.
     * Simulates FK or Junction table rule.
     */
    private EAnnotation createForeignKeyRule(String referenceName, String type) {
        EAnnotation rule = EcoreFactory.eINSTANCE.createEAnnotation();
        rule.setSource("fkRule");
        rule.getDetails().put("referenceName", referenceName);
        rule.getDetails().put("type", type); // "foreignKey" or "junctionTable"
        rulesResource.getContents().add(rule);
        return rule;
    }

    // ========================================================================
    // Phase 2: Multi-Model Transformation Tests
    // ========================================================================

    @Nested
    @DisplayName("Multi-Model Transformation Tests")
    class MultiModelTransformationTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should transform entity using mapping from separate model")
        void shouldTransformEntityUsingMappingFromSeparateModel() {
            // Setup: Create entities in ASM
            createEntity("Customer");
            createEntity("Order");

            // Setup: Create type mappings
            createTypeMapping("Customer", "CUSTOMER_TABLE");
            createTypeMapping("Order", "ORDER_TABLE");

            // Debug: Verify elements exist in resources
            System.out.println("ASM resources: " + asmResourceSet.getResources());
            System.out.println("ASM resource contents: " + asmResource.getContents());
            System.out.println("context.all(asm, EClass): " + context.all("asm", EClass.class));

            // Register transformation
            registry.register(EntityToTableWithMapping.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            System.out.println("Execution count: " + executionCount.get());
            System.out.println("Execution log: " + executionLog);

            // Verify: 2 entities transformed
            assertEquals(2, executionCount.get());
            assertTrue(executionLog.contains("Customer->CUSTOMER_TABLE"));
            assertTrue(executionLog.contains("Order->ORDER_TABLE"));
        }

        @Test
        @DisplayName("should access all three models during transformation")
        void shouldAccessAllThreeModelsDuringTransformation() {
            // Setup: Entity in ASM
            EClass customer = createEntity("Customer");
            createAttribute(customer, "name", EcorePackage.Literals.ESTRING);

            // Setup: Type mapping
            createTypeMapping("Customer", "TBL_CUSTOMER");

            // Setup: Name mapping for SQL naming
            createNameMapping("Customer", "t_customer");

            // Register transformation that uses all three
            registry.register(ThreeModelTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify transformation used all models
            assertEquals(1, executionCount.get());
            assertTrue(executionLog.contains("Customer:TBL_CUSTOMER:t_customer"));
        }
    }

    // ========================================================================
    // Phase 3: Cross-Model Equivalence Tests
    // ========================================================================

    @Nested
    @DisplayName("Cross-Model Equivalence Tests")
    class CrossModelEquivalenceTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should resolve equivalent from aliased target")
        void shouldResolveEquivalentFromAliasedTarget() {
            // Setup: Parent and child entities
            EClass parent = createEntity("Parent");
            EClass child = createEntity("Child");
            createReference(child, "parent", parent, false);

            // Register EntityToTableSimple first to ensure entities are transformed
            // before references try to resolve their equivalents
            registry.register(EntityToTableSimple.class);

            // Execute first phase - transform entities
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor1.transform();

            // Now register and execute FK transformation
            registry.register(ReferenceToForeignKey.class);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor2.transform();

            // Verify: Both entities transformed, FK resolved equivalent
            assertTrue(executionLog.contains("Entity:Parent"), "Expected Entity:Parent in " + executionLog);
            assertTrue(executionLog.contains("Entity:Child"), "Expected Entity:Child in " + executionLog);
            assertTrue(executionLog.contains("FK:parent->Parent"), "Expected FK:parent->Parent in " + executionLog);
        }

        @Test
        @DisplayName("should cache equivalent across multiple calls")
        void shouldCacheEquivalentAcrossMultipleCalls() {
            // Setup: One parent, two children referencing it
            EClass parent = createEntity("Parent");
            EClass child1 = createEntity("Child1");
            EClass child2 = createEntity("Child2");
            createReference(child1, "parentRef", parent, false);
            createReference(child2, "parentRef", parent, false);

            // Phase 1: Transform entities first
            registry.register(EntityToTableSimple.class);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor1.transform();

            // Phase 2: Transform references (which call equivalent)
            registry.register(ReferenceToForeignKey.class);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor2.transform();

            // Verify: Parent entity transformed only once
            long parentCount = executionLog.stream()
                    .filter(s -> s.equals("Entity:Parent"))
                    .count();
            assertEquals(1, parentCount, "Parent should be transformed only once");

            // Both FKs resolved to same parent equivalent
            assertTrue(executionLog.contains("FK:parentRef->Parent"), "Expected FK:parentRef->Parent in " + executionLog);
        }

        @Test
        @DisplayName("should follow equivalent chain across transformations")
        void shouldFollowEquivalentChainAcrossTransformations() {
            // Setup: A -> B -> C chain (Entity -> Table -> Column)
            EClass entity = createEntity("Customer");
            EAttribute attr = createAttribute(entity, "name", EcorePackage.Literals.ESTRING);

            // Phase 1: Transform entities first
            registry.register(EntityToTableSimple.class);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor1.transform();

            // Phase 2: Transform attributes (which call equivalent on owning class)
            registry.register(AttributeToColumnTransformation.class);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor2.transform();

            // Verify: Chain followed correctly
            assertTrue(executionLog.contains("Entity:Customer"), "Expected Entity:Customer in " + executionLog);
            assertTrue(executionLog.contains("Column:name->Customer_TABLE"), "Expected Column:name->Customer_TABLE in " + executionLog);
        }
    }

    // ========================================================================
    // Phase 4: Guard with Alias Tests
    // ========================================================================

    @Nested
    @DisplayName("Guard with Alias Tests")
    class GuardWithAliasTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should evaluate guard that queries mapping alias")
        void shouldEvaluateGuardQueryingMappingAlias() {
            // Setup: Create references
            EClass customer = createEntity("Customer");
            EClass order = createEntity("Order");
            EReference ordersRef = createReference(customer, "orders", order, true);
            EReference customerRef = createReference(order, "customer", customer, false);

            // Setup: Only customerRef should create FK (single reference)
            createForeignKeyRule("customer", "foreignKey");
            // orders is many, should create junction table instead (not FK)

            // Register guarded transformation
            registry.register(GuardedForeignKeyTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Only customer reference created FK
            assertEquals(1, executionCount.get());
            assertTrue(executionLog.contains("FK:customer"));
            assertFalse(executionLog.contains("FK:orders"));
        }

        @Test
        @DisplayName("should skip rule when guard fails on alias lookup")
        void shouldSkipRuleWhenGuardFailsOnAliasLookup() {
            // Setup: References without corresponding rules
            EClass a = createEntity("A");
            EClass b = createEntity("B");
            createReference(a, "refToB", b, false);
            // No FK rule created for "refToB"

            // Register guarded transformation
            registry.register(GuardedForeignKeyTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: No FKs created (guard failed)
            assertEquals(0, executionCount.get());
        }

        @Test
        @DisplayName("should apply rule when guard succeeds on alias lookup")
        void shouldApplyRuleWhenGuardSucceedsOnAliasLookup() {
            // Setup: Reference with corresponding FK rule
            EClass order = createEntity("Order");
            EClass customer = createEntity("Customer");
            EReference customerRef = createReference(order, "customer", customer, false);

            // Create FK rule for this reference
            createForeignKeyRule("customer", "foreignKey");

            // Register guarded transformation
            registry.register(GuardedForeignKeyTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: FK created (guard passed)
            assertEquals(1, executionCount.get());
            assertTrue(executionLog.contains("FK:customer"));
        }
    }

    // ========================================================================
    // Phase 5: Lazy Rule Tests
    // ========================================================================

    @Nested
    @DisplayName("Lazy Rule with Alias Tests")
    class LazyRuleWithAliasTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should invoke lazy rule only when equivalent called")
        void shouldInvokeLazyRuleOnlyWhenEquivalentCalled() {
            // Setup: Many-to-many reference that needs junction table
            EClass order = createEntity("Order");
            EClass product = createEntity("Product");
            EReference productsRef = createReference(order, "products", product, true);

            // Register lazy junction table rule
            registry.register(LazyJunctionTableTransformation.class);

            // Execute transformation (lazy rule should NOT fire yet)
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Lazy rules are triggered by equivalent() calls, which happens during
            // eager rule execution. If no eager rule calls equivalent(), lazy won't fire.
            // Verify lazy rule did NOT fire during transformation
            assertEquals(0, executionCount.get());
            assertFalse(executionLog.contains("Junction:products"));
        }

        @Test
        @DisplayName("should trigger lazy rule when equivalent is called")
        void shouldTriggerLazyRuleWhenEquivalentCalled() {
            // Setup: Reference and an eager rule that calls equivalent()
            EClass order = createEntity("Order");
            EClass product = createEntity("Product");
            EReference productsRef = createReference(order, "products", product, true);

            // Register both lazy and eager rules - lazy must be registered first
            // so it's available when eager rule calls equivalent()
            registry.register(LazyJunctionTableTransformation.class);
            registry.register(EagerRuleThatCallsEquivalent.class);
            context.setTransformationRegistry(registry);

            // Execute transformation
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Eager rule fired and lazy rule was triggered by equivalent() call
            assertTrue(executionLog.contains("EagerTrigger:products"), 
                    "Expected EagerTrigger:products in " + executionLog);
            assertTrue(executionLog.contains("Junction:products"), 
                    "Expected Junction:products in " + executionLog);
        }

        @Test
        @DisplayName("should cache lazy rule result")
        void shouldCacheLazyRuleResult() {
            // Setup: Reference
            EClass order = createEntity("Order");
            EClass product = createEntity("Product");
            EReference productsRef = createReference(order, "products", product, true);

            // Register lazy rule and eager rule that calls equivalent twice
            registry.register(LazyJunctionTableTransformation.class);
            registry.register(EagerRuleThatCallsEquivalentTwice.class);
            context.setTransformationRegistry(registry);

            // Execute transformation
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Lazy rule executed only once despite two equivalent() calls
            long junctionCount = executionLog.stream()
                    .filter(s -> s.startsWith("Junction:"))
                    .count();
            assertEquals(1, junctionCount, "Lazy rule should execute only once, log: " + executionLog);
            
            // Also verify the SameInstance check passed
            assertTrue(executionLog.contains("SameInstance:true"), 
                    "Expected SameInstance:true in " + executionLog);
        }
    }

    // ========================================================================
    // Phase 6: Greedy Rule Tests
    // ========================================================================

    @Nested
    @DisplayName("Greedy Rule with Alias Tests")
    class GreedyRuleWithAliasTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should fire greedy rule for all matching elements")
        void shouldFireGreedyRuleForAllMatchingElements() {
            // Setup: Multiple entities
            createEntity("Entity1");
            createEntity("Entity2");
            createEntity("Entity3");

            // Register greedy transformation
            registry.register(GreedyDocumentationTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: All 3 entities processed by greedy rule
            assertEquals(3, executionCount.get());
            assertTrue(executionLog.contains("Doc:Entity1"));
            assertTrue(executionLog.contains("Doc:Entity2"));
            assertTrue(executionLog.contains("Doc:Entity3"));
        }

        @Test
        @DisplayName("should fire greedy rule alongside non-greedy rules")
        void shouldFireGreedyRuleAlongsideNonGreedyRules() {
            // Setup: Entities
            createEntity("Customer");
            createEntity("Order");

            // Register both greedy and non-greedy transformations
            registry.register(EntityToTableSimple.class);
            registry.register(GreedyDocumentationTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Both rule types fired
            assertTrue(executionLog.contains("Entity:Customer"));
            assertTrue(executionLog.contains("Entity:Order"));
            assertTrue(executionLog.contains("Doc:Customer"));
            assertTrue(executionLog.contains("Doc:Order"));
        }

        @Test
        @DisplayName("should respect guard in greedy rule")
        void shouldRespectGuardInGreedyRule() {
            // Setup: Multiple entities with different naming patterns
            createEntity("Entity_Valid");
            createEntity("Entity_Invalid");
            createEntity("Another_Valid");

            // Create rules that mark some entities as valid for documentation
            createForeignKeyRule("Entity_Valid", "documentable");
            createForeignKeyRule("Another_Valid", "documentable");
            // Entity_Invalid not marked

            // Register guarded greedy transformation
            registry.register(GuardedGreedyTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Only valid entities processed
            assertEquals(2, executionCount.get());
            assertTrue(executionLog.contains("GuardedDoc:Entity_Valid"));
            assertTrue(executionLog.contains("GuardedDoc:Another_Valid"));
            assertFalse(executionLog.contains("GuardedDoc:Entity_Invalid"));
        }
    }

    // ========================================================================
    // Phase 7: Rule Inheritance with Alias Tests
    // ========================================================================

    @Nested
    @DisplayName("Rule Inheritance with Alias Tests")
    class RuleInheritanceWithAliasTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should inherit @Transform alias from abstract rule")
        void shouldInheritTransformAliasFromAbstractRule() {
            // Setup: Entities
            createEntity("BaseEntity");

            // Register abstract and extending rules
            registry.register(AbstractEntityTransformation.class);
            registry.register(ConcreteEntityTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Concrete rule executed using inherited alias context
            assertTrue(executionLog.contains("ConcreteEntity:BaseEntity"), 
                    "Expected ConcreteEntity:BaseEntity in " + executionLog);
        }

        @Test
        @DisplayName("should allow extending rule to have its own alias")
        void shouldAllowExtendingRuleToHaveOwnAlias() {
            // Setup: Entities in asm
            createEntity("SourceEntity");

            // Register transformation with specific alias
            registry.register(SpecificAliasTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Rule executed with its own alias
            assertTrue(executionLog.contains("SpecificAlias:SourceEntity"),
                    "Expected SpecificAlias:SourceEntity in " + executionLog);
        }
    }

    // ========================================================================
    // Phase 8: Pre/Post Hook Tests
    // ========================================================================

    @Nested
    @DisplayName("Pre/Post Hook with Alias Tests")
    class PrePostHookWithAliasTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should execute pre-hook before transformation")
        void shouldExecutePreHookBeforeTransformation() {
            // Setup: Entity
            createEntity("TestEntity");

            // Register transformation with hooks
            registry.register(TransformationWithHooks.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Pre-hook executed before transformation
            int preHookIndex = executionLog.indexOf("PreHook:executed");
            int transformIndex = executionLog.indexOf("HookedTransform:TestEntity");
            assertTrue(preHookIndex >= 0, "Pre-hook should have executed");
            assertTrue(transformIndex >= 0, "Transform should have executed");
            assertTrue(preHookIndex < transformIndex, 
                    "Pre-hook should execute before transform: " + executionLog);
        }

        @Test
        @DisplayName("should execute post-hook after transformation")
        void shouldExecutePostHookAfterTransformation() {
            // Setup: Entity
            createEntity("TestEntity");

            // Register transformation with hooks
            registry.register(TransformationWithHooks.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Post-hook executed after transformation
            int postHookIndex = executionLog.indexOf("PostHook:executed");
            int transformIndex = executionLog.indexOf("HookedTransform:TestEntity");
            assertTrue(postHookIndex >= 0, "Post-hook should have executed");
            assertTrue(transformIndex >= 0, "Transform should have executed");
            assertTrue(postHookIndex > transformIndex, 
                    "Post-hook should execute after transform: " + executionLog);
        }

        @Test
        @DisplayName("should access all aliases from hooks")
        void shouldAccessAllAliasesFromHooks() {
            // Setup: Elements in multiple aliases
            createEntity("TestEntity");
            createTypeMapping("TestEntity", "TEST_TABLE");

            // Register transformation with hooks that access aliases
            registry.register(TransformationWithAliasAccessingHooks.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Hooks accessed both aliases
            assertTrue(executionLog.contains("PreHook:asm=1,mapping=1"),
                    "Pre-hook should access aliases: " + executionLog);
        }
    }

    // ========================================================================
    // Phase 9: Complex Scenario Tests (ASM2RDBMS-like)
    // ========================================================================

    @Nested
    @DisplayName("Complex ASM2RDBMS-like Scenario Tests")
    class ComplexScenarioTests {

        @BeforeEach
        void nestedSetUp() {
            ETLPatternIntegrationTest.this.setUp();
        }

        @Test
        @DisplayName("should transform complete entity model to RDBMS")
        void shouldTransformCompleteEntityModelToRDBMS() {
            // Setup: Complete entity model
            EClass customer = createEntity("Customer");
            createAttribute(customer, "name", EcorePackage.Literals.ESTRING);
            createAttribute(customer, "email", EcorePackage.Literals.ESTRING);

            EClass order = createEntity("Order");
            createAttribute(order, "orderNumber", EcorePackage.Literals.ESTRING);
            createAttribute(order, "total", EcorePackage.Literals.EDOUBLE);

            // Create bidirectional reference
            EReference ordersRef = createReference(customer, "orders", order, true);
            EReference customerRef = createReference(order, "customer", customer, false);

            // Setup: Mappings
            createTypeMapping("Customer", "TBL_CUSTOMER");
            createTypeMapping("Order", "TBL_ORDER");

            // Setup: FK rules
            createForeignKeyRule("customer", "foreignKey");

            // Phase 1: Transform entities
            registry.register(EntityToTableWithMapping.class);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor1.transform();

            // Phase 2: Transform attributes
            registry.register(AttributeToColumnTransformation.class);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor2.transform();

            // Phase 3: Transform FK references
            registry.register(GuardedForeignKeyTransformation.class);
            TransformationExecutor executor3 = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor3.transform();

            // Verify: Complete transformation
            // Entities transformed
            assertTrue(executionLog.contains("Customer->TBL_CUSTOMER"));
            assertTrue(executionLog.contains("Order->TBL_ORDER"));

            // Attributes transformed (4 total)
            long columnCount = executionLog.stream()
                    .filter(s -> s.startsWith("Column:"))
                    .count();
            assertEquals(4, columnCount, "Should transform 4 attributes: " + executionLog);

            // FK created for customer reference only
            assertTrue(executionLog.contains("FK:customer"));
            assertFalse(executionLog.contains("FK:orders")); // Many-side, no FK rule
        }

        @Test
        @DisplayName("should handle discriminated transformations")
        void shouldHandleDiscriminatedTransformations() {
            // Setup: Different entity types
            EClass regularEntity = createEntity("RegularEntity");
            EClass specialEntity = createEntity("SpecialEntity");

            // Mark SpecialEntity as requiring special handling
            createForeignKeyRule("SpecialEntity", "special");

            // Register discriminated transformations
            registry.register(RegularEntityTransformation.class);
            registry.register(SpecialEntityTransformation.class);

            // Execute
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: Each entity transformed by appropriate rule
            assertTrue(executionLog.contains("Regular:RegularEntity"),
                    "Regular entity should be transformed: " + executionLog);
            assertTrue(executionLog.contains("Special:SpecialEntity"),
                    "Special entity should be transformed: " + executionLog);
        }
    }

    // ========================================================================
    // Transformation Classes
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class EntityToTableWithMapping {
        @TransformRule(name = "EntityToTable")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> entityToTable() {
            return (entity, ctx) -> {
                // Query mapping model for target name
                Collection<EAnnotation> mappings = ctx.all("mapping", EAnnotation.class);
                String targetName = mappings.stream()
                        .filter(m -> "typeMapping".equals(m.getSource()))
                        .filter(m -> entity.getName().equals(m.getDetails().get("sourceType")))
                        .map(m -> m.getDetails().get("targetType"))
                        .findFirst()
                        .orElse(entity.getName() + "_TABLE");

                executionCount.incrementAndGet();
                executionLog.add(entity.getName() + "->" + targetName);

                EClass table = EcoreFactory.eINSTANCE.createEClass();
                table.setName(targetName);
                return table;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ThreeModelTransformation {
        @TransformRule(name = "ThreeModelRule")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> threeModelRule() {
            return (entity, ctx) -> {
                // Query type mapping
                String typeName = ctx.all("mapping", EAnnotation.class).stream()
                        .filter(m -> "typeMapping".equals(m.getSource()))
                        .filter(m -> entity.getName().equals(m.getDetails().get("sourceType")))
                        .map(m -> m.getDetails().get("targetType"))
                        .findFirst()
                        .orElse("UNKNOWN");

                // Query name mapping
                String sqlName = ctx.all("mapping", EAnnotation.class).stream()
                        .filter(m -> "nameMapping".equals(m.getSource()))
                        .filter(m -> entity.getName().equals(m.getDetails().get("entityName")))
                        .map(m -> m.getDetails().get("tableName"))
                        .findFirst()
                        .orElse("unknown");

                executionCount.incrementAndGet();
                executionLog.add(entity.getName() + ":" + typeName + ":" + sqlName);

                EClass table = EcoreFactory.eINSTANCE.createEClass();
                table.setName(sqlName);
                return table;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class EntityToTableSimple {
        @Primary
        @TransformRule(name = "EntityToTableSimple")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> entityToTable() {
            return (entity, ctx) -> {
                executionLog.add("Entity:" + entity.getName());
                EClass table = EcoreFactory.eINSTANCE.createEClass();
                table.setName(entity.getName() + "_TABLE");
                createdElements.put(entity.getName(), table);
                return table;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EClass.class)
    public static class ReferenceToForeignKey {
        @TransformRule(name = "ReferenceToFK")
        @Transform(alias = "asm", type = EReference.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EReference, EClass> referenceToFK() {
            return (ref, ctx) -> {
                // Get equivalent table for target type
                EClass targetType = (EClass) ref.getEType();
                EClass targetTable = ctx.equivalent(targetType, EClass.class);

                String targetName = targetTable != null ? 
                        targetTable.getName().replace("_TABLE", "") : "UNKNOWN";
                executionLog.add("FK:" + ref.getName() + "->" + targetName);

                EClass fk = EcoreFactory.eINSTANCE.createEClass();
                fk.setName(ref.getName() + "_FK");
                return fk;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EClass.class)
    public static class GuardedForeignKeyTransformation {
        @TransformRule(name = "GuardedFK")
        @Transform(alias = "asm", type = EReference.class)
        @To(alias = "rdbms", type = EClass.class)
        @Guard(method = "shouldCreateFK")
        public TransformFunction<EReference, EClass> guardedFK() {
            return (ref, ctx) -> {
                executionCount.incrementAndGet();
                executionLog.add("FK:" + ref.getName());
                EClass fk = EcoreFactory.eINSTANCE.createEClass();
                fk.setName(ref.getName() + "_FK");
                return fk;
            };
        }

        public boolean shouldCreateFK(EObject source, TransformationContext ctx) {
            EReference ref = (EReference) source;
            // Check if there's a FK rule for this reference in the rules model
            return ctx.all("rules", EAnnotation.class).stream()
                    .filter(r -> "fkRule".equals(r.getSource()))
                    .filter(r -> "foreignKey".equals(r.getDetails().get("type")))
                    .anyMatch(r -> ref.getName().equals(r.getDetails().get("referenceName")));
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EClass.class)
    public static class LazyJunctionTableTransformation {
        @Lazy
        @TransformRule(name = "LazyJunction")
        @Transform(alias = "asm", type = EReference.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EReference, EClass> lazyJunction() {
            return (ref, ctx) -> {
                executionCount.incrementAndGet();
                executionLog.add("Junction:" + ref.getName());
                EClass junction = EcoreFactory.eINSTANCE.createEClass();
                junction.setName(ref.getName() + "_JUNCTION");
                return junction;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GreedyDocumentationTransformation {
        @Greedy
        @TransformRule(name = "GreedyDoc")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EAnnotation.class)
        public TransformFunction<EClass, EAnnotation> greedyDoc() {
            return (entity, ctx) -> {
                executionCount.incrementAndGet();
                executionLog.add("Doc:" + entity.getName());
                EAnnotation doc = EcoreFactory.eINSTANCE.createEAnnotation();
                doc.setSource("documentation");
                doc.getDetails().put("entity", entity.getName());
                return doc;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAttribute.class, target = EAttribute.class)
    public static class AttributeToColumnTransformation {
        @TransformRule(name = "AttributeToColumn")
        @Transform(alias = "asm", type = EAttribute.class)
        @To(alias = "rdbms", type = EAttribute.class)
        public TransformFunction<EAttribute, EAttribute> attributeToColumn() {
            return (attr, ctx) -> {
                // Get the owning class and its equivalent table
                EClass owningClass = attr.getEContainingClass();
                EClass table = ctx.equivalent(owningClass, EClass.class);
                String tableName = table != null ? table.getName() : "UNKNOWN";

                executionLog.add("Column:" + attr.getName() + "->" + tableName);

                EAttribute column = EcoreFactory.eINSTANCE.createEAttribute();
                column.setName(attr.getName().toUpperCase());
                column.setEType(attr.getEType());
                return column;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EClass.class)
    public static class EagerRuleThatCallsEquivalent {
        @TransformRule(name = "EagerTrigger")
        @Transform(alias = "asm", type = EReference.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EReference, EClass> eagerTrigger() {
            return (ref, ctx) -> {
                executionLog.add("EagerTrigger:" + ref.getName());
                // Call equivalent() which should trigger lazy rule
                EClass junction = ctx.equivalent(ref, EClass.class);
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("Trigger_" + ref.getName());
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EReference.class, target = EAnnotation.class)
    public static class EagerRuleThatCallsEquivalentTwice {
        @TransformRule(name = "EagerTriggerTwice")
        @Transform(alias = "asm", type = EReference.class)
        @To(alias = "rdbms", type = EAnnotation.class)
        public TransformFunction<EReference, EAnnotation> eagerTriggerTwice() {
            return (ref, ctx) -> {
                executionLog.add("EagerTriggerTwice:" + ref.getName());
                // Call equivalent() twice
                EClass junction1 = ctx.equivalent(ref, EClass.class);
                EClass junction2 = ctx.equivalent(ref, EClass.class);
                // They should be the same instance
                executionLog.add("SameInstance:" + (junction1 == junction2));
                EAnnotation result = EcoreFactory.eINSTANCE.createEAnnotation();
                result.setSource("trigger");
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GuardedGreedyTransformation {
        @Greedy
        @TransformRule(name = "GuardedGreedyDoc")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EAnnotation.class)
        @Guard(method = "isDocumentable")
        public TransformFunction<EClass, EAnnotation> guardedGreedyDoc() {
            return (entity, ctx) -> {
                executionCount.incrementAndGet();
                executionLog.add("GuardedDoc:" + entity.getName());
                EAnnotation doc = EcoreFactory.eINSTANCE.createEAnnotation();
                doc.setSource("guardedDoc");
                doc.getDetails().put("entity", entity.getName());
                return doc;
            };
        }

        public boolean isDocumentable(EObject source, TransformationContext ctx) {
            EClass entity = (EClass) source;
            // Check if entity is marked as documentable in rules
            return ctx.all("rules", EAnnotation.class).stream()
                    .filter(r -> "fkRule".equals(r.getSource()))
                    .filter(r -> "documentable".equals(r.getDetails().get("type")))
                    .anyMatch(r -> entity.getName().equals(r.getDetails().get("referenceName")));
        }
    }

    // ========================================================================
    // Phase 7: Rule Inheritance Transformation Classes
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class AbstractEntityTransformation {
        @Abstract
        @TransformRule(name = "AbstractEntity")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> abstractEntity() {
            return (entity, ctx) -> {
                executionLog.add("AbstractEntity:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("ABSTRACT_" + entity.getName());
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ConcreteEntityTransformation {
        @TransformRule(name = "ConcreteEntity")
        @Extends("AbstractEntity")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> concreteEntity() {
            return (entity, ctx) -> {
                executionLog.add("ConcreteEntity:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("CONCRETE_" + entity.getName());
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SpecificAliasTransformation {
        @TransformRule(name = "SpecificAlias")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> specificAlias() {
            return (entity, ctx) -> {
                executionLog.add("SpecificAlias:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("SPECIFIC_" + entity.getName());
                return result;
            };
        }
    }

    // ========================================================================
    // Phase 8: Hook Transformation Classes
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class TransformationWithHooks {
        @PreExecution
        public void preHook(TransformationContext ctx) {
            executionLog.add("PreHook:executed");
        }

        @PostExecution
        public void postHook(TransformationContext ctx) {
            executionLog.add("PostHook:executed");
        }

        @TransformRule(name = "HookedTransform")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> hookedTransform() {
            return (entity, ctx) -> {
                executionLog.add("HookedTransform:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("HOOKED_" + entity.getName());
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class TransformationWithAliasAccessingHooks {
        @PreExecution
        public void preHook(TransformationContext ctx) {
            int asmCount = ctx.all("asm", EClass.class).size();
            int mappingCount = ctx.all("mapping", EAnnotation.class).size();
            executionLog.add("PreHook:asm=" + asmCount + ",mapping=" + mappingCount);
        }

        @TransformRule(name = "AliasAccessingTransform")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        public TransformFunction<EClass, EClass> aliasAccessingTransform() {
            return (entity, ctx) -> {
                executionLog.add("AliasAccessingTransform:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("ALIAS_" + entity.getName());
                return result;
            };
        }
    }

    // ========================================================================
    // Phase 9: Complex Scenario Transformation Classes
    // ========================================================================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class RegularEntityTransformation {
        @TransformRule(name = "RegularEntity")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        @Guard(method = "isRegularEntity")
        public TransformFunction<EClass, EClass> regularEntity() {
            return (entity, ctx) -> {
                executionLog.add("Regular:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("REGULAR_" + entity.getName());
                return result;
            };
        }

        public boolean isRegularEntity(EObject source, TransformationContext ctx) {
            EClass entity = (EClass) source;
            // Regular entity = NOT marked as special
            return ctx.all("rules", EAnnotation.class).stream()
                    .filter(r -> "fkRule".equals(r.getSource()))
                    .filter(r -> "special".equals(r.getDetails().get("type")))
                    .noneMatch(r -> entity.getName().equals(r.getDetails().get("referenceName")));
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SpecialEntityTransformation {
        @TransformRule(name = "SpecialEntity")
        @Transform(alias = "asm", type = EClass.class)
        @To(alias = "rdbms", type = EClass.class)
        @Guard(method = "isSpecialEntity")
        public TransformFunction<EClass, EClass> specialEntity() {
            return (entity, ctx) -> {
                executionLog.add("Special:" + entity.getName());
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName("SPECIAL_" + entity.getName());
                return result;
            };
        }

        public boolean isSpecialEntity(EObject source, TransformationContext ctx) {
            EClass entity = (EClass) source;
            // Special entity = marked as special in rules
            return ctx.all("rules", EAnnotation.class).stream()
                    .filter(r -> "fkRule".equals(r.getSource()))
                    .filter(r -> "special".equals(r.getDetails().get("type")))
                    .anyMatch(r -> entity.getName().equals(r.getDetails().get("referenceName")));
        }
    }

    /**
     * Simple ModelProvider implementation for tests using Ecore metamodel.
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
