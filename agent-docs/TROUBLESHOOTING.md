# Troubleshooting Guide

## Rule Not Executing

### Check 1: Registration
```java
// Ensure class is registered
registry.register(MyTransform.class);

// Verify rule exists
TransformRuleDescriptor rule = registry.getRuleByName("MyRule");
assertNotNull(rule);
```

### Check 2: Annotations
```java
// Required annotations
@TransformRule(name = "MyRule")  // Required
@Transform(type = SourceType.class)  // Recommended
```

### Check 3: Rule Modifiers
- `@Lazy` → Only executes via `equivalent()`, not eager phase
- `@Abstract` → Only executes via `executeParentRule()`

### Check 4: Guard Condition
```java
// Guard returning false skips rule
@Guard(method = "myGuard")

// Debug guard
private boolean myGuard(SourceType s, TransformationContext ctx) {
    boolean result = /* condition */;
    log.debug("Guard for {}: {}", s, result);
    return result;
}
```

### Check 5: Type Matching
```java
// Non-greedy (default): exact type only
@Transform(type = EntityType.class)  // Won't match SubEntityType

// Greedy: includes subtypes
@Transform(type = EntityType.class)
@Greedy  // Matches EntityType and SubEntityType
```

### Check 6: Resource Alias
```java
// Element must come from correct alias
@Transform(alias = "asm", type = EntityType.class)

// Verify registration
ctx.registerResource("asm", asmResourceSet);
```

## equivalent() Returns Null

### Cause 1: No Matching Rule
```java
// Check rule exists for source type
Collection<TransformRuleDescriptor> rules = 
    registry.getRulesForSource(source.getClass());
```

### Cause 2: Lazy Rule Not Triggered
```java
// Lazy rules need explicit equivalent() call
// They don't execute during eager phase
```

### Cause 3: Guard Failed
```java
// Add logging to guard method
```

### Cause 4: Wrong Target Type
```java
// Rule produces Table, but asking for Column
ctx.equivalent(source, Column.class);  // Returns null
```

## Duplicate Execution

### Cause: Missing Cache Check
```java
// equivalent() is cached - safe to call multiple times
Column col = ctx.equivalent(attr, Column.class);  // Executes once
Column col2 = ctx.equivalent(attr, Column.class); // Returns cached
```

### Cause: Multiple Rules Same Type
```java
// Two rules producing same target type
// Use @Primary to designate preferred
@TransformRule(name = "Rule1")
@Primary
public TransformFunction<A, B> rule1() { }

@TransformRule(name = "Rule2")
public TransformFunction<A, B> rule2() { }
```

## Parallel Execution Issues

### Symptom: Non-deterministic Order
```java
// Elements may process in different order
// Use element sequence for deterministic iteration
long seq = ctx.getElementSequence(element);
```

### Symptom: Concurrent Modification
```java
// Don't modify shared state without synchronization
// Use ctx.setAttribute() for thread-safe storage
```

### Fix: Disable Parallel
```java
TransformationExecutor.builder()
    .parallel(false)  // Force sequential
    .build();
```

## Race Conditions (Duplicate Elements)

### Symptom
- More elements in output than expected (e.g., +13 extra elements)
- Inconsistent counts between runs
- `executeParentRule()` producing multiple results

### Cause
Without atomic operations, concurrent threads can:
1. Both check cache (miss)
2. Both execute same rule
3. Both create target elements

### Solution: Atomic getOrCreate() (Already Built-in)
The framework uses atomic `getOrCreate()` pattern:
```java
// Internal implementation - automatic
cache.getOrCreate(source, ruleName, () -> {
    return executeRule(source);
}, isPrimary);
```

### Verification Steps
1. **Check element counts**:
   ```java
   int expected = ctx.getAllSource(Type.class).size();
   int actual = ctx.getAllTarget(TargetType.class).size();
   assertEquals(expected, actual);
   ```

2. **Run multiple times**: Results should be consistent

3. **Enable debug logging**:
   ```xml
   <logger name="hu.blackbelt.judo.zeta.transformation" level="DEBUG"/>
   ```

### If Still Seeing Duplicates
- Check for multiple rules producing same target type without `@Primary`
- Verify guards aren't returning different values for same input
- Look for shared mutable state in transformation rules
- **Check for direct factory usage** - bypasses staging and cache

## Direct Factory Usage Issues (Critical)

### Symptom: NPE - preparedResult is null
```
Cannot invoke "org.eclipse.emf.ecore.InternalEObject.eDirectResource()"
because "this.preparedResult" is null
```

### Cause
Direct EMF factory usage bypasses Zeta's thread-safe staging:
```java
// UNSAFE: Direct factory - bypasses staging
RdbmsTable table = rdbmsFactory.createRdbmsTable();
table.setName(source.getName());
rdbmsModel.getRdbmsTables().add(table);  // Race condition!
```

### Fix: Use ctx.createTarget() or ctx.create()
```java
// SAFE: Uses Zeta staging mechanism
RdbmsTable table = ctx.createTarget(RdbmsTable.class);
table.setName(source.getName());
// No manual Resource.add() needed - staging handles it

// For contained elements (not added to Resource root):
Column column = ctx.create(Column.class);  // No staging, manual containment
table.getColumns().add(column);            // Safe - own element
```

## Duplicate Key Errors

### Symptom
```
IllegalStateException: Duplicate key rackinspect.entities.DimensionTemplateType
(attempted merging values Optional[EEnumImpl@36064c3e...] and Optional[EEnumImpl@7fc4b4e5...])
```

### Cause
Multiple threads creating equivalent targets before cache is updated:
```java
// Thread 1 and Thread 2 both call this concurrently for same source
EEnum target = ctx.equivalent(source, EEnum.class);
// Without atomic getOrCreate(), both may create new targets
```

### Fix
Zeta's `getOrCreate()` handles this automatically. Ensure:
1. Using latest Zeta version with atomic cache operations
2. All creation goes through `ctx.createTarget()` or `ctx.equivalent()`
3. No direct factory calls that bypass the cache

## NPE in Post-Processing

### Symptom
```
NullPointerException after transformation completes
when accessing model elements via eResource() or eDirectResource()
```

### Cause
Elements not fully committed before post-processing:
- Staged elements not yet in Resource
- XMI IDs not yet applied
- Containment not established

### Fix
```java
// Ensure transform() completes fully before post-processing
TransformationResult result = executor.transform();

// Apply pending XMI IDs after containment operations
ctx.applyAllPendingXmiIds();

// Verify elements are attached
for (EObject element : targetResource.getContents()) {
    assertNotNull(element.eResource());
}
```

## Migration from Direct EMF to Zeta Patterns

### Common Anti-Patterns to Fix

| Anti-Pattern | Thread-Safe Alternative |
|--------------|------------------------|
| `factory.createXxx()` | `ctx.createTarget(Xxx.class)` |
| `factory.createXxx()` (contained) | `ctx.create(Xxx.class)` |
| `resource.getContents().add(e)` | Return from rule (auto-staged) |
| `parent.getChildren().add(child)` | Safe if parent is your own element |
| Manual cache/trace management | Use `ctx.equivalent()` |
| `element.eSet(feature, value)` | `element.setXxx(value)` (safe on own element) |

### Search Patterns for Unsafe Code
```bash
# Find direct factory usage (high priority)
grep -rn "Factory().create" --include="*.java"
grep -rn "Factory.eINSTANCE.create" --include="*.java"

# Find direct Resource modification
grep -rn "\.getContents().add" --include="*.java"
grep -rn "\.getResource().getContents()" --include="*.java"

# Find potential shared object modification
grep -rn "ctx.equivalent.*\.get.*\.add" --include="*.java"
```

### Safe vs Unsafe Patterns

```java
// SAFE: Modifying your own created element
Table table = ctx.createTarget(Table.class);
table.setName("MyTable");              // Safe - own element
table.getColumns().add(column);        // Safe - own element's list

// UNSAFE: Modifying element from ctx.equivalent()
Table existingTable = ctx.equivalent(source, Table.class);
existingTable.getColumns().add(column); // UNSAFE in parallel!
// Another thread may be iterating this list

// SAFE ALTERNATIVE: Create association during creation
@TransformRule(name = "Column2RdbmsColumn")
public TransformFunction<Column, RdbmsColumn> column2RdbmsColumn() {
    return (source, ctx) -> {
        RdbmsColumn col = ctx.createTarget(RdbmsColumn.class);
        // Set reference during creation, not later
        RdbmsTable table = ctx.equivalent(source.getOwner(), RdbmsTable.class);
        col.setTable(table);  // Safe - setting ref on own element
        return col;
    };
}
```

## Inheritance Issues

### executeParentRule() Returns Null
```java
// Parent rule must be registered
// Check parent rule name matches exactly
ctx.executeParentRule("BaseTransform", source);  // Case-sensitive
```

### Parent Executes Multiple Times
```java
// executeParentRule() is idempotent
// Same result returned on repeated calls - this is expected
```

## Memory Issues

### Clear Caches
```java
// In @PostExecution hook
ctx.clearExtensionCache();
ctx.getElementResolutionCache().clear();
```

### Reduce Parallel Chunks
```java
TransformationExecutor.builder()
    .chunkSize(50)  // Smaller chunks, less memory
    .build();
```

## Debugging Tips

### Enable Trace Logging
```xml
<logger name="hu.blackbelt.judo.zeta.transformation" level="DEBUG"/>
```

### Export Transformation Trace
```java
TransformationResult result = executor.transform();
TransformationTrace trace = result.getTrace();

// Export to JSON
String json = trace.toJson();
```

### Add Execution Counter
```java
private AtomicInteger executionCount = new AtomicInteger();

@TransformRule(name = "MyRule")
public TransformFunction<A, B> myRule() {
    return (source, ctx) -> {
        int count = executionCount.incrementAndGet();
        log.debug("Execution #{}: {}", count, source);
        // ...
    };
}
```

## Common Exceptions

### TransformationException
```java
try {
    executor.transform();
} catch (TransformationException e) {
    // Get detailed failure context
    EObject element = e.getFailedElement();  // Source that caused failure
    String rule = e.getRuleName();           // Which rule failed
    Throwable cause = e.getCause();          // Original exception

    log.error("Rule '{}' failed on element {}: {}",
        rule, element, cause.getMessage());

    // Debug: print full stack trace
    cause.printStackTrace();
}
```

**Available Methods**:
| Method | Description |
|--------|-------------|
| `getFailedElement()` | Source element being transformed |
| `getRuleName()` | Name of the failed rule |
| `getCause()` | Original exception |
| `getMessage()` | Formatted error message |

### Guard Method Not Found
```java
// Guard method must be in same class
// Must have correct signature:
// boolean name(SourceType, TransformationContext)
```

### Circular Reference
```java
// A.equivalent() → B.equivalent() → A.equivalent()
// Use @Lazy to break cycles
// Or restructure to avoid mutual dependencies
```

## Performance Issues

### Symptom: Slow Transformation

Use `TransformationMetrics` to identify bottlenecks:

```java
// Enable before transformation
TransformationMetrics.enable();

executor.transform();

// Print report
System.out.println(TransformationMetrics.getReport());

// Reset for next run
TransformationMetrics.disable();
TransformationMetrics.reset();
```

### Interpreting the Report

| Metric | Problem Indicator | Cause | Solution |
|--------|-------------------|-------|----------|
| Cache hit rate <80% | Low efficiency | Inconsistent equivalent() params | Standardize lookups |
| High XMI ID scans | O(n) bottleneck | Deferred ID resolution | Ensure indexed lookups |
| High ms/call for rule | Slow rule | Expensive computation | Use @Cached or optimize |
| High rule iterations | Rule matching overhead | Many rules checked | Consolidate rules |
| High lock wait time | Lock contention | Thread blocking | Review atomic operations |

### Common Bottlenecks

**1. O(n) XMI ID lookups**
```java
// Problem: Linear scan for each lookup
ctx.findByXmiId("some-id");  // O(n) if not indexed

// Solution: Use indexed lookups (automatic in latest version)
```

**2. Expensive guard evaluations**
```java
// Problem: Guard does expensive computation
@Guard(method = "expensiveGuard")

private boolean expensiveGuard(Entity e, TransformationContext ctx) {
    // O(n) operation on every call
    return ctx.getAllSource(Entity.class).stream()...
}

// Solution: Cache in @PreExecution
@PreExecution
public void cacheData(TransformationContext ctx) {
    ctx.setAttribute("entitySet", computeOnce());
}
```

**3. Repeated equivalent() with same params**
```java
// Problem: Call equivalent() in loop
for (Item item : items) {
    Table t = ctx.equivalent(entity, Table.class);  // Same every time
    t.getColumns().add(...);
}

// Solution: Cache outside loop
Table t = ctx.equivalent(entity, Table.class);
for (Item item : items) {
    t.getColumns().add(...);
}
```

### Built-in Optimizations

Zeta automatically applies these optimizations:

| Optimization | Default | Impact |
|--------------|---------|--------|
| Skip XMI resource lookup | `true` | **-91%** greedy rule time for fresh transformations |
| Pending XMI ID index | enabled | O(1) vs O(n) for findByXmiId() |
| Atomic cache operations | enabled | Thread-safe, no duplicates |

These optimizations achieved **78% faster** overall transformation (2.5x faster than ETL).
