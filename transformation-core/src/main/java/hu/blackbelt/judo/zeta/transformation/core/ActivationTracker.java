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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which source elements have been "activated" for activity-based rules.
 *
 * <p>Activity-based processing is used with {@code @Greedy @Lazy @ActivityBased} rules
 * to match Epsilon ETL's implicit filtering behavior. Instead of eagerly processing
 * all matching elements, activity-based rules only process elements that are
 * "activated" via {@code equivalent()} calls during transformation.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. It uses {@link ConcurrentHashMap} for storage
 * to support parallel transformation execution.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ActivationTracker tracker = new ActivationTracker();
 *
 * // Record activation when equivalent() is called
 * tracker.activate("ClassType", sourceElement);
 *
 * // Later, get activated elements for a rule
 * Set<EObject> activated = tracker.getActivated("ClassType");
 * for (EObject source : activated) {
 *     // Process only activated elements
 * }
 * }</pre>
 */
public class ActivationTracker {

    /**
     * Map from rule name to set of activated source elements.
     * Uses ConcurrentHashMap for thread-safe access during parallel execution.
     */
    private final ConcurrentHashMap<String, Set<EObject>> activations = new ConcurrentHashMap<>();

    /**
     * Record that a source element was activated for an activity-based rule.
     *
     * <p>This is called from {@code equivalent()} when it finds a matching
     * activity-based rule. The activation is recorded so that Phase 2 execution
     * knows which elements to process.</p>
     *
     * @param ruleName the name of the activity-based rule
     * @param source the source element that was activated
     */
    public void activate(String ruleName, EObject source) {
        if (ruleName == null || source == null) {
            return;
        }
        activations.computeIfAbsent(ruleName, k -> ConcurrentHashMap.newKeySet())
                   .add(source);
    }

    /**
     * Get all activated source elements for a rule.
     *
     * @param ruleName the rule name
     * @return unmodifiable set of activated elements (empty if no activations)
     */
    public Set<EObject> getActivated(String ruleName) {
        Set<EObject> set = activations.get(ruleName);
        if (set == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Check if any elements were activated for a rule.
     *
     * @param ruleName the rule name
     * @return true if at least one element was activated
     */
    public boolean hasActivations(String ruleName) {
        Set<EObject> set = activations.get(ruleName);
        return set != null && !set.isEmpty();
    }

    /**
     * Get all rule names that have activations.
     *
     * @return unmodifiable set of rule names with activations
     */
    public Set<String> getRulesWithActivations() {
        return Collections.unmodifiableSet(activations.keySet());
    }

    /**
     * Get the total count of activations across all rules.
     *
     * @return total activation count
     */
    public int getTotalActivationCount() {
        return activations.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    /**
     * Clear all recorded activations.
     *
     * <p>This is useful for test cleanup or when reusing a context
     * for multiple transformations.</p>
     */
    public void clear() {
        activations.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ActivationTracker{");
        boolean first = true;
        for (var entry : activations.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue().size());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
