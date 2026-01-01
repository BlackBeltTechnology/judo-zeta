# Transformation Execution

## Execution Flow

```
┌─────────────────────────────────────────────────────────┐
│ executor.transform()                                     │
├─────────────────────────────────────────────────────────┤
│ 1. Reset state (clear caches, staging)                  │
│ 2. Invoke @PreExecution hooks                           │
│ 3. Collect source elements from registered resources    │
│ 4. Execute eager rules (non-lazy, non-abstract)         │
│    ├─ Sequential if elements < 1000                     │
│    └─ Parallel with staging if elements >= 1000         │
│ 5. Execute multi-source rules (Cartesian product)       │
│ 6. Invoke @PostExecution hooks                          │
│ 7. Return TransformationResult                          │
└─────────────────────────────────────────────────────────┘
```

## Rule Execution Order

Rules execute in **registration order** within each phase:

1. **Eager Phase**: All non-lazy, non-abstract rules
2. **Lazy Phase**: On-demand via `equivalent()` calls
3. **Multi-source**: Cartesian product rules after single-source

## Rule Filtering (executeEagerRulesFor)

For each source element, rules are skipped if:
- `isMultiSource()` → handled separately
- `isLazy()` → on-demand only
- `isAbstract()` → inheritance only
- Already cached → idempotent
- Guard returns false
- Type doesn't match (`appliesTo()`)
- Wrong resource alias

## Parallel Execution

### Activation
```java
boolean useParallel = parallel && sourceElements.size() >= parallelThreshold;
```

Default thresholds:
- `parallelThreshold`: 1000 elements
- `chunkSize`: 100 elements per chunk

### Configuration
```java
TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)           // Enable (default: true)
    .parallelThreshold(500)   // Lower threshold
    .chunkSize(50)            // Smaller chunks
    .build();
```

### Staging Pattern
```
┌───────────────────────────────────────┐
│ Parallel Phase (multi-threaded)       │
│ ├─ Elements created in staging area   │
│ ├─ Thread-local current source        │
│ └─ Sequence numbers for ordering      │
├───────────────────────────────────────┤
│ Commit Phase (single-threaded)        │
│ └─ Staged elements added to Resource  │
└───────────────────────────────────────┘
```

### Thread Safety
- `currentSource`: ThreadLocal per thread
- Staging: ConcurrentLinkedQueue
- Element ordering: AtomicLong sequence
- Lazy rule tracking: ConcurrentHashMap

## Cartesian Product Execution

For rules with multiple `@Transform` annotations:

```java
@TransformRule(name = "EntityMapping")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
public MultiSourceTransformFunction<Table> entityMapping() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        // ...
    };
}
```

Execution:
```
asm: [A, B, C]  ×  mapping: [M1, M2]
= 6 executions: (A,M1), (A,M2), (B,M1), (B,M2), (C,M1), (C,M2)
```

## Lazy Rule Execution

Lazy rules execute via `equivalent()`:

```
ctx.equivalent(source, TargetType.class)
    │
    ├─ Check cache → return if exists
    │
    └─ Find matching lazy rule
        ├─ Evaluate guard
        ├─ Execute rule
        ├─ Cache result
        └─ Return target
```

## Error Handling

**Fail-fast behavior**: First error stops all processing

```java
// Error captured in AtomicReference
firstError.compareAndSet(null, transformException);

// Checked after parallel phase
if (firstError.get() != null) {
    throw (TransformationException) firstError.get();
}
```

## TransformationResult

```java
TransformationResult result = executor.transform();

result.getTargetResourceSet();  // Target ResourceSet
result.getContext();            // TransformationContext
result.getDurationMs();         // Execution time
result.getTrace();              // TransformationTrace for export
```

## Executor Lifecycle

```java
// Create once
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .build();

// Reusable - state reset on each transform()
executor.transform();
executor.transform();  // Safe to call again

// Shutdown when done (releases thread pool)
executor.shutdown();
```
