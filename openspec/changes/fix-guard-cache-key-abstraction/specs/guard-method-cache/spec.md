## ADDED Requirements

### Requirement: Guard results SHALL be cached by guard method and source element

The `TransformationContext` SHALL maintain a guard result cache keyed by `(java.lang.reflect.Method guardMethod, EObject source)`. When `evaluateGuard()` is called, it SHALL check this cache before invoking the guard. Both `true` and `false` results SHALL be cached.

#### Scenario: First evaluation caches the result

- **WHEN** `evaluateGuard(sourceA, context)` is called on a rule with guard method `isNotAbstract`
- **AND** no cached result exists for `(isNotAbstract, sourceA)`
- **THEN** the guard method SHALL be invoked
- **AND** the result SHALL be stored in the guard method cache

#### Scenario: Subsequent evaluation of same guard method and source returns cached result

- **WHEN** `evaluateGuard(sourceA, context)` is called on Rule B which also uses guard method `isNotAbstract`
- **AND** a cached result exists for `(isNotAbstract, sourceA)` from Rule A's earlier evaluation
- **THEN** the guard method SHALL NOT be invoked
- **AND** the cached result SHALL be returned

#### Scenario: Different guard methods are cached independently

- **WHEN** Rule A uses guard method `isNotAbstract` and Rule B uses guard method `hasContainer`
- **AND** both are evaluated for the same source element
- **THEN** each guard method SHALL be invoked independently
- **AND** each result SHALL be cached under its own method key

#### Scenario: Different source elements are cached independently

- **WHEN** `evaluateGuard(sourceA, context)` and `evaluateGuard(sourceB, context)` are called for the same guard method
- **THEN** each source element SHALL have its own cached result

### Requirement: Guard method cache SHALL be thread-safe

The guard result cache SHALL support concurrent access from multiple threads during parallel transformation execution.

#### Scenario: Concurrent guard evaluations for different sources

- **WHEN** multiple threads evaluate the same guard method for different source elements concurrently
- **THEN** all evaluations SHALL complete without data corruption
- **AND** each result SHALL be correctly cached

#### Scenario: Concurrent guard evaluations for same source

- **WHEN** multiple threads evaluate the same guard method for the same source element concurrently
- **THEN** the guard method MAY be invoked more than once (benign race)
- **AND** the final cached result SHALL be correct

### Requirement: Guard method cache SHALL be scoped to a single transformation run

The guard result cache SHALL be created fresh with each `TransformationContext` instance. There SHALL be no stale results carried between `transform()` calls.

#### Scenario: Reused executor gets fresh guard cache

- **WHEN** a `TransformationExecutor` is reused for a second `transform()` call
- **THEN** the guard method cache SHALL be empty at the start of the second run
- **AND** guard methods SHALL be re-evaluated for all source elements

### Requirement: Per-rule rejected set SHALL be removed from TransformRuleDescriptor

The `TransformRuleDescriptor.rejected` field (`Set<EObject>`) and associated methods (`wasRejected()`, `recordRejection()`, `clearRejected()`) SHALL be removed. Guard caching is now handled at the guard-method level in `TransformationContext`.

#### Scenario: evaluateGuard uses guard method cache instead of per-rule rejected set

- **WHEN** `evaluateGuard(source, context)` is called
- **THEN** it SHALL check `context.getGuardResult(guardMethod, source)` for a cached result
- **AND** it SHALL NOT check a per-rule rejected set

#### Scenario: TransformationRegistry.clearAllRejectedSets removed

- **WHEN** the executor resets state for a new transformation run
- **THEN** it SHALL NOT call `clearAllRejectedSets()` on the registry
- **AND** the per-rule rejected sets SHALL not exist

### Requirement: ElementResolutionCache.rejectedKeys SHALL remain unchanged

The `ElementResolutionCache.rejectedKeys` cache (keyed by `(source, ruleName)`) SHALL continue to function as before. It caches the overall "getOrCreate returned null" result, which includes guard rejection and rule returning null for other reasons. This is a rule-level result cache, not a guard cache.

#### Scenario: getOrCreate still marks rejected when lambda returns null

- **WHEN** the `getOrCreate` lambda returns null (guard rejected or rule returned null)
- **THEN** `ElementResolutionCache` SHALL mark `(source, ruleName)` as rejected
- **AND** subsequent `getOrCreate` calls for the same `(source, ruleName)` SHALL return null without invoking the lambda
