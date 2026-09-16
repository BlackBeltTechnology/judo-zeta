# Tasks: Fix Eager Rule Race Condition

## Implementation Tasks

- [x] Measure baseline parallel vs sequential performance
  - Run existing transformation tests in both modes
  - Record timing for comparison after implementation

- [x] Add `getOrCreate()` method to `ElementResolutionCache` with per-key locking
  - Signature: `EObject getOrCreate(EObject source, String ruleName, Supplier<EObject> ruleExecutor)`
  - Use `ConcurrentHashMap<CacheKey, ReentrantLock>` for fine-grained locks
  - Lock covers full execution: cache lookup + guard evaluation + rule execution
  - CacheKey should be `(System.identityHashCode(source), ruleName)` pair

- [x] Update `TransformationExecutor.executeEagerRulesFor()` to use atomic cache operation
  - Replace separate `getByRule()` + `addMapping()` with `getOrCreate()` pattern
  - Move guard evaluation inside the Supplier (executed under lock)
  - Supplier returns null if guard rejects, target EObject if rule executes

- [x] Add proxy unwrapping in `TransformationContext.addToResource()`
  - Check if element implements `DeferredEObject.ProxyMarker`
  - Call `DeferredEObject.unwrap()` before adding to resource
  - Log warning if proxy detected (indicates potential issue in caller)

- [x] Add proxy unwrapping in `TransformationContext.commitStagedElements()`
  - Unwrap all staged elements before adding to resource
  - Ensure element references are also unwrapped

- [x] Add `unwrapAllProxiesInModel()` cleanup method to `TransformationContext`
  - Traverse all elements in target resource
  - Scan all EReference values for proxy instances
  - Replace proxy references with unwrapped real objects

- [x] Update `DeferredEList` to unwrap elements before recording operations
  - In `add()`, `addAll()`, `set()` methods, unwrap proxy arguments
  - Prevents proxies from being stored in reference values

## Unit Tests

- [x] Create tests for atomic cache operations
  - Concurrent `getOrCreate()` calls for same (source, ruleName) return identical instance
  - Different (source, ruleName) pairs execute in parallel without blocking
  - Cache correctly stores and retrieves results after `getOrCreate()`

- [x] Create tests for proxy unwrapping
  - `addToResource()` unwraps proxy elements
  - `commitStagedElements()` produces model with no proxies
  - `unwrapAllProxiesInModel()` cleans all reference values

## Integration Tests - Feature Combinations

- [x] Test: Parallel + Deferred Writes
  - Enable both parallel mode and deferred writes
  - Verify no duplicate elements created
  - Verify all deferred operations committed correctly
  - Verify model serialization succeeds

- [x] Test: Parallel + Element Staging
  - Parallel eager rule execution with staged elements
  - Verify staged elements committed in correct order
  - Verify no race conditions during commit phase

- [x] Test: Parallel + Guard Rejection Caching
  - Guards evaluated under lock still cache rejections
  - Cached rejections prevent redundant guard evaluation
  - No stale cache entries across transformation runs

- [x] Test: Deferred Writes + Proxy Unwrapping
  - Proxied elements correctly unwrapped before resource addition
  - Reference values containing proxies are cleaned
  - Nested proxy references (proxy referencing proxy) handled

- [x] Test: Parallel + Per-Element Locking + Deferred Writes (Full Combination)
  - Full combination of all thread-safety features
  - Large model (22,000+ elements) produces correct count
  - Multiple runs produce identical results

## Regression Tests

- [x] Test: Sequential mode unchanged
  - Sequential transformation produces same results as before
  - No performance regression in sequential mode

- [x] Test: Existing transformation rules
  - Run existing transformation test suite
  - Verify no behavioral changes in rule execution
  - Verify guard evaluation still works correctly

- [x] Test: Edge cases
  - Empty source models
  - Single-element models (below parallel threshold)
  - Models exactly at parallel threshold
  - Rules with null guard (always execute)
  - Rules returning null target

- [x] Measure post-implementation performance
  - Compare with baseline measurements from task 1
  - Verify no significant performance regression

## Dependencies

- Task 1 (baseline measurement) should complete first
- Tasks 2-7 (implementation) must complete before unit tests
- Unit tests should complete before integration tests
- All tests must pass before measuring final performance
