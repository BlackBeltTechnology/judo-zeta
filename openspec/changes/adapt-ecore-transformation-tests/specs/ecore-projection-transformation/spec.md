# ecore-projection-transformation Specification

## Purpose

Test patterns for entity-to-transfer-object projections, where a target EClass contains a subset of features from a source EClass (similar to tatami's mapped/unmapped transfer objects).

## ADDED Requirements

### Requirement: Subset attribute projection

The transformation framework SHALL support creating target EClasses with a subset of source EClass attributes.

#### Scenario: Project entity to transfer object with selected attributes

- **GIVEN** source EClass Customer with attributes [id, name, email, password, createdAt]
- **AND** projection rule selects only [id, name, email] for CustomerTO
- **WHEN** transformation executes
- **THEN** target CustomerTO has exactly 3 EAttributes
- **AND** attribute names are [id, name, email]
- **AND** attribute types match source types

#### Scenario: Projection with renamed attributes

- **GIVEN** source EClass Person with attribute fullName
- **AND** projection rule maps fullName to name in PersonTO
- **WHEN** transformation executes
- **THEN** target PersonTO has attribute named "name"
- **AND** attribute type matches source.fullName type

---

### Requirement: Subset reference projection

The transformation framework SHALL support creating target EClasses with a subset of source EClass references.

#### Scenario: Project entity to transfer object with selected references

- **GIVEN** source EClass Order with references [customer, items, payments, shippingAddress]
- **AND** projection rule selects only [customer, items] for OrderTO
- **WHEN** transformation executes
- **THEN** target OrderTO has exactly 2 EReferences
- **AND** reference names are [customer, items]
- **AND** reference types resolve to equivalent target EClasses

#### Scenario: Projection with cardinality change

- **GIVEN** source EReference items with cardinality 1..*
- **AND** projection rule changes cardinality to 0..* for OrderTO.items
- **WHEN** transformation executes
- **THEN** target EReference has lowerBound = 0, upperBound = -1

---

### Requirement: Guard-based projection selection

The transformation framework SHALL support guard methods that determine which features to include in projections.

#### Scenario: Guard selects features by annotation

- **GIVEN** source EClass with attributes marked with @exposed annotation
- **AND** projection guard returns true only for exposed attributes
- **WHEN** transformation executes
- **THEN** only exposed attributes appear in target projection
- **AND** non-exposed attributes are excluded

#### Scenario: Guard selects features by naming convention

- **GIVEN** source EClass with attributes starting with "public" prefix
- **AND** projection guard checks attribute name pattern
- **WHEN** transformation executes
- **THEN** only matching attributes appear in target

---

### Requirement: Projection with feature binding

The transformation framework SHALL support binding projected features to source features for traceability.

#### Scenario: Transfer attribute bound to source attribute

- **GIVEN** source EAttribute Customer.email
- **AND** projected EAttribute CustomerTO.email
- **WHEN** binding is established
- **THEN** target attribute can trace back to source attribute
- **AND** transformation context can resolve binding for cross-references

---

### Requirement: Unmapped projection (standalone transfer object)

The transformation framework SHALL support creating transfer objects that are not mapped to any entity (standalone DTOs).

#### Scenario: Create unmapped transfer object

- **GIVEN** no source EClass mapping
- **AND** unmapped transfer object definition with attributes [searchTerm, maxResults]
- **WHEN** transformation executes
- **THEN** target EClass exists without entity mapping
- **AND** target EClass has specified attributes

---

### Requirement: Nested projection (embedded transfer objects)

The transformation framework SHALL support projections that embed other projections.

#### Scenario: Order projection embeds customer projection

- **GIVEN** source Order with reference to Customer
- **AND** OrderTO projection with reference to CustomerTO projection
- **WHEN** transformation executes
- **THEN** OrderTO.customer reference points to CustomerTO
- **AND** CustomerTO is a projection of Customer
- **AND** nested projection is correctly resolved via equivalent()

---

### Requirement: Projection with derived features

The transformation framework SHALL support creating derived/calculated features in projections that don't exist in source.

#### Scenario: Derived attribute in projection

- **GIVEN** source EClass Person with attributes [firstName, lastName]
- **AND** projection rule creates derived attribute fullName = firstName + " " + lastName
- **WHEN** transformation executes
- **THEN** target PersonTO has attribute fullName
- **AND** fullName is marked as derived (volatile or transient)

#### Scenario: Calculated reference in projection

- **GIVEN** source EClass Order with reference items
- **AND** projection rule creates derived reference expensiveItems = items.filter(price > 1000)
- **WHEN** transformation executes
- **THEN** target OrderTO has reference expensiveItems
- **AND** reference is derived from items reference
