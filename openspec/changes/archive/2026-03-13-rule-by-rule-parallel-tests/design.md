## Context

RULE_BY_RULE parallel execution exists in `TransformationExecutor.transformRuleByRuleParallel()` but has zero test coverage. All existing parallel tests (`ParallelStressTest`, `ParallelRaceConditionStressTest`, `ParallelSafetyTest`) use ELEMENT_BY_ELEMENT strategy exclusively. The RULE_BY_RULE parallel path processes rules sequentially (outer loop) with elements in parallel chunks (inner loop), using `commitDeferredOperationsIncremental()` as a barrier between rules.

## Goals / Non-Goals

**Goals:**
- Comprehensive test coverage for RULE_BY_RULE + parallel execution
- TDD approach: write tests first, fix any bugs discovered
- Cover all interaction patterns: cross-rule equivalent(), guards, greedy, lazy, @Extends
- Stress tests for race condition detection
- Verify the CLONE_CURRENT_STATE + RULE_BY_RULE + parallel rejection

**Non-Goals:**
- Modifying production code unless tests reveal actual bugs
- Performance optimization of RULE_BY_RULE parallel
- Adding new parallel features (just testing existing ones)

## Decisions

### 1. Single test class with @Nested inner classes

**Decision:** Create `RuleByRuleParallelTest` with `@Nested` inner classes for each test category (basic, cross-rule, guards, greedy, lazy, extends, stress, rejection).

**Rationale:** Follows existing test patterns (`ParallelStressTest`, `ParallelSafetyTest`). @Nested provides logical grouping while keeping all RULE_BY_RULE parallel tests discoverable in one file.

**Alternative considered:** Multiple separate test files. Rejected because the tests share common setup (transformation rules, model providers) and the scope is focused enough for one file.

### 2. Use parallelThreshold(1) to force parallel execution

**Decision:** Set `parallelThreshold(1)` in all tests to force parallel execution even with small element counts.

**Rationale:** Tests need deterministic parallel behavior. This is the established pattern used by all existing parallel tests. Small element counts make assertions easier while still exercising parallel code paths.

### 3. Use EcorePackage types as source/target

**Decision:** Use `EPackage`, `EClass`, `EAttribute`, `EAnnotation` etc. from EcorePackage as source and target types.

**Rationale:** Consistent with all existing tests. Avoids need for custom metamodels. EcorePackage provides enough type variety for multi-rule scenarios.

### 4. Static tracking fields for execution verification

**Decision:** Use `static ConcurrentLinkedQueue<String>` and `static AtomicInteger` fields in test rule classes to track execution order, counts, and thread IDs.

**Rationale:** Follows existing test patterns. ConcurrentLinkedQueue is thread-safe for parallel execution. Static fields allow rule classes to communicate with test assertions.

### 5. Repeated tests for race condition detection

**Decision:** Use `@RepeatedTest(50)` for stress/race-condition scenarios.

**Rationale:** Race conditions are timing-dependent. 50 repetitions provides reasonable confidence without excessive test duration. Matches existing `ParallelRaceConditionStressTest` approach.

## Risks / Trade-offs

- **[Risk] Tests may reveal actual bugs in `transformRuleByRuleParallel()`** → Good — that's the point. Fix bugs as TDD demands, with test proving the fix.
- **[Risk] Race conditions may be hard to reproduce deterministically** → Mitigation: Use `@RepeatedTest` and high element counts to increase probability of triggering races.
- **[Risk] Test execution time for stress tests** → Mitigation: Keep stress test element counts reasonable (1000, not 100000). Tag stress tests separately if needed.
