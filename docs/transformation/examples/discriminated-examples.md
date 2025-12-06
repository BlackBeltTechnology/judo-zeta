# Discriminated Equivalence Examples

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Discriminated Examples

Examples of creating multiple outputs from a single source using discriminators.

## CRUD Operations from Relation

```java
@TransformRule(name = "Relation2CRUDOperations")
public TransformFunction<Relation, Void> relation2CRUDOperations() {
    return (relation, ctx) -> {
        String targetName = relation.getTarget().getName();
        
        // Create operation
        Operation create = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationCRUD", "create"
        );
        create.setName("create" + targetName);
        create.setHttpMethod("POST");
        create.setPath("/" + targetName.toLowerCase());
        
        // Read operation
        Operation read = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationCRUD", "read"
        );
        read.setName("get" + targetName);
        read.setHttpMethod("GET");
        read.setPath("/" + targetName.toLowerCase() + "/{id}");
        
        // Update operation
        Operation update = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationCRUD", "update"
        );
        update.setName("update" + targetName);
        update.setHttpMethod("PUT");
        update.setPath("/" + targetName.toLowerCase() + "/{id}");
        
        // Delete operation
        Operation delete = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationCRUD", "delete"
        );
        delete.setName("delete" + targetName);
        delete.setHttpMethod("DELETE");
        delete.setPath("/" + targetName.toLowerCase() + "/{id}");
        
        // List operation
        Operation list = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationCRUD", "list"
        );
        list.setName("list" + targetName + "s");
        list.setHttpMethod("GET");
        list.setPath("/" + targetName.toLowerCase());
        
        return null;
    };
}
```

## Bidirectional Navigation

```java
@TransformRule(name = "Relation2BidirectionalNavigation")
public TransformFunction<Relation, Void> relation2BidirectionalNavigation() {
    return (relation, ctx) -> {
        // Forward navigation (owner -> target)
        Navigation forward = ctx.equivalentDiscriminated(
            relation, Navigation.class, "RelationNavigation", "forward"
        );
        forward.setName(relation.getName());
        forward.setSource(ctx.equivalent(relation.getOwner(), Table.class));
        forward.setTarget(ctx.equivalent(relation.getTarget(), Table.class));
        forward.setDirection(Direction.FORWARD);
        
        // Inverse navigation (target -> owner)
        Navigation inverse = ctx.equivalentDiscriminated(
            relation, Navigation.class, "RelationNavigation", "inverse"
        );
        inverse.setName(relation.getInverseName());
        inverse.setSource(ctx.equivalent(relation.getTarget(), Table.class));
        inverse.setTarget(ctx.equivalent(relation.getOwner(), Table.class));
        inverse.setDirection(Direction.INVERSE);
        
        return null;
    };
}
```

## Retrieving Discriminated Elements

```java
@TransformRule(name = "EntityType2Controller")
public TransformFunction<EntityType, Controller> entityType2Controller() {
    return (entity, ctx) -> {
        Controller ctrl = ctx.createTarget(Controller.class);
        ctrl.setName(entity.getName() + "Controller");
        
        for (Relation rel : entity.getRelations()) {
            // Retrieve specific CRUD operations by discriminator
            Operation create = ctx.equivalentDiscriminated(
                rel, Operation.class, "RelationCRUD", "create"
            );
            ctrl.getEndpoints().add(create);
            
            Operation read = ctx.equivalentDiscriminated(
                rel, Operation.class, "RelationCRUD", "read"
            );
            ctrl.getEndpoints().add(read);
        }
        
        return ctrl;
    };
}
```

## Dynamic Discriminators

```java
@TransformRule(name = "Operation2VersionedOperations")
public TransformFunction<Operation, Void> operation2VersionedOperations() {
    return (op, ctx) -> {
        for (String version : List.of("v1", "v2", "v3")) {
            Operation versioned = ctx.equivalentDiscriminated(
                op, Operation.class, "VersionedOperation", version
            );
            versioned.setName(op.getName());
            versioned.setPath("/" + version + "/" + op.getPath());
            versioned.setVersion(version);
        }
        return null;
    };
}
```

---

**Previous**: [Lazy Rule Examples](lazy-rule-examples.md) | **Next**: [Inheritance Examples](inheritance-examples.md)
