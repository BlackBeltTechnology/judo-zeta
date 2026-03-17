# ecore-inheritance-transformation Specification

## Purpose

Test patterns for EClass inheritance hierarchies in transformations, covering abstract classes, multi-level inheritance chains, and feature inheritance propagation.

## ADDED Requirements

### Requirement: Abstract class transformation preserves abstract flag

The transformation framework SHALL correctly transform abstract EClasses, preserving the abstract flag in the target model.

#### Scenario: Abstract source produces abstract target

- **WHEN** a source EClass has `abstract = true`
- **AND** a transformation rule copies the EClass to target
- **THEN** the target EClass has `abstract = true`

#### Scenario: Concrete source produces concrete target

- **WHEN** a source EClass has `abstract = false`
- **AND** a transformation rule copies the EClass to target
- **THEN** the target EClass has `abstract = false`

---

### Requirement: Multi-level inheritance chain transformation

The transformation framework SHALL correctly transform multi-level inheritance hierarchies, preserving the entire inheritance chain.

#### Scenario: Three-level inheritance chain

- **WHEN** source model has EClasses: Animal (abstract) → Mammal (abstract) → Dog (concrete)
- **AND** transformation rules handle inheritance via `@Extends` or explicit supertype handling
- **THEN** target model has EClasses with same inheritance chain
- **AND** Dog.getESuperTypes() contains Mammal
- **AND** Mammal.getESuperTypes() contains Animal

#### Scenario: Inheritance with diamond pattern

- **WHEN** source model has: A → B, A → C, B → D, C → D (diamond)
- **AND** transformation preserves all supertype relationships
- **THEN** target model has D with both B and C as supertypes
- **AND** A appears once in D's inheritance hierarchy (no duplication)

---

### Requirement: Inherited feature visibility in transformations

The transformation framework SHALL allow rules to access inherited features when transforming classes.

#### Scenario: Access inherited attributes during transformation

- **WHEN** transforming ChildClass that extends ParentClass
- **AND** ParentClass has attribute "parentName"
- **AND** ChildClass has attribute "childName"
- **THEN** transformation rule can access both "parentName" and "childName" via `source.getEAllAttributes()`

#### Scenario: Access inherited references during transformation

- **WHEN** transforming ChildClass that extends ParentClass
- **AND** ParentClass has reference "parentRef" to OtherClass
- **THEN** transformation rule can access "parentRef" via `source.getEAllReferences()`

---

### Requirement: Abstract rule inheritance with @Extends

Transformation rules using `@Extends` SHALL correctly handle inheritance hierarchies in both source and rule definitions.

#### Scenario: Abstract base rule for common features

- **GIVEN** an abstract rule `BaseEntityRule` that transforms common attributes
- **AND** a concrete rule `ConcreteEntityRule` with `@Extends("BaseEntityRule")`
- **WHEN** transforming a concrete EClass
- **THEN** both base and extending rules execute
- **AND** common attributes are transformed by base rule
- **AND** specific attributes are transformed by extending rule

#### Scenario: Rule inheritance matches class inheritance

- **GIVEN** rule `PersonRule` for EClass Person
- **AND** rule `EmployeeRule` with `@Extends("PersonRule")` for EClass Employee (extends Person)
- **WHEN** transforming an Employee instance
- **THEN** PersonRule transforms Person-level features
- **AND** EmployeeRule transforms Employee-specific features
- **AND** equivalent() resolves correctly for both rule types

---

### Requirement: Interface-like inheritance (multiple supertypes)

The transformation framework SHALL handle EClasses with multiple supertypes (interface-like inheritance).

#### Scenario: Class with multiple interfaces

- **WHEN** source EClass ConcreteImpl has supertypes [InterfaceA, InterfaceB]
- **AND** both interfaces define abstract operations
- **THEN** target EClass has same supertypes
- **AND** operations from both interfaces are visible in `getEAllOperations()`

#### Scenario: Inherited features from multiple supertypes

- **WHEN** source EClass has attributes from InterfaceA and InterfaceB
- **AND** transformation iterates `getEAllAttributes()`
- **THEN** all inherited attributes from both branches are processed
- **AND** no attribute is processed twice (deduplication)
