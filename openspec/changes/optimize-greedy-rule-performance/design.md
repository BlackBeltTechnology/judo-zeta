# Design: Optimize Greedy Rule Performance

## Current Performance (After XMI Optimization)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Total ZETA time | 40,844 ms | 9,007 ms | **-78%** |
| Greedy rules | 20,561 ms | 1,759 ms | **-91%** |
| vs ETL | 1.79x slower | **2.5x faster** | |

## Current Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         TransformationExecutor                              │
│                                                                             │
│  transform()                                                                │
│    │                                                                        │
│    ├─ Phase 0: Element Collection                                          │
│    │    for each rule:                              O(r) = 50 rules        │
│    │      elements = context.all(alias, type)       O(n) traversal EACH!   │
│    │      singleSourceElements.addAll(elements)                             │
│    │    PROBLEM: Same type traversed multiple times                        │
│    │                                                                        │
│    ├─ Phase 1: Eager Rule Execution                                        │
│    │    for each element:                           O(n) = 10,000          │
│    │      rules = registry.getRulesForSource()      O(1) [CACHED]          │
│    │      for each rule:                            O(r) = 30              │
│    │        if (isMultiSource) continue;            ─┐                     │
│    │        if (isLazy) continue;                    │ 4 checks            │
│    │        if (isAbstract) continue;                │                     │
│    │        if (isActivityBased) continue;          ─┘                     │
│    │        if (!appliesTo) continue;                                      │
│    │        if (!isFromExpectedAlias) continue;                            │
│    │        cache.getOrCreate()                     LOCK per (src,rule)    │
│    │                                                                        │
│    └─ Phase 2: Activity-Based Rules                                        │
│                                                                             │
│  Total: O(r × n) collection + O(n × r) execution                           │
└────────────────────────────────────────────────────────────────────────────┘
```

## Optimized Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         TransformationExecutor                              │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ NEW: Element Collection Cache                                         │  │
│  │                                                                        │  │
│  │  elementsByAliasAndType: Map<alias, Map<type, Collection<EObject>>>   │  │
│  │                                                                        │  │
│  │  getCachedElements(alias, type):                                       │  │
│  │    return cache.computeIfAbsent(alias, _)                              │  │
│  │                  .computeIfAbsent(type, _ -> context.all(alias,type)) │  │
│  │                                                                        │  │
│  │  BENEFIT: Each unique (alias, type) traversed only ONCE               │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  transform()                                                                │
│    │                                                                        │
│    ├─ Phase 0: Element Collection [OPTIMIZED]                              │
│    │    for each rule:                              O(r)                   │
│    │      elements = getCachedElements(alias, type) O(1) after first!     │
│    │      singleSourceElements.addAll(elements)                            │
│    │    TOTAL: O(unique_types × n) instead of O(r × n)                    │
│    │                                                                        │
│    ├─ Phase 1: Eager Rule Execution                                        │
│    │    for each element:                           O(n)                   │
│    │      rules = registry.getRulesForSource()      O(1) [CACHED]          │
│    │      for each rule:                            O(r)                   │
│    │        cache.getOrCreate()                     STRIPED LOCK           │
│    │                                                                        │
│    └─ Phase 2: Activity-Based Rules                                        │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│                         TransformationRegistry                              │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Rule Lookup Cache [IMPLEMENTED]                                       │  │
│  │                                                                        │  │
│  │  rulesBySourceTypeCache: ConcurrentHashMap<Class, List<Rule>>         │  │
│  │                                                                        │  │
│  │  getRulesForSource(type):                                              │  │
│  │    return cache.computeIfAbsent(type, this::computeRulesForSource)    │  │
│  │                                                                        │  │
│  │  computeRulesForSource(type):                                          │  │
│  │    LinkedHashSet for O(1) dedup + order preservation                   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ OPTIONAL: Pre-Partitioned Rule Lists                                  │  │
│  │                                                                        │  │
│  │  eagerGreedyRules: List<Rule>  (lazy-init, double-checked locking)   │  │
│  │  eagerNonGreedyRules: List<Rule>                                      │  │
│  │  lazyRules: List<Rule>                                                │  │
│  │                                                                        │  │
│  │  BENEFIT: Eliminates 4 boolean checks per rule per element            │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│                         ElementResolutionCache                              │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ CURRENT: Per-Key Locking                                              │  │
│  │                                                                        │  │
│  │  keyLocks: ConcurrentHashMap<CacheKey, ReentrantLock>                 │  │
│  │  PROBLEM: Creates 300K lock objects for large models                  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    ↓                                        │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ NEW: Lock Striping                                                    │  │
│  │                                                                        │  │
│  │  private static final int STRIPE_COUNT = 1024;                        │  │
│  │  private final ReentrantLock[] lockStripes = new ReentrantLock[1024]; │  │
│  │                                                                        │  │
│  │  getLockFor(source, ruleName):                                         │  │
│  │    hash = identityHashCode(source) ^ ruleName.hashCode()              │  │
│  │    return lockStripes[abs(hash % STRIPE_COUNT)]                       │  │
│  │                                                                        │  │
│  │  BENEFIT: 1024 locks instead of 300K                                  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Element Collection Cache (Priority: HIGH)

```java
// TransformationExecutor.java

// Cache for model traversal results (cleared after each transformation)
private Map<String, Map<Class<?>, Collection<EObject>>> elementsByAliasAndType;

private void initElementCache() {
    elementsByAliasAndType = new HashMap<>();
}

private void clearElementCache() {
    elementsByAliasAndType = null;
}

@SuppressWarnings("unchecked")
private <T extends EObject> Collection<T> getCachedElements(String alias, Class<T> type) {
    return (Collection<T>) elementsByAliasAndType
        .computeIfAbsent(alias, k -> new HashMap<>())
        .computeIfAbsent(type, t -> new ArrayList<>(context.all(alias, type)));
}

public TransformationResult transform() {
    reset();
    initElementCache();  // NEW

    try {
        // Element collection using cache
        for (TransformRuleDescriptor rule : registry.getAllRules()) {
            if (!rule.isMultiSource()) {
                TransformDefinition transform = rule.getTransforms().get(0);
                Collection<? extends EObject> elements = getCachedElements(
                    transform.getAlias(), transform.getType());  // CACHED
                singleSourceElements.addAll(elements);
            }
        }
        // ... rest of transformation
    } finally {
        clearElementCache();  // NEW
    }
}
```

### 2. Rule Lookup Cache (IMPLEMENTED)

```java
// TransformationRegistry.java

private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache =
    new ConcurrentHashMap<>();

public Collection<TransformRuleDescriptor> getRulesForSource(Class<? extends EObject> sourceType) {
    return rulesBySourceTypeCache.computeIfAbsent(sourceType, this::computeRulesForSource);
}

private List<TransformRuleDescriptor> computeRulesForSource(Class<?> sourceType) {
    Set<TransformRuleDescriptor> result = new LinkedHashSet<>();  // O(1) dedup

    result.addAll(rulesBySourceType.getOrDefault(sourceType, Collections.emptyList()));

    for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry :
            rulesBySourceType.entrySet()) {
        if (entry.getKey().isAssignableFrom(sourceType) && !entry.getKey().equals(sourceType)) {
            result.addAll(entry.getValue());
        }
    }

    return Collections.unmodifiableList(new ArrayList<>(result));
}

// Cache invalidation on dynamic registration
public void register(Class<?> transformationClass) {
    // ... registration logic ...
    rulesBySourceTypeCache.clear();  // Invalidate cache
}
```

### 3. Lock Striping (Priority: MEDIUM)

```java
// ElementResolutionCache.java

private static final int LOCK_STRIPE_COUNT = 1024;
private final ReentrantLock[] lockStripes;

public ElementResolutionCache() {
    lockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];
    for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
        lockStripes[i] = new ReentrantLock();
    }
}

private ReentrantLock getLockFor(EObject source, String ruleName) {
    int hash = System.identityHashCode(source) ^ ruleName.hashCode();
    return lockStripes[Math.abs(hash % LOCK_STRIPE_COUNT)];
}

public <T extends EObject> T getOrCreate(
        EObject source, String ruleName, Supplier<T> ruleExecutor, boolean isPrimary) {

    // Fast path unchanged
    CacheKey key = new CacheKey(source, ruleName);
    if (rejectedKeys.contains(key)) return null;
    T cached = getByRule(source, ruleName);
    if (cached != null) return cached;

    // Use striped lock instead of per-key lock
    ReentrantLock lock = getLockFor(source, ruleName);  // STRIPED
    lock.lock();
    try {
        // Double-check + execute (unchanged)
        if (rejectedKeys.contains(key)) return null;
        cached = getByRule(source, ruleName);
        if (cached != null) return cached;

        T target = ruleExecutor.get();
        if (target != null) {
            addMapping(source, ruleName, target, isPrimary);
        } else {
            rejectedKeys.add(key);
        }
        return target;
    } finally {
        lock.unlock();
    }
}
```

### 4. Pre-Partitioned Rules (Priority: LOW)

```java
// TransformationRegistry.java

private volatile List<TransformRuleDescriptor> eagerGreedyRules;
private volatile List<TransformRuleDescriptor> eagerNonGreedyRules;

public List<TransformRuleDescriptor> getEagerGreedyRules() {
    if (eagerGreedyRules == null) {
        synchronized (this) {
            if (eagerGreedyRules == null) {
                eagerGreedyRules = getAllRules().stream()
                    .filter(r -> r.isGreedy())
                    .filter(r -> !r.isLazy())
                    .filter(r -> !r.isAbstract())
                    .filter(r -> !r.isMultiSource())
                    .collect(Collectors.toUnmodifiableList());
            }
        }
    }
    return eagerGreedyRules;
}

// Invalidate on registration
private void invalidateCaches() {
    rulesBySourceTypeCache.clear();
    eagerGreedyRules = null;
    eagerNonGreedyRules = null;
}
```

## Performance Analysis

### Before All Optimizations

| Phase | Time | Complexity |
|-------|------|------------|
| Element collection | ~2,000 ms | O(r × n) = 50 × 22K |
| Rule lookup | ~500 ms | O(n × types) |
| Rule execution | ~18,000 ms | O(n × r) with XMI lookups |
| **Total** | **~20,500 ms** | |

### After XMI Optimization (External)

| Phase | Time | Improvement |
|-------|------|-------------|
| Element collection | ~500 ms | - |
| Rule lookup | ~200 ms | - |
| Rule execution | ~1,000 ms | **-94%** |
| **Total** | **~1,700 ms** | **-91%** |

### After All Proposed Optimizations

| Phase | Time | Additional Improvement |
|-------|------|------------------------|
| Element collection | ~100 ms | **-80%** (caching) |
| Rule lookup | ~20 ms | **-90%** (caching) |
| Rule execution | ~900 ms | **-10%** (lock striping) |
| **Total** | **~1,020 ms** | **-40%** |

## Thread Safety

| Component | Mechanism |
|-----------|-----------|
| rulesBySourceTypeCache | `ConcurrentHashMap.computeIfAbsent()` |
| elementsByAliasAndType | Thread-local per transformation |
| lockStripes | Fixed array, stripe selection by hash |
| eagerGreedyRules | Double-checked locking + volatile |

## Memory Impact

| Cache | Size | Impact |
|-------|------|--------|
| rulesBySourceTypeCache | O(distinct types) | ~10-100 entries |
| elementsByAliasAndType | O(distinct (alias,type) pairs) | Cleared after each transform |
| lockStripes | 1024 × ReentrantLock | ~40KB fixed |
| Pre-partitioned lists | O(rules) | References only |

**Total additional memory:** < 100KB for typical transformations.

## Backward Compatibility

All optimizations are internal implementation details:
- Same public API
- Same execution semantics
- Same deterministic ordering (LinkedHashSet preserves order)
- Same XMI IDs generated
