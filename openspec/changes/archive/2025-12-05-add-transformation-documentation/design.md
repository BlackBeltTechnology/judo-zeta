# Design: Add Transformation Documentation

## Overview

This design document describes the documentation structure for adding comprehensive transformation framework documentation to the Judo Zeta project.

## Current State

```
docs/
├── index.md                          # Main hub (validation-focused)
├── getting-started.md                # Validation getting started
├── user-guide/                       # Validation user guides
│   ├── core-concepts.md
│   ├── validation-rules.md
│   ├── guards-and-dependencies.md
│   ├── caching.md
│   ├── extension-methods.md
│   └── lifecycle-hooks.md
├── best-practices/                   # Validation best practices
│   ├── constants.md
│   ├── extension-delegation.md
│   ├── guard-methods.md
│   ├── error-messages.md
│   └── performance.md
├── evl-comparison/                   # EVL comparison
│   ├── overview.md
│   ├── syntax-mapping.md
│   ├── migration-guide.md
│   └── feature-parity.md
├── examples/                         # Validation examples
│   ├── simple-validations.md
│   ├── entity-type-validations.md
│   ├── operation-validations.md
│   ├── inheritance-validations.md
│   └── cross-reference-validations.md
├── architecture/                     # Validation architecture
│   ├── overview.md
│   ├── execution-flow.md
│   ├── parallel-execution.md
│   └── dependency-resolution.md
└── reference/                        # Validation reference
    ├── annotations.md
    ├── validation-result.md
    ├── validation-context.md
    └── troubleshooting.md
```

## Target State

```
docs/
├── index.md                          # Main hub for BOTH frameworks
│
├── validation/                       # Validation framework docs
│   ├── index.md                      # Validation hub
│   ├── getting-started.md
│   ├── user-guide/
│   │   ├── core-concepts.md
│   │   ├── validation-rules.md
│   │   ├── guards-and-dependencies.md
│   │   ├── caching.md
│   │   ├── extension-methods.md
│   │   └── lifecycle-hooks.md
│   ├── best-practices/
│   │   ├── constants.md
│   │   ├── extension-delegation.md
│   │   ├── guard-methods.md
│   │   ├── error-messages.md
│   │   └── performance.md
│   ├── evl-comparison/
│   │   ├── overview.md
│   │   ├── syntax-mapping.md
│   │   ├── migration-guide.md
│   │   └── feature-parity.md
│   ├── examples/
│   │   ├── simple-validations.md
│   │   ├── entity-type-validations.md
│   │   ├── operation-validations.md
│   │   ├── inheritance-validations.md
│   │   └── cross-reference-validations.md
│   ├── architecture/
│   │   ├── overview.md
│   │   ├── execution-flow.md
│   │   ├── parallel-execution.md
│   │   └── dependency-resolution.md
│   └── reference/
│       ├── annotations.md
│       ├── validation-result.md
│       ├── validation-context.md
│       └── troubleshooting.md
│
├── transformation/                   # Transformation framework docs
│   ├── index.md                      # Transformation hub
│   ├── getting-started.md
│   ├── user-guide/
│   │   ├── core-concepts.md
│   │   ├── transformation-rules.md
│   │   ├── element-resolution.md
│   │   ├── guards-and-conditions.md
│   │   ├── lazy-evaluation.md
│   │   ├── rule-inheritance.md
│   │   ├── greedy-matching.md
│   │   ├── discriminated-equivalence.md
│   │   ├── lifecycle-hooks.md
│   │   └── extension-methods.md
│   ├── best-practices/
│   │   ├── rule-naming.md
│   │   ├── rule-organization.md
│   │   ├── performance.md
│   │   ├── error-handling.md
│   │   └── testing.md
│   ├── etl-comparison/
│   │   ├── overview.md
│   │   ├── syntax-mapping.md
│   │   ├── migration-guide.md
│   │   └── feature-parity.md
│   ├── examples/
│   │   ├── simple-transformations.md
│   │   ├── entity-transformations.md
│   │   ├── namespace-transformations.md
│   │   ├── service-transformations.md
│   │   ├── lazy-rule-examples.md
│   │   ├── discriminated-examples.md
│   │   └── inheritance-examples.md
│   ├── architecture/
│   │   ├── overview.md
│   │   ├── execution-flow.md
│   │   ├── parallel-execution.md
│   │   ├── element-resolution-cache.md
│   │   └── rule-inheritance-graph.md
│   └── reference/
│       ├── annotations.md
│       ├── transformation-context.md
│       ├── transformation-result.md
│       ├── transformation-trace.md
│       └── troubleshooting.md
│
└── shared/                           # Content shared by both frameworks
    ├── installation.md               # Maven, P2, OSGi installation
    └── annotation-packages.md        # zeta-annotations overview
```

## Documentation Parity

The transformation documentation should mirror the validation documentation structure and detail level:

| Validation Doc | Transformation Equivalent |
|----------------|---------------------------|
| Getting Started (15 min read) | Getting Started (15 min read) |
| Core Concepts (with diagrams) | Core Concepts (with diagrams) |
| Writing Validation Rules | Writing Transformation Rules |
| Guards and Dependencies | Guards and Conditions |
| @Satisfies dependencies | Rule Inheritance with @Extends |
| Caching | Lazy Evaluation |
| Extension Methods | Extension Methods (same) |
| Lifecycle Hooks | Lifecycle Hooks |
| EVL Comparison | ETL Comparison |
| Syntax Mapping | Syntax Mapping |
| Migration Guide | Migration Guide |
| Feature Parity | Feature Parity |
| 5 Example categories | 7 Example categories |
| 4 Architecture docs | 5 Architecture docs |
| 4 Reference docs | 5 Reference docs |

## Key Transformation Topics

### Unique to Transformation

1. **Element Resolution (equivalent/equivalents)**
   - Core concept not present in validation
   - Critical for understanding transformation semantics
   - Needs dedicated user guide chapter

2. **Lazy Evaluation (@Lazy)**
   - On-demand execution pattern
   - Different from validation's linear execution

3. **Rule Inheritance (@Abstract, @Extends)**
   - Complex feature for code reuse
   - Needs detailed examples

4. **Greedy Type Matching (@Greedy)**
   - Type hierarchy matching
   - ETL-specific concept

5. **Discriminated Equivalence**
   - Multiple outputs from single source
   - Advanced pattern

6. **Transformation Trace**
   - JSON export for debugging
   - No validation equivalent

### Shared with Validation

1. **Guards (@Guard)** - Same semantics
2. **Extension Methods (@ExtensionMethod)** - Identical
3. **Caching (@Cached)** - Identical
4. **Lifecycle Hooks** - Similar but different annotations

## Example Sources

Transformation examples should reference real code from:

1. **judo-tatami-esm2psm** - ESM to PSM transformation
   - `esmToPsm.etl` - Main transformation
   - `modules/namespace/namespace.etl` - Namespace transformations
   - `modules/type/type.etl` - Type transformations
   - `modules/data/entityType.etl` - Entity transformations

2. **transformation-core tests** - Unit test examples
   - `ElementResolutionCacheTest.java`
   - `RuleInheritanceGraphTest.java`
   - `TransformationTraceTest.java`

## Mermaid Diagrams

Minimum required diagrams for transformation docs:

1. **Core Concepts** - Component diagram showing TransformationRegistry, TransformationExecutor, TransformationContext, TransformRuleDescriptor

2. **Execution Flow** - Sequence diagram showing registration, discovery, eager execution, lazy resolution

3. **Parallel Execution** - Activity diagram showing chunking and parallel processing

4. **Element Resolution Cache** - Graph diagram showing cache structure

5. **Rule Inheritance** - Graph diagram showing inheritance relationships and topological sort

## Link Strategy

All documentation files should use relative links:

```markdown
<!-- From docs/transformation/user-guide/core-concepts.md -->
[Getting Started](../getting-started.md)
[Examples](../examples/simple-transformations.md)
[Validation docs](../../validation/index.md)
```

## Quality Requirements

1. **Code Examples**: All code examples must compile
2. **Diagrams**: All Mermaid diagrams must render in GitHub
3. **Links**: All internal links must be valid
4. **Consistency**: Same terminology throughout
5. **Completeness**: Every annotation documented with examples
