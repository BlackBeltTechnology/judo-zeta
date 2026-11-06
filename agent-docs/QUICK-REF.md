# Zeta Transformation Quick Reference

## Minimal Working Example

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityToTableTransform {
    
    @TransformRule(name = "Entity2Table")
    @Transform(type = EntityType.class)
    @To(type = Table.class)
    public TransformFunction<EntityType, Table> entity2Table() {
        return (source, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(source.getName());
            return table;
        };
    }
}
```

## Execution

```java
TransformationRegistry registry = new TransformationRegistry();
registry.register(EntityToTableTransform.class);

TransformationContext context = new TransformationContext(
    modelProvider, sourceRS, targetRS, extensionRegistry);
context.setTransformationRegistry(registry);

TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .build();

TransformationResult result = executor.transform();
```

## Core Annotations

| Annotation | Level | Purpose |
|------------|-------|---------|
| `@TransformationContext` | Class | Marks transformation class, sets default types |
| `@TransformRule(name="...")` | Method | Defines a rule (name must be unique) |
| `@Transform(type=X.class)` | Method | Source type (repeatable for multi-source) |
| `@To(type=Y.class)` | Method | Target type |
| `@Guard(method="...")` | Method | Conditional execution |
| `@Lazy` | Method | On-demand execution via equivalent() |
| `@Abstract` | Method | Only via inheritance |
| `@Extends({"parent"})` | Method | Inherit from parent rule |
| `@Primary` | Method | Preferred by equivalent() |
| `@Greedy` | Method | Match subtypes too |
| `@ActivityBased` | Method | With @Greedy @Lazy: process only activated elements |

## TransformationContext Key Methods

```java
// Create target element (adds to Resource)
Table table = ctx.createTarget(Table.class);

// Get transformed equivalent (cached, triggers lazy rules)
Column col = ctx.equivalent(attribute, Column.class);

// Get all equivalents of type
List<Column> cols = ctx.equivalents(source, Column.class);

// Execute parent rule (for @Extends)
Table base = ctx.executeParentRule("BaseTransform", source);

// Get all source elements of type
Collection<EntityType> entities = ctx.getAllSource(EntityType.class);
```

## Rule Return Types

```java
// Single-source rule
TransformFunction<SourceType, TargetType>

// Multi-source rule (Cartesian product)
MultiSourceTransformFunction<TargetType>
```

## Guards

```java
@TransformRule(name = "Entity2Table")
@Guard(method = "isNotAbstract")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { /* ... */ };
}

// Guard method signature (same class)
private boolean isNotAbstract(EntityType entity, TransformationContext ctx) {
    return !entity.isAbstract();
}
```

## Lifecycle Hooks

```java
@PreExecution
public void setup(TransformationContext ctx) {
    ctx.setAttribute("stats", new HashMap<>());
}

@PostExecution
public void cleanup(TransformationContext ctx) {
    log.info("Done: {}", ctx.getAttribute("stats"));
}
```

## Common Patterns

### Transform Related Elements
```java
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    for (Attribute attr : source.getAttributes()) {
        Column col = ctx.equivalent(attr, Column.class);
        if (col != null) {
            table.getColumns().add(col);
        }
    }
    return table;
};
```

### Discriminated Equivalence (Multiple outputs from same source)
```java
// Create rule
for (String op : Arrays.asList("create", "read", "update", "delete")) {
    Operation operation = ctx.create(Operation.class);
    operation.setName(op + source.getName());
    ctx.getElementResolutionCache().addDiscriminatedMapping(
        source, operation, "CrudOperations", op);
}

// Lookup later
Operation createOp = ctx.equivalentDiscriminated(
    source, Operation.class, "CrudOperations", "create");
```

## Type Matching

| Annotation | Behavior |
|------------|----------|
| (default) | Exact type match only |
| `@Greedy` | Match type + all subtypes |

## Execution Order

1. `@PreExecution` hooks
2. Eager rules (non-lazy, non-abstract) - in registration order
3. Lazy rules - on-demand via `equivalent()`
4. `@PostExecution` hooks

## Parallel Execution

- Enabled by default
- Activates when elements >= 1000 (configurable)
- Uses staging + commit pattern for thread safety
- Atomic cache operations prevent duplicate elements

## Thread-Safe Rule Guidelines

**Safe** in transformation rules:
- `ctx.createTarget()` / `ctx.create()`
- `ctx.equivalent()` / `ctx.equivalents()`
- `ctx.executeParentRule()` (atomic)
- Reading source elements
- Setting properties on own element

**CRITICAL - Avoid** in transformation rules:
- `factory.createXxx()` - bypasses staging, causes NPE
- `resource.getContents().add()` - race condition
- Shared mutable state (ArrayList, HashMap)
- Static fields
- Modifying elements from `ctx.equivalent()` result

## Migration from Direct Factory

```java
// BEFORE (UNSAFE for parallel):
RdbmsTable table = rdbmsFactory.createRdbmsTable();
targetResource.getContents().add(table);

// AFTER (SAFE):
RdbmsTable table = ctx.createTarget(RdbmsTable.class);
// No resource.add() needed - staging handles it
```

See PATTERNS.md "Migration from Direct EMF Factory Pattern" for details.

## Error Handling

```java
try {
    executor.transform();
} catch (TransformationException e) {
    log.error("Rule '{}' failed on {}: {}",
        e.getRuleName(),
        e.getFailedElement(),
        e.getCause().getMessage());
}
```

## Performance Profiling

```java
// Enable metrics before transformation
TransformationMetrics.enable();

executor.transform();

// Print comprehensive performance report
System.out.println(TransformationMetrics.getReport());

// Reset for next run
TransformationMetrics.disable();
TransformationMetrics.reset();
```

Report shows: equivalent() calls, cache hit rate, per-rule timing, top 10 slowest rules, potential issues.
