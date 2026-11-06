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
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EClass inheritance hierarchies in transformations.
 *
 * <p>Verifies transformation framework correctly handles:</p>
 * <ul>
 *   <li>Abstract class transformation (preserving abstract flag)</li>
 *   <li>Multi-level inheritance chains (3+ levels)</li>
 *   <li>Inherited feature visibility via getEAllAttributes/getEAllReferences</li>
 *   <li>Rule inheritance with @Extends matching class inheritance</li>
 *   <li>Multiple supertypes (interface-like inheritance)</li>
 *   <li>Diamond inheritance patterns</li>
 * </ul>
 */
class EcoreInheritanceTest extends AbstractEcoreTransformationTest {

    // ==================== Abstract Class Tests ====================

    @Nested
    @DisplayName("Abstract Class Transformation")
    class AbstractClassTests {

        @Test
        @DisplayName("Abstract source produces abstract target")
        void abstractSourceProducesAbstractTarget() {
            // Create abstract source class
            EClass abstractSource = createAbstractEClass("AbstractEntity");

            // Register and run transformation
            registry.register(AbstractClassTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify target is abstract
            assertTargetSize(1);
            EClass target = getTargetRoot(0, EClass.class);
            assertEClassAbstract(target, true);
            assertEClassName(target, "AbstractEntity");
        }

        @Test
        @DisplayName("Concrete source produces concrete target")
        void concreteSourceProducesConcreteTarget() {
            // Create concrete source class
            EClass concreteSource = createEClass("ConcreteEntity");

            registry.register(AbstractClassTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(1);
            EClass target = getTargetRoot(0, EClass.class);
            assertEClassAbstract(target, false);
            assertEClassName(target, "ConcreteEntity");
        }
    }

    // ==================== Multi-Level Inheritance Tests ====================

    @Nested
    @DisplayName("Multi-Level Inheritance Chain")
    class MultiLevelInheritanceTests {

        @Test
        @DisplayName("Three-level inheritance chain is preserved")
        void threeLevelInheritanceChainPreserved() {
            // Create inheritance chain: Animal (abstract) -> Mammal (abstract) -> Dog (concrete)
            EClass animal = createAbstractEClass("Animal");
            EClass mammal = createAbstractEClass("Mammal");
            mammal.getESuperTypes().add(animal);
            EClass dog = createEClass("Dog");
            dog.getESuperTypes().add(mammal);

            registry.register(MultiLevelInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(3);

            // Find targets by name
            EClass targetDog = findTargetClassByName("Dog");
            EClass targetMammal = findTargetClassByName("Mammal");
            EClass targetAnimal = findTargetClassByName("Animal");

            assertNotNull(targetDog, "Dog should be in target");
            assertNotNull(targetMammal, "Mammal should be in target");
            assertNotNull(targetAnimal, "Animal should be in target");

            // Verify inheritance chain
            assertEClassSupertypes(targetDog, targetMammal);
            assertEClassSupertypes(targetMammal, targetAnimal);

            // Verify abstract flags preserved
            assertEClassAbstract(targetAnimal, true);
            assertEClassAbstract(targetMammal, true);
            assertEClassAbstract(targetDog, false);
        }

        @Test
        @DisplayName("Inheritance with diamond pattern")
        void inheritanceWithDiamondPattern() {
            // Create diamond: A -> B, A -> C, B -> D, C -> D
            EClass classA = createAbstractEClass("InterfaceA");
            EClass classB = createAbstractEClass("ClassB");
            classB.getESuperTypes().add(classA);
            EClass classC = createAbstractEClass("ClassC");
            classC.getESuperTypes().add(classA);
            EClass classD = createEClass("ConcreteD");
            classD.getESuperTypes().add(classB);
            classD.getESuperTypes().add(classC);

            registry.register(MultiLevelInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(4);

            EClass targetD = findTargetClassByName("ConcreteD");
            EClass targetB = findTargetClassByName("ClassB");
            EClass targetC = findTargetClassByName("ClassC");
            EClass targetA = findTargetClassByName("InterfaceA");

            // D should have both B and C as supertypes
            assertEquals(2, targetD.getESuperTypes().size(), "D should have 2 supertypes");
            assertTrue(targetD.getESuperTypes().contains(targetB), "D should extend B");
            assertTrue(targetD.getESuperTypes().contains(targetC), "D should extend C");

            // A should appear only once in inheritance hierarchy
            assertTrue(targetD.getEAllSuperTypes().contains(targetA), "A should be in D's hierarchy");
        }
    }

    // ==================== Inherited Feature Visibility Tests ====================

    @Nested
    @DisplayName("Inherited Feature Visibility")
    class InheritedFeatureVisibilityTests {

        @Test
        @DisplayName("Child class can access inherited attributes via getEAllAttributes")
        void childCanAccessInheritedAttributes() {
            // Create parent with attribute
            EClass parent = createEClass("Parent");
            parent.getEStructuralFeatures().add(createStringAttribute("parentName"));

            // Create child with its own attribute
            EClass child = createEClassWithSupertypes("Child", parent);
            child.getEStructuralFeatures().add(createStringAttribute("childName"));

            registry.register(InheritedFeatureTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(2);

            EClass targetChild = findTargetClassByName("Child");
            assertNotNull(targetChild, "Child should be in target");

            // Child should see both its own and inherited attributes
            assertTrue(targetChild.getEAllAttributes().size() >= 2,
                    "Child should see all attributes including inherited");
            assertEClassHasInheritedFeature(targetChild, "parentName");
            assertEClassHasInheritedFeature(targetChild, "childName");
        }

        @Test
        @DisplayName("Child class can access inherited references via getEAllReferences")
        void childCanAccessInheritedReferences() {
            // Create target class for reference
            EClass otherClass = createEClass("OtherClass");

            // Create parent with reference
            EClass parent = createEClass("Parent");
            EReference parentRef = createEReference("parentRef", otherClass);
            parent.getEStructuralFeatures().add(parentRef);

            // Create child with its own reference
            EClass child = createEClassWithSupertypes("Child", parent);
            EReference childRef = createEReference("childRef", otherClass);
            child.getEStructuralFeatures().add(childRef);

            registry.register(InheritedFeatureTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(3);

            EClass targetChild = findTargetClassByName("Child");
            assertNotNull(targetChild, "Child should be in target");

            // Child should see both its own and inherited references
            assertTrue(targetChild.getEAllReferences().size() >= 2,
                    "Child should see all references including inherited");
            assertEClassHasInheritedFeature(targetChild, "parentRef");
            assertEClassHasInheritedFeature(targetChild, "childRef");
        }
    }

    // ==================== Rule Inheritance Tests ====================

    @Nested
    @DisplayName("Rule Inheritance with @Extends")
    class RuleInheritanceTests {

        @Test
        @DisplayName("Abstract base rule transforms common features")
        void abstractBaseRuleTransformsCommonFeatures() {
            EClass source = createEClass("TestEntity");
            source.getEStructuralFeatures().add(createStringAttribute("name"));
            source.getEStructuralFeatures().add(createIntegerAttribute("count"));

            registry.register(RuleInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(1);
            EClass target = getTargetRoot(0, EClass.class);

            // Both base and extending rules should have executed
            assertEClassHasAttribute(target, "name");
            assertEClassHasAttribute(target, "count");
        }

        @Test
        @DisplayName("Rule inheritance matches class inheritance")
        void ruleInheritanceMatchesClassInheritance() {
            // Create class hierarchy
            EClass person = createEClass("Person");
            person.getEStructuralFeatures().add(createStringAttribute("name"));

            EClass employee = createEClassWithSupertypes("Employee", person);
            employee.getEStructuralFeatures().add(createStringAttribute("employeeId"));

            registry.register(RuleInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(2);

            EClass targetPerson = findTargetClassByName("Person");
            EClass targetEmployee = findTargetClassByName("Employee");

            assertNotNull(targetPerson, "Person should be in target");
            assertNotNull(targetEmployee, "Employee should be in target");

            // Verify class inheritance preserved
            assertEClassSupertypes(targetEmployee, targetPerson);

            // Verify features transformed
            assertEClassHasAttribute(targetPerson, "name");
            assertEClassHasAttribute(targetEmployee, "employeeId");
        }
    }

    // ==================== Multiple Supertypes Tests ====================

    @Nested
    @DisplayName("Multiple Supertypes (Interface-like Inheritance)")
    class MultipleSupertypesTests {

        @Test
        @DisplayName("Class with multiple supertypes preserves all")
        void classWithMultipleSupertypesPreservesAll() {
            // Create interfaces
            EClass interfaceA = createAbstractEClass("InterfaceA");
            EClass interfaceB = createAbstractEClass("InterfaceB");

            // Create concrete class implementing both
            EClass concrete = createEClass("ConcreteImpl");
            concrete.getESuperTypes().add(interfaceA);
            concrete.getESuperTypes().add(interfaceB);

            registry.register(MultiSupertypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertTargetSize(3);

            EClass targetConcrete = findTargetClassByName("ConcreteImpl");
            EClass targetA = findTargetClassByName("InterfaceA");
            EClass targetB = findTargetClassByName("InterfaceB");

            // Should have both supertypes
            assertEClassSupertypes(targetConcrete, targetA, targetB);
        }

        @Test
        @DisplayName("Features from multiple supertypes are visible")
        void featuresFromMultipleSupertypesAreVisible() {
            // Create interfaces with attributes
            EClass interfaceA = createAbstractEClass("InterfaceA");
            interfaceA.getEStructuralFeatures().add(createStringAttribute("propertyA"));

            EClass interfaceB = createAbstractEClass("InterfaceB");
            interfaceB.getEStructuralFeatures().add(createStringAttribute("propertyB"));

            // Concrete implementation
            EClass concrete = createEClass("ConcreteImpl");
            concrete.getESuperTypes().add(interfaceA);
            concrete.getESuperTypes().add(interfaceB);

            registry.register(MultiSupertypeTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EClass targetConcrete = findTargetClassByName("ConcreteImpl");

            // Should see features from both branches
            assertEClassHasInheritedFeature(targetConcrete, "propertyA");
            assertEClassHasInheritedFeature(targetConcrete, "propertyB");
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

    // ==================== Transformation Classes ====================

    /**
     * Simple transformation preserving abstract flag.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class AbstractClassTransformation {

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation for multi-level inheritance.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class MultiLevelInheritanceTransformation {

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Transform supertypes
                for (EClass superType : source.getESuperTypes()) {
                    EClass targetSuperType = ctx.equivalent(superType, EClass.class);
                    if (targetSuperType != null) {
                        target.getESuperTypes().add(targetSuperType);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation handling inherited features.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class InheritedFeatureTransformation {

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Transform supertypes
                for (EClass superType : source.getESuperTypes()) {
                    EClass targetSuperType = ctx.equivalent(superType, EClass.class);
                    if (targetSuperType != null) {
                        target.getESuperTypes().add(targetSuperType);
                    }
                }

                // Transform structural features (only direct, not inherited)
                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof org.eclipse.emf.ecore.EAttribute attr) {
                        var targetAttr = ctx.createTarget(org.eclipse.emf.ecore.EAttribute.class);
                        targetAttr.setName(attr.getName());
                        targetAttr.setEType(attr.getEType());
                        target.getEStructuralFeatures().add(targetAttr);
                    } else if (feature instanceof EReference ref) {
                        var targetRef = ctx.createTarget(org.eclipse.emf.ecore.EReference.class);
                        targetRef.setName(ref.getName());
                        targetRef.setEType(ctx.equivalent((EClass) ref.getEType(), EClass.class));
                        target.getEStructuralFeatures().add(targetRef);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Transformation with rule inheritance using @Extends.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class RuleInheritanceTransformation {

        @TransformRule(name = "BaseEClassRule")
        @Abstract
        public TransformFunction<EClass, EClass> baseEClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Transform supertypes
                for (EClass superType : source.getESuperTypes()) {
                    EClass targetSuperType = ctx.equivalent(superType, EClass.class);
                    if (targetSuperType != null) {
                        target.getESuperTypes().add(targetSuperType);
                    }
                }

                return target;
            };
        }

        @TransformRule(name = "ConcreteEClassRule")
        @Transform(type = EClass.class)
        @Extends("BaseEClassRule")
        public TransformFunction<EClass, EClass> concreteEClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);

                // Transform direct features
                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof org.eclipse.emf.ecore.EAttribute attr) {
                        var targetAttr = ctx.createTarget(org.eclipse.emf.ecore.EAttribute.class);
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
     * Transformation for multiple supertypes.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class MultiSupertypeTransformation {

        @TransformRule(name = "EClassRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> eClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Transform all supertypes
                for (EClass superType : source.getESuperTypes()) {
                    EClass targetSuperType = ctx.equivalent(superType, EClass.class);
                    if (targetSuperType != null) {
                        target.getESuperTypes().add(targetSuperType);
                    }
                }

                // Transform features
                for (var feature : source.getEStructuralFeatures()) {
                    if (feature instanceof org.eclipse.emf.ecore.EAttribute attr) {
                        var targetAttr = ctx.createTarget(org.eclipse.emf.ecore.EAttribute.class);
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
}
