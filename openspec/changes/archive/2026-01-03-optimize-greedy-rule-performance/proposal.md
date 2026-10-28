# Proposal: Optimize Greedy Rule Performance

**Change ID:** optimize-greedy-rule-performance
**Status:** Complete
**Created:** 2026-01-03
**Updated:** 2026-01-03
**Blocked By:** fix-parallel-execution-race-conditions (RESOLVED)

## Related Proposals

| Proposal | Relationship |
|----------|--------------|
| `fix-parallel-execution-race-conditions` | **Blocks this proposal** - Race conditions must be fixed before optimizing performance. Optimizing lock mechanisms before ensuring thread safety could make corruption happen faster or hide bugs with different timing. |

> **Important:** This proposal's lock striping and reduced synchronization optimizations must be revisited after the race conditions fix. Any performance optimization that reduces locking must be validated against the thread safety requirements established by the blocking proposal.

## Current Performance (After XMI Optimization)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Total ZETA time | 40,844 ms | 9,007 ms | **-78%** |
| Greedy rules | 20,561 ms | 1,759 ms | **-91%** |
| vs ETL | 1.79x slower | **2.5x faster** | |

> **Note:** The main bottleneck (XMI resource lookups consuming 91% of greedy rule time) was solved by adding `skipXmiIdResourceLookup` flag.

## Deep Performance Analysis

### Current Transformation Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Phase 0: Source Element Collection                                            │
│                                                                               │
│   for each rule in registry.getAllRules():        O(r) = 50 rules            │
│       elements = context.all(alias, type)         O(n) model traversal!      │
│       singleSourceElements.addAll(elements)       O(m) per type              │
│                                                                               │
│   Total: O(r × n) = 50 × 22,000 = 1,100,000 element visits                   │
│   BUT: Same (alias, type) traversed multiple times!                          │
│   If 10 rules share EntityType: 10 × 22,000 = 220,000 wasted visits          │
└──────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Eager Rule Execution                                                 │
│                                                                               │
│   for each element in sourceElements:             O(n) = 10,000 elements     │
│       rules = registry.getRulesForSource(type)    O(1) [NOW CACHED]          │
│       for each rule in rules:                     O(r) = 30 applicable       │
│           if (rule.isMultiSource()) continue;     ─┐                         │
│           if (rule.isLazy()) continue;             │ 4 boolean checks        │
│           if (rule.isAbstract()) continue;         │ = 300,000 × 4           │
│           if (isActivityBased()) continue;        ─┘ = 1.2M checks           │
│           if (!rule.appliesTo(source)) continue;  O(1)                       │
│           if (!isFromExpectedAlias()) continue;   O(aliases)                 │
│           cache.getOrCreate(source, ruleName, λ)  LOCK ACQUISITION           │
│                                                                               │
│   Total: O(n × r) = 10,000 × 30 = 300,000 iterations                         │
└──────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│ Lock Acquisition Pattern (getOrCreate)                                        │
│                                                                               │
│   CacheKey key = new CacheKey(source, ruleName);  Object creation            │
│   if (rejectedKeys.contains(key)) return null;    Hash lookup                │
│   cached = getByRule(source, ruleName);           Hash lookup                │
│   if (cached != null) return cached;              Fast path                  │
│                                                                               │
│   lock = keyLocks.computeIfAbsent(key, λ);        300K lock objects!         │
│   lock.lock();                                    ~50-100ns each             │
│   try {                                                                       │
│       // double-check + execute                                               │
│   } finally {                                                                 │
│       lock.unlock();                                                          │
│   }                                                                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Bottleneck Breakdown

| Bottleneck | Current Cost | Optimization | Estimated Savings |
|------------|--------------|--------------|-------------------|
| **A. Repeated model traversal** | O(r × n) = 50 × 22K | Cache by (alias, type) | **10-20%** |
| **B. Rule lookup per element** | O(n × types) | Already cached (2.1) | **Done** |
| **C. Boolean checks per rule** | 1.2M checks | Pre-partition by phase | **~1%** |
| **D. Lock object creation** | 300K objects | Lock striping | **2-5%** |
| **E. CacheKey object creation** | 300K objects | Pooling/interning | **1-2%** |

## Optimization Details

### Optimization A: Cache Model Traversal Results (HIGH IMPACT)

**Problem:** Each rule calls `context.all(alias, type)` which traverses the entire model.
If 10 rules all transform `EntityType`, the model is traversed 10 times.

**Solution:** Cache by (alias, type) pair:

```java
// TransformationExecutor.java - element collection phase

private Map<String, Map<Class<?>, Collection<EObject>>> elementsByAliasAndType = new HashMap<>();

private Collection<? extends EObject> getCachedElements(String alias, Class<? extends EObject> type) {
    return elementsByAliasAndType
        .computeIfAbsent(alias, k -> new HashMap<>())
        .computeIfAbsent(type, t -> new ArrayList<>(context.all(alias, type)));
}

// In transform():
for (TransformRuleDescriptor rule : registry.getAllRules()) {
    if (!rule.isMultiSource()) {
        TransformDefinition transform = rule.getTransforms().get(0);
        // CACHED: Same (alias, type) returns same collection
        Collection<? extends EObject> elements = getCachedElements(
            transform.getAlias(), transform.getType());
        singleSourceElements.addAll(elements);
    }
}
```

**Impact:** For a model with 22K elements and 50 rules where 10 share the same type:
- Before: 50 × 22K = 1,100,000 element visits
- After: 10 × 22K = 220,000 element visits (unique types only)
- **Savings: 80% reduction in element collection phase**

### Optimization B: Rule Lookup Caching (DONE)

Already implemented in 2.1 with `ConcurrentHashMap.computeIfAbsent()`.

### Optimization C: Pre-Partition Rules by Phase (LOW IMPACT)

**Problem:** For each element, iterate all rules and check 4 boolean flags.

**Solution:** Pre-compute filtered lists:

```java
// TransformationRegistry.java

private volatile List<TransformRuleDescriptor> eagerGreedyRules;

public List<TransformRuleDescriptor> getEagerGreedyRules() {
    if (eagerGreedyRules == null) {
        synchronized (this) {
            if (eagerGreedyRules == null) {
                eagerGreedyRules = getAllRules().stream()
                    .filter(r -> !r.isMultiSource())
                    .filter(r -> !r.isLazy())
                    .filter(r -> !r.isAbstract())
                    .filter(r -> !isEffectivelyActivityBased(r))
                    .collect(Collectors.toUnmodifiableList());
            }
        }
    }
    return eagerGreedyRules;
}
```

**Impact:**
- Eliminates 1.2M boolean checks
- ~1% improvement (boolean checks are ~5ns each)

### Optimization D: Lock Striping (MEDIUM IMPACT)

**Problem:** Creating 300K `ReentrantLock` objects is expensive.

**Solution:** Use lock striping with fixed pool:

```java
// ElementResolutionCache.java

private static final int LOCK_STRIPE_COUNT = 1024;
private final ReentrantLock[] lockStripes = new ReentrantLock[LOCK_STRIPE_COUNT];

{
    for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
        lockStripes[i] = new ReentrantLock();
    }
}

private ReentrantLock getLockFor(EObject source, String ruleName) {
    int hash = System.identityHashCode(source) ^ ruleName.hashCode();
    return lockStripes[Math.abs(hash % LOCK_STRIPE_COUNT)];
}
```

**Impact:**
- Before: 300K lock objects created
- After: 1024 locks reused
- **Savings: 2-5% (reduced GC pressure + allocation)**

### Optimization E: Rule-Centric Batch Processing (MEDIUM IMPACT)

**Problem:** Element-centric iteration repeats type checks for each element.

**Solution:** Rule-centric iteration with pre-grouped elements:

```java
// ETL Compatibility OFF (new transformations)
private void executeEagerRulesRuleCentric(Collection<? extends EObject> sourceElements) {
    // Group elements by type once
    Map<Class<?>, List<EObject>> elementsByType = sourceElements.stream()
        .collect(Collectors.groupingBy(EObject::getClass));

    // Iterate rules first
    for (TransformRuleDescriptor rule : registry.getEagerGreedyRules()) {
        Class<?> sourceType = rule.getSourceType();

        // Get matching elements (exact + subtypes for greedy)
        List<EObject> matchingElements = rule.isGreedy()
            ? getElementsAssignableTo(elementsByType, sourceType)
            : elementsByType.getOrDefault(sourceType, Collections.emptyList());

        // Execute for all matching elements
        for (EObject source : matchingElements) {
            if (!isFromExpectedAlias(source, rule)) continue;
            executeRuleWithGetOrCreate(rule, source);
        }
    }
}
```

**Impact:**
- Better cache locality (process all elements for one rule before moving to next)
- Eliminates redundant `appliesTo()` checks (already filtered by type)
- **Savings: 5-10% for large models**

## Implementation Priority

| Priority | Optimization | Complexity | Impact | Status |
|----------|--------------|------------|--------|--------|
| 1 | B. Rule lookup caching | Low | High | **DONE** |
| 2 | A. Model traversal caching | Low | High | Pending |
| 3 | D. Lock striping | Medium | Medium | Pending |
| 4 | E. Rule-centric batch | Medium | Medium | Optional |
| 5 | C. Pre-partition rules | Low | Low | Optional |

## Expected Total Improvement

| Phase | Current Time | After Optimization | Improvement |
|-------|--------------|-------------------|-------------|
| Element collection | ~500 ms | ~100 ms | -80% |
| Rule lookup | ~200 ms | ~20 ms | -90% (DONE) |
| Rule execution | ~1,000 ms | ~900 ms | -10% |
| **Total greedy** | **~1,700 ms** | **~1,020 ms** | **-40%** |

Combined with XMI optimization already achieved:
- Before all optimizations: 20,561 ms
- After XMI optimization: 1,759 ms (-91%)
- After all optimizations: ~1,050 ms (-95% from original)

## Risks

1. **Cache invalidation complexity:** Model traversal cache must be cleared if model changes during transformation.
2. **Lock striping collisions:** With 1024 stripes, collision probability is low but non-zero.
3. **Rule-centric ordering:** Different execution order may affect edge cases (use only with ETL compatibility OFF).

## Success Criteria

1. All 444+ tests pass
2. ≥30% additional improvement in greedy rule execution time
3. Deterministic output (same XMI IDs)
4. No increase in memory usage beyond caches
