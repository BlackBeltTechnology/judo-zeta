# Extension Methods

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Extension Methods

Extension methods provide reusable helper functions that can be shared across transformation rules.

## @ExtensionMethod Annotation

```java
@ExtensionMethod(elementType = EntityType.class)
public class EntityTypeExtensions {
    
    public String getQualifiedName(EntityType entity) {
        if (entity.getNamespace() != null) {
            return entity.getNamespace().getName() + "::" + entity.getName();
        }
        return entity.getName();
    }
    
    public List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> result = new ArrayList<>(entity.getAttributes());
        if (entity.getSuperType() != null) {
            result.addAll(getAllAttributes(entity.getSuperType()));
        }
        return result;
    }
}
```

## @Cached Annotation

Cache expensive computations:

```java
@ExtensionMethod(elementType = EntityType.class)
public class EntityTypeExtensions {
    
    @Cached  // Result cached per entity instance
    public List<EntityType> getAllDescendants(EntityType entity, TransformationContext ctx) {
        List<EntityType> descendants = new ArrayList<>();
        Collection<EntityType> allEntities = ctx.getAllSource(EntityType.class);
        
        for (EntityType e : allEntities) {
            if (isDescendantOf(e, entity)) {
                descendants.add(e);
            }
        }
        return descendants;
    }
    
    private boolean isDescendantOf(EntityType entity, EntityType ancestor) {
        EntityType current = entity.getSuperType();
        while (current != null) {
            if (current.equals(ancestor)) return true;
            current = current.getSuperType();
        }
        return false;
    }
}
```

## Registration

```java
ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
extensionRegistry.register(EntityTypeExtensions.class);
extensionRegistry.register(NamespaceExtensions.class);

TransformationContext context = new TransformationContext(
    modelProvider,
    sourceResourceSet,
    targetResourceSet,
    extensionRegistry  // Pass to context
);
```

## Usage in Rules

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Call extension method
        String qualifiedName = ctx.call(entity, "getQualifiedName");
        table.setQualifiedName(qualifiedName);
        
        // Call cached extension method
        List<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
        for (Attribute attr : allAttrs) {
            table.getColumns().add(ctx.equivalent(attr, Column.class));
        }
        
        return table;
    };
}
```

## Cache Key Generation

For `@Cached` methods, cache key is based on:
- Target element (the element the method is called on)
- Method name
- Method parameters

```java
// Same entity, same method → cached result
List<EntityType> desc1 = ctx.call(entity, "getAllDescendants");
List<EntityType> desc2 = ctx.call(entity, "getAllDescendants");
// desc1 == desc2 (same cached list)
```

## Best Practices

### Stateless Extensions

```java
// Good: Stateless, pure function
public String getQualifiedName(EntityType entity) {
    return entity.getNamespace().getName() + "::" + entity.getName();
}

// Avoid: Stateful extensions
private int counter = 0;  // Don't do this
public int getNextId(EntityType entity) {
    return counter++;  // Non-deterministic
}
```

### Cache Expensive Operations

```java
@Cached  // Good: Expensive computation cached
public List<EntityType> getAllDescendants(EntityType entity, TransformationContext ctx) {
    // O(n) operation - worth caching
}

// No @Cached: Simple, fast operations don't need caching
public String getName(EntityType entity) {
    return entity.getName();  // Already O(1)
}
```

### Group by Element Type

```java
// EntityType extensions
@ExtensionMethod(elementType = EntityType.class)
public class EntityTypeExtensions { ... }

// Namespace extensions
@ExtensionMethod(elementType = Namespace.class)
public class NamespaceExtensions { ... }
```

---

**Previous**: [Lifecycle Hooks](lifecycle-hooks.md) | **Next**: [Best Practices](../best-practices/rule-naming.md)
