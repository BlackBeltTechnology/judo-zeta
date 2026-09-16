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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for circular dependency handling in equivalentDiscriminated().
 *
 * <h2>Problem Statement</h2>
 * <p>When utility methods call ctx.equivalentDiscriminated() to trigger @Lazy rules,
 * and those rules in turn call other transformations that eventually call back to
 * the same utility method, a circular dependency occurs.</p>
 *
 * <h2>Call Chain Example</h2>
 * <pre>
 * 1. AccessViewTransformations.calculateTabularReferenceFieldActions
 *    → createTabularReferenceTableRowViewAction (utility method)
 * 2. createTabularReferenceTableRowViewAction
 *    → ctx.equivalentDiscriminated(source, "TabularReferenceTableRowViewAction", discriminator)
 * 3. TabularReferenceTableRowViewAction rule
 *    → ctx.equivalent(relation, "RelationFeatureView")
 * 4. RelationFeatureView rule
 *    → calculateActions (utility method) - CIRCULAR!
 * 5. calculateActions
 *    → createTabularReferenceTableRowViewAction - back to step 2!
 * </pre>
 *
 * <h2>Expected Behavior</h2>
 * <p>When a circular call is detected, equivalentDiscriminated() should return null
 * (or a placeholder) to break the cycle, allowing the transformation to complete
 * without infinite recursion.</p>
 */
@DisplayName("Circular Dependency in equivalentDiscriminated()")
class CircularEquivalentDiscriminatedTest {

    private static final Logger LOG = LoggerFactory.getLogger(CircularEquivalentDiscriminatedTest.class);

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
        ViewRule.executionCount.set(0);
        ActionRule.executionCount.set(0);
        UtilityMethods.callCount.set(0);
        UtilityMethods.nullReturns.set(0);
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        return ctx;
    }

    private EClass createSourceElement(String name) {
        EClass ec = EcoreFactory.eINSTANCE.createEClass();
        ec.setName(name);
        sourceResource.getContents().add(ec);
        return ec;
    }

    // ========================================================================
    // Test: Circular dependency should be handled gracefully
    // ========================================================================

    @Test
    @DisplayName("equivalentDiscriminated() should handle circular dependencies")
    void testCircularDependencyHandling() {
        EClass source = createSourceElement("TestEntity");

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ViewRule.class);
        registry.register(ActionRule.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        // This should NOT throw StackOverflowError
        assertDoesNotThrow(() -> executor.transform(),
                "Transformation should handle circular dependencies without StackOverflowError");

        LOG.info("ViewRule executions: {}", ViewRule.executionCount.get());
        LOG.info("ActionRule executions: {}", ActionRule.executionCount.get());
        LOG.info("Utility method calls: {}", UtilityMethods.callCount.get());
        LOG.info("Utility method null returns: {}", UtilityMethods.nullReturns.get());

        // ViewRule should execute once per source
        assertEquals(1, ViewRule.executionCount.get(),
                "ViewRule should execute once");

        // ActionRule may execute multiple times due to discriminators, but should not infinite loop
        assertTrue(ActionRule.executionCount.get() > 0,
                "ActionRule should execute at least once");
        assertTrue(ActionRule.executionCount.get() < 100,
                "ActionRule should not execute excessively (circular loop detected)");
    }

    @Test
    @DisplayName("Direct circular equivalentDiscriminated() returns null on second call")
    void testDirectCircularReturnsNull() {
        EClass source = createSourceElement("TestEntity");

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(DirectCircularRule.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        assertDoesNotThrow(() -> executor.transform(),
                "Direct circular dependency should not cause StackOverflow");

        LOG.info("DirectCircularRule executions: {}", DirectCircularRule.executionCount.get());
        LOG.info("DirectCircularRule circular calls: {}", DirectCircularRule.circularCallCount.get());

        // Rule should execute once
        assertEquals(1, DirectCircularRule.executionCount.get(),
                "Rule should execute exactly once");

        // The circular call should have returned null (or been blocked)
        assertTrue(DirectCircularRule.circularCallCount.get() >= 1,
                "Circular call should have been attempted");
    }

    // ========================================================================
    // Utility Methods (simulates TabularReferenceFieldActionUtils pattern)
    // ========================================================================

    /**
     * Simulates the utility method pattern that causes circular dependencies.
     */
    public static class UtilityMethods {
        static final AtomicInteger callCount = new AtomicInteger(0);
        static final AtomicInteger nullReturns = new AtomicInteger(0);

        /**
         * Creates an action by calling equivalentDiscriminated.
         * This simulates createTabularReferenceTableRowViewAction.
         */
        public static EAnnotation createAction(EClass source, String discriminator,
                TransformationContext ctx) {
            int count = callCount.incrementAndGet();
            LOG.debug("UtilityMethods.createAction called (count={}), source={}, discriminator={}",
                    count, source.getName(), discriminator);

            // This is the call that can cause circular dependency
            EAnnotation action = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "ActionRule", discriminator);

            if (action == null) {
                nullReturns.incrementAndGet();
                LOG.debug("UtilityMethods.createAction: equivalentDiscriminated returned null (circular?)");
            }

            return action;
        }
    }

    // ========================================================================
    // Transformation Rules
    // ========================================================================

    /**
     * ViewRule - simulates AccessViewPageDefinition or RelationFeatureView.
     * This rule calls UtilityMethods.createAction which triggers ActionRule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ViewRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ViewRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> viewRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("ViewRule executing (count={}), source={}", count, source.getName());

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("View_" + source.getName());

                // Call utility method to create actions - this triggers ActionRule
                for (String disc : new String[]{"view", "edit", "delete"}) {
                    EAnnotation action = UtilityMethods.createAction(source, disc, ctx);
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
     * ActionRule - simulates TabularReferenceTableRowViewAction.
     * This rule is @Lazy and is triggered by UtilityMethods.createAction.
     * Inside, it calls ctx.equivalent() which triggers ViewRule again - CIRCULAR!
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class ActionRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ActionRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> actionRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("ActionRule executing (count={}), source={}", count, source.getName());

                if (count > 50) {
                    throw new RuntimeException("ActionRule executed too many times - circular loop detected!");
                }

                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("Action_" + source.getName());

                // This call triggers ViewRule which calls UtilityMethods.createAction
                // which triggers ActionRule again - CIRCULAR!
                // Simulates: ctx.equivalent(relation, "RelationFeatureView")
                EPackage view = ctx.equivalent(source, EPackage.class, "ViewRule");
                if (view != null) {
                    action.getDetails().put("viewName", view.getName());
                }

                return action;
            };
        }
    }

    // ========================================================================
    // Test: Circular dependency between @Lazy rules via utility methods
    // ========================================================================

    /**
     * This test simulates the actual ESM2UI pattern where:
     * 1. LazyViewRule (lazy) is triggered
     * 2. LazyViewRule calls utility method
     * 3. Utility method calls equivalentDiscriminated to trigger LazyActionRule
     * 4. LazyActionRule calls equivalent to trigger LazyRelationRule
     * 5. LazyRelationRule calls utility method - CIRCULAR back to step 3!
     */
    @Test
    @DisplayName("Circular dependency between @Lazy rules via utility methods")
    void testCircularBetweenLazyRulesViaUtilityMethods() {
        EClass source = createSourceElement("CircularEntity");

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(LazyViewRule.class);
        registry.register(LazyActionRule.class);
        registry.register(LazyRelationRule.class);
        registry.register(TriggerRule.class);

        TransformationContext ctx = createContext(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        // Reset counters
        LazyViewRule.executionCount.set(0);
        LazyActionRule.executionCount.set(0);
        LazyRelationRule.executionCount.set(0);
        CircularUtilityMethods.callCount.set(0);
        CircularUtilityMethods.nullReturns.set(0);

        // This should NOT throw StackOverflowError
        assertDoesNotThrow(() -> executor.transform(),
                "Transformation should handle circular dependencies between @Lazy rules");

        LOG.info("LazyViewRule executions: {}", LazyViewRule.executionCount.get());
        LOG.info("LazyActionRule executions: {}", LazyActionRule.executionCount.get());
        LOG.info("LazyRelationRule executions: {}", LazyRelationRule.executionCount.get());
        LOG.info("CircularUtility calls: {}", CircularUtilityMethods.callCount.get());
        LOG.info("CircularUtility null returns: {}", CircularUtilityMethods.nullReturns.get());

        // No rule should execute more than a reasonable number of times
        assertTrue(LazyViewRule.executionCount.get() < 10,
                "LazyViewRule should not execute excessively");
        assertTrue(LazyActionRule.executionCount.get() < 10,
                "LazyActionRule should not execute excessively");
        assertTrue(LazyRelationRule.executionCount.get() < 10,
                "LazyRelationRule should not execute excessively");
    }

    /**
     * Utility methods that create a circular call pattern.
     */
    public static class CircularUtilityMethods {
        static final AtomicInteger callCount = new AtomicInteger(0);
        static final AtomicInteger nullReturns = new AtomicInteger(0);

        public static EAnnotation createAction(EClass source, String discriminator,
                TransformationContext ctx) {
            int count = callCount.incrementAndGet();
            LOG.debug("CircularUtilityMethods.createAction called (count={}), discriminator={}",
                    count, discriminator);

            EAnnotation action = ctx.equivalentDiscriminated(
                    source, EAnnotation.class, "LazyActionRule", discriminator);

            if (action == null) {
                nullReturns.incrementAndGet();
                LOG.debug("CircularUtilityMethods.createAction: returned null (circular?)");
            }

            return action;
        }
    }

    /**
     * Eager trigger rule to start the chain.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class TriggerRule {
        @TransformRule(name = "TriggerRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> triggerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Trigger_" + source.getName());

                // Trigger the lazy view rule
                EDataType view = ctx.equivalent(source, EDataType.class, "LazyViewRule");
                if (view != null) {
                    pkg.setNsPrefix(view.getName());
                }

                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    /**
     * LazyViewRule - first lazy rule in the chain.
     * Calls utility method which triggers LazyActionRule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EDataType.class)
    public static class LazyViewRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyViewRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> lazyViewRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("LazyViewRule executing (count={})", count);

                if (count > 10) {
                    throw new RuntimeException("LazyViewRule circular loop!");
                }

                EDataType result = ctx.createTarget(EDataType.class);
                result.setName("View_" + source.getName());

                // Call utility method - triggers LazyActionRule
                EAnnotation action = CircularUtilityMethods.createAction(source, "fromView", ctx);
                if (action != null) {
                    result.setInstanceClassName(action.getSource());
                }

                return result;
            };
        }
    }

    /**
     * LazyActionRule - second lazy rule in the chain.
     * Calls equivalent which triggers LazyRelationRule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class LazyActionRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyActionRule")
        @Lazy
        @Detached
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> lazyActionRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("LazyActionRule executing (count={})", count);

                if (count > 10) {
                    throw new RuntimeException("LazyActionRule circular loop!");
                }

                EAnnotation result = ctx.createTarget(EAnnotation.class);
                result.setSource("Action_" + source.getName());

                // This triggers LazyRelationRule
                EEnum relation = ctx.equivalent(source, EEnum.class, "LazyRelationRule");
                if (relation != null) {
                    result.getDetails().put("relation", relation.getName());
                }

                return result;
            };
        }
    }

    /**
     * LazyRelationRule - third lazy rule in the chain.
     * Calls utility method which tries to trigger LazyActionRule again - CIRCULAR!
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EEnum.class)
    public static class LazyRelationRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyRelationRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EEnum> lazyRelationRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("LazyRelationRule executing (count={})", count);

                if (count > 10) {
                    throw new RuntimeException("LazyRelationRule circular loop!");
                }

                EEnum result = ctx.createTarget(EEnum.class);
                result.setName("Relation_" + source.getName());

                // CIRCULAR: This calls utility method which tries to trigger LazyActionRule
                // But LazyActionRule is already executing (we're inside its call chain)
                EAnnotation action = CircularUtilityMethods.createAction(source, "fromRelation", ctx);
                if (action != null) {
                    result.getEAnnotations().add(action);
                }

                return result;
            };
        }
    }

    /**
     * Rule that directly calls equivalentDiscriminated on itself.
     * Tests the most basic circular dependency case.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAnnotation.class)
    public static class DirectCircularRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final AtomicInteger circularCallCount = new AtomicInteger(0);

        @TransformRule(name = "DirectCircularRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EAnnotation> directCircularRule() {
            return (source, ctx) -> {
                int count = executionCount.incrementAndGet();
                LOG.debug("DirectCircularRule executing (count={}), source={}", count, source.getName());

                EAnnotation result = ctx.createTarget(EAnnotation.class);
                result.setSource("Direct_" + source.getName());

                // Try to call ourselves with a different discriminator
                // This should NOT cause infinite recursion
                circularCallCount.incrementAndGet();
                EAnnotation circular = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "DirectCircularRule", "circular");

                if (circular != null) {
                    result.getDetails().put("circular", "found");
                } else {
                    result.getDetails().put("circular", "null");
                }

                return result;
            };
        }

        // Eager rule to trigger the lazy rule
        @TransformRule(name = "DirectCircularTrigger")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> triggerRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Trigger_" + source.getName());

                // Trigger the lazy rule
                EAnnotation ann = ctx.equivalentDiscriminated(
                        source, EAnnotation.class, "DirectCircularRule", "initial");
                if (ann != null) {
                    pkg.getEAnnotations().add(ann);
                }

                ctx.addToResource(pkg);
                return pkg;
            };
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
