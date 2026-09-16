# Tasks: Add ETL Pattern Integration Tests

**Change ID**: `add-etl-pattern-integration-tests`  
**Status**: Completed

## Overview

Implementation tasks for comprehensive ETL pattern integration tests.

## Task Breakdown

### Phase 0: Cartesian Product Support

#### [x] Task 0.1: Update TransformFunction to support Object[] source
- Created `MultiSourceTransformFunction<T>` interface for multi-source rules
- The EObject[] contains elements in @Transform annotation order
- **Validation**: Compiles with new signature ✓

#### [x] Task 0.2: Update TransformationExecutor for Cartesian product
- When rule has multiple TransformDefinitions, collect elements from each alias
- Generate Cartesian product of all element collections
- Execute rule once for each tuple in the product
- Pass EObject[] to transform function
- **Validation**: Unit test with 2 sources, 3x2 = 6 executions ✓ (CartesianProductTest)

#### [x] Task 0.3: Update TransformRuleDescriptor for multi-source
- Added method `isMultiSource()` - true when transforms.size() > 1
- Added method `getSourceCount()` - returns number of source types
- Added `getMultiSourceFunction()` and `execute(EObject[], context)`
- **Validation**: Unit tests pass ✓

#### [x] Task 0.4: Update TransformGuard for multi-source
- Created `MultiSourceTransformGuard` interface for multi-source guards
- Added `evaluateGuard(EObject[] sources, TransformationContext context)` method
- For multi-source rules, pass all source elements to guard
- For single-source rules, maintain backward compatibility with single EObject
- Updated guard method lookup in TransformationRegistry to support both signatures
- **Validation**: Guard can access all source elements in Cartesian product ✓

---

### Phase 1: Test Infrastructure

#### [x] Task 1.1: Create ETLPatternIntegrationTest class
- Create test class in `transformation-core/src/test/java/.../ETLPatternIntegrationTest.java`
- Set up base fixtures with Ecore metamodel
- Create helper methods for test data creation
- **Validation**: Test class compiles and runs ✓

#### [x] Task 1.2: Create test resource setup utilities
- Helper methods: `createEntity()`, `createAttribute()`, `createReference()`
- Helper methods: `createTypeMapping()`, `createNameMapping()`, `createForeignKeyRule()`
- Multi-alias resource set initialization (asm, rdbms, mapping, rules)
- **Validation**: Utility methods work correctly ✓

#### [x] Task 1.3: Fix getRulesForSource for interface types
- Fixed TransformationRegistry.getRulesForSource() to match rules registered on interfaces (EClass) with implementation types (EClassImpl)
- **Validation**: Rules now fire correctly for Ecore elements ✓

---

### Phase 2: Multi-Model Transformation Tests

#### [x] Task 2.1: Test transformation across 3 aliased models
- Test: `shouldTransformEntityUsingMappingFromSeparateModel`
- Setup: source ("asm"), target ("rdbms"), lookup ("mapping")
- Rule reads mapping to determine target names
- Verify elements created in correct alias
- **Validation**: Test passes with correct element resolution ✓

#### [x] Task 2.2: Test accessing mapping model during transformation
- Test: `shouldAccessAllThreeModelsDuringTransformation`
- Setup: Mapping entries with type conversions and name mappings
- Rule queries mapping for type conversion rules
- Verify transformation applies mappings correctly
- **Validation**: Test passes with correct type mapping ✓

---

### Phase 3: Cross-Model Equivalence Tests

#### [x] Task 3.1: Test equivalent() from aliased target
- Test: `shouldResolveEquivalentFromAliasedTarget`
- Setup: Parent/child elements in source
- Transform parent, then use equivalent() from child rule
- Verify equivalent() returns element from aliased target
- **Validation**: Test passes with correct cross-reference ✓

#### [x] Task 3.2: Test equivalent() chain across models
- Test: `shouldFollowEquivalentChainAcrossTransformations`
- Setup: Entity -> Table -> Column transformation chain
- Verify equivalent() follows chain correctly
- **Validation**: Test passes with chained resolution ✓

#### [x] Task 3.3: Test equivalent() caching across aliases
- Test: `shouldCacheEquivalentAcrossMultipleCalls`
- Setup: Multiple equivalent() calls for same source
- Verify cache hit on second call
- Verify same instance returned
- **Validation**: Test passes with cache working ✓

---

### Phase 4: Guard with Alias Tests

#### [x] Task 4.1: Test guard querying mapping alias
- Test: `shouldEvaluateGuardQueryingMappingAlias`
- Setup: Rules in "rules" alias with FK metadata
- Guard queries "rules" alias to check applicability
- Verify guard returns correct boolean
- **Validation**: Test passes with guard evaluation ✓

#### [x] Task 4.2: Test rule skipped when guard fails on alias
- Test: `shouldSkipRuleWhenGuardFailsOnAliasLookup`
- Setup: No matching rule entry in "rules" alias
- Verify rule not executed
- Verify no element created in target
- **Validation**: Test passes with rule skipped ✓

#### [x] Task 4.3: Test rule applied when guard succeeds on alias
- Test: `shouldApplyRuleWhenGuardSucceedsOnAliasLookup`
- Setup: Matching rule entry in "rules" alias
- Verify rule executed
- Verify element created in target
- **Validation**: Test passes with rule applied ✓

---

### Phase 5: Lazy Rule with Alias Tests

#### [x] Task 5.1: Test lazy rule not executed until equivalent() called
- Test: `shouldInvokeLazyRuleOnlyWhenEquivalentCalled`
- Setup: Lazy rule with @Transform alias
- Execute transformation without calling equivalent()
- Verify lazy rule not executed
- **Validation**: Test passes with deferred execution ✓

#### [x] Task 5.2: Test lazy rule triggered when equivalent called
- Test: `shouldTriggerLazyRuleWhenEquivalentCalled`
- Setup: Lazy rule with @Transform alias, eager rule that calls equivalent()
- Call equivalent() to trigger lazy execution
- Verify lazy rule creates element
- **Validation**: Test passes with lazy triggering ✓

#### [x] Task 5.3: Test lazy rule result cached
- Test: `shouldCacheLazyRuleResult`
- Setup: Call equivalent() twice for same source
- Verify rule executed only once
- Verify same instance returned
- **Validation**: Test passes with caching ✓

---

### Phase 6: Greedy Rule with Alias Tests

#### [x] Task 6.1: Test greedy rule fires for all matching elements
- Test: `shouldFireGreedyRuleForAllMatchingElements`
- Setup: Multiple EClass elements in "asm" alias
- Greedy rule transforms all of them
- Verify all elements processed
- **Validation**: Test passes with all elements transformed ✓

#### [x] Task 6.2: Test greedy rule alongside non-greedy rules
- Test: `shouldFireGreedyRuleAlongsideNonGreedyRules`
- Setup: Both greedy and non-greedy transformations on same elements
- Verify both rule types fire
- **Validation**: Test passes with both rule types ✓

#### [x] Task 6.3: Test greedy rule respects guard with alias
- Test: `shouldRespectGuardInGreedyRule`
- Setup: Greedy rule with guard querying "rules" alias
- Guard filters some elements
- Verify only matching elements transformed
- **Validation**: Test passes with filtered transformation ✓

---

### Phase 7: Rule Inheritance with Alias Tests

#### [x] Task 7.1: Test abstract rule with @Transform alias
- Test: `shouldInheritTransformAliasFromAbstractRule`
- Setup: Abstract rule with custom alias, concrete rule extending it
- Extending rule inherits alias
- Verify alias propagated
- **Validation**: Test passes with inherited alias ✓

#### [x] Task 7.2: Test extending rule can have its own alias
- Test: `shouldAllowExtendingRuleToHaveOwnAlias`
- Setup: Rule with specific alias configuration
- Verify rule uses its own alias
- **Validation**: Test passes ✓

---

### Phase 8: Pre/Post Hook Tests

#### [x] Task 8.1: Test pre-hook executes before transformation
- Test: `shouldExecutePreHookBeforeTransformation`
- Setup: Pre-execution hook with @PreExecution annotation
- Verify hook executes before transformation rules
- **Validation**: Test passes with hook order verified ✓

#### [x] Task 8.2: Test post-hook executes after transformation
- Test: `shouldExecutePostHookAfterTransformation`
- Setup: Post-execution hook with @PostExecution annotation
- Verify hook executes after transformation rules
- **Validation**: Test passes with hook order verified ✓

#### [x] Task 8.3: Test hooks can access all aliases
- Test: `shouldAccessAllAliasesFromHooks`
- Setup: Pre-hook that queries "asm" and "mapping" aliases
- Verify hook can access all registered aliases
- **Validation**: Test passes with multi-alias access ✓

---

### Phase 9: Complex Scenario Tests

#### [x] Task 9.1: Test ASM2RDBMS-like transformation
- Test: `shouldTransformCompleteEntityModelToRDBMS`
- Simulate: EClass → Table, EAttribute → Column, EReference → ForeignKey
- Multiple aliases: "asm", "rdbms", "mapping", "rules"
- Guards query "rules" for FK type
- Multi-phase transformation execution
- **Validation**: End-to-end test passes ✓

#### [x] Task 9.2: Test discriminated transformations
- Test: `shouldHandleDiscriminatedTransformations`
- Setup: Same source type (EClass), different rules based on guard
- Regular entities vs Special entities (based on rules alias)
- Verify correct discrimination
- **Validation**: Test passes with discriminated outputs ✓

---

### Phase 10: Documentation

#### [x] Task 10.1: Document test patterns in test class
- Added comprehensive Javadoc explaining ETL patterns
- Organized tests into nested classes by phase
- Each test documents the pattern it validates
- **Validation**: Documentation complete ✓

## Dependencies

- **Phase 0 must complete first** (Cartesian product is prerequisite)
- Phase 1 depends on Phase 0
- Phases 2-8 can run in parallel after Phase 1
- Phase 9 depends on Phases 2-8
- Phase 10 can start after Phase 9

## Estimated Effort

| Phase | Tasks | Estimated Hours |
|-------|-------|-----------------|
| Phase 0 | Cartesian Product | 4 |
| Phase 1 | Infrastructure | 2 |
| Phase 2 | Multi-Model | 2 |
| Phase 3 | Equivalence | 2 |
| Phase 4 | Guards | 2 |
| Phase 5 | Lazy Rules | 2 |
| Phase 6 | Greedy Rules | 2 |
| Phase 7 | Inheritance | 2 |
| Phase 8 | Hooks | 2 |
| Phase 9 | Complex | 3 |
| Phase 10 | Documentation | 1 |
| **Total** | | **24** |

## Test File Location

```
transformation-core/src/test/java/
└── hu/blackbelt/judo/zeta/transformation/core/
    └── ETLPatternIntegrationTest.java
```
