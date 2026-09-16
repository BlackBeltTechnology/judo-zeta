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

### TransformationMetrics

Zeta includes a built-in profiling system for identifying performance bottlenecks.

#### Enable Metrics

```java
// Enable before transformation
TransformationMetrics.enable();

// Run transformation
executor.transform();

// Get comprehensive report
System.out.println(TransformationMetrics.getReport());

// Disable and reset for next run
TransformationMetrics.disable();
TransformationMetrics.reset();
```

#### What Metrics Are Collected

| Metric Category | What It Tracks |
|-----------------|----------------|
| **Operation Counts** | equivalent() calls, cache hits/misses, rule iterations, guard evaluations |
| **Timing Breakdown** | Time spent in equivalent(), getRulesForSource(), guard evaluation, rule execution |
| **Per-Rule Metrics** | Execution count and total time for each lazy and greedy rule |
| **XMI ID Lookups** | findByXmiId() calls and scans (helps identify O(n) bottlenecks) |
| **Lock Contention** | Lock acquisition count and wait time |

#### Sample Report Output

```
========== ZETA TRANSFORMATION PERFORMANCE REPORT ==========

=== OPERATION COUNTS ===
  equivalent() calls:       45,234
    - Cache hits:           41,892 (92.6%)
    - Cache misses:         3,342 (7.4%)
  getRulesForSource() calls: 3,342
  Rule iterations:          18,456
  Guard evaluations:        12,234
  Rule executions:          3,342
  findByXmiId() calls:      156
  findByXmiId() scans:      0

=== TIMING BREAKDOWN ===
  equivalent() total:       32,853 ms
    - getRulesForSource:    1,234 ms (3.8%)
    - Guard evaluation:     2,456 ms (7.5%)
    - Rule execution:       28,456 ms (86.6%)
    - findByXmiId:          12 ms (0.0%)
    - Cache operations:     234 ms (0.7%)
    - Lock wait:            45 ms (0.1%)

=== TOP 10 SLOWEST LAZY RULES (via equivalent()) ===
  EntityType2EClass                                    4,567 ms (234 calls, 19.517 ms/call)
  Reference2EReference                                 3,456 ms (567 calls, 6.095 ms/call)
  ...

=== TOP 10 SLOWEST GREEDY RULES ===
  Model2EPackage                                       2,345 ms (12 calls, 195.417 ms/call)
  ...

=== POTENTIAL ISSUES ===
  (none detected)
============================================================
```

#### Identifying Bottlenecks

| Issue Indicator | Meaning | Solution |
|-----------------|---------|----------|
| Low cache hit rate (<80%) | Repeated lookups with different params | Use consistent equivalent() calls |
| High XMI ID scans (>1000) | O(n) linear scans in findByXmiId | Ensure using indexed lookups |
| High ms/call for specific rule | Expensive rule implementation | Optimize rule or use @Cached |
| High rule iterations per source | Many rules checked per element | Consider rule organization |

### Built-in Performance Optimizations

Zeta includes several optimizations enabled by default:

| Optimization | Impact | Description |
|--------------|--------|-------------|
| **Skip XMI Resource Lookup** | **-91%** greedy time | For fresh transformations, XMI resource lookups always return null. `skipXmiIdResourceLookup=true` (default) skips these wasteful lookups. |
| **Pending XMI ID Index** | O(1) lookup | Reverse index `pendingXmiIdIndex` replaces O(n) linear scan in `findByXmiId()` |
| **Model Traversal Caching** | **-80%** collection time | Results of `context.all(alias, type)` are cached by (alias, type) pair. Same type traversed only once even if 10 rules use it. |
| **Rule Lookup Caching** | **-90%** lookup time | `getRulesForSource(type)` results cached in `ConcurrentHashMap` with O(1) deduplication. |
| **Per-Key Locking** | Thread-safe | Uses per-key `ReentrantLock` for each `(source, ruleName)` pair to prevent duplicate element creation and avoid deadlocks from nested locking. |
| **Deadlock Prevention** | Thread-safe | Uses `tryLock()` with 30s timeout instead of blocking locks to prevent circular wait deadlocks. |
| **Atomic Cache Operations** | Thread-safe | `getOrCreate()` pattern prevents duplicate element creation |
| **Two-Phase Staging** | Parallel-safe | Elements staged during parallel execution, committed single-threaded |

**Real-world impact (22,000 element model):**

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Total time | 40,844 ms | 9,007 ms | **-78%** |
| Greedy rules | 20,561 ms | 1,759 ms | **-91%** |
| vs ETL | 1.79x slower | 2.5x faster | |

### Deadlock Prevention

Zeta uses two mechanisms to prevent deadlocks in parallel execution:

1. **Per-key locking** instead of lock striping
2. **Timeout-based lock acquisition** with `tryLock(30, TimeUnit.SECONDS)`

#### Why Per-Key Locks (Not Lock Striping)

Lock striping (using a fixed array of 1024 locks) can cause deadlocks due to hash collisions when combined with nested locking:

```
Lock Striping Deadlock (AVOIDED):
┌─────────────────────────────────────────────────────────────┐
│ Thread A: holds stripe[42] for (src1, ruleA)                │
│           → rule body calls equivalent() → needs stripe[99] │
│                                                             │
│ Thread B: holds stripe[99] for (src2, ruleB)                │
│           → rule body calls equivalent() → needs stripe[42] │
│                                                             │
│ DEADLOCK: Different (source, rule) pairs hash to same stripe│
└─────────────────────────────────────────────────────────────┘
```

Per-key locks guarantee each `(source, ruleName)` pair has its own unique lock, eliminating hash collision deadlocks. Rule bodies calling `equivalent()` (nested locking) safely acquire different locks.

#### Circular Dependency Detection

Even with per-key locks, true circular dependencies can still cause deadlocks:

```
Circular Dependency (Detected via Timeout):
Thread A: Holds lock(Entity1, "EntityToTable")
          Calls equivalent(Entity2, "ReferenceToFK") → waits

Thread B: Holds lock(Entity2, "ReferenceToFK")
          Calls equivalent(Entity1, "EntityToTable") → waits
```

#### The Solution

`tryLock(30, TimeUnit.SECONDS)` detects potential deadlocks:
- If the lock is acquired within 30 seconds, execution continues normally
- If timeout occurs, a descriptive exception is thrown identifying the deadlock source

#### Troubleshooting Deadlock Errors

If you see an error like:
```
Potential deadlock detected: timeout waiting for lock on equivalent(EntityType, EntityToTable).
This may indicate circular rule dependencies.
```

**Solutions:**
1. **Review rule dependencies**: Check if rules call `equivalent()` on each other's source types
2. **Use sequential mode**: Set `parallelExecution=false` to avoid parallel locking
3. **Restructure rules**: Break circular dependencies by introducing intermediate rules
4. **Increase timeout**: For very large models, the 30-second timeout may be insufficient (contact maintainers)

### Basic Timer (Alternative)

For simple timing without full metrics:

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
