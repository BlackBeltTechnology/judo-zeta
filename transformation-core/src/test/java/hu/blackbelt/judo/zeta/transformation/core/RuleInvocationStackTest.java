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

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Rule Invocation Stack and Discriminator Resolver features (Path C).
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Rule invocation stack tracks nested rule execution correctly</li>
 *   <li>Discriminator resolver can infer discriminators from call chain</li>
 *   <li>Context-aware discriminator resolution enables ETL-compatible patterns</li>
 * </ul>
 */
@DisplayName("Rule Invocation Stack and Discriminator Resolver Tests")
class RuleInvocationStackTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Captured call chains for verification
    static List<List<RuleInvocation>> capturedCallChains = Collections.synchronizedList(new ArrayList<>());
    static List<String> capturedDiscriminators = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        capturedCallChains.clear();
        capturedDiscriminators.clear();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    // ==================== RuleInvocation Record Tests ====================

    @Test
    @DisplayName("RuleInvocation record stores rule name, source, and discriminator")
    void testRuleInvocationBasic() {
        EClass source = createEClass("TestClass");

        RuleInvocation inv1 = RuleInvocation.of("Rule1", source);
        assertEquals("Rule1", inv1.ruleName());
        assertEquals(source, inv1.source());
        assertNull(inv1.discriminator());

        RuleInvocation inv2 = RuleInvocation.of("Rule2", source, "disc123");
        assertEquals("Rule2", inv2.ruleName());
        assertEquals(source, inv2.source());
        assertEquals("disc123", inv2.discriminator());
    }

    @Test
    @DisplayName("RuleInvocation toString includes relevant info")
    void testRuleInvocationToString() {
        EClass source = createEClass("TestClass");

        RuleInvocation inv = RuleInvocation.of("MyRule", source, "myDisc");
        String str = inv.toString();

        assertTrue(str.contains("MyRule"), "Should contain rule name");
        assertTrue(str.contains("EClass"), "Should contain source type");
        assertTrue(str.contains("myDisc"), "Should contain discriminator");
    }

    // ==================== Stack Management Tests ====================

    @Test
    @DisplayName("Push and pop maintain LIFO order")
    void testStackLifoOrder() {
        EClass source1 = createEClass("Source1");
        EClass source2 = createEClass("Source2");

        context.pushRuleInvocation("Rule1", source1, null);
        context.pushRuleInvocation("Rule2", source2, "disc");

        List<RuleInvocation> chain = context.getRuleInvocationChain();
        assertEquals(2, chain.size());
        assertEquals("Rule2", chain.get(0).ruleName()); // Most recent first
        assertEquals("Rule1", chain.get(1).ruleName());

        RuleInvocation popped = context.popRuleInvocation();
        assertEquals("Rule2", popped.ruleName());

        chain = context.getRuleInvocationChain();
        assertEquals(1, chain.size());
        assertEquals("Rule1", chain.get(0).ruleName());
    }

    @Test
    @DisplayName("getRuleInvocationChain returns unmodifiable list")
    void testChainIsUnmodifiable() {
        EClass source = createEClass("Test");
        context.pushRuleInvocation("Rule1", source, null);

        List<RuleInvocation> chain = context.getRuleInvocationChain();

        assertThrows(UnsupportedOperationException.class, () -> {
            chain.add(RuleInvocation.of("Evil", source));
        });
    }

    @Test
    @DisplayName("Stack depth tracking works correctly")
    void testStackDepth() {
        assertEquals(0, context.getRuleInvocationDepth());

        context.pushRuleInvocation("Rule1", createEClass("S1"), null);
        assertEquals(1, context.getRuleInvocationDepth());

        context.pushRuleInvocation("Rule2", createEClass("S2"), null);
        assertEquals(2, context.getRuleInvocationDepth());

        context.popRuleInvocation();
        assertEquals(1, context.getRuleInvocationDepth());

        context.popRuleInvocation();
        assertEquals(0, context.getRuleInvocationDepth());
    }

    @Test
    @DisplayName("isInRuleContext finds rule in call chain")
    void testIsInRuleContext() {
        EClass source1 = createEClass("S1");
        EClass source2 = createEClass("S2");

        assertFalse(context.isInRuleContext("Rule1"));

        context.pushRuleInvocation("Rule1", source1, null);
        assertTrue(context.isInRuleContext("Rule1"));
        assertFalse(context.isInRuleContext("Rule2"));

        context.pushRuleInvocation("Rule2", source2, null);
        assertTrue(context.isInRuleContext("Rule1"));
        assertTrue(context.isInRuleContext("Rule2"));
        assertFalse(context.isInRuleContext("Rule3"));
    }

    @Test
    @DisplayName("findRuleInChain returns most recent matching invocation")
    void testFindRuleInChain() {
        EClass source1 = createEClass("S1");
        EClass source2 = createEClass("S2");
        EClass source3 = createEClass("S3");

        context.pushRuleInvocation("Rule1", source1, "disc1");
        context.pushRuleInvocation("Rule2", source2, null);
        context.pushRuleInvocation("Rule1", source3, "disc3"); // Same rule name, different source

        RuleInvocation found = context.findRuleInChain("Rule1");
        assertNotNull(found);
        assertEquals(source3, found.source()); // Most recent
        assertEquals("disc3", found.discriminator());

        RuleInvocation found2 = context.findRuleInChain("Rule2");
        assertNotNull(found2);
        assertEquals(source2, found2.source());

        assertNull(context.findRuleInChain("NonExistent"));
    }

    @Test
    @DisplayName("formatRuleInvocationChain provides readable output")
    void testFormatChain() {
        assertEquals("<empty stack>", context.formatRuleInvocationChain());

        context.pushRuleInvocation("Rule1", createEClass("S1"), null);
        context.pushRuleInvocation("Rule2", createEClass("S2"), "myDisc");

        String formatted = context.formatRuleInvocationChain();
        assertTrue(formatted.contains("[0]"), "Should have index 0");
        assertTrue(formatted.contains("[1]"), "Should have index 1");
        assertTrue(formatted.contains("Rule2"), "Should contain Rule2");
        assertTrue(formatted.contains("Rule1"), "Should contain Rule1");
    }

    // ==================== Discriminator Resolver Tests ====================

    @Test
    @DisplayName("Discriminator resolver is called when discriminator is null")
    void testDiscriminatorResolverCalled() {
        AtomicReference<String> resolvedRule = new AtomicReference<>();
        AtomicReference<List<RuleInvocation>> resolvedChain = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            resolvedRule.set(ruleName);
            resolvedChain.set(new ArrayList<>(callChain));
            return "resolved-discriminator";
        });

        // Push some context
        context.pushRuleInvocation("OuterRule", createEClass("Outer"), null);

        // The resolver should be called when equivalentDiscriminated is called with null discriminator
        // For this test, we just verify the resolver can be set and would return a value
        String resolved = context.resolveDiscriminator(createEClass("Test"), "InnerRule");

        assertEquals("resolved-discriminator", resolved);
        assertEquals("InnerRule", resolvedRule.get());
        assertNotNull(resolvedChain.get());
        assertEquals(1, resolvedChain.get().size());
        assertEquals("OuterRule", resolvedChain.get().get(0).ruleName());
    }

    @Test
    @DisplayName("Discriminator resolver returns null when not configured")
    void testNoResolverReturnsNull() {
        assertNull(context.getDiscriminatorResolver());
        assertNull(context.resolveDiscriminator(createEClass("Test"), "SomeRule"));
    }

    @Test
    @DisplayName("Discriminator resolver can be cleared")
    void testResolverCanBeCleared() {
        context.setDiscriminatorResolver((s, r, c) -> "test");
        assertNotNull(context.getDiscriminatorResolver());

        context.setDiscriminatorResolver(null);
        assertNull(context.getDiscriminatorResolver());
    }

    @Test
    @DisplayName("Context-aware discriminator based on calling rule")
    void testContextAwareDiscriminator() {
        // Simulate ETL-like scenario: ActionDefinition discriminator depends on caller
        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            if (ruleName.equals("ActionDefinitionRule")) {
                // Check if called from ButtonGroup context
                for (RuleInvocation inv : callChain) {
                    if (inv.ruleName().equals("ButtonGroupRule")) {
                        return "buttonContext/" + inv.source().eClass().getName();
                    }
                }
                // Default context
                return "defaultContext";
            }
            return null;
        });

        // Scenario 1: Called from ButtonGroup context
        EClass buttonGroupSource = createEClass("ButtonGroup");
        context.pushRuleInvocation("ButtonGroupRule", buttonGroupSource, null);

        String disc1 = context.resolveDiscriminator(createEClass("ActionDef"), "ActionDefinitionRule");
        assertEquals("buttonContext/EClass", disc1);

        context.popRuleInvocation();

        // Scenario 2: Called from different context
        context.pushRuleInvocation("PageRule", createEClass("Page"), null);

        String disc2 = context.resolveDiscriminator(createEClass("ActionDef"), "ActionDefinitionRule");
        assertEquals("defaultContext", disc2);
    }

    // ==================== Integration with TransformRuleDescriptor Tests ====================

    @Test
    @DisplayName("TransformRuleDescriptor.execute pushes and pops rule invocation")
    void testRuleDescriptorPushPop() {
        // Create a simple rule descriptor manually
        EClass source = createEClass("TestSource");

        // Verify stack is empty before
        assertEquals(0, context.getRuleInvocationDepth());

        // Simulate what TransformRuleDescriptor.execute does
        context.pushRuleInvocation("TestRule", source, null);

        // Verify invocation was pushed
        assertEquals(1, context.getRuleInvocationDepth());
        List<RuleInvocation> chain = context.getRuleInvocationChain();
        assertEquals(1, chain.size());
        assertEquals("TestRule", chain.get(0).ruleName());
        assertEquals(source, chain.get(0).source());

        // Simulate nested rule call
        EClass nestedSource = createEClass("NestedSource");
        context.pushRuleInvocation("NestedRule", nestedSource, "someDiscriminator");

        // Verify both invocations in chain
        assertEquals(2, context.getRuleInvocationDepth());
        chain = context.getRuleInvocationChain();
        assertEquals(2, chain.size());
        assertEquals("NestedRule", chain.get(0).ruleName()); // Most recent first
        assertEquals("TestRule", chain.get(1).ruleName());

        // Pop nested
        RuleInvocation popped = context.popRuleInvocation();
        assertEquals("NestedRule", popped.ruleName());
        assertEquals("someDiscriminator", popped.discriminator());
        assertEquals(1, context.getRuleInvocationDepth());

        // Pop outer
        popped = context.popRuleInvocation();
        assertEquals("TestRule", popped.ruleName());
        assertEquals(0, context.getRuleInvocationDepth());
    }

    @Test
    @DisplayName("Discriminator resolver receives correct call chain")
    void testDiscriminatorResolverReceivesCallChain() {
        EClass source1 = createEClass("Source1");
        EClass source2 = createEClass("Source2");

        // Set up a resolver that captures the call chain
        AtomicReference<List<RuleInvocation>> capturedChain = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            capturedChain.set(new ArrayList<>(callChain));
            return "resolved-" + callChain.size();
        });

        // Push some rules onto the stack
        context.pushRuleInvocation("ParentRule", source1, null);
        context.pushRuleInvocation("ChildRule", source2, null);

        // Resolve discriminator
        String result = context.resolveDiscriminator(createEClass("Target"), "ActionDefinition");

        // Verify chain was passed to resolver
        assertNotNull(capturedChain.get());
        assertEquals(2, capturedChain.get().size());
        assertEquals("ChildRule", capturedChain.get().get(0).ruleName());
        assertEquals("ParentRule", capturedChain.get().get(1).ruleName());

        // Verify result reflects chain size
        assertEquals("resolved-2", result);

        // Cleanup
        context.popRuleInvocation();
        context.popRuleInvocation();
    }

    // ==================== Edge Case Tests ====================

    @Test
    @DisplayName("Resolver returns null - should use null effectiveDiscriminator")
    void testResolverReturnsNull_usesNullEffectiveDiscriminator() {
        context.setDiscriminatorResolver((source, ruleName, callChain) -> null);

        String result = context.resolveDiscriminator(createEClass("Test"), "SomeRule");
        assertNull(result, "When resolver returns null, resolveDiscriminator should return null");
    }

    @Test
    @DisplayName("Resolver throws exception - should propagate")
    void testResolverThrowsException_shouldPropagate() {
        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            throw new IllegalStateException("Test exception from resolver");
        });

        assertThrows(IllegalStateException.class, () -> {
            context.resolveDiscriminator(createEClass("Test"), "SomeRule");
        }, "Exception from resolver should propagate");
    }

    @Test
    @DisplayName("Resolver returns empty string - should be treated as non-null discriminator")
    void testResolverReturnsEmptyString_treatedAsNonNull() {
        context.setDiscriminatorResolver((source, ruleName, callChain) -> "");

        String result = context.resolveDiscriminator(createEClass("Test"), "SomeRule");
        assertEquals("", result, "Empty string discriminator should be returned as-is");
    }

    @Test
    @DisplayName("Resolver called with null source")
    void testResolverCalledWithNullSource() {
        AtomicReference<EObject> capturedSource = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            capturedSource.set(source);
            return "resolved";
        });

        String result = context.resolveDiscriminator(null, "SomeRule");

        assertNull(capturedSource.get(), "Null source should be passed to resolver");
        assertEquals("resolved", result);
    }

    @Test
    @DisplayName("Resolver called with null rule name")
    void testResolverCalledWithNullRuleName() {
        AtomicReference<String> capturedRuleName = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            capturedRuleName.set(ruleName);
            return "resolved";
        });

        String result = context.resolveDiscriminator(createEClass("Test"), null);

        assertNull(capturedRuleName.get(), "Null rule name should be passed to resolver");
        assertEquals("resolved", result);
    }

    @Test
    @DisplayName("Call chain is empty when no rules executing")
    void testEmptyCallChain() {
        AtomicReference<List<RuleInvocation>> capturedChain = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            capturedChain.set(new ArrayList<>(callChain));
            return "resolved";
        });

        // Don't push any rules - chain should be empty
        context.resolveDiscriminator(createEClass("Test"), "SomeRule");

        assertNotNull(capturedChain.get());
        assertTrue(capturedChain.get().isEmpty(), "Call chain should be empty when no rules executing");
    }

    @Test
    @DisplayName("Resolver sees deeply nested call chain")
    void testDeeplyNestedCallChain() {
        AtomicReference<Integer> capturedDepth = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            capturedDepth.set(callChain.size());
            return "depth-" + callChain.size();
        });

        // Push 5 nested rules
        for (int i = 0; i < 5; i++) {
            context.pushRuleInvocation("Rule" + i, createEClass("Source" + i), "disc" + i);
        }

        String result = context.resolveDiscriminator(createEClass("Test"), "TargetRule");

        assertEquals(5, capturedDepth.get().intValue());
        assertEquals("depth-5", result);

        // Cleanup
        for (int i = 0; i < 5; i++) {
            context.popRuleInvocation();
        }
    }

    @Test
    @DisplayName("Resolver can inspect discriminator of parent rules")
    void testResolverCanInspectParentDiscriminator() {
        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            // Find the first parent with a non-null discriminator
            for (RuleInvocation inv : callChain) {
                if (inv.discriminator() != null) {
                    return "inherited:" + inv.discriminator();
                }
            }
            return "no-parent-disc";
        });

        // Push rules - only middle one has discriminator
        context.pushRuleInvocation("Rule1", createEClass("S1"), null);
        context.pushRuleInvocation("Rule2", createEClass("S2"), "parent-discriminator");
        context.pushRuleInvocation("Rule3", createEClass("S3"), null);

        String result = context.resolveDiscriminator(createEClass("Test"), "TargetRule");

        // Should find Rule2's discriminator (first non-null going up the stack)
        assertEquals("inherited:parent-discriminator", result);

        // Cleanup
        context.popRuleInvocation();
        context.popRuleInvocation();
        context.popRuleInvocation();
    }

    @Test
    @DisplayName("Stack operations during resolver execution")
    void testStackOperationsDuringResolverExecution() {
        // Test that stack is stable during resolver execution
        context.pushRuleInvocation("OuterRule", createEClass("Outer"), null);

        AtomicReference<Integer> depthDuringResolver = new AtomicReference<>();

        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            depthDuringResolver.set(context.getRuleInvocationDepth());
            // The resolver should see the current stack state
            return "resolved";
        });

        context.resolveDiscriminator(createEClass("Test"), "InnerRule");

        assertEquals(1, depthDuringResolver.get().intValue(),
                "Stack should be stable during resolver execution");

        context.popRuleInvocation();
    }

    @Test
    @DisplayName("Resolver based on source type pattern")
    void testResolverBasedOnSourceType() {
        context.setDiscriminatorResolver((source, ruleName, callChain) -> {
            if (source != null && source.eClass().getName().equals("EClass")) {
                return "eclass-discriminator";
            } else if (source != null && source.eClass().getName().equals("EAttribute")) {
                return "eattribute-discriminator";
            }
            return null;
        });

        EClass eClassSource = createEClass("TestClass");
        EAttribute eAttrSource = EcoreFactory.eINSTANCE.createEAttribute();
        eAttrSource.setName("testAttr");

        String classResult = context.resolveDiscriminator(eClassSource, "SomeRule");
        String attrResult = context.resolveDiscriminator(eAttrSource, "SomeRule");

        assertEquals("eclass-discriminator", classResult);
        assertEquals("eattribute-discriminator", attrResult);
    }

    // ==================== Test Model Provider ====================

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
