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

    // ==================== Transformation Classes ====================

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
