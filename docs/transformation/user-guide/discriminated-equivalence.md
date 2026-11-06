# Discriminated Equivalence

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Discriminated Equivalence

> **Note**: Discriminated equivalence is a **Zeta-specific extension**, not part of the original Epsilon ETL specification. It was designed as a workaround for scenarios where multiple target elements need to be created from a single source element, which ETL handles differently using multiple target declarations in a single rule.

Discriminated equivalence allows creating multiple distinct target elements from the same source element.

## equivalentDiscriminated()

```java
<T extends EObject> T equivalentDiscriminated(
    EObject source, 
    Class<T> targetClass, 
    String ruleName, 
    String discriminator
);
```

## Use Case: Multiple Outputs

When one source element should produce multiple related target elements:

```java
@TransformRule(name = "Relation2Operations")
public TransformFunction<Relation, Void> relation2Operations() {
    return (relation, ctx) -> {
        String targetName = relation.getTarget().getName();
        
        // Create operation
        Operation create = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "create"
        );
        create.setName("create" + targetName);
        create.setHttpMethod("POST");
        
        // Read operation
        Operation read = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "read"
        );
        read.setName("get" + targetName);
        read.setHttpMethod("GET");
        
        // Update operation  
        Operation update = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "update"
        );
        update.setName("update" + targetName);
        update.setHttpMethod("PUT");
        
        // Delete operation
        Operation delete = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "delete"
        );
        delete.setName("delete" + targetName);
        delete.setHttpMethod("DELETE");
        
        return null;
    };
}
```

## Structured XMI ID Generation

When `useStructuredIds` is enabled (default), discriminated elements get ETL-style structured XMI IDs:

```
Format: <source-name>/(<alias>/<source-id>)/<rule-name>/(discriminator/<discriminator-value>)

Example for relation "orders" in "esm" resource:
  Customer/(esm/_abc123)/RelationOperation/(discriminator/create)
  Customer/(esm/_abc123)/RelationOperation/(discriminator/read)
  Customer/(esm/_abc123)/RelationOperation/(discriminator/update)
  Customer/(esm/_abc123)/RelationOperation/(discriminator/delete)
```

The `<alias>` is the registered resource alias (e.g., "esm", "asm", "mapping", "source").

### XMI ID-based Lookup

When calling `equivalentDiscriminated()`, ZETA first looks up elements by their structured XMI ID:

```java
// First call creates element with ID: Customer/(esm/_abc123)/RelOp/(discriminator/create)
Operation create = ctx.equivalentDiscriminated(rel, Operation.class, "RelOp", "create");

// Second call finds existing element by XMI ID lookup
Operation sameCreate = ctx.equivalentDiscriminated(rel, Operation.class, "RelOp", "create");

assert create == sameCreate;  // Same instance found by XMI ID
```

This enables cross-phase element discovery and ensures elements created in earlier transformation phases are found correctly.

## Cache Structure

Each discriminator creates a separate cache entry:

```java
// Conceptual cache structure
cache[source][ruleName][discriminator] = target

// Example entries:
cache[relation]["RelationOperation"]["create"] = createOp
cache[relation]["RelationOperation"]["read"]   = readOp
cache[relation]["RelationOperation"]["update"] = updateOp
cache[relation]["RelationOperation"]["delete"] = deleteOp
```

## Retrieving Discriminated Elements

```java
// Later in another rule, retrieve specific discriminated element
@TransformRule(name = "EntityType2Controller")
public TransformFunction<EntityType, Controller> entityType2Controller() {
    return (entity, ctx) -> {
        Controller ctrl = ctx.createTarget(Controller.class);
        
        for (Relation rel : entity.getRelations()) {
            // Get specific discriminated operation
            Operation createOp = ctx.equivalentDiscriminated(
                rel, Operation.class, "RelationOperation", "create"
            );
            ctrl.getCreateOperations().add(createOp);
        }
        
        return ctrl;
    };
}
```

## vs Regular equivalent()

| Method | Use Case | Cache Key |
|--------|----------|-----------|
| `equivalent()` | One target per source | `source + ruleName` |
| `equivalentDiscriminated()` | Multiple targets per source | `source + ruleName + discriminator` |

```java
// equivalent() - one Column per Attribute
Column col = ctx.equivalent(attr, Column.class);

// equivalentDiscriminated() - multiple Operations per Relation
Operation create = ctx.equivalentDiscriminated(rel, Operation.class, "RelOp", "create");
Operation update = ctx.equivalentDiscriminated(rel, Operation.class, "RelOp", "update");
```

## Common Patterns

### CRUD Operations

```java
private void createCrudOperations(Relation rel, TransformationContext ctx) {
    for (String action : List.of("create", "read", "update", "delete")) {
        Operation op = ctx.equivalentDiscriminated(
            rel, Operation.class, "CrudOperation", action
        );
        op.setName(action + rel.getTarget().getName());
    }
}
```

### Bidirectional References

```java
// Forward and inverse navigation
Navigation forward = ctx.equivalentDiscriminated(
    rel, Navigation.class, "RelationNavigation", "forward"
);
Navigation inverse = ctx.equivalentDiscriminated(
    rel, Navigation.class, "RelationNavigation", "inverse"
);
```

---

**Previous**: [Greedy Matching](greedy-matching.md) | **Next**: [Lifecycle Hooks](lifecycle-hooks.md)
