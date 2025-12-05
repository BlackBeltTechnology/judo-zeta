# Judo Zeta Framework Documentation

Welcome to the comprehensive documentation for the Judo Zeta Framework. This project provides annotation-based frameworks for Eclipse Modeling Framework (EMF) model processing.

## Frameworks

Judo Zeta consists of two complementary frameworks:

### Validation Framework

Annotation-based model validation for checking model correctness and quality.

**[Validation Documentation](validation/index.md)**

- Validate EMF models with compile-time type safety
- Define constraints (`@Constraint`) and warnings (`@Critique`)
- Conditional validation with guards and dependencies
- Parallel execution for large models
- EVL (Epsilon Validation Language) replacement

**Quick Start**: [Getting Started with Validation](validation/getting-started.md)

### Transformation Framework

Annotation-based model-to-model transformation for converting between metamodels.

**[Transformation Documentation](transformation/index.md)**

- Transform EMF models with compile-time type safety
- Define rules with `@TransformRule`
- Lazy evaluation, rule inheritance, greedy matching
- Parallel execution for large models
- ETL (Epsilon Transformation Language) replacement

**Quick Start**: [Getting Started with Transformation](transformation/getting-started.md)

## When to Use Which Framework

| Task | Framework |
|------|-----------|
| Check if model is valid | Validation |
| Find errors and warnings in model | Validation |
| Enforce business rules | Validation |
| Convert model to different metamodel | Transformation |
| Generate target model from source model | Transformation |
| Map ESM to PSM, PSM to ASM | Transformation |

## Shared Concepts

Both frameworks share common concepts:

| Concept | Validation | Transformation |
|---------|------------|----------------|
| Context annotation | `@ValidationContext` | `@TransformationContext` |
| Guard conditions | `@Guard` | `@Guard` |
| Extension methods | `@ExtensionMethod` | `@ExtensionMethod` |
| Caching | `@Cached` | `@Cached` |
| Pre/Post hooks | `@PreValidation`/`@PostValidation` | `@PreExecution`/`@PostExecution` |

## Quick Reference

### Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null 
                ? ValidationResult.pass()
                : ValidationResult.fail("Name is required");
        };
    }
}
```

### Transformation

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            return table;
        };
    }
}
```

## Key Benefits

Both frameworks provide:

- **Type Safety** - Compile-time type checking catches errors early
- **IDE Support** - Full autocomplete, refactoring, and debugging
- **Performance** - No interpretation overhead, automatic parallelization
- **Testability** - Standard unit testing with JUnit
- **Maintainability** - Familiar Java code, no DSL to learn

## Documentation Structure

Each framework has parallel documentation:

```
docs/
├── index.md                 # This page
├── validation/
│   ├── index.md             # Validation hub
│   ├── getting-started.md
│   ├── user-guide/
│   ├── best-practices/
│   ├── evl-comparison/
│   ├── examples/
│   ├── architecture/
│   └── reference/
└── transformation/
    ├── index.md             # Transformation hub
    ├── getting-started.md
    ├── user-guide/
    ├── best-practices/
    ├── etl-comparison/
    ├── examples/
    ├── architecture/
    └── reference/
```

## Getting Help

- **Validation Issues**: [Troubleshooting Guide](validation/reference/troubleshooting.md)
- **Transformation Issues**: [Troubleshooting Guide](transformation/reference/troubleshooting.md)
- **Bug Reports**: https://github.com/BlackBeltTechnology/judo-zeta/issues

## License

This documentation is part of the Judo Zeta Framework, licensed under the Eclipse Public License 2.0 (EPL-2.0).

---

**Start Here**: 
- [Validation Getting Started](validation/getting-started.md)
- [Transformation Getting Started](transformation/getting-started.md)
