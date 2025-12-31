# Advanced Transformation Patterns

## Rule Inheritance

### Abstract Parent Rule
```java
@TransformRule(name = "BaseEntity2Table")
@Abstract
public TransformFunction<EntityType, Table> baseEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(source.getName());
        // Common logic
        return table;
    };
}
```

### Child Rule with @Extends
```java
@TransformRule(name = "Entity2Table")
@Extends({"BaseEntity2Table"})
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        // Execute parent first (idempotent)
        Table table = ctx.executeParentRule("BaseEntity2Table", source);
        
        // Add child-specific logic
        table.setSchema("public");
        return table;
    };
}
```

### Multiple Inheritance
```java
@TransformRule(name = "FullEntity2Table")
@Extends({"BaseEntity2Table", "AuditableEntity2Table"})
public TransformFunction<EntityType, Table> fullEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.executeParentRule("BaseEntity2Table", source);
        ctx.executeParentRule("AuditableEntity2Table", source);
        // Combine results
        return table;
    };
}
```

**Key**: `executeParentRule()` is idempotent - same result on repeated calls.

## Multi-Source (Cartesian Product)

### Two Sources
```java
@TransformRule(name = "EntityMappingToTable")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public MultiSourceTransformFunction<Table> entityMappingToTable() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];  // Order matches @Transform order
        TypeMapping mapping = (TypeMapping) sources[1];
        
        Table table = ctx.createTarget(Table.class);
        table.setName(mapping.mapName(entity.getName()));
        return table;
    };
}
```

### Three Sources
```java
@TransformRule(name = "ThreeWayTransform")
@Transform(alias = "model", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@Transform(alias = "rules", type = ValidationRule.class)
public MultiSourceTransformFunction<Table> threeWayTransform() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        ValidationRule rule = (ValidationRule) sources[2];
        // ...
    };
}
```

### Multi-Source Guard
```java
@TransformRule(name = "GuardedMultiSource")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@Guard(method = "matchesMapping")
public MultiSourceTransformFunction<Table> guardedMultiSource() {
    return (sources, ctx) -> { /* ... */ };
}

// Guard receives array
private boolean matchesMapping(EObject[] sources, TransformationContext ctx) {
    EntityType entity = (EntityType) sources[0];
    TypeMapping mapping = (TypeMapping) sources[1];
    return mapping.appliesTo(entity);
}
```

## Lazy Rules

### Define Lazy Rule
```java
@TransformRule(name = "Attribute2Column")
@Lazy
public TransformFunction<Attribute, Column> attribute2Column() {
    return (source, ctx) -> {
        Column col = ctx.createTarget(Column.class);
        col.setName(source.getName());
        return col;
    };
}
```

### Trigger Lazy Execution
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        for (Attribute attr : source.getAttributes()) {
            // Triggers lazy rule if not cached
            Column col = ctx.equivalent(attr, Column.class);
            if (col != null) {
                table.getColumns().add(col);
            }
        }
        return table;
    };
}
```

**Use case**: Transform elements only when actually needed.

## Greedy Matching

### Default (Exact Type)
```java
@TransformRule(name = "EntityType2Table")
@Transform(type = EntityType.class)  // Matches ONLY EntityType
public TransformFunction<EntityType, Table> entityType2Table() { }
```

### Greedy (Type + Subtypes)
```java
@TransformRule(name = "NamedElement2Named")
@Transform(type = NamedElement.class)
@Greedy  // Matches NamedElement, EntityType, DataType, etc.
public TransformFunction<NamedElement, Named> namedElement2Named() { }
```

## Primary Rules

When multiple rules produce same target type:

```java
@TransformRule(name = "Entity2Table")
@Primary  // This result returned by equivalent()
public TransformFunction<EntityType, Table> entity2Table() { }

@TransformRule(name = "Entity2AuditTable")
public TransformFunction<EntityType, Table> entity2AuditTable() { }
```

```java
// Returns result from @Primary rule
Table table = ctx.equivalent(entity, Table.class);

// Returns all results
List<Table> tables = ctx.equivalents(entity, Table.class);
```

## Discriminated Equivalence

Multiple named outputs from single source:

```java
@TransformRule(name = "CrudOperations")
public TransformFunction<EntityType, Operation> crudOperations() {
    return (source, ctx) -> {
        ElementResolutionCache cache = ctx.getElementResolutionCache();
        
        for (String op : Arrays.asList("create", "read", "update", "delete")) {
            Operation operation = ctx.createTarget(Operation.class);
            operation.setName(op + source.getName());
            
            // Store with discriminator
            cache.addDiscriminatedMapping(source, operation, "CrudOperations", op);
        }
        
        return null;  // No single return value
    };
}
```

Retrieval:
```java
Operation create = ctx.equivalentDiscriminated(
    entity, Operation.class, "CrudOperations", "create");
Operation update = ctx.equivalentDiscriminated(
    entity, Operation.class, "CrudOperations", "update");
```

## Extension Methods

### Define Extension Class
```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached  // Cache per EntityType instance
    public List<Attribute> getAllInheritedAttributes(EntityType self) {
        List<Attribute> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        while (current != null) {
            result.addAll(current.getAttributes());
            current = current.getSuperType();
        }
        return result;
    }
}
```

### Use in Rule
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Call extension method
        List<Attribute> inherited = ctx.call(source, "getAllInheritedAttributes");
        for (Attribute attr : inherited) {
            // ...
        }
        return table;
    };
}
```

### Register Extensions
```java
ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
extensionRegistry.register(EntityTypeExtensions.class);

TransformationContext context = new TransformationContext(
    provider, sourceRS, targetRS, extensionRegistry);
```

## Lifecycle Hooks

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class MyTransform {
    
    private Map<String, Integer> stats;
    
    @PreExecution
    public void setup(TransformationContext ctx) {
        stats = new HashMap<>();
        ctx.setAttribute("startTime", System.currentTimeMillis());
    }
    
    @TransformRule(name = "Entity2Table")
    public TransformFunction<EntityType, Table> entity2Table() {
        return (source, ctx) -> {
            stats.merge("entities", 1, Integer::sum);
            // ...
        };
    }
    
    @PostExecution
    public void cleanup(TransformationContext ctx) {
        long duration = System.currentTimeMillis() - 
            (Long) ctx.getAttribute("startTime");
        log.info("Transformed {} entities in {}ms", 
            stats.get("entities"), duration);
    }
}
```
