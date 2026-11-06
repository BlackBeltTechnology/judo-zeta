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

## 11. Nested equivalent() Returns Wrong Target

**Symptom**: When calling `equivalent()` from within a rule that uses `@Extends`, the returned target is the same object as the caller's target, causing property overwrites.

**Example of the bug pattern**:
```java
@TransformRule(name = "OrderItem")
@Extends("BaseElement")
public TransformFunction<EReference, OrderItem> orderItem() {
    return (ref, ctx) -> {
        OrderItem item = ctx.createTarget(OrderItem.class);
        item.setName("item_" + ref.getName());

        // BUG: If equivalent() incorrectly inherits caller's state,
        // 'product' might be the SAME object as 'item'!
        Product product = ctx.equivalent(ref.getEType(), Product.class);
        item.setProduct(product);  // Might be setting item.setProduct(item)!

        return item;
    };
}
```

**Cause**: This was a framework bug where inheritance state (pre-created target) was not properly isolated for nested `equivalent()` calls.

**Solution**: This issue was fixed in the framework. If you're experiencing this issue:
1. Update to the latest version of the transformation framework
2. Verify that nested `equivalent()` calls produce independent targets
3. See [Rule Inheritance - Inheritance State Isolation](../user-guide/rule-inheritance.md#inheritance-state-isolation) for details

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

## Known Limitations

The Zeta Transformation Framework has the following known limitations compared to Epsilon ETL:

### 1. EMF Proxy Objects Not Supported

**Description**: EMF proxy objects (unresolved cross-references) are not automatically resolved during element iteration. If your model contains unresolved proxies, they may not be correctly typed or may be skipped during transformation.

**Impact**: 
- `all(SomeType.class)` may not find elements that are still proxies
- `instanceof` checks may fail for unresolved proxy objects
- Type casting may throw unexpected `ClassCastException`

**Workaround**: Ensure all proxies are resolved before transformation:
```java
// Resolve all proxies in the resource set before transformation
EcoreUtil.resolveAll(resourceSet);
```

### 2. Cross-Resource References Not Supported

**Description**: The transformation framework assumes all source elements are in a single EMF Resource or properly connected ResourceSet. Elements spread across multiple disconnected resources may not be discovered by `all()` iteration.

**Impact**:
- Elements in external resources may be missed during iteration
- Cross-resource references may not resolve correctly
- `equivalent()` lookups may fail for elements in different resources

**Workaround**: Load all related resources into a single ResourceSet before transformation:
```java
// Ensure all resources are in the same ResourceSet
ResourceSet resourceSet = new ResourceSetImpl();
resourceSet.getResource(uri1, true);
resourceSet.getResource(uri2, true);
// All resources now share the same ResourceSet
```

### 3. Dynamic EMF Not Fully Supported

**Description**: The framework is optimized for generated EMF models (with Java interfaces). Dynamic EMF (models created at runtime without generated code) may have limited support.

**Impact**:
- Java `instanceof` checks won't work with dynamic EObjects
- Type-safe transformation rules require generated interfaces

**Workaround**: Use EClass-based type checking for dynamic models:
```java
// Instead of: element instanceof MyType
// Use: myTypeEClass.isInstance(element)
EClass myTypeEClass = (EClass) ePackage.getEClassifier("MyType");
if (myTypeEClass.isInstance(element)) {
    // Handle element
}
```

---

**Previous**: [TransformationTrace](transformation-trace.md)
