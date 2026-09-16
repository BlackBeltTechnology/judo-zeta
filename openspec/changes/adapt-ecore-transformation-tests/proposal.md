# Proposal: Adapt Ecore Transformation Test Patterns

## Why

The judo-tatami projects contain comprehensive test patterns for model transformations (ESM→PSM→ASM pipeline) that are currently unavailable in judo-zeta. These patterns include inheritance transformations, relation cardinality handling, projection/transfer object patterns, and realistic model generation. Adapting these patterns to use Ecore models as both source and target will provide:

1. **Production-realistic test coverage** - Testing scenarios that mirror real-world transformation complexity
2. **Reference implementations** - Demonstrating how to write transformation rules for common patterns
3. **Performance benchmarking** - Realistic models for performance testing
4. **Documentation through tests** - Tests serve as executable documentation for framework capabilities

## What Changes

### New Test Infrastructure
- **Realistic Ecore Model Generator** - Generate configurable Ecore models with inheritance, relations, operations (similar to tatami's `RealisticPsmModelGenerator`)
- **Model Comparison Utilities** - Structural comparison for Ecore models (adapted from tatami's `ModelComparator`)
- **Performance Test Harness** - Benchmark infrastructure for large-scale transformations

### New Test Categories
- **Inheritance Transformation Tests** - Abstract classes, multi-level inheritance, inherited features
- **Relation Cardinality Tests** - One-way/two-way, containment/association, single/collection
- **Projection/Transfer Object Tests** - Entity → subset projection, mapped/unmapped transfer objects
- **Derived Feature Tests** - Derived attributes, calculated references, lazy navigation properties
- **Cross-Package Reference Tests** - References between elements in different packages

### Test Infrastructure Improvements
- Common base classes for transformation tests (similar to `AbstractDualTransformationTest`)
- Parameterized tests for rule variations (similar to tatami's `@ParameterizedTest` patterns)
- Synthetic model generation with reproducible seeds

## Capabilities

### New Capabilities

- `ecore-inheritance-transformation`: Test patterns for EClass inheritance hierarchies (abstract classes, multi-level inheritance, feature inheritance)
- `ecore-relation-cardinality`: Test patterns for EReference cardinality variations (single/collection, containment/association, bidirectional)
- `ecore-projection-transformation`: Test patterns for entity-to-transfer-object projections (subset features, mapped/unmapped types)
- `realistic-ecore-generator`: Synthetic Ecore model generation with configurable structure (packages, classes, attributes, references, operations, inheritance depth)
- `ecore-performance-benchmarks`: Performance benchmark tests using realistic Ecore models

### Modified Capabilities

- `synthetic-model-generation`: Extend existing spec to support Ecore-specific generation patterns (EClass hierarchies, EReference patterns, EOperation generation)

## Impact

### New Files
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/EcoreInheritanceTest.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/EcoreRelationCardinalityTest.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/EcoreProjectionTest.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/RealisticEcoreModelGenerator.java`
- `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/EcorePerformanceBenchmarkTest.java`

### Dependencies
- Uses existing `EcorePackage.eINSTANCE` as source/target metamodel
- Leverages existing `TransformationExecutor`, `TransformationContext`, `TransformationRegistry`
- Extends existing test infrastructure (JUnit 5 patterns, `TestModelProvider`)

### No Breaking Changes
- All changes are additive (new test classes only)
- No modifications to production code
- No API changes
