# Proposal: Optimize Greedy Rule Performance

**Change ID:** optimize-greedy-rule-performance
**Status:** Superseded
**Created:** 2026-01-03

> **Note:** The main bottleneck (XMI resource lookups consuming 91% of greedy rule time) was solved by adding `skipXmiIdResourceLookup` flag. ZETA is now 2.5x faster than ETL.
>
> The optimizations below (rule lookup caching, pre-partitioning) remain valid for potential future improvements if profiling reveals they become bottlenecks.

## Why

TransformationMetrics profiling shows that greedy rule execution consumes the majority of transformation time. For the RackInspect model (40,844 ms → 32,853 ms after XMI ID optimization), greedy rules still dominate the timing breakdown.

**Root Cause Analysis:**

1. **Rule Lookup O(n×r)** - `getRulesForSource()` is called for **every source element**, iterating through all registered source types:
   ```java
   for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
       if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
           for (TransformRuleDescriptor rule : entry.getValue()) {
               if (!result.contains(rule)) {  // O(n) ArrayList.contains()
                   result.add(rule);
               }
           }
       }
   }
   ```
   - For 10,000 elements × 100 source types = 1,000,000 `isAssignableFrom` checks!
   - `ArrayList.contains()` is O(n) in the hot path

2. **No Caching of Rule Lookups** - Same source type appears thousands of times, but lookup is recomputed every time. Type hierarchy is static after registration.

3. **Per-Element Per-Rule Flag Checks** - For each element × each applicable rule, checks:
   - `isMultiSource()`, `isLazy()`, `isAbstract()`, `isActivityBased()` - method calls on hot path
   - `isFromExpectedAlias()` - ResourceSet comparisons
   - `appliesTo()` - type checking with EClass name comparison for non-greedy

4. **Element-Centric Iteration** - Current approach:
   ```
   for each element:
       find applicable rules (O(r))
       for each rule:
           check flags, guards, execute
   ```

   This causes repeated rule lookups and poor cache locality.

**TransformationMetrics Evidence:**
```
=== TIMING BREAKDOWN ===
  equivalent() total:       32,853 ms
    - getRulesForSource:    X,XXX ms (significant portion)
    - Rule execution:       28,456 ms (86.6%)

=== TOP 10 SLOWEST GREEDY RULES ===
  Model2EPackage           2,345 ms (12 calls, 195.417 ms/call)
```

## What Changes

### Optimization 1: Cache Rule Lookups by Type

Add a concurrent cache to `TransformationRegistry` that memoizes `getRulesForSource()` results:

```java
private final ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache =
    new ConcurrentHashMap<>();

public Collection<TransformRuleDescriptor> getRulesForSource(Class<? extends EObject> sourceType) {
    return rulesBySourceTypeCache.computeIfAbsent(sourceType, this::computeRulesForSource);
}
```

**Impact:** Eliminates O(n×r) → O(1) for repeated lookups. Same type appears thousands of times in typical transformations.

### Optimization 2: Pre-Partition Rules by Phase

During registration, categorize rules into separate lists:

```java
private List<TransformRuleDescriptor> eagerGreedyRules;      // !lazy && !abstract && !multiSource && greedy
private List<TransformRuleDescriptor> eagerNonGreedyRules;   // !lazy && !abstract && !multiSource && !greedy
private List<TransformRuleDescriptor> lazyRules;             // lazy
private List<TransformRuleDescriptor> multiSourceRules;      // multiSource
private List<TransformRuleDescriptor> activityBasedRules;    // activityBased
```

**Impact:** Eliminates per-element per-rule flag checks. During execution, directly iterate the appropriate list.

### Optimization 3: Rule-Centric Batch Processing

Change from element-centric to rule-centric iteration:

**Current (O(n×r) lookups):**
```java
for (EObject source : sourceElements) {
    Collection<TransformRuleDescriptor> rules = registry.getRulesForSource(source.getClass());
    for (TransformRuleDescriptor rule : rules) {
        // execute rule for source
    }
}
```

**Optimized (O(r) lookups):**
```java
for (TransformRuleDescriptor rule : registry.getEagerGreedyRules()) {
    Collection<EObject> matchingSources = collectMatchingSources(rule, sourceElements);
    for (EObject source : matchingSources) {
        // execute rule for source
    }
}
```

**Impact:**
- Rule lookup happens once per rule, not once per element
- Better cache locality - process all elements for one rule before moving to next
- Parallel execution still works - partition elements per rule

### Optimization 4: Index Non-Greedy Rules by Exact Type Name

For non-greedy rules, type matching is exact. Pre-build an index:

```java
private Map<String, List<TransformRuleDescriptor>> nonGreedyRulesByTypeName = new HashMap<>();
```

**Impact:** `appliesTo()` for non-greedy rules becomes O(1) hash lookup instead of O(n) iteration with EClass name comparison.

### Optimization 5: Use LinkedHashSet for Rule Deduplication

Replace `ArrayList.contains()` with `LinkedHashSet`:

```java
// Before: O(n) contains
List<TransformRuleDescriptor> result = new ArrayList<>();
if (!result.contains(rule)) { result.add(rule); }

// After: O(1) add (auto-dedup)
Set<TransformRuleDescriptor> result = new LinkedHashSet<>();
result.add(rule);  // Preserves order, deduplicates in O(1)
```

## Approach

### Phase 1: Add Performance Baseline Tests
1. Create `GreedyRulePerformanceTest` with large model generation
2. Establish baseline metrics using TransformationMetrics
3. Assert regression boundaries (±10% tolerance)

### Phase 2: Implement Optimizations (Incremental)
1. **Cache rule lookups** - Lowest risk, highest impact
2. **Pre-partition rules** - Requires refactoring but safe
3. **Rule-centric batch** - Optional, for very large models
4. **Non-greedy index** - Optimize specific hot path

### Phase 3: Validate
1. Run existing 439+ tests
2. Run performance tests with large models
3. Compare TransformationMetrics before/after

## Scope

- `TransformationRegistry.java` - Add caching, pre-partition rules
- `TransformationExecutor.java` - Use cached/partitioned rules
- `TransformRuleDescriptor.java` - Optimize appliesTo() caching if needed
- New: `GreedyRulePerformanceTest.java` - Performance regression tests

## Complexity

**Low-Medium:** All optimizations are additive caching/indexing layers on top of existing logic. No semantic changes to transformation behavior.

## Success Criteria

1. **Performance:** 20-40% reduction in greedy rule execution time
2. **No regressions:** All 439+ existing tests pass
3. **Deterministic:** Results identical to before (same XMI IDs, same element order)
4. **Measurable:** TransformationMetrics shows reduced `getRulesForSource` time

## Risks

- **Memory overhead:** Caching adds memory usage. Mitigate with lazy initialization and weak references if needed.
- **Cache invalidation:** Registry is immutable after setup, so no invalidation needed.
- **Rule ordering:** Pre-partitioning must preserve registration order for deterministic execution.

## Alternatives Considered

1. **Parallel rule matching:** Rejected - adds complexity, limited benefit given cache hit
2. **JIT rule compilation:** Rejected - too complex, premature optimization
3. **Rule lazy loading:** Rejected - rules are cheap to load, execution is the bottleneck
