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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for transformation trace (source → target mappings).
 *
 * <p>Provides idempotent transformation guarantees by caching results.
 * Multiple calls with the same source and rule return the same cached target.</p>
 */
public class ElementResolutionCache {

    // Map: source element → rule name → target instance
    private final Map<EObject, Map<String, EObject>> ruleCache = new ConcurrentHashMap<>();

    // Map: source element → target type name → target instances (for equivalents())
    private final Map<EObject, Map<String, List<EObject>>> typeCache = new ConcurrentHashMap<>();

    // Primary targets tracked separately for efficient equivalent() lookup
    private final Map<EObject, Map<String, EObject>> primaryCache = new ConcurrentHashMap<>();

    // Discriminated cache: source → rule name → discriminator → instance
    private final Map<EObject, Map<String, Map<String, EObject>>> discriminatedCache = new ConcurrentHashMap<>();

    /**
     * Add a mapping from source to target.
     *
     * @param source the source element
     * @param ruleName the transformation rule name
     * @param target the target element
     * @param isPrimary whether this is a primary transformation
     * @param <T> the target type
     */
    public <T extends EObject> void addMapping(
            EObject source,
            String ruleName,
            T target,
            boolean isPrimary
    ) {
        String targetTypeName = target.eClass().getName();

        // Add to rule cache (for idempotent equivalent() calls)
        ruleCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .put(ruleName, target);

        // Add to type cache (for equivalents() by type)
        typeCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(targetTypeName, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(target);

        // Add to primary cache if marked
        if (isPrimary) {
            primaryCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                    .put(targetTypeName, target);
        }
    }

    /**
     * Get cached target by rule name.
     *
     * @param source the source element
     * @param ruleName the rule name
     * @param <T> the target type
     * @return the cached target, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T getByRule(EObject source, String ruleName) {
        Map<String, EObject> ruleMap = ruleCache.get(source);
        if (ruleMap != null) {
            return (T) ruleMap.get(ruleName);
        }
        return null;
    }

    /**
     * Get the equivalent target for a source element.
     * Returns the primary target if available, otherwise the first target of the type.
     *
     * @param source the source element
     * @param targetType the target type class
     * @param <T> the target type
     * @return the equivalent target, or null if not found
     */
    public <T extends EObject> T getEquivalent(EObject source, Class<T> targetType) {
        String typeName = getTypeName(targetType);

        // Check primary cache first
        Map<String, EObject> primaryMap = primaryCache.get(source);
        if (primaryMap != null) {
            EObject primary = primaryMap.get(typeName);
            if (primary != null) {
                return targetType.cast(primary);
            }
        }

        // Fall back to first from type cache
        Map<String, List<EObject>> typeMap = typeCache.get(source);
        if (typeMap != null) {
            List<EObject> targets = typeMap.get(typeName);
            if (targets != null && !targets.isEmpty()) {
                return targetType.cast(targets.get(0));
            }
        }

        return null;
    }

    /**
     * Get all equivalent targets for a source element of a given type.
     *
     * @param source the source element
     * @param targetType the target type class
     * @param <T> the target type
     * @return list of equivalent targets
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> List<T> getEquivalents(EObject source, Class<T> targetType) {
        String typeName = getTypeName(targetType);
        Map<String, List<EObject>> typeMap = typeCache.get(source);

        if (typeMap == null) {
            return Collections.emptyList();
        }

        List<EObject> targets = typeMap.get(typeName);
        if (targets == null) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(targets.size());
        for (EObject target : targets) {
            result.add((T) target);
        }
        return result;
    }

    /**
     * Add a discriminated mapping.
     *
     * @param source the source element
     * @param target the target element
     * @param ruleName the rule name
     * @param discriminator the discriminator value
     * @param <T> the target type
     */
    public <T extends EObject> void addDiscriminatedMapping(
            EObject source,
            T target,
            String ruleName,
            String discriminator
    ) {
        discriminatedCache
                .computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ruleName, k -> new ConcurrentHashMap<>())
                .put(discriminator, target);
    }

    /**
     * Get a discriminated equivalent.
     *
     * @param source the source element
     * @param targetType the target type class
     * @param ruleName the rule name
     * @param discriminator the discriminator value
     * @param <T> the target type
     * @return the cached target, or null if not found
     */
    public <T extends EObject> T getEquivalentDiscriminated(
            EObject source,
            Class<T> targetType,
            String ruleName,
            String discriminator
    ) {
        Map<String, Map<String, EObject>> ruleMap = discriminatedCache.get(source);
        if (ruleMap != null) {
            Map<String, EObject> discMap = ruleMap.get(ruleName);
            if (discMap != null && discMap.containsKey(discriminator)) {
                return targetType.cast(discMap.get(discriminator));
            }
        }
        return null;
    }

    /**
     * Get all mappings for trace export.
     *
     * @return collection of all trace entries
     */
    public Collection<TraceEntry> getAllMappings() {
        List<TraceEntry> entries = new ArrayList<>();

        for (Map.Entry<EObject, Map<String, EObject>> sourceEntry : ruleCache.entrySet()) {
            EObject source = sourceEntry.getKey();
            for (Map.Entry<String, EObject> ruleEntry : sourceEntry.getValue().entrySet()) {
                String ruleName = ruleEntry.getKey();
                EObject target = ruleEntry.getValue();
                boolean isPrimary = isPrimaryMapping(source, target);
                entries.add(new TraceEntry(source, target, ruleName, null, isPrimary));
            }
        }

        // Add discriminated mappings
        for (Map.Entry<EObject, Map<String, Map<String, EObject>>> sourceEntry : discriminatedCache.entrySet()) {
            EObject source = sourceEntry.getKey();
            for (Map.Entry<String, Map<String, EObject>> ruleEntry : sourceEntry.getValue().entrySet()) {
                String ruleName = ruleEntry.getKey();
                for (Map.Entry<String, EObject> discEntry : ruleEntry.getValue().entrySet()) {
                    String discriminator = discEntry.getKey();
                    EObject target = discEntry.getValue();
                    entries.add(new TraceEntry(source, target, ruleName, discriminator, false));
                }
            }
        }

        return entries;
    }

    private boolean isPrimaryMapping(EObject source, EObject target) {
        Map<String, EObject> primaryMap = primaryCache.get(source);
        if (primaryMap != null) {
            String typeName = target.eClass().getName();
            return target.equals(primaryMap.get(typeName));
        }
        return false;
    }

    private String getTypeName(Class<? extends EObject> targetType) {
        return targetType.getSimpleName();
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        ruleCache.clear();
        typeCache.clear();
        primaryCache.clear();
        discriminatedCache.clear();
    }

    /**
     * Trace entry for export.
     */
    public static class TraceEntry {
        private final EObject source;
        private final EObject target;
        private final String ruleName;
        private final String discriminator;
        private final boolean primary;

        public TraceEntry(EObject source, EObject target, String ruleName, String discriminator, boolean primary) {
            this.source = source;
            this.target = target;
            this.ruleName = ruleName;
            this.discriminator = discriminator;
            this.primary = primary;
        }

        public EObject getSource() {
            return source;
        }

        public EObject getTarget() {
            return target;
        }

        public String getRuleName() {
            return ruleName;
        }

        public String getDiscriminator() {
            return discriminator;
        }

        public boolean isPrimary() {
            return primary;
        }
    }
}
