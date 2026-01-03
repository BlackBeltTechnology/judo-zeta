# Judo Zeta Validation Framework Documentation

**Navigation**: [Documentation Hub](../index.md) > Validation Framework

Welcome to the comprehensive documentation for the Judo Zeta Validation Framework. This documentation will help you understand, implement, and optimize validation rules for Eclipse Modeling Framework (EMF) models.

## Quick Navigation

### Getting Started
- **[Getting Started Guide](getting-started.md)** - Install and write your first validation rule

### User Guide
Learn the core concepts and features:
- [Core Concepts](user-guide/core-concepts.md) - Annotations, rules, and validation context
- [Writing Validation Rules](user-guide/validation-rules.md) - Constraints, critiques, and messages
- [Guards and Dependencies](user-guide/guards-and-dependencies.md) - Conditional validation and rule ordering
- [Caching](user-guide/caching.md) - Optimize expensive validations
- [Extension Methods](user-guide/extension-methods.md) - Reusable helper methods
- [Lifecycle Hooks](user-guide/lifecycle-hooks.md) - Pre/post validation setup

### Best Practices
Proven patterns for production code:
- [Constants](best-practices/constants.md) - Constraint and guard method name constants
- [Extension Delegation](best-practices/extension-delegation.md) - Static utility patterns
- [Guard Methods](best-practices/guard-methods.md) - Effective guard patterns
- [Error Messages](best-practices/error-messages.md) - Clear, actionable messages
- [Performance](best-practices/performance.md) - Optimization and parallelization

### EVL Comparison
For developers familiar with Epsilon Validation Language:
- [Overview](evl-comparison/overview.md) - High-level differences and similarities
- [Syntax Mapping](evl-comparison/syntax-mapping.md) - EVL constructs to Zeta equivalents
- [Migration Guide](evl-comparison/migration-guide.md) - Step-by-step migration from EVL
- [Dual-Engine Testing](evl-comparison/dual-engine-testing.md) - Testing framework for EVL/Zeta equivalence
- [Feature Parity](evl-comparison/feature-parity.md) - What's supported and what's not

### Real-World Examples
Learn from production code:
- [Simple Validations](examples/simple-validations.md) - Basic constraints and critiques
- [Entity Type Validations](examples/entity-type-validations.md) - Name uniqueness, mapping checks
- [Operation Validations](examples/operation-validations.md) - Complex guard/satisfies chains
- [Inheritance Validations](examples/inheritance-validations.md) - Hierarchy and cyclic detection
- [Cross-Reference Validations](examples/cross-reference-validations.md) - Multi-element validation

### Architecture
Understand the internals:
- [Overview](architecture/overview.md) - System architecture and components
- [Execution Flow](architecture/execution-flow.md) - How validation runs
- [Parallel Execution](architecture/parallel-execution.md) - Work distribution and performance
- [Dependency Resolution](architecture/dependency-resolution.md) - Topological sorting

### Reference
Complete API documentation:
- [Annotations](reference/annotations.md) - All annotations with parameters
- [ValidationResult API](reference/validation-result.md) - Creating and handling results
- [ValidationContext API](reference/validation-context.md) - Context methods and utilities
- [Troubleshooting](reference/troubleshooting.md) - Common issues and solutions

## Quick Reference Card

| Task | Annotation | Example |
|------|------------|---------|
| Define error-level rule | `@Constraint` | `@Constraint(name = "MustHaveName", message = "...")` |
| Define warning-level rule | `@Critique` | `@Critique(name = "ShouldHaveDesc", message = "...")` |
| Add conditional guard | `@Guard` | `@Guard(method = "isNotAbstract")` |
| Declare dependencies | `@Satisfies` | `@Satisfies("MustHaveName")` |
| Cache expensive results | `@Cached` | `@Cached` |
| Define helper method | `@ExtensionMethod` | `@ExtensionMethod(elementType = MyType.class)` |

## Related Documentation

- **[Transformation Framework](../transformation/index.md)** - Model-to-model transformation documentation
- **[Documentation Hub](../index.md)** - Main documentation index

---

**Next**: [Getting Started Guide](getting-started.md)
