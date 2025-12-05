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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Graph for managing rule inheritance relationships.
 *
 * <p>Supports @Extends annotation for rule inheritance with:</p>
 * <ul>
 *   <li>Topological ordering for execution</li>
 *   <li>Cycle detection</li>
 *   <li>Parent rule lookup</li>
 * </ul>
 */
public class RuleInheritanceGraph {

    private static final Logger log = LoggerFactory.getLogger(RuleInheritanceGraph.class);

    private final Map<String, TransformRuleDescriptor> rules = new HashMap<>();
    private final Map<String, Set<String>> parentEdges = new HashMap<>();  // child → parents
    private final Map<String, Set<String>> childEdges = new HashMap<>();   // parent → children
    private List<String> executionOrder;

    /**
     * Add a rule to the graph.
     */
    public void addRule(TransformRuleDescriptor rule) {
        rules.put(rule.getName(), rule);

        List<String> extendsRules = rule.getExtendsRules();
        if (extendsRules != null && !extendsRules.isEmpty()) {
            parentEdges.put(rule.getName(), new HashSet<>(extendsRules));

            // Build reverse edges
            for (String parent : extendsRules) {
                childEdges.computeIfAbsent(parent, k -> new HashSet<>()).add(rule.getName());
            }
        }
    }

    /**
     * Build the execution order using topological sort.
     * Must be called after all rules are added.
     */
    public void buildExecutionOrder() {
        validateGraph();
        executionOrder = topologicalSort();
    }

    /**
     * Validate the graph for consistency.
     */
    public void validateGraph() {
        // Check all parent rules exist
        for (Map.Entry<String, Set<String>> entry : parentEdges.entrySet()) {
            for (String parent : entry.getValue()) {
                if (!rules.containsKey(parent)) {
                    throw new IllegalStateException(
                            "Rule '" + entry.getKey() + "' extends non-existent parent '" + parent + "'");
                }
            }
        }

        // Detect cycles
        detectCycles();

        // Warn about abstract rules without children
        for (TransformRuleDescriptor rule : rules.values()) {
            if (rule.isAbstract() && !childEdges.containsKey(rule.getName())) {
                log.warn("Abstract rule '{}' has no child rules", rule.getName());
            }
        }
    }

    private void detectCycles() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String ruleName : rules.keySet()) {
            if (hasCycle(ruleName, visited, inStack, new ArrayList<>())) {
                // The cycle path is logged in hasCycle method
            }
        }
    }

    private boolean hasCycle(String ruleName, Set<String> visited, Set<String> inStack, List<String> path) {
        if (inStack.contains(ruleName)) {
            path.add(ruleName);
            throw new IllegalStateException(
                    "Cycle detected in rule inheritance graph: " + String.join(" -> ", path));
        }

        if (visited.contains(ruleName)) {
            return false;
        }

        visited.add(ruleName);
        inStack.add(ruleName);
        path.add(ruleName);

        Set<String> parents = parentEdges.get(ruleName);
        if (parents != null) {
            for (String parent : parents) {
                if (hasCycle(parent, visited, inStack, new ArrayList<>(path))) {
                    return true;
                }
            }
        }

        inStack.remove(ruleName);
        return false;
    }

    private List<String> topologicalSort() {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String ruleName : rules.keySet()) {
            topologicalSortVisit(ruleName, visited, result);
        }

        return result;
    }

    private void topologicalSortVisit(String ruleName, Set<String> visited, List<String> result) {
        if (visited.contains(ruleName)) {
            return;
        }

        visited.add(ruleName);

        // Visit parents first (they should execute before children)
        Set<String> parents = parentEdges.get(ruleName);
        if (parents != null) {
            for (String parent : parents) {
                topologicalSortVisit(parent, visited, result);
            }
        }

        result.add(ruleName);
    }

    /**
     * Get rules in execution order (parents before children).
     */
    public List<TransformRuleDescriptor> getExecutionOrder() {
        if (executionOrder == null) {
            buildExecutionOrder();
        }

        return executionOrder.stream()
                .map(rules::get)
                .collect(Collectors.toList());
    }

    /**
     * Get parent rules for a given rule.
     */
    public List<TransformRuleDescriptor> getParentRules(String ruleName) {
        Set<String> parents = parentEdges.get(ruleName);
        if (parents == null || parents.isEmpty()) {
            return Collections.emptyList();
        }

        return parents.stream()
                .map(rules::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get child rules for a given rule.
     */
    public List<TransformRuleDescriptor> getChildRules(String ruleName) {
        Set<String> children = childEdges.get(ruleName);
        if (children == null || children.isEmpty()) {
            return Collections.emptyList();
        }

        return children.stream()
                .map(rules::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get a rule by name.
     */
    public TransformRuleDescriptor getRule(String name) {
        return rules.get(name);
    }

    /**
     * Get all rules in the graph.
     */
    public Collection<TransformRuleDescriptor> getAllRules() {
        return rules.values();
    }
}
