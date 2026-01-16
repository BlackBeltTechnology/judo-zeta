## Context

The circular dependency fix (commit `27f058b`) correctly enables `@Lazy` rule triggering from utility methods but adds overhead to transformation hot paths. Benchmarking shows cache operations consume 89.3% of execution time, with 93.1% of that time unaccounted (object allocation, map operations).

**Constraints:**
- Must maintain correctness of circular dependency handling
- Must not break existing 622 transformation-core tests
- Must preserve XMI ID generation compatibility
- Should target 2-3x performance improvement

**Stakeholders:**
- ZETA transformation framework users
- ESM2UI transformation (primary use case)

## Goals / Non-Goals

**Goals:**
- Reduce cache operation overhead by 50%+
- Reduce guard evaluations from 80,695 to ~10,000
- Eliminate object allocation in hot paths
- Maintain circular dependency correctness

**Non-Goals:**
- Changing the transformation execution model
- Modifying XMI ID generation semantics
- Adding new transformation features

## Decisions

### Decision 1: Use Rule Ordinals Instead of String Keys

**What:** Assign each rule a unique integer ordinal at registration time. Use this for cache lookups instead of String-based `RuleCacheKey`.

**Why:**
- Integer comparison is O(1) vs String hashCode computation
- Enables array-indexed storage: `EObject[ruleOrdinal]` instead of `Map<String, EObject>`
- Eliminates `RuleCacheKey` object allocation (currently created 30,012+ times per transformation)

**Alternatives considered:**
1. String interning - reduces String comparison cost but still requires Map lookup
2. Composite key caching - reuses key objects but adds complexity

### Decision 2: Integrate In-Progress Tracking into ResolutionCache

**What:** Add in-progress target tracking to existing `ResolutionCache` instead of separate `executingLazyRules` map.

**Why:**
- Single cache structure for all transformation state
- Leverages existing IdentityHashMap for source lookup
- Can share array storage with rule results

**Implementation:**
```java
// In ResolutionCache.TransformationState
class TransformationState {
    EObject[] targetsByRule;      // Completed results
    EObject[] inProgressByRule;   // Early-cached for circular deps
    boolean[] rejected;           // Guard rejection cache
}
```

### Decision 3: Lazy-Initialize Circular Dependency Tracking

**What:** Only allocate `inProgressByRule` array when recursion is first detected.

**Why:**
- Most transformations have no circular dependencies
- Avoid allocation overhead for the common case
- Only ~1% of calls actually need circular dependency handling

**Implementation:**
```java
// Only allocate when needed
if (recursionDetected && state.inProgressByRule == null) {
    state.inProgressByRule = new EObject[ruleCount];
}
```

### Decision 4: Cache Guard Results with Rejection Tracking

**What:** Use existing rejection tracking to cache guard evaluation results. Skip guard evaluation for sources already cached or rejected.

**Why:**
- 96% of guard evaluations result in rejection
- Same source-rule combination is checked multiple times
- Rejection result is stable (guard won't pass later if it failed before)

**Current flow:**
```
getOrCreate() → evaluateGuard() → reject or execute
                    ↑
                Called 80,695 times
```

**Optimized flow:**
```
getOrCreate() → check rejection cache → skip if rejected
                                      → evaluateGuard() if not cached
                                      → cache result
```

## Risks / Trade-offs

### High-Risk Side Effects

| Side Effect | Risk | Detection Test | Mitigation |
|-------------|------|----------------|------------|
| **Registration order changes ordinals** | HIGH | Test 0.1.1 - verify same output with different registration order | Sort rules by name before ordinal assignment |
| **Dynamic registration during transform** | HIGH | Test 0.1.2 - attempt registration mid-transform | Throw `IllegalStateException` if registry locked |
| **Stale in-progress targets returned** | HIGH | Test 0.2.1 - verify cleanup on completion | Clear in-progress in `finally` block of rule execution |
| **In-progress not cleared on exception** | HIGH | Test 0.2.2 - throw in rule, verify cleanup | Use try-finally pattern for all in-progress marking |
| **Thread race on recursion detection** | MEDIUM | Test 0.3.4 - parallel threads hitting same source | Use `compareAndSet` for lazy array initialization |
| **XMI ID order changes** | HIGH | Test 0.5.3 - byte-compare XMI output | Ensure deterministic iteration order in cache |

### Medium-Risk Side Effects

| Side Effect | Risk | Detection Test | Mitigation |
|-------------|------|----------------|------------|
| **Memory leak from in-progress tracking** | MEDIUM | Test 0.2.4 - verify GC after transform | Weak references or explicit `clear()` method |
| **Guard depends on mutable state** | MEDIUM | Test 0.4.1 - guard checks transformation phase | Document that guards MUST be pure functions |
| **Non-deterministic guard order** | MEDIUM | Test 0.4.3 - verify guard eval order | Process rules in ordinal order |
| **Containment structure differs** | MEDIUM | Test 0.5.4 - compare containment trees | Preserve insertion order in all collections |

### Low-Risk Side Effects

| Side Effect | Risk | Detection Test | Mitigation |
|-------------|------|----------------|------------|
| **Guards with side effects break** | LOW | N/A - guards should be pure | Document requirement, add `@Pure` annotation suggestion |
| **Rejection cache not cleared** | LOW | Test 0.4.4 - run two transforms, verify isolation | Create new cache per transformation session |

### Critical Invariants

These invariants MUST hold before and after optimization:

1. **XMI ID Determinism**: Same input model → same XMI IDs (byte-identical output)
2. **Containment Integrity**: Every non-root element has exactly one container
3. **Reference Resolution**: All EReference targets resolve to elements in the model
4. **Guard Purity**: Guard evaluation has no observable side effects
5. **Thread Isolation**: Parallel transformations on different sources don't interfere

## Migration Plan

1. Implement rule ordinals (backward compatible)
2. Add in-progress tracking to ResolutionCache
3. Update TransformationContext to use new API
4. Remove `executingLazyRules` map
5. Run full test suite
6. Benchmark and verify improvement

**Rollback:** Revert to commit `27f058b` if issues arise.

## Open Questions

1. Should guard results be cached indefinitely or cleared periodically?
   - Proposal: Cache indefinitely within a transformation session (guards are deterministic)

2. Should we apply the same optimization to non-lazy rules?
   - Proposal: Start with lazy rules only, extend later if beneficial
