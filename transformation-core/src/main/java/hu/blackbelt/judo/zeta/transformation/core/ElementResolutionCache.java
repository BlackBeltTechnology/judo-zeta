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
 *
 * <p>Supports both parallel and sequential execution modes. In sequential mode,
 * locking is skipped entirely for maximum performance.</p>
 */
public class ElementResolutionCache {

    /**
     * Sequential mode flag. When true, all locking is bypassed for better performance.
     */
    private final boolean sequentialMode;

    // Map: source element → rule name → target instance
    // Uses IdentityHashMap in sequential mode for faster lookups
    private final Map<EObject, Map<String, EObject>> ruleCache;

    // Map: source element → target type name → target instances (for equivalents())
    private final Map<EObject, Map<String, List<EObject>>> typeCache;

    // Primary targets tracked separately for efficient equivalent() lookup
    private final Map<EObject, Map<String, EObject>> primaryCache;

    // Discriminated cache: source → rule name → discriminator → instance
    private final Map<EObject, Map<String, Map<String, EObject>>> discriminatedCache;

    /**
     * Per-key locks for atomic getOrCreate operations (parallel mode only).
     */
    private final Map<IdentityWrapper, Map<String, ReentrantLock>> ruleLocks;

    /**
     * Track rejected (source, ruleName) pairs to avoid re-evaluating guards.
     * Uses IdentityHashMap in sequential mode.
     */
    private final Map<EObject, Set<String>> rejectedKeysSequential;
    private final Map<IdentityWrapper, Set<String>> rejectedKeysParallel;

    /**
     * Wrapper for EObject that uses identity-based hashCode and equals.
     * Only used in parallel mode.
     */
    private static final class IdentityWrapper {
        private final EObject object;
        private final int hash;

        IdentityWrapper(EObject object) {
            this.object = object;
            this.hash = System.identityHashCode(object);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityWrapper)) return false;
            return object == ((IdentityWrapper) o).object;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Create cache for parallel execution (default, backward compatible).
     */
    public ElementResolutionCache() {
        this(false);
    }

    /**
     * Create cache with specified execution mode.
     *
     * @param sequentialMode true for sequential execution (no locking),
     *                       false for parallel execution (with locking)
     */
    public ElementResolutionCache(boolean sequentialMode) {
        this.sequentialMode = sequentialMode;

        if (sequentialMode) {
            // Sequential mode: use IdentityHashMap for O(1) identity lookups, no locking needed
            this.ruleCache = new IdentityHashMap<>();
            this.typeCache = new IdentityHashMap<>();
            this.primaryCache = new IdentityHashMap<>();
            this.discriminatedCache = new IdentityHashMap<>();
            this.rejectedKeysSequential = new IdentityHashMap<>();
            this.rejectedKeysParallel = null;
            this.ruleLocks = null;
        } else {
            // Parallel mode: use ConcurrentHashMap for thread safety
            this.ruleCache = new ConcurrentHashMap<>();
            this.typeCache = new ConcurrentHashMap<>();
            this.primaryCache = new ConcurrentHashMap<>();
            this.discriminatedCache = new ConcurrentHashMap<>();
            this.rejectedKeysSequential = null;
            this.rejectedKeysParallel = new ConcurrentHashMap<>();
            this.ruleLocks = new ConcurrentHashMap<>();
        }
    }

    /**
     * A no-op lock that always succeeds immediately.
     * Used in sequential mode to avoid null checks in all lock usages.
     */
    private static final ReentrantLock NO_OP_LOCK = new ReentrantLock() {
        @Override
        public void lock() {
            // No-op in sequential mode
        }

        @Override
        public void unlock() {
            // No-op in sequential mode
        }

        @Override
        public boolean tryLock() {
            return true;  // Always succeeds
        }

        @Override
        public boolean tryLock(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;  // Always succeeds
        }

        @Override
        public void lockInterruptibly() {
            // No-op in sequential mode
        }
    };

    /**
     * Check if the cache is in sequential (non-parallel) mode.
     *
     * @return true if sequential mode is enabled
     */
    public boolean isSequentialMode() {
        return sequentialMode;
    }

    /**
     * Get the lock for a given (source, ruleName) pair.
     *
     * <p>In sequential mode, returns a no-op lock that always succeeds immediately,
     * avoiding the need for null checks in all lock usages.</p>
     *
     * @param source the source element
     * @param ruleName the rule name
     * @return the unique lock for this key (never null)
     */
    public ReentrantLock getLockFor(EObject source, String ruleName) {
        if (sequentialMode) {
            return NO_OP_LOCK;  // No-op lock for sequential mode
        }
        IdentityWrapper key = new IdentityWrapper(source);
        Map<String, ReentrantLock> ruleMap = ruleLocks.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        return ruleMap.computeIfAbsent(ruleName, k -> new ReentrantLock());
    }

    /**
     * Check if a (source, ruleName) pair has been rejected.
     *
     * @param source the source element
     * @param ruleName the rule name
     * @return true if previously rejected
     */
    private boolean isRejected(EObject source, String ruleName) {
        if (sequentialMode) {
            Set<String> rejected = rejectedKeysSequential.get(source);
            return rejected != null && rejected.contains(ruleName);
        } else {
            IdentityWrapper key = new IdentityWrapper(source);
            Set<String> rejected = rejectedKeysParallel.get(key);
            return rejected != null && rejected.contains(ruleName);
        }
    }

    /**
     * Mark a (source, ruleName) pair as rejected.
     *
     * @param source the source element
     * @param ruleName the rule name
     */
    private void markRejected(EObject source, String ruleName) {
        if (sequentialMode) {
            // Optimized: get() first, then put() only on miss (avoids computeIfAbsent overhead)
            Set<String> rejected = rejectedKeysSequential.get(source);
            if (rejected == null) {
                rejected = new HashSet<>();
                rejectedKeysSequential.put(source, rejected);
            }
            rejected.add(ruleName);
        } else {
            IdentityWrapper key = new IdentityWrapper(source);
            rejectedKeysParallel.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                    .add(ruleName);
        }
    }

    /**
     * Add a mapping from source to target.
     *
     * <p>In parallel mode, uses thread-safe collections. In sequential mode,
     * uses simple HashMap/ArrayList for better performance.</p>
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
        if (source == null || ruleName == null || target == null) {
            return;
        }
        String targetTypeName = target.eClass().getName();

        if (sequentialMode) {
            // Sequential mode: Optimized with get-then-put pattern (avoids computeIfAbsent overhead)

            // Rule cache: source -> ruleName -> target
            Map<String, EObject> ruleMap = ruleCache.get(source);
            if (ruleMap == null) {
                ruleMap = new HashMap<>();
                ruleCache.put(source, ruleMap);
            }
            ruleMap.put(ruleName, target);

            // Type cache: source -> targetType -> [targets]
            Map<String, List<EObject>> typeMap = typeCache.get(source);
            if (typeMap == null) {
                typeMap = new HashMap<>();
                typeCache.put(source, typeMap);
            }
            List<EObject> typeList = typeMap.get(targetTypeName);
            if (typeList == null) {
                typeList = new ArrayList<>();
                typeMap.put(targetTypeName, typeList);
            }
            typeList.add(target);

            // Primary cache: source -> targetType -> primary target
            if (isPrimary) {
                Map<String, EObject> primaryMap = primaryCache.get(source);
                if (primaryMap == null) {
                    primaryMap = new HashMap<>();
                    primaryCache.put(source, primaryMap);
                }
                primaryMap.put(targetTypeName, target);
            }
        } else {
            // Parallel mode: use thread-safe collections with computeIfAbsent for atomicity
            ruleCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                    .put(ruleName, target);

            typeCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(targetTypeName, k -> new CopyOnWriteArrayList<>())
                    .add(target);

            if (isPrimary) {
                primaryCache.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                        .put(targetTypeName, target);
            }
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
     * Atomic get-or-create operation.
     *
     * <p>In parallel mode, uses locking for thread safety. In sequential mode,
     * bypasses all locking for maximum performance.</p>
     *
     * @param source the source element
     * @param ruleName the transformation rule name
     * @param ruleExecutor supplier that executes the rule
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

        boolean metricsEnabled = TransformationMetrics.isEnabled();

        // FAST PATH: Check rule cache FIRST (with timing)
        long t0 = metricsEnabled ? System.nanoTime() : 0;
        T cached = getByRule(source, ruleName);
        if (metricsEnabled) {
            TransformationMetrics.addCacheLookupNanos(System.nanoTime() - t0);
        }
        if (cached != null) {
            if (metricsEnabled) {
                TransformationMetrics.recordCacheHit();
            }
            return cached;
        }

        // Check rejection cache (with timing)
        long t1 = metricsEnabled ? System.nanoTime() : 0;
        boolean rejected = isRejected(source, ruleName);
        if (metricsEnabled) {
            TransformationMetrics.addCacheRejectionCheckNanos(System.nanoTime() - t1);
        }
        if (rejected) {
            if (metricsEnabled) {
                TransformationMetrics.recordCacheRejectionHit();
            }
            return null;
        }

        if (sequentialMode) {
            // SEQUENTIAL MODE: No locking needed, execute directly
            if (metricsEnabled) {
                TransformationMetrics.recordCacheMiss();
            }
            T target = ruleExecutor.get();
            if (target != null) {
                long t2 = metricsEnabled ? System.nanoTime() : 0;
                addMapping(source, ruleName, target, isPrimary);
                if (metricsEnabled) {
                    TransformationMetrics.addCacheAddMappingNanos(System.nanoTime() - t2);
                }
            } else {
                long t3 = metricsEnabled ? System.nanoTime() : 0;
                markRejected(source, ruleName);
                if (metricsEnabled) {
                    TransformationMetrics.addCacheMarkRejectedNanos(System.nanoTime() - t3);
                }
            }
            return target;
        }

        // PARALLEL MODE: Use locking for thread safety
        ReentrantLock lock = getLockFor(source, ruleName);
        lock.lock();
        try {
            // Double-check after acquiring lock (with timing)
            long t2 = metricsEnabled ? System.nanoTime() : 0;
            cached = getByRule(source, ruleName);
            if (metricsEnabled) {
                TransformationMetrics.addCacheLookupNanos(System.nanoTime() - t2);
            }
            if (cached != null) {
                if (metricsEnabled) {
                    TransformationMetrics.recordCacheHit();
                }
                return cached;
            }

            long t3 = metricsEnabled ? System.nanoTime() : 0;
            rejected = isRejected(source, ruleName);
            if (metricsEnabled) {
                TransformationMetrics.addCacheRejectionCheckNanos(System.nanoTime() - t3);
            }
            if (rejected) {
                if (metricsEnabled) {
                    TransformationMetrics.recordCacheRejectionHit();
                }
                return null;
            }

            if (metricsEnabled) {
                TransformationMetrics.recordCacheMiss();
            }
            T target = ruleExecutor.get();
            if (target != null) {
                long t4 = metricsEnabled ? System.nanoTime() : 0;
                addMapping(source, ruleName, target, isPrimary);
                if (metricsEnabled) {
                    TransformationMetrics.addCacheAddMappingNanos(System.nanoTime() - t4);
                }
            } else {
                long t5 = metricsEnabled ? System.nanoTime() : 0;
                markRejected(source, ruleName);
                if (metricsEnabled) {
                    TransformationMetrics.addCacheMarkRejectedNanos(System.nanoTime() - t5);
                }
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
        // ConcurrentHashMap provides thread-safe visibility
        Map<String, EObject> primaryMap = primaryCache.get(source);
        if (primaryMap != null) {
            EObject primary = primaryMap.get(typeName);
            if (primary != null) {
                return targetType.cast(primary);
            }
            // Fall back to assignable type check in primary cache
            // Iterate directly over concurrent map values
            for (EObject primary2 : primaryMap.values()) {
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
            // Iterate directly over concurrent map values
            for (List<EObject> targets : typeMap.values()) {
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
        // Iterate directly over concurrent map values
        for (List<EObject> targets : typeMap.values()) {
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
        if (source == null || target == null || ruleName == null || discriminator == null) {
            return;
        }
        if (sequentialMode) {
            discriminatedCache
                    .computeIfAbsent(source, k -> new HashMap<>())
                    .computeIfAbsent(ruleName, k -> new HashMap<>())
                    .put(discriminator, target);
        } else {
            discriminatedCache
                    .computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(ruleName, k -> new ConcurrentHashMap<>())
                    .put(discriminator, target);
        }
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
     * <p>Also clears the rule locks to free memory.</p>
     */
    public void clear() {
        ruleCache.clear();
        typeCache.clear();
        primaryCache.clear();
        discriminatedCache.clear();
        if (sequentialMode) {
            rejectedKeysSequential.clear();
        } else {
            rejectedKeysParallel.clear();
            ruleLocks.clear();
        }
    }

    /**
     * Clear only the rejection cache (guard rejections).
     *
     * <p>Called at the start of each transformation to allow guards to be
     * re-evaluated. This is separate from clear() because we may want to
     * keep element mappings while clearing rejection tracking.</p>
     */
    public void clearRejections() {
        if (sequentialMode) {
            rejectedKeysSequential.clear();
        } else {
            rejectedKeysParallel.clear();
        }
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
