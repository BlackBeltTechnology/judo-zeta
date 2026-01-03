# Tasks: Optimize Greedy Rule Performance

## Phase 1: Baseline and Tests

- [x] **1.1 Create performance test infrastructure**
  - Add `GreedyRulePerformanceTest.java` in transformation-core tests
  - Create model generator for large test models (1K, 10K elements)
  - Integrate with TransformationMetrics for automated measurement
  - *Dependency:* None

- [x] **1.2 Establish performance baselines**
  - Record current greedy rule execution time
  - Document baseline metrics in test assertions
  - *Dependency:* 1.1

## Phase 2: Core Optimizations

- [x] **2.1 Add rule lookup caching to TransformationRegistry** (DONE)
  - Add `ConcurrentHashMap<Class<?>, List<TransformRuleDescriptor>> rulesBySourceTypeCache`
  - Modify `getRulesForSource()` to use `computeIfAbsent()`
  - Replace `ArrayList.contains()` with `LinkedHashSet` for O(1) dedup
  - Add cache invalidation on dynamic registration
  - *Dependency:* 1.2

- [x] **2.2 Verify caching correctness** (DONE)
  - Run all 444 existing tests - ALL PASS
  - Verify deterministic ordering preserved (LinkedHashSet maintains order)
  - Verify thread-safety (ConcurrentHashMap for parallel execution)
  - *Dependency:* 2.1

- [x] **2.3 Cache model traversal results** (HIGH IMPACT - DONE)
  - Add `Map<String, Map<Class<?>, Collection<EObject>>> elementsByAliasAndType`
  - Modify element collection in `transform()` to use cached traversal
  - Clear cache at end of transformation
  - **Expected improvement: 10-20%**
  - *Dependency:* 2.2

- [x] **2.4 Lock striping for getOrCreate** (MEDIUM IMPACT - DONE)
  - Replace per-key `ReentrantLock` map with fixed lock stripe array (1024 locks)
  - Reduces 300K lock object allocations to 1024
  - **Expected improvement: 2-5%**
  - *Dependency:* 2.3

## Phase 3: Optional Optimizations

- [ ] **3.1 Pre-partition rules by phase** (LOW IMPACT)
  - Add lazy-initialized `getEagerGreedyRules()`, `getEagerNonGreedyRules()`
  - Eliminate 4 boolean checks per rule per element
  - **Expected improvement: ~1%**
  - *Dependency:* 2.4

- [ ] **3.2 Rule-centric batch processing** (MEDIUM IMPACT)
  - Add `executeEagerRulesRuleCentric()` for `etlCompatibilityMode=false`
  - Group elements by type once, iterate rules first
  - Better cache locality, eliminates redundant type checks
  - **Expected improvement: 5-10%**
  - *Dependency:* 3.1

## Phase 4: Validation and Documentation

- [ ] **4.1 Performance validation**
  - Run performance tests with TransformationMetrics enabled
  - Compare before/after metrics
  - Target: ≥30% additional improvement in greedy rule time
  - *Dependency:* 2.4 (or 3.2 if implemented)

- [ ] **4.2 Regression testing**
  - Run full test suite (444+ tests)
  - Verify deterministic output (same XMI IDs)
  - *Dependency:* 4.1

- [ ] **4.3 Update documentation**
  - Update performance.md with new optimizations
  - Document cache behavior and invalidation
  - *Dependency:* 4.2

## Implementation Priority

| Priority | Task | Complexity | Impact | Status |
|----------|------|------------|--------|--------|
| 1 | 2.1 Rule lookup caching | Low | High | **DONE** |
| 2 | 2.3 Model traversal caching | Low | **High** | **DONE** |
| 3 | 2.4 Lock striping | Medium | Medium | **DONE** |
| 4 | 3.1 Pre-partition rules | Low | Low | Optional |
| 5 | 3.2 Rule-centric batch | Medium | Medium | Optional |

## Expected Total Improvement

| Optimization | Individual Impact | Cumulative |
|--------------|-------------------|------------|
| XMI optimization (external) | -91% | 1,759 ms |
| Rule lookup caching (2.1) | -10% (of remaining) | ~1,580 ms |
| Model traversal caching (2.3) | -15% | ~1,340 ms |
| Lock striping (2.4) | -3% | ~1,300 ms |
| Pre-partition + Rule-centric (3.x) | -5% | ~1,235 ms |
| **Total from original** | | **-94%** |

## Verification Criteria

Each task completion should verify:
1. All existing tests pass
2. No new warnings or errors
3. Metrics show expected improvement (for optimization tasks)
4. Code follows existing patterns
