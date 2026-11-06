## 1. Test Infrastructure Setup

- [x] 1.1 Create `RuleByRuleParallelTest` class with @Nested structure, static tracking fields (ConcurrentLinkedQueue, AtomicInteger), and TestModelProvider
- [x] 1.2 Create shared test transformation rules: multi-rule set (RuleA→EClass, RuleB→EAttribute, RuleC→EAnnotation) with static execution tracking

## 2. Basic RULE_BY_RULE Parallel Tests

- [x] 2.1 Test all elements transformed correctly with RULE_BY_RULE + parallelThreshold(1) — verify target counts match sequential
- [x] 2.2 Test rules execute in registration order — Rule A completes before Rule B starts (verify via execution log timestamps/order)
- [x] 2.3 Test parallel chunks within a single rule — verify multiple threads participate (check thread IDs)

## 3. Cross-Rule Equivalent Resolution Tests

- [x] 3.1 Test Rule B resolves equivalent() for Rule A targets after incremental commit barrier
- [x] 3.2 Test concurrent equivalent() calls within same rule's parallel chunks don't interfere
- [x] 3.3 Test equivalent() returns null for elements not yet processed (before their rule's iteration)

## 4. Incremental Commit Visibility Tests

- [x] 4.1 Test deferred operations from Rule A are committed before Rule B starts
- [x] 4.2 Test cross-references set by Rule A via deferred ops are visible to Rule B
- [x] 4.3 Test target model contains all elements from all rules after completion

## 5. Guard Evaluation Tests

- [x] 5.1 Test guards reject correct elements in parallel chunks (50% rejection rate, verify same elements rejected as sequential)
- [x] 5.2 Test guard rejection caching works across rules in parallel (Rule B equivalent() for Rule A rejected element returns null)

## 6. Greedy Rules Tests

- [x] 6.1 Test @Greedy rule processes all applicable source types (EClass + EAttribute) in RULE_BY_RULE parallel
- [x] 6.2 Test greedy rule target count matches sequential execution

## 7. Lazy Rules Tests

- [x] 7.1 Test @Lazy rule triggered by equivalent() during parallel chunk execution
- [x] 7.2 Test concurrent lazy rule triggers for same source — only one execution, both get same target

## 8. @Extends Inheritance Tests

- [x] 8.1 Test child rule with @Extends finds parent rule's equivalent after incremental commit in parallel mode

## 9. Stress and Race Condition Tests

- [x] 9.1 Stress test: 1000+ elements with 5+ rules in RULE_BY_RULE parallel — verify no data loss
- [x] 9.2 Race condition test: @RepeatedTest(50) with concurrent equivalent() calls across rules
- [x] 9.3 Determinism test: repeated execution produces identical target counts

## 10. Invalid Configuration Rejection

- [x] 10.1 Test CLONE_CURRENT_STATE + RULE_BY_RULE + parallel throws IllegalStateException with descriptive message
