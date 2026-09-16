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
 * Unit tests for @Detached annotation behavior.
 *
 * <p>@Detached marks lazy rules whose output should NOT be added to Resource.contents.
 * The caller is responsible for adding the object to its proper container.</p>
 */
class DetachedRuleTest {

    private TransformationContext context;
    private TransformationRegistry registry;
    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    // Static tracking for test verification
    static List<EObject> createdObjects = new ArrayList<>();

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
        context.setAutoAddRootElements(true); // Enable auto-add to test @Detached override

        registry = new TransformationRegistry();

        createdObjects.clear();
    }

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    // ==================== @Detached Tests ====================

    @Nested
    @DisplayName("@Detached Annotation Tests")
    class DetachedAnnotationTests {

        @Test
        @DisplayName("@Detached rule creates object NOT in Resource.contents")
        void detachedRuleDoesNotAddToResource() {
            EClass source = createEClass("Entity");

            registry.register(DetachedTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // @Detached rule should NOT add to resource contents
            // Only the non-detached rule should add
            long detachedCount = targetResource.getContents().stream()
                    .filter(e -> e instanceof EAnnotation)
                    .count();

            assertEquals(0, detachedCount, "@Detached rule should NOT add EAnnotation to resource");
        }

        @Test
        @DisplayName("Non-detached rule with autoAddRootElements adds to Resource.contents")
        void nonDetachedRuleAddsToResource() {
            EClass source = createEClass("Entity");

            registry.register(NonDetachedTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Non-detached rule SHOULD add to resource contents
            assertEquals(1, targetResource.getContents().size());
        }

        @Test
        @DisplayName("@Detached object can be added to container by caller")
        void detachedObjectCanBeAddedToContainer() {
            EClass source = createEClass("Entity");

            registry.register(DetachedWithContainerTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // The parent (EPackage) should be in resource, containing the child (EAnnotation)
            assertEquals(1, targetResource.getContents().size());
            EPackage pkg = (EPackage) targetResource.getContents().get(0);
            assertEquals(1, pkg.getEAnnotations().size(), "EAnnotation should be contained in EPackage");
        }

        @Test
        @DisplayName("equivalentDiscriminated with @Detached returns same cached instance")
        void equivalentDiscriminatedReturnsSameCachedInstance() {
            EClass source = createEClass("Entity");

            registry.register(DetachedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            // First call - creates and caches
            EAnnotation first = context.equivalentDiscriminated(
                    source, EAnnotation.class, "DetachedLazy", "disc1");

            // Second call with same discriminator - should return cached instance
            EAnnotation second = context.equivalentDiscriminated(
                    source, EAnnotation.class, "DetachedLazy", "disc1");

            assertNotNull(first);
            assertSame(first, second, "Should return same cached instance");
        }

        @Test
        @DisplayName("equivalentDiscriminated with @Detached does NOT add clone to Resource")
        void equivalentDiscriminatedDoesNotAddToResource() {
            EClass source = createEClass("Entity");

            registry.register(DetachedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            // Call equivalentDiscriminated with @Detached rule
            EAnnotation result = context.equivalentDiscriminated(
                    source, EAnnotation.class, "DetachedLazy", "myDiscriminator");

            assertNotNull(result);
            // @Detached should prevent adding to Resource
            assertEquals(0, targetResource.getContents().size(),
                    "@Detached rule should NOT add clone to Resource");
        }

        @Test
        @DisplayName("Name appending accumulates on cached @Detached objects")
        void nameAppendingAccumulatesOnCachedObjects() {
            EClass source = createEClass("Entity");

            registry.register(DetachedLazyTransformation.class);
            context.setTransformationRegistry(registry);

            // First call - get base action
            EAnnotation action = context.equivalentDiscriminated(
                    source, EAnnotation.class, "DetachedLazy", "rel1");
            action.setSource(action.getSource() + "::FirstRelation");

            // Second call with different discriminator - should be a different clone
            EAnnotation action2 = context.equivalentDiscriminated(
                    source, EAnnotation.class, "DetachedLazy", "rel2");
            action2.setSource(action2.getSource() + "::SecondRelation");

            // Verify names are correctly set
            assertTrue(action.getSource().contains("::FirstRelation"));
            assertTrue(action2.getSource().contains("::SecondRelation"));
            assertNotEquals(action.getSource(), action2.getSource());
        }
    }

    // ==================== Transformation Classes ====================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DetachedTransformation {
        @TransformRule(name = "DetachedRule")
        @Transform(type = EClass.class)
        @Detached
        public TransformFunction<EClass, EAnnotation> detachedRule() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("detached_" + source.getName());
                createdObjects.add(ann);
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class NonDetachedTransformation {
        @TransformRule(name = "NonDetachedRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> nonDetachedRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                createdObjects.add(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DetachedWithContainerTransformation {
        @TransformRule(name = "ParentRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());

                // Get detached child via equivalent
                EAnnotation child = ctx.equivalent(source, "ChildDetached");
                if (child != null) {
                    // Caller adds to container
                    pkg.getEAnnotations().add(child);
                }

                return pkg;
            };
        }

        @TransformRule(name = "ChildDetached")
        @Transform(type = EClass.class)
        @Lazy
        @Detached
        public TransformFunction<EClass, EAnnotation> childDetached() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("child_" + source.getName());
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DetachedLazyTransformation {
        @TransformRule(name = "DetachedLazy")
        @Transform(type = EClass.class)
        @Lazy
        @Detached
        public TransformFunction<EClass, EAnnotation> detachedLazy() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("lazy_" + source.getName());
                return ann;
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
