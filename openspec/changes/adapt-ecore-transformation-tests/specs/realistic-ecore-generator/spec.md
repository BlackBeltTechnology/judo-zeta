# realistic-ecore-generator Specification

## Purpose

Synthetic Ecore model generation with configurable structure for transformation testing. Generates realistic models with packages, classes, attributes, references, operations, and inheritance hierarchies.

## ADDED Requirements

### Requirement: Configurable model structure generation

The realistic Ecore model generator SHALL create models with configurable structural parameters.

#### Scenario: Generate model with specified parameters

- **WHEN** generator is configured with packageCount=3, classesPerPackage=10, attributesPerClass=5
- **THEN** generated ResourceSet contains exactly 3 EPackages
- **AND** each EPackage contains exactly 10 EClasses
- **AND** each EClass has exactly 5 EAttributes

#### Scenario: Generate with all configuration options

- **WHEN** generator is configured with:
  - packageCount=2
  - classesPerPackage=15
  - attributesPerClass=4
  - referencesPerClass=3
  - operationsPerClass=2
  - inheritanceDepth=3
- **THEN** generated model reflects all specified parameters
- **AND** total element count is approximately: 2 + (2*15) + (2*15*4) + (2*15*3) + (2*15*2)

---

### Requirement: Deterministic generation with fixed seed

The realistic Ecore model generator SHALL use a fixed random seed for reproducible model generation.

#### Scenario: Reproducible model generation

- **WHEN** generator is configured with seed=42 and same parameters
- **AND** generator is executed twice
- **THEN** both generated models have identical structure
- **AND** EClass names are identical
- **AND** attribute names are identical
- **AND** reference targets are identical

#### Scenario: Different seeds produce different models

- **WHEN** generator is executed with seed=42
- **AND** generator is executed with seed=123
- **THEN** generated models have different class names
- **OR** different attribute distributions
- **OR** different reference patterns

---

### Requirement: Inheritance hierarchy generation

The realistic Ecore model generator SHALL create inheritance hierarchies with configurable depth.

#### Scenario: Generate inheritance hierarchy

- **WHEN** generator is configured with inheritanceDepth=3
- **THEN** some EClasses have supertypes
- **AND** maximum inheritance chain length is 3
- **AND** abstract ratio of classes are abstract

#### Scenario: No inheritance when depth is 1

- **WHEN** generator is configured with inheritanceDepth=1
- **THEN** no EClass has any supertype
- **AND** all EClasses are at root level

---

### Requirement: Cross-package reference generation

The realistic Ecore model generator SHALL create references between classes in different packages.

#### Scenario: Generate cross-package references

- **WHEN** generator is configured with multiple packages
- **AND** referencesPerClass > 0
- **THEN** some EReferences point to EClasses in different packages
- **AND** cross-package references are valid (target EClass exists)

---

### Requirement: Bidirectional reference generation

The realistic Ecore model generator SHALL create bidirectional references with eOpposite.

#### Scenario: Generate bidirectional references

- **WHEN** generator creates reference A.toB
- **AND** bidirectional ratio causes opposite creation
- **THEN** reference B.toA exists with eOpposite pointing to A.toB
- **AND** A.toB.eOpposite equals B.toA

---

### Requirement: Attribute type distribution

The realistic Ecore model generator SHALL create attributes with varied data types.

#### Scenario: Mix of attribute types

- **WHEN** generator creates attributes
- **THEN** attributes use various EDataTypes:
  - EString (approximately 40%)
  - EInt/EIntegerObject (approximately 20%)
  - EBoolean/EBooleanObject (approximately 15%)
  - EDate/EDateTime (approximately 15%)
  - EDouble/EDoubleObject (approximately 10%)

---

### Requirement: Reference cardinality distribution

The realistic Ecore model generator SHALL create references with varied cardinalities.

#### Scenario: Mix of reference cardinalities

- **WHEN** generator creates references
- **THEN** references have varied cardinalities:
  - Single-valued (upperBound=1): approximately 40%
  - Collection-valued (upperBound=-1): approximately 60%
- **AND** mix of required (lowerBound>0) and optional (lowerBound=0)

---

### Requirement: Containment vs association distribution

The realistic Ecore model generator SHALL create mix of containment and association references.

#### Scenario: Mix of containment types

- **WHEN** generator creates references
- **THEN** approximately 30% are containment references (containment=true)
- **AND** approximately 70% are association references (containment=false)

---

### Requirement: Model validation after generation

The realistic Ecore model generator SHALL produce valid Ecore models.

#### Scenario: Generated model is valid

- **WHEN** generator completes model generation
- **THEN** all EPackages are valid per Ecore constraints
- **AND** no dangling references exist
- **AND** all EClasses have valid feature types

---

### Requirement: Scaled generation for performance testing

The realistic Ecore model generator SHALL support scaled generation for performance benchmarks.

#### Scenario: Generate small model

- **WHEN** generator.scaled(100) is called (100 target elements)
- **THEN** generated model has approximately 100 elements
- **AND** generation completes in reasonable time (<1s)

#### Scenario: Generate large model

- **WHEN** generator.scaled(5000) is called (5000 target elements)
- **THEN** generated model has approximately 5000 elements
- **AND** generation completes in reasonable time (<10s)

---

### Requirement: Statistics reporting

The realistic Ecore model generator SHALL provide statistics about generated models.

#### Scenario: Get generation statistics

- **WHEN** generator.getStatistics() is called after generation
- **THEN** returned map contains:
  - "packageCount": number of EPackages
  - "classCount": number of EClasses
  - "attributeCount": number of EAttributes
  - "referenceCount": number of EReferences
  - "operationCount": number of EOperations
  - "abstractClassCount": number of abstract EClasses
  - "totalElements": total model elements
