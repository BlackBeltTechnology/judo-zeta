# ecore-relation-cardinality Specification

## Purpose

Test patterns for EReference cardinality variations in transformations, including single vs collection, containment vs association, and bidirectional references.

## ADDED Requirements

### Requirement: Single-valued reference transformation

The transformation framework SHALL correctly transform single-valued EReferences (upperBound = 1).

#### Scenario: Optional single reference

- **WHEN** source EReference has lowerBound = 0, upperBound = 1
- **AND** transformation copies the reference
- **THEN** target EReference has lowerBound = 0, upperBound = 1
- **AND** target EReference type resolves to equivalent target EClass

#### Scenario: Required single reference

- **WHEN** source EReference has lowerBound = 1, upperBound = 1
- **AND** transformation copies the reference
- **THEN** target EReference has lowerBound = 1, upperBound = 1

---

### Requirement: Multi-valued reference transformation

The transformation framework SHALL correctly transform collection-valued EReferences (upperBound > 1 or -1).

#### Scenario: Bounded collection reference

- **WHEN** source EReference has lowerBound = 0, upperBound = 10
- **AND** transformation copies the reference
- **THEN** target EReference has lowerBound = 0, upperBound = 10

#### Scenario: Unbounded collection reference

- **WHEN** source EReference has lowerBound = 0, upperBound = -1 (unbounded)
- **AND** transformation copies the reference
- **THEN** target EReference has lowerBound = 0, upperBound = -1

#### Scenario: Required collection reference

- **WHEN** source EReference has lowerBound = 5, upperBound = -1
- **AND** transformation copies the reference
- **THEN** target EReference has lowerBound = 5, upperBound = -1

---

### Requirement: Containment reference transformation

The transformation framework SHALL correctly transform containment references, preserving the containment semantics.

#### Scenario: Containment reference preserves containment flag

- **WHEN** source EReference has containment = true
- **AND** transformation copies the reference
- **THEN** target EReference has containment = true
- **AND** target element is contained in the referencing resource

#### Scenario: Containment with bidirectional opposite

- **WHEN** source has containment reference parent.children with opposite child.parent
- **AND** transformation preserves bidirectionality
- **THEN** target has containment reference with opposite
- **AND** container relationship is correctly established

---

### Requirement: Association (non-containment) reference transformation

The transformation framework SHALL correctly transform association references (containment = false).

#### Scenario: Association reference preserves non-containment

- **WHEN** source EReference has containment = false
- **AND** transformation copies the reference
- **THEN** target EReference has containment = false
- **AND** referenced element is NOT contained by the referencer

#### Scenario: Cross-package association

- **WHEN** source EReference points to EClass in different package
- **AND** transformation resolves equivalent target EClass
- **THEN** target EReference points to correct target EClass
- **AND** cross-package reference is valid in target model

---

### Requirement: Bidirectional reference transformation

The transformation framework SHALL correctly transform bidirectional EReferences (references with eOpposite).

#### Scenario: One-to-one bidirectional reference

- **WHEN** source has bidirectional pair: Person.spouse (0..1) ↔ Person.spouse (0..1)
- **AND** transformation preserves bidirectionality
- **THEN** target has bidirectional pair with same cardinalities
- **AND** target references are mutual opposites

#### Scenario: One-to-many bidirectional reference

- **WHEN** source has bidirectional pair: Order.lines (0..*) ↔ LineItem.order (0..1)
- **AND** transformation preserves bidirectionality
- **THEN** target has bidirectional pair with same cardinalities
- **AND** one side has upperBound = -1, other has upperBound = 1

#### Scenario: Many-to-many bidirectional reference

- **WHEN** source has bidirectional pair: Student.courses (0..*) ↔ Course.students (0..*)
- **AND** transformation preserves bidirectionality
- **THEN** target has bidirectional pair with upperBound = -1 on both sides

---

### Requirement: Reference resolution via equivalent()

The transformation framework SHALL resolve reference targets via the equivalent() mechanism to maintain transformation traceability.

#### Scenario: Reference target resolved through equivalent

- **WHEN** source EReference points to TargetClass instance A
- **AND** A is transformed to target instance A' via equivalent()
- **THEN** target EReference points to A' (not a copy of A)

#### Scenario: Lazy resolution of reference targets

- **WHEN** source EReference points to TargetClass instance
- **AND** TargetClass transformation rule is lazy
- **THEN** equivalent() triggers lazy transformation of target
- **AND** reference points to lazily-created target

---

### Requirement: Self-references

The transformation framework SHALL correctly handle self-referential EClasses.

#### Scenario: Self-reference (same class)

- **WHEN** EClass TreeNode has reference "parent" of type TreeNode
- **AND** transformation processes the reference
- **THEN** target EReference type points to the target TreeNode EClass
- **AND** no infinite recursion occurs during transformation

#### Scenario: Circular reference chain

- **WHEN** EClass A references B, B references C, C references A
- **AND** transformation processes all classes
- **THEN** all references are correctly resolved
- **AND** no transformation failures due to circular dependencies
