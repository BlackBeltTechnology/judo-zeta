# Documentation Specification Delta

## MODIFIED Requirements

### Requirement: Documentation Structure

The Judo Zeta project SHALL provide comprehensive documentation in a `docs/` directory with separate subdirectories for validation and transformation frameworks.

The documentation structure MUST include:
- `docs/index.md` - Central documentation hub for both frameworks
- `docs/validation/` - Complete validation framework documentation
- `docs/transformation/` - Complete transformation framework documentation
- `docs/shared/` - Content common to both frameworks

#### Scenario: Developer discovers both frameworks from main index

**Given** a developer opens `docs/index.md`  
**When** they review the documentation hub  
**Then** they MUST see:
  - Overview of Judo Zeta project
  - Links to validation framework documentation
  - Links to transformation framework documentation
  - Quick comparison of when to use each framework
**And** all navigation links work correctly

#### Scenario: Developer navigates to validation documentation

**Given** a developer is at `docs/index.md`  
**When** they click on validation documentation link  
**Then** they MUST be taken to `docs/validation/index.md`  
**And** the validation hub contains all existing validation documentation organized by topic

#### Scenario: Developer navigates to transformation documentation

**Given** a developer is at `docs/index.md`  
**When** they click on transformation documentation link  
**Then** they MUST be taken to `docs/transformation/index.md`  
**And** the transformation hub contains comprehensive transformation documentation

---

## ADDED Requirements

### Requirement: Transformation Getting Started Guide

The documentation SHALL include a getting started guide (`docs/transformation/getting-started.md`) that covers:

- Installation via Maven, Eclipse P2, and OSGi
- First transformation example with step-by-step explanation
- Common setup patterns
- Quick reference for annotations

#### Scenario: New developer writes first transformation

**Given** a developer new to the transformation framework  
**When** they follow the getting started guide  
**Then** they MUST be able to:
  - Install the framework successfully
  - Write and execute a simple transformation rule
  - Understand the basic annotation usage
**And** the example code compiles and runs without errors

---

### Requirement: Transformation User Guide Documentation

The documentation SHALL provide comprehensive user guides covering:

- Core concepts (annotations, rules, context, results)
- Writing transformation rules (TransformRule, TransformFunction)
- Element resolution (equivalent, equivalents, equivalentDiscriminated)
- Guards and conditions (@Guard)
- Lazy evaluation (@Lazy)
- Rule inheritance (@Abstract, @Extends)
- Greedy type matching (@Greedy)
- Discriminated equivalence
- Lifecycle hooks (@PreExecution, @PostExecution)
- Extension methods (@ExtensionMethod, @Cached)

#### Scenario: Developer learns about element resolution

**Given** a developer wants to understand equivalent() semantics  
**When** they read `docs/transformation/user-guide/element-resolution.md`  
**Then** they MUST understand:
  - How equivalent() looks up transformed elements
  - Difference between equivalent() and equivalents()
  - How @Primary affects resolution
  - How lazy rules are triggered
**And** working examples demonstrate each concept

#### Scenario: Developer implements rule inheritance

**Given** a developer wants to reuse transformation logic  
**When** they read `docs/transformation/user-guide/rule-inheritance.md`  
**Then** they MUST understand:
  - How to use @Abstract annotation
  - How to use @Extends annotation
  - How to call executeParentRule()
  - How multi-level inheritance works
**And** the examples compile and execute correctly

#### Scenario: Developer uses lazy evaluation

**Given** a developer needs on-demand transformation  
**When** they read `docs/transformation/user-guide/lazy-evaluation.md`  
**Then** they MUST understand:
  - When to use @Lazy annotation
  - How lazy rules are triggered via equivalent()
  - How lazy results are cached
**And** performance implications are explained

#### Scenario: Developer uses discriminated equivalence

**Given** a developer needs multiple outputs from one source  
**When** they read `docs/transformation/user-guide/discriminated-equivalence.md`  
**Then** they MUST understand:
  - How equivalentDiscriminated() works
  - ID generation for discriminated elements
  - Cache structure for discriminated mappings
**And** real-world examples demonstrate the pattern

---

### Requirement: Transformation Best Practices Documentation

The documentation SHALL document best practices including:

- Rule naming conventions
- Rule organization patterns
- Performance optimization strategies
- Error handling approaches
- Testing transformation rules

#### Scenario: Developer organizes transformation rules

**Given** a developer has many transformation rules  
**When** they read `docs/transformation/best-practices/rule-organization.md`  
**Then** they MUST understand:
  - How to organize context classes by source type
  - How to split large transformations into modules
  - How to avoid rule name collisions
**And** examples show recommended project structure

#### Scenario: Developer optimizes transformation performance

**Given** a developer has slow transformation execution  
**When** they read `docs/transformation/best-practices/performance.md`  
**Then** they MUST understand:
  - Parallel execution threshold configuration
  - Caching strategies for expensive operations
  - Avoiding repeated equivalent() calls
**And** benchmarking examples show performance improvements

---

### Requirement: ETL Comparison Documentation

The documentation SHALL provide comprehensive ETL (Epsilon Transformation Language) comparison including:

- Overview of differences and similarities
- Syntax mapping (ETL constructs to Zeta equivalents)
- Migration guide from ETL to Zeta
- Feature parity matrix

#### Scenario: ETL user migrates to Zeta transformation

**Given** a developer familiar with ETL  
**When** they read `docs/transformation/etl-comparison/migration-guide.md`  
**Then** they MUST understand:
  - Philosophical differences (declarative vs programmatic)
  - Step-by-step migration process
  - How to convert .etl files to Java classes
**And** they can successfully convert an ETL transformation to Zeta

#### Scenario: ETL user looks up syntax equivalent

**Given** an ETL user needs to translate an ETL construct  
**When** they consult `docs/transformation/etl-comparison/syntax-mapping.md`  
**Then** they MUST find mappings for:
  - rule declarations
  - @lazy, @abstract, @primary, @greedy annotations
  - extends inheritance
  - guard conditions
  - equivalent() and equivalents() operations
  - pre/post blocks
**And** side-by-side code examples demonstrate each mapping

#### Scenario: Developer checks feature availability

**Given** a developer uses a specific ETL feature  
**When** they consult `docs/transformation/etl-comparison/feature-parity.md`  
**Then** they MUST see:
  - Whether the feature is supported in Zeta
  - Alternative approaches for unsupported features
**And** the feature matrix covers at least 20 features

---

### Requirement: Real-World Transformation Examples

The documentation SHALL include at least 15 real-world examples covering:

- Simple transformations (basic rules)
- Entity transformations (EntityType to Table)
- Namespace transformations (package hierarchy)
- Service layer transformations (TransferObjects, Operations)
- Lazy rule examples (on-demand patterns)
- Discriminated equivalence examples (CRUD operations)
- Inheritance examples (abstract rules, extends chains)

#### Scenario: Developer learns from entity transformation example

**Given** a developer needs to transform entity types  
**When** they read `docs/transformation/examples/entity-transformations.md`  
**Then** they MUST see:
  - At least 3 entity transformation examples
  - Full working code for each example
  - References to judo-tatami-esm2psm patterns
**And** the examples compile and execute correctly

#### Scenario: Developer learns discriminated equivalence patterns

**Given** a developer needs to create CRUD operations from relations  
**When** they read `docs/transformation/examples/discriminated-examples.md`  
**Then** they MUST see:
  - Real-world use case (relation to operations)
  - Complete implementation code
  - Explanation of discriminator naming
**And** the pattern matches judo-tatami usage

---

### Requirement: Transformation Architecture Documentation

The documentation SHALL explain the transformation framework architecture including:

- System overview with component diagram
- Transformation execution flow with sequence diagram
- Parallel execution with activity diagram
- Element resolution cache structure
- Rule inheritance graph and topological sorting

#### Scenario: Developer understands transformation flow

**Given** a developer wants to understand how transformation works  
**When** they read `docs/transformation/architecture/execution-flow.md`  
**Then** they MUST see a sequence diagram showing:
  - Registration phase
  - Discovery phase
  - Eager execution phase
  - Lazy resolution phase
  - Result collection
**And** the diagram uses Mermaid syntax and renders in GitHub

#### Scenario: Developer understands parallelization

**Given** a developer wants to leverage parallel execution  
**When** they read `docs/transformation/architecture/parallel-execution.md`  
**Then** they MUST understand:
  - Threshold configuration
  - Chunk size considerations
  - Thread safety guarantees
**And** performance characteristics are documented

#### Scenario: Developer understands rule inheritance resolution

**Given** a developer uses rule inheritance  
**When** they read `docs/transformation/architecture/rule-inheritance-graph.md`  
**Then** they MUST understand:
  - How dependency graph is constructed
  - How topological sorting determines execution order
  - How cycles are detected
**And** a diagram illustrates the algorithm

---

### Requirement: Transformation Reference Documentation

The documentation SHALL provide complete API reference including:

- All transformation annotations with parameters and usage
- TransformationContext API
- TransformationResult API
- TransformationTrace API with JSON format
- Troubleshooting guide for common issues

#### Scenario: Developer looks up annotation parameters

**Given** a developer needs to use @TransformRule annotation  
**When** they consult `docs/transformation/reference/annotations.md`  
**Then** they MUST see:
  - Complete parameter documentation for all annotations
  - Default values for each parameter
  - Usage examples
  - Required vs optional parameters
**And** every annotation is documented

#### Scenario: Developer troubleshoots common error

**Given** a developer encounters "Rule not found" error  
**When** they consult `docs/transformation/reference/troubleshooting.md`  
**Then** they MUST find:
  - The error in the troubleshooting guide
  - Root causes explained
  - Step-by-step solution
**And** the guide covers at least 10 common issues

---

### Requirement: Transformation Visual Diagrams

The documentation SHALL include at least 5 Mermaid diagrams:

- Component diagram for architecture overview
- Sequence diagram for transformation execution flow
- Activity diagram for parallel execution
- Graph diagram for element resolution cache
- Graph diagram for rule inheritance

#### Scenario: GitHub renders transformation diagrams correctly

**Given** transformation documentation contains Mermaid diagrams  
**When** a developer views the documentation on GitHub  
**Then** all Mermaid diagrams MUST render correctly  
**And** diagrams are clear and readable  
**And** diagram style is consistent with validation documentation

---

### Requirement: Documentation Cross-References

The documentation SHALL include cross-references between validation and transformation documentation where concepts overlap.

#### Scenario: Developer finds shared concepts

**Given** a developer is reading transformation extension methods documentation  
**When** they look for more information on @ExtensionMethod  
**Then** they MUST see a reference to validation extension methods documentation  
**And** shared patterns are clearly identified

#### Scenario: Developer understands framework similarities

**Given** a developer knows validation framework  
**When** they read transformation getting started guide  
**Then** they MUST see comparisons to validation concepts they already know  
**And** differences are clearly highlighted

---

## REMOVED Requirements

None - all existing validation documentation requirements remain unchanged. Validation documentation is moved to `docs/validation/` subdirectory with updated internal links.
