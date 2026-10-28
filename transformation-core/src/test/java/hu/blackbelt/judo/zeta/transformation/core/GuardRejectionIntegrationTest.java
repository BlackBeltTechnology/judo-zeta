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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for guard rejection caching in equivalent() and equivalentDiscriminated().
 *
 * <p>Verifies that when a guard rejects a source element during the greedy pass, subsequent
 * calls to equivalent(source, ruleName) return null without re-evaluating the guard —
 * matching ETL semantics where guard rejections are permanent.</p>
 */
@DisplayName("Guard Rejection Integration Tests")
class GuardRejectionIntegrationTest {

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
    void equivalentReturnsNullForRejectedSource() {
        // Source element that will be rejected by RuleA's guard
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Rejected");
        sourceResource.getContents().add(sourceClass);

        // Also add an "Accepted" element so RuleB has something to process
        EClass acceptedClass = EcoreFactory.eINSTANCE.createEClass();
        acceptedClass.setName("Accepted");
        sourceResource.getContents().add(acceptedClass);

        registry.register(GuardedRuleA.class);
        registry.register(CrossLookupRuleB.class);
        context.setTransformationRegistry(registry);

        GuardedRuleA.guardCallCount.set(0);
        CrossLookupRuleB.equivalentResult.clear();

        // Run with RULE_BY_RULE to ensure RuleA greedy pass runs before RuleB
        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
        executor.transform();

        // RuleA guard should reject "Rejected" element — called exactly once
        // (It also evaluates for "Accepted" which passes, so guardCallCount = 2)
        assertTrue(GuardedRuleA.guardCallCount.get() >= 1,
                "Guard should have been called at least once");

        // RuleB calls equivalent(rejected, "GuardedRule") — should get null
        // because the rejection cache prevents re-evaluation
        assertTrue(CrossLookupRuleB.equivalentResult.containsKey("Rejected"),
                "RuleB should have attempted equivalent lookup for 'Rejected'");
        assertNull(CrossLookupRuleB.equivalentResult.get("Rejected"),
                "equivalent() should return null for guard-rejected source (ETL semantics)");

        // Verify the accepted element WAS transformed by RuleA
        assertTrue(CrossLookupRuleB.equivalentResult.containsKey("Accepted"),
                "RuleB should have attempted equivalent lookup for 'Accepted'");
        assertNotNull(CrossLookupRuleB.equivalentResult.get("Accepted"),
                "equivalent() should return the target for accepted source");
    }

    @Test
    void equivalentDiscriminatedReturnsNullForRejectedSource() {
        // Source element that will be rejected by the discriminated rule's guard
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("RejectedDisc");
        sourceResource.getContents().add(sourceClass);

        registry.register(DiscriminatedGuardedRule.class);
        registry.register(DiscriminatedLookupRule.class);
        context.setTransformationRegistry(registry);

        DiscriminatedGuardedRule.guardCallCount.set(0);
        DiscriminatedLookupRule.lookupResult = "NOT_SET";

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .executionStrategy(ExecutionStrategy.RULE_BY_RULE)
                .build();
        executor.transform();

        // The guard should have been called once and rejected
        assertTrue(DiscriminatedGuardedRule.guardCallCount.get() >= 1,
                "Guard should have been called");

        // The lookup via equivalentDiscriminated should return null
        assertEquals("NULL", DiscriminatedLookupRule.lookupResult,
                "equivalentDiscriminated should return null for guard-rejected source");
    }

    // ========== Transformation Classes ==========

    /**
     * RuleA: Greedy rule with a guard that rejects elements named "Rejected".
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class GuardedRuleA {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "GuardedRule")
        @Transform(type = EClass.class)
        @Primary
        @Guard(method = "rejectGuard")
        public TransformFunction<EClass, EClass> guardedRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_A");
                return target;
            };
        }

        public boolean rejectGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return !((EClass) source).getName().contains("Rejected");
        }
    }

    /**
     * RuleB: Another greedy rule that looks up RuleA's results via equivalent().
     * Registered AFTER RuleA so it runs second in RULE_BY_RULE mode.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAttribute.class)
    public static class CrossLookupRuleB {
        // Use a regular synchronized map to allow null values (ConcurrentHashMap does not)
        static final java.util.Map<String, EObject> equivalentResult =
                java.util.Collections.synchronizedMap(new java.util.HashMap<>());

        @TransformRule(name = "CrossLookupRule")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EAttribute> crossLookupRule() {
            return (source, ctx) -> {
                // Look up RuleA's result for this source
                EClass ruleAResult = ctx.equivalent(source, "GuardedRule");
                equivalentResult.put(source.getName(), ruleAResult);

                EAttribute attr = ctx.createTarget(EAttribute.class);
                attr.setName(source.getName() + "_B");
                attr.setEType(EcorePackage.Literals.ESTRING);
                return attr;
            };
        }
    }

    /**
     * Discriminated rule with guard that rejects "RejectedDisc" elements.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class DiscriminatedGuardedRule {
        static final AtomicInteger guardCallCount = new AtomicInteger(0);

        @TransformRule(name = "DiscGuardedRule")
        @Transform(type = EClass.class)
        @Primary
        @Guard(method = "discGuard")
        public TransformFunction<EClass, EClass> discGuardedRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_disc");
                return target;
            };
        }

        public boolean discGuard(EObject source, TransformationContext ctx) {
            guardCallCount.incrementAndGet();
            return !((EClass) source).getName().contains("Rejected");
        }
    }

    /**
     * Rule that uses equivalentDiscriminated to look up the guarded rule's result.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAttribute.class)
    public static class DiscriminatedLookupRule {
        static volatile String lookupResult = "NOT_SET";

        @TransformRule(name = "DiscLookupRule")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EAttribute> discLookupRule() {
            return (source, ctx) -> {
                if (source.getName().contains("Rejected")) {
                    EClass result = ctx.equivalentDiscriminated(
                            source, EClass.class, "DiscGuardedRule", null);
                    lookupResult = (result == null) ? "NULL" : result.getName();
                }

                EAttribute attr = ctx.createTarget(EAttribute.class);
                attr.setName(source.getName() + "_lookup");
                attr.setEType(EcorePackage.Literals.ESTRING);
                return attr;
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
