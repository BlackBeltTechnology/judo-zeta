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
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying Zeta's correct idempotent caching behavior.
 *
 * <p>These tests confirm that Zeta correctly implements idempotent caching where
 * the same (source, ruleName) pair always returns the same target instance,
 * regardless of calling context.</p>
 *
 * <p><b>Note</b>: ETL's compound XMI ID generation for @lazy @greedy rules from
 * different calling contexts is a <b>BUG</b> that violates idempotency. Zeta
 * intentionally does NOT replicate this buggy behavior.</p>
 *
 * <p>ETL Buggy Behavior:</p>
 * <ul>
 *   <li>Creates compound IDs like ((esm/A)/RuleName)_((esm/B)/CallerRule)</li>
 *   <li>Multiple targets for same source when called from different contexts</li>
 *   <li>Non-deterministic results depending on execution order</li>
 * </ul>
 *
 * <p>Zeta Correct Behavior:</p>
 * <ul>
 *   <li>Creates simple IDs like (esm/A)/RuleName</li>
 *   <li>Same target returned regardless of calling context</li>
 *   <li>Deterministic, thread-safe results</li>
 * </ul>
 *
 * @see ElementResolutionCache#getOrCreate - Idempotent caching implementation
 * @see TransformationContext#equivalent - Idempotent rule invocation
 */
@DisplayName("Idempotent Caching Tests (Verifies Correct Behavior)")
class IdempotentCachingTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotentCachingTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields
    static AtomicInteger targetRuleExecutionCount = new AtomicInteger(0);
    static AtomicInteger callerAExecutionCount = new AtomicInteger(0);
    static AtomicInteger callerBExecutionCount = new AtomicInteger(0);
    static List<EObject> createdTargets = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        // Reset tracking
        targetRuleExecutionCount.set(0);
        callerAExecutionCount.set(0);
        callerBExecutionCount.set(0);
        createdTargets.clear();

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

    // ==================== Test Cases ====================

    @Nested
    @DisplayName("Multi-Context Rule Invocation Tests")
    class MultiContextTests {

        /**
         * Verifies that the same source element accessed from different calling contexts
         * returns the SAME cached target (correct idempotent behavior).
         *
         * <p>This is the key test that demonstrates Zeta's correct behavior vs ETL's bug:</p>
         * <ul>
         *   <li>ETL (BUG): Would create TWO targets with compound IDs</li>
         *   <li>Zeta (CORRECT): Creates ONE target, returns same instance to both callers</li>
         * </ul>
         */
        @Test
        @DisplayName("Same source returns same target regardless of calling context")
        void testSameSourceSameTargetDifferentCallers() {
            // Setup: Create source element that will be transformed
            EClass operation = createEClass("TestOperation");

            // Register rules: Two callers that both request equivalent() for same source
            registry.register(MultiContextCallerRules.class);
            context.setTransformationRegistry(registry);

            // Execute transformation
            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify: TargetRule executed exactly ONCE (idempotent)
            assertEquals(1, targetRuleExecutionCount.get(),
                    "TargetRule should execute exactly once (idempotent caching)");

            // Verify: Both callers executed
            assertEquals(1, callerAExecutionCount.get(), "CallerA should execute once");
            assertEquals(1, callerBExecutionCount.get(), "CallerB should execute once");

            // Verify: Both callers got the SAME target instance
            assertEquals(2, createdTargets.size(),
                    "Both callers should have captured their target reference");
            assertSame(createdTargets.get(0), createdTargets.get(1),
                    "Both callers should receive the SAME target instance (ETL bug would give different instances)");

            log.info("SUCCESS: Idempotent caching verified - same target returned to both callers");
        }

        /**
         * Verifies that cache key is computed from (source, ruleName) only,
         * NOT including the calling context.
         */
        @Test
        @DisplayName("Cache key is (source, ruleName) only - context independent")
        void testCacheKeyIsContextIndependent() {
            EClass source = createEClass("SourceElement");

            // Create cache and verify key computation
            ElementResolutionCache cache = new ElementResolutionCache(true); // sequential mode

            // Simulate first call from "CallerA" context
            EPackage target1 = cache.getOrCreate(source, "TargetRule", () -> {
                EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
                pkg.setName("Target");
                return pkg;
            }, false);

            // Simulate second call from "CallerB" context (different calling context)
            EPackage target2 = cache.getOrCreate(source, "TargetRule", () -> {
                // This supplier should NOT be called (cache hit)
                fail("Supplier should not be called - should be cache hit");
                return null;
            }, false);

            // Verify: Same instance returned (identity check)
            assertSame(target1, target2,
                    "Same instance should be returned regardless of calling context");

            log.info("SUCCESS: Cache key is context-independent (source, ruleName) only");
        }
    }

    @Nested
    @DisplayName("XMI ID Format Tests")
    class XmiIdFormatTests {

        /**
         * Verifies that Zeta generates simple XMI IDs, NOT compound IDs.
         *
         * <p>ETL BUG: Generates compound IDs like ((esm/A)/RuleName)_((esm/B)/CallerRule)</p>
         * <p>Zeta CORRECT: Generates simple IDs like (source)/RuleName</p>
         */
        @Test
        @DisplayName("XMI IDs are simple format, not compound (ETL bug not replicated)")
        void testSimpleXmiIdFormat() {
            EClass source = createEClass("MyOperation");

            registry.register(SimpleIdTestRules.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Check all targets have simple IDs (not compound)
            for (EObject content : targetResource.getContents()) {
                String id = targetResource.getURIFragment(content);
                log.info("Target XMI ID: {}", id);

                // Compound IDs would contain patterns like ")_(" or have nested parentheses
                assertFalse(id.contains(")_("),
                        "XMI ID should NOT contain compound separator ')_(' - ETL bug not replicated");

                // Simple IDs should have at most one level of parentheses
                long openCount = id.chars().filter(ch -> ch == '(').count();
                long closeCount = id.chars().filter(ch -> ch == ')').count();
                assertTrue(openCount <= 1 && closeCount <= 1,
                        "XMI ID should be simple format with at most one level of parentheses");
            }

            log.info("SUCCESS: All XMI IDs are simple format (ETL compound ID bug not replicated)");
        }
    }

    @Nested
    @DisplayName("Idempotency Guarantee Tests")
    class IdempotencyGuaranteeTests {

        /**
         * Verifies that multiple calls to equivalent() for same (source, ruleName)
         * always return the same target instance.
         */
        @Test
        @DisplayName("Multiple equivalent() calls return same instance")
        void testMultipleEquivalentCallsReturnSameInstance() {
            EClass source = createEClass("TestSource");

            registry.register(IdempotencyTestRules.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // All captured targets should be the SAME instance
            assertFalse(createdTargets.isEmpty(), "Should have captured targets");

            EObject firstTarget = createdTargets.get(0);
            for (int i = 1; i < createdTargets.size(); i++) {
                assertSame(firstTarget, createdTargets.get(i),
                        "All equivalent() calls should return the SAME instance");
            }

            log.info("SUCCESS: All {} equivalent() calls returned same instance", createdTargets.size());
        }

        /**
         * Verifies that lazy rule execution is idempotent across multiple invocations.
         */
        @Test
        @DisplayName("Lazy rule execution is idempotent")
        void testLazyRuleIdempotency() {
            EClass source = createEClass("LazyTestSource");

            registry.register(LazyIdempotencyRules.class);
            context.setTransformationRegistry(registry);

            // The context's cache is already in sequential mode (default)
            TransformRuleDescriptor lazyRule = registry.getRuleByName("LazyTargetRule");
            assertNotNull(lazyRule, "LazyTargetRule should be registered");

            // First call - should execute rule
            EObject target1 = context.equivalent(source, "LazyTargetRule");
            assertNotNull(target1, "First call should return target");

            // Second call - should return cached target
            EObject target2 = context.equivalent(source, "LazyTargetRule");

            // Third call - should return same cached target
            EObject target3 = context.equivalent(source, "LazyTargetRule");

            // Verify all return same instance
            assertSame(target1, target2, "Second call should return same instance");
            assertSame(target1, target3, "Third call should return same instance");

            // Verify rule executed only once
            assertEquals(1, targetRuleExecutionCount.get(),
                    "Lazy rule should execute only once (idempotent)");

            log.info("SUCCESS: Lazy rule idempotency verified - executed once, cached for subsequent calls");
        }
    }

    // ==================== Test Rules ====================

    /**
     * Rules demonstrating multi-context idempotent caching.
     * Two callers (CallerA, CallerB) both request equivalent() for same source.
     * Correct behavior: TargetRule executes once, both callers get same target.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EPackage.class
    )
    public static class MultiContextCallerRules {

        /**
         * CallerA - requests equivalent(source, "TargetRule")
         */
        @TransformRule(name = "CallerA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> callerA() {
            return (source, ctx) -> {
                callerAExecutionCount.incrementAndGet();

                // Request target from TargetRule
                EPackage target = ctx.equivalent(source, "TargetRule");
                createdTargets.add(target);

                // Create own target for CallerA
                EPackage callerTarget = ctx.createTarget(EPackage.class);
                callerTarget.setName("CallerA_" + source.getName());
                return callerTarget;
            };
        }

        /**
         * CallerB - also requests equivalent(source, "TargetRule") for SAME source
         */
        @TransformRule(name = "CallerB")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EDataType> callerB() {
            return (source, ctx) -> {
                callerBExecutionCount.incrementAndGet();

                // Request target from TargetRule (same source as CallerA)
                EPackage target = ctx.equivalent(source, "TargetRule");
                createdTargets.add(target);

                // Create own target for CallerB
                EDataType callerTarget = ctx.createTarget(EDataType.class);
                callerTarget.setName("CallerB_" + source.getName());
                return callerTarget;
            };
        }

        /**
         * TargetRule - the rule that should execute only ONCE per source
         * ETL BUG: Would execute multiple times with compound IDs
         * Zeta CORRECT: Executes once, returns cached target
         */
        @TransformRule(name = "TargetRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> targetRule() {
            return (source, ctx) -> {
                targetRuleExecutionCount.incrementAndGet();

                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Rules for testing simple XMI ID format.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EPackage.class
    )
    public static class SimpleIdTestRules {

        @TransformRule(name = "SimpleIdRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> simpleIdRule() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Simple_" + source.getName());
                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Rules for testing idempotency guarantees.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EPackage.class
    )
    public static class IdempotencyTestRules {

        @TransformRule(name = "IdempotencyTestRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> idempotencyTest() {
            return (source, ctx) -> {
                // Call equivalent() multiple times for same target
                EPackage target1 = ctx.equivalent(source, "IdempotentTarget");
                EPackage target2 = ctx.equivalent(source, "IdempotentTarget");
                EPackage target3 = ctx.equivalent(source, "IdempotentTarget");

                createdTargets.add(target1);
                createdTargets.add(target2);
                createdTargets.add(target3);

                EPackage result = ctx.createTarget(EPackage.class);
                result.setName("IdempotencyTest_" + source.getName());
                return result;
            };
        }

        @TransformRule(name = "IdempotentTarget")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> idempotentTarget() {
            return (source, ctx) -> {
                targetRuleExecutionCount.incrementAndGet();
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("IdempotentTarget_" + source.getName());
                return target;
            };
        }
    }

    /**
     * Rules for testing lazy rule idempotency.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(
            source = EClass.class,
            target = EPackage.class
    )
    public static class LazyIdempotencyRules {

        @TransformRule(name = "LazyTargetRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> lazyTargetRule() {
            return (source, ctx) -> {
                targetRuleExecutionCount.incrementAndGet();
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("LazyTarget_" + source.getName());
                return target;
            };
        }
    }

    // ==================== Test Model Provider ====================

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                org.eclipse.emf.common.util.TreeIterator<EObject> iterator = resource.getAllContents();
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
