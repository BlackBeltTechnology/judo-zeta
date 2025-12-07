# Performance Optimization

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Performance

Optimize transformation performance for large models.

## Parallel Execution

### Configuration

```java
TransformationExecutor executor = new TransformationExecutor(
    registry,
    context,
    true  // Enable parallel execution
);

// Parallel execution activates when elements >= 5000
// Elements are processed in chunks of 100
```

### Threshold Behavior

| Element Count | Execution Mode |
|---------------|----------------|
| < 5000 | Sequential |
| >= 5000 | Parallel (chunked) |

## Caching Strategies

### Use @Cached for Expensive Operations

```java
@ExtensionMethod(elementType = EntityType.class)
public class EntityTypeExtensions {
    
    @Cached  // Cache result per entity
    public List<EntityType> getAllDescendants(EntityType entity, TransformationContext ctx) {
        // Expensive O(n) operation
        return ctx.getAllSource(EntityType.class).stream()
            .filter(e -> isDescendantOf(e, entity))
            .toList();
    }
}
```

### Avoid Repeated equivalent() Calls

```java
// Bad: Repeated calls (even if cached, there's lookup overhead)
for (int i = 0; i < 100; i++) {
    Table t = ctx.equivalent(entity, Table.class);
    process(t);
}

// Good: Cache locally
Table t = ctx.equivalent(entity, Table.class);
for (int i = 0; i < 100; i++) {
    process(t);
}
```

## Lazy Rules for Performance

```java
// Use @Lazy for conditionally-needed transformations
@TransformRule(name = "Reference2ForeignKey")
@Lazy  // Only transform references that are actually used
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() { ... }
```

## Avoid Expensive Operations in Rules

```java
// Bad: getAllSource() in every rule execution
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        // O(n) operation for each of n entities = O(n²)
        Collection<EntityType> all = ctx.getAllSource(EntityType.class);
        // ...
    };
}

// Good: Use @PreExecution to compute once
@PreExecution
public void prepareData(TransformationContext ctx) {
    Collection<EntityType> all = ctx.getAllSource(EntityType.class);
    ctx.setAttribute("allEntities", all);
}

@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Collection<EntityType> all = (Collection<EntityType>) ctx.getAttribute("allEntities");
        // O(1) lookup
    };
}
```

## Profiling Transformations

```java
@PreExecution
public void startTimer(TransformationContext ctx) {
    ctx.setAttribute("startTime", System.currentTimeMillis());
}

@PostExecution  
public void endTimer(TransformationContext ctx) {
    long start = (long) ctx.getAttribute("startTime");
    long duration = System.currentTimeMillis() - start;
    log.info("Transformation took {}ms", duration);
}
```

---

**Previous**: [Rule Organization](rule-organization.md) | **Next**: [Error Handling](error-handling.md)
