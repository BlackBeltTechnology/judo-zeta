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

import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RuleInheritanceGraph.
 */
class RuleInheritanceGraphTest {

    private RuleInheritanceGraph graph;
    private Method dummyMethod;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        graph = new RuleInheritanceGraph();
        // Get a real method to use in descriptors
        dummyMethod = RuleInheritanceGraphTest.class.getDeclaredMethod("dummyTransformFunction");
    }

    // Dummy method to use as ruleMethod in tests
    public TransformFunction<EObject, EObject> dummyTransformFunction() {
        return (source, ctx) -> null;
    }

    @Test
    void testAddAndGetRule() {
        TransformRuleDescriptor rule = createDescriptor("Rule1", Collections.emptyList(), false);
        graph.addRule(rule);
        
        TransformRuleDescriptor result = graph.getRule("Rule1");
        
        assertNotNull(result);
        assertEquals("Rule1", result.getName());
    }

    @Test
    void testGetRuleReturnsNullForUnknown() {
        TransformRuleDescriptor result = graph.getRule("UnknownRule");
        
        assertNull(result);
    }

    @Test
    void testExecutionOrderWithNoInheritance() {
        graph.addRule(createDescriptor("Rule1", Collections.emptyList(), false));
        graph.addRule(createDescriptor("Rule2", Collections.emptyList(), false));
        graph.addRule(createDescriptor("Rule3", Collections.emptyList(), false));
        
        List<TransformRuleDescriptor> order = graph.getExecutionOrder();
        
        assertEquals(3, order.size());
    }

    @Test
    void testExecutionOrderWithInheritance() {
        // Child extends Parent - Parent should execute before Child
        graph.addRule(createDescriptor("Parent", Collections.emptyList(), true));
        graph.addRule(createDescriptor("Child", Arrays.asList("Parent"), false));
        
        List<TransformRuleDescriptor> order = graph.getExecutionOrder();
        
        assertEquals(2, order.size());
        
        // Find positions
        int parentIndex = -1, childIndex = -1;
        for (int i = 0; i < order.size(); i++) {
            if ("Parent".equals(order.get(i).getName())) parentIndex = i;
            if ("Child".equals(order.get(i).getName())) childIndex = i;
        }
        
        assertTrue(parentIndex < childIndex, "Parent should come before Child");
    }

    @Test
    void testExecutionOrderWithMultiLevelInheritance() {
        // GrandChild extends Child extends Parent
        graph.addRule(createDescriptor("Parent", Collections.emptyList(), true));
        graph.addRule(createDescriptor("Child", Arrays.asList("Parent"), true));
        graph.addRule(createDescriptor("GrandChild", Arrays.asList("Child"), false));
        
        List<TransformRuleDescriptor> order = graph.getExecutionOrder();
        
        assertEquals(3, order.size());
        
        // Find positions
        int parentIndex = -1, childIndex = -1, grandChildIndex = -1;
        for (int i = 0; i < order.size(); i++) {
            if ("Parent".equals(order.get(i).getName())) parentIndex = i;
            if ("Child".equals(order.get(i).getName())) childIndex = i;
            if ("GrandChild".equals(order.get(i).getName())) grandChildIndex = i;
        }
        
        assertTrue(parentIndex < childIndex, "Parent should come before Child");
        assertTrue(childIndex < grandChildIndex, "Child should come before GrandChild");
    }

    @Test
    void testCycleDetection() {
        // Create a cycle: A extends B, B extends A
        graph.addRule(createDescriptor("RuleA", Arrays.asList("RuleB"), false));
        graph.addRule(createDescriptor("RuleB", Arrays.asList("RuleA"), false));
        
        assertThrows(IllegalStateException.class, () -> graph.validateGraph());
    }

    @Test
    void testMissingParentDetection() {
        // Child extends non-existent Parent
        graph.addRule(createDescriptor("Child", Arrays.asList("NonExistentParent"), false));
        
        assertThrows(IllegalStateException.class, () -> graph.validateGraph());
    }

    @Test
    void testGetParentRules() {
        TransformRuleDescriptor parent = createDescriptor("Parent", Collections.emptyList(), true);
        TransformRuleDescriptor child = createDescriptor("Child", Arrays.asList("Parent"), false);
        
        graph.addRule(parent);
        graph.addRule(child);
        graph.buildExecutionOrder();
        
        List<TransformRuleDescriptor> parents = graph.getParentRules("Child");
        
        assertEquals(1, parents.size());
        assertEquals("Parent", parents.get(0).getName());
    }

    @Test
    void testGetChildRules() {
        TransformRuleDescriptor parent = createDescriptor("Parent", Collections.emptyList(), true);
        TransformRuleDescriptor child = createDescriptor("Child", Arrays.asList("Parent"), false);
        
        graph.addRule(parent);
        graph.addRule(child);
        graph.buildExecutionOrder();
        
        List<TransformRuleDescriptor> children = graph.getChildRules("Parent");
        
        assertEquals(1, children.size());
        assertEquals("Child", children.get(0).getName());
    }

    @Test
    void testGetAllRules() {
        graph.addRule(createDescriptor("Rule1", Collections.emptyList(), false));
        graph.addRule(createDescriptor("Rule2", Collections.emptyList(), false));
        
        assertEquals(2, graph.getAllRules().size());
    }

    /**
     * Create a TransformRuleDescriptor for testing.
     */
    private TransformRuleDescriptor createDescriptor(String name, List<String> extendsRules, boolean isAbstract) {
        return new TransformRuleDescriptor(
                this, // instance
                dummyMethod,
                name,
                "Description for " + name,
                EObject.class,
                EObject.class,
                null, // guardMethod
                false, // isLazy
                isAbstract,
                false, // isPrimary
                false, // isGreedy
                extendsRules
        );
    }
}
