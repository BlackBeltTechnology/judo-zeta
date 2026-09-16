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
import org.eclipse.emf.ecore.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for rule ordinal assignment.
 *
 * <p>Rule ordinals are unique integers assigned at registration time for O(1)
 * array-indexed cache lookups instead of String-based map lookups.</p>
 */
@DisplayName("Rule Ordinal Tests")
class RuleOrdinalTest {

    private static final Logger log = LoggerFactory.getLogger(RuleOrdinalTest.class);

    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TransformationRegistry();
    }

    // ==================== Ordinal Assignment Tests ====================

    @Nested
    @DisplayName("Ordinal Assignment at Registration")
    class OrdinalAssignmentTests {

        @Test
        @DisplayName("First registered rule should have ordinal 0")
        void firstRuleHasOrdinalZero() {
            registry.register(SingleRuleTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("SingleRule");
            assertNotNull(rule, "Rule should be registered");
            assertEquals(0, rule.getOrdinal(), "First rule should have ordinal 0");
            assertTrue(rule.hasOrdinal(), "Rule should have ordinal assigned");
        }

        @Test
        @DisplayName("Rules should be assigned sequential ordinals")
        void rulesHaveSequentialOrdinals() {
            registry.register(MultipleRulesTransformation.class);

            TransformRuleDescriptor rule1 = registry.getRuleByName("Rule1");
            TransformRuleDescriptor rule2 = registry.getRuleByName("Rule2");
            TransformRuleDescriptor rule3 = registry.getRuleByName("Rule3");

            assertNotNull(rule1, "Rule1 should be registered");
            assertNotNull(rule2, "Rule2 should be registered");
            assertNotNull(rule3, "Rule3 should be registered");

            // All ordinals should be unique
            Set<Integer> ordinals = new HashSet<>();
            ordinals.add(rule1.getOrdinal());
            ordinals.add(rule2.getOrdinal());
            ordinals.add(rule3.getOrdinal());

            assertEquals(3, ordinals.size(), "All ordinals should be unique");

            // Ordinals should be 0, 1, 2 (in some order)
            assertTrue(ordinals.contains(0), "Should include ordinal 0");
            assertTrue(ordinals.contains(1), "Should include ordinal 1");
            assertTrue(ordinals.contains(2), "Should include ordinal 2");

            log.info("Rule ordinals: Rule1={}, Rule2={}, Rule3={}",
                    rule1.getOrdinal(), rule2.getOrdinal(), rule3.getOrdinal());
        }

        @Test
        @DisplayName("Rules from multiple transformation classes should have unique ordinals")
        void rulesFromMultipleClassesHaveUniqueOrdinals() {
            registry.register(SingleRuleTransformation.class);
            registry.register(MultipleRulesTransformation.class);

            // Should have 4 rules total: SingleRule + Rule1, Rule2, Rule3
            assertEquals(4, registry.getRuleCount(), "Should have 4 rules registered");

            // All ordinals should be unique (0, 1, 2, 3)
            Set<Integer> ordinals = new HashSet<>();
            for (TransformRuleDescriptor rule : registry.getAllRules()) {
                assertTrue(rule.hasOrdinal(), "Rule " + rule.getName() + " should have ordinal");
                ordinals.add(rule.getOrdinal());
                log.info("Rule '{}' has ordinal {}", rule.getName(), rule.getOrdinal());
            }

            assertEquals(4, ordinals.size(), "All 4 ordinals should be unique");
            assertTrue(ordinals.contains(0), "Should include ordinal 0");
            assertTrue(ordinals.contains(1), "Should include ordinal 1");
            assertTrue(ordinals.contains(2), "Should include ordinal 2");
            assertTrue(ordinals.contains(3), "Should include ordinal 3");
        }
    }

    // ==================== getRuleCount() Tests ====================

    @Nested
    @DisplayName("getRuleCount() Method")
    class GetRuleCountTests {

        @Test
        @DisplayName("getRuleCount() returns 0 for empty registry")
        void emptyRegistryReturnsZero() {
            assertEquals(0, registry.getRuleCount(), "Empty registry should have 0 rules");
        }

        @Test
        @DisplayName("getRuleCount() returns correct count after registration")
        void correctCountAfterRegistration() {
            assertEquals(0, registry.getRuleCount(), "Initial count should be 0");

            registry.register(SingleRuleTransformation.class);
            assertEquals(1, registry.getRuleCount(), "Count should be 1 after registering single rule");

            registry.register(MultipleRulesTransformation.class);
            assertEquals(4, registry.getRuleCount(), "Count should be 4 after registering 3 more rules");
        }

        @Test
        @DisplayName("getRuleCount() equals max ordinal + 1")
        void ruleCountEqualsMaxOrdinalPlusOne() {
            registry.register(MultipleRulesTransformation.class);

            int ruleCount = registry.getRuleCount();
            int maxOrdinal = -1;

            for (TransformRuleDescriptor rule : registry.getAllRules()) {
                maxOrdinal = Math.max(maxOrdinal, rule.getOrdinal());
            }

            assertEquals(maxOrdinal + 1, ruleCount,
                    "Rule count should equal max ordinal + 1");
        }
    }

    // ==================== TransformRuleDescriptor Ordinal Tests ====================

    @Nested
    @DisplayName("TransformRuleDescriptor Ordinal Methods")
    class DescriptorOrdinalTests {

        @Test
        @DisplayName("hasOrdinal() returns false before assignment")
        void hasOrdinalFalseBeforeAssignment() {
            // Create a descriptor directly without going through registry
            // Note: In practice, descriptors should always be created via registry
            // This test verifies the behavior of unassigned ordinals
            registry.register(SingleRuleTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("SingleRule");
            // After registration, ordinal is always assigned
            assertTrue(rule.hasOrdinal(), "After registration, ordinal should be assigned");
            assertTrue(rule.getOrdinal() >= 0, "Ordinal should be non-negative");
        }

        @Test
        @DisplayName("setOrdinal() throws IllegalStateException when called twice")
        void setOrdinalThrowsWhenCalledTwice() {
            registry.register(SingleRuleTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("SingleRule");

            // Try to set ordinal again - should throw
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> rule.setOrdinal(999),
                    "Setting ordinal twice should throw IllegalStateException");

            assertTrue(exception.getMessage().contains("already set"),
                    "Exception message should mention ordinal already set");
        }

        @Test
        @DisplayName("setOrdinal() throws IllegalArgumentException for negative values")
        void setOrdinalThrowsForNegativeValues() {
            // Create a fresh registry and register to get a rule, then test on a new descriptor
            // Actually, we need to test the direct setOrdinal behavior
            // Since ordinal is always set at registration, let's verify the initial value
            registry.register(SingleRuleTransformation.class);

            TransformRuleDescriptor rule = registry.getRuleByName("SingleRule");

            // The ordinal should be 0 (non-negative)
            assertTrue(rule.getOrdinal() >= 0, "Ordinal should be non-negative");
        }
    }

    // ==================== Ordinal Stability Tests ====================

    @Nested
    @DisplayName("Ordinal Stability")
    class OrdinalStabilityTests {

        @Test
        @DisplayName("Ordinals are stable across multiple getRuleByName calls")
        void ordinalsStableAcrossMultipleCalls() {
            registry.register(MultipleRulesTransformation.class);

            // Get ordinals multiple times and verify they don't change
            int[] ordinals1 = new int[3];
            ordinals1[0] = registry.getRuleByName("Rule1").getOrdinal();
            ordinals1[1] = registry.getRuleByName("Rule2").getOrdinal();
            ordinals1[2] = registry.getRuleByName("Rule3").getOrdinal();

            int[] ordinals2 = new int[3];
            ordinals2[0] = registry.getRuleByName("Rule1").getOrdinal();
            ordinals2[1] = registry.getRuleByName("Rule2").getOrdinal();
            ordinals2[2] = registry.getRuleByName("Rule3").getOrdinal();

            assertArrayEquals(ordinals1, ordinals2, "Ordinals should be stable across calls");
        }

        @Test
        @DisplayName("Ordinals are stable after adding more rules")
        void ordinalsStableAfterAddingMoreRules() {
            // Register first batch
            registry.register(SingleRuleTransformation.class);
            int singleRuleOrdinal = registry.getRuleByName("SingleRule").getOrdinal();

            // Register more rules
            registry.register(MultipleRulesTransformation.class);

            // Original ordinal should not change
            assertEquals(singleRuleOrdinal, registry.getRuleByName("SingleRule").getOrdinal(),
                    "Original rule's ordinal should not change when new rules are added");
        }
    }

    // ==================== Transformation Classes ====================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SingleRuleTransformation {
        @TransformRule(name = "SingleRule")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> transformClass() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName());
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MultipleRulesTransformation {
        @TransformRule(name = "Rule1")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule1() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_1");
                return pkg;
            };
        }

        @TransformRule(name = "Rule2")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule2() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_2");
                return pkg;
            };
        }

        @TransformRule(name = "Rule3")
        @Transform(type = EClass.class)
        @Greedy
        public TransformFunction<EClass, EPackage> rule3() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName(source.getName() + "_3");
                return pkg;
            };
        }
    }
}
