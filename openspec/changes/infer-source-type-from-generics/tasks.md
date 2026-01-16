# Tasks: Infer Source Type from Generic Parameters

**IMPORTANT: TDD Approach** - Write tests FIRST, then implement to make them pass.

## 1. TDD Phase 1: Write Failing Tests (RED)

All tests in this section should be written BEFORE any implementation code.
Tests will initially fail because the feature doesn't exist yet.

### 1.1 Create Test Infrastructure

- [ ] 1.1.1 Create `SourceTypeInferenceTest.java` test class
  - File: `transformation-core/src/test/java/.../SourceTypeInferenceTest.java`
  - Set up test fixtures with source/target ResourceSets
  - Create helper methods for dynamic EClass/EPackage creation

### 1.2 Core Functionality Tests (P0 - Must Pass)

- [ ] 1.2.1 Test: Basic generic type extraction
  - Rule with `TransformFunction<EClass, EPackage>` should infer `EClass`
  - Verify `rule.getSourceType()` returns inferred type
  - **Expected**: FAIL (method doesn't exist yet)

- [ ] 1.2.2 Test: Explicit `@Transform` annotation takes priority
  - Rule with both generic `TransformFunction<EObject, X>` and `@Transform(type = EClass.class)`
  - Verify `@Transform` wins over generic inference
  - **Expected**: PASS (existing behavior)

- [ ] 1.2.3 Test: Explicit `sourceTypes` attribute takes priority
  - Rule with both generic and `@TransformRule(sourceTypes = {EClass.class})`
  - Verify `sourceTypes` wins over generic inference
  - **Expected**: PASS (existing behavior)

- [ ] 1.2.4 Test: Greedy rule with supertype correctly matches subtypes
  - Rule: `TransformFunction<EClassifier, EPackage>` with `@Greedy`
  - Element: `EClass` (subtype of `EClassifier`)
  - Verify rule is included in `getEagerRulesForType(EClass.class)`
  - **Expected**: FAIL (inference not implemented)

- [ ] 1.2.5 Test: Non-greedy rule with inferred type matches exact type only
  - Rule: `TransformFunction<EClass, EPackage>` (no `@Greedy`)
  - Element A: `EClass` instance - should match
  - Element B: subtype instance - should NOT match (type-of semantics)
  - **Expected**: FAIL (inference not implemented)

- [ ] 1.2.6 Test: Behavioral equivalence verification
  - For each rule, verify type filtering produces identical element sets as `appliesTo()`
  - Use mixed-type element collection (EClass, EDataType, EAttribute, etc.)
  - Assert: `filter(appliesTo) == filter(typeIndex)` for all rules
  - **Expected**: FAIL (inference not implemented)

### 1.3 Edge Case Tests (P1)

- [ ] 1.3.1 Test: Raw EObject generic parameter falls back to default
  - Rule: `TransformFunction<EObject, EPackage>`
  - Should use `@TransformationContext` default (inference provides no benefit)
  - **Expected**: PASS (fallback behavior)

- [ ] 1.3.2 Test: Multi-source rules (multiple `@Transform`) unaffected
  - Rules with multiple `@Transform` annotations continue to work
  - Inference only applies when no explicit transforms defined
  - **Expected**: PASS (existing behavior)

- [ ] 1.3.3 Test: Lazy rules correctly filtered
  - Verify `getLazyRulesForType()` respects inferred source types
  - **Expected**: FAIL (inference not implemented)

### 1.4 Performance Validation Tests (P2)

- [ ] 1.4.1 Test: Guard evaluation count reduction
  - Create scenario with many types and targeted rules
  - Verify guard evaluations reduced from O(N*R) to O(N*M) where M << R
  - Use `TransformationMetrics` to track
  - **Expected**: FAIL (no optimization yet)

- [ ] 1.4.2 Test: Type index cache efficiency
  - Verify `eagerRulesByTypeCache` and `lazyRulesByTypeCache` work with inferred types
  - Confirm O(1) lookup after initial computation
  - **Expected**: FAIL (inference not implemented)

## 2. TDD Phase 2: Implementation (GREEN)

Only start this phase after ALL tests from Phase 1 are written.

- [ ] 2.1 Add `extractSourceTypeFromReturnType(Method)` method in `TransformationRegistry.java`
  - Mirror existing `extractTargetTypeFromReturnType()` implementation
  - Extract first generic parameter from `TransformFunction<S, T>`
  - Handle `Class`, `ParameterizedType`, and `WildcardType` cases
  - File: `transformation-core/src/main/java/.../TransformationRegistry.java:497-547`

- [ ] 2.2 Integrate source type inference into `registerRule()` method
  - Add inference after explicit annotation checks, before default fallback
  - Log inferred type at DEBUG level for observability
  - Preserve existing priority: `@Transform` > `sourceTypes` > **inferred** > default
  - File: `transformation-core/src/main/java/.../TransformationRegistry.java:174-216`

- [ ] 2.3 Handle edge case: inferred type is `EObject.class`
  - Skip inference if generic parameter is raw `EObject` (no benefit)
  - Fall back to default source type in this case

- [ ] 2.4 Verify all P0 tests pass
  - Run: `mvn test -Dtest=SourceTypeInferenceTest`
  - All tests from 1.2.x should now pass

- [ ] 2.5 Verify all P1 tests pass
  - All tests from 1.3.x should now pass

- [ ] 2.6 Verify all P2 tests pass
  - All tests from 1.4.x should now pass

## 3. TDD Phase 3: Refactor and Validate

- [ ] 3.1 Run full judo-zeta test suite
  - All existing tests must pass (behavioral equivalence)
  - Command: `mvn test -pl transformation-core`

- [ ] 3.2 Verify no semantic changes with existing transformations
  - Existing rules without explicit types now benefit from inference
  - No behavior changes - only performance improvement

- [ ] 3.3 Code review and cleanup
  - Remove any debugging code
  - Ensure consistent code style

## 4. Documentation

- [ ] 4.1 Update Javadoc for `registerRule()` method
  - Document new priority order: `@Transform` > `sourceTypes` > generic inference > default

- [ ] 4.2 Update Javadoc for `extractSourceTypeFromReturnType()` method
  - Document extraction logic and edge cases

- [ ] 4.3 Add example in AGENTS.md or code comments
  - Show how `TransformFunction<EntityType, EClass>` enables automatic optimization

## Execution Order

```
Phase 1 (RED)     →  Phase 2 (GREEN)  →  Phase 3 (REFACTOR)  →  Phase 4 (DOCS)
Write all tests      Implement code      Validate & cleanup      Document
Tests FAIL           Tests PASS          All tests PASS          Complete
```

## Verification Criteria

1. **TDD compliance**: All tests written before implementation
2. **All existing tests pass** (no regressions)
3. **New tests verify behavioral equivalence**
4. **Guard evaluation count measurably reduced** in benchmarks
5. **No annotation changes required** for existing rules
