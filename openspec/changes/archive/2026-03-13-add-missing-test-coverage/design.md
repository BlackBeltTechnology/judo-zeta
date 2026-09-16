## Context

Commits 8bff98e and 0a9fe21 introduced production features without corresponding tests. The existing test suite (60+ test classes) covers most transformation features well, but these specific code paths have zero assertions.

## Goals / Non-Goals

**Goals:**
- Add regression tests for all untested features from commits 8bff98e and 0a9fe21
- Follow existing test patterns (JUnit 5, Ecore metamodels, inline transformation classes)

**Non-Goals:**
- Refactoring production code
- Adding tests for already-tested features
- Performance or stress testing

## Decisions

**One test class per feature area** — keeps tests focused and discoverable:
1. `EquivalentCachedTest` — tests for `equivalentCached()` public API
2. `SequentialAddToResourceXmiIdTest` — tests for recursive XMI ID assignment in sequential `addToResource()`
3. `GuardRejectionIntegrationTest` — tests for `isRejected()` checks inside `equivalent()` and `equivalentDiscriminated()`
4. `GreedyPassRuleTrackingTest` — tests for `currentGreedyPassRuleName` same-rule null semantics

**Test approach:**
- Use inline `@TransformationContext` classes with `@TransformRule` methods (same pattern as `ExecutionStrategyTest`, `GuardRejectionCacheTest`)
- Use ECore metamodel (`EClass`, `EPackage`, `EAttribute`) as source/target since it's self-contained
- Test both positive (feature works) and negative (feature doesn't trigger when it shouldn't) paths

## Risks / Trade-offs

- [Tests may be brittle to internal refactoring] → Test observable behavior (return values, XMI IDs) not implementation details
- [Some features interact with each other] → Keep tests isolated; each test class sets up its own context
