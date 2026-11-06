# ETL vs Zeta Transformation Overview

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Overview

This guide compares Epsilon Transformation Language (ETL) with Judo Zeta Transformation Framework.

## Philosophy Comparison

| Aspect | ETL | Zeta |
|--------|-----|------|
| **Language** | Domain-specific language (DSL) | Pure Java with annotations |
| **Type Safety** | Runtime | Compile-time |
| **IDE Support** | Limited (Epsilon plugins) | Full (any Java IDE) |
| **Debugging** | ETL debugger | Standard Java debugger |
| **Testing** | Epsilon test framework | JUnit, TestNG, etc. |
| **Learning Curve** | Learn ETL + EOL | Use existing Java skills |

## Key Differences

### Declarative vs Programmatic

**ETL (Declarative)**:
```etl
rule EntityType2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    
    t.name = e.name;
    t.columns.addAll(e.attributes.equivalent());
}
```

**Zeta (Programmatic)**:
```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (e, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setName(e.getName());
        for (Attribute attr : e.getAttributes()) {
            t.getColumns().add(ctx.equivalent(attr, Column.class));
        }
        return t;
    };
}
```

### Type Safety

**ETL**: Type errors discovered at runtime
```etl
t.name = e.getName();  // Error only at execution
```

**Zeta**: Type errors caught at compile time
```java
t.setName(e.getName());  // IDE shows error immediately if types mismatch
```

## When to Use Which

### Choose ETL When:
- Team is already proficient in Epsilon
- Transformations are simple and declarative
- Quick prototyping is the priority
- Eclipse-based toolchain is required

### Choose Zeta When:
- Type safety is critical
- Team prefers Java over DSLs
- Standard Java tooling is desired (build, test, debug)
- Integration with existing Java codebase
- Performance is important (no interpretation overhead)
- Parallel execution for large models

## Benefits of Zeta

1. **Compile-Time Safety**: Catch errors before execution
2. **IDE Support**: Full autocomplete, refactoring, navigation
3. **Debugging**: Use your favorite Java debugger
4. **Testing**: Standard JUnit tests
5. **Performance**: Native Java execution, automatic parallelization
6. **Maintainability**: Familiar Java patterns and practices
7. **Traceability**: Built-in transformation trace with JSON export

## Resource Alias Comparison

### ETL Model References

```etl
rule Entity2Table
    transform e : ASM!EntityType  // Model name prefix
    to t : RDBMS!Table {
    ...
}
```

### Zeta @Transform / @To Annotations

```java
@TransformRule(name = "Entity2Table")
@Transform(alias = "asm", type = EntityType.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<EntityType, Table> entity2Table() { ... }

// Registration
ctx.registerResource("asm", asmResourceSet);
ctx.registerResource("rdbms", rdbmsResourceSet);
```

## ETL Parity Features

### @ActivityBased (ETL Lazy Rule Behavior)

ETL lazy rules implicitly filter to only process elements reachable from eager rules. Zeta provides `@ActivityBased` for the same behavior:

```java
// ETL behavior: lazy rules only transform activated elements
@TransformRule(name = "Type2Element")
@Greedy
@Lazy
@ActivityBased  // ETL-compatible filtering
public TransformFunction<Type, Element> type2Element() { ... }
```

Or enable globally:
```java
TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // All @Greedy @Lazy become activity-based
    .build();
```

## Feature Parity Table

| ETL Feature | Zeta Equivalent | Notes |
|-------------|-----------------|-------|
| `rule` | `@TransformRule` | Identical semantics |
| `transform e : Model!Type` | `@Transform(alias, type)` | Explicit alias |
| `to t : Model!Type` | `@To(alias, type)` | Explicit alias |
| `guard` | `@Guard(method)` | Same logic, different syntax |
| `@lazy` | `@Lazy` | Identical semantics |
| `@abstract` | `@Abstract` | Identical semantics |
| `@greedy` | `@Greedy` | Identical semantics |
| `@primary` | `@Primary` | Identical semantics |
| `extends rule` | `@Extends({"rule"})` | Multiple inheritance supported |
| `e.equivalent()` | `ctx.equivalent(e, Type)` | Explicit target type |
| Implicit lazy filtering | `@ActivityBased` | Explicit opt-in |
| `pre { }` | `@PreExecution` | Method-based |
| `post { }` | `@PostExecution` | Method-based |

## Migration Path

See the [Migration Guide](migration-guide.md) for step-by-step instructions on converting ETL transformations to Zeta.

---

**Next**: [Syntax Mapping](syntax-mapping.md)
