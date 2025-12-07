# Annotations Reference

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > Annotations

Complete reference for all transformation annotations.

## @TransformationContext

Marks a class as containing transformation rules.

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations { ... }
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `source` | `Class<?>` | Yes | Primary source element type |
| `target` | `Class<?>` | Yes | Primary target element type |

## @TransformRule

Defines a transformation rule.

```java
@TransformRule(name = "EntityType2Table", description = "Transforms entity to table")
public TransformFunction<EntityType, Table> entityType2Table() { ... }
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `name` | `String` | Yes | - | Unique rule identifier |
| `description` | `String` | No | `""` | Human-readable description |

## @Lazy

Rule executes on-demand via `equivalent()` calls.

```java
@TransformRule(name = "Reference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() { ... }
```

No parameters.

## @Abstract

Rule only executes via `executeParentRule()`.

```java
@TransformRule(name = "BaseNamedElement")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement() { ... }
```

No parameters.

## @Primary

Rule's result takes precedence in `equivalent()`.

```java
@TransformRule(name = "Entity2MainTable")
@Primary
public TransformFunction<EntityType, Table> entity2MainTable() { ... }
```

No parameters.

## @Greedy

Matches source type AND all subtypes.

```java
@TransformRule(name = "NamedElement2NamedType")
@Greedy
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() { ... }
```

No parameters.

## @Extends

Inherits from parent rule(s).

```java
@TransformRule(name = "EntityType2Table")
@Extends("BaseNamedElement")
public TransformFunction<EntityType, Table> entityType2Table() { ... }
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `value` | `String` | Yes | Parent rule name |

## @Guard

Conditional execution based on guard method.

```java
@TransformRule(name = "AbstractEntity2Table")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2Table() { ... }

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | `String` | Yes | Guard method name |

**Guard method signatures**:
- `boolean methodName(EObject element)`
- `boolean methodName(EObject element, TransformationContext ctx)`

## @PreExecution

Method runs before transformation starts.

```java
@PreExecution
public void setup(TransformationContext ctx) { ... }
```

No parameters.

## @PostExecution

Method runs after transformation completes.

```java
@PostExecution
public void cleanup(TransformationContext ctx) { ... }
```

No parameters.

## Annotation Combinations

| Combination | Valid | Notes |
|-------------|-------|-------|
| @Lazy + @Guard | Yes | Guard evaluated when equivalent() called |
| @Lazy + @Primary | Yes | Primary among lazy rules |
| @Abstract + @Extends | Yes | Abstract rule can extend another |
| @Greedy + @Guard | Yes | Guard checked for each matched subtype |
| @Abstract + @Lazy | No | Abstract rules are never lazy |
| @Primary + @Abstract | No | Abstract rules don't produce direct results |

---

**Next**: [TransformationContext API](transformation-context.md)
