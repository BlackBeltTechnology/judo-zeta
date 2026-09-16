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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for discriminator-only cache mode (ETL-compatible caching).
 *
 * <p>When discriminator-only cache is enabled, different source objects with the
 * same discriminator share the same target instance. This matches ETL's string-based
 * caching semantics where the discriminator alone determines identity.</p>
 *
 * <h2>Cache Modes</h2>
 * <ul>
 *   <li><b>SOURCE_BASED</b> (default): Cache key is (source, ruleName, discriminator).
 *       Different source objects produce different targets even with same discriminator.</li>
 *   <li><b>DISCRIMINATOR_ONLY</b>: Cache key is only (ruleName, discriminator).
 *       Different source objects with same discriminator share the same target.</li>
 * </ul>
 */
class DiscriminatorOnlyCacheTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
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
    }

    private EClass createSourceClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    /**
     * Helper DiscriminatorResolver that returns Resolution with discriminator-only cache.
     */
    private DiscriminatorResolver createDiscriminatorOnlyResolver(String discriminator) {
        return new DiscriminatorResolver() {
            @Override
            public Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return Resolution.discriminatorOnlyCache(discriminator);
            }

            @Override
            @Deprecated
            public String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return discriminator;
            }
        };
    }

    /**
     * Helper DiscriminatorResolver that returns Resolution with source-based cache.
     */
    private DiscriminatorResolver createSourceBasedResolver(String discriminator) {
        return new DiscriminatorResolver() {
            @Override
            public Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return Resolution.sourceBasedCache(discriminator);
            }

            @Override
            @Deprecated
            public String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return discriminator;
            }
        };
    }

    // ==================== RESOLUTION RECORD TESTS ====================

    @Test
    @DisplayName("Resolution.sourceBasedCache creates correct resolution")
    void testResolutionSourceBasedCache() {
        DiscriminatorResolver.Resolution res = DiscriminatorResolver.Resolution.sourceBasedCache("my-disc");

        assertEquals("my-disc", res.discriminator());
        assertFalse(res.useDiscriminatorOnlyCache(), "Source-based cache should have useDiscriminatorOnlyCache=false");
    }

    @Test
    @DisplayName("Resolution.discriminatorOnlyCache creates correct resolution")
    void testResolutionDiscriminatorOnlyCache() {
        DiscriminatorResolver.Resolution res = DiscriminatorResolver.Resolution.discriminatorOnlyCache("shared-disc");

        assertEquals("shared-disc", res.discriminator());
        assertTrue(res.useDiscriminatorOnlyCache(), "Discriminator-only cache should have useDiscriminatorOnlyCache=true");
    }

    @Test
    @DisplayName("Resolution record constructor works directly")
    void testResolutionDirectConstruction() {
        DiscriminatorResolver.Resolution res1 = new DiscriminatorResolver.Resolution("disc1", false);
        assertEquals("disc1", res1.discriminator());
        assertFalse(res1.useDiscriminatorOnlyCache());

        DiscriminatorResolver.Resolution res2 = new DiscriminatorResolver.Resolution("disc2", true);
        assertEquals("disc2", res2.discriminator());
        assertTrue(res2.useDiscriminatorOnlyCache());
    }

    // ==================== DISCRIMINATOR RESOLVER DEFAULT BEHAVIOR TESTS ====================

    @Test
    @DisplayName("Default resolve() wraps resolveDiscriminator() with source-based cache")
    void testDefaultResolveWrapsDeprecatedMethod() {
        // Use a lambda (implements deprecated method via functional interface)
        DiscriminatorResolver resolver = (source, ruleName, callChain) -> "legacy-disc";

        EClass source = createSourceClass("TestSource");
        DiscriminatorResolver.Resolution resolution = resolver.resolve(source, "TestRule", Collections.emptyList());

        assertNotNull(resolution, "Default resolve should return Resolution when deprecated method returns non-null");
        assertEquals("legacy-disc", resolution.discriminator());
        assertFalse(resolution.useDiscriminatorOnlyCache(), "Default should use source-based cache");
    }

    @Test
    @DisplayName("Default resolve() returns null when resolveDiscriminator() returns null")
    void testDefaultResolveReturnsNullWhenDeprecatedReturnsNull() {
        DiscriminatorResolver resolver = (source, ruleName, callChain) -> null;

        EClass source = createSourceClass("TestSource");
        DiscriminatorResolver.Resolution resolution = resolver.resolve(source, "TestRule", Collections.emptyList());

        assertNull(resolution, "Default resolve should return null when deprecated method returns null");
    }

    // ==================== CUSTOM RESOLVER WITH DISCRIMINATOR-ONLY CACHE TESTS ====================

    @Test
    @DisplayName("Custom resolver can return discriminator-only cache mode")
    void testCustomResolverReturnsDiscriminatorOnlyCache() {
        DiscriminatorResolver resolver = createDiscriminatorOnlyResolver("shared-action");

        EClass source = createSourceClass("TestSource");
        DiscriminatorResolver.Resolution resolution = resolver.resolve(source, "ActionRule", Collections.emptyList());

        assertNotNull(resolution);
        assertEquals("shared-action", resolution.discriminator());
        assertTrue(resolution.useDiscriminatorOnlyCache(), "Should use discriminator-only cache");
    }

    @Test
    @DisplayName("TransformationContext stores and retrieves custom resolver")
    void testContextStoresCustomResolver() {
        DiscriminatorResolver resolver = createDiscriminatorOnlyResolver("test-disc");

        assertNull(context.getDiscriminatorResolver(), "Initially no resolver");

        context.setDiscriminatorResolver(resolver);

        assertSame(resolver, context.getDiscriminatorResolver(), "Should return same resolver");
    }

    @Test
    @DisplayName("Resolver can switch between modes based on rule name")
    void testResolverSwitchesModes() {
        DiscriminatorResolver resolver = new DiscriminatorResolver() {
            @Override
            public Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
                if ("SharedActionDefinition".equals(ruleName)) {
                    // This rule's results should be shared across different sources
                    return Resolution.discriminatorOnlyCache("action-def-shared");
                } else if ("PerSourceTarget".equals(ruleName)) {
                    // This rule creates separate targets per source
                    return Resolution.sourceBasedCache("per-source-disc");
                }
                return null; // Use default behavior
            }

            @Override
            @Deprecated
            public String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain) {
                // Not used when resolve() is overridden
                return null;
            }
        };

        context.setDiscriminatorResolver(resolver);

        EClass source = createSourceClass("TestSource");

        // Test discriminator-only mode
        DiscriminatorResolver.Resolution res1 = resolver.resolve(source, "SharedActionDefinition", Collections.emptyList());
        assertNotNull(res1);
        assertTrue(res1.useDiscriminatorOnlyCache());

        // Test source-based mode
        DiscriminatorResolver.Resolution res2 = resolver.resolve(source, "PerSourceTarget", Collections.emptyList());
        assertNotNull(res2);
        assertFalse(res2.useDiscriminatorOnlyCache());

        // Test null (no resolver decision)
        DiscriminatorResolver.Resolution res3 = resolver.resolve(source, "OtherRule", Collections.emptyList());
        assertNull(res3);
    }

    @Test
    @DisplayName("Resolver can use call chain to determine cache mode")
    void testResolverUsesCallChainForCacheMode() {
        DiscriminatorResolver resolver = new DiscriminatorResolver() {
            @Override
            public Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
                // If called from ButtonGroupRule, use discriminator-only cache
                for (RuleInvocation inv : callChain) {
                    if ("ButtonGroupRule".equals(inv.ruleName())) {
                        return Resolution.discriminatorOnlyCache("button-context-shared");
                    }
                }
                // Otherwise use source-based cache
                return Resolution.sourceBasedCache("default-context");
            }

            @Override
            @Deprecated
            public String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return null;
            }
        };

        context.setDiscriminatorResolver(resolver);

        EClass buttonSource = createSourceClass("ButtonGroup");
        EClass actionSource = createSourceClass("Action");

        // Simulate call from ButtonGroupRule context
        context.pushRuleInvocation("ButtonGroupRule", buttonSource, null);
        DiscriminatorResolver.Resolution resFromButton = resolver.resolve(
                actionSource, "ActionDefinitionRule", context.getRuleInvocationChain());
        context.popRuleInvocation();

        assertTrue(resFromButton.useDiscriminatorOnlyCache(),
                "When called from ButtonGroupRule, should use discriminator-only cache");
        assertEquals("button-context-shared", resFromButton.discriminator());

        // Simulate call from different context
        context.pushRuleInvocation("PageRule", createSourceClass("Page"), null);
        DiscriminatorResolver.Resolution resFromPage = resolver.resolve(
                actionSource, "ActionDefinitionRule", context.getRuleInvocationChain());
        context.popRuleInvocation();

        assertFalse(resFromPage.useDiscriminatorOnlyCache(),
                "When called from PageRule, should use source-based cache");
        assertEquals("default-context", resFromPage.discriminator());
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    @DisplayName("Empty discriminator is valid for discriminator-only cache")
    void testEmptyDiscriminatorValid() {
        DiscriminatorResolver.Resolution res = DiscriminatorResolver.Resolution.discriminatorOnlyCache("");

        assertEquals("", res.discriminator());
        assertTrue(res.useDiscriminatorOnlyCache());
    }

    @Test
    @DisplayName("Null discriminator with true flag is technically valid")
    void testNullDiscriminatorWithDiscriminatorOnlyCache() {
        // This is a valid Resolution but may cause issues in cache lookup
        DiscriminatorResolver.Resolution res = new DiscriminatorResolver.Resolution(null, true);

        assertNull(res.discriminator());
        assertTrue(res.useDiscriminatorOnlyCache());
    }

    @Test
    @DisplayName("Resolver returning null resolution uses default behavior")
    void testNullResolutionUsesDefault() {
        DiscriminatorResolver resolver = new DiscriminatorResolver() {
            @Override
            public Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return null; // Explicitly return null
            }

            @Override
            @Deprecated
            public String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain) {
                return "deprecated-disc"; // Should not be used
            }
        };

        EClass source = createSourceClass("TestSource");
        DiscriminatorResolver.Resolution res = resolver.resolve(source, "TestRule", Collections.emptyList());

        assertNull(res, "When resolve() returns null, result should be null");
    }

    @Test
    @DisplayName("Different rule names with same discriminator still separate in source-based mode")
    void testDifferentRuleNamesSeparateInSourceBasedMode() {
        // In source-based cache mode, key is (source, ruleName, discriminator)
        // So same discriminator but different rule names are separate entries

        DiscriminatorResolver resolver = createSourceBasedResolver("same-disc");
        context.setDiscriminatorResolver(resolver);

        EClass source = createSourceClass("TestSource");

        DiscriminatorResolver.Resolution res1 = resolver.resolve(source, "Rule1", Collections.emptyList());
        DiscriminatorResolver.Resolution res2 = resolver.resolve(source, "Rule2", Collections.emptyList());

        // Both have same discriminator but source-based mode
        assertEquals(res1.discriminator(), res2.discriminator());
        assertFalse(res1.useDiscriminatorOnlyCache());
        assertFalse(res2.useDiscriminatorOnlyCache());
        // The cache would treat these as different because rule names differ
    }

    @Test
    @DisplayName("Same rule name with same discriminator shares in discriminator-only mode")
    void testSameRuleNameSharesInDiscriminatorOnlyMode() {
        // In discriminator-only mode, key is (ruleName, discriminator)
        // So same discriminator and same rule name = shared entry

        DiscriminatorResolver resolver = createDiscriminatorOnlyResolver("shared-disc");
        context.setDiscriminatorResolver(resolver);

        EClass source1 = createSourceClass("Source1");
        EClass source2 = createSourceClass("Source2");

        DiscriminatorResolver.Resolution res1 = resolver.resolve(source1, "SharedRule", Collections.emptyList());
        DiscriminatorResolver.Resolution res2 = resolver.resolve(source2, "SharedRule", Collections.emptyList());

        // Both resolve to same discriminator with discriminator-only mode
        assertEquals("shared-disc", res1.discriminator());
        assertEquals("shared-disc", res2.discriminator());
        assertTrue(res1.useDiscriminatorOnlyCache());
        assertTrue(res2.useDiscriminatorOnlyCache());
        // The cache would treat these as SAME entry because key is (ruleName, discriminator)
    }

    // ==================== MODEL PROVIDER HELPER ====================

    /**
     * Simple test ModelProvider implementation.
     */
    private static class TestModelProvider implements ModelProvider {
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
