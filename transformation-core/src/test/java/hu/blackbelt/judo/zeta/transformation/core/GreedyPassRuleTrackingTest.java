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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for currentGreedyPassRuleName tracking during RULE_BY_RULE execution.
 *
 * <p>Verifies that during the greedy pass of a rule, the context correctly tracks
 * which rule is currently iterating (via {@link TransformationContext#getCurrentGreedyPassRuleName()}).
 * This mechanism enables ETL-compatible semantics where same-rule lookups during the
 * greedy pass only return previously cached results.</p>
 */
@DisplayName("Greedy Pass Rule Tracking Tests")
class GreedyPassRuleTrackingTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
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

    @Test
    void currentGreedyPassRuleNameIsSetDuringRuleByRuleExecution() {
        // Create two source elements
        EClass source1 = EcoreFactory.eINSTANCE.createEClass();
        source1.setName("First");
        sourceResource.getContents().add(source1);

        EClass source2 = EcoreFactory.eINSTANCE.createEClass();
        source2.setName("Second");
        sourceResource.getContents().add(source2);

        registry.register(TrackingTransformation.class);
        context.setTransformationRegistry(registry);

        TrackingTransformation.observedGreedyRuleNames.clear();

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
        executor.transform();

        // During RULE_BY_RULE, each rule execution should see the current rule name
        assertFalse(TrackingTransformation.observedGreedyRuleNames.isEmpty(),
                "Should have observed greedy pass rule name during execution");

        // All observations should be "TrackingRule" (since that's the rule running)
        for (String observed : TrackingTransformation.observedGreedyRuleNames) {
            assertEquals("TrackingRule", observed,
                    "getCurrentGreedyPassRuleName should return the executing rule's name");
        }

        // After transformation, the greedy pass rule name should be cleared
        assertNull(context.getCurrentGreedyPassRuleName(),
                "currentGreedyPassRuleName should be null after transformation completes");
    }

    @Test
    void currentGreedyPassRuleNameIsNullInElementByElementMode() {
        EClass source1 = EcoreFactory.eINSTANCE.createEClass();
        source1.setName("Test");
        sourceResource.getContents().add(source1);

        registry.register(TrackingTransformation.class);
        context.setTransformationRegistry(registry);

        TrackingTransformation.observedGreedyRuleNames.clear();

        // ELEMENT_BY_ELEMENT does not use the greedy pass tracking
        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .executionStrategy(ExecutionStrategy.ELEMENT_BY_ELEMENT)
                .build();
        executor.transform();

        // In ELEMENT_BY_ELEMENT mode, greedy pass rule name should be null
        for (String observed : TrackingTransformation.observedGreedyRuleNames) {
            assertNull(observed,
                    "getCurrentGreedyPassRuleName should be null in ELEMENT_BY_ELEMENT mode");
        }
    }

    // ========== Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class TrackingTransformation {
        static final List<String> observedGreedyRuleNames =
                Collections.synchronizedList(new ArrayList<>());

        @TransformRule(name = "TrackingRule")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EClass> trackingRule() {
            return (source, ctx) -> {
                // Capture the current greedy pass rule name during execution
                observedGreedyRuleNames.add(ctx.getCurrentGreedyPassRuleName());

                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_tracked");
                return target;
            };
        }
    }

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
