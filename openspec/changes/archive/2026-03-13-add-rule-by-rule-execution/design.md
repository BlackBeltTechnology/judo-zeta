## Context

ZETA's `TransformationExecutor` currently uses an **element-by-element** execution strategy: for each source element, all matching eager rules execute before moving to the next element (`transformSequential()` at line 641). ETL uses **rule-by-rule** execution: each rule processes ALL matching source elements before the next rule begins. The rule ordering in ETL is determined by the module import chain in the main `.etl` file.

The ZETA registration order in `Esm2UiZetaTransformation.registerTransformations()` already mirrors ETL's import order. Rules within each class are registered in declaration order via `getDeclaredMethods()`. The `LinkedHashMap` in `TransformationRegistry` preserves this ordering.

The current executor has two configurable dimensions:
- **Parallel mode**: `parallel(boolean)` controls chunked parallel vs sequential
- **ETL compatibility mode**: `etlCompatibilityMode(boolean)` controls activity-based rule semantics

This change adds a third dimension: **execution strategy** (element-by-element vs rule-by-rule).

## Goals / Non-Goals

**Goals:**
- Add rule-by-rule execution strategy to `TransformationExecutor` that processes all matching elements per rule before moving to the next rule
- Preserve exact rule ordering from `registry.register()` call sequence (mirrors ETL import order)
- Maintain source element iteration order within each rule (same as source collection order)
- Integrate cleanly with existing builder pattern and ETL compatibility mode
- Ensure lazy rules remain on-demand (not affected by execution strategy)

**Non-Goals:**
- Changing the activity-based Phase 2 execution (already rule-by-rule in its fixpoint loop)
- Per-rule execution strategy (all rules use the same strategy within one executor)
- Changing rule ordering itself (that's defined by registration order, not execution strategy)

## Decisions

### Decision 1: Add `ExecutionStrategy` enum, not a boolean flag

**Choice**: New `ExecutionStrategy` enum with `ELEMENT_BY_ELEMENT` (default) and `RULE_BY_RULE` values.

**Alternative considered**: `boolean ruleByRule` flag on the builder.

**Rationale**: An enum is more readable and extensible. If future strategies are needed (e.g., `RULE_BY_RULE_PARALLEL` for independent rule groups), the enum accommodates them without API changes. Consistent with the `EquivalentDiscriminatedStrategy` pattern already established.

### Decision 2: New `transformRuleByRule()` method alongside `transformSequential()`

**Choice**: Add a new private method `transformRuleByRule(Collection<? extends EObject> sourceElements)` that inverts the loop:

```
for each rule in registry.getOrderedEagerRules():
    for each source in sourceElements:
        if rule.appliesTo(source) && !isEffectivelyActivityBased(rule) && isFromExpectedAlias(source, rule):
            execute rule for source (same getOrCreate pattern)
```

**Alternative considered**: Refactoring `transformSequential()` to accept a strategy parameter.

**Rationale**: Separate methods are clearer and easier to maintain. The two strategies have fundamentally different loop structures. The dispatch in `transform()` selects based on strategy:
```java
if (executionStrategy == ExecutionStrategy.RULE_BY_RULE) {
    transformRuleByRule(sourceElements);
} else {
    transformSequential(sourceElements);
}
```

### Decision 3: Use `TransformationRegistry.getOrderedEagerRules()` for rule iteration

**Choice**: Add a new method `getOrderedEagerRules()` to `TransformationRegistry` that returns all eager rules in registration order (preserving class registration order and within-class declaration order). This method filters out lazy, abstract, multi-source, and activity-based rules.

**Alternative considered**: Using `getAllRules()` and filtering at execution time.

**Rationale**: Pre-filtering is consistent with the existing `getEagerRulesForType()` pattern. Caching the filtered list avoids redundant filtering on each transformation. The method returns rules in the exact order they were registered — which mirrors ETL's module import order.

### Decision 4: Parallel rule-by-rule uses per-rule barriers

**Choice**: Rule-by-rule supports parallel execution. The outer rule loop remains sequential, but the inner source-element loop is parallelized using the existing chunking mechanism. Between each rule, a barrier ensures all parallel chunks complete and deferred operations are committed before the next rule starts.

```
enableStaging(); enableDeferredWrites();
for each rule in registry.getOrderedEagerRules():
    // Inner loop: parallel chunks of source elements
    parallelForEach(sourceElements, chunk -> {
        for each source in chunk:
            if rule.appliesTo(source): executeRuleForSource(rule, source)
    });
    // Barrier: commit deferred ops from this rule before next rule starts
    commitDeferredOperations();
commitStagedElements();  // Final commit after all rules
unwrapAllProxiesInModel();
```

**Alternative considered**: Sequential-only rule-by-rule (fail-fast on parallel).

**Rationale**: Parallel within each rule is safe because all elements for one rule are independent of each other (same as existing parallel element-by-element). The rule-level barrier guarantees that Rule B sees all of Rule A's outputs — maintaining ETL's ordering semantics. Deferred operations must be committed between rules (not just at the end) so that Rule B sees materialized outputs from Rule A.

**Constraint**: `CLONE_CURRENT_STATE` + `RULE_BY_RULE` + `parallel` throws `IllegalStateException` because mutation-propagation semantics require deterministic ordering within a rule's element processing.

### Decision 5: Reuse existing `executeEagerRulesFor()` logic via extraction

**Choice**: Extract the core "execute one rule for one source" logic from `executeEagerRulesFor()` into a reusable private method `executeRuleForSource(TransformRuleDescriptor rule, EObject source)`. Both `executeEagerRulesFor()` and `transformRuleByRule()` call this extracted method.

**Alternative considered**: Duplicating the execute logic in `transformRuleByRule()`.

**Rationale**: The execution logic (guard evaluation, cache getOrCreate, error handling, metrics) is identical regardless of loop order. Extracting avoids duplication and ensures both strategies share the same behavior for individual rule-source pairs.

## Risks / Trade-offs

**[Performance]** Rule-by-rule iterates over ALL source elements once per rule, meaning it visits elements multiple times (once per applicable rule). Element-by-element visits each element once and finds all applicable rules.
→ For transformations with many rules and few elements, rule-by-rule may be slightly slower. For the actual esm2ui transformation (~40 classes, ~10K elements), the difference is negligible because the per-element type check (`appliesTo`) is O(1).

**[Lazy rule triggering order]** Lazy rules triggered via `equivalent()` still execute on-demand regardless of strategy. If a rule in rule-by-rule mode triggers a lazy rule that hasn't "run yet" in the rule ordering, this is fine — lazy rules are separate from the eager ordering.
→ No mitigation needed; lazy rules are orthogonal to eager execution strategy.

**[Source element ordering]** ETL processes elements in XMI resource iteration order. ZETA's `sourceElements` collection order depends on how the caller collects them. The rule-by-rule strategy preserves whatever order is passed in.
→ Consumer must ensure source elements are iterated in the same order as ETL. This is already the case for `EsmModel.getResourceContents()`.

**[Multi-source rules]** Multi-source rules (Cartesian product execution) already execute in a rule-by-rule fashion (each multi-source rule processes all element tuples). They are not affected by this change.
→ No mitigation needed.

**[Parallel determinism]** Parallel execution within a rule processes elements in arbitrary chunk order. The output must be byte-identical to sequential execution. This is already guaranteed by the existing staging + deferred writes mechanism — deferred operations are replayed in insertion order, and staged elements are committed in a deterministic order.
→ Concurrency tests must verify sequential vs parallel output identity for rule-by-rule mode.

**[Deferred commit between rules]** In parallel rule-by-rule, deferred operations must be committed BETWEEN rules (not just at the very end). Otherwise Rule B running in parallel won't see Rule A's deferred property assignments. This differs from existing parallel element-by-element where all deferred ops commit once at the end.
→ The per-rule barrier must call `commitDeferredOperations()` after each rule's parallel phase completes. A new `commitDeferredOperationsIncremental()` method may be needed that commits pending ops without disabling deferred writes.
