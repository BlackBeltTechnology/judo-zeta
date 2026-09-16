# Proposal: Add ETL Pattern Integration Tests

**Change ID**: `add-etl-pattern-integration-tests`  
**Status**: Draft  
**Created**: 2025-12-09  
**Type**: Testing

## Summary

Add comprehensive integration tests that validate the resource alias feature against real-world ETL patterns found in judo-tatami transformations. The existing tests cover basic alias registration and annotation processing, but lack end-to-end integration tests that mirror actual ETL transformation scenarios.

## Motivation

Analysis of 116 ETL files across judo-tatami-base, judo-tatami-client, and judo-tatami reveals sophisticated multi-model transformation patterns:

1. **Multi-Model Access**: Transformations routinely access 3-5 models (ESM, PSM, ASM, RDBMS, MAPPING)
2. **Cross-Model Lookups**: Heavy use of `equivalent()` to navigate transformation traces across models
3. **Complex Guards**: Guard conditions that query multiple aliased resources
4. **Lazy/Greedy Rules**: Both patterns used extensively with multi-model access
5. **Rule Inheritance**: Abstract base rules with specialized variants accessing different aliases
6. **Pre/Post Hooks**: Global initialization and post-processing across models

Current test coverage focuses on:
- Basic alias registration (`ResourceAliasTest`)
- Annotation reflection and registry processing (`TransformToAnnotationTest`)

Missing coverage:
- End-to-end transformation execution with multiple aliased resources
- `equivalent()` lookups across aliased models
- Guards that access aliased resources
- Lazy/Greedy rule execution with aliases
- Rule inheritance combined with aliases
- Pre/post hooks that manage aliased resources

## Goals

1. **End-to-End Integration Tests**: Validate complete transformation pipelines with multiple aliased resources
2. **ETL Pattern Coverage**: Test patterns directly observed in judo-tatami ETL files
3. **Cross-Model Equivalence**: Test `equivalent()` lookups across aliased models
4. **Guard Integration**: Test guards that query multiple aliased resources
5. **Lazy/Greedy with Aliases**: Test both execution modes with aliased resources
6. **Rule Inheritance with Aliases**: Test `@Extends` combined with `@Transform`/`@To`
7. **Hook Integration**: Test pre/post hooks that register and access aliased resources

## Non-Goals

- Testing ETL syntax parsing (we use Java annotations)
- Testing Epsilon runtime compatibility
- Performance benchmarking
- Using actual judo metamodels (PSM, ASM, RDBMS) - tests use Ecore only

## Design Decisions

### DD-1: Use Ecore Metamodel Only
All tests use Ecore metamodel (EClass, EAttribute, EReference, EPackage, EAnnotation) to simulate transformation scenarios. This keeps tests self-contained without external dependencies.

### DD-2: Cartesian Product for Multiple @Transform
When a rule has multiple `@Transform` annotations, the framework should produce a Cartesian product of source elements. For example:
```java
@Transform(alias = "asm", type = EClass.class)
@Transform(alias = "mapping", type = EAnnotation.class)
```
If "asm" has 3 EClass and "mapping" has 2 EAnnotation, the rule fires 6 times (3 x 2).

### DD-3: Separate Test File
Tests will be in a dedicated `ETLPatternIntegrationTest.java` file, separate from existing `ResourceAliasTest.java` and `TransformToAnnotationTest.java`.

## Scope

### In Scope

- Integration test class: `ETLPatternIntegrationTest`
- Test scenarios based on actual judo-tatami patterns:
  - ASM2RDBMS-like transformation (3 models: ASM, RDBMS, MAPPING)
  - Cross-model equivalence lookups
  - Guard conditions accessing multiple aliases
  - Lazy rule invocation with aliased resources
  - Greedy rules with multiple target aliases
  - Abstract rules with alias-specific extensions
  - Pre-execution hooks registering resources
  - Post-execution hooks accessing transformed elements

### Out of Scope

- Testing actual judo-tatami transformations (different project)
- Testing Epsilon ETL compatibility layer

## ETL Patterns to Test

Based on judo-tatami analysis:

### Pattern 1: Multi-Model Transformation Chain
```
ESM!EntityType → PSM!TransferObjectType → ASM!EClass → RDBMS!RdbmsTable
```
Test: Register 3+ aliases, transform across all with correct element resolution.

### Pattern 2: Cross-Model Lookup via Equivalent
```etl
s.eContainingClass.equivalent("EClassToRdbmsTable").fields.add(t);
```
Test: `equivalent()` returns correct element from aliased target resource.

### Pattern 3: Guard with Multi-Model Access
```etl
guard: s.eReferenceType.isEntityType() and 
       s.ruleMapping().foreignKey and 
       not s.derived
```
Test: Guard queries element from "mapping" alias to determine applicability.

### Pattern 4: Lazy Rule with Alias
```etl
@lazy
rule EReferenceToRdbmsJunctionTable
    transform s : ASM!EReference
    to t : RDBMS!RdbmsJunctionTable
```
Test: Lazy rule creates element in aliased target on-demand via `equivalent()`.

### Pattern 5: Greedy Rule with Multiple Targets
```etl
@greedy
rule CreateDocumentationAnnotation
    transform s : JUDOPSM!TransferObjectType
    to t : ASM!EAnnotation
```
Test: Greedy rule fires for all matching elements from aliased source.

### Pattern 6: Rule Inheritance with Aliases
```etl
@abstract
rule AddAttributeConstraints
    transform s : JUDOPSM!Attribute
    to t : ASM!EAnnotation

rule AddStringAttributeConstraints
    extends AddAttributeConstraints
    guard: s.dataType.isKindOf(JUDOPSM!StringType)
```
Test: Abstract rule with `@Transform` alias, extended rules with guards.

### Pattern 7: Pre-Hook Resource Registration
```etl
pre {
    var mappingModel = loadModel("mapping.xml");
}
```
Test: `@PreExecution` hook registers additional aliased resources.

### Pattern 8: Post-Hook Cross-Model Processing
```etl
post {
    for (e in RDBMS!RdbmsElement.all) {
        var mapping = MAPPING!NameMapping.all.selectOne(m | m.id == e.uuid);
        e.sqlName = mapping.rdbmsName;
    }
}
```
Test: `@PostExecution` hook iterates aliased resources to apply mappings.

## Benefits

1. **Confidence**: Validates that resource alias feature supports real ETL patterns
2. **Regression Prevention**: Catches issues before judo-tatami migration
3. **Documentation**: Tests serve as examples of supported patterns
4. **Completeness**: Fills gaps in existing test coverage

## Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Test complexity | Medium | Medium | Use Ecore metamodel for simplicity |
| Maintenance burden | Low | Low | Well-documented, focused tests |
| False sense of security | Medium | Low | Tests based on actual ETL patterns |

## Dependencies

- Completed `add-resource-alias-support` implementation (done)
- Ecore metamodel available for test fixtures

## References

- ETL patterns analyzed from: `judo-tatami-base/judo-tatami-asm2rdbms/src/main/epsilon/`
- ETL patterns analyzed from: `judo-tatami/judo-tatami-esm2psm/src/main/epsilon/`
- Existing tests: `transformation-core/src/test/java/.../ResourceAliasTest.java`
- Existing tests: `transformation-core/src/test/java/.../TransformToAnnotationTest.java`
