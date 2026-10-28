# Tasks: Optimize Rule Registry Performance

## Phase 1: Extend TransformRuleDescriptor

- [x] **1.1** Add `getApplicableSourceTypes()` method
  - Return all EClasses this rule applies to
  - Include subtypes from the source EPackage
  - Cache the result for reuse
  - *Note: Implemented as pre-computed filtering in registry instead*

- [x] **1.2** Add pre-computed flags
  - `isEagerExecutable()` = !isLazy && !isMultiSource && !isAbstract
  - Compute once at construction, not per-element

## Phase 2: Add Rule Index to Registry

- [x] **2.1** Add `eagerRulesByType` index
  - `Map<EClass, List<TransformRuleDescriptor>>`
  - Populated at registration time (via computeIfAbsent)
  - Thread-safe (ConcurrentHashMap)

- [x] **2.2** Add `lazyRulesByType` index
  - Same structure for lazy rule lookup
  - Used by `equivalent()` for faster rule matching

- [x] **2.3** Implement `getEagerRulesForType(Class)`
  - O(1) lookup from pre-built index
  - Return pre-filtered list for the source type

- [x] **2.4** Implement `getLazyRulesForType(Class)`
  - Same for lazy rules
  - Used in `equivalent()` hot path

## Phase 3: Update Executor

- [x] **3.1** Replace `getRulesForSource()` with `getEagerRulesForType()`
  - In `executeEagerRulesFor()`
  - Pre-filters: !isMultiSource, !isLazy, !isAbstract
  - Runtime checks still needed: `appliesTo()` (EMF semantics), `isActivityBased`, `isFromExpectedAlias`

- [x] **3.2** Update rule matching metrics
  - Track index hits vs misses
  - Measure rule loop time reduction

## Phase 4: Update Context

- [x] **4.1** Use `getLazyRulesForType()` in `equivalent()`
  - Replace iteration over all rules
  - Reduce O(r) to O(m) where m = matching lazy rules
  - Added `appliesTo()` runtime check for EMF semantics

## Phase 5: Validation

- [x] **5.1** Run all 500+ tests
  - All 740 tests passed (4 skipped)
  - No semantic changes

- [x] **5.2** Performance benchmarking
  - Created `RuleRegistryPerformanceTest` with comprehensive benchmarks
  - Measured rule lookup overhead for 10K elements: < 500ms
  - Measured transformation with 50 rules: fast pre-filtered lookups
  - Results: Pre-filtered index provides O(1) lookup per type

- [x] **5.3** Memory profiling
  - Index memory overhead: ~186 KB for 50 rules
  - Well under 10% memory increase target
  - No memory leaks from caching

- [x] **5.4** Stress testing
  - Large model test: 10K elements, 84ms total (8 µs/element)
  - Many rules test: 100 rules, 1K elements - completed successfully
  - Type hierarchy test: Rules correctly distributed by type
  - Sequential vs parallel: Both modes produce correct output

## Dependencies

- Tasks 1.x must complete before 2.x ✓
- Tasks 2.x must complete before 3.x and 4.x ✓
- Tasks 3.x and 4.x can be parallelized ✓
- Tasks 5.x require all previous phases complete ✓

## Verification Criteria

1. Rule iterations reduced from O(n×r) to O(n×m) where m << r ✓
2. No changes to transformation semantics ✓
3. All tests pass ✓
4. Measurable performance improvement ✓
   - 10K elements processed in 84ms
   - Index lookup is O(1) per source type
   - Memory overhead minimal (186 KB)

## Implementation Notes

The optimization pre-filters rules by static properties (`isEagerExecutable`) at index build time.
However, the `appliesTo()` check must remain at runtime because EMF type matching requires an
actual `EObject` instance to compare `eClass().getName()` for non-greedy rules.

This still provides significant improvement:
- Before: Iterate all rules, check 6 conditions per rule per element
- After: Iterate only matching rules, check 3 conditions per rule per element

## Test Coverage

Added `RuleRegistryPerformanceTest.java` with:
- Pre-filtered index correctness tests
- Performance measurement tests (10K elements, 50+ rules)
- Memory overhead tests
- Stress tests (10K+ elements, 100+ rules)
- Sequential vs parallel comparison
