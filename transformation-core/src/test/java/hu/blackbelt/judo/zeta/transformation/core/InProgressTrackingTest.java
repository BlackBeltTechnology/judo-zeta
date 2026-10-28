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
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for in-progress tracking in ElementResolutionCache.
 *
 * <p>In-progress tracking is used for circular dependency handling. When a @Lazy rule
 * starts executing, it marks its target as "in-progress" before the transformation completes.
 * If another rule calls equivalentDiscriminated() for the same source during execution,
 * it can find the in-progress target.</p>
 */
@DisplayName("In-Progress Tracking Tests")
class InProgressTrackingTest {

    private static final Logger log = LoggerFactory.getLogger(InProgressTrackingTest.class);

    // ==================== Sequential Mode Tests ====================

    @Nested
    @DisplayName("Sequential Mode")
    class SequentialModeTests {

        private ElementResolutionCache cache;

        @BeforeEach
        void setUp() {
            cache = new ElementResolutionCache(true); // sequential mode
        }

        @Test
        @DisplayName("markInProgress stores target for source and ordinal")
        void markInProgressStoresTarget() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            int ruleOrdinal = 0;
            int ruleCount = 5;

            cache.markInProgress(source, ruleOrdinal, target, ruleCount);

            EClass retrieved = cache.getInProgress(source, ruleOrdinal);
            assertSame(target, retrieved, "Should retrieve the same target instance");
        }

        @Test
        @DisplayName("getInProgress returns null for non-existent source")
        void getInProgressReturnsNullForNonExistentSource() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            EClass retrieved = cache.getInProgress(source, 0);
            assertNull(retrieved, "Should return null for source with no in-progress tracking");
        }

        @Test
        @DisplayName("getInProgress returns null for non-existent ordinal")
        void getInProgressReturnsNullForNonExistentOrdinal() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            cache.markInProgress(source, 0, target, 5);

            EClass retrieved = cache.getInProgress(source, 1);
            assertNull(retrieved, "Should return null for ordinal with no target");
        }

        @Test
        @DisplayName("clearInProgress removes target")
        void clearInProgressRemovesTarget() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            int ruleOrdinal = 2;
            int ruleCount = 5;

            cache.markInProgress(source, ruleOrdinal, target, ruleCount);
            assertNotNull(cache.getInProgress(source, ruleOrdinal), "Target should be present");

            cache.clearInProgress(source, ruleOrdinal);
            assertNull(cache.getInProgress(source, ruleOrdinal), "Target should be cleared");
        }

        @Test
        @DisplayName("Multiple ordinals can be tracked for same source")
        void multipleOrdinalsForSameSource() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target0 = EcoreFactory.eINSTANCE.createEClass();
            EClass target1 = EcoreFactory.eINSTANCE.createEClass();
            EClass target2 = EcoreFactory.eINSTANCE.createEClass();
            int ruleCount = 5;

            cache.markInProgress(source, 0, target0, ruleCount);
            cache.markInProgress(source, 1, target1, ruleCount);
            cache.markInProgress(source, 2, target2, ruleCount);

            assertSame(target0, cache.getInProgress(source, 0));
            assertSame(target1, cache.getInProgress(source, 1));
            assertSame(target2, cache.getInProgress(source, 2));
        }

        @Test
        @DisplayName("hasAnyInProgress returns true when targets exist")
        void hasAnyInProgressReturnsTrue() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            assertFalse(cache.hasAnyInProgress(source), "Should be false before marking");

            cache.markInProgress(source, 0, target, 5);
            assertTrue(cache.hasAnyInProgress(source), "Should be true after marking");

            cache.clearInProgress(source, 0);
            assertFalse(cache.hasAnyInProgress(source), "Should be false after clearing");
        }

        @Test
        @DisplayName("clearInProgressTracking clears all in-progress data")
        void clearInProgressTrackingClearsAll() {
            EClass source1 = EcoreFactory.eINSTANCE.createEClass();
            EClass source2 = EcoreFactory.eINSTANCE.createEClass();
            EClass target1 = EcoreFactory.eINSTANCE.createEClass();
            EClass target2 = EcoreFactory.eINSTANCE.createEClass();

            cache.markInProgress(source1, 0, target1, 5);
            cache.markInProgress(source2, 1, target2, 5);

            assertTrue(cache.hasAnyInProgress(source1));
            assertTrue(cache.hasAnyInProgress(source2));

            cache.clearInProgressTracking();

            assertFalse(cache.hasAnyInProgress(source1));
            assertFalse(cache.hasAnyInProgress(source2));
        }

        @Test
        @DisplayName("clear() also clears in-progress data")
        void clearAlsoClearsInProgress() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            cache.markInProgress(source, 0, target, 5);
            assertTrue(cache.hasAnyInProgress(source));

            cache.clear();

            assertFalse(cache.hasAnyInProgress(source));
        }
    }

    // ==================== Parallel Mode Tests ====================

    @Nested
    @DisplayName("Parallel Mode")
    class ParallelModeTests {

        private ElementResolutionCache cache;

        @BeforeEach
        void setUp() {
            cache = new ElementResolutionCache(false); // parallel mode
        }

        @Test
        @DisplayName("markInProgress stores target for source and ordinal")
        void markInProgressStoresTarget() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            int ruleOrdinal = 0;
            int ruleCount = 5;

            cache.markInProgress(source, ruleOrdinal, target, ruleCount);

            EClass retrieved = cache.getInProgress(source, ruleOrdinal);
            assertSame(target, retrieved, "Should retrieve the same target instance");
        }

        @Test
        @DisplayName("Multiple ordinals can be tracked for same source")
        void multipleOrdinalsForSameSource() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target0 = EcoreFactory.eINSTANCE.createEClass();
            EClass target1 = EcoreFactory.eINSTANCE.createEClass();
            int ruleCount = 5;

            cache.markInProgress(source, 0, target0, ruleCount);
            cache.markInProgress(source, 1, target1, ruleCount);

            assertSame(target0, cache.getInProgress(source, 0));
            assertSame(target1, cache.getInProgress(source, 1));
        }

        @Test
        @DisplayName("clearInProgress removes target")
        void clearInProgressRemovesTarget() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            cache.markInProgress(source, 0, target, 5);
            assertNotNull(cache.getInProgress(source, 0));

            cache.clearInProgress(source, 0);
            assertNull(cache.getInProgress(source, 0));
        }

        @Test
        @DisplayName("Concurrent access from multiple threads")
        void concurrentAccess() throws InterruptedException {
            final int THREAD_COUNT = 10;
            final int OPS_PER_THREAD = 100;
            Thread[] threads = new Thread[THREAD_COUNT];
            final int ruleCount = THREAD_COUNT;

            // Each thread works with its own source element
            EClass[] sources = new EClass[THREAD_COUNT];
            for (int i = 0; i < THREAD_COUNT; i++) {
                sources[i] = EcoreFactory.eINSTANCE.createEClass();
                sources[i].setName("Source" + i);
            }

            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadIdx = i;
                threads[i] = new Thread(() -> {
                    EClass source = sources[threadIdx];
                    for (int j = 0; j < OPS_PER_THREAD; j++) {
                        EClass target = EcoreFactory.eINSTANCE.createEClass();
                        target.setName("Target_" + threadIdx + "_" + j);

                        cache.markInProgress(source, threadIdx, target, ruleCount);
                        EClass retrieved = cache.getInProgress(source, threadIdx);
                        assertNotNull(retrieved, "Should find in-progress target");
                        cache.clearInProgress(source, threadIdx);
                    }
                });
            }

            // Start all threads
            for (Thread t : threads) {
                t.start();
            }

            // Wait for all threads to complete
            for (Thread t : threads) {
                t.join();
            }

            // After all threads complete, no in-progress should remain
            for (EClass source : sources) {
                assertFalse(cache.hasAnyInProgress(source),
                        "No in-progress should remain after threads complete");
            }
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Null source is handled gracefully")
        void nullSourceHandled() {
            ElementResolutionCache cache = new ElementResolutionCache(true);
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            // Should not throw
            cache.markInProgress(null, 0, target, 5);
            assertNull(cache.getInProgress(null, 0));
            cache.clearInProgress(null, 0);
            assertFalse(cache.hasAnyInProgress(null));
        }

        @Test
        @DisplayName("Null target is handled gracefully")
        void nullTargetHandled() {
            ElementResolutionCache cache = new ElementResolutionCache(true);
            EClass source = EcoreFactory.eINSTANCE.createEClass();

            // Should not throw
            cache.markInProgress(source, 0, null, 5);
            assertNull(cache.getInProgress(source, 0));
        }

        @Test
        @DisplayName("Negative ordinal is handled gracefully")
        void negativeOrdinalHandled() {
            ElementResolutionCache cache = new ElementResolutionCache(true);
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            // Should not throw
            cache.markInProgress(source, -1, target, 5);
            assertNull(cache.getInProgress(source, -1));
            cache.clearInProgress(source, -1);
        }

        @Test
        @DisplayName("Zero ruleCount is handled gracefully")
        void zeroRuleCountHandled() {
            ElementResolutionCache cache = new ElementResolutionCache(true);
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            // Should not throw
            cache.markInProgress(source, 0, target, 0);
            assertNull(cache.getInProgress(source, 0));
        }

        @Test
        @DisplayName("Ordinal out of bounds is handled gracefully")
        void ordinalOutOfBoundsHandled() {
            ElementResolutionCache cache = new ElementResolutionCache(true);
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            EClass target = EcoreFactory.eINSTANCE.createEClass();

            // Mark with ordinal 0, then query with ordinal 10
            cache.markInProgress(source, 0, target, 5);

            // Should return null, not throw
            assertNull(cache.getInProgress(source, 10));

            // Clear should also not throw
            cache.clearInProgress(source, 10);
        }
    }
}
