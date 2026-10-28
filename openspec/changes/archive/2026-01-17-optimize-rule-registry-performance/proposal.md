# Proposal: Optimize Rule Registry Performance

**Change ID:** optimize-rule-registry-performance
**Status:** Proposed
**Created:** 2026-01-04
**Updated:** 2026-01-04

## Related Proposals

| Proposal | Relationship |
|----------|--------------|
| `implement-thread-isolated-parallel-architecture` | **Alternative** - This proposal achieves better performance with less complexity. See ANALYSIS.md in that proposal for why thread isolation is problematic. |
| `add-executor-timing-instrumentation` | **Enabled by** - Metrics revealed the actual bottleneck this proposal addresses. |

## Problem Statement

Performance profiling revealed the **actual bottleneck** in Zeta transformations:

```
executeEagerRulesFor() is called 22,134 times (once per source element)
Each call iterates through ALL registered rules (~50+ rules)
= 22,134 × 50 = 1,106,700 rule checks per transformation
```

This O(n×r) complexity dominates transformation time. The current implementation:

```java
for (TransformRuleDescriptor rule : registry.getRulesForSource(source.getClass())) {
    if (rule.isMultiSource()) continue;      // Check 1
    if (rule.isLazy()) continue;             // Check 2
    if (rule.isAbstract()) continue;         // Check 3
    if (isEffectivelyActivityBased(rule)) continue;  // Check 4
    if (!rule.appliesTo(source)) continue;   // Check 5 (expensive)
    if (!isFromExpectedAlias(source, rule)) continue;  // Check 6
    // Finally execute if all checks pass
}
```

Each iteration performs 6 checks, most of which could be pre-computed.

## Proposed Solution: Pre-filtered Rule Index

Build an optimized index at rule registration time:

```
┌─────────────────────────────────────────────────────────────────┐
│  Current: O(n×r) - Check all rules for every element           │
│                                                                  │
│  for each element (22,000):                                     │
│    for each rule (50):        ← 1,100,000 iterations            │
│      6 conditional checks     ← 6,600,000 checks                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Proposed: O(n×m) where m << r - Pre-filtered by type          │
│                                                                  │
│  At registration time:                                          │
│    Build: EClass → [applicable eager rules] index               │
│                                                                  │
│  At transform time:                                             │
│    for each element (22,000):                                   │
│      for each applicable rule (~5):  ← 110,000 iterations       │
│        2 runtime checks              ← 220,000 checks           │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. New Pre-filtered Rule Index

```java
public class TransformRuleRegistry {
    // Existing
    private final List<TransformRuleDescriptor> allRules;

    // NEW: Pre-filtered index
    private final Map<EClass, List<TransformRuleDescriptor>> eagerRulesByType;
    private final Map<EClass, List<TransformRuleDescriptor>> lazyRulesByType;

    public void register(TransformRuleDescriptor rule) {
        allRules.add(rule);

        // Index by applicable source types
        if (!rule.isLazy() && !rule.isMultiSource() && !rule.isAbstract()) {
            indexEagerRule(rule);
        } else if (rule.isLazy() && !rule.isAbstract()) {
            indexLazyRule(rule);
        }
    }

    private void indexEagerRule(TransformRuleDescriptor rule) {
        // Get all EClasses this rule applies to (including subtypes)
        Set<EClass> applicableTypes = rule.getApplicableSourceTypes();
        for (EClass type : applicableTypes) {
            eagerRulesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(rule);
        }
    }

    // NEW: O(m) lookup where m << r
    public List<TransformRuleDescriptor> getApplicableEagerRules(EClass sourceType) {
        return eagerRulesByType.getOrDefault(sourceType, Collections.emptyList());
    }
}
```

### 2. Optimized executeEagerRulesFor

```java
private void executeEagerRulesFor(EObject source) {
    // O(1) lookup of pre-filtered rules
    List<TransformRuleDescriptor> rules = registry.getApplicableEagerRules(source.eClass());

    for (TransformRuleDescriptor rule : rules) {
        // Only 2 runtime checks remain:
        // 1. Activity-based check (depends on runtime state)
        if (isEffectivelyActivityBased(rule)) continue;

        // 2. Alias check (depends on source element location)
        if (!isFromExpectedAlias(source, rule)) continue;

        // Execute (pre-filtered rules already match type)
        executeRule(source, rule);
    }
}
```

### 3. Checks That Move to Registration Time

| Check | Current | Proposed |
|-------|---------|----------|
| `isMultiSource()` | Runtime | Registration |
| `isLazy()` | Runtime | Registration |
| `isAbstract()` | Runtime | Registration |
| `appliesTo(source)` | Runtime | Registration (indexed by type) |
| `isEffectivelyActivityBased()` | Runtime | **Runtime** (depends on config) |
| `isFromExpectedAlias()` | Runtime | **Runtime** (depends on element) |

## Expected Performance Impact

Based on metrics (22,134 elements, ~50 rules):

| Metric | Current | Optimized | Improvement |
|--------|---------|-----------|-------------|
| Rule iterations | 1,106,700 | ~110,670 | **10x fewer** |
| Type checks | 1,106,700 | 0 | **Eliminated** |
| Boolean checks | 4,426,800 | 221,340 | **20x fewer** |
| Estimated time | ~13,000 ms | ~2,500 ms | **5x faster** |

## Why This Is Better Than Thread Isolation

| Aspect | Thread Isolation | Rule Registry Optimization |
|--------|------------------|---------------------------|
| Complexity | High (new architecture) | Low (index optimization) |
| Risk | High (cross-partition refs) | Low (no semantic changes) |
| Memory | 2-3x increase | Minimal |
| Merge overhead | New bottleneck | None |
| Cross-partition refs | Major problem | N/A |
| Expected improvement | -31% (slower) | +80% (faster) |

## Success Criteria

1. Rule loop time reduced by >80%
2. All 500+ existing tests pass
3. No semantic changes to transformation behavior
4. Memory increase <10%
5. Sequential and parallel modes produce identical results

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Index invalidation | Rebuild index on dynamic rule changes |
| Memory for large type hierarchies | Limit index depth, use lazy population |
| Subtype matching complexity | Cache assignability checks |
