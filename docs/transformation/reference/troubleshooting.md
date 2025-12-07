# Troubleshooting Guide

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > Troubleshooting

Common issues and solutions for the transformation framework.

## 1. Rule Not Found

**Error**: `TransformationException: No rule found for source type: EntityType`

**Causes**:
- Class not registered with registry
- Missing `@TransformationContext` annotation
- Missing `@TransformRule` annotation

**Solution**:
```java
// Ensure class is registered
registry.register(EntityTypeTransformations.class);

// Ensure annotations are present
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() { ... }
}
```

## 2. equivalent() Returns Null

**Symptom**: `ctx.equivalent(source, Target.class)` returns null unexpectedly

**Causes**:
- Source element is null
- No rule exists for the source type
- Rule is `@Lazy` and hasn't been triggered
- Guard condition failed

**Solution**:
```java
// Check for null
if (source != null) {
    Target target = ctx.equivalent(source, Target.class);
}

// Ensure rule exists for type
@TransformRule(name = "Source2Target")
public TransformFunction<Source, Target> source2Target() { ... }
```

## 3. Circular Inheritance Detected

**Error**: `IllegalStateException: Circular dependency detected in rule inheritance`

**Cause**: Rules form a cycle via `@Extends`

**Solution**: Remove the cycle
```java
// Bad: Cycle
@Extends("RuleB") RuleA
@Extends("RuleC") RuleB
@Extends("RuleA") RuleC  // Creates cycle!

// Good: Acyclic
@Abstract BaseRule
@Extends("BaseRule") RuleA
@Extends("BaseRule") RuleB
```

## 4. Missing Parent Rule

**Error**: `IllegalStateException: Parent rule not found: ParentRuleName`

**Cause**: `@Extends` references non-existent rule

**Solution**: Ensure parent rule is registered
```java
// Register parent rule class first
registry.register(BaseTransformations.class);  // Contains "ParentRuleName"
registry.register(ChildTransformations.class);
```

## 5. Target Package Not Set

**Error**: `IllegalStateException: Target EPackage not set`

**Solution**:
```java
context.setTargetPackage(TargetPackage.eINSTANCE);
```

## 6. Guard Method Not Found

**Error**: `RuntimeException: Guard method not found: isAbstract`

**Causes**:
- Method name typo
- Method not in same class
- Wrong method signature

**Solution**:
```java
@TransformRule(name = "Rule")
@Guard(method = "isAbstract")  // Must match exactly
public TransformFunction<E, T> rule() { ... }

// Method must be in same class with correct signature
private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

## 7. ClassCastException in Rule

**Error**: `ClassCastException: cannot cast X to Y`

**Cause**: Source element doesn't match expected type

**Solution**:
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        // source is guaranteed to be EntityType
        EntityType entity = source;  // Safe, no cast needed
        ...
    };
}
```

## 8. Concurrent Modification in Parallel

**Error**: `ConcurrentModificationException`

**Cause**: Modifying shared state during parallel execution

**Solution**: Use thread-safe collections
```java
@PreExecution
public void setup(TransformationContext ctx) {
    // Use concurrent collection
    ctx.setAttribute("stats", new ConcurrentHashMap<>());
}
```

## 9. Out of Memory for Large Models

**Symptom**: `OutOfMemoryError` with large models

**Solutions**:
1. Increase heap: `-Xmx4g`
2. Use lazy rules for optional transformations
3. Process in batches

## 10. Transformation Takes Too Long

**Symptom**: Slow transformation performance

**Solutions**:
1. Enable parallel execution: `new TransformationExecutor(registry, context, true)`
2. Use `@Cached` for expensive extension methods
3. Use `@Lazy` for conditionally-needed rules
4. Avoid `getAllSource()` in rule bodies - use `@PreExecution`

## Debug Logging

Enable debug logging for troubleshooting:

```xml
<!-- logback.xml -->
<logger name="hu.blackbelt.judo.zeta.transformation" level="DEBUG"/>
```

## Export Trace for Analysis

```java
TransformationResult result = executor.transform(elements);
result.getTrace().saveToJson(new File("debug-trace.json"));
```

---

**Previous**: [TransformationTrace](transformation-trace.md)
