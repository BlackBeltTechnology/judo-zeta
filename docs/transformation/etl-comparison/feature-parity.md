# ETL Feature Parity

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Feature Parity

Comparison of ETL and Zeta Transformation Framework features.

## Feature Matrix

| Feature | ETL | Zeta | Notes |
|---------|-----|------|-------|
| **Rule Definition** | | | |
| Basic rules | Yes | Yes | Full support |
| @lazy rules | Yes | Yes | `@Lazy` annotation |
| @abstract rules | Yes | Yes | `@Abstract` annotation |
| @primary rules | Yes | Yes | `@Primary` annotation |
| @greedy rules | Yes | Yes | `@Greedy` annotation |
| Rule inheritance (extends) | Yes | Yes | `@Extends` annotation |
| Guard conditions | Yes | Yes | `@Guard` annotation |
| **Element Resolution** | | | |
| equivalent() | Yes | Yes | `ctx.equivalent()` |
| equivalents() | Yes | Yes | `ctx.equivalents()` |
| equivalent("ruleName") | Yes | Partial | Via cache lookup |
| **Lifecycle** | | | |
| pre blocks | Yes | Yes | `@PreExecution` |
| post blocks | Yes | Yes | `@PostExecution` |
| **Collections** | | | |
| select/filter | Yes | Yes | Java streams |
| collect/map | Yes | Yes | Java streams |
| exists/anyMatch | Yes | Yes | Java streams |
| forAll/allMatch | Yes | Yes | Java streams |
| **Execution** | | | |
| Sequential execution | Yes | Yes | Default mode |
| Parallel execution | Partial | Yes | Automatic for large models |
| **Debugging** | | | |
| Step debugging | ETL debugger | Java debugger | Standard IDE tools |
| Trace output | Epsilon trace | JSON trace | Built-in export |
| **Type Safety** | | | |
| Compile-time checking | No | Yes | Major Zeta advantage |
| IDE autocomplete | Limited | Full | Standard Java support |

## Supported Features

### Fully Supported

- Basic transformation rules
- All rule modifiers (@lazy, @abstract, @primary, @greedy)
- Rule inheritance with @Extends
- Guard conditions
- Pre/post execution hooks
- Element resolution (equivalent/equivalents)
- Extension methods with caching
- Parallel execution

### Partially Supported

| Feature | ETL | Zeta Alternative |
|---------|-----|------------------|
| Multiple target elements | `to t1, t2, t3` | Use separate rules or discriminated equivalence |
| EOL operations | Built-in | Use Java methods or extension methods |
| Dynamic typing | Yes | Use generics or Object type |

## Unsupported Features (with Alternatives)

### Multiple Target Types in One Rule

**ETL**:
```etl
rule Entity2TableAndView
    transform e : ESM!EntityType
    to t : PSM!Table, v : PSM!View {
    t.name = e.name;
    v.name = e.name + "View";
}
```

**Zeta Alternative**: Use two rules
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() { ... }

@TransformRule(name = "Entity2View")
public TransformFunction<EntityType, View> entity2View() { ... }
```

### EOL Built-in Operations

**ETL**:
```etl
e.attributes.first()
e.name.toUpperCase()
collection.flatten()
```

**Zeta Alternative**: Java equivalents
```java
e.getAttributes().isEmpty() ? null : e.getAttributes().get(0)
e.getName().toUpperCase()
collection.stream().flatMap(Collection::stream).toList()
```

## Future Roadmap

Potential future additions:
- Bidirectional transformations
- Incremental transformations (re-execute only changed elements)
- Model merging (multiple source models)
- Validation integration with validation-core
- Transformation composition (chaining)

---

**Previous**: [Migration Guide](migration-guide.md) | **Next**: [Examples](../examples/simple-transformations.md)
