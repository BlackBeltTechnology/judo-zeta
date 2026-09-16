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
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for @Transform and @To annotations.
 * Tests annotation reflection, registry processing, and backward compatibility.
 */
@DisplayName("@Transform and @To Annotation Tests")
class TransformToAnnotationTest {

    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TransformationRegistry();
    }

    // ============================================================
    // Test Transformation Classes
    // ============================================================

    /**
     * Rule with single @Transform annotation using default alias.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SingleTransformDefaultAlias {
        @TransformRule(name = "SingleTransformDefault")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with single @Transform annotation using custom alias.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SingleTransformCustomAlias {
        @TransformRule(name = "SingleTransformCustom")
        @Transform(alias = "asm", type = EClass.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with multiple @Transform annotations from different aliases.
     * Note: For multi-source rules, the function still takes the first source type.
     * The framework handles the Cartesian product internally.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultipleTransformAnnotations {
        @TransformRule(name = "MultipleTransforms")
        @Transform(alias = "asm", type = EClass.class)
        @Transform(alias = "mapping", type = EAttribute.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with single @To annotation using default alias.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SingleToDefaultAlias {
        @TransformRule(name = "SingleToDefault")
        @To(type = EPackage.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with single @To annotation using custom alias.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SingleToCustomAlias {
        @TransformRule(name = "SingleToCustom")
        @To(alias = "rdbms", type = EPackage.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with multiple @To annotations to different aliases.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultipleToAnnotations {
        @TransformRule(name = "MultipleTos")
        @To(alias = "rdbms", type = EPackage.class)
        @To(alias = "index", type = EAttribute.class)
        public TransformFunction<EClass, EObject> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with both @Transform and @To annotations.
     * Note: For multi-source rules, the function still takes the first source type.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CombinedTransformAndTo {
        @TransformRule(name = "CombinedAnnotations")
        @Transform(alias = "asm", type = EClass.class)
        @Transform(alias = "mapping", type = EAttribute.class)
        @To(alias = "rdbms", type = EPackage.class)
        @To(alias = "index", type = EAttribute.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule using legacy sourceTypes attribute (backward compatibility).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class LegacySourceTypes {
        @TransformRule(name = "LegacySourceTypes", sourceTypes = {EClass.class, EAttribute.class})
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule using legacy targetTypes attribute (backward compatibility).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class LegacyTargetTypes {
        @TransformRule(name = "LegacyTargetTypes", targetTypes = {EPackage.class, EAttribute.class})
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with mixed usage: @Transform with targetTypes attribute.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MixedTransformWithTargetTypes {
        @TransformRule(name = "MixedTransformTargetTypes", targetTypes = {EPackage.class})
        @Transform(alias = "asm", type = EClass.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with mixed usage: sourceTypes attribute with @To.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MixedSourceTypesWithTo {
        @TransformRule(name = "MixedSourceTypesTo", sourceTypes = {EClass.class})
        @To(alias = "rdbms", type = EPackage.class)
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    /**
     * Rule with no annotations (uses defaults from @TransformationContext).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class NoAnnotationsUsesDefaults {
        @TransformRule(name = "NoAnnotations")
        public TransformFunction<EClass, EPackage> rule() {
            return (source, ctx) -> null;
        }
    }

    // ============================================================
    // Annotation Reflection Tests
    // ============================================================

    @Nested
    @DisplayName("Annotation Reflection Tests")
    class AnnotationReflectionTests {

        @Test
        @DisplayName("Should extract single @Transform annotation via reflection")
        void shouldExtractSingleTransformAnnotation() throws Exception {
            Method method = SingleTransformDefaultAlias.class.getDeclaredMethod("rule");
            
            Transform[] transforms = method.getAnnotationsByType(Transform.class);
            
            assertEquals(1, transforms.length);
            assertEquals("source", transforms[0].alias());
            assertEquals(EClass.class, transforms[0].type());
        }

        @Test
        @DisplayName("Should extract single @Transform with custom alias via reflection")
        void shouldExtractSingleTransformWithCustomAlias() throws Exception {
            Method method = SingleTransformCustomAlias.class.getDeclaredMethod("rule");
            
            Transform[] transforms = method.getAnnotationsByType(Transform.class);
            
            assertEquals(1, transforms.length);
            assertEquals("asm", transforms[0].alias());
            assertEquals(EClass.class, transforms[0].type());
        }

        @Test
        @DisplayName("Should extract multiple @Transform annotations via reflection")
        void shouldExtractMultipleTransformAnnotations() throws Exception {
            Method method = MultipleTransformAnnotations.class.getDeclaredMethod("rule");
            
            Transform[] transforms = method.getAnnotationsByType(Transform.class);
            
            assertEquals(2, transforms.length);
            assertEquals("asm", transforms[0].alias());
            assertEquals(EClass.class, transforms[0].type());
            assertEquals("mapping", transforms[1].alias());
            assertEquals(EAttribute.class, transforms[1].type());
        }

        @Test
        @DisplayName("Should extract @Transforms container annotation")
        void shouldExtractTransformsContainerAnnotation() throws Exception {
            Method method = MultipleTransformAnnotations.class.getDeclaredMethod("rule");
            
            Transforms container = method.getAnnotation(Transforms.class);
            
            assertNotNull(container);
            assertEquals(2, container.value().length);
        }

        @Test
        @DisplayName("Should extract single @To annotation via reflection")
        void shouldExtractSingleToAnnotation() throws Exception {
            Method method = SingleToDefaultAlias.class.getDeclaredMethod("rule");
            
            To[] tos = method.getAnnotationsByType(To.class);
            
            assertEquals(1, tos.length);
            assertEquals("target", tos[0].alias());
            assertEquals(EPackage.class, tos[0].type());
        }

        @Test
        @DisplayName("Should extract single @To with custom alias via reflection")
        void shouldExtractSingleToWithCustomAlias() throws Exception {
            Method method = SingleToCustomAlias.class.getDeclaredMethod("rule");
            
            To[] tos = method.getAnnotationsByType(To.class);
            
            assertEquals(1, tos.length);
            assertEquals("rdbms", tos[0].alias());
            assertEquals(EPackage.class, tos[0].type());
        }

        @Test
        @DisplayName("Should extract multiple @To annotations via reflection")
        void shouldExtractMultipleToAnnotations() throws Exception {
            Method method = MultipleToAnnotations.class.getDeclaredMethod("rule");
            
            To[] tos = method.getAnnotationsByType(To.class);
            
            assertEquals(2, tos.length);
            assertEquals("rdbms", tos[0].alias());
            assertEquals(EPackage.class, tos[0].type());
            assertEquals("index", tos[1].alias());
            assertEquals(EAttribute.class, tos[1].type());
        }

        @Test
        @DisplayName("Should extract @Tos container annotation")
        void shouldExtractTosContainerAnnotation() throws Exception {
            Method method = MultipleToAnnotations.class.getDeclaredMethod("rule");
            
            Tos container = method.getAnnotation(Tos.class);
            
            assertNotNull(container);
            assertEquals(2, container.value().length);
        }
    }

    // ============================================================
    // Registry Processing Tests - Single @Transform
    // ============================================================

    @Nested
    @DisplayName("Single @Transform Annotation Tests")
    class SingleTransformTests {

        @Test
        @DisplayName("Should register rule with single @Transform using default alias")
        void shouldRegisterRuleWithSingleTransformDefaultAlias() {
            registry.register(SingleTransformDefaultAlias.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("SingleTransformDefault");
            
            assertNotNull(descriptor);
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(1, transforms.size());
            assertEquals("source", transforms.get(0).getAlias());
            assertEquals(EClass.class, transforms.get(0).getType());
        }

        @Test
        @DisplayName("Should register rule with single @Transform using custom alias")
        void shouldRegisterRuleWithSingleTransformCustomAlias() {
            registry.register(SingleTransformCustomAlias.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("SingleTransformCustom");
            
            assertNotNull(descriptor);
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(1, transforms.size());
            assertEquals("asm", transforms.get(0).getAlias());
            assertEquals(EClass.class, transforms.get(0).getType());
        }
    }

    // ============================================================
    // Registry Processing Tests - Multiple @Transform
    // ============================================================

    @Nested
    @DisplayName("Multiple @Transform Annotations Tests")
    class MultipleTransformTests {

        @Test
        @DisplayName("Should register rule with multiple @Transform annotations")
        void shouldRegisterRuleWithMultipleTransformAnnotations() {
            registry.register(MultipleTransformAnnotations.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MultipleTransforms");
            
            assertNotNull(descriptor);
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(2, transforms.size());
            
            // First transform
            assertEquals("asm", transforms.get(0).getAlias());
            assertEquals(EClass.class, transforms.get(0).getType());
            
            // Second transform
            assertEquals("mapping", transforms.get(1).getAlias());
            assertEquals(EAttribute.class, transforms.get(1).getType());
        }

        @Test
        @DisplayName("Should use first @Transform type as primary sourceType")
        void shouldUseFirstTransformTypeAsPrimarySourceType() {
            registry.register(MultipleTransformAnnotations.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MultipleTransforms");
            
            assertNotNull(descriptor);
            assertEquals(EClass.class, descriptor.getSourceType());
        }
    }

    // ============================================================
    // Registry Processing Tests - Single @To
    // ============================================================

    @Nested
    @DisplayName("Single @To Annotation Tests")
    class SingleToTests {

        @Test
        @DisplayName("Should register rule with single @To using default alias")
        void shouldRegisterRuleWithSingleToDefaultAlias() {
            registry.register(SingleToDefaultAlias.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("SingleToDefault");
            
            assertNotNull(descriptor);
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(1, tos.size());
            assertEquals("target", tos.get(0).getAlias());
            assertEquals(EPackage.class, tos.get(0).getType());
        }

        @Test
        @DisplayName("Should register rule with single @To using custom alias")
        void shouldRegisterRuleWithSingleToCustomAlias() {
            registry.register(SingleToCustomAlias.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("SingleToCustom");
            
            assertNotNull(descriptor);
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(1, tos.size());
            assertEquals("rdbms", tos.get(0).getAlias());
            assertEquals(EPackage.class, tos.get(0).getType());
        }
    }

    // ============================================================
    // Registry Processing Tests - Multiple @To
    // ============================================================

    @Nested
    @DisplayName("Multiple @To Annotations Tests")
    class MultipleToTests {

        @Test
        @DisplayName("Should register rule with multiple @To annotations")
        void shouldRegisterRuleWithMultipleToAnnotations() {
            registry.register(MultipleToAnnotations.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MultipleTos");
            
            assertNotNull(descriptor);
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(2, tos.size());
            
            // First to
            assertEquals("rdbms", tos.get(0).getAlias());
            assertEquals(EPackage.class, tos.get(0).getType());
            
            // Second to
            assertEquals("index", tos.get(1).getAlias());
            assertEquals(EAttribute.class, tos.get(1).getType());
        }

        @Test
        @DisplayName("Should use first @To type as primary targetType")
        void shouldUseFirstToTypeAsPrimaryTargetType() {
            registry.register(MultipleToAnnotations.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MultipleTos");
            
            assertNotNull(descriptor);
            assertEquals(EPackage.class, descriptor.getTargetType());
        }
    }

    // ============================================================
    // Registry Processing Tests - Combined @Transform and @To
    // ============================================================

    @Nested
    @DisplayName("Combined @Transform and @To Tests")
    class CombinedAnnotationTests {

        @Test
        @DisplayName("Should register rule with both @Transform and @To annotations")
        void shouldRegisterRuleWithBothAnnotations() {
            registry.register(CombinedTransformAndTo.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("CombinedAnnotations");
            
            assertNotNull(descriptor);
            
            // Verify transforms
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(2, transforms.size());
            assertEquals("asm", transforms.get(0).getAlias());
            assertEquals("mapping", transforms.get(1).getAlias());
            
            // Verify tos
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(2, tos.size());
            assertEquals("rdbms", tos.get(0).getAlias());
            assertEquals("index", tos.get(1).getAlias());
        }
    }

    // ============================================================
    // Backward Compatibility Tests
    // ============================================================

    @Nested
    @DisplayName("Backward Compatibility Tests")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("Should handle legacy sourceTypes attribute with default alias")
        void shouldHandleLegacySourceTypesAttribute() {
            registry.register(LegacySourceTypes.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("LegacySourceTypes");
            
            assertNotNull(descriptor);
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(2, transforms.size());
            
            // All should use default "source" alias
            for (TransformDefinition td : transforms) {
                assertEquals("source", td.getAlias());
            }
            assertEquals(EClass.class, transforms.get(0).getType());
            assertEquals(EAttribute.class, transforms.get(1).getType());
        }

        @Test
        @DisplayName("Should handle legacy targetTypes attribute with default alias")
        void shouldHandleLegacyTargetTypesAttribute() {
            registry.register(LegacyTargetTypes.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("LegacyTargetTypes");
            
            assertNotNull(descriptor);
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(2, tos.size());
            
            // All should use default "target" alias
            for (ToDefinition td : tos) {
                assertEquals("target", td.getAlias());
            }
            assertEquals(EPackage.class, tos.get(0).getType());
            assertEquals(EAttribute.class, tos.get(1).getType());
        }

        @Test
        @DisplayName("Should use @Transform over sourceTypes when both present")
        void shouldPreferTransformOverSourceTypes() {
            registry.register(MixedTransformWithTargetTypes.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MixedTransformTargetTypes");
            
            assertNotNull(descriptor);
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(1, transforms.size());
            assertEquals("asm", transforms.get(0).getAlias()); // Custom alias from @Transform
        }

        @Test
        @DisplayName("Should use @To over targetTypes when both present")
        void shouldPreferToOverTargetTypes() {
            registry.register(MixedSourceTypesWithTo.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("MixedSourceTypesTo");
            
            assertNotNull(descriptor);
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(1, tos.size());
            assertEquals("rdbms", tos.get(0).getAlias()); // Custom alias from @To
        }

        @Test
        @DisplayName("Should fall back to @TransformationContext defaults when no annotations")
        void shouldFallbackToContextDefaults() {
            registry.register(NoAnnotationsUsesDefaults.class);
            
            TransformRuleDescriptor descriptor = registry.getRuleByName("NoAnnotations");
            
            assertNotNull(descriptor);
            
            // Should use defaults from @TransformationContext
            List<TransformDefinition> transforms = descriptor.getTransforms();
            assertEquals(1, transforms.size());
            assertEquals("source", transforms.get(0).getAlias());
            assertEquals(EClass.class, transforms.get(0).getType());
            
            List<ToDefinition> tos = descriptor.getTos();
            assertEquals(1, tos.size());
            assertEquals("target", tos.get(0).getAlias());
            assertEquals(EPackage.class, tos.get(0).getType());
        }
    }

    // ============================================================
    // TransformDefinition and ToDefinition Unit Tests
    // ============================================================

    @Nested
    @DisplayName("TransformDefinition Tests")
    class TransformDefinitionTests {

        @Test
        @DisplayName("Should create TransformDefinition with alias and type")
        void shouldCreateTransformDefinition() {
            TransformDefinition def = new TransformDefinition("asm", EClass.class);
            
            assertEquals("asm", def.getAlias());
            assertEquals(EClass.class, def.getType());
        }

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEquals() {
            TransformDefinition def1 = new TransformDefinition("asm", EClass.class);
            TransformDefinition def2 = new TransformDefinition("asm", EClass.class);
            TransformDefinition def3 = new TransformDefinition("mapping", EClass.class);
            
            assertEquals(def1, def2);
            assertNotEquals(def1, def3);
        }

        @Test
        @DisplayName("Should implement hashCode correctly")
        void shouldImplementHashCode() {
            TransformDefinition def1 = new TransformDefinition("asm", EClass.class);
            TransformDefinition def2 = new TransformDefinition("asm", EClass.class);
            
            assertEquals(def1.hashCode(), def2.hashCode());
        }

        @Test
        @DisplayName("Should implement toString correctly")
        void shouldImplementToString() {
            TransformDefinition def = new TransformDefinition("asm", EClass.class);
            
            String str = def.toString();
            assertTrue(str.contains("asm"));
            assertTrue(str.contains("EClass"));
        }

        @Test
        @DisplayName("Should throw NPE for null alias")
        void shouldThrowForNullAlias() {
            assertThrows(NullPointerException.class, () -> 
                new TransformDefinition(null, EClass.class));
        }

        @Test
        @DisplayName("Should throw NPE for null type")
        void shouldThrowForNullType() {
            assertThrows(NullPointerException.class, () -> 
                new TransformDefinition("asm", null));
        }
    }

    @Nested
    @DisplayName("ToDefinition Tests")
    class ToDefinitionTests {

        @Test
        @DisplayName("Should create ToDefinition with alias and type")
        void shouldCreateToDefinition() {
            ToDefinition def = new ToDefinition("rdbms", EPackage.class);
            
            assertEquals("rdbms", def.getAlias());
            assertEquals(EPackage.class, def.getType());
        }

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEquals() {
            ToDefinition def1 = new ToDefinition("rdbms", EPackage.class);
            ToDefinition def2 = new ToDefinition("rdbms", EPackage.class);
            ToDefinition def3 = new ToDefinition("index", EPackage.class);
            
            assertEquals(def1, def2);
            assertNotEquals(def1, def3);
        }

        @Test
        @DisplayName("Should implement hashCode correctly")
        void shouldImplementHashCode() {
            ToDefinition def1 = new ToDefinition("rdbms", EPackage.class);
            ToDefinition def2 = new ToDefinition("rdbms", EPackage.class);
            
            assertEquals(def1.hashCode(), def2.hashCode());
        }

        @Test
        @DisplayName("Should implement toString correctly")
        void shouldImplementToString() {
            ToDefinition def = new ToDefinition("rdbms", EPackage.class);
            
            String str = def.toString();
            assertTrue(str.contains("rdbms"));
            assertTrue(str.contains("EPackage"));
        }

        @Test
        @DisplayName("Should throw NPE for null alias")
        void shouldThrowForNullAlias() {
            assertThrows(NullPointerException.class, () -> 
                new ToDefinition(null, EPackage.class));
        }

        @Test
        @DisplayName("Should throw NPE for null type")
        void shouldThrowForNullType() {
            assertThrows(NullPointerException.class, () -> 
                new ToDefinition("rdbms", null));
        }
    }
}
