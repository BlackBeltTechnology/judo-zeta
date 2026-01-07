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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for abstract type inheritance patterns.
 *
 * <p>Verifies two inheritance patterns:</p>
 * <ul>
 *   <li>Pattern 1: Automatic inheritance with @Extends (concrete types)</li>
 *   <li>Pattern 2: Manual inheritance with executeParentRule(name, source, target) (abstract parent types)</li>
 * </ul>
 */
class AbstractTypeInheritanceTest {

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
        context.setAutoAddRootElements(false);

        registry = new TransformationRegistry();
    }

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    // ==================== Manual Inheritance Pattern Tests ====================

    @Nested
    @DisplayName("Manual Inheritance Pattern (Abstract Parent Types)")
    class ManualInheritanceTests {

        @Test
        @DisplayName("executeParentRule with target passes target to parent")
        void executeParentRuleWithTargetPassesTargetToParent() {
            EClass source = createEClass("Customer");

            registry.register(ManualInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify the child rule created exactly one element
            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Verify parent rule set the name (from abstract parent logic)
            assertEquals("Customer", result.getName());

            // Verify child rule set the nsURI (child-specific logic)
            assertEquals("http://customer.test", result.getNsURI());
        }

        @Test
        @DisplayName("Parent's createTarget returns child's pre-created target")
        void parentCreateTargetReturnsChildTarget() {
            EClass source = createEClass("Order");

            registry.register(ManualInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Should have exactly one element (child created it, parent modified it)
            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Both parent and child logic applied to same object
            assertEquals("Order", result.getName());  // Parent set this
            assertEquals("http://order.test", result.getNsURI());  // Child set this
        }

        @Test
        @DisplayName("Multiple parent rules can be executed with same target")
        void multipleParentRulesWithSameTarget() {
            EClass source = createEClass("Product");

            registry.register(MultiParentTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Both parents and child applied logic to same object
            assertEquals("Product", result.getName());  // Parent1 set this
            assertEquals("http://product.ns", result.getNsURI());  // Parent2 set this
            assertEquals("product-prefix", result.getNsPrefix());  // Child set this
        }
    }

    // ==================== Automatic Inheritance Pattern Tests ====================

    @Nested
    @DisplayName("Automatic Inheritance Pattern (@Extends with Concrete Types)")
    class AutomaticInheritanceTests {

        @Test
        @DisplayName("@Extends with concrete types shares pre-created target")
        void extendsWithConcreteTypesSharesTarget() {
            EClass source = createEClass("Invoice");

            registry.register(AutomaticInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Both parent and child logic applied
            assertEquals("Invoice", result.getName());  // Parent set this
            assertEquals("http://invoice.auto", result.getNsURI());  // Child set this
        }

        @Test
        @DisplayName("Multi-level @Extends shares same target across chain")
        void multiLevelExtendsSharesTarget() {
            EClass source = createEClass("Account");

            registry.register(MultiLevelInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // All three levels applied to same object
            assertEquals("Account", result.getName());  // GrandParent set this
            assertEquals("http://account.ns", result.getNsURI());  // Parent set this
            assertEquals("account-child", result.getNsPrefix());  // Child set this
        }
    }

    // ==================== Abstract Type Handling Tests ====================

    @Nested
    @DisplayName("Abstract Type Handling")
    class AbstractTypeHandlingTests {

        @Test
        @DisplayName("Abstract parent target type doesn't cause instantiation error")
        void abstractParentDoesNotCauseError() {
            EClass source = createEClass("Entity");

            // This transformation has a parent with EClassifier (abstract) target type
            registry.register(AbstractParentTargetTransformation.class);
            context.setTransformationRegistry(registry);

            // Should not throw - abstract type is handled gracefully
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            assertDoesNotThrow(() -> executor.transform());

            assertEquals(1, targetResource.getContents().size());
        }

        @Test
        @DisplayName("Inheritance context is properly restored after executeParentRule")
        void inheritanceContextRestoredAfterParentRule() {
            EClass source1 = createEClass("First");
            EClass source2 = createEClass("Second");

            registry.register(ManualInheritanceTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Both elements should be created correctly
            assertEquals(2, targetResource.getContents().size());

            // Each should have correct values
            for (EObject obj : targetResource.getContents()) {
                EPackage pkg = (EPackage) obj;
                assertNotNull(pkg.getName());
                assertNotNull(pkg.getNsURI());
            }
        }
    }

    // ==================== Structured XMI ID Tests ====================

    @Nested
    @DisplayName("Structured XMI ID in Inheritance")
    class StructuredXmiIdTests {

        @Test
        @DisplayName("Automatic @Extends sets structured XMI ID on pre-created target")
        void automaticExtendsSetStructuredXmiId() {
            EClass source = createEClass("Customer");

            registry.register(AutomaticInheritanceTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);
            context.setIncludeElementNameInStructuredIds(true);  // Enable element name in ID for this test

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Verify XMI ID is set and contains expected components
            String xmiId = context.getPendingXmiId(result);
            assertNotNull(xmiId, "XMI ID should be set on target");
            assertTrue(xmiId.contains("DerivedPackage"), "XMI ID should contain rule name: " + xmiId);
            assertTrue(xmiId.contains("Customer"), "XMI ID should contain source name: " + xmiId);
        }

        @Test
        @DisplayName("Manual executeParentRule with target sets structured XMI ID")
        void manualExecuteParentRuleSetsXmiId() {
            EClass source = createEClass("Order");

            registry.register(ManualInheritanceTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // Verify XMI ID is set (set by child's createTarget call)
            String xmiId = context.getPendingXmiId(result);
            assertNotNull(xmiId, "XMI ID should be set on target");
            assertTrue(xmiId.contains("ConcretePackage"), "XMI ID should contain rule name: " + xmiId);
        }

        @Test
        @DisplayName("Multi-level @Extends uses child rule name in XMI ID")
        void multiLevelExtendsUsesChildRuleName() {
            EClass source = createEClass("Account");

            registry.register(MultiLevelInheritanceTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            // XMI ID should use the CHILD rule name (most derived)
            String xmiId = context.getPendingXmiId(result);
            assertNotNull(xmiId, "XMI ID should be set on target");
            assertTrue(xmiId.contains("Child"), "XMI ID should contain child rule name: " + xmiId);
        }
    }

    // ==================== Preferred Source Alias Tests ====================

    @Nested
    @DisplayName("Preferred Source Alias")
    class PreferredSourceAliasTests {

        @Test
        @DisplayName("setPreferredSourceAlias changes alias in structured XMI IDs")
        void preferredAliasChangesXmiId() {
            EClass source = createEClass("Entity");

            // Register a custom alias for the source ResourceSet
            context.registerResource("esm", sourceResourceSet);
            context.setPreferredSourceAlias("esm");

            registry.register(AutomaticInheritanceTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            assertEquals(1, targetResource.getContents().size());
            EPackage result = (EPackage) targetResource.getContents().get(0);

            String xmiId = context.getPendingXmiId(result);
            assertNotNull(xmiId, "XMI ID should be set");
            assertTrue(xmiId.contains("esm/"), "XMI ID should use 'esm' alias: " + xmiId);
            assertFalse(xmiId.contains("source/"), "XMI ID should not use 'source' alias: " + xmiId);
        }

        @Test
        @DisplayName("Unregistered alias throws exception")
        void unregisteredAliasThrows() {
            assertThrows(IllegalArgumentException.class, () -> {
                context.setPreferredSourceAlias("unregistered");
            });
        }

        @Test
        @DisplayName("Null alias resets to default behavior")
        void nullAliasResetsToDefault() {
            context.registerResource("esm", sourceResourceSet);
            context.setPreferredSourceAlias("esm");

            // Reset to default
            context.setPreferredSourceAlias(null);

            EClass source = createEClass("Test");

            registry.register(AutomaticInheritanceTransformation.class);
            context.setTransformationRegistry(registry);
            context.setUseStructuredIds(true);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            EPackage result = (EPackage) targetResource.getContents().get(0);
            String xmiId = context.getPendingXmiId(result);

            // Should use default "source" alias
            assertTrue(xmiId.contains("source/"), "XMI ID should use default 'source' alias: " + xmiId);
        }
    }

    // ==================== Transformation Classes ====================

    /**
     * Manual inheritance pattern - child creates target and passes to parent.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ManualInheritanceTransformation {

        @TransformRule(name = "AbstractNamedElement")
        @Abstract
        public TransformFunction<EClass, ENamedElement> abstractNamedElement() {
            return (source, ctx) -> {
                // createTarget returns the target passed by child
                ENamedElement target = ctx.createTarget(ENamedElement.class);
                target.setName(source.getName());
                return target;
            };
        }

        @TransformRule(name = "ConcretePackage")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> concretePackage() {
            return (source, ctx) -> {
                // Child creates CONCRETE target first
                EPackage pkg = ctx.createTarget(EPackage.class);

                // Pass target to abstract parent
                ctx.executeParentRule("AbstractNamedElement", source, pkg);

                // Add child-specific logic
                pkg.setNsURI("http://" + source.getName().toLowerCase() + ".test");

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Multiple parent rules with same target.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiParentTransformation {

        @TransformRule(name = "SetName")
        @Abstract
        public TransformFunction<EClass, ENamedElement> setName() {
            return (source, ctx) -> {
                ENamedElement target = ctx.createTarget(ENamedElement.class);
                target.setName(source.getName());
                return target;
            };
        }

        @TransformRule(name = "SetNsUri")
        @Abstract
        public TransformFunction<EClass, EPackage> setNsUri() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://" + source.getName().toLowerCase() + ".ns");
                return pkg;
            };
        }

        @TransformRule(name = "FullPackage")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> fullPackage() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);

                // Call multiple parents with same target
                ctx.executeParentRule("SetName", source, pkg);
                ctx.executeParentRule("SetNsUri", source, pkg);

                // Child-specific
                pkg.setNsPrefix(source.getName().toLowerCase() + "-prefix");

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Automatic inheritance with @Extends and concrete types.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class AutomaticInheritanceTransformation {

        @TransformRule(name = "BasePackage")
        @Abstract
        public TransformFunction<EClass, EPackage> basePackage() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "DerivedPackage")
        @Transform(type = EClass.class)
        @Extends("BasePackage")
        public TransformFunction<EClass, EPackage> derivedPackage() {
            return (source, ctx) -> {
                // createTarget returns pre-created target shared with parent
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://" + source.getName().toLowerCase() + ".auto");
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Multi-level inheritance chain.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultiLevelInheritanceTransformation {

        @TransformRule(name = "GrandParent")
        @Abstract
        public TransformFunction<EClass, EPackage> grandParent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "Parent")
        @Abstract
        @Extends("GrandParent")
        public TransformFunction<EClass, EPackage> parent() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsURI("http://" + source.getName().toLowerCase() + ".ns");
                return pkg;
            };
        }

        @TransformRule(name = "Child")
        @Transform(type = EClass.class)
        @Extends("Parent")
        public TransformFunction<EClass, EPackage> child() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setNsPrefix(source.getName().toLowerCase() + "-child");
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Parent with abstract target type (EClassifier).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class AbstractParentTargetTransformation {

        @TransformRule(name = "AbstractClassifier")
        @Abstract
        public TransformFunction<EClass, EClassifier> abstractClassifier() {
            return (source, ctx) -> {
                // EClassifier is abstract - createTarget returns child's pre-created EClass
                EClassifier target = ctx.createTarget(EClassifier.class);
                target.setName(source.getName());
                return target;
            };
        }

        @TransformRule(name = "ConcreteClass")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> concreteClass() {
            return (source, ctx) -> {
                // Create concrete EClass
                EClass eClass = ctx.createTarget(EClass.class);

                // Pass to abstract parent
                ctx.executeParentRule("AbstractClassifier", source, eClass);

                // Child-specific
                eClass.setAbstract(source.isAbstract());

                ctx.addToResource(eClass);
                return eClass;
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
