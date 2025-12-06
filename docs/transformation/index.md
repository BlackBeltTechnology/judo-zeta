# Judo Zeta Transformation Framework Documentation

**Navigation**: [Documentation Hub](../index.md) > Transformation Framework

Welcome to the comprehensive documentation for the Judo Zeta Transformation Framework. This documentation will help you understand, implement, and optimize model-to-model transformations for Eclipse Modeling Framework (EMF) models.

## Quick Navigation

### Getting Started
- **[Getting Started Guide](getting-started.md)** - Install and write your first transformation rule

### User Guide
Learn the core concepts and features:
- [Core Concepts](user-guide/core-concepts.md) - Annotations, rules, and transformation context
- [Writing Transformation Rules](user-guide/transformation-rules.md) - Basic and advanced rule patterns
- [Element Resolution](user-guide/element-resolution.md) - equivalent(), equivalents(), and caching
- [Guards and Conditions](user-guide/guards-and-conditions.md) - Conditional transformation
- [Lazy Evaluation](user-guide/lazy-evaluation.md) - On-demand rule execution
- [Rule Inheritance](user-guide/rule-inheritance.md) - @Abstract and @Extends patterns
- [Greedy Matching](user-guide/greedy-matching.md) - Type hierarchy matching
- [Discriminated Equivalence](user-guide/discriminated-equivalence.md) - Multiple outputs from one source
- [Lifecycle Hooks](user-guide/lifecycle-hooks.md) - Pre/post transformation setup
- [Extension Methods](user-guide/extension-methods.md) - Reusable helper methods

### Best Practices
Proven patterns for production code:
- [Rule Naming](best-practices/rule-naming.md) - Naming conventions and constants
- [Rule Organization](best-practices/rule-organization.md) - Project structure
- [Performance](best-practices/performance.md) - Optimization and parallelization
- [Error Handling](best-practices/error-handling.md) - Robust transformation code
- [Testing](best-practices/testing.md) - Unit and integration testing

### ETL Comparison
For developers familiar with Epsilon Transformation Language:
- [Overview](etl-comparison/overview.md) - High-level differences and similarities
- [Syntax Mapping](etl-comparison/syntax-mapping.md) - ETL constructs to Zeta equivalents
- [Migration Guide](etl-comparison/migration-guide.md) - Step-by-step migration from ETL
- [Feature Parity](etl-comparison/feature-parity.md) - What's supported and what's not

### Real-World Examples
Learn from production code:
- [Simple Transformations](examples/simple-transformations.md) - Basic property mapping
- [Entity Transformations](examples/entity-transformations.md) - Entity to Table patterns
- [Namespace Transformations](examples/namespace-transformations.md) - Package hierarchy handling
- [Service Transformations](examples/service-transformations.md) - TransferObject and Operation patterns
- [Lazy Rule Examples](examples/lazy-rule-examples.md) - On-demand patterns
- [Discriminated Examples](examples/discriminated-examples.md) - CRUD operation generation
- [Inheritance Examples](examples/inheritance-examples.md) - Abstract rules and @Extends

### Architecture
Understand the internals:
- [Overview](architecture/overview.md) - System architecture and components
- [Execution Flow](architecture/execution-flow.md) - How transformation runs
- [Parallel Execution](architecture/parallel-execution.md) - Work distribution and performance
- [Element Resolution Cache](architecture/element-resolution-cache.md) - Cache structure
- [Rule Inheritance Graph](architecture/rule-inheritance-graph.md) - Topological sorting

### Reference
Complete API documentation:
- [Annotations](reference/annotations.md) - All annotations with parameters
- [TransformationContext API](reference/transformation-context.md) - Context methods
- [TransformationResult API](reference/transformation-result.md) - Result handling
- [TransformationTrace API](reference/transformation-trace.md) - Trace and JSON export
- [Troubleshooting](reference/troubleshooting.md) - Common issues and solutions

## Quick Reference Card

| Task | Annotation/Method | Example |
|------|-------------------|---------|
| Define transformation class | `@TransformationContext` | `@TransformationContext(source = A.class, target = B.class)` |
| Define rule | `@TransformRule` | `@TransformRule(name = "A2B")` |
| Lazy evaluation | `@Lazy` | Rule executes on-demand |
| Abstract rule | `@Abstract` | Only via executeParentRule() |
| Rule inheritance | `@Extends` | `@Extends("ParentRule")` |
| Greedy matching | `@Greedy` | Matches type and subtypes |
| Primary result | `@Primary` | Preferred equivalent() result |
| Conditional | `@Guard` | `@Guard(method = "guardMethod")` |
| Create target | `ctx.createTarget()` | `ctx.createTarget(Table.class)` |
| Get equivalent | `ctx.equivalent()` | `ctx.equivalent(source, Target.class)` |

## Related Documentation

- **[Validation Framework](../validation/index.md)** - Model validation documentation
- **[Documentation Hub](../index.md)** - Main documentation index

---

**Next**: [Getting Started Guide](getting-started.md)
