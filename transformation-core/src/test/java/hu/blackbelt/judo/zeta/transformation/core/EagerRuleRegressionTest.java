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

import org.eclipse.emf.ecore.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for eager rule execution with atomic cache operations.
 *
 * <p>These tests verify that the getOrCreate() atomic cache operation:
 * <ul>
 *   <li>Produces same results as previous separate get+create pattern</li>
 *   <li>Guard evaluation works correctly inside locked section</li>
 *   <li>Existing rule behavior is unchanged</li>
 *   <li>Sequential mode performance is not degraded</li>
 * </ul>
 */
class EagerRuleRegressionTest {

    private ElementResolutionCache cache;

    @BeforeEach
    void setUp() {
        cache = new ElementResolutionCache();
    }

    // ==================== Sequential Mode Unchanged Tests ====================

    @Nested
    class SequentialModeTests {

        @Test
        void testSequentialAccessProducesSameResultAsOldPattern() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Source");

            // Simulate old pattern: check then execute
            EObject oldResult = cache.getByRule(source, "TestRule");
            if (oldResult == null) {
                EClass target = EcoreFactory.eINSTANCE.createEClass();
                target.setName("Target");
                cache.addMapping(source, "TestRule", target, true);
                oldResult = target;
            }

            // Clear and retry with new pattern
            cache.clear();

            EObject newResult = cache.getOrCreate(source, "TestRule", () -> {
                EClass target = EcoreFactory.eINSTANCE.createEClass();
                target.setName("Target");
                return target;
            }, true);

            // Both approaches should produce valid results
            assertNotNull(oldResult);
            assertNotNull(newResult);
            assertEquals(((EClass) oldResult).getName(), ((EClass) newResult).getName());
        }

        @Test
        void testSequentialMultipleRulesOnSameSource() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Source");

            // Multiple rules on same source
            EObject target1 = cache.getOrCreate(source, "Rule1", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("Target1");
                return t;
            }, true);

            EObject target2 = cache.getOrCreate(source, "Rule2", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("Target2");
                return t;
            }, false);

            // Both should be cached
            assertSame(target1, cache.getByRule(source, "Rule1"));
            assertSame(target2, cache.getByRule(source, "Rule2"));

            // Different results
            assertNotSame(target1, target2);
        }

        @Test
        void testSequentialCacheHitReturnsExisting() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            AtomicInteger callCount = new AtomicInteger(0);
            Supplier<EObject> supplier = () -> {
                callCount.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            };

            // First call creates
            EObject first = cache.getOrCreate(source, "TestRule", supplier, true);
            assertEquals(1, callCount.get());

            // Second call returns cached
            EObject second = cache.getOrCreate(source, "TestRule", supplier, true);
            assertEquals(1, callCount.get()); // Not incremented

            assertSame(first, second);
        }
    }

    // ==================== Guard Evaluation Tests ====================

    @Nested
    class GuardEvaluationTests {

        @Test
        void testGuardRejectingSourceReturnsNull() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Rejected");

            EObject result = cache.getOrCreate(source, "GuardedRule", () -> {
                // Guard rejects
                if (source.getName().equals("Rejected")) {
                    return null;
                }
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            assertNull(result);
            // Null result should NOT be cached
            assertNull(cache.getByRule(source, "GuardedRule"));
        }

        @Test
        void testGuardAcceptingSourceReturnsTarget() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("Accepted");

            EObject result = cache.getOrCreate(source, "GuardedRule", () -> {
                // Guard accepts
                if (source.getName().equals("Accepted")) {
                    EClass target = EcoreFactory.eINSTANCE.createEClass();
                    target.setName("Target");
                    return target;
                }
                return null;
            }, true);

            assertNotNull(result);
            assertEquals("Target", ((EClass) result).getName());
            assertSame(result, cache.getByRule(source, "GuardedRule"));
        }

        @Test
        void testGuardEvaluationWithSideEffects() {
            // Note: Guards SHOULD be pure functions, but we test that
            // side effects from guard evaluation are visible
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            AtomicInteger sideEffectCounter = new AtomicInteger(0);

            cache.getOrCreate(source, "TestRule", () -> {
                sideEffectCounter.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            assertEquals(1, sideEffectCounter.get());

            // Second call should not re-evaluate (cached)
            cache.getOrCreate(source, "TestRule", () -> {
                sideEffectCounter.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            assertEquals(1, sideEffectCounter.get()); // Still 1
        }
    }

    // ==================== Rule Isolation Tests ====================

    @Nested
    class RuleIsolationTests {

        @Test
        void testDifferentRulesHaveIndependentCaches() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            EClass target1 = (EClass) cache.getOrCreate(source, "Rule1", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("FromRule1");
                return t;
            }, true);

            EClass target2 = (EClass) cache.getOrCreate(source, "Rule2", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("FromRule2");
                return t;
            }, false);

            assertNotSame(target1, target2);
            assertEquals("FromRule1", target1.getName());
            assertEquals("FromRule2", target2.getName());
        }

        @Test
        void testSameRuleDifferentSourcesAreIndependent() {
            EClass source1 = EcoreFactory.eINSTANCE.createEClass();
            source1.setName("Source1");
            EClass source2 = EcoreFactory.eINSTANCE.createEClass();
            source2.setName("Source2");

            AtomicInteger callCount = new AtomicInteger(0);

            EObject target1 = cache.getOrCreate(source1, "TestRule", () -> {
                callCount.incrementAndGet();
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("Target_" + callCount.get());
                return t;
            }, true);

            EObject target2 = cache.getOrCreate(source2, "TestRule", () -> {
                callCount.incrementAndGet();
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("Target_" + callCount.get());
                return t;
            }, true);

            assertEquals(2, callCount.get()); // Both sources processed
            assertNotSame(target1, target2);
        }
    }

    // ==================== Primary Flag Tests ====================

    @Nested
    class PrimaryFlagTests {

        @Test
        void testPrimaryFlagSetCorrectly() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            // Create primary mapping
            cache.getOrCreate(source, "PrimaryRule", () -> {
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            // Verify primary is set
            EClass equivalent = cache.getEquivalent(source, EClass.class);
            assertNotNull(equivalent);

            // Create non-primary mapping
            cache.getOrCreate(source, "NonPrimaryRule", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("NonPrimary");
                return t;
            }, false);

            // Primary should still be returned by equivalent()
            EClass stillPrimary = cache.getEquivalent(source, EClass.class);
            assertNotSame(stillPrimary.getName(), "NonPrimary");
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    class ErrorHandlingTests {

        @Test
        void testSupplierExceptionPropagates() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            RuntimeException expectedException = new RuntimeException("Test exception");

            RuntimeException actualException = assertThrows(RuntimeException.class, () -> {
                cache.getOrCreate(source, "ExceptionRule", () -> {
                    throw expectedException;
                }, true);
            });

            assertSame(expectedException, actualException);

            // Cache should not have entry after exception
            assertNull(cache.getByRule(source, "ExceptionRule"));
        }

        @Test
        void testSubsequentCallAfterExceptionWorks() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            AtomicInteger callCount = new AtomicInteger(0);

            // First call throws
            assertThrows(RuntimeException.class, () -> {
                cache.getOrCreate(source, "TestRule", () -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("First call fails");
                }, true);
            });

            // Second call should work (no cached exception)
            EObject result = cache.getOrCreate(source, "TestRule", () -> {
                callCount.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            assertNotNull(result);
            assertEquals(2, callCount.get()); // Both calls executed
        }
    }

    // ==================== Clear and Reset Tests ====================

    @Nested
    class ClearResetTests {

        @Test
        void testClearAllowsReCreation() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            EClass target1 = (EClass) cache.getOrCreate(source, "TestRule", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("First");
                return t;
            }, true);

            assertEquals("First", target1.getName());

            cache.clear();

            EClass target2 = (EClass) cache.getOrCreate(source, "TestRule", () -> {
                EClass t = EcoreFactory.eINSTANCE.createEClass();
                t.setName("Second");
                return t;
            }, true);

            assertEquals("Second", target2.getName());
            assertNotSame(target1, target2);
        }
    }

    // ==================== Performance Regression Tests ====================

    @Nested
    class PerformanceTests {

        @Test
        void testSequentialPerformanceNotDegraded() {
            // Create many sources
            int sourceCount = 10000;
            List<EObject> sources = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("Source_" + i);
                sources.add(source);
            }

            // Measure time for getOrCreate pattern
            long start = System.nanoTime();
            for (EObject source : sources) {
                cache.getOrCreate(source, "TestRule", () -> {
                    return EcoreFactory.eINSTANCE.createEClass();
                }, true);
            }
            long elapsed = System.nanoTime() - start;

            // Should complete in reasonable time (< 1 second for 10k elements)
            assertTrue(elapsed < 1_000_000_000L,
                    "Sequential processing of 10k elements should complete in < 1s, took " + (elapsed / 1_000_000) + "ms");

            // All should be cached
            for (EObject source : sources) {
                assertNotNull(cache.getByRule(source, "TestRule"));
            }
        }

        @Test
        void testCacheHitPerformance() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            // First call creates
            cache.getOrCreate(source, "TestRule", () -> {
                return EcoreFactory.eINSTANCE.createEClass();
            }, true);

            // Measure cache hit performance
            int iterations = 100000;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                cache.getOrCreate(source, "TestRule", () -> {
                    fail("Supplier should not be called for cache hit");
                    return null;
                }, true);
            }
            long elapsed = System.nanoTime() - start;

            // Cache hits should be very fast (< 100ms for 100k iterations)
            assertTrue(elapsed < 100_000_000L,
                    "100k cache hits should complete in < 100ms, took " + (elapsed / 1_000_000) + "ms");
        }
    }
}
