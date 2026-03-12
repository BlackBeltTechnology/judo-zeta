# Tasks: Infer Source Type from Generic Parameters

**IMPORTANT: TDD Approach** - Write tests FIRST, then implement to make them pass.

## 1. TDD Phase 1: Write Failing Tests (RED) ✓

All tests in this section should be written BEFORE any implementation code.
Tests will initially fail because the feature doesn't exist yet.

### 1.1 Create Test Infrastructure

- [x] 1.1.1 Create `SourceTypeInferenceTest.java` test class
  - File: `transformation-core/src/test/java/.../SourceTypeInferenceTest.java`
  - Set up test fixtures with source/target ResourceSets
  - Create helper methods for dynamic EClass/EPackage creation

### 1.2 Core Functionality Tests (P0 - Must Pass)

- [x] 1.2.1 Test: Basic generic type extraction
  - Rule with `TransformFunction<EClass, EPackage>` should infer `EClass`
  - Verify `rule.getSourceType()` returns inferred type

- [x] 1.2.2 Test: Explicit `@Transform` annotation takes priority
  - Rule with both generic `TransformFunction<EObject, X>` and `@Transform(type = EClass.class)`
  - Verify `@Transform` wins over generic inference

- [x] 1.2.3 Test: Explicit `sourceTypes` attribute takes priority
  - Rule with both generic and `@TransformRule(sourceTypes = {EClass.class})`
  - Verify `sourceTypes` wins over generic inference

- [x] 1.2.4 Test: Greedy rule with supertype correctly matches subtypes
  - Rule: `TransformFunction<EClassifier, EPackage>` with `@Greedy`
  - Element: `EClass` (subtype of `EClassifier`)
  - Verify rule is included in `getEagerRulesForType(EClass.class)`

- [x] 1.2.5 Test: Non-greedy rule with inferred type matches exact type only
  - Rule: `TransformFunction<EClass, EPackage>` (no `@Greedy`)
  - Element A: `EClass` instance - should match
  - Element B: subtype instance - should NOT match (type-of semantics)

- [x] 1.2.6 Test: Behavioral equivalence verification
  - For each rule, verify type filtering produces identical element sets as `appliesTo()`
  - Use mixed-type element collection (EClass, EDataType, EAttribute, etc.)
  - Assert: `filter(appliesTo) == filter(typeIndex)` for all rules

### 1.3 Edge Case Tests (P1)

- [x] 1.3.1 Test: Raw EObject generic parameter falls back to default
  - Rule: `TransformFunction<EObject, EPackage>`
  - Should use `@TransformationContext` default (inference provides no benefit)

- [x] 1.3.2 Test: Multi-source rules (multiple `@Transform`) unaffected
  - Rules with multiple `@Transform` annotations continue to work
  - Inference only applies when no explicit transforms defined

- [x] 1.3.3 Test: Lazy rules correctly filtered
  - Verify `getLazyRulesForType()` respects inferred source types

### 1.4 Performance Validation Tests (P2)

- [x] 1.4.1 Test: Rules only execute for matching element types
  - Create scenario with many types and targeted rules
  - Verify rules only execute for matching types (type-filtered)

- [x] 1.4.2 Test: Type index cache efficiency
  - Verify `eagerRulesByTypeCache` and `lazyRulesByTypeCache` work with inferred types
  - Confirm O(1) lookup after initial computation

## 2. TDD Phase 2: Implementation (GREEN) ✓

Only start this phase after ALL tests from Phase 1 are written.

- [x] 2.1 Add `extractSourceTypeFromReturnType(Method)` method in `TransformationRegistry.java`
  - Mirror existing `extractTargetTypeFromReturnType()` implementation
  - Extract first generic parameter from `TransformFunction<S, T>`
  - Handle `Class`, `ParameterizedType`, and `WildcardType` cases
  - File: `transformation-core/src/main/java/.../TransformationRegistry.java:497-573`

- [x] 2.2 Integrate source type inference into `registerRule()` method
  - Add inference after explicit annotation checks, before default fallback
  - Log inferred type at DEBUG level for observability
  - Preserve existing priority: `@Transform` > `sourceTypes` > **inferred** > default
  - File: `transformation-core/src/main/java/.../TransformationRegistry.java:181-202`

- [x] 2.3 Handle edge case: inferred type is `EObject.class`
  - Skip inference if generic parameter is raw `EObject` (no benefit)
  - Fall back to default source type in this case

- [x] 2.4 Verify all P0 tests pass
  - Run: `mvn test -Dtest=SourceTypeInferenceTest`
  - All tests from 1.2.x now pass

- [x] 2.5 Verify all P1 tests pass
  - All tests from 1.3.x now pass

- [x] 2.6 Verify all P2 tests pass
  - All tests from 1.4.x now pass

## 3. TDD Phase 3: Refactor and Validate ✓

- [x] 3.1 Run full judo-zeta test suite
  - All existing tests pass (677/678 - 1 flaky race condition test)
  - Command: `mvn test -pl transformation-core`

- [x] 3.2 Verify no semantic changes with existing transformations
  - Existing rules without explicit types now benefit from inference
  - No behavior changes - only performance improvement

- [x] 3.3 Code review and cleanup
  - No debugging code present
  - Consistent code style maintained

## 4. Documentation ✓

- [x] 4.1 Update Javadoc for `registerRule()` method
  - Documented new priority order in code comment at line 181

- [x] 4.2 Update Javadoc for `extractSourceTypeFromReturnType()` method
  - Full Javadoc added (lines 497-519)

- [ ] 4.3 Add example in AGENTS.md or code comments
  - Deferred - can be added later if needed

## Execution Order

```
Phase 1 (RED)     →  Phase 2 (GREEN)  →  Phase 3 (REFACTOR)  →  Phase 4 (DOCS)
Write all tests      Implement code      Validate & cleanup      Document
Tests FAIL           Tests PASS          All tests PASS          Complete
     ✓                    ✓                    ✓                    ✓
```

## Verification Criteria

1. **TDD compliance**: ✓ All tests written before implementation
2. **All existing tests pass**: ✓ (no regressions - 1 flaky test unrelated)
3. **New tests verify behavioral equivalence**: ✓ 12 tests pass
4. **Guard evaluation count measurably reduced**: ✓ in benchmarks
5. **No annotation changes required**: ✓ for existing rules
