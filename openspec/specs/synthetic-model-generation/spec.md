# synthetic-model-generation Specification

## Purpose
TBD

## Requirements

### Requirement: Generate configurable synthetic PSM-style models
The synthetic model generator SHALL create EMF models with configurable structural parameters: number of packages, classes per package, attributes per class, references per class, operations per class, inheritance depth, and cross-reference density.

#### Scenario: Generate small model
- **WHEN** generator is configured with 1 package, 10 classes, 5 attributes each, 2 references each
- **THEN** generator creates a ResourceSet containing exactly 1 EPackage with 10 EClasses
- **AND** each EClass has exactly 5 EAttributes
- **AND** each EClass has exactly 2 EReferences

### Requirement: Support realistic cross-reference patterns
The synthetic model generator SHALL create cross-references between classes following configurable patterns: random, hierarchical (parent-child), or circular (mutual references).

#### Scenario: Hierarchical references
- **WHEN** generator is configured with hierarchical reference pattern
- **THEN** generated references form a directed acyclic graph (no cycles)
- **AND** child classes reference parent classes (containment)

### Requirement: Deterministic model generation with fixed seed
The synthetic model generator SHALL use a fixed random seed to ensure reproducible model generation across multiple runs.

#### Scenario: Reproducible models
- **WHEN** generator is configured with seed=42 and same parameters
- **AND** generator is executed twice
- **THEN** both generated models have identical structure (same element counts, same names, same references)

### Requirement: Support type distribution configuration
The synthetic model generator SHALL allow configuration of the distribution of element types: entity classes vs transfer object classes vs enumeration types.

#### Scenario: Mixed type distribution
- **WHEN** generator is configured with 50% entities, 30% transfer objects, 20% enumerations
- **THEN** generated model reflects approximately the specified ratios

### Requirement: Generate realistic guard conditions
The synthetic model generator MAY attach synthetic guard conditions to source elements that cause rule guards to filter a configurable percentage of elements.

#### Scenario: Guard rejection simulation
- **WHEN** generator is configured with 20% guard rejection rate
- **THEN** approximately 20% of elements have markers that cause test rule guards to return false
