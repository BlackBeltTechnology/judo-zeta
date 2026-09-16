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
 * Tests that @Lazy rules can be triggered by equivalentDiscriminated() when
 * called from utility methods (i.e., outside of transformation rule bodies).
 *
 * <h2>Problem Statement</h2>
 * <p>Before the fix, when a utility method called ctx.equivalentDiscriminated(),
 * the @Lazy rule would not be triggered because the TransformationContext
 * didn't have access to the TransformationRegistry. The equivalentDiscriminated()
 * method would return null, and the calling code would fall back to manual
 * creation, resulting in incorrect XMI IDs.</p>
 *
 * <h2>Solution</h2>
 * <p>The TransformationExecutor now sets the registry on the context in its
 * constructor, ensuring that equivalent() and equivalentDiscriminated() can
 * trigger @Lazy rules from any calling context.</p>
 *
 * @see TransformationExecutor
 * @see TransformationContext#equivalentDiscriminated(EObject, Class, String, String)
 */
@DisplayName("Utility Method @Lazy Rule Invocation")
class UtilityMethodLazyRuleInvocationTest {

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

        // Reset counters
        LazyActionRule.executionCount.set(0);
        UtilityCallerRule.utilityCallCount.set(0);
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        // NOTE: Before the fix, tests had to manually set the registry.
        // After the fix, the executor does this automatically.
        // ctx.setTransformationRegistry(registry);  // Now handled by executor
        return ctx;
    }

    private void createSourceElements(int count) {
        for (int i = 0; i < count; i++) {
            EClass ec = EcoreFactory.eINSTANCE.createEClass();
            ec.setName("Entity" + i);
            sourceResource.getContents().add(ec);
        }
    }

    // ========================================================================
    // Test: Utility method can trigger @Lazy rule via equivalentDiscriminated()
    // ========================================================================

    @Test
    @DisplayName("equivalentDiscriminated() from utility method should trigger @Lazy rule")
    void testEquivalentDiscriminatedFromUtilityMethodTriggersLazyRule() {
        createSourceElements(5);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(LazyActionRule.class);
        registry.register(UtilityCallerRule.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        // Verify the registry was set on the context (the key fix)
        assertNotNull(ctx.getTransformationRegistry(),
                "Executor should set registry on context");
        assertSame(registry, ctx.getTransformationRegistry(),
                "Context registry should be the same as executor registry");

        assertDoesNotThrow(() -> executor.transform());

        // Verify that the utility method was called for each source element
        assertEquals(5, UtilityCallerRule.utilityCallCount.get(),
                "Utility method should be called once per source element");

        // Count annotations across all packages (they are in pkg.getEAnnotations(), not resource.getContents())
        int annotationCount = 0;
        for (EObject obj : targetResource.getContents()) {
            if (obj instanceof EPackage) {
                EPackage pkg = (EPackage) obj;
                annotationCount += pkg.getEAnnotations().size();
            }
        }

        // Verify that the @Lazy rule was executed (LazyActionRule has its own executionCount)
        assertTrue(LazyActionRule.executionCount.get() > 0,
                "@Lazy rule should have been executed at least once, but was executed "
                + LazyActionRule.executionCount.get() + " times");

        // Expected: 5 sources × 3 discriminators = 15 annotations
        assertEquals(15, annotationCount,
                "Should have 15 discriminated annotations (5 sources × 3 discriminators) but got " + annotationCount);
    }

    @Test
    @DisplayName("equivalentDiscriminated() from utility method should work with @Greedy @Lazy rule")
    void testEquivalentDiscriminatedFromUtilityMethodTriggersGreedyLazyRule() {
        createSourceElements(3);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(GreedyLazyActionRule.class);
        registry.register(UtilityCallerForGreedyRule.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        assertDoesNotThrow(() -> executor.transform());

        // Verify the registry was set on the context
        assertNotNull(ctx.getTransformationRegistry(),
                "Executor should set registry on context");

        // Count annotations across all packages (they are in pkg.getEAnnotations(), not resource.getContents())
        int annotationCount = 0;
        for (EObject obj : targetResource.getContents()) {
            if (obj instanceof EPackage) {
                EPackage pkg = (EPackage) obj;
                annotationCount += pkg.getEAnnotations().size();
            }
        }

        // Expected: 3 sources × 2 discriminators = 6 annotations
        assertEquals(6, annotationCount,
                "Should have 6 discriminated annotations (3 sources × 2 discriminators)");
    }

    @Test
    @DisplayName("equivalent() with rule name from utility method should trigger @Lazy rule")
    void testEquivalentWithRuleNameFromUtilityMethodTriggersLazyRule() {
        createSourceElements(4);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(LazyTargetRule.class);
        registry.register(UtilityCallerWithEquivalent.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        assertDoesNotThrow(() -> executor.transform());

        // Verify the registry was set on the context (the key fix being tested)
        assertNotNull(ctx.getTransformationRegistry(),
                "Executor should set registry on context");
        assertSame(registry, ctx.getTransformationRegistry(),
                "Context registry should be the same as executor registry");

        // The key assertion is that the transformation didn't throw an exception
        // when calling equivalent() from the utility method. The equivalent() call
        // uses the registry to look up the @Lazy rule by name.
        //
        // Before the fix, ctx.getTransformationRegistry() would return null,
        // and equivalent() would fail to find the rule.
        //
        // Note: EDataType elements might not be in targetResource.getContents()
        // depending on how they're staged. The important thing is that the
        // transformation succeeded and the utility method was able to call
        // ctx.equivalent() without returning null due to missing registry.
    }

    // ========================================================================
    // Utility Class (simulates TabularReferenceFieldActionUtils pattern)
    // ========================================================================

    /**
     * Simulates the utility method pattern from ESM2UI transformation.
     * This static utility method calls ctx.equivalentDiscriminated() to trigger
     * a @Lazy rule and create discriminated actions.
     */
    public static class ActionUtilityMethods {
        private static final String[] DISCRIMINATORS = {"create", "update", "delete"};

        /**
         * Create discriminated actions for a source element.
         * This simulates TabularReferenceFieldActionUtils.createTabularReferenceTableCreateAction().
         *
         * @param source the source element
         * @param ctx the transformation context
         * @return list of created actions (annotations)
         */
        public static List<EAnnotation> createActions(EClass source, TransformationContext ctx) {
            List<EAnnotation> results = new ArrayList<>();

            for (String discriminator : DISCRIMINATORS) {
                // This is the key call that should trigger the @Lazy rule
                EAnnotation action = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "LazyAction", discriminator);

                if (action != null) {
                    results.add(action);
                }
            }

            return results;
        }
    }

    // ========================================================================
    // Transformation Rules
    // ========================================================================

    /**
     * A @Lazy rule that creates an action (EAnnotation).
     * This simulates TabularReferenceTableCreateAction in ESM2UI.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyActionRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyAction")
        @Lazy
        @Detached  // Actions are added to container, not to Resource.contents
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyAction() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("Action_" + source.getName());
                return action;
            };
        }
    }

    /**
     * A @Greedy @Lazy rule (matches subtypes).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class GreedyLazyActionRule {
        @TransformRule(name = "GreedyLazyAction")
        @Lazy
        @Greedy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> greedyLazyAction() {
            return (source, ctx) -> {
                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("GreedyAction_" + source.getName());
                return action;
            };
        }
    }

    /**
     * A @Lazy rule for testing equivalent() with rule name.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class LazyTargetRule {
        @TransformRule(name = "LazyTarget")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyTarget() {
            return (source, ctx) -> {
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("Target_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Eager rule that calls the utility method.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class UtilityCallerRule {
        static final AtomicInteger utilityCallCount = new AtomicInteger(0);

        @TransformRule(name = "UtilityCaller")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> callUtility() {
            return (source, ctx) -> {
                utilityCallCount.incrementAndGet();

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Pkg_" + source.getName());

                // Call the utility method - this should trigger the @Lazy rule
                List<EAnnotation> actions = ActionUtilityMethods.createActions(source, ctx);

                // Add actions to the package
                for (EAnnotation action : actions) {
                    pkg.getEAnnotations().add(action);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Eager rule that calls utility method for @Greedy @Lazy rule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class UtilityCallerForGreedyRule {
        @TransformRule(name = "UtilityCallerForGreedy")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> callUtility() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Pkg_" + source.getName());

                // Call equivalentDiscriminated directly (simulating utility method)
                for (String disc : new String[]{"view", "edit"}) {
                    EAnnotation action = ctx.equivalentDiscriminated(
                            source, EAnnotation.class, "GreedyLazyAction", disc);
                    if (action != null) {
                        pkg.getEAnnotations().add(action);
                    }
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Eager rule that uses equivalent() with rule name.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class UtilityCallerWithEquivalent {
        @TransformRule(name = "UtilityCallerWithEquivalent")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> callUtility() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Pkg_" + source.getName());

                // Call equivalent() with rule name - simulating utility method pattern
                EDataType target = UtilityMethodsForEquivalent.getTarget(source, ctx);

                // Just verify the target was created
                assertNotNull(target, "equivalent() should trigger lazy rule");

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * Utility class that uses equivalent() with rule name.
     */
    public static class UtilityMethodsForEquivalent {
        public static EDataType getTarget(EClass source, TransformationContext ctx) {
            // This should trigger the @Lazy rule
            return ctx.equivalent(source, EDataType.class, "LazyTarget");
        }
    }

    // ========================================================================
    // Model Provider
    // ========================================================================

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
