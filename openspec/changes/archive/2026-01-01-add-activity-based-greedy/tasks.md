# Tasks: Add Activity-Based Greedy Processing

## Overview

Implementation tasks for adding activity-based processing mode for `@Greedy @Lazy` rules.

---

## Phase 1: Annotation Layer

### Task 1.1: Create @ActivityBased annotation
**File**: `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/ActivityBased.java`

- [x] Create new annotation with `@Retention(RUNTIME)` and `@Target(METHOD)`
- [x] Add Javadoc explaining activity-based semantics
- [x] Note requirements: must be used with `@Greedy` and `@Lazy`

**Verification**: Compiles without errors

---

## Phase 2: Activation Tracking

### Task 2.1: Create ActivationTracker class
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/ActivationTracker.java`

- [x] Create class with thread-safe `ConcurrentHashMap<String, Set<EObject>>`
- [x] Add `activate(String ruleName, EObject source)` method
- [x] Add `getActivated(String ruleName)` method returning `Set<EObject>`
- [x] Add `hasActivations(String ruleName)` method
- [x] Add `clear()` method for test cleanup

**Verification**: Unit tests for concurrent activation tracking

### Task 2.2: Add ActivationTracker to TransformationContext
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] Add `private ActivationTracker activationTracker` field
- [x] Initialize in constructor
- [x] Add `getActivationTracker()` getter
- [x] Expose `activate(String ruleName, EObject source)` convenience method

**Verification**: Existing tests pass

---

## Phase 3: Descriptor Layer

### Task 3.1: Add isActivityBased to TransformRuleDescriptor
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformRuleDescriptor.java`

- [x] Add `private final boolean isActivityBased` field
- [x] Update constructors to accept `isActivityBased` parameter
- [x] Add `isActivityBased()` getter
- [x] Update `toString()` to include activity-based status

**Verification**: Compiles without errors

### Task 3.2: Register @ActivityBased in TransformationRegistry
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationRegistry.java`

- [x] Check for `@ActivityBased` annotation on rule method
- [x] Pass to `TransformRuleDescriptor` constructor
- [x] Log warning if `@ActivityBased` used without `@Greedy` and `@Lazy`

**Verification**: Rules with @ActivityBased are correctly registered

---

## Phase 4: Activation Recording

### Task 4.1: Record activations in equivalent() methods
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`

- [x] In `equivalent(EObject, Class)`: record activation if rule is activity-based
- [x] In `equivalent(EObject, String)`: record activation if rule is activity-based
- [x] In `equivalentDiscriminated()`: record activation if rule is activity-based
- [x] Added `isEffectivelyActivityBased()` helper to check ETL compatibility mode
- [x] For activity-based rules, `equivalent()` only records activation and returns null (defers to Phase 2)

**Verification**: Test that equivalent() calls record activations

---

## Phase 5: Two-Phase Execution

### Task 5.1: Add etlCompatibilityMode to TransformationExecutor
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`

- [x] Add `private boolean etlCompatibilityMode` field
- [x] Add `etlCompatibilityMode(boolean)` to builder
- [x] Add helper method `isEffectivelyActivityBased(TransformRuleDescriptor)`:
  - Returns `true` if rule has `@ActivityBased`
  - Returns `true` if `etlCompatibilityMode` AND rule is `@Greedy @Lazy`

**Verification**: Builder accepts etlCompatibilityMode setting

### Task 5.2: Skip activity-based rules in Phase 1
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`

- [x] In `executeEagerRulesFor()`: skip rules where `isEffectivelyActivityBased(rule)`
- [x] Add comment explaining the skip

**Verification**: Activity-based rules not executed in Phase 1

### Task 5.3: Add Phase 2 execution for activity-based rules
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`

- [x] Add `executeActivityBasedRules()` method
- [x] Use `isEffectivelyActivityBased()` to identify rules
- [x] Iterate over activated elements from `ActivationTracker`
- [x] Check cache to avoid re-execution
- [x] Apply guards to activated elements
- [x] Execute rules and cache results
- [x] Call from `transform()` after Phase 1 completes

**Verification**: Only activated elements are processed in Phase 2

### Task 5.4: Handle late activations (fixpoint loop)
**File**: `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java`

- [x] Wrap Phase 2 in a loop that continues until no new activations
- [x] Track processed elements to avoid infinite loops
- [x] Add configurable max iterations as safety limit (MAX_ACTIVITY_BASED_ITERATIONS = 100)

**Verification**: Test with rules that activate each other

---

## Phase 6: Testing

### Task 6.1: Add unit tests for ActivationTracker
**File**: `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ActivationTrackerTest.java`

- [x] Test single activation
- [x] Test multiple activations for same rule
- [x] Test concurrent activations
- [x] Test `getActivated()` returns correct elements
- [x] Test `clear()` removes all activations

### Task 6.2: Add integration tests for activity-based processing
**File**: `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ActivityBasedProcessingTest.java`

- [x] Test `@ActivityBased` rule only processes activated elements
- [x] Test non-referenced elements are not processed
- [x] Test ETL compatibility mode treats `@Greedy @Lazy` as activity-based
- [x] Test guards still apply to activated elements
- [x] Test late activations (fixpoint loop)
- [x] Test parallel execution with activity-based rules

### Task 6.3: Add benchmark test comparing ETL output
**File**: `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ETLMatchingTest.java`

- [ ] Test ClassType transformation produces same count as ETL (133)
- [ ] Test orphan types are not processed

**Verification**: All tests pass

---

## Phase 7: Documentation

### Task 7.1: Update documentation
**Files**:
- `docs/transformation/reference/annotations.md`
- `docs/transformation/etl-comparison/syntax-mapping.md`
- `AGENTS.md`

- [x] Document `@ActivityBased` annotation usage
- [x] Explain two-phase execution model
- [x] Add migration guide for ETL transformations
- [x] Update ETL comparison table

---

## Verification Checklist

After all tasks complete:

- [x] `mvn clean install` succeeds
- [x] All existing tests pass (no regressions) - 323 tests pass
- [x] New activity-based tests pass (5 integration + 14 unit tests)
- [x] `@ActivityBased @Greedy @Lazy` rules only process activated elements
- [x] Standard `@Greedy` rules process all elements (unchanged)
- [ ] Benchmark test produces 133 ClassTypes (matches ETL) - deferred
- [x] Documentation complete

---

## Dependencies

```
Task 1.1 ──► Task 3.1 ──► Task 3.2 ──► Task 4.1 ──┬──► Task 5.1
                                                  │
Task 2.1 ──► Task 2.2 ─────────────────────────────┴──► Task 5.2 ──► Task 5.3
                                                              │
                                                              ▼
                                                        Task 6.x ──► Task 7.1
```

**Parallelizable**:
- Tasks 1.1 and 2.1 can run in parallel
- Tasks 6.1, 6.2, 6.3 can run in parallel after 5.3

**Sequential**:
- Task 3.2 depends on 3.1 (descriptor changes)
- Task 5.2 depends on 2.2 (activation tracker in context)
- Task 5.3 depends on 5.2 (base Phase 2 execution)
