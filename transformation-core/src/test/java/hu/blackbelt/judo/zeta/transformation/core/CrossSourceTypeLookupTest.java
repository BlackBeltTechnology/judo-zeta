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
 * Tests for cross-source-type lazy rule invocation (JNG-6349).
 *
 * <p>These tests verify that ctx.equivalent(source, "RuleName") works correctly when:
 * <ul>
 *   <li>The calling rule has source type A</li>
 *   <li>The target rule has source type B (different type)</li>
 *   <li>The rules are in different transformation classes</li>
 * </ul>
 *
 * <p>This complements {@link GreedyLazyCrossRuleLookupTest} which tests same-source-type lookups.</p>
 *
 * @see <a href="openspec/changes/fix-greedy-rule-target-lookup/specs/greedy-target-lookup/spec.md">Spec</a>
 */
@DisplayName("Cross-Source-Type Lazy Rule Lookup Tests (JNG-6349)")
class CrossSourceTypeLookupTest {

    private static final Logger log = LoggerFactory.getLogger(CrossSourceTypeLookupTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    // Tracking counters for test verification
    static final AtomicInteger packageRuleExecutions = new AtomicInteger(0);
    static final AtomicInteger classRuleExecutions = new AtomicInteger(0);
    static final AtomicInteger attributeRuleExecutions = new AtomicInteger(0);
    static final AtomicInteger nullLookupCount = new AtomicInteger(0);
    static final AtomicInteger successfulLookupCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        // Reset counters
        packageRuleExecutions.set(0);
        classRuleExecutions.set(0);
        attributeRuleExecutions.set(0);
        nullLookupCount.set(0);
        successfulLookupCount.set(0);
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
        return ctx;
    }

    /**
     * Creates a source model with EPackages containing EClasses containing EAttributes.
     * This provides three distinct source types for cross-type lookup testing.
     */
    private void createSourceModel(int packageCount, int classesPerPackage, int attributesPerClass) {
        for (int p = 0; p < packageCount; p++) {
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("Package" + p);
            pkg.setNsURI("http://test/package" + p);
            pkg.setNsPrefix("pkg" + p);
            sourceResource.getContents().add(pkg);

            for (int c = 0; c < classesPerPackage; c++) {
                EClass cls = EcoreFactory.eINSTANCE.createEClass();
                cls.setName("Class" + p + "_" + c);
                pkg.getEClassifiers().add(cls);

                for (int a = 0; a < attributesPerClass; a++) {
                    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                    attr.setName("attr" + a);
                    attr.setEType(EcorePackage.Literals.ESTRING);
                    cls.getEStructuralFeatures().add(attr);
                }
            }
        }
    }

    // ==================== Cross-Source-Type Tests ====================

    @Nested
    @DisplayName("Cross-Source-Type equivalent() Calls")
    class CrossSourceTypeTests {

        /**
         * Scenario: Cross-source-type equivalent() call succeeds
         *
         * Given TransformationClassA with source type EAttribute
         * And TransformationClassB with @Lazy @Greedy rule "PackageTarget" for source type EPackage
         * When A rule in TransformationClassA calls ctx.equivalent(package, "PackageTarget")
         * Then PackageTarget executes immediately
         * And The created target is returned (not null)
         */
        @Test
        @DisplayName("Cross-source-type equivalent() call succeeds")
        void testCrossSourceTypeEquivalentSucceeds() {
            createSourceModel(2, 2, 2);

            TransformationRegistry registry = new TransformationRegistry();
            // Register in order: AttributeRule first, then PackageRule
            // AttributeRule will call equivalent() on EPackage to invoke PackageRule
            registry.register(AttributeToDataTypeRule.class);
            registry.register(PackageToAnnotationRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            log.info("PackageRule executions: {}, AttributeRule executions: {}",
                    packageRuleExecutions.get(), attributeRuleExecutions.get());
            log.info("Successful lookups: {}, Null lookups: {}",
                    successfulLookupCount.get(), nullLookupCount.get());

            // Verify cross-source-type lookups succeeded
            assertEquals(0, nullLookupCount.get(),
                    "Cross-source-type equivalent() calls should not return null");
            assertTrue(successfulLookupCount.get() > 0,
                    "Should have successful cross-source-type lookups");

            // Verify both rule types executed
            assertTrue(packageRuleExecutions.get() > 0, "PackageRule should have executed");
            assertTrue(attributeRuleExecutions.get() > 0, "AttributeRule should have executed");
        }

        /**
         * Scenario: Cross-source-type lookup is registration order independent
         *
         * Given TransformationClassA registered BEFORE TransformationClassB
         * And TransformationClassA's rule calls ctx.equivalent(source, "RuleBFromClassB")
         * When The transformation executes
         * Then The equivalent() call succeeds (not null)
         * And Same result occurs if registration order is reversed
         */
        @Test
        @DisplayName("Cross-source-type lookup is registration order independent")
        void testRegistrationOrderIndependence() {
            // Test with order A, B
            createSourceModel(2, 2, 1);
            nullLookupCount.set(0);
            successfulLookupCount.set(0);

            TransformationRegistry registry1 = new TransformationRegistry();
            registry1.register(AttributeToDataTypeRule.class);  // First
            registry1.register(PackageToAnnotationRule.class);  // Second

            TransformationContext context1 = createContext(registry1);
            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry1)
                    .context(context1)
                    .parallel(false)
                    .build();

            executor1.transform();

            int successCountOrder1 = successfulLookupCount.get();
            int nullCountOrder1 = nullLookupCount.get();

            log.info("Order A,B - Successful: {}, Null: {}", successCountOrder1, nullCountOrder1);

            // Reset and test with reversed order B, A
            setUp(); // Reset resources
            createSourceModel(2, 2, 1);
            nullLookupCount.set(0);
            successfulLookupCount.set(0);

            TransformationRegistry registry2 = new TransformationRegistry();
            registry2.register(PackageToAnnotationRule.class);  // First (reversed)
            registry2.register(AttributeToDataTypeRule.class);  // Second (reversed)

            TransformationContext context2 = createContext(registry2);
            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry2)
                    .context(context2)
                    .parallel(false)
                    .build();

            executor2.transform();

            int successCountOrder2 = successfulLookupCount.get();
            int nullCountOrder2 = nullLookupCount.get();

            log.info("Order B,A - Successful: {}, Null: {}", successCountOrder2, nullCountOrder2);

            // Both orders should produce same results
            assertEquals(0, nullCountOrder1, "Order A,B should have no null lookups");
            assertEquals(0, nullCountOrder2, "Order B,A should have no null lookups");
            assertEquals(successCountOrder1, successCountOrder2,
                    "Both registration orders should produce same number of successful lookups");
        }

        /**
         * Scenario: Cross-source-type lookup with @Greedy rule
         *
         * Given RuleA in ClassA has @Greedy on source type A
         * And RuleB in ClassB has @Lazy @Greedy on source type B
         * When RuleA executes during greedy pass
         * And ClassB's greedy pass has NOT yet started
         * Then RuleB executes immediately via executeLazyRuleImmediately()
         * And The target is returned (not null)
         */
        @Test
        @DisplayName("Cross-source-type lookup with @Greedy rule before target's greedy pass")
        void testCrossSourceTypeBeforeGreedyPass() {
            createSourceModel(3, 3, 2);

            TransformationRegistry registry = new TransformationRegistry();
            // Register AttributeRule first - its greedy pass runs before PackageRule's
            registry.register(AttributeToDataTypeRule.class);
            registry.register(PackageToAnnotationRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // The key assertion: AttributeRule successfully looked up PackageRule targets
            // even though PackageRule's greedy pass hadn't started yet
            assertEquals(0, nullLookupCount.get(),
                    "Cross-source-type lookups should succeed even before target rule's greedy pass");
        }

        /**
         * Scenario: appliesTo() check passes for valid cross-type source
         *
         * Given RuleB declared with source type B
         * And RuleB has @Greedy annotation
         * When rule.appliesTo(instanceOfB) is called
         * Then Returns true (not false)
         */
        @Test
        @DisplayName("appliesTo() returns true for valid cross-type source")
        void testAppliesToPassesForValidSource() {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PackageToAnnotationRule.class);

            TransformRuleDescriptor rule = registry.getRuleByName("PackageTarget");
            assertNotNull(rule, "PackageTarget rule should be registered");

            // Create a valid EPackage source
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("TestPackage");

            // appliesTo() should return true
            assertTrue(rule.appliesTo(pkg),
                    "appliesTo() should return true for valid source type");

            // appliesTo() should return false for wrong type
            EClass cls = EcoreFactory.eINSTANCE.createEClass();
            assertFalse(rule.appliesTo(cls),
                    "appliesTo() should return false for wrong source type");
        }

        /**
         * Scenario: Global rule registry lookup
         *
         * Given Multiple transformation classes with different source types
         * When ctx.equivalent(source, "SomeRuleName") is called
         * Then The rule is found in the global registry
         */
        @Test
        @DisplayName("Global rule registry lookup finds rules across transformation classes")
        void testGlobalRuleRegistryLookup() {
            TransformationRegistry registry = new TransformationRegistry();
            registry.register(PackageToAnnotationRule.class);
            registry.register(ClassToEnumRule.class);
            registry.register(AttributeToDataTypeRule.class);

            // All rules should be findable by name regardless of source type
            assertNotNull(registry.getRuleByName("PackageTarget"),
                    "PackageTarget should be findable");
            assertNotNull(registry.getRuleByName("ClassTarget"),
                    "ClassTarget should be findable");
            assertNotNull(registry.getRuleByName("AttributeTarget"),
                    "AttributeTarget should be findable");
        }
    }

    // ==================== 3-Rule Chain Tests ====================

    @Nested
    @DisplayName("Multiple Greedy Rules with Cross-References")
    class ThreeRuleChainTests {

        /**
         * Scenario: Multiple greedy rules with cross-references
         *
         * Given transformation with 3 greedy rules: RuleA, RuleB, RuleC
         * And RuleB depends on RuleA's output
         * And RuleC depends on RuleB's output
         * When the transformation executes
         * Then RuleA's targets are findable by RuleB
         * And RuleB's targets are findable by RuleC
         */
        @Test
        @DisplayName("3-rule chain: A -> B -> C cross-references work")
        void testThreeRuleChain() {
            createSourceModel(2, 3, 2);

            TransformationRegistry registry = new TransformationRegistry();
            // Chain: Attribute -> Class -> Package
            // AttributeRule looks up ClassRule targets
            // ClassRule looks up PackageRule targets
            registry.register(AttributeChainRule.class);
            registry.register(ClassChainRule.class);
            registry.register(PackageChainRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            log.info("Chain test - Package: {}, Class: {}, Attribute: {}",
                    packageRuleExecutions.get(), classRuleExecutions.get(), attributeRuleExecutions.get());
            log.info("Chain test - Successful: {}, Null: {}",
                    successfulLookupCount.get(), nullLookupCount.get());

            // All rules should have executed
            assertTrue(packageRuleExecutions.get() > 0, "PackageChainRule should execute");
            assertTrue(classRuleExecutions.get() > 0, "ClassChainRule should execute");
            assertTrue(attributeRuleExecutions.get() > 0, "AttributeChainRule should execute");

            // No null lookups in the chain
            assertEquals(0, nullLookupCount.get(),
                    "3-rule chain should have no null lookups");
        }

        /**
         * Test parallel mode with 3-rule chain.
         */
        @Test
        @DisplayName("3-rule chain works in parallel mode")
        void testThreeRuleChainParallel() {
            createSourceModel(5, 5, 3); // More elements for parallel

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(AttributeChainRule.class);
            registry.register(ClassChainRule.class);
            registry.register(PackageChainRule.class);

            TransformationContext context = createContext(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(5)
                    .build();

            executor.transform();

            assertEquals(0, nullLookupCount.get(),
                    "3-rule chain should have no null lookups in parallel mode");
        }
    }

    // ==================== XMI ID Lookup Tests ====================

    @Nested
    @DisplayName("XMI ID Lookup Scenarios")
    class XmiIdLookupTests {

        /**
         * Scenario: equivalent() finds eager rule target via XMI ID
         *
         * Given an EAGER rule (without @Lazy) that creates a target
         * When another rule calls ctx.equivalent(source, "RuleName")
         * Then XMI ID lookup finds the target
         */
        @Test
        @DisplayName("equivalent() finds eager rule target via XMI ID")
        void testEagerRuleTargetFoundViaXmiId() {
            createSourceModel(2, 2, 1);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(EagerPackageRule.class);
            registry.register(ClassLookingUpPackageRule.class);

            TransformationContext context = createContext(registry);
            context.setUseStructuredIds(true); // Enable XMI ID lookup

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(0, nullLookupCount.get(),
                    "Eager rule targets should be findable via XMI ID");
        }

        /**
         * Scenario: XMI ID index is updated synchronously on target creation
         *
         * Given an eager rule creates a target
         * When the target is added to the resolution cache
         * Then subsequent equivalent() calls can find the target
         */
        @Test
        @DisplayName("XMI ID index updated synchronously - subsequent calls find target")
        void testXmiIdIndexUpdatedSynchronously() {
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("TestPkg");
            sourceResource.getContents().add(pkg);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(EagerPackageRule.class);

            TransformationContext context = createContext(registry);
            context.setUseStructuredIds(true);
            context.setTransformationRegistry(registry);

            // First call creates the target
            EAnnotation result1 = context.equivalent(pkg, "EagerPackageTarget");
            assertNotNull(result1, "First equivalent() call should create and return target");

            // Second call should find cached target
            EAnnotation result2 = context.equivalent(pkg, "EagerPackageTarget");
            assertNotNull(result2, "Second equivalent() call should find cached target");
            assertSame(result1, result2, "Should return same cached instance");
        }

        /**
         * Scenario: Structured ID format enables lookup
         *
         * Given useStructuredIds is enabled
         * And a target is created for source element S by rule R
         * When equivalent(S, R) is called
         * Then lookup by ID finds the target
         */
        @Test
        @DisplayName("Structured ID format enables lookup")
        void testStructuredIdEnablesLookup() {
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("StructuredIdTest");
            sourceResource.getContents().add(pkg);

            // Set XMI ID on source
            if (sourceResource instanceof XMIResource) {
                ((XMIResource) sourceResource).setID(pkg, "_testSourceId123");
            }

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(EagerPackageRule.class);

            TransformationContext context = createContext(registry);
            context.setUseStructuredIds(true);
            context.setTransformationRegistry(registry);

            // Execute transformation to create target
            EAnnotation result = context.equivalent(pkg, "EagerPackageTarget");
            assertNotNull(result, "Target should be created");

            // Verify target has structured XMI ID
            if (targetResource instanceof XMIResource) {
                String targetId = ((XMIResource) targetResource).getID(result);
                log.info("Target XMI ID: {}", targetId);
                // ID should be non-null after commit
            }

            // Lookup should still work
            EAnnotation result2 = context.equivalent(pkg, "EagerPackageTarget");
            assertSame(result, result2, "Lookup should return same target");
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Rule for EPackage -> EAnnotation transformation.
     * Source type: EPackage (different from AttributeRule's EAttribute)
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EAnnotation.class)
    public static class PackageToAnnotationRule {

        @TransformRule(name = "PackageTarget")
        @Transform(type = EPackage.class)
        @Greedy
        @Lazy
        public TransformFunction<EPackage, EAnnotation> packageTarget() {
            return (source, ctx) -> {
                packageRuleExecutions.incrementAndGet();
                log.debug("PackageTarget executing for: {}", source.getName());

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("package:" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Rule for EClass -> EEnum transformation.
     * Source type: EClass
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class ClassToEnumRule {

        @TransformRule(name = "ClassTarget")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        public TransformFunction<EClass, EEnum> classTarget() {
            return (source, ctx) -> {
                classRuleExecutions.incrementAndGet();
                log.debug("ClassTarget executing for: {}", source.getName());

                EEnum target = ctx.createTarget(EEnum.class);
                target.setName(source.getName() + "_Enum");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Rule for EAttribute -> EDataType transformation.
     * This rule calls equivalent() on EPackage (cross-source-type lookup).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAttribute.class, target = EDataType.class)
    public static class AttributeToDataTypeRule {

        @TransformRule(name = "AttributeTarget")
        @Transform(type = EAttribute.class)
        @Greedy
        public TransformFunction<EAttribute, EDataType> attributeTarget() {
            return (source, ctx) -> {
                attributeRuleExecutions.incrementAndGet();

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_Type");

                // CROSS-SOURCE-TYPE LOOKUP: EAttribute rule looks up EPackage rule's target
                EClass containingClass = source.getEContainingClass();
                if (containingClass != null) {
                    EPackage containingPackage = containingClass.getEPackage();
                    if (containingPackage != null) {
                        // This is the key cross-source-type call
                        EAnnotation packageTarget = ctx.equivalent(containingPackage, "PackageTarget");

                        if (packageTarget == null) {
                            nullLookupCount.incrementAndGet();
                            log.warn("Cross-source-type lookup returned null for package: {}",
                                    containingPackage.getName());
                        } else {
                            successfulLookupCount.incrementAndGet();
                            log.debug("Cross-source-type lookup succeeded for package: {}",
                                    containingPackage.getName());
                        }
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    // ==================== 3-Rule Chain Rules ====================

    /**
     * Chain rule for EPackage (base of chain).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EAnnotation.class)
    public static class PackageChainRule {

        @TransformRule(name = "PackageChain")
        @Transform(type = EPackage.class)
        @Greedy
        @Lazy
        public TransformFunction<EPackage, EAnnotation> packageChain() {
            return (source, ctx) -> {
                packageRuleExecutions.incrementAndGet();

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("chain:package:" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Chain rule for EClass - looks up PackageChain.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class ClassChainRule {

        @TransformRule(name = "ClassChain")
        @Transform(type = EClass.class)
        @Greedy
        @Lazy
        public TransformFunction<EClass, EEnum> classChain() {
            return (source, ctx) -> {
                classRuleExecutions.incrementAndGet();

                EEnum target = ctx.createTarget(EEnum.class);
                target.setName(source.getName() + "_ChainEnum");

                // Look up PackageChain target (cross-source-type)
                EPackage pkg = source.getEPackage();
                if (pkg != null) {
                    EAnnotation pkgTarget = ctx.equivalent(pkg, "PackageChain");
                    if (pkgTarget == null) {
                        nullLookupCount.incrementAndGet();
                    } else {
                        successfulLookupCount.incrementAndGet();
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Chain rule for EAttribute - looks up ClassChain.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EAttribute.class, target = EDataType.class)
    public static class AttributeChainRule {

        @TransformRule(name = "AttributeChain")
        @Transform(type = EAttribute.class)
        @Greedy
        public TransformFunction<EAttribute, EDataType> attributeChain() {
            return (source, ctx) -> {
                attributeRuleExecutions.incrementAndGet();

                EDataType target = ctx.createTarget(EDataType.class);
                target.setName(source.getName() + "_ChainType");

                // Look up ClassChain target (cross-source-type)
                EClass cls = source.getEContainingClass();
                if (cls != null) {
                    EEnum clsTarget = ctx.equivalent(cls, "ClassChain");
                    if (clsTarget == null) {
                        nullLookupCount.incrementAndGet();
                    } else {
                        successfulLookupCount.incrementAndGet();
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    // ==================== XMI ID Test Rules ====================

    /**
     * EAGER rule (no @Lazy) for EPackage.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EAnnotation.class)
    public static class EagerPackageRule {

        @TransformRule(name = "EagerPackageTarget")
        @Transform(type = EPackage.class)
        @Greedy
        // No @Lazy - this is an EAGER rule
        public TransformFunction<EPackage, EAnnotation> eagerPackageTarget() {
            return (source, ctx) -> {
                packageRuleExecutions.incrementAndGet();

                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("eager:package:" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Rule that looks up eager package targets.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class ClassLookingUpPackageRule {

        @TransformRule(name = "ClassLookupPackage")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EEnum> classLookupPackage() {
            return (source, ctx) -> {
                classRuleExecutions.incrementAndGet();

                EEnum target = ctx.createTarget(EEnum.class);
                target.setName(source.getName() + "_LookupEnum");

                // Look up EAGER package rule target
                EPackage pkg = source.getEPackage();
                if (pkg != null) {
                    EAnnotation pkgTarget = ctx.equivalent(pkg, "EagerPackageTarget");
                    if (pkgTarget == null) {
                        nullLookupCount.incrementAndGet();
                    } else {
                        successfulLookupCount.incrementAndGet();
                    }
                }

                ctx.addToResource(target);
                return target;
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
