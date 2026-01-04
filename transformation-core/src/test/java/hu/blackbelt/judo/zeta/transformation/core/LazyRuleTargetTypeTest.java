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
 * Tests for lazy rule target type extraction and independent target creation.
 *
 * <p>These tests verify two key fixes:</p>
 * <ol>
 *   <li><b>Target Type Extraction</b>: Lazy rules without @To annotations should
 *       extract their target type from the TransformFunction&lt;S, T&gt; return type</li>
 *   <li><b>Independent Target Creation</b>: Sibling lazy rules invoked from within
 *       an inheritance context should create independent targets, not share the
 *       caller's pre-created target</li>
 * </ol>
 */
@DisplayName("Lazy Rule Target Type and Independence Tests")
class LazyRuleTargetTypeTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Tracking for test verification
    static List<String> createdTargetTypes = Collections.synchronizedList(new ArrayList<>());
    static List<String> createdTargetNames = Collections.synchronizedList(new ArrayList<>());
    static Map<String, EObject> createdTargets = Collections.synchronizedMap(new HashMap<>());

    @BeforeEach
    void setUp() {
        createdTargetTypes.clear();
        createdTargetNames.clear();
        createdTargets.clear();

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

    // ==================== Target Type Extraction Tests ====================

    @Nested
    @DisplayName("Target Type Extraction from Method Signature")
    class TargetTypeExtractionTests {

        /**
         * Test that target type is extracted from TransformFunction&lt;Source, Target&gt;
         * when no @To annotation is specified.
         */
        @Test
        @DisplayName("Lazy rule extracts target type from TransformFunction generic parameter")
        void lazyRuleExtractsTargetTypeFromMethodSignature() {
            EClass source = createEClass("TestActor");

            registry.register(LazyRuleWithImplicitTargetType.class);
            context.setTransformationRegistry(registry);

            // Get the rule descriptor and verify target type was extracted
            TransformRuleDescriptor rule = registry.getRuleByName("CreateAnnotation");
            assertNotNull(rule, "Rule should be registered");
            assertEquals(EAnnotation.class, rule.getTargetType(),
                    "Target type should be extracted from TransformFunction<EClass, EAnnotation>");
        }

        /**
         * Test that explicit @To annotation takes precedence over method signature.
         */
        @Test
        @DisplayName("Explicit @To annotation takes precedence over method signature")
        void explicitToAnnotationTakesPrecedence() {
            registry.register(LazyRuleWithExplicitTo.class);
            context.setTransformationRegistry(registry);

            TransformRuleDescriptor rule = registry.getRuleByName("CreatePackageExplicit");
            assertNotNull(rule, "Rule should be registered");
            assertEquals(EPackage.class, rule.getTargetType(),
                    "@To annotation should take precedence over method return type");
        }

        /**
         * Test that transformation context default is used when no type can be extracted.
         */
        @Test
        @DisplayName("Falls back to @TransformationContext default when extraction fails")
        void fallsBackToContextDefault() {
            registry.register(LazyRuleWithRawReturnType.class);
            context.setTransformationRegistry(registry);

            TransformRuleDescriptor rule = registry.getRuleByName("RawReturnType");
            assertNotNull(rule, "Rule should be registered");
            // Should fall back to the @TransformationContext target = EPackage.class
            assertEquals(EPackage.class, rule.getTargetType(),
                    "Should fall back to @TransformationContext default target type");
        }

        /**
         * Test that target type extraction works for rules invoked via equivalent().
         */
        @Test
        @DisplayName("Lazy rule with extracted target type creates correct target via equivalent()")
        void lazyRuleCreatesCorrectTargetViaEquivalent() {
            EClass source = createEClass("TestSource");

            registry.register(LazyRuleWithImplicitTargetType.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Invoke the lazy rule via equivalent
            EAnnotation result = context.equivalent(source, "CreateAnnotation");

            assertNotNull(result, "Lazy rule should create target");
            assertTrue(result instanceof EAnnotation, "Target should be EAnnotation type");
            assertEquals("TestSource_annotation", result.getSource(),
                    "Annotation source should be set correctly");
        }
    }

    // ==================== Lazy Rule Independence Tests ====================

    @Nested
    @DisplayName("Lazy Rule Independence (Sibling Rules)")
    class LazyRuleIndependenceTests {

        /**
         * Test that two sibling lazy rules invoked from the same parent create
         * independent targets, not sharing the same instance.
         *
         * <p>This tests the fix for the bug where lazy rules incorrectly inherited
         * the caller's pre-created target when invoked from within an inheritance context.</p>
         */
        @Test
        @DisplayName("Sibling lazy rules create independent targets")
        void siblingLazyRulesCreateIndependentTargets() {
            EClass source = createEClass("ActorType");

            registry.register(ParentRuleInvokingSiblingLazyRules.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Both lazy rules should have created their own targets
            assertEquals(2, createdTargets.size(),
                    "Two sibling lazy rules should create two independent targets");

            EObject metadata = createdTargets.get("_MetadataFor");
            EObject security = createdTargets.get("_MetadataSecurityFor");

            assertNotNull(metadata, "Metadata lazy rule should create target");
            assertNotNull(security, "Security lazy rule should create target");
            assertNotSame(metadata, security,
                    "Sibling lazy rules must create DIFFERENT target instances");

            // Verify names are different (proves they didn't overwrite each other)
            assertTrue(createdTargetNames.contains("_MetadataForActorType"),
                    "Metadata target should have correct name");
            assertTrue(createdTargetNames.contains("_MetadataSecurityForActorType"),
                    "Security target should have correct name");
        }

        /**
         * Test that lazy rule invoked from inheritance context creates its own target
         * of the correct type (not the parent's target type).
         */
        @Test
        @DisplayName("Lazy rule creates correct target type when invoked from inheritance context")
        void lazyRuleCreatesCorrectTypeFromInheritanceContext() {
            EClass source = createEClass("TestActor");

            registry.register(InheritanceWithLazyRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // The lazy rule should create an EAnnotation, not an EPackage (parent's type)
            assertEquals(1, createdTargetTypes.stream()
                            .filter(t -> t.equals("EAnnotation")).count(),
                    "Lazy rule should create EAnnotation, not parent's EPackage type");
        }

        /**
         * Test that multiple invocations of the same lazy rule for the same source
         * return the cached result (idempotency).
         */
        @Test
        @DisplayName("Multiple equivalent() calls for same source return cached result")
        void multipleEquivalentCallsReturnCachedResult() {
            EClass source = createEClass("CacheTest");

            registry.register(LazyRuleWithImplicitTargetType.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Call equivalent multiple times
            EAnnotation result1 = context.equivalent(source, "CreateAnnotation");
            EAnnotation result2 = context.equivalent(source, "CreateAnnotation");
            EAnnotation result3 = context.equivalent(source, "CreateAnnotation");

            assertSame(result1, result2, "Second call should return cached result");
            assertSame(result2, result3, "Third call should return cached result");
        }
    }

    // ==================== Transformation Classes ====================

    /**
     * Lazy rule that extracts target type from TransformFunction<EClass, EAnnotation>.
     * No @To annotation - target type should be inferred from method signature.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class LazyRuleWithImplicitTargetType {
        @TransformRule(name = "CreateAnnotation")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createAnnotation() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource(source.getName() + "_annotation");
                ctx.addToResource(ann);
                createdTargetTypes.add("EAnnotation");
                createdTargetNames.add(ann.getSource());
                return ann;
            };
        }
    }

    /**
     * Lazy rule with explicit @To annotation - should use @To type, not method signature.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyRuleWithExplicitTo {
        @TransformRule(name = "CreatePackageExplicit")
        @Transform(type = EClass.class)
        @To(type = EPackage.class)  // Explicit @To takes precedence
        @Lazy
        public TransformFunction<EClass, EPackage> createPackage() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Lazy rule with raw return type (no generics) - should fall back to context default.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class LazyRuleWithRawReturnType {
        @TransformRule(name = "RawReturnType")
        @Transform(type = EClass.class)
        @Lazy
        @SuppressWarnings("rawtypes")
        public TransformFunction rawReturnType() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(((EClass) source).getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Parent rule that invokes two sibling lazy rules.
     * Tests that each lazy rule creates its own independent target.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentRuleInvokingSiblingLazyRules {

        @TransformRule(name = "ActorType2Package")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> actorType2Package() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);

                // Invoke two sibling lazy rules - each should create independent targets
                EAnnotation metadata = ctx.equivalent(source, "CreateMetadata");
                EAnnotation security = ctx.equivalent(source, "CreateSecurity");

                // Store references to the annotations
                if (metadata != null) {
                    pkg.getEAnnotations().add(metadata);
                }
                if (security != null) {
                    pkg.getEAnnotations().add(security);
                }

                return pkg;
            };
        }

        @TransformRule(name = "CreateMetadata")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createMetadata() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                String name = "_MetadataFor" + source.getName();
                ann.setSource(name);
                ctx.addToResource(ann);
                createdTargetTypes.add("EAnnotation");
                createdTargetNames.add(name);
                createdTargets.put("_MetadataFor", ann);
                return ann;
            };
        }

        @TransformRule(name = "CreateSecurity")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createSecurity() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                String name = "_MetadataSecurityFor" + source.getName();
                ann.setSource(name);
                ctx.addToResource(ann);
                createdTargetTypes.add("EAnnotation");
                createdTargetNames.add(name);
                createdTargets.put("_MetadataSecurityFor", ann);
                return ann;
            };
        }
    }

    /**
     * Tests lazy rule invoked from an @Extends inheritance context.
     * The lazy rule should create its own target type, not the parent's.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class InheritanceWithLazyRule {

        @TransformRule(name = "BaseRule")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> baseRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                ctx.addToResource(pkg);

                // Invoke lazy rule from greedy context
                EAnnotation ann = ctx.equivalent(source, "LazyAnnotation");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                return pkg;
            };
        }

        @TransformRule(name = "LazyAnnotation")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> lazyAnnotation() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("lazy_" + source.getName());
                ctx.addToResource(ann);
                createdTargetTypes.add("EAnnotation");
                return ann;
            };
        }
    }
}
