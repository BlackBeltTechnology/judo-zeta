# Tasks: Adapt Ecore Transformation Test Patterns

## 1. Foundation - Test Infrastructure

- [x] 1.1 Create `AbstractEcoreTransformationTest` base class with common setup/teardown
- [x] 1.2 Add helper methods for creating EClass, EAttribute, EReference, EOperation
- [x] 1.3 Add assertion helpers for verifying EClass structure, features, inheritance
- [x] 1.4 Create `TestModelProvider` implementation for Ecore-based tests

## 2. Realistic Ecore Model Generator

- [x] 2.1 Create `RealisticEcoreModelGenerator` class with Config builder
- [x] 2.2 Implement package generation with configurable count
- [x] 2.3 Implement EClass generation with configurable attributes/references/operations
- [x] 2.4 Implement inheritance hierarchy generation with configurable depth
- [x] 2.5 Implement cross-package reference generation
- [x] 2.6 Implement bidirectional reference generation (eOpposite pairs)
- [x] 2.7 Implement attribute type distribution (String, Integer, Boolean, Date, Double)
- [x] 2.8 Implement reference cardinality distribution (single/collection, required/optional)
- [x] 2.9 Implement containment vs association distribution
- [x] 2.10 Add deterministic generation with fixed seed
- [x] 2.11 Add `scaled(int targetElements)` factory method
- [x] 2.12 Add `getStatistics()` method returning generation metrics
- [x] 2.13 Add model validation after generation

## 3. Inheritance Transformation Tests

- [x] 3.1 Create `EcoreInheritanceTest` test class
- [x] 3.2 Add test for abstract class transformation preserves abstract flag
- [x] 3.3 Add test for multi-level inheritance chain (3+ levels)
- [x] 3.4 Add test for inherited feature visibility (getEAllAttributes, getEAllReferences)
- [x] 3.5 Add test for abstract rule with @Extends for common features
- [x] 3.6 Add test for rule inheritance matching class inheritance
- [x] 3.7 Add test for multiple supertypes (interface-like inheritance)
- [x] 3.8 Add test for diamond inheritance pattern

## 4. Relation Cardinality Tests

- [x] 4.1 Create `EcoreRelationCardinalityTest` test class
- [x] 4.2 Add test for optional single reference (0..1)
- [x] 4.3 Add test for required single reference (1..1)
- [x] 4.4 Add test for bounded collection reference (0..N)
- [x] 4.5 Add test for unbounded collection reference (0..*)
- [x] 4.6 Add test for required collection reference (N..*)
- [x] 4.7 Add test for containment reference preserves containment flag
- [x] 4.8 Add test for containment with bidirectional opposite
- [x] 4.9 Add test for association (non-containment) reference
- [x] 4.10 Add test for cross-package association
- [x] 4.11 Add test for one-to-one bidirectional reference
- [x] 4.12 Add test for one-to-many bidirectional reference
- [x] 4.13 Add test for many-to-many bidirectional reference
- [x] 4.14 Add test for reference target resolution via equivalent()
- [x] 4.15 Add test for lazy resolution of reference targets
- [x] 4.16 Add test for self-references (same class)
- [x] 4.17 Add test for circular reference chain (A→B→C→A)

## 5. Projection Transformation Tests

- [x] 5.1 Create `EcoreProjectionTest` test class
- [x] 5.2 Add test for subset attribute projection (entity → TO with selected attrs)
- [x] 5.3 Add test for projection with renamed attributes
- [x] 5.4 Add test for subset reference projection
- [x] 5.5 Add test for projection with cardinality change
- [x] 5.6 Add test for guard-based projection selection by annotation
- [x] 5.7 Add test for guard-based projection selection by naming convention
- [x] 5.8 Add test for transfer attribute binding to source attribute
- [x] 5.9 Add test for unmapped projection (standalone DTO)
- [x] 5.10 Add test for nested projection (embedded transfer objects)
- [x] 5.11 Add test for derived attribute in projection
- [x] 5.12 Add test for calculated reference in projection

## 6. Performance Benchmark Tests

- [x] 6.1 Create `EcorePerformanceBenchmarkTest` test class with @Tag("performance")
- [x] 6.2 Add small model benchmark (20 classes, untagged for CI)
- [x] 6.3 Add medium model benchmark (100 classes, @Tag("performance"))
- [x] 6.4 Add large model benchmark (500 classes, @Tag("performance") @Tag("slow"))
- [x] 6.5 Add throughput measurement (elements/second)
- [x] 6.6 Add sequential vs parallel comparison benchmark
- [x] 6.7 Add memory usage tracking
- [x] 6.8 Add scalability measurement (linear scaling verification)
- [x] 6.9 Add warmup run before measurement
- [x] 6.10 Add structured benchmark result logging
- [x] 6.11 Add baseline comparison (regression detection)

## 7. Documentation and Integration

- [x] 7.1 Add Javadoc to `RealisticEcoreModelGenerator` public API
- [x] 7.2 Add README section explaining test patterns and how to use generator
- [x] 7.3 Verify all tests pass with `mvn test -pl transformation-core`
- [x] 7.4 Verify performance tests can be run with `-Dtest=EcorePerformanceBenchmarkTest`
- [x] 7.5 Update CLAUDE.md or agent-docs if patterns are generally useful
