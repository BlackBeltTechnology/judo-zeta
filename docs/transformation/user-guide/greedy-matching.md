# Greedy Type Matching

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Greedy Matching

Greedy matching allows a rule to match not just the declared source type, but also all of its subtypes.

## @Greedy Annotation

```java
@TransformRule(name = "NamedElement2NamedType")
@Greedy  // Matches NamedElement AND all subtypes
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        return target;
    };
}
```

## Type Hierarchy Matching

Without `@Greedy`:
```
Rule for NamedElement → Only matches exact NamedElement instances
```

With `@Greedy`:
```
Rule for NamedElement → Matches:
  - NamedElement
  - EntityType (extends NamedElement)
  - ActorType (extends NamedElement)
  - OperationType (extends NamedElement)
  - ... all other subtypes
```

## Use Cases

### 1. Common Property Transformation

```java
// Transform name for ALL named elements
@TransformRule(name = "NamedElement2NamedType")
@Greedy
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        return target;
    };
}
```

### 2. Catch-All Rules

```java
// Handle any element not matched by specific rules
@TransformRule(name = "EObject2GenericElement")
@Greedy
public TransformFunction<EObject, GenericElement> catchAll() {
    return (source, ctx) -> {
        GenericElement target = ctx.createTarget(GenericElement.class);
        target.setSourceType(source.eClass().getName());
        return target;
    };
}
```

## Rule Priority with Greedy

When multiple rules match:

1. **Exact type rules** take precedence over greedy rules
2. **More specific greedy rules** take precedence over less specific ones

```java
// Specific rule - higher priority for EntityType
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() { ... }

// Greedy rule - lower priority, used as fallback
@TransformRule(name = "NamedElement2NamedType")
@Greedy
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() { ... }
```

For an `EntityType` instance:
- `EntityType2Table` executes (exact match)
- `NamedElement2NamedType` is skipped (greedy, but less specific)

## Greedy with Other Annotations

```java
// Greedy + Lazy
@TransformRule(name = "Element2Target")
@Greedy
@Lazy
public TransformFunction<Element, Target> element2Target() { ... }

// Greedy + Guard
@TransformRule(name = "ValidElement2Target")
@Greedy
@Guard(method = "isValid")
public TransformFunction<Element, Target> validElement2Target() { ... }

// Greedy + Abstract (for inheritance)
@TransformRule(name = "BaseElement2BaseTarget")
@Greedy
@Abstract
public TransformFunction<Element, Target> baseElement2BaseTarget() { ... }
```

## Activity-Based Greedy (@ActivityBased)

By default, `@Greedy @Lazy` rules process **all** matching elements during the lazy phase. The `@ActivityBased` annotation changes this behavior to only process elements that were explicitly activated via `equivalent()` calls.

### Standard @Greedy @Lazy Behavior

```java
@TransformRule(name = "Type2Element")
@Greedy
@Lazy
public TransformFunction<Type, Element> type2Element() { ... }
// Processes ALL Type instances and subtypes during lazy phase
```

### Activity-Based Behavior

```java
@TransformRule(name = "Type2Element")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<Type, Element> type2Element() { ... }
// ONLY processes Type instances that were passed to ctx.equivalent()
```

### Activation Tracking

When `@ActivityBased` is used:

1. Element becomes "activated" when passed to `ctx.equivalent()`
2. During lazy phase, only activated elements are transformed
3. Orphan/unreferenced elements are skipped

```java
// In some eager rule
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);

        for (Attribute attr : entity.getAttributes()) {
            // This ACTIVATES each attribute
            Column col = ctx.equivalent(attr, Column.class);
            table.getColumns().add(col);
        }

        return table;
    };
}

// Activity-based rule - only processes activated attributes
@TransformRule(name = "Attribute2Column")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<Attribute, Column> attribute2Column() { ... }
```

### When to Use @ActivityBased

| Scenario | Use @ActivityBased? |
|----------|---------------------|
| Skip orphan elements | ✅ Yes |
| ETL compatibility | ✅ Yes |
| Process all matching | ❌ No (use standard @Greedy @Lazy) |

### ETL Compatibility Mode

Instead of adding `@ActivityBased` to individual rules, you can enable it globally:

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // All @Greedy @Lazy rules become activity-based
    .build();
```

This matches Epsilon ETL's implicit filtering behavior where lazy rules only process elements reachable from eager transformations.

## EMF Type Checking

Greedy matching uses EMF's `EClass.isSuperTypeOf()`:

```java
// Conceptual implementation
boolean matches = sourceEClass.isSuperTypeOf(element.eClass());
```

---

**Previous**: [Rule Inheritance](rule-inheritance.md) | **Next**: [Discriminated Equivalence](discriminated-equivalence.md)
