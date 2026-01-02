# Performance Optimization

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Performance

Optimize transformation performance for large models.

## Parallel Execution

### Configuration

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)            // Enable parallel (default: true)
    .parallelThreshold(1000)   // Default: 1000 elements
    .chunkSize(100)            // Default: 100 elements per chunk
    .build();
```

### Threshold Behavior

| Element Count | Execution Mode |
|---------------|----------------|
| < 1000 | Sequential |
| >= 1000 | Parallel (chunked) |

### Executor Reuse

Create the executor once and reuse it:

```java
// Create once (thread pool is lazy-initialized)
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .build();

// Reuse for multiple transformations - state resets automatically
executor.transform();  // First transformation
executor.transform();  // Second transformation

// Shutdown when completely done
executor.shutdown();
```

## Thread-Safety Guidelines

When parallel execution is active, transformation rules must be thread-safe.

### Safe Operations

| Operation | Thread-Safe? | Reason |
|-----------|--------------|--------|
| `ctx.createTarget()` | ✅ Yes | Uses staging + atomic sequence |
| `ctx.equivalent()` | ✅ Yes | Atomic getOrCreate() |
| `ctx.executeParentRule()` | ✅ Yes | Per-key locking |
| `ctx.getAttribute()` | ✅ Yes | ConcurrentHashMap |
| Setting properties on created element | ✅ Yes | Thread-local element |

### Unsafe Operations to Avoid

| Operation | Thread-Safe? | Alternative |
|-----------|--------------|-------------|
| Shared mutable fields | ❌ No | Use `ConcurrentHashMap` |
| Direct Resource modification | ❌ No | Use `ctx.createTarget()` |
| Static field access | ❌ No | Use `ctx.setAttribute()` |
| Non-atomic counters | ❌ No | Use `AtomicInteger` |

### Thread-Safe Pattern

```java
// Use concurrent collections for shared state
private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

@TransformRule(name = "SafeRule")
public TransformFunction<EntityType, Table> safeRule() {
    return (entity, ctx) -> {
        // SAFE: Atomic operations
        counters.computeIfAbsent(entity.getNamespace().getName(),
            k -> new AtomicInteger()).incrementAndGet();

        Table table = ctx.createTarget(Table.class);  // Thread-safe
        table.setName(entity.getName());
        return table;
    };
}
```

## Common Parallel Execution Issues

### Issue 1: NPE - preparedResult is null

**Symptom:**
```
Cannot invoke "org.eclipse.emf.ecore.InternalEObject.eDirectResource()"
because "this.preparedResult" is null
```

**Cause:** Direct EMF factory usage bypasses Zeta's thread-safe staging mechanism.

**Fix:**
```java
// BEFORE (UNSAFE):
RdbmsTable table = rdbmsFactory.createRdbmsTable();
targetResource.getContents().add(table);

// AFTER (SAFE):
RdbmsTable table = ctx.createTarget(RdbmsTable.class);
// No resource.add() needed - staging handles it
```

### Issue 2: Duplicate Key IllegalStateException

**Symptom:**
```
IllegalStateException: Duplicate key rackinspect.entities.DimensionTemplateType
```

**Cause:** Multiple threads creating targets for same source before cache updated.

**Fix:** Zeta's atomic `getOrCreate()` handles this automatically. Ensure:
- Using latest Zeta version
- All creation goes through `ctx.createTarget()` or `ctx.equivalent()`
- No direct factory calls that bypass the cache

### Issue 3: Extra Elements in Output

**Symptom:** Model has more elements than expected (+10, +13, etc.)

**Cause:** Race conditions in rule execution or cache lookup.

**Fix:**
- Use `ctx.equivalent()` for lookups (atomic)
- Use `ctx.executeParentRule()` for inheritance (atomic)
- Avoid direct factory creation

## Migration from Direct Factory Pattern

For projects using direct EMF factory (like tatami-base), migrate to Zeta patterns:

| Before (Unsafe) | After (Safe) |
|-----------------|--------------|
| `factory.createXxx()` | `ctx.createTarget(Xxx.class)` |
| `factory.createXxx()` (contained) | `ctx.create(Xxx.class)` |
| `resource.getContents().add(e)` | Return from rule (auto-staged) |
| Manual trace map | `ctx.equivalent()` |

### Synchronized Helper Pattern (Temporary)

If full migration is not immediately possible:

```java
public class TransformationHelper {
    public static void addToResource(Resource resource, EObject element) {
        synchronized (resource) {
            if (!resource.getContents().contains(element)) {
                resource.getContents().add(element);
            }
        }
    }
}
```

**Note:** This is a temporary workaround. Prefer full migration to `ctx.createTarget()`.

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
