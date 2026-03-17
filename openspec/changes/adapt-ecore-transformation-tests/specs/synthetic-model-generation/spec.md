# synthetic-model-generation Delta Specification

## Purpose

Extend existing synthetic model generation spec to support Ecore-specific generation patterns (EClass hierarchies, EReference patterns, EOperation generation) as used by the realistic-ecore-generator.

## MODIFIED Requirements

### Requirement: Generate configurable synthetic PSM-style models

The synthetic model generator SHALL create EMF models with configurable structural parameters: number of packages, classes per package, attributes per class, references per class, operations per class, inheritance depth, and cross-reference density.

#### Scenario: Generate small model
- **WHEN** generator is configured with 1 package, 10 classes, 5 attributes each, 2 references each
- **THEN** generator creates a ResourceSet containing exactly 1 EPackage with 10 EClasses
- **AND** each EClass has exactly 5 EAttributes
- **AND** each EClass has exactly 2 EReferences

#### Scenario: Generate model with operations
- **WHEN** generator is configured with operationsPerClass=3
- **THEN** each EClass has exactly 3 EOperations
- **AND** each EOperation has at least one EParameter

#### Scenario: Generate model with inheritance
- **WHEN** generator is configured with inheritanceDepth=3
- **THEN** generated model contains inheritance hierarchies
- **AND** maximum inheritance chain length is 3
- **AND** approximately 10% of classes are abstract

#### Scenario: Generate model with cross-package references
- **WHEN** generator is configured with multiple packages and referencesPerClass > 0
- **THEN** some EReferences point to EClasses in different packages
- **AND** cross-package references are valid

#### Scenario: Generate model with bidirectional references
- **WHEN** generator is configured with bidirectionalReferenceRatio=0.3
- **THEN** approximately 30% of EReferences have valid eOpposite
- **AND** bidirectional pairs are correctly formed

---

### Requirement: Support realistic cross-reference patterns

The synthetic model generator SHALL create cross-references between classes following configurable patterns: random, hierarchical (parent-child), or circular (mutual references).

#### Scenario: Hierarchical references
- **WHEN** generator is configured with hierarchical reference pattern
- **THEN** generated references form a directed acyclic graph (no cycles)
- **AND** child classes reference parent classes (containment)

#### Scenario: Random references
- **WHEN** generator is configured with random reference pattern
- **THEN** references are randomly distributed across classes
- **AND** reference targets are uniformly distributed

#### Scenario: Circular references
- **WHEN** generator is configured with circular reference pattern
- **THEN** some classes have mutual references (A→B and B→A)
- **AND** circular reference chains are supported

---

### Requirement: Deterministic model generation with fixed seed

The synthetic model generator SHALL use a fixed random seed to ensure reproducible model generation across multiple runs.

#### Scenario: Reproducible models
- **WHEN** generator is configured with seed=42 and same parameters
- **AND** generator is executed twice
- **THEN** both generated models have identical structure (same element counts, same names, same references)

#### Scenario: Scaled generation maintains reproducibility
- **WHEN** generator.scaled(1000, seed=42) is called
- **AND** generator.scaled(1000, seed=42) is called again
- **THEN** both models have identical structure

---

### Requirement: Support type distribution configuration

The synthetic model generator SHALL allow configuration of the distribution of element types: entity classes vs transfer object classes vs enumeration types.

#### Scenario: Mixed type distribution
- **WHEN** generator is configured with 50% entities, 30% transfer objects, 20% enumerations
- **THEN** generated model reflects approximately the specified ratios

#### Scenario: EDataType distribution for attributes
- **WHEN** generator creates EAttributes
- **THEN** attributes use various EDataTypes:
  - EString (approximately 40%)
  - EInt (approximately 20%)
  - EBoolean (approximately 15%)
  - EDate (approximately 15%)
  - EDouble (approximately 10%)

---

### Requirement: Generate realistic guard conditions

The synthetic model generator MAY attach synthetic guard conditions to source elements that cause rule guards to filter a configurable percentage of elements.

#### Scenario: Guard rejection simulation
- **WHEN** generator is configured with 20% guard rejection rate
- **THEN** approximately 20% of elements have markers that cause test rule guards to return false

---

## ADDED Requirements

### Requirement: EOperation generation with parameters

The synthetic model generator SHALL create EOperations with configurable parameters.

#### Scenario: Generate operations with parameters
- **WHEN** generator creates EOperation
- **THEN** operation has 0-3 EParameters
- **AND** parameter types are valid EClassifiers from generated model

#### Scenario: Generate operations with return type
- **WHEN** generator creates EOperation
- **THEN** operation may have return type
- **AND** return type is valid EClassifier

---

### Requirement: EClass hierarchy generation

The synthetic model generator SHALL create EClass inheritance hierarchies with configurable depth and branching.

#### Scenario: Generate inheritance tree
- **WHEN** generator is configured with inheritanceDepth=4
- **THEN** generated model has inheritance trees
- **AND** maximum depth from root to leaf is 4

#### Scenario: Generate multiple inheritance branches
- **WHEN** generator creates inheritance hierarchy
- **THEN** multiple parallel branches may exist
- **AND** abstract classes are at higher levels
- **AND** concrete classes are at lower levels

---

### Requirement: Containment vs association distribution

The synthetic model generator SHALL create mix of containment and association EReferences.

#### Scenario: Containment distribution
- **WHEN** generator creates EReferences
- **THEN** approximately 30% are containment (containment=true)
- **AND** approximately 70% are association (containment=false)

---

### Requirement: Reference cardinality distribution

The synthetic model generator SHALL create EReferences with varied cardinalities.

#### Scenario: Cardinality distribution
- **WHEN** generator creates EReferences
- **THEN** approximately:
  - 40% are single-valued (upperBound=1)
  - 60% are collection-valued (upperBound=-1)
- **AND** mix of required (lowerBound>0) and optional (lowerBound=0)

---

### Requirement: Model statistics reporting

The synthetic model generator SHALL provide statistics about generated models.

#### Scenario: Get model statistics
- **WHEN** generator.getStatistics() is called
- **THEN** returned map contains:
  - "packageCount": number of EPackages
  - "classCount": number of EClasses
  - "attributeCount": number of EAttributes
  - "referenceCount": number of EReferences
  - "operationCount": number of EOperations
  - "abstractClassCount": number of abstract EClasses
  - "totalElements": total model elements
