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
// Wraps rule execution errors
// Contains: source element, rule name, root cause
catch (TransformationException e) {
    log.error("Rule {} failed on {}: {}", 
        e.getRuleName(), e.getSource(), e.getCause());
}
```

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
