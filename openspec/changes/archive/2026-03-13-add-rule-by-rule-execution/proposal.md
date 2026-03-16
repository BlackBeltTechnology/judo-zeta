## Why

ZETA's current execution model is **element-by-element**: for each source element, all matching eager rules execute before moving to the next element. ETL's execution model is **rule-by-rule**: each rule processes ALL matching source elements before the next rule starts. This ordering difference causes behavioral divergence when rules depend on outputs from earlier rules being complete across ALL elements — not just the current element. While `equivalent()` handles same-element cross-rule lookups, patterns where Rule B iterates over ALL outputs of Rule A (e.g., collecting all PageDefinitions to build navigation) produce different results because in ZETA, Rule A hasn't finished processing all elements when Rule B starts executing for the first element.

## What Changes

- Add a new `RuleByRuleExecutionStrategy` (or execution mode) to `TransformationExecutor` that inverts the loop nesting: outer loop over rules, inner loop over matching source elements
- The rule execution order MUST match ETL's module import order, which corresponds to the `registry.register()` call order in ZETA (already preserved via `LinkedHashMap`)
- Within each rule, source elements are processed in the same iteration order as the source collection
- Lazy rules remain on-demand (triggered via `equivalent()`), only eager rules change their execution grouping
- Activity-based rules (Phase 2) remain unchanged — they execute after all eager rules complete
- The strategy is configurable per-executor (default remains element-by-element for backward compatibility)
- Parallel execution supported: within each rule's inner loop, source elements can be processed in parallel chunks (same as existing `transformParallel()` chunking), but the outer rule loop remains sequential — Rule A must complete for ALL elements before Rule B starts
- Staging + deferred writes are used for parallel rule-by-rule, same as existing parallel element-by-element mode

## Capabilities

### New Capabilities
- `rule-by-rule-execution`: ETL-compatible execution strategy where each eager rule processes all matching source elements before the next rule begins, preserving ETL's module import ordering semantics

### Modified Capabilities
- `rule-execution`: Execution model documentation updated to cover both element-by-element (default) and rule-by-rule strategies, including how rule ordering is determined and interaction with lazy/activity-based rules

## Impact

- **TransformationExecutor.java**: New `transformRuleByRule()` method alongside existing `transformSequential()`, configurable via setter or builder
- **TransformationRegistry.java**: May need a `getOrderedEagerRules()` method that returns all eager rules in registration order (not grouped by source type)
- **No API breaking changes**: Default behavior unchanged, new strategy is opt-in
- **Downstream consumers** (judo-tatami-client): Can enable rule-by-rule execution for esm2ui transformation to match ETL ordering
- **Parallel mode**: Rule-by-rule supports parallel execution — each rule's inner loop over source elements is parallelized via chunking, but the outer rule loop is sequential (barrier between rules ensures Rule A completes before Rule B starts)
- **CLONE_CURRENT_STATE constraint**: `CLONE_CURRENT_STATE` + `RULE_BY_RULE` + `parallel` throws `IllegalStateException` — mutation-propagation semantics require sequential execution within each rule

## Test Plan

Integration-level tests using EcorePackage types (EClass, EPackage, EAnnotation, EAttribute) to emulate real ETL transformation patterns. Tests use `TransformationExecutor` with full registry/context setup.

### Test Setup (shared)

```java
// Multiple transformation classes registered in specific order to emulate ETL module imports:
// 1. PackageRules   — @Greedy rule: EPackage → EPackage (like namespace.etl)
// 2. ClassRules     — @Greedy rule: EClass → EClass (like type.etl)
// 3. AttributeRules — @Greedy rule: EAttribute → EAnnotation (like data.etl)
// 4. NavigationRule  — @Greedy rule: EPackage → EAnnotation, iterates ALL ClassRules outputs
// 5. LazyHelperRule  — @Lazy rule: EClass → EAnnotation (triggered via equivalent())
// 6. GreedyLazyRule  — @Greedy @Lazy rule: EClass → EAnnotation (activity-based, Phase 2)
// 7. InheritedRule   — extends ClassRules, adds extra behavior
// 8. DiscriminatedRule — @Lazy rule: EClass → EAnnotation, called via equivalentDiscriminated()
```

### Tests

| # | Test | Strategy | Pattern Emulated | Assert | Priority |
|---|------|----------|-----------------|--------|----------|
| 1 | Rule-by-rule order: RuleA completes ALL elements before RuleB starts | RULE_BY_RULE | ETL module import ordering | Execution log shows all PackageRules elements before any ClassRules elements | **CRITICAL** |
| 2 | Element-by-element order: all rules for E1 before E2 | ELEMENT_BY_ELEMENT | ZETA default | Execution log shows PackageRules+ClassRules for E1 before any rule for E2 | **CRITICAL** |
| 3 | Rule-by-rule: later rule can see ALL outputs from earlier rule | RULE_BY_RULE | ETL pattern: NavigationRule iterates all ClassRules outputs | NavigationRule sees N targets (all classes), not just 1 | **CRITICAL** |
| 4 | Element-by-element: later rule only sees current element's outputs | ELEMENT_BY_ELEMENT | ZETA current behavior (contrast with test 3) | NavigationRule sees only 1 target per element (not all N) | **CRITICAL** |
| 5 | Parallel rule-by-rule: sequential output == parallel output | RULE_BY_RULE + parallel | Concurrency correctness | XMI output from parallel RULE_BY_RULE byte-identical to sequential RULE_BY_RULE | **CRITICAL** |
| 6 | Lazy rule triggered via `equivalent()` during rule-by-rule | RULE_BY_RULE | ETL @lazy rule pattern | LazyHelperRule executes on-demand, result cached | HIGH |
| 7 | Greedy+Lazy (activity-based) in Phase 2 after rule-by-rule Phase 1 | RULE_BY_RULE | ETL @greedy @lazy pattern | GreedyLazyRule executes only for activated elements, after all eager rules complete | HIGH |
| 8 | Rule inheritance: child rule extends parent, both execute in rule order | RULE_BY_RULE | ETL `extends` keyword | InheritedRule output includes parent's behavior + child's additions | HIGH |
| 9 | equivalentDiscriminated works with rule-by-rule execution | RULE_BY_RULE | ETL discriminated equivalent with CLONE_CURRENT_STATE | First caller gets original, subsequent callers get clone of current state | HIGH |
| 10 | equivalentDiscriminated works with rule-by-rule + CLONE_PRISTINE | RULE_BY_RULE | ZETA default discriminated behavior | All callers get independent clones from pristine original | HIGH |
| 11 | Guard rejection in rule-by-rule: rejected elements skipped per rule | RULE_BY_RULE | ETL guard semantics | Element rejected by guard not processed, others proceed | MEDIUM |
| 12 | Error in rule-by-rule: fail-fast stops all subsequent rules | RULE_BY_RULE | Error handling | Exception in RuleA prevents RuleB from executing | MEDIUM |
| 13 | Registration order preserved: classes registered A,B,C → rules execute A,B,C | RULE_BY_RULE | ETL import chain ordering | `getOrderedEagerRules()` returns rules in exact registration order | MEDIUM |
| 14 | Default strategy is ELEMENT_BY_ELEMENT (no config) | — | Backward compatibility | Executor without explicit strategy uses element-by-element | MEDIUM |
| 15 | Cross-element visibility: RuleB reads RuleA outputs for different source | RULE_BY_RULE | ETL: rule B calls equivalent(otherSource, "RuleA") | equivalent() returns RuleA's cached result for otherSource (already executed) | HIGH |
| 16 | Mutation propagation with CLONE_CURRENT_STATE in rule-by-rule | RULE_BY_RULE | ETL equivalentDiscriminated mutation accumulation | First caller mutates original, second caller's clone inherits mutation | HIGH |

### Parallel / Concurrency Tests

| # | Test | Strategy | Pattern Emulated | Assert | Priority |
|---|------|----------|-----------------|--------|----------|
| 17 | Parallel rule-by-rule: rule barrier ensures RuleA completes before RuleB | RULE_BY_RULE + parallel | Parallel ETL | All RuleA targets exist before any RuleB execution starts | **CRITICAL** |
| 18 | Parallel rule-by-rule: output identical to sequential rule-by-rule | RULE_BY_RULE + parallel vs sequential | Determinism | XMI outputs byte-identical (same IDs, same element order) | **CRITICAL** |
| 19 | Parallel element-by-element: output identical to sequential element-by-element | ELEMENT_BY_ELEMENT + parallel vs sequential | Existing determinism | XMI outputs byte-identical | HIGH |
| 20 | Parallel rule-by-rule with many elements: stress test | RULE_BY_RULE + parallel | Concurrency stress | 100+ elements, multiple rules, no race conditions, no duplicate targets | HIGH |
| 21 | Parallel rule-by-rule: deferred writes + staging enabled per-rule batch | RULE_BY_RULE + parallel | Staging correctness | Deferred operations committed between rules (barrier), staging active during parallel chunks | HIGH |
| 22 | CLONE_CURRENT_STATE + RULE_BY_RULE + parallel throws ISE | RULE_BY_RULE + parallel + CLONE_CURRENT_STATE | Incompatibility constraint | `IllegalStateException` — mutation ordering non-deterministic in parallel | **CRITICAL** |
| 23 | Parallel rule-by-rule: lazy rules thread-safe across chunks | RULE_BY_RULE + parallel | Concurrent equivalent() | Multiple chunks trigger same lazy rule concurrently, no duplicate execution | HIGH |

**Must-pass for release**: Tests 1, 2, 3, 4, 5, 17, 18, 22 (8 tests).
**High priority**: Tests 6, 7, 8, 9, 10, 15, 16, 19, 20, 21, 23 (11 tests).
**Medium priority**: Tests 11, 12, 13, 14 (4 tests).
