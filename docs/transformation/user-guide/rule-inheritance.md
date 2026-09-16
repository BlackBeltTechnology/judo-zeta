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

Two overloads are available:

```java
// Basic - parent creates target
<T extends EObject> T executeParentRule(String parentRuleName, EObject source);

// With pre-created target - child creates target, parent modifies it
<T extends EObject> T executeParentRule(String parentRuleName, EObject source, T target);
```

### Pattern 1: Automatic Inheritance (Concrete Types)

When the child rule has a concrete target type, the framework automatically handles inheritance:

```java
@TransformRule(name = "EntityType2Table")
@Extends("NamedElement2NamedType")  // Framework executes parent automatically
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // createTarget() returns the pre-created target shared with parent
        Table table = ctx.createTarget(Table.class);
        table.setAbstract(entity.isAbstract());
        return table;
    };
}
```

### Pattern 2: Manual Inheritance (Abstract Parent Types)

When the parent rule has an abstract target type (e.g., `NamedElement`), use the manual pattern:

```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Child creates the CONCRETE target first
        Table table = ctx.createTarget(Table.class);

        // Pass target to parent - parent's createTarget() returns this same instance
        ctx.executeParentRule("NamedElement2NamedType", entity, table);

        // Add entity-specific transformations
        table.setAbstract(entity.isAbstract());
        return table;
    };
}

@TransformRule(name = "NamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedElement> namedElement2NamedType() {
    return (source, ctx) -> {
        // createTarget() returns the pre-created target passed by child
        NamedElement target = ctx.createTarget(NamedElement.class);
        target.setName(source.getName());
        return target;
    };
}
```

### When to Use Each Pattern

| Pattern | Use When |
|---------|----------|
| Automatic (`@Extends`) | Child's target type is concrete (can be instantiated) |
| Manual (`executeParentRule(name, source, target)`) | Parent's target type is abstract, or you need explicit control |

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

## Inheritance State Isolation

When a transform function calls `equivalent()` to look up related elements, the nested transformation starts with a **fresh inheritance state**. This ensures that:

- Nested transformations create their **own targets**, not reuse the caller's pre-created target
- Properties set by the nested rule don't overwrite the caller's properties
- Each `equivalent()` call is independent, regardless of the caller's inheritance context

### Example: Bidirectional Reference Lookup

```java
@TransformRule(name = "AssociationEnd")
@Extends("BaseAssociationEnd")
public TransformFunction<EReference, AssociationEnd> associationEnd() {
    return (ref, ctx) -> {
        // This rule has a pre-created target from @Extends
        AssociationEnd end = ctx.createTarget(AssociationEnd.class);
        end.setName(ref.getName());

        // Look up the bidirectional partner via equivalent()
        // IMPORTANT: This nested transformation is ISOLATED
        // It will NOT reuse our pre-created target
        EReference opposite = ref.getEOpposite();
        if (opposite != null) {
            AssociationEnd partner = ctx.equivalent(opposite, AssociationEnd.class);
            end.setPartner(partner);  // partner is a DIFFERENT object
        }

        return end;
    };
}
```

### How It Works

1. **Parent rules in @Extends chain**: Share the pre-created target (as expected)
2. **equivalent() calls**: Always start fresh, creating independent targets
3. **State restoration**: After the nested transformation completes, the caller's inheritance state is restored

This behavior matches Epsilon ETL semantics where each `equivalent()` call produces independent results.

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
