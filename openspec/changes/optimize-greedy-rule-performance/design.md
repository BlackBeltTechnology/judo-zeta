# Design: Optimize Greedy Rule Performance

## Current Performance (After XMI Optimization)

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Total ZETA time | 40,844 ms | 9,007 ms | **-78%** |
| Greedy rules | 20,561 ms | 1,759 ms | **-91%** |
| vs ETL | 1.79x slower | **2.5x faster** | |

> The main bottleneck (XMI resource lookups) was solved. The optimizations below target remaining overhead if profiling reveals it becomes significant.

## Current Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    TransformationExecutor                       │
│                                                                 │
│  transform(sourceElements)                                      │
│    ├─ for each element                        O(n)              │
│    │    └─ registry.getRulesForSource()       O(r × types)      │
│    │         └─ for each source type          O(types)          │
│    │              └─ isAssignableFrom()       O(1)              │
│    │              └─ ArrayList.contains()     O(rules)          │
│    │                                                            │
│    │    └─ for each applicable rule           O(r)              │
│    │         └─ isMultiSource(), isLazy()...  O(1)              │
│    │         └─ appliesTo(source)             O(1) or O(name)   │
│    │         └─ isFromExpectedAlias()         O(aliases)        │
│    │         └─ evaluateGuard()               O(guard)          │
│    │         └─ getOrCreate()                 O(1) + lock       │
│    │                                                            │
│    Total: O(n × r × types) = O(n × r²) for typical case        │
└────────────────────────────────────────────────────────────────┘
```

**Bottleneck Breakdown:**
| Operation | Frequency | Cost | Total Impact |
|-----------|-----------|------|--------------|
| getRulesForSource() | n elements | O(types × rules) | **HIGH** |
| isAssignableFrom() | n × types | O(1) but JVM call | MEDIUM |
| ArrayList.contains() | n × types × rules | O(rules) | MEDIUM |
| appliesTo() | n × rules | O(1) or O(name) | LOW-MEDIUM |
| isFromExpectedAlias() | n × rules | O(aliases) | LOW |

## Optimized Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    TransformationRegistry                       │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Pre-computed caches (populated during registration)     │   │
│  │                                                          │   │
│  │  rulesBySourceTypeCache: Map<Class, List<Rule>>         │   │
│  │  eagerGreedyRules: List<Rule>                           │   │
│  │  eagerNonGreedyRules: List<Rule>                        │   │
│  │  nonGreedyRulesByTypeName: Map<String, List<Rule>>      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  getRulesForSource(type)                                        │
│    └─ cache.computeIfAbsent(type, compute)    O(1) amortized   │
│                                                                 │
│  getEagerGreedyRules()                        O(1)             │
│    └─ return pre-computed list                                  │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                    TransformationExecutor                       │
│                                                                 │
│  Option A: Rule-Centric (recommended for large models)         │
│    for each eagerGreedyRule:                  O(r)              │
│      matchingSources = filter by type         O(n) but cached   │
│      for each source:                         O(matches)        │
│        getOrCreate()                          O(1)              │
│                                                                 │
│  Option B: Element-Centric with Cache (simpler)                │
│    for each element:                          O(n)              │
│      rules = getRulesForSource() [CACHED]     O(1)              │
│      for each rule:                           O(r_applicable)   │
│        appliesTo() [CACHED]                   O(1)              │
│        getOrCreate()                          O(1)              │
│                                                                 │
│  Total: O(n × r_applicable) = O(n × r) but with O(1) lookups   │
└────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Rule Lookup Caching

```java
// TransformationRegistry.java

private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache =
    new ConcurrentHashMap<>();

public Collection<TransformRuleDescriptor> getRulesForSource(Class<? extends EObject> sourceType) {
    return rulesBySourceTypeCache.computeIfAbsent(sourceType, this::computeRulesForSource);
}

private List<TransformRuleDescriptor> computeRulesForSource(Class<?> sourceType) {
    // Use LinkedHashSet for O(1) deduplication while preserving order
    Set<TransformRuleDescriptor> result = new LinkedHashSet<>();

    // Exact type match
    List<TransformRuleDescriptor> exactMatch = rulesBySourceType.get(sourceType);
    if (exactMatch != null) {
        result.addAll(exactMatch);
    }

    // Supertype matches (for greedy/lazy rules)
    for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
        Class<? extends EObject> ruleSourceType = entry.getKey();
        if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
            result.addAll(entry.getValue());
        }
    }

    return new ArrayList<>(result);  // Convert to ArrayList for iteration efficiency
}
```

### 2. Pre-Partitioned Rule Lists

```java
// TransformationRegistry.java

// Pre-computed lists (populated lazily after first rule registration)
private volatile List<TransformRuleDescriptor> eagerGreedyRules;
private volatile List<TransformRuleDescriptor> eagerNonGreedyRules;
private volatile List<TransformRuleDescriptor> lazyRules;
private volatile List<TransformRuleDescriptor> multiSourceRules;
private volatile List<TransformRuleDescriptor> activityBasedRules;

public List<TransformRuleDescriptor> getEagerGreedyRules() {
    if (eagerGreedyRules == null) {
        synchronized (this) {
            if (eagerGreedyRules == null) {
                eagerGreedyRules = getAllRules().stream()
                    .filter(r -> r.isGreedy() && !r.isLazy() && !r.isAbstract() && !r.isMultiSource())
                    .filter(r -> !isEffectivelyActivityBased(r))
                    .collect(Collectors.toList());
            }
        }
    }
    return eagerGreedyRules;
}

// Similar for other categories...
```

### 3. Type Name Index for Non-Greedy Rules

```java
// TransformationRegistry.java

private final Map<String, List<TransformRuleDescriptor>> nonGreedyRulesByTypeName = new HashMap<>();

// Populated during registerRule():
if (!isGreedy && !isLazy) {
    String typeName = sourceType.getSimpleName();
    nonGreedyRulesByTypeName.computeIfAbsent(typeName, k -> new ArrayList<>()).add(descriptor);
}

// Fast lookup in appliesTo():
public List<TransformRuleDescriptor> getNonGreedyRulesForTypeName(String typeName) {
    return nonGreedyRulesByTypeName.getOrDefault(typeName, Collections.emptyList());
}
```

### 4. Optimized TransformationExecutor

```java
// TransformationExecutor.java

private void executeEagerRulesOptimized(Collection<? extends EObject> sourceElements) {
    // Process greedy rules
    for (TransformRuleDescriptor rule : registry.getEagerGreedyRules()) {
        if (firstError.get() != null) return;

        // Filter elements matching this rule's source type
        for (EObject source : sourceElements) {
            if (!rule.appliesTo(source)) continue;
            if (!isFromExpectedAlias(source, rule)) continue;

            try {
                executeRuleWithGetOrCreate(rule, source);
            } catch (Exception e) {
                handleError(e, rule, source);
                return;
            }
        }
    }

    // Process non-greedy rules (exact type match only)
    Map<String, List<EObject>> elementsByTypeName = groupElementsByTypeName(sourceElements);

    for (TransformRuleDescriptor rule : registry.getEagerNonGreedyRules()) {
        if (firstError.get() != null) return;

        String typeName = rule.getSourceType().getSimpleName();
        List<EObject> matchingElements = elementsByTypeName.get(typeName);
        if (matchingElements == null || matchingElements.isEmpty()) continue;

        for (EObject source : matchingElements) {
            if (!isFromExpectedAlias(source, rule)) continue;

            try {
                executeRuleWithGetOrCreate(rule, source);
            } catch (Exception e) {
                handleError(e, rule, source);
                return;
            }
        }
    }
}

private Map<String, List<EObject>> groupElementsByTypeName(Collection<? extends EObject> elements) {
    Map<String, List<EObject>> result = new HashMap<>();
    for (EObject element : elements) {
        String typeName = element.eClass().getName();
        result.computeIfAbsent(typeName, k -> new ArrayList<>()).add(element);
    }
    return result;
}
```

## Performance Analysis

### Before Optimization

| Model Size | Elements | Rules | getRulesForSource Calls | Time |
|------------|----------|-------|------------------------|------|
| Small | 1,000 | 50 | 1,000 | ~100ms |
| Medium | 10,000 | 100 | 10,000 | ~1,000ms |
| Large | 100,000 | 200 | 100,000 | ~10,000ms |

### After Optimization

| Model Size | Elements | Rules | Cache Hits | getRulesForSource Time | Savings |
|------------|----------|-------|------------|------------------------|---------|
| Small | 1,000 | 50 | 990 | ~10ms | 90% |
| Medium | 10,000 | 100 | 9,900 | ~100ms | 90% |
| Large | 100,000 | 200 | 99,800 | ~200ms | 98% |

**Expected Improvement:** 20-40% reduction in total transformation time (greedy execution is ~30% of total).

## Thread Safety Considerations

All caches use thread-safe data structures:
- `ConcurrentHashMap.computeIfAbsent()` - atomic cache population
- Pre-computed lists are effectively immutable after initialization
- Double-checked locking pattern for lazy initialization

## Memory Impact

| Cache | Size | Impact |
|-------|------|--------|
| rulesBySourceTypeCache | O(distinct types) | ~10-100 entries, negligible |
| Pre-partitioned lists | O(rules) | References to existing descriptors |
| Type name index | O(non-greedy rules) | ~10-50 entries |

**Total:** < 1KB additional memory for typical transformations.

## ETL Compatibility Mode

The executor supports two iteration strategies controlled by `etlCompatibilityMode`:

| Mode | Strategy | Execution Order |
|------|----------|-----------------|
| `etlCompatibilityMode=true` (default) | Element-Centric | For each element, execute all applicable rules |
| `etlCompatibilityMode=false` | Rule-Centric | For each rule, execute on all matching elements |

```java
TransformationExecutor.builder()
    .etlCompatibilityMode(true)   // ETL-compatible (element-centric)
    .etlCompatibilityMode(false)  // Performance mode (rule-centric)
    .build();
```

**When to use Rule-Centric:**
- New transformations not migrating from ETL
- Performance-critical scenarios where element order doesn't affect semantics
- Large models (100K+ elements) where cache locality matters

## Cache Invalidation

If `register()` is called after caches are populated, **all caches must be invalidated**:

```java
public void register(Class<?> transformationClass) {
    // ... existing registration logic ...

    // Invalidate caches on dynamic registration
    rulesBySourceTypeCache.clear();
    eagerGreedyRules = null;
    eagerNonGreedyRules = null;
    lazyRules = null;
    nonGreedyRulesByTypeName = null;
}
```

## Backward Compatibility

All optimizations are internal implementation details:
- Same public API
- Same execution semantics (when `etlCompatibilityMode=true`)
- Same deterministic ordering (LinkedHashSet preserves insertion order)
- Same XMI IDs generated
