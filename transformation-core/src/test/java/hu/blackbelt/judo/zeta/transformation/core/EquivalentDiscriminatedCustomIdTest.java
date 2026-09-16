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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 5-argument {@code equivalentDiscriminated()} overload with explicit {@code customId}.
 *
 * <p>When {@code customId} is non-null, it SHALL override the generated discriminated ID
 * for the clone/original across all cloning strategies (CLONE_PRISTINE, CLONE_CURRENT_STATE).</p>
 */
@DisplayName("equivalentDiscriminated customId Tests")
class EquivalentDiscriminatedCustomIdTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
    }

    private TransformationContext createContext(TransformationRegistry registry,
                                                EquivalentDiscriminatedStrategy strategy) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setAutoAddRootElements(true);
        ctx.setUseStructuredIds(true);
        ctx.setTransformationRegistry(registry);
        ctx.setEquivalentDiscriminatedStrategy(strategy);
        return ctx;
    }

    private EClass createSource(String name, String xmiId) {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName(name);
        sourceResource.getContents().add(source);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(source, xmiId);
        }
        return source;
    }

    // ==================== Scenario A: CLONE_PRISTINE — customId overrides generated ID ====================

    @Test
    @DisplayName("Scenario A: CLONE_PRISTINE — customId overrides generated discriminated ID")
    void scenarioA_clonePristine_customIdOverridesGeneratedId() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioATransformation.capturedCloneId.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioATransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_PRISTINE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        String cloneId = ScenarioATransformation.capturedCloneId.get();
        assertNotNull(cloneId, "Clone should have an ID");
        assertEquals("my/custom/id", cloneId,
                "Clone's XMI ID should be the customId, not the generated discriminated ID");
    }

    // ==================== Scenario B: null customId preserves existing behavior ====================

    @Test
    @DisplayName("Scenario B: null customId preserves existing behavior (4-arg overload)")
    void scenarioB_nullCustomId_preservesExistingBehavior() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioBTransformation.capturedCloneId.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioBTransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_PRISTINE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        String cloneId = ScenarioBTransformation.capturedCloneId.get();
        assertNotNull(cloneId, "Clone should have an ID");
        // Standard discriminated ID format: (source/_widget001)/CreateLazy/(discriminator/test-disc)
        assertTrue(cloneId.contains("/(discriminator/test-disc)"),
                "Clone should have standard discriminated ID format but was: " + cloneId);
    }

    // ==================== Scenario C: CLONE_CURRENT_STATE — first caller gets original with customId ====================

    @Test
    @DisplayName("Scenario C: CLONE_CURRENT_STATE — first caller gets original with customId")
    void scenarioC_cloneCurrentState_firstCallerGetsOriginalWithCustomId() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioCTransformation.capturedId.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioCTransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        String id = ScenarioCTransformation.capturedId.get();
        assertNotNull(id, "Element should have an ID");
        assertEquals("first/caller/id", id,
                "First caller should get original with customId");
    }

    // ==================== Scenario D: CLONE_CURRENT_STATE — subsequent caller gets clone with customId ====================

    @Test
    @DisplayName("Scenario D: CLONE_CURRENT_STATE — subsequent caller gets clone with different customId")
    void scenarioD_cloneCurrentState_subsequentCallerGetsCloneWithCustomId() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioDTransformation.capturedFirstId.set(null);
        ScenarioDTransformation.capturedSecondId.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioDTransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_CURRENT_STATE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        String firstId = ScenarioDTransformation.capturedFirstId.get();
        String secondId = ScenarioDTransformation.capturedSecondId.get();
        assertNotNull(firstId, "First element should have an ID");
        assertNotNull(secondId, "Second element should have an ID");
        assertEquals("first/caller/id", firstId,
                "First caller's original should have its customId");
        assertEquals("second/caller/id", secondId,
                "Second caller's clone should have its customId");
    }

    // ==================== Scenario E: Second call with same customId returns cached clone ====================

    @Test
    @DisplayName("Scenario E: Second call with same (source, rule, disc, customId) returns cached clone")
    void scenarioE_secondCallReturnsCachedCloneWithCustomId() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioETransformation.capturedFirstResult.set(null);
        ScenarioETransformation.capturedSecondResult.set(null);
        ScenarioETransformation.capturedSameInstance.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioETransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_PRISTINE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        Boolean sameInstance = ScenarioETransformation.capturedSameInstance.get();
        assertNotNull(sameInstance, "Should have compared instances");
        assertTrue(sameInstance, "Second call should return the same cached clone instance");

        String firstId = ScenarioETransformation.capturedFirstResult.get();
        String secondId = ScenarioETransformation.capturedSecondResult.get();
        assertEquals("my/custom/id", firstId, "First result should have customId");
        assertEquals("my/custom/id", secondId, "Second result should have same customId");
    }

    // ==================== Scenario F: 4-arg overload delegates to 5-arg with null ====================

    @Test
    @DisplayName("Scenario F: 4-arg overload produces identical result to 5-arg with null customId")
    void scenarioF_fourArgDelegatesToFiveArgWithNull() {
        EClass source = createSource("Widget", "_widget001");

        ScenarioFTransformation.capturedFourArgId.set(null);
        ScenarioFTransformation.capturedFiveArgId.set(null);
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ScenarioFTransformation.class);

        TransformationContext ctx = createContext(registry, EquivalentDiscriminatedStrategy.CLONE_PRISTINE);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        String fourArgId = ScenarioFTransformation.capturedFourArgId.get();
        String fiveArgId = ScenarioFTransformation.capturedFiveArgId.get();
        assertNotNull(fourArgId, "4-arg clone should have an ID");
        assertNotNull(fiveArgId, "5-arg clone should have an ID");
        // Both should use the same discriminated ID format (just different discriminator values)
        // They won't be exactly equal because different discriminators, but format should match
        assertTrue(fourArgId.contains("/(discriminator/disc-4arg)"),
                "4-arg should use standard format but was: " + fourArgId);
        assertTrue(fiveArgId.contains("/(discriminator/disc-5arg)"),
                "5-arg with null customId should use standard format but was: " + fiveArgId);
    }

    // ==================== Test Model Provider ====================

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                var iterator = resource.getAllContents();
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

    // ==================== Transformation Classes ====================

    /**
     * Scenario A: CLONE_PRISTINE with customId.
     * MainRule calls equivalentDiscriminated with customId — clone should have that ID.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioATransformation {
        static final AtomicReference<String> capturedCloneId = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                EDataType clone = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "test-disc", "my/custom/id");
                if (clone != null) {
                    capturedCloneId.set(ctx.getElementId(clone));
                }
                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }

    /**
     * Scenario B: null customId preserves existing behavior (uses 4-arg overload).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioBTransformation {
        static final AtomicReference<String> capturedCloneId = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // 4-arg overload (no customId)
                EDataType clone = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "test-disc");
                if (clone != null) {
                    capturedCloneId.set(ctx.getElementId(clone));
                }
                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }

    /**
     * Scenario C: CLONE_CURRENT_STATE — first caller gets original with customId.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioCTransformation {
        static final AtomicReference<String> capturedId = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                EDataType result = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "disc1", "first/caller/id");
                if (result != null) {
                    capturedId.set(ctx.getElementId(result));
                }
                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }

    /**
     * Scenario D: CLONE_CURRENT_STATE — first caller and subsequent caller both with customId.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioDTransformation {
        static final AtomicReference<String> capturedFirstId = new AtomicReference<>();
        static final AtomicReference<String> capturedSecondId = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // First call — gets the original
                EDataType first = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "disc1", "first/caller/id");
                if (first != null) {
                    capturedFirstId.set(ctx.getElementId(first));
                }

                // Second call with different discriminator — gets a clone
                EDataType second = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "disc2", "second/caller/id");
                if (second != null) {
                    capturedSecondId.set(ctx.getElementId(second));
                }

                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }

    /**
     * Scenario E: Second call with same (source, rule, disc, customId) returns cached clone.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioETransformation {
        static final AtomicReference<String> capturedFirstResult = new AtomicReference<>();
        static final AtomicReference<String> capturedSecondResult = new AtomicReference<>();
        static final AtomicReference<Boolean> capturedSameInstance = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // First call — creates clone with customId
                EDataType first = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "test-disc", "my/custom/id");

                // Second call — same args, should return cached clone
                EDataType second = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "test-disc", "my/custom/id");

                if (first != null) capturedFirstResult.set(ctx.getElementId(first));
                if (second != null) capturedSecondResult.set(ctx.getElementId(second));
                capturedSameInstance.set(first == second);

                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }

    /**
     * Scenario F: 4-arg and 5-arg(null) produce same format IDs.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioFTransformation {
        static final AtomicReference<String> capturedFourArgId = new AtomicReference<>();
        static final AtomicReference<String> capturedFiveArgId = new AtomicReference<>();

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // 4-arg overload
                EDataType clone4 = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "disc-4arg");
                if (clone4 != null) {
                    capturedFourArgId.set(ctx.getElementId(clone4));
                }

                // 5-arg overload with null customId
                EDataType clone5 = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateLazy", "disc-5arg", null);
                if (clone5 != null) {
                    capturedFiveArgId.set(ctx.getElementId(clone5));
                }

                return container;
            };
        }

        @TransformRule(name = "CreateLazy")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createLazy() {
            return (source, ctx) -> {
                EDataType dt = ctx.createTarget(EDataType.class);
                dt.setName("LazyCreated");
                ctx.addToResource(dt);
                return dt;
            };
        }
    }
}
