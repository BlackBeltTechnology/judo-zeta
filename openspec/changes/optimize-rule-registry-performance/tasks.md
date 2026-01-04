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
  - All 500 tests passed (11 skipped)
  - No semantic changes

- [ ] **5.2** Performance benchmarking
  - Measure rule loop time before/after
  - Target: >80% reduction in rule matching time

- [ ] **5.3** Memory profiling
  - Verify index memory <10% increase
  - No memory leaks from caching

- [ ] **5.4** Stress testing
  - Large models (10,000+ elements)
  - Many rules (100+ rules)
  - Deep type hierarchies

## Dependencies

- Tasks 1.x must complete before 2.x ✓
- Tasks 2.x must complete before 3.x and 4.x ✓
- Tasks 3.x and 4.x can be parallelized ✓
- Tasks 5.x require all previous phases complete ✓

## Verification Criteria

1. Rule iterations reduced from O(n×r) to O(n×m) where m << r ✓
2. No changes to transformation semantics ✓
3. All tests pass ✓
4. Measurable performance improvement (pending benchmarks)

## Implementation Notes

The optimization pre-filters rules by static properties (`isEagerExecutable`) at index build time.
However, the `appliesTo()` check must remain at runtime because EMF type matching requires an
actual `EObject` instance to compare `eClass().getName()` for non-greedy rules.

This still provides significant improvement:
- Before: Iterate all rules, check 6 conditions per rule per element
- After: Iterate only matching rules, check 3 conditions per rule per element
