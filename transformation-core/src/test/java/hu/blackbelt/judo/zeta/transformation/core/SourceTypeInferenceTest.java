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
 * TDD tests for automatic source type inference from generic parameters.
 *
 * <p>These tests verify that the framework correctly infers source types from
 * {@code TransformFunction<S, T>} generic parameters when no explicit source
 * type is specified via {@code @Transform} or {@code sourceTypes} attribute.</p>
 *
 * <p><b>TDD Approach:</b> These tests are written BEFORE implementation.
 * Tests marked with "Expected: FAIL" should fail until implementation is complete.</p>
 */
@DisplayName("Source Type Inference from Generic Parameters")
class SourceTypeInferenceTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields for transformation rules
    static AtomicInteger executionCount = new AtomicInteger(0);
    static List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
    static Set<String> transformedTypes = Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void setUp() {
        // Reset tracking
        executionCount.set(0);
        executionLog.clear();
        transformedTypes.clear();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    // ==================== Helper Methods ====================

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    private EDataType createEDataType(String name) {
        EDataType dataType = EcoreFactory.eINSTANCE.createEDataType();
        dataType.setName(name);
        sourceResource.getContents().add(dataType);
        return dataType;
    }

    private EAttribute createEAttribute(String name) {
        EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName(name);
        sourceResource.getContents().add(attr);
        return attr;
    }

    // ==================== P0: Core Functionality Tests ====================

    @Nested
    @DisplayName("P0: Basic Generic Type Extraction")
    class BasicGenericTypeExtractionTests {

        /**
         * Test that source type is inferred from TransformFunction<EClass, EPackage>.
         * The rule has no @Transform annotation, so source type should be inferred from generic.
         *
         * Expected: FAIL until implementation complete (currently falls back to default EObject)
         */
        @Test
        @DisplayName("Source type inferred from TransformFunction<EClass, EPackage>")
        void sourceTypeInferredFromGenericParameter() {
            registry.register(InferredEClassTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("InferredEClassRule");

            // Source type should be inferred from generic parameter, not default
            assertEquals(EClass.class, rule.getSourceType(),
                    "Source type should be inferred from TransformFunction<EClass, EPackage>");
        }

        /**
         * Test that inferred source type actually filters elements correctly.
         * Rule with TransformFunction<EClass, X> should only execute for EClass elements.
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Inferred source type filters elements correctly")
        void inferredSourceTypeFiltersElements() {
            // Create mixed elements
            createEClass("TestClass");
            createEDataType("TestDataType");
            createEAttribute("TestAttribute");

            registry.register(InferredEClassTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Should only transform EClass, not EDataType or EAttribute
            assertEquals(1, executionCount.get(),
                    "Rule with inferred EClass source should only execute for EClass elements");
            assertTrue(transformedTypes.contains("EClass"),
                    "Should have transformed EClass");
            assertFalse(transformedTypes.contains("EDataType"),
                    "Should NOT have transformed EDataType");
            assertFalse(transformedTypes.contains("EAttribute"),
                    "Should NOT have transformed EAttribute");
        }
    }

    /**
     * Transformation with source type inferred from generic parameter.
     * No @Transform annotation - source type should be inferred from TransformFunction<EClass, EPackage>.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class InferredEClassTransformation {
        @TransformRule(name = "InferredEClassRule")
        // NO @Transform annotation - should infer EClass from generic parameter
        public TransformFunction<EClass, EPackage> inferredEClassRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                transformedTypes.add(source.eClass().getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    // ==================== P0: Priority Order Tests ====================

    @Nested
    @DisplayName("P0: Priority Order - Explicit Annotations Take Precedence")
    class PriorityOrderTests {

        /**
         * Test that @Transform annotation takes priority over inferred type.
         * Rule has TransformFunction<EObject, X> but @Transform(type = EClass.class).
         *
         * Expected: PASS (existing behavior)
         */
        @Test
        @DisplayName("@Transform annotation takes priority over generic inference")
        void transformAnnotationTakesPriority() {
            registry.register(ExplicitTransformAnnotationTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("ExplicitTransformRule");

            // @Transform type should override generic parameter
            assertEquals(EClass.class, rule.getSourceType(),
                    "@Transform annotation should take priority over generic inference");
        }

        /**
         * Test that sourceTypes attribute takes priority over inferred type.
         *
         * Expected: PASS (existing behavior)
         */
        @Test
        @DisplayName("sourceTypes attribute takes priority over generic inference")
        void sourceTypesAttributeTakesPriority() {
            registry.register(ExplicitSourceTypesTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("ExplicitSourceTypesRule");

            // sourceTypes attribute should override generic parameter
            assertEquals(EDataType.class, rule.getSourceType(),
                    "sourceTypes attribute should take priority over generic inference");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class ExplicitTransformAnnotationTransformation {
        @TransformRule(name = "ExplicitTransformRule")
        @Transform(type = EClass.class)  // Explicit - should take priority
        public TransformFunction<EObject, EPackage> explicitTransformRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                return ctx.createTarget(EPackage.class);
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class ExplicitSourceTypesTransformation {
        @TransformRule(name = "ExplicitSourceTypesRule", sourceTypes = {EDataType.class})
        public TransformFunction<EObject, EPackage> explicitSourceTypesRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                return ctx.createTarget(EPackage.class);
            };
        }
    }

    // ==================== P0: Greedy Rule Semantics Tests ====================

    @Nested
    @DisplayName("P0: Greedy Rules with Inferred Supertype")
    class GreedyRuleTests {

        /**
         * Test that @Greedy rule with inferred supertype matches all subtypes.
         * Rule: TransformFunction<EClassifier, X> with @Greedy
         * Should match both EClass and EDataType (subtypes of EClassifier).
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Greedy rule with inferred supertype matches subtypes")
        void greedyRuleWithInferredSupertypeMatchesSubtypes() {
            // Create EClass and EDataType (both subtypes of EClassifier)
            createEClass("TestClass");
            createEDataType("TestDataType");

            registry.register(GreedyInferredClassifierTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Greedy rule should match both EClass and EDataType
            assertEquals(2, executionCount.get(),
                    "Greedy rule with inferred EClassifier should match both EClass and EDataType");
            assertTrue(transformedTypes.contains("EClass"));
            assertTrue(transformedTypes.contains("EDataType"));
        }

        /**
         * Test that greedy rule is included in getEagerRulesForType for subtypes.
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Greedy rule included in getEagerRulesForType for subtypes")
        void greedyRuleIncludedInEagerRulesForSubtypes() {
            registry.register(GreedyInferredClassifierTransformation.class);

            // EClassImpl is the implementation class for EClass
            @SuppressWarnings("unchecked")
            List<TransformRuleDescriptor> rulesForEClass = registry.getEagerRulesForType(
                    (Class<? extends EObject>) EcoreFactory.eINSTANCE.createEClass().getClass());

            assertTrue(rulesForEClass.stream().anyMatch(r -> r.getName().equals("GreedyClassifierRule")),
                    "Greedy rule for EClassifier should be included in rules for EClass");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class GreedyInferredClassifierTransformation {
        @TransformRule(name = "GreedyClassifierRule")
        @Greedy  // Kind-of semantics - matches subtypes
        // NO @Transform - should infer EClassifier from generic parameter
        public TransformFunction<EClassifier, EPackage> greedyClassifierRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                transformedTypes.add(source.eClass().getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    // ==================== P0: Non-Greedy Exact Type Matching ====================

    @Nested
    @DisplayName("P0: Non-Greedy Rules Match Exact Type Only")
    class NonGreedyExactTypeTests {

        /**
         * Test that non-greedy rule with inferred type matches only exact type.
         * Rule: TransformFunction<EClass, X> (no @Greedy)
         * Should match EClass but NOT subtypes.
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Non-greedy rule with inferred type matches exact type only")
        void nonGreedyRuleMatchesExactTypeOnly() {
            // Create EClass only (EDataType is NOT a subtype of EClass)
            createEClass("TestClass");
            createEDataType("TestDataType");

            registry.register(InferredEClassTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Non-greedy rule should match only EClass
            assertEquals(1, executionCount.get(),
                    "Non-greedy rule with inferred EClass should match only EClass");
            assertTrue(transformedTypes.contains("EClass"));
            assertFalse(transformedTypes.contains("EDataType"));
        }
    }

    // ==================== P0: Behavioral Equivalence Tests ====================

    @Nested
    @DisplayName("P0: Behavioral Equivalence with appliesTo()")
    class BehavioralEquivalenceTests {

        /**
         * Test that type filtering produces identical results to appliesTo().
         * For each rule, the set of elements passing type filter must equal
         * the set of elements where appliesTo() returns true.
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Type filtering matches appliesTo() for all elements")
        void typeFilteringMatchesAppliesTo() {
            // Create mixed elements
            EClass eClass = createEClass("TestClass");
            EDataType eDataType = createEDataType("TestDataType");
            EAttribute eAttribute = createEAttribute("TestAttribute");

            List<EObject> elements = Arrays.asList(eClass, eDataType, eAttribute);

            registry.register(InferredEClassTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("InferredEClassRule");

            // Count elements that pass appliesTo() (current runtime behavior)
            long appliesToCount = elements.stream()
                    .filter(rule::appliesTo)
                    .count();

            // Count elements that pass type filter (new compile-time filtering)
            long typeFilterCount = elements.stream()
                    .filter(e -> {
                        @SuppressWarnings("unchecked")
                        Collection<TransformRuleDescriptor> rulesForType =
                                registry.getRulesForSource((Class<? extends EObject>) e.getClass());
                        return rulesForType.contains(rule);
                    })
                    .count();

            assertEquals(appliesToCount, typeFilterCount,
                    "Type filtering must produce identical results to appliesTo()");
        }
    }

    // ==================== P1: Edge Case Tests ====================

    @Nested
    @DisplayName("P1: Edge Cases")
    class EdgeCaseTests {

        /**
         * Test that raw EObject generic parameter falls back to default.
         * Rule: TransformFunction<EObject, X> should use @TransformationContext default.
         *
         * Expected: PASS (fallback behavior)
         */
        @Test
        @DisplayName("Raw EObject generic falls back to @TransformationContext default")
        void rawEObjectFallsBackToDefault() {
            registry.register(RawEObjectTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("RawEObjectRule");

            // Should fall back to default (ENamedElement from @TransformationContext)
            // because inferring EObject provides no filtering benefit
            assertEquals(ENamedElement.class, rule.getSourceType(),
                    "Raw EObject generic should fall back to @TransformationContext default");
        }

        /**
         * Test that multi-source rules with @Transform are unaffected.
         *
         * Expected: PASS (existing behavior)
         */
        @Test
        @DisplayName("Multi-source rules with explicit @Transform unaffected")
        void multiSourceRulesUnaffected() {
            registry.register(MultiTransformTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("MultiTransformRule");

            // @Transform annotations should still work
            assertEquals(EClass.class, rule.getSourceType());
            assertTrue(rule.isMultiSource());
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = ENamedElement.class, target = EPackage.class)
    public static class RawEObjectTransformation {
        @TransformRule(name = "RawEObjectRule")
        // Generic is EObject - should fall back to default ENamedElement
        public TransformFunction<EObject, EPackage> rawEObjectRule() {
            return (source, ctx) -> ctx.createTarget(EPackage.class);
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class MultiTransformTransformation {
        @TransformRule(name = "MultiTransformRule")
        @Transform(alias = "a", type = EClass.class)
        @Transform(alias = "b", type = EAttribute.class)
        public MultiSourceTransformFunction<EPackage> multiTransformRule() {
            return (sources, ctx) -> ctx.createTarget(EPackage.class);
        }
    }

    // ==================== P1: Lazy Rule Tests ====================

    @Nested
    @DisplayName("P1: Lazy Rules with Inferred Types")
    class LazyRuleTests {

        /**
         * Test that lazy rules with inferred types are correctly filtered.
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Lazy rules with inferred types correctly filtered")
        void lazyRulesWithInferredTypesFiltered() {
            registry.register(LazyInferredTypeTransformation.class);

            @SuppressWarnings("unchecked")
            List<TransformRuleDescriptor> lazyRulesForEClass = registry.getLazyRulesForType(
                    (Class<? extends EObject>) EcoreFactory.eINSTANCE.createEClass().getClass());

            assertTrue(lazyRulesForEClass.stream().anyMatch(r -> r.getName().equals("LazyInferredRule")),
                    "Lazy rule with inferred EClass should be included in lazy rules for EClass");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class LazyInferredTypeTransformation {
        @TransformRule(name = "LazyInferredRule")
        @Lazy
        // NO @Transform - should infer EClass from generic parameter
        public TransformFunction<EClass, EPackage> lazyInferredRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    // ==================== P2: Performance Validation Tests ====================

    @Nested
    @DisplayName("P2: Performance Validation")
    class PerformanceValidationTests {

        /**
         * Test that rules are only executed for matching element types.
         * Without type filtering: rules would be checked against all elements
         * With type filtering: only matching (rule, element) pairs are executed
         *
         * Expected: FAIL until implementation complete
         */
        @Test
        @DisplayName("Rules only execute for matching element types")
        void rulesOnlyExecuteForMatchingTypes() {
            // Create elements of different types
            for (int i = 0; i < 10; i++) {
                createEClass("Class" + i);
                createEDataType("DataType" + i);
                createEAttribute("Attribute" + i);
            }

            // Register rules for specific types
            registry.register(InferredEClassTransformation.class);
            registry.register(InferredEDataTypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // With type filtering:
            // - EClass rule should only execute for 10 EClass elements
            // - EDataType rule should only execute for 10 EDataType elements
            // Total: 20 executions (not checking all 30 elements against both rules)
            assertEquals(20, executionCount.get(),
                    "Rules should only execute for matching types: 10 EClass + 10 EDataType = 20");

            // Verify correct types were transformed
            assertTrue(transformedTypes.contains("EClass"), "Should transform EClass");
            assertTrue(transformedTypes.contains("EDataType"), "Should transform EDataType");
            assertFalse(transformedTypes.contains("EAttribute"), "Should NOT transform EAttribute");
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class InferredEDataTypeTransformation {
        @TransformRule(name = "InferredEDataTypeRule")
        // NO @Transform - should infer EDataType from generic parameter
        public TransformFunction<EDataType, EPackage> inferredEDataTypeRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                transformedTypes.add(source.eClass().getName());
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    // ==================== Test Model Provider ====================

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
