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
- Cache operations: Per-key ReentrantLock
- Deferred writes: SynchronizedList for pending additions (O(1) vs O(N) copy-on-write)

### Per-Key Locking (Not Lock Striping)

The framework uses **per-key locks** for thread-safe cache operations:

```java
// Each (source, ruleName) pair has its own lock
ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
```

**Why not lock striping?** Lock striping (fixed array of 1024 locks) causes deadlocks with nested locking:
- Thread A holds stripe[42], calls `equivalent()` needing stripe[99]
- Thread B holds stripe[99], calls `equivalent()` needing stripe[42]
- Different `(source, rule)` pairs can hash to same stripe → DEADLOCK

Per-key locks guarantee unique locks per `(source, rule)`, eliminating hash collision deadlocks.

### Timeout-Based Deadlock Detection

All lock acquisitions use 30-second timeout:
```java
if (!lock.tryLock(30, TimeUnit.SECONDS)) {
    throw new RuntimeException("Potential deadlock detected...");
}
```

## Deferred Writes (Auto-enabled for Parallel)

When `parallel=true`, deferred writes are automatically enabled to prevent EMF EList corruption. Transformations can opt-out via `ctx.disableDeferredWrites()`.

### How Deferred Writes Work

```
┌──────────────────────────────────────────────────────┐
│ 1. ctx.createTarget() returns JDK dynamic proxy      │
│ 2. Proxy intercepts all eSet() and EList.add() calls │
│ 3. Operations stored in thread-safe OperationQueue   │
│ 4. After parallel phase, operations replayed         │
│    single-threaded in sequence order                 │
└──────────────────────────────────────────────────────┘
```

### EMF Bidirectional Reference Handling

EMF's bidirectional references (`eOpposite`) require special handling:

```java
// EMF internally calls:
newValue.eInverseAdd(this, OPPOSITE_FEATURE_ID, ...);

// Problem: If newValue is a proxy, eInverseAdd fails
// Solution: Unwrap at QUEUE TIME, not apply time
```

Reference values are unwrapped when the operation is queued:
```java
// In DeferredEObject.handleSet():
if (feature instanceof EReference) {
    EObject realValue = unwrap(value);  // Unwrap NOW
    queue.add(new SetReferenceOp(delegate, feature, realValue, seq));
}
```

### Pending State Cleanup

After commit, pending state must be cleared on all proxies:
```java
// In commitDeferredOperations():
for (ProxyMarker proxy : createdProxies) {
    proxy.clearPendingState();
}
```
Without this, `DeferredEList.getCombinedView()` would return both committed and stale pending values.

```java
// Automatic in parallel mode:
Table table = ctx.createTarget(Table.class);  // Returns proxy
table.getFields().add(field);                  // Recorded, not applied immediately
// Operations replayed single-threaded after parallel phase
```

### Deferred Writes Compatibility Issues

**WARNING**: Deferred writes have limitations that may affect transformation behavior:

**1. Read-After-Write Inconsistency**
```java
parent.getChildren().add(child);
int count = parent.getChildren().size();  // Returns OLD size, not +1!
boolean found = parent.getChildren().contains(child);  // Returns false!
```
*Impact*: Rules that read from lists after modifying them will get stale data.

**2. eContainer() Returns Null**
```java
parent.getChildren().add(child);
EObject container = child.eContainer();  // Returns null until replay!
```
*Impact*: Containment-based navigation fails during parallel phase.

**3. Cross-Rule Visibility**
```java
// Rule A (Thread 1)
parent.getChildren().add(childA);

// Rule B (Thread 2) - running concurrently
for (Child c : parent.getChildren()) {  // Won't see childA!
    // ...
}
```
*Impact*: Rules can't see each other's additions during parallel phase.

**4. Existing Synchronization Conflicts**
```java
// If transformation already uses synchronized helpers:
TransformationHelper.synchronizedAdd(list, element);  // Double-handling?
```
*Impact*: May conflict with existing thread-safety workarounds.

**5. List Order Dependencies**
```java
// Insertion order may differ between sequential and parallel
parent.getChildren().add(a);  // seq=5
parent.getChildren().add(b);  // seq=3 (from different thread)
// After replay: order is [b, a] not [a, b]
```
*Impact*: Element ordering may change vs. sequential mode.

### Mitigation

`DeferredEList` tracks pending additions for `contains()` and `size()` checks. However, **cross-rule visibility** and **eContainer()** issues cannot be mitigated - they're inherent to deferred writes.

### Opt-Out

If deferred writes cause issues, disable for specific transformation:
```java
@PreExecution
public void setup(TransformationContext ctx) {
    ctx.disableDeferredWrites();  // Use direct EMF writes
}
```

### Two-Phase Proxy Unwrapping

After transformation completes, all proxies must be unwrapped. This happens in two phases:

```java
public int unwrapAllProxiesInModel() {
    int unwrappedCount = 0;

    // Phase 1: Unwrap all proxies in the resolution cache
    // Critical: equivalent() returns cached values; if these are proxies,
    // they end up in containment references after transformation
    unwrappedCount += resolutionCache.unwrapAllProxies();

    // Phase 2: Unwrap proxies in the target resource
    // Handles nested elements that may still be wrapped
    if (!targetResourceSet.getResources().isEmpty()) {
        Resource targetResource = targetResourceSet.getResources().get(0);
        for (EObject root : targetResource.getContents()) {
            unwrappedCount += unwrapProxiesRecursively(root);
        }
    }

    return unwrappedCount;
}
```

**Why Phase 1 matters**: The resolution cache may contain proxy objects. If not unwrapped, these proxies contaminate the final model.

**Why Phase 2 matters**: Nested elements within the target resource may still be wrapped.

## Atomic Cache Operations

### Problem: Race Conditions
Without atomicity, parallel threads could:
1. Both check cache (miss)
2. Both execute same rule
3. Create duplicate target elements

### Solution: getOrCreate() Pattern
```java
// Lock covers: cache check + guard + rule execution
cache.getOrCreate(source, ruleName, () -> {
    if (!evaluateGuard(source)) return null;
    return executeRule(source);
}, isPrimary);
```

**Per-Key Locking**:
- Each `(source, ruleName)` pair has its own `ReentrantLock`
- Different sources/rules execute in parallel without blocking
- Same source+rule: first thread executes, others wait

### Guard Rejection Caching
```java
// If guard returns null/false, rejection is cached
rejectedKeys.add(new CacheKey(source, ruleName));
// Subsequent calls for same (source, ruleName) return null immediately
```

Benefits:
- Guards don't re-evaluate for rejected elements
- Fast path: rejection check before acquiring lock

### Shared Lock Acquisition

To prevent race conditions between `getOrCreate()` and `executeParentRule()`, both use the same lock source:

```java
// Both methods use the SAME lock to prevent race conditions
ReentrantLock lock = resolutionCache.getLockFor(source, ruleName);
```

**Why this matters**: Without shared locks, `equivalent()` and `executeParentRule()` could acquire different locks for the same `(source, ruleName)` pair, allowing both to execute simultaneously and create duplicates.

## Parent Rule Atomicity

`executeParentRule()` and `@Extends` chains use atomic `getOrCreate()`:

```java
// Multiple concurrent calls → exactly one execution
ctx.executeParentRule("ParentRule", source);
```

**Inheritance Chain Atomicity**:
```
ChildRule @Extends ParentA, ParentB
    ↓
ParentA executes once (getOrCreate)
    ↓
ParentB executes once (getOrCreate)
    ↓
Child continues with results
```

Even with 50 concurrent threads calling `executeParentRule("ParentA", sameSource)`, `ParentA` executes exactly once.

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

### TransformationException

```java
try {
    executor.transform();
} catch (TransformationException e) {
    // Get context about what failed
    EObject element = e.getFailedElement();  // Source element that caused failure
    String rule = e.getRuleName();           // Rule name that failed
    Throwable cause = e.getCause();          // Original exception

    log.error("Rule '{}' failed on element {}: {}",
        rule, element, cause.getMessage());
}
```

**Available Methods**:
| Method | Returns | Description |
|--------|---------|-------------|
| `getFailedElement()` | `EObject` | Source element being transformed |
| `getRuleName()` | `String` | Name of the failed rule |
| `getCause()` | `Throwable` | Original exception |
| `getMessage()` | `String` | Formatted error message |

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
