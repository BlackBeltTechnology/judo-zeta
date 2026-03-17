# Design: Cache Lookup Performance Optimization

**Updated:** 2026-01-15 (Based on Validated Benchmark Results)

## Critical Finding: Map Lookup is NOT the Bottleneck

**Benchmark Results (CacheLookupBenchmarkTest):**

```
Pure lookup benchmark (40,000 operations):
  Two-level map:    3 ms  (75 ns/op)
  Array-based:      0 ms  (< 1 ns/op)

Reported "cache ops": 2,383 ms (59,575 ns/op)
Discrepancy:          793x slower than pure lookup!
```

**This means 99.87% of "cache ops" time is NOT map lookups.**

## Architecture Analysis

### Current ElementResolutionCache Structure

```
ruleCache: Map<EObject, Map<String, EObject>>
           └── IdentityHashMap (sequential mode) → 75ns/lookup
                    └── HashMap (inner map)

typeCache: Map<EObject, Map<String, List<EObject>>>
primaryCache: Map<EObject, Map<String, EObject>>
```

### What "Cache Ops (Exclusive)" Actually Measures

The metric `cacheGetOrCreateNanos` is recorded in `TransformationExecutor.executeRulesOnElement()`:

```java
// Lines 876-896 in TransformationExecutor.java
long cacheStart = System.nanoTime();
context.getElementResolutionCache().getOrCreate(
    source, ruleName,
    () -> {
        // Guard evaluation (counted as part of cache ops!)
        if (!rule.evaluateGuard(source, context)) {
            return null;
        }
        // Rule execution (subtracted as greedyRuleNanos)
        return rule.execute(source, context);
    },
    isPrimary
);
TransformationMetrics.addCacheGetOrCreateNanos(System.nanoTime() - cacheStart);
```

**The "cache ops" metric INCLUDES:**
1. Pure map lookup (~3ms total)
2. Rejection check (~2ms total)
3. **Guard evaluation** (7,487 evaluations)
4. **addMapping() with computeIfAbsent** (7,487 calls)
5. **System.nanoTime() overhead** (~40K calls)

**The "cache ops" metric EXCLUDES:**
- Greedy rule execution (subtracted as `greedyRuleNanos`)

## Hypothesis: Where is the 2,380ms?

| Component | Estimated Time | Basis |
|-----------|----------------|-------|
| Pure map lookup | ~3ms | Benchmarked |
| Rejection check | ~2ms | ~50% of lookup |
| Guard evaluation | ~500-1000ms | 7,487 calls × 67-133μs |
| addMapping computeIfAbsent | ~200-400ms | 7,487 calls × 27-53μs |
| System.nanoTime() overhead | ~100-200ms | ~80K calls × 1.25-2.5μs |
| **Unknown overhead** | ~800-1500ms | Needs profiling |

**Total estimated: ~1,605-3,100ms** (overlaps with reported 2,383ms)

## Validated Assumptions

### ❌ INVALID: Two-Level Map is Slower
**Actual**: Two-level (IdentityHashMap → HashMap) is **18.6% FASTER** than single-level composite key.

**Reason**: IdentityHashMap uses identity comparison (==) which is faster than hashCode/equals.

### ✅ VALID: computeIfAbsent Has Overhead
**Actual**: computeIfAbsent is **108% slower** than get() for existing keys.

**Implication**: Replace `computeIfAbsent` with `get()` first, then `put()` on miss.

### ✅ VALID: Array-Based is Fastest
**Actual**: Array-based lookup is **7.85x faster** than two-level map.

**Implication**: Only saves ~3ms total. Not worth the complexity.

### ✅ VALID: String Interning Helps
**Actual**: Identity comparison (==) is **44% faster** than equals().

**Implication**: Marginal benefit. Low priority.

## Revised Design Approach

### Priority 1: Investigate the 793x Discrepancy

Before optimizing, we MUST understand where the time goes:

```java
// Add fine-grained timing to getOrCreate()
public <T extends EObject> T getOrCreate(EObject source, String ruleName, ...) {
    long t0 = System.nanoTime();

    // Step 1: Cache lookup
    T cached = getByRule(source, ruleName);
    long t1 = System.nanoTime();
    if (cached != null) {
        metrics.addCacheLookupNanos(t1 - t0);
        return cached;
    }

    // Step 2: Rejection check
    if (isRejected(source, ruleName)) {
        metrics.addRejectionCheckNanos(System.nanoTime() - t1);
        return null;
    }
    long t2 = System.nanoTime();

    // Step 3: Execute rule (includes guard)
    T target = ruleExecutor.get();
    long t3 = System.nanoTime();

    // Step 4: Add mapping
    if (target != null) {
        addMapping(source, ruleName, target, isPrimary);
    }
    long t4 = System.nanoTime();

    metrics.addCacheLookupNanos(t1 - t0);
    metrics.addRejectionCheckNanos(t2 - t1);
    metrics.addRuleExecutorNanos(t3 - t2);
    metrics.addMappingNanos(t4 - t3);

    return target;
}
```

### Priority 2: Eliminate computeIfAbsent Overhead

Current:
```java
ruleCache.computeIfAbsent(source, k -> new HashMap<>())
         .put(ruleName, target);
```

Optimized:
```java
Map<String, EObject> ruleMap = ruleCache.get(source);
if (ruleMap == null) {
    ruleMap = new HashMap<>();
    ruleCache.put(source, ruleMap);
}
ruleMap.put(ruleName, target);
```

**Expected benefit**: 50% reduction in addMapping overhead.

### Priority 3: Inline Rejection with Sentinel

```java
private static final EObject REJECTED = new EObjectImpl() {};

// Store rejection in same map as cache hits
public void markRejected(EObject source, String ruleName) {
    ruleCache.computeIfAbsent(source, k -> new HashMap<>())
             .put(ruleName, REJECTED);
}

// Single lookup handles both
public <T extends EObject> T getOrCreate(EObject source, String ruleName, ...) {
    Map<String, EObject> ruleMap = ruleCache.get(source);
    if (ruleMap != null) {
        EObject cached = ruleMap.get(ruleName);
        if (cached == REJECTED) return null;
        if (cached != null) return (T) cached;
    }
    // Cache miss...
}
```

**Expected benefit**: Eliminates separate rejection map lookup.

### Priority 4: Guard Optimization (If Needed)

If Phase 1 shows guard evaluation is significant, consider:
1. Caching guard results per (source, rule) pair
2. Pre-computing static guard conditions
3. Inlining simple type checks

## NOT Recommended

### Array-Based Cache (Low Benefit)
- Saves only ~3ms
- Requires rule ordinal assignment
- Adds complexity for minimal gain

### Composite Key Cache (Worse Performance)
- Benchmark shows it's 18.6% slower
- IdentityHashMap is already optimal

## Validation

Run with fine-grained timing to validate each optimization:

```bash
mvn test -pl judo-tatami-psm2asm \
    -Dtest=Psm2AsmExternalModelTest \
    -Djacoco.skip=true \
    -Dperformance.verbose=true
```

## Realistic Target

| Metric | Current | Target | Notes |
|--------|---------|--------|-------|
| Cache ops time | 2,383ms | 1,000-1,500ms | ~40-60% reduction |
| Total time | 3,109ms | ~2,000ms | ~35% reduction |
| Speedup vs ETL | 9.22x | ~14x | Conservative |

**Note**: 3x improvement target was based on invalid assumptions. 1.5-2x is realistic.
