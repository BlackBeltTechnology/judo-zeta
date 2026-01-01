# Tasks: Add Resource Alias Support

**Change ID**: `add-resource-alias-support`  
**Status**: Implemented

## Overview

Implementation tasks for adding resource alias support to transformation and validation frameworks.

## Task Breakdown

### Phase 1: Core Infrastructure

#### [x] Task 1.1: Add resource registry to TransformationContext
- Add `resourceRegistry` field (ConcurrentHashMap<String, ResourceSet>)
- Register "source" and "target" aliases in constructor
- Add `registerResource(String alias, ResourceSet resourceSet)` method
- Add `getResource(String alias)` method with error handling
- **Validation**: Unit tests for registry operations

#### [x] Task 1.2: Add all(alias, Class) method to TransformationContext
- Implement `all(String alias, Class<T> type)` method
- Delegate to `modelProvider.getAllContents(getResource(alias), type)`
- Update `getAllSource(Class<T>)` to delegate to `all("source", type)`
- **Validation**: Unit tests comparing `getAllSource()` with `all("source", ...)`

#### [x] Task 1.3: Add create(Class) method to TransformationContext
- Implement `create(Class<T> type)` method that creates element without containment
- Use targetPackage to create instance via EFactory
- Do NOT add to any resource (element has no container)
- Keep existing `createTarget(Class<T>)` for backward compatibility
- **Validation**: Unit tests verifying element created but not contained

#### [x] Task 1.4: Add resource registry to ValidationContext
- Add `resourceRegistry` field (ConcurrentHashMap<String, ResourceSet>)
- Register "source" alias in constructor
- Add `registerResource(String alias, ResourceSet resourceSet)` method
- Add `getResource(String alias)` method with error handling
- Add `all(String alias, Class<T> type)` method
- Update `getAllInstances(Class<T>)` to delegate to `all("source", type)`
- **Validation**: Unit tests for registry operations

---

### Phase 2: New Annotations

#### [x] Task 2.1: Create @Transform annotation
- Create `@Transform` annotation with `alias()` (default "source") and `type()` attributes
- Create `@Transforms` container annotation for repeatability
- **Validation**: Compile and verify annotation accessible via reflection

#### [x] Task 2.2: Create @To annotation
- Create `@To` annotation with `alias()` (default "target") and `type()` attributes
- Create `@Tos` container annotation for repeatability
- **Validation**: Compile and verify annotation accessible via reflection

#### [x] Task 2.3: Update @Constraint annotation
- Add `resourceAlias()` attribute with default "source"
- **Validation**: Compile and verify annotation attribute accessible via reflection

#### [x] Task 2.4: Update @Critique annotation
- Add `resourceAlias()` attribute with default "source"
- **Validation**: Compile and verify annotation attribute accessible via reflection

---

### Phase 3: Descriptor Changes

#### [x] Task 3.1: Create TransformDefinition and ToDefinition classes
- Create `TransformDefinition` class with `alias` and `type` fields
- Create `ToDefinition` class with `alias` and `type` fields
- **Validation**: Unit tests for definition classes

#### [x] Task 3.2: Update TransformRuleDescriptor
- Add `List<TransformDefinition> transforms` field
- Add `List<ToDefinition> tos` field
- Add getters for both fields
- Update builder/factory to extract from @Transform/@To annotations
- Fall back to sourceTypes/targetTypes with default aliases for backward compatibility
- **Validation**: Unit tests for descriptor creation with annotations

#### [x] Task 3.3: Update ValidatorDescriptor
- Add `resourceAlias` field
- Add getter for field
- Update builder/factory to extract alias from annotation
- **Validation**: Unit tests for descriptor creation with alias

---

### Phase 4: Registry Changes

#### [x] Task 4.1: Update TransformationRegistry
- Extract @Transform and @To annotations when registering rules
- Build TransformDefinition and ToDefinition lists
- Handle backward compatibility with sourceTypes/targetTypes
- **Validation**: Unit tests for rule registration with annotations

#### [x] Task 4.2: Update ValidationRegistry
- Extract `resourceAlias` when registering validators
- Store in ValidatorDescriptor
- **Validation**: Unit tests for validator registration with alias

---

### Phase 5: Executor Changes

#### [x] Task 5.1: Update TransformationExecutor to use source aliases
- Added `transform()` method that collects elements based on rule definitions
- Added `isFromExpectedAlias()` check in `executeEagerRulesFor()`
- Use `context.all(source.getAlias(), source.getType())` for element collection
- **Validation**: Integration test with multi-alias transformation

#### [x] Task 5.2: Update ValidationExecutor to use aliases
- Added `validate()` method that collects elements based on validator definitions
- Added `isFromExpectedAlias()` check in validation methods
- Use `context.all(resourceAlias, ElementType.class)` for element collection
- **Validation**: Integration test with multi-alias validation

---

### Phase 6: Testing

#### [x] Task 6.1: Backward compatibility tests
- Verify all existing transformation tests pass unchanged
- Verify all existing validation tests pass unchanged
- Test rules without @Transform/@To use sourceTypes/targetTypes
- Test rules without explicit aliases use defaults
- **Validation**: All existing tests green

#### [x] Task 6.2: Multi-model transformation test
- Created ResourceAliasTest with tests for multiple aliases
- Tests verify elements from all resources accessible via aliases
- **Validation**: Test passes with correct element resolution

#### [x] Task 6.3: create() vs createTarget() test
- Test `create()` creates element without containment
- Test `createTarget()` adds to target resource root
- Test explicit containment setting via `parent.getChildren().add()`
- **Validation**: Tests verify correct containment behavior

#### [x] Task 6.4: Multi-model validation test
- Added Resource Alias Tests to ValidationContextTest
- Tests verify elements from aliased resources accessible
- **Validation**: Test passes with correct element resolution

#### [x] Task 6.5: Error handling tests
- Test unknown alias throws clear exception
- Test exception message includes available aliases
- **Validation**: Tests verify error messages

#### [x] Task 6.6: Parallel execution tests
- Existing parallel tests continue to pass
- Registry uses ConcurrentHashMap for thread-safety
- **Validation**: No race conditions under stress

---

### Phase 6b: Annotation Tests

#### [x] Task 6.7: Single @Transform annotation tests
- Test rule with single `@Transform(type = EntityType.class)` uses default "source" alias
- Test rule with single `@Transform(alias = "asm", type = EntityType.class)` uses custom alias
- Verify TransformDefinition is correctly created with alias and type
- **Validation**: Unit tests pass (TransformToAnnotationTest)

#### [x] Task 6.8: Multiple @Transform annotations tests
- Test rule with multiple `@Transform` annotations from different aliases
- Verify Cartesian product behavior for multi-source rules
- Test element collection from each aliased resource
- **Validation**: Unit tests pass with elements from multiple resources (TransformToAnnotationTest)

#### [x] Task 6.9: Single @To annotation tests
- Test rule with single `@To(type = Table.class)` uses default "target" alias
- Test rule with single `@To(alias = "rdbms", type = Table.class)` uses custom alias
- Verify ToDefinition is correctly created with alias and type
- **Validation**: Unit tests pass (TransformToAnnotationTest)

#### [x] Task 6.10: Multiple @To annotations tests
- Test rule with multiple `@To` annotations to different aliases
- Test rule can declare multiple target types
- Verify ToDefinition list contains all declared targets
- **Validation**: Unit tests pass (TransformToAnnotationTest)

#### [x] Task 6.11: Combined @Transform and @To annotation tests
- Test rule using both `@Transform` and `@To` annotations together
- Verify TransformRuleDescriptor contains both transforms and tos lists
- Test end-to-end transformation with custom aliases on both source and target
- **Validation**: Integration test passes (TransformToAnnotationTest)

#### [x] Task 6.12: Annotation backward compatibility tests
- Test rules using `sourceTypes` attribute still work (default "source" alias)
- Test rules using `targetTypes` attribute still work (default "target" alias)
- Test mixed usage: `@Transform` with `targetTypes` attribute
- Test mixed usage: `sourceTypes` attribute with `@To`
- **Validation**: All backward compatibility scenarios pass (TransformToAnnotationTest)

#### [x] Task 6.13: Annotation reflection tests
- Test `@Transform` annotations are correctly extracted via `getAnnotationsByType()`
- Test `@Transforms` container annotation works for multiple `@Transform`
- Test `@To` annotations are correctly extracted via `getAnnotationsByType()`
- Test `@Tos` container annotation works for multiple `@To`
- **Validation**: Reflection tests pass (TransformToAnnotationTest)

#### [x] Task 6.14: Registry annotation processing tests
- Test TransformationRegistry extracts `@Transform` annotations into TransformDefinition list
- Test TransformationRegistry extracts `@To` annotations into ToDefinition list
- Test priority: `@Transform` annotations override `sourceTypes` attribute
- Test priority: `@To` annotations override `targetTypes` attribute
- Test fallback to `sourceTypes`/`targetTypes` when no annotations present
- **Validation**: Registry tests pass (TransformToAnnotationTest)

---

### Phase 7: Documentation

#### [x] Task 7.1: Update transformation documentation
- Created docs/transformation/user-guide/resource-aliases.md
- Document resource alias registration API
- Document @Transform and @To annotations with full API reference
- Document `create()` vs `createTarget()` difference
- Add multi-model transformation examples
- Added comprehensive "Annotation Equivalence with @TransformRule Attributes" section
- Added priority rules for annotation processing
- Added quick reference table comparing legacy and new styles
- **Validation**: Documentation renders correctly

#### [x] Task 7.2: Update validation documentation
- Included in resource-aliases.md
- Document resource alias registration API
- Document @Constraint/@Critique resourceAlias attribute
- Add multi-model validation example
- **Validation**: Documentation renders correctly

## Dependencies

- Task 1.x (Core Infrastructure) must complete first
- Task 2.x (Annotations) can run in parallel with Task 1.x
- Task 3.x (Descriptors) depends on Task 2.x completion
- Task 4.x (Registries) depends on Task 3.x completion
- Task 5.x (Executors) depends on Tasks 1.x, 3.x, and 4.x completion
- Task 6.x (Testing) depends on all Phase 1-5 tasks
- Task 7.x (Documentation) can start after Phase 5 completion

## Estimated Effort

| Phase | Tasks | Estimated Hours |
|-------|-------|-----------------|
| Phase 1 | Core Infrastructure | 4-5 |
| Phase 2 | New Annotations | 2-3 |
| Phase 3 | Descriptor Changes | 2-3 |
| Phase 4 | Registry Changes | 2-3 |
| Phase 5 | Executor Changes | 2-3 |
| Phase 6 | Testing | 4-5 |
| Phase 7 | Documentation | 2-3 |
| **Total** | | **18-25** |

## Implementation Summary

All tasks have been completed. The following files were created or modified:

### New Files
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/Transform.java`
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/Transforms.java`
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/To.java`
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/Tos.java`
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformDefinition.java`
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/ToDefinition.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/ResourceAliasTest.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/TransformToAnnotationTest.java` - Comprehensive annotation tests
- `docs/transformation/user-guide/resource-aliases.md`

### Modified Files
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/Constraint.java` - Added resourceAlias attribute
- `zeta-annotations/src/main/java/hu/blackbelt/judo/zeta/annotation/Critique.java` - Added resourceAlias attribute
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java` - Added resource registry
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformRuleDescriptor.java` - Added transforms/tos lists
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationRegistry.java` - Extract @Transform/@To annotations
- `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationExecutor.java` - Added transform() and isFromExpectedAlias()
- `validation-core/src/main/java/hu/blackbelt/judo/meta/validation/core/ValidationContext.java` - Added resource registry
- `validation-core/src/main/java/hu/blackbelt/judo/meta/validation/core/ValidatorDescriptor.java` - Added resourceAlias field
- `validation-core/src/main/java/hu/blackbelt/judo/meta/validation/core/ValidationRegistry.java` - Extract resourceAlias
- `validation-core/src/main/java/hu/blackbelt/judo/meta/validation/core/ValidationExecutor.java` - Added validate() and isFromExpectedAlias()
- `validation-core/src/test/java/hu/blackbelt/judo/zeta/validation/core/ValidationContextTest.java` - Added Resource Alias Tests
