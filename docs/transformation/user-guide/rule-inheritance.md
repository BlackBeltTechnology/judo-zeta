# Rule Inheritance

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Rule Inheritance

Rule inheritance enables code reuse by allowing rules to extend and build upon parent rules.

## @Abstract and @Extends

### Abstract Rules

Abstract rules provide reusable transformation logic but never execute directly:

```java
@TransformRule(name = "NamedElement2NamedType")
@Abstract  // Never executes during eager phase
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        return target;
    };
}
```

### Extending Rules

Child rules extend parent rules using `@Extends`:

```java
@TransformRule(name = "EntityType2Table")
@Extends("NamedElement2NamedType")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Execute parent rule first
        Table table = ctx.executeParentRule("NamedElement2NamedType", entity);
        
        // Add entity-specific transformations
        table.setAbstract(entity.isAbstract());
        table.setSchema(ctx.equivalent(entity.getNamespace(), Schema.class));
        
        return table;
    };
}
```

## executeParentRule()

```java
<T extends EObject> T executeParentRule(String parentRuleName, EObject source);
```

- Executes the parent rule and returns its result
- Child can then modify/extend the result
- Parent rule creates the target element

## Multi-Level Inheritance

```java
// Level 1: Base rule
@TransformRule(name = "NamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        return target;
    };
}

// Level 2: Intermediate rule
@TransformRule(name = "TypedElement2TypedType")
@Abstract
@Extends("NamedElement2NamedType")
public TransformFunction<TypedElement, TypedType> typedElement2TypedType() {
    return (source, ctx) -> {
        TypedType target = ctx.executeParentRule("NamedElement2NamedType", source);
        target.setType(mapType(source.getType()));
        return target;
    };
}

// Level 3: Concrete rule
@TransformRule(name = "Attribute2Column")
@Extends("TypedElement2TypedType")
public TransformFunction<Attribute, Column> attribute2Column() {
    return (attr, ctx) -> {
        Column column = ctx.executeParentRule("TypedElement2TypedType", attr);
        column.setNullable(!attr.isRequired());
        column.setDefaultValue(attr.getDefaultValue());
        return column;
    };
}
```

**Execution chain**: `Attribute2Column` → `TypedElement2TypedType` → `NamedElement2NamedType`

## Cycle Detection

The framework detects circular inheritance:

```java
// This will throw IllegalStateException during registration
@TransformRule(name = "RuleA")
@Extends("RuleC")
public TransformFunction<A, X> ruleA() { ... }

@TransformRule(name = "RuleB")
@Extends("RuleA")
public TransformFunction<B, Y> ruleB() { ... }

@TransformRule(name = "RuleC")
@Extends("RuleB")  // Cycle: C → B → A → C
public TransformFunction<C, Z> ruleC() { ... }
```

Error: `IllegalStateException: Circular dependency detected in rule inheritance: RuleA → RuleC → RuleB → RuleA`

## Inheritance Graph

```mermaid
graph TD
    A[NamedElement2NamedType<br/>@Abstract] --> B[TypedElement2TypedType<br/>@Abstract]
    A --> C[Entity2Table]
    B --> D[Attribute2Column]
    B --> E[Operation2Procedure]
    A --> F[Namespace2Schema]
```

Rules are topologically sorted to ensure parents execute before children.

## Best Practices

### Extract Common Logic

```java
// Good: Common name/description handling in abstract rule
@TransformRule(name = "BaseTransform")
@Abstract
public TransformFunction<NamedElement, NamedType> baseTransform() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setQualifiedName(buildQualifiedName(source));
        return target;
    };
}
```

### Keep Inheritance Shallow

```java
// Prefer: 2-3 levels max
Base → Entity2Table
Base → Attribute2Column

// Avoid: Deep hierarchies
Base → Level2 → Level3 → Level4 → ConcreteRule
```

---

**Previous**: [Lazy Evaluation](lazy-evaluation.md) | **Next**: [Greedy Matching](greedy-matching.md)
