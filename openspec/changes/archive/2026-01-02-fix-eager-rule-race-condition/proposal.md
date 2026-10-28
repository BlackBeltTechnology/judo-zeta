# Proposal: Fix Eager Rule Race Condition in Parallel Transformation

**Change ID:** fix-eager-rule-race-condition
**Status:** Draft
**Created:** 2026-01-02

## Why

Parallel transformation mode produces duplicate elements on large models due to race conditions in `executeEagerRulesFor()`. The issue manifests when the same source element is processed by multiple threads simultaneously - both threads check the cache, find a miss, and execute the rule independently, creating duplicate target elements.

**Evidence:**
- RackInspect model: Expected 22,134 elements, Actual 22,147 elements (+13 duplicates)
- Issue only occurs in parallel mode with models exceeding the parallel threshold
- Root cause: Non-atomic cache lookup in `TransformationExecutor.executeEagerRulesFor()`

Additionally, when deferred writes mode is enabled, proxied EObjects can end up in the target resource, causing serialization failures with `ClassCastException: DeferredEList cannot be cast to InternalEList`.

## What Changes

### Issue #1: Non-Atomic Cache Lookup in Eager Rules

**Current Behavior:**
```java
// TransformationExecutor.executeEagerRulesFor() - RACE CONDITION
EObject cached = context.getElementResolutionCache().getByRule(source, rule.getName());
if (cached != null) continue;  // <-- Race window starts
// ... guard checks ...
EObject target = rule.execute(source, context);  // <-- Multiple threads execute
```

**Fix:** Add `getOrCreate()` method to `ElementResolutionCache` with per-key locking:
```java
EObject getOrCreate(EObject source, String ruleName, Supplier<EObject> ruleExecutor);
```
- Lock covers full rule execution (cache lookup + guard evaluation + rule execution)
- Guard evaluation happens inside the lock to prevent race conditions
- Supplier-based API for lazy execution - rule only executes on cache miss

### Issue #2: Proxy Objects in Resource

**Current Behavior:** When deferred writes mode is enabled, `createTarget()` returns proxied EObjects. If these proxies are added to the resource (via staging or `addToResource()`), EMF serialization fails.

**Fix:** Unwrap proxies before adding to resource in `addToResource()` and `commitStagedElements()`.

### Issue #3: Proxy References Not Unwrapped

**Current Behavior:** Reference values set via deferred writes may contain proxy objects that aren't unwrapped during commit.

**Fix:** Add post-commit cleanup to scan and unwrap any remaining proxies in the model.

## Scope

- `ElementResolutionCache.java` - Add `getOrCreate()` with per-key locking
- `TransformationExecutor.java` - Use `getOrCreate()` in `executeEagerRulesFor()`
- `TransformationContext.java` - Unwrap proxies in `addToResource()`, `commitStagedElements()`, add `unwrapAllProxiesInModel()`
- `DeferredEList.java` - Unwrap elements before recording operations

## Success Criteria

1. **Correctness:** Parallel mode produces identical element count to sequential mode
2. **No duplicates:** Same source element always maps to same target element
3. **Serializable:** Model can be saved without ClassCastException
4. **Deterministic:** Multiple parallel runs produce identical results
5. **Performance:** Measure current parallel vs sequential performance before/after to ensure no significant regression
6. **Regression-free:** All existing features continue to work correctly in combination with fixes

## Test Strategy

### Unit Tests

1. **Atomic cache operations**
   - Concurrent `getOrCreate()` calls for same (source, ruleName) return identical instance
   - Different (source, ruleName) pairs execute in parallel without blocking
   - Cache correctly stores and retrieves results after `getOrCreate()`

2. **Proxy unwrapping**
   - `addToResource()` unwraps proxy elements
   - `commitStagedElements()` produces model with no proxies
   - `unwrapAllProxiesInModel()` cleans all reference values

### Integration Tests - Feature Combinations

3. **Parallel + Deferred Writes**
   - Enable both parallel mode and deferred writes
   - Verify no duplicate elements created
   - Verify all deferred operations committed correctly
   - Verify model serialization succeeds

4. **Parallel + Element Staging**
   - Parallel eager rule execution with staged elements
   - Verify staged elements committed in correct order
   - Verify no race conditions during commit phase

5. **Parallel + Guard Rejection Caching**
   - Guards evaluated under lock still cache rejections
   - Cached rejections prevent redundant guard evaluation
   - No stale cache entries across transformation runs

6. **Deferred Writes + Proxy Unwrapping**
   - Proxied elements correctly unwrapped before resource addition
   - Reference values containing proxies are cleaned
   - Nested proxy references (proxy referencing proxy) handled

7. **Parallel + Per-Element Locking + Deferred Writes**
   - Full combination of all thread-safety features
   - Large model (22,000+ elements) produces correct count
   - Multiple runs produce identical results

### Regression Tests

8. **Sequential mode unchanged**
   - Sequential transformation produces same results as before
   - No performance regression in sequential mode

9. **Existing transformation rules**
   - Run existing transformation test suite
   - Verify no behavioral changes in rule execution
   - Verify guard evaluation still works correctly

10. **Edge cases**
    - Empty source models
    - Single-element models (below parallel threshold)
    - Models exactly at parallel threshold
    - Rules with null guard (always execute)
    - Rules returning null target

## Risks

- **Lock contention:** Per-key locking could reduce parallelism if many rules target same source. Mitigation: Use fine-grained locks per (source, ruleName) pair.
- **Behavior change:** Guard evaluation now happens inside locked section, which could affect guard methods with side effects. Mitigation: Guards should be pure functions anyway.

## Compatibility

### API Compatibility: No Breaking Changes

- `getOrCreate()` is a new method (additive)
- `unwrapAllProxiesInModel()` is a new method (additive)
- Existing `getByRule()` and `addMapping()` methods remain unchanged
- `TransformationContext` public API unchanged
- Transformation rule definitions unchanged

### Behavior Changes

1. **Guard execution timing**
   - Guards now execute under lock for same (source, ruleName) pair
   - Guards with side effects may observe different execution order
   - **Impact:** Only affects guards that violate the pure function contract

2. **Rule execution serialization**
   - Same (source, ruleName) pairs now execute sequentially, not in parallel
   - Different (source, ruleName) pairs still execute in parallel
   - **Impact:** Only affects code with race condition dependencies (incorrect usage)

3. **Proxy reference handling**
   - Proxy objects are now unwrapped before resource addition
   - External code that obtained proxy references will see unwrapped objects after commit
   - **Impact:** Should not affect normal usage; proxies are internal implementation detail

### Migration

No migration required. Existing transformation rules work without modification.

## Alternatives Considered

1. **Global lock on eager rule execution:** Rejected - too much contention, negates parallel benefits
2. **Optimistic locking with retry:** Rejected - more complex, duplicate elements would still be created temporarily
3. **Disable deferred writes for eager rules:** Rejected - doesn't address the core cache race condition
