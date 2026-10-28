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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EquivalentDiscriminatedStrategy} — validates both CLONE_PRISTINE (default)
 * and CLONE_CURRENT_STATE (ETL-compatible) behaviors of {@code equivalentDiscriminated()}.
 *
 * <p>The core ETL semantic difference: ETL's {@code id.eol} clones from the CURRENT (mutated) state
 * of the original object, while ZETA's default clones from the PRISTINE (unmodified) original.
 * The CLONE_CURRENT_STATE strategy replicates ETL's behavior.</p>
 */
@DisplayName("EquivalentDiscriminatedStrategy Tests")
class EquivalentDiscriminatedStrategyTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext ctx;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        TestLazyRule.executionCount.set(0);
        TestDetachedLazyRule.executionCount.set(0);
    }

    private TransformationContext createContext(TransformationRegistry registry,
                                                EquivalentDiscriminatedStrategy strategy) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        ctx.setUseStructuredIds(true);
        ctx.setEquivalentDiscriminatedStrategy(strategy);
        return ctx;
    }

    private EClass createSource(String name) {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName(name);
        sourceResource.getContents().add(source);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(source, "source_" + name);
        }
        return source;
    }

    // ==================== CLONE_CURRENT_STATE Tests ====================

    @Nested
    @DisplayName("CLONE_CURRENT_STATE Strategy")
    class CloneCurrentStateTests {

        private TransformationRegistry registry;

        @BeforeEach
        void setUpRegistry() {
            registry = new TransformationRegistry();
            registry.register(TestLazyRule.class);
            registry.register(TestDetachedLazyRule.class);
        }

        @Test
        @DisplayName("Test 1: First caller receives the original object (no clone)")
        void firstCallerReceivesOriginal() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");

            // Get the original via equivalent() - should be same object
            EAnnotation original = ctx.equivalent(source, EAnnotation.class, "TestLazy");

            assertNotNull(resultA);
            assertSame(resultA, original,
                    "First caller should get the original object, not a clone");
        }

        @Test
        @DisplayName("Test 2: Second caller receives a clone (different identity)")
        void secondCallerReceivesClone() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");

            assertNotNull(resultA);
            assertNotNull(resultB);
            assertNotSame(resultA, resultB,
                    "Second caller should get a different object (clone)");
            assertEquals("base", resultB.getSource(),
                    "Clone should have the base name (original not yet mutated)");
        }

        @Test
        @DisplayName("Test 3: Mutations by first caller propagate to subsequent clones")
        void mutationsPropagateToClones() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            resultA.setSource(resultA.getSource() + "::suffix1");

            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");

            assertEquals("base::suffix1", resultA.getSource());
            assertEquals("base::suffix1", resultB.getSource(),
                    "Clone should inherit first caller's mutation");
        }

        @Test
        @DisplayName("Test 4: Clones copy from original, not from each other")
        void clonesCopyFromOriginalOnly() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            resultA.setSource(resultA.getSource() + "::s1");
            // resultA (the original) is now "base::s1"

            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");
            resultB.setSource(resultB.getSource() + "::s2");
            // resultB (a clone) is now "base::s1::s2" — but this mutation is on the clone, NOT the original

            EAnnotation resultC = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discC");

            assertEquals("base::s1", resultA.getSource(), "Original mutated once");
            assertEquals("base::s1::s2", resultB.getSource(), "Clone mutated independently");
            assertEquals("base::s1", resultC.getSource(),
                    "Third clone should copy from ORIGINAL (base::s1), not from resultB (base::s1::s2)");
        }

        @Test
        @DisplayName("Test 5: Same discriminator returns cached result")
        void sameDiscriminatorReturnsCached() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            resultA.setSource("mutated");

            EAnnotation resultA2 = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");

            assertSame(resultA, resultA2, "Same discriminator should return cached result");
            assertEquals("mutated", resultA2.getSource(), "Should see mutation on cached object");
        }

        @Test
        @DisplayName("Test 6: First caller's XMI ID is set to discriminated ID")
        void firstCallerXmiIdSet() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");

            String xmiId = ctx.getElementId(resultA);
            assertNotNull(xmiId, "XMI ID should be set");
            assertTrue(xmiId.contains("/(discriminator/discA)"),
                    "XMI ID should contain discriminator. Got: " + xmiId);
        }

        @Test
        @DisplayName("Test 7: Clone's XMI ID is unique per discriminator")
        void cloneXmiIdUnique() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");

            String idA = ctx.getElementId(resultA);
            String idB = ctx.getElementId(resultB);

            assertNotNull(idA);
            assertNotNull(idB);
            assertNotEquals(idA, idB, "XMI IDs should be different for different discriminators");
            assertTrue(idA.contains("/(discriminator/discA)"), "ID A should contain discA. Got: " + idA);
            assertTrue(idB.contains("/(discriminator/discB)"), "ID B should contain discB. Got: " + idB);
        }

        @Test
        @DisplayName("Test 10: Different source objects are independent")
        void differentSourcesIndependent() {
            EClass sourceX = createSource("EntityX");
            EClass sourceY = createSource("EntityY");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultX = ctx.equivalentDiscriminated(
                    sourceX, EAnnotation.class, "TestLazy", "discA");
            resultX.setSource("mutatedX");

            EAnnotation resultY = ctx.equivalentDiscriminated(
                    sourceY, EAnnotation.class, "TestLazy", "discA");

            assertEquals("mutatedX", resultX.getSource());
            assertEquals("base", resultY.getSource(),
                    "sourceY's original should be independent from sourceX's mutations");
        }

        @Test
        @DisplayName("Test 11: @Detached clones not added to Resource")
        void detachedNotInResource() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestDetachedLazy", "discA");
            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestDetachedLazy", "discB");

            assertNotNull(resultA);
            assertNotNull(resultB);
            assertFalse(targetResource.getContents().contains(resultA),
                    "@Detached result should not be in target resource");
            assertFalse(targetResource.getContents().contains(resultB),
                    "@Detached clone should not be in target resource");
        }

        @Test
        @DisplayName("Test 12: Non-detached clones ARE added to Resource")
        void nonDetachedInResource() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");

            assertTrue(targetResource.getContents().contains(resultA),
                    "Original (first caller) should be in target resource");
            assertTrue(targetResource.getContents().contains(resultB),
                    "Clone (second caller) should be in target resource");
        }

        @Test
        @DisplayName("Test 13: Multiple single-valued properties propagate")
        void multiplePropertiesPropagate() {
            EClass source = createSource("Entity1");
            createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EAnnotation resultA = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discA");
            // Mutate two properties on the original
            resultA.setSource("mutated-source");
            resultA.getDetails().put("key1", "val1");

            EAnnotation resultB = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "TestLazy", "discB");

            assertEquals("mutated-source", resultB.getSource(),
                    "Source property mutation should propagate");
            assertEquals("val1", resultB.getDetails().get("key1"),
                    "Details map mutation should propagate");
        }

        @Test
        @DisplayName("Test 14: List property mutations propagate as deep copy")
        void listMutationsPropagate() {
            EClass source = createSource("Entity1");

            // Use EClass as target type instead of EAnnotation to test eStructuralFeatures list
            TransformationRegistry eClassRegistry = new TransformationRegistry();
            eClassRegistry.register(TestLazyEClassRule.class);
            createContext(eClassRegistry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

            EClass resultA = ctx.equivalentDiscriminated(
                    source, EClass.class, "TestLazyEClass", "discA");

            // Add a structural feature to the original
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName("field1");
            attr.setEType(EcorePackage.eINSTANCE.getEString());
            resultA.getEStructuralFeatures().add(attr);

            EClass resultB = ctx.equivalentDiscriminated(
                    source, EClass.class, "TestLazyEClass", "discB");

            assertEquals(1, resultB.getEStructuralFeatures().size(),
                    "List mutation should propagate to clone");
            assertEquals("field1", resultB.getEStructuralFeatures().get(0).getName());
            assertNotSame(attr, resultB.getEStructuralFeatures().get(0),
                    "Should be a deep copy, not the same reference");
        }
    }

    // ==================== Deferred Writes Compatibility Test ====================

    @Test
    @DisplayName("Test 8: CLONE_CURRENT_STATE + deferred writes is allowed")
    void cloneCurrentStateWithDeferredWritesAllowed() {
        EClass source = createSource("Entity1");

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestLazyRule.class);
        createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

        // Enable deferred writes (parallel mode)
        ctx.enableDeferredWrites();

        EAnnotation result = assertDoesNotThrow(() ->
                ctx.equivalentDiscriminated(source, EAnnotation.class, "TestLazy", "discA"),
                "CLONE_CURRENT_STATE + deferred writes is now allowed");
        assertNotNull(result, "Should return a valid target");
        assertEquals("base", result.getSource());
    }

    // ==================== CLONE_PRISTINE Regression Test ====================

    @Test
    @DisplayName("Test 9: CLONE_PRISTINE behavior unchanged (regression)")
    void clonePristineUnchanged() {
        EClass source = createSource("Entity1");

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestLazyRule.class);
        createContext(registry, EquivalentDiscriminatedStrategy.CLONE_PRISTINE);

        EAnnotation resultA = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "TestLazy", "discA");
        resultA.setSource(resultA.getSource() + "::suffix");

        EAnnotation resultB = ctx.equivalentDiscriminated(
                source, EAnnotation.class, "TestLazy", "discB");

        assertEquals("base::suffix", resultA.getSource());
        assertEquals("base", resultB.getSource(),
                "CLONE_PRISTINE: Clone should NOT inherit mutations — pristine copy");
    }

    // ==================== Transformation Rules ====================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class TestLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "TestLazy")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("base");
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class TestDetachedLazyRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "TestDetachedLazy")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyDetachedRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EAnnotation target = ctx.createTarget(EAnnotation.class);
                target.setSource("detached-base");
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class TestLazyEClassRule {
        @TransformRule(name = "TestLazyEClass")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> lazyEClassRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName("base-class");
                return target;
            };
        }
    }

    // ==================== Test Infrastructure ====================

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
