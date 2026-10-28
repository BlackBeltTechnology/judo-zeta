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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ActivationTracker}.
 */
@DisplayName("ActivationTracker Tests")
class ActivationTrackerTest {

    private ActivationTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ActivationTracker();
    }

    @Nested
    @DisplayName("Single Activation Tests")
    class SingleActivationTests {

        @Test
        @DisplayName("Activating an element records it for the rule")
        void activateSingleElement() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("TestClass");

            tracker.activate("TestRule", element);

            assertTrue(tracker.hasActivations("TestRule"));
            Set<EObject> activated = tracker.getActivated("TestRule");
            assertEquals(1, activated.size());
            assertTrue(activated.contains(element));
        }

        @Test
        @DisplayName("Non-activated rule returns empty set")
        void nonActivatedRuleReturnsEmpty() {
            assertFalse(tracker.hasActivations("NonExistentRule"));
            Set<EObject> activated = tracker.getActivated("NonExistentRule");
            assertTrue(activated.isEmpty());
        }

        @Test
        @DisplayName("Null rule name is ignored")
        void nullRuleNameIgnored() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            tracker.activate(null, element);

            assertEquals(0, tracker.getTotalActivationCount());
        }

        @Test
        @DisplayName("Null element is ignored")
        void nullElementIgnored() {
            tracker.activate("TestRule", null);

            assertFalse(tracker.hasActivations("TestRule"));
        }
    }

    @Nested
    @DisplayName("Multiple Activations Tests")
    class MultipleActivationsTests {

        @Test
        @DisplayName("Multiple elements activated for same rule")
        void multipleElementsSameRule() {
            EClass element1 = EcoreFactory.eINSTANCE.createEClass();
            element1.setName("Class1");
            EClass element2 = EcoreFactory.eINSTANCE.createEClass();
            element2.setName("Class2");
            EClass element3 = EcoreFactory.eINSTANCE.createEClass();
            element3.setName("Class3");

            tracker.activate("TestRule", element1);
            tracker.activate("TestRule", element2);
            tracker.activate("TestRule", element3);

            Set<EObject> activated = tracker.getActivated("TestRule");
            assertEquals(3, activated.size());
            assertTrue(activated.contains(element1));
            assertTrue(activated.contains(element2));
            assertTrue(activated.contains(element3));
        }

        @Test
        @DisplayName("Same element activated twice is counted once")
        void duplicateActivationIgnored() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("TestClass");

            tracker.activate("TestRule", element);
            tracker.activate("TestRule", element);

            Set<EObject> activated = tracker.getActivated("TestRule");
            assertEquals(1, activated.size());
        }

        @Test
        @DisplayName("Elements activated for different rules are tracked separately")
        void differentRulesTrackedSeparately() {
            EClass element1 = EcoreFactory.eINSTANCE.createEClass();
            element1.setName("Class1");
            EClass element2 = EcoreFactory.eINSTANCE.createEClass();
            element2.setName("Class2");

            tracker.activate("RuleA", element1);
            tracker.activate("RuleB", element2);

            assertEquals(1, tracker.getActivated("RuleA").size());
            assertEquals(1, tracker.getActivated("RuleB").size());
            assertTrue(tracker.getActivated("RuleA").contains(element1));
            assertTrue(tracker.getActivated("RuleB").contains(element2));
        }

        @Test
        @DisplayName("Same element can be activated for multiple rules")
        void sameElementMultipleRules() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("SharedClass");

            tracker.activate("RuleA", element);
            tracker.activate("RuleB", element);

            assertTrue(tracker.getActivated("RuleA").contains(element));
            assertTrue(tracker.getActivated("RuleB").contains(element));
            assertEquals(2, tracker.getTotalActivationCount());
        }
    }

    @Nested
    @DisplayName("Concurrent Activation Tests")
    class ConcurrentActivationTests {

        @Test
        @DisplayName("Concurrent activations are thread-safe")
        void concurrentActivationsThreadSafe() throws InterruptedException {
            int threadCount = 10;
            int elementsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            List<EClass> allElements = new ArrayList<>();
            for (int i = 0; i < threadCount * elementsPerThread; i++) {
                EClass element = EcoreFactory.eINSTANCE.createEClass();
                element.setName("Class" + i);
                allElements.add(element);
            }

            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < elementsPerThread; i++) {
                            int elementIndex = threadIndex * elementsPerThread + i;
                            tracker.activate("TestRule", allElements.get(elementIndex));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            Set<EObject> activated = tracker.getActivated("TestRule");
            assertEquals(threadCount * elementsPerThread, activated.size(),
                    "All elements should be activated without loss");
        }

        @Test
        @DisplayName("Concurrent activations across different rules")
        void concurrentActivationsDifferentRules() throws InterruptedException {
            int ruleCount = 5;
            int elementsPerRule = 50;
            ExecutorService executor = Executors.newFixedThreadPool(ruleCount);
            CountDownLatch latch = new CountDownLatch(ruleCount);

            for (int r = 0; r < ruleCount; r++) {
                final String ruleName = "Rule" + r;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < elementsPerRule; i++) {
                            EClass element = EcoreFactory.eINSTANCE.createEClass();
                            element.setName(ruleName + "_Class" + i);
                            tracker.activate(ruleName, element);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(ruleCount, tracker.getRulesWithActivations().size());
            assertEquals(ruleCount * elementsPerRule, tracker.getTotalActivationCount());
        }
    }

    @Nested
    @DisplayName("Clear and Query Tests")
    class ClearAndQueryTests {

        @Test
        @DisplayName("Clear removes all activations")
        void clearRemovesAll() {
            EClass element1 = EcoreFactory.eINSTANCE.createEClass();
            EClass element2 = EcoreFactory.eINSTANCE.createEClass();

            tracker.activate("RuleA", element1);
            tracker.activate("RuleB", element2);

            assertEquals(2, tracker.getTotalActivationCount());

            tracker.clear();

            assertEquals(0, tracker.getTotalActivationCount());
            assertFalse(tracker.hasActivations("RuleA"));
            assertFalse(tracker.hasActivations("RuleB"));
        }

        @Test
        @DisplayName("getRulesWithActivations returns all active rules")
        void getRulesWithActivations() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();

            tracker.activate("RuleA", element);
            tracker.activate("RuleB", element);
            tracker.activate("RuleC", element);

            Set<String> rules = tracker.getRulesWithActivations();
            assertEquals(3, rules.size());
            assertTrue(rules.contains("RuleA"));
            assertTrue(rules.contains("RuleB"));
            assertTrue(rules.contains("RuleC"));
        }

        @Test
        @DisplayName("getTotalActivationCount returns sum across all rules")
        void getTotalActivationCount() {
            EClass element1 = EcoreFactory.eINSTANCE.createEClass();
            EClass element2 = EcoreFactory.eINSTANCE.createEClass();
            EClass element3 = EcoreFactory.eINSTANCE.createEClass();

            tracker.activate("RuleA", element1);
            tracker.activate("RuleA", element2);
            tracker.activate("RuleB", element3);

            assertEquals(3, tracker.getTotalActivationCount());
        }

        @Test
        @DisplayName("toString provides useful debug information")
        void toStringOutput() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            tracker.activate("TestRule", element);

            String str = tracker.toString();
            assertTrue(str.contains("ActivationTracker"));
            assertTrue(str.contains("TestRule"));
            assertTrue(str.contains("1"));
        }
    }
}
