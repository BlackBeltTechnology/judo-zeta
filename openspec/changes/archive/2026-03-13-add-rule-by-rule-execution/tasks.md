## 1. New Types

- [x] 1.1 Create `ExecutionStrategy` enum with `ELEMENT_BY_ELEMENT` and `RULE_BY_RULE` values in `transformation-core`

## 2. TransformationRegistry Changes

- [x] 2.1 Add `getOrderedEagerRules()` method that returns all eager rules in registration order, filtering out lazy, abstract, multi-source, and activity-based rules
- [x] 2.2 Add caching for `getOrderedEagerRules()` result (invalidated on `invalidateCaches()`)

## 3. TransformationContext Changes

- [x] 3.1 Add `commitDeferredOperationsIncremental()` method that commits pending deferred operations without disabling deferred writes (for inter-rule barrier in parallel rule-by-rule)

## 4. TransformationExecutor Changes

- [x] 4.1 Add `executionStrategy` field (default `ELEMENT_BY_ELEMENT`) to `TransformationExecutor` and `Builder`
- [x] 4.2 Add `executionStrategy(ExecutionStrategy)` builder method
- [x] 4.3 Add fail-fast validation: throw `IllegalStateException` when `CLONE_CURRENT_STATE` + `RULE_BY_RULE` + `parallel == true`
- [x] 4.4 Extract `executeRuleForSource(TransformRuleDescriptor rule, EObject source)` from `executeEagerRulesFor()` — shared logic for guard evaluation, cache getOrCreate, error handling, metrics
- [x] 4.5 Refactor `executeEagerRulesFor()` to call `executeRuleForSource()` in its inner loop
- [x] 4.6 Add `transformRuleByRule(Collection<? extends EObject> sourceElements)` method — sequential version: outer loop over rules, inner loop over elements
- [x] 4.7 Add `transformRuleByRuleParallel(Collection<? extends EObject> sourceElements)` method — parallel version: outer loop over rules (sequential), inner loop parallelized via chunks with staging/deferred writes, inter-rule barrier with `commitDeferredOperationsIncremental()`
- [x] 4.8 Update `transform()` dispatch: when `executionStrategy == RULE_BY_RULE`, call sequential or parallel variant based on `parallel` flag

## 5. Tests

Create `ExecutionStrategyTest.java` with shared test setup using EcorePackage types. Multiple transformation classes registered in specific order emulating ETL module imports (PackageRules, ClassRules, AttributeRules, NavigationRule, LazyHelperRule, GreedyLazyRule, InheritedRule, DiscriminatedRule).

### Critical (must-pass for release)

- [x] 5.1 Test 1: RULE_BY_RULE processes all elements for RuleA before RuleB starts (verify execution order via recorded log)
- [x] 5.2 Test 2: ELEMENT_BY_ELEMENT processes all rules for E1 before moving to E2 (verify existing behavior)
- [x] 5.3 Test 3: RULE_BY_RULE — later rule sees ALL outputs from earlier rule (NavigationRule iterates all ClassRules outputs, sees N targets not just 1)
- [x] 5.4 Test 4: ELEMENT_BY_ELEMENT — later rule only sees current element's outputs (contrast with test 3)
- [x] 5.5 Test 5: Parallel RULE_BY_RULE output byte-identical to sequential RULE_BY_RULE
- [x] 5.6 Test 17: Parallel RULE_BY_RULE — rule barrier ensures RuleA completes before RuleB starts
- [x] 5.7 Test 18: Parallel RULE_BY_RULE output byte-identical to sequential RULE_BY_RULE (with many elements)
- [x] 5.8 Test 22: CLONE_CURRENT_STATE + RULE_BY_RULE + parallel throws `IllegalStateException`

### High priority

- [x] 5.9 Test 6: Lazy rule triggered via `equivalent()` during rule-by-rule (on-demand execution, result cached)
- [x] 5.10 Test 7: Greedy+Lazy (activity-based) Phase 2 executes after rule-by-rule Phase 1 (only activated elements processed)
- [x] 5.11 Test 8: Rule inheritance — child rule extends parent, both execute in rule order
- [x] 5.12 Test 9: `equivalentDiscriminated` with RULE_BY_RULE + CLONE_CURRENT_STATE (first caller gets original, subsequent get clone)
- [x] 5.13 Test 10: `equivalentDiscriminated` with RULE_BY_RULE + CLONE_PRISTINE (all callers get independent pristine clones)
- [x] 5.14 Test 15: Cross-element visibility — RuleB calls `equivalent(otherSource, "RuleA")` and gets cached result
- [x] 5.15 Test 16: Mutation propagation — CLONE_CURRENT_STATE in sequential rule-by-rule
- [x] 5.16 Test 19: Parallel ELEMENT_BY_ELEMENT output identical to sequential ELEMENT_BY_ELEMENT
- [x] 5.17 Test 20: Parallel RULE_BY_RULE stress test — 100+ elements, no race conditions, no duplicate targets
- [x] 5.18 Test 21: Parallel RULE_BY_RULE — deferred writes committed between rules (RuleB sees RuleA's materialized properties)
- [x] 5.19 Test 23: Parallel RULE_BY_RULE — lazy rules thread-safe across concurrent chunks

### Medium priority

- [x] 5.20 Test 11: Guard rejection in RULE_BY_RULE — rejected elements skipped, others proceed
- [x] 5.21 Test 12: Error in RULE_BY_RULE — fail-fast stops all subsequent rules and elements
- [x] 5.22 Test 13: Registration order preserved — `getOrderedEagerRules()` returns rules in exact registration order
- [x] 5.23 Test 14: Default strategy is ELEMENT_BY_ELEMENT when no builder config

## 6. Regression

- [x] 6.1 Verify all existing transformation tests pass (no regression in ELEMENT_BY_ELEMENT path)
