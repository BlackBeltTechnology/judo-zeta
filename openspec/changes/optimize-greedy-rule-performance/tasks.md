# Tasks: Optimize Greedy Rule Performance

## Phase 1: Baseline and Tests

- [ ] **1.1 Create performance test infrastructure**
  - Add `GreedyRulePerformanceTest.java` in transformation-core tests
  - Create model generator for large test models (1K, 10K, 100K elements)
  - Integrate with TransformationMetrics for automated measurement
  - *Dependency:* None

- [ ] **1.2 Establish performance baselines**
  - Record current `getRulesForSource` call count and time
  - Record current total greedy rule execution time
  - Document baseline metrics in test assertions (±10% tolerance)
  - *Dependency:* 1.1

## Phase 2: Core Optimizations

- [ ] **2.1 Add rule lookup caching to TransformationRegistry**
  - Add `ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache`
  - Modify `getRulesForSource()` to use `computeIfAbsent()`
  - Replace `ArrayList.contains()` with `LinkedHashSet` for O(1) dedup
  - Add metrics: cache hits/misses
  - *Dependency:* 1.2

- [ ] **2.2 Verify caching correctness**
  - Run all 439+ existing tests
  - Verify deterministic ordering preserved
  - Check parallel execution still works
  - *Dependency:* 2.1

- [ ] **2.3 Pre-partition rules by phase**
  - Add lazy-initialized fields: `eagerGreedyRules`, `eagerNonGreedyRules`, `lazyRules`, etc.
  - Add getter methods with double-checked locking
  - Ensure list contents are immutable after computation
  - *Dependency:* 2.2

- [ ] **2.4 Update TransformationExecutor to use pre-partitioned rules**
  - Modify `executeEagerRulesFor()` to skip flag checks (already filtered)
  - Use `getEagerGreedyRules()` and `getEagerNonGreedyRules()`
  - *Dependency:* 2.3

## Phase 3: Advanced Optimizations (Optional)

- [ ] **3.1 Add type name index for non-greedy rules**
  - Add `Map<String, List<TransformRuleDescriptor>> nonGreedyRulesByTypeName`
  - Populate during rule registration
  - Use in `executeEagerRulesFor()` for O(1) lookup
  - *Dependency:* 2.4

- [ ] **3.2 Implement rule-centric batch processing**
  - Add `executeEagerRulesRuleCentric()` alternative implementation
  - Group elements by type name once
  - Iterate rules first, then matching elements
  - Make configurable via builder option
  - *Dependency:* 3.1

## Phase 4: Validation and Documentation

- [ ] **4.1 Performance validation**
  - Run performance tests with TransformationMetrics enabled
  - Compare before/after metrics
  - Assert ≥20% improvement in greedy rule execution time
  - *Dependency:* 2.4 (or 3.2 if advanced optimizations applied)

- [ ] **4.2 Regression testing**
  - Run full test suite (439+ tests)
  - Run OSGi integration tests
  - Verify deterministic output (same XMI IDs)
  - *Dependency:* 4.1

- [ ] **4.3 Update documentation**
  - Update performance.md with caching details
  - Update agent-docs/QUICK-REF.md with performance tips
  - Document TransformationMetrics usage for profiling
  - *Dependency:* 4.2

## Parallelization Notes

Tasks 2.1-2.4 should be done sequentially (each builds on previous).

Tasks 3.1 and 3.2 are optional and can be skipped if Phase 2 achieves target improvement.

## Verification Criteria

Each task completion should verify:
1. All existing tests pass
2. No new warnings or errors
3. Metrics show expected improvement (for optimization tasks)
4. Code follows existing patterns
