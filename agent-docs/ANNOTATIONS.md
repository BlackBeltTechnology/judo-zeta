# Zeta Annotations Reference

## Class-Level Annotations

### @TransformationContext
```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class MyTransform { }
```
- **source**: Default source type for rules without @Transform
- **target**: Default target type for rules without @To

### @ExtensionMethod
```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) { }
}
```
- Provides extension methods for EClass type
- Methods called via `ctx.call(entity, "getAllAttributes")`

## Method-Level Annotations

### @TransformRule (Required)
```java
@TransformRule(name = "Entity2Table", description = "Transforms entity to table")
```
| Attribute | Required | Description |
|-----------|----------|-------------|
| `name` | Yes | Unique rule identifier |
| `description` | No | Human-readable description |
| `sourceTypes` | No | Deprecated - use @Transform |
| `targetTypes` | No | Deprecated - use @To |

### @Transform (Repeatable)
```java
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
```
| Attribute | Default | Description |
|-----------|---------|-------------|
| `alias` | "source" | Resource alias |
| `type` | Required | Source element type |

Multiple @Transform = Cartesian product execution.

### @To (Repeatable)
```java
@To(alias = "rdbms", type = Table.class)
@To(alias = "rdbms", type = Index.class)
```
| Attribute | Default | Description |
|-----------|---------|-------------|
| `alias` | "target" | Resource alias |
| `type` | Required | Target element type |

### @Guard
```java
@Guard(method = "isNotAbstract")
```
| Attribute | Required | Description |
|-----------|----------|-------------|
| `method` | Yes | Guard method name in same class |

Guard method signatures:
```java
// Single-source
boolean guardName(SourceType source, TransformationContext ctx)

// Multi-source
boolean guardName(EObject[] sources, TransformationContext ctx)
```

### @Lazy
```java
@TransformRule(name = "LazyRule")
@Lazy
```
- Rule executes only when `ctx.equivalent()` is called
- Not executed during eager phase

### @Abstract
```java
@TransformRule(name = "BaseRule")
@Abstract
```
- Rule never executes directly
- Only via `ctx.executeParentRule("BaseRule", source)`

### @Extends
```java
@TransformRule(name = "ChildRule")
@Extends({"ParentRule1", "ParentRule2"})
```
- Declares parent rules
- Call `ctx.executeParentRule(name, source)` to execute parent

### @Primary
```java
@TransformRule(name = "PrimaryRule")
@Primary
```
- When multiple rules transform same source to same target type
- This rule's result is returned by `equivalent(source, TargetType.class)`

### @Greedy
```java
@TransformRule(name = "GreedyRule")
@Greedy
```
- Default: Exact type match only
- @Greedy: Match source type AND all subtypes

### @PreExecution / @PostExecution
```java
@PreExecution
public void setup(TransformationContext ctx) { }

@PostExecution  
public void cleanup(TransformationContext ctx) { }
```
- Lifecycle hooks
- Called before/after all rule execution

### @Cached (Extension Methods)
```java
@ExtensionMethod(EntityType.class)
public class Extensions {
    @Cached
    public List<Type> computeExpensive(EntityType self) { }
}
```
- Caches result per EObject (keyed by XMI ID)
- Clear via `ctx.clearExtensionCache()`

## Functional Interfaces

```java
// Single-source transformation
@FunctionalInterface
public interface TransformFunction<S extends EObject, T extends EObject> {
    T transform(S source, TransformationContext context);
}

// Multi-source transformation (Cartesian product)
@FunctionalInterface
public interface MultiSourceTransformFunction<T extends EObject> {
    T transform(EObject[] sources, TransformationContext context);
}

// Single-source guard
@FunctionalInterface
public interface TransformGuard {
    boolean evaluate(EObject source, TransformationContext context);
}

// Multi-source guard
@FunctionalInterface
public interface MultiSourceTransformGuard {
    boolean evaluate(EObject[] sources, TransformationContext context);
}
```
