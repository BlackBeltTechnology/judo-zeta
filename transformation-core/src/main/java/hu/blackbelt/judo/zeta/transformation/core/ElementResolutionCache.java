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

import hu.blackbelt.judo.zeta.transformation.core.deferred.DeferredEObject;
import org.eclipse.emf.ecore.EObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
     * Lock striping for atomic getOrCreate operations.
     *
     * <p>Uses a fixed array of 1024 locks instead of per-key locks. This reduces
     * memory allocation from O(n × r) = 300K lock objects for large models to
     * exactly 1024 locks (~40KB fixed memory).</p>
     *
     * <p>Lock selection uses hash of (source identity, ruleName) to distribute
     * load evenly across stripes. With 1024 stripes and uniform hash distribution,
     * collision probability is low for typical workloads.</p>
     */
    private static final int LOCK_STRIPE_COUNT = 1024;
    private final ReentrantLock[] lockStripes;

    // Track rejected (source, ruleName) pairs to avoid re-evaluating guards
    private final Set<CacheKey> rejectedKeys = ConcurrentHashMap.newKeySet();

    public ElementResolutionCache() {
        // Initialize lock stripes
        lockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            lockStripes[i] = new ReentrantLock();
        }
    }

    /**
     * Get the lock stripe for a given (source, ruleName) pair.
     *
     * <p>Uses XOR of source identity hash and ruleName hash for better distribution.
     * Math.abs handles negative hash codes, modulo selects the stripe index.</p>
     *
     * @param source the source element
     * @param ruleName the rule name
     * @return the lock stripe for this key
     */
    public ReentrantLock getLockFor(EObject source, String ruleName) {
        int hash = System.identityHashCode(source) ^ ruleName.hashCode();
        return lockStripes[Math.abs(hash % LOCK_STRIPE_COUNT)];
    }

    /**
     * Key for per-element locking using (source identity, ruleName) pair.
     *
     * <p>Uses System.identityHashCode for source to ensure consistency
     * even if the source element's equals/hashCode are overridden.</p>
     */
    private static class CacheKey {
        private final int sourceIdentity;
        private final String ruleName;

        CacheKey(EObject source, String ruleName) {
            this.sourceIdentity = System.identityHashCode(source);
            this.ruleName = ruleName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return sourceIdentity == cacheKey.sourceIdentity &&
                    Objects.equals(ruleName, cacheKey.ruleName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceIdentity, ruleName);
        }
    }

    /**
     * Add a mapping from source to target.
     *
     * <p>Thread-safe: Uses CopyOnWriteArrayList for the type cache lists to allow
     * concurrent iteration during parallel transformation while adding new mappings.</p>
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
        // ConcurrentHashMap doesn't allow null keys or values - skip if any are null
        if (source == null || ruleName == null || target == null) {
            return;
        }
        String targetTypeName = target.eClass().getName();

        // Add to rule cache (for idempotent equivalent() calls)
        ruleCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .put(ruleName, target);

        // Add to type cache (for equivalents() by type)
        // Use CopyOnWriteArrayList for thread-safe iteration during parallel transformation
        typeCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(targetTypeName, k -> new CopyOnWriteArrayList<>())
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
        // ConcurrentHashMap doesn't allow null keys
        if (source == null || ruleName == null) {
            return null;
        }
        Map<String, EObject> ruleMap = ruleCache.get(source);
        if (ruleMap != null) {
            return (T) ruleMap.get(ruleName);
        }
        return null;
    }

    /**
     * Atomic get-or-create operation with lock striping.
     *
     * <p>This method provides atomic check-and-execute semantics for parallel
     * transformation. The lock covers the full execution: cache lookup, guard
     * evaluation, and rule execution. This prevents race conditions where
     * multiple threads could create duplicate target elements for the same source.</p>
     *
     * <p>The supplier is only invoked on cache miss. If the supplier returns null
     * (e.g., guard rejected), no mapping is added.</p>
     *
     * <p>Thread-safe: Uses lock striping with 1024 stripes to allow different
     * sources and rules to execute in parallel without blocking, while using
     * fixed memory (no per-key lock allocation).</p>
     *
     * @param source the source element
     * @param ruleName the transformation rule name
     * @param ruleExecutor supplier that executes the rule (called under lock)
     * @param isPrimary whether this is a primary transformation
     * @param <T> the target type
     * @return the cached or newly created target, or null if supplier returns null
     */
    @SuppressWarnings("unchecked")
    public <T extends EObject> T getOrCreate(
            EObject source,
            String ruleName,
            Supplier<T> ruleExecutor,
            boolean isPrimary
    ) {
        if (source == null || ruleName == null) {
            return null;
        }

        // Fast path: check cache without locking
        CacheKey key = new CacheKey(source, ruleName);

        // Check if previously rejected (guard returned null)
        if (rejectedKeys.contains(key)) {
            return null;
        }

        T cached = getByRule(source, ruleName);
        if (cached != null) {
            return cached;
        }

        // Acquire striped lock for atomic check-and-execute
        ReentrantLock lock = getLockFor(source, ruleName);
        lock.lock();
        try {
            // Double-check after acquiring lock (another thread may have completed)
            if (rejectedKeys.contains(key)) {
                return null;
            }

            cached = getByRule(source, ruleName);
            if (cached != null) {
                return cached;
            }

            // Execute rule under lock (includes guard evaluation)
            T target = ruleExecutor.get();
            if (target != null) {
                addMapping(source, ruleName, target, isPrimary);
            } else {
                // Cache the rejection to prevent redundant guard evaluation
                rejectedKeys.add(key);
            }
            return target;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the equivalent target for a source element.
     * Returns the primary target if available, otherwise the first target of the type.
     *
     * <p>This method supports type hierarchy lookups. If an EDataType is cached
     * and EClassifier is requested, the EDataType will be returned since
     * EDataType extends EClassifier.</p>
     *
     * <p>Thread-safe: Uses snapshot-based iteration to avoid ConcurrentModificationException
     * during parallel transformation.</p>
     *
     * @param source the source element
     * @param targetType the target type class
     * @param <T> the target type
     * @return the equivalent target, or null if not found
     */
    public <T extends EObject> T getEquivalent(EObject source, Class<T> targetType) {
        String typeName = getTypeName(targetType);

        // Check primary cache first (exact match)
        Map<String, EObject> primaryMap = primaryCache.get(source);
        if (primaryMap != null) {
            EObject primary = primaryMap.get(typeName);
            if (primary != null) {
                return targetType.cast(primary);
            }
            // Fall back to assignable type check in primary cache
            // Use snapshot to avoid ConcurrentModificationException
            for (EObject primary2 : new ArrayList<>(primaryMap.values())) {
                if (targetType.isInstance(primary2)) {
                    return targetType.cast(primary2);
                }
            }
        }

        // Check for assignable types in type cache (e.g., EDataType when requesting EClassifier)
        // This handles cases where EDataType is cached but EClassifier is requested
        Map<String, List<EObject>> typeMap = typeCache.get(source);
        if (typeMap != null) {
            // First try exact type match
            List<EObject> exactTargets = typeMap.get(typeName);
            if (exactTargets != null && !exactTargets.isEmpty()) {
                return targetType.cast(exactTargets.get(0));
            }
            // Fall back to assignable type check
            // CopyOnWriteArrayList is already safe for iteration, but we need snapshot of typeMap.values()
            for (List<EObject> targets : new ArrayList<>(typeMap.values())) {
                for (EObject target : targets) {
                    if (targetType.isInstance(target)) {
                        return targetType.cast(target);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get all equivalent targets for a source element of a given type.
     *
     * <p>This method supports type hierarchy lookups. If an EDataType is cached
     * and EClassifier is requested, the EDataType will be returned since
     * EDataType extends EClassifier.</p>
     *
     * <p>Thread-safe: Uses snapshot-based iteration to avoid ConcurrentModificationException
     * during parallel transformation.</p>
     *
     * @param source the source element
     * @param targetType the target type class
     * @param <T> the target type
     * @return list of equivalent targets (includes subtypes)
     */
    public <T extends EObject> List<T> getEquivalents(EObject source, Class<T> targetType) {
        Map<String, List<EObject>> typeMap = typeCache.get(source);

        if (typeMap == null) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>();
        
        // Check all cached targets for type assignability
        // This handles cases where EDataType is cached but EClassifier is requested
        // Use snapshot of typeMap.values() to avoid ConcurrentModificationException
        for (List<EObject> targets : new ArrayList<>(typeMap.values())) {
            // CopyOnWriteArrayList is already safe for iteration
            for (EObject target : targets) {
                if (targetType.isInstance(target)) {
                    result.add(targetType.cast(target));
                }
            }
        }
        
        return result;
    }

    /**
     * Add a discriminated mapping.
     *
     * @param source the source element
     * @param target the target element (null targets are not cached)
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
        // ConcurrentHashMap doesn't allow null keys or values - skip if any are null
        if (source == null || target == null || ruleName == null || discriminator == null) {
            return;
        }
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
        // ConcurrentHashMap doesn't allow null keys
        if (source == null || ruleName == null || discriminator == null) {
            return null;
        }
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
     * Unwrap all proxy objects stored in the cache.
     *
     * <p>After parallel transformation with deferred writes, the cache may contain
     * JDK proxy objects (implementing ProxyMarker) instead of real EMF objects.
     * This method replaces all cached proxies with their underlying delegates.</p>
     *
     * <p>This is critical because:</p>
     * <ol>
     *   <li>Proxies implement EMF interfaces but cannot extend *Impl classes</li>
     *   <li>EMF internal code (e.g., EClassImpl.getEAllOperations()) casts to *Impl</li>
     *   <li>If equivalent() returns a cached proxy after transformation, and that
     *       proxy is added to a containment reference, EMF will crash with ClassCastException</li>
     * </ol>
     *
     * @return the number of proxy references that were unwrapped
     */
    public int unwrapAllProxies() {
        int count = 0;

        // Unwrap ruleCache: Map<EObject, Map<String, EObject>>
        for (Map<String, EObject> ruleMap : ruleCache.values()) {
            for (Map.Entry<String, EObject> entry : ruleMap.entrySet()) {
                EObject target = entry.getValue();
                if (target instanceof DeferredEObject.ProxyMarker) {
                    EObject unwrapped = DeferredEObject.unwrap(target);
                    entry.setValue(unwrapped);
                    count++;
                }
            }
        }

        // Unwrap typeCache: Map<EObject, Map<String, List<EObject>>>
        for (Map<String, List<EObject>> typeMap : typeCache.values()) {
            for (List<EObject> targets : typeMap.values()) {
                for (int i = 0; i < targets.size(); i++) {
                    EObject target = targets.get(i);
                    if (target instanceof DeferredEObject.ProxyMarker) {
                        EObject unwrapped = DeferredEObject.unwrap(target);
                        targets.set(i, unwrapped);
                        count++;
                    }
                }
            }
        }

        // Unwrap primaryCache: Map<EObject, Map<String, EObject>>
        for (Map<String, EObject> primaryMap : primaryCache.values()) {
            for (Map.Entry<String, EObject> entry : primaryMap.entrySet()) {
                EObject target = entry.getValue();
                if (target instanceof DeferredEObject.ProxyMarker) {
                    EObject unwrapped = DeferredEObject.unwrap(target);
                    entry.setValue(unwrapped);
                    count++;
                }
            }
        }

        // Unwrap discriminatedCache: Map<EObject, Map<String, Map<String, EObject>>>
        for (Map<String, Map<String, EObject>> ruleMap : discriminatedCache.values()) {
            for (Map<String, EObject> discMap : ruleMap.values()) {
                for (Map.Entry<String, EObject> entry : discMap.entrySet()) {
                    EObject target = entry.getValue();
                    if (target instanceof DeferredEObject.ProxyMarker) {
                        EObject unwrapped = DeferredEObject.unwrap(target);
                        entry.setValue(unwrapped);
                        count++;
                    }
                }
            }
        }

        return count;
    }

    /**
     * Clear all caches.
     *
     * <p>Note: Lock stripes are not cleared as they are a fixed array that
     * is reused across transformations.</p>
     */
    public void clear() {
        ruleCache.clear();
        typeCache.clear();
        primaryCache.clear();
        discriminatedCache.clear();
        rejectedKeys.clear();
    }

    /**
     * Clear only the rejection cache (guard rejections).
     *
     * <p>Called at the start of each transformation to allow guards to be
     * re-evaluated. This is separate from clear() because we may want to
     * keep element mappings while clearing rejection tracking.</p>
     */
    public void clearRejections() {
        rejectedKeys.clear();
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
