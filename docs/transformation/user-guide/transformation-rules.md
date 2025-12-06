# Writing Transformation Rules

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Transformation Rules

This guide covers how to write effective transformation rules using the Judo Zeta Transformation Framework.

## Basic Rule Structure

Every transformation rule follows this pattern:

```java
@TransformRule(name = "UniqueRuleName")
public TransformFunction<SourceType, TargetType> methodName() {
    return (source, ctx) -> {
        // 1. Create target element
        TargetType target = ctx.createTarget(TargetType.class);
        
        // 2. Map properties from source to target
        target.setName(source.getName());
        
        // 3. Handle related elements
        // ...
        
        // 4. Return target
        return target;
    };
}
```

## TransformFunction Return Types

The `TransformFunction<S, T>` interface is parameterized:

- **S** - Source element type (extends EObject)
- **T** - Target element type (extends EObject)

```java
// Single target element
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() { ... }

// Void return (for rules that create side effects only)
@TransformRule(name = "ProcessRelations")
public TransformFunction<EntityType, Void> processRelations() {
    return (entity, ctx) -> {
        // Process but don't return a target
        for (Relation rel : entity.getRelations()) {
            ctx.equivalent(rel, ForeignKey.class);
        }
        return null;
    };
}
```

## Creating Target Elements

Use `ctx.createTarget()` to create new elements in the target model:

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        // Creates element and adds to target ResourceSet
        Table table = ctx.createTarget(Table.class);
        
        // Set properties
        table.setName(entity.getName());
        table.setSchema(entity.getNamespace().getName());
        
        return table;
    };
}
```

**Important**: `createTarget()` requires the target EPackage to be set on the context:

```java
context.setTargetPackage(TargetPackage.eINSTANCE);
```

## Mapping Properties

### Simple Property Mapping

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Direct mapping
        table.setName(entity.getName());
        table.setDescription(entity.getDescription());
        table.setAbstract(entity.isAbstract());
        
        return table;
    };
}
```

### Property Transformation

```java
@TransformRule(name = "Attribute2Column")
public TransformFunction<Attribute, Column> attribute2Column() {
    return (attr, ctx) -> {
        Column column = ctx.createTarget(Column.class);
        
        // Transform property values
        column.setName(toSnakeCase(attr.getName()));
        column.setType(mapDataType(attr.getType()));
        column.setNullable(!attr.isRequired());
        column.setLength(calculateLength(attr));
        
        return column;
    };
}

private String toSnakeCase(String camelCase) {
    return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
}

private String mapDataType(DataType type) {
    return switch (type.getName()) {
        case "String" -> "VARCHAR(255)";
        case "Integer" -> "INT";
        case "Long" -> "BIGINT";
        case "Boolean" -> "BOOLEAN";
        case "Date" -> "DATE";
        case "Timestamp" -> "TIMESTAMP";
        default -> "TEXT";
    };
}
```

### Handling Optional Properties

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Handle optional reference
        if (entity.getSuperType() != null) {
            Table parentTable = ctx.equivalent(entity.getSuperType(), Table.class);
            table.setParentTable(parentTable);
        }
        
        // Handle optional value with default
        String description = entity.getDescription();
        table.setDescription(description != null ? description : "No description");
        
        return table;
    };
}
```

## Transforming Related Elements

### One-to-One Relationships

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Transform single related element
        if (entity.getPrimaryKey() != null) {
            PrimaryKey pk = ctx.equivalent(entity.getPrimaryKey(), PrimaryKey.class);
            table.setPrimaryKey(pk);
        }
        
        return table;
    };
}
```

### One-to-Many Relationships

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Transform collection of related elements
        for (Attribute attr : entity.getAttributes()) {
            Column column = ctx.equivalent(attr, Column.class);
            table.getColumns().add(column);
        }
        
        // Or using streams
        entity.getOperations().stream()
            .map(op -> ctx.equivalent(op, Procedure.class))
            .forEach(proc -> table.getProcedures().add(proc));
        
        return table;
    };
}
```

### Filtering Related Elements

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Only transform persistent attributes
        entity.getAttributes().stream()
            .filter(Attribute::isPersistent)
            .map(attr -> ctx.equivalent(attr, Column.class))
            .forEach(col -> table.getColumns().add(col));
        
        return table;
    };
}
```

## Handling Null Values

Always handle potential null values safely:

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Safe null handling
        table.setName(Objects.requireNonNullElse(entity.getName(), "unnamed"));
        
        // Optional chaining
        Optional.ofNullable(entity.getSuperType())
            .map(superType -> ctx.equivalent(superType, Table.class))
            .ifPresent(table::setParentTable);
        
        // Guard against null collections
        if (entity.getAttributes() != null) {
            for (Attribute attr : entity.getAttributes()) {
                table.getColumns().add(ctx.equivalent(attr, Column.class));
            }
        }
        
        return table;
    };
}
```

## Error Handling in Rules

### Validation Within Rules

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        // Validate required properties
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new TransformationException(
                "Entity must have a name: " + entity.eClass().getName()
            );
        }
        
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        return table;
    };
}
```

### Logging and Diagnostics

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TransformationContext(source = EntityType.class, target = Table.class)
public class Entity2TableTransformations {
    
    private static final Logger log = LoggerFactory.getLogger(Entity2TableTransformations.class);
    
    @TransformRule(name = "Entity2Table")
    public TransformFunction<EntityType, Table> entity2Table() {
        return (entity, ctx) -> {
            log.debug("Transforming entity: {}", entity.getName());
            
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            
            int attrCount = entity.getAttributes().size();
            log.debug("Processing {} attributes for {}", attrCount, entity.getName());
            
            for (Attribute attr : entity.getAttributes()) {
                try {
                    Column column = ctx.equivalent(attr, Column.class);
                    table.getColumns().add(column);
                } catch (Exception e) {
                    log.warn("Failed to transform attribute {}: {}", 
                        attr.getName(), e.getMessage());
                }
            }
            
            log.info("Created table {} with {} columns", 
                table.getName(), table.getColumns().size());
            
            return table;
        };
    }
}
```

## Accessing the Source Model

Query the source model for additional context:

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Get all entities in source model
        Collection<EntityType> allEntities = ctx.getAllSource(EntityType.class);
        
        // Find related entities
        List<EntityType> referencingEntities = allEntities.stream()
            .filter(e -> e.getReferences().stream()
                .anyMatch(ref -> ref.getTarget().equals(entity)))
            .toList();
        
        // Use the information
        table.setReferencedBy(referencingEntities.size());
        
        return table;
    };
}
```

## Accessing Custom Attributes

Pass data between rules using attributes:

```java
@PreExecution
public void setupStatistics(TransformationContext ctx) {
    ctx.setAttribute("entityCount", 0);
    ctx.setAttribute("statistics", new HashMap<String, Integer>());
}

@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Update statistics
        int count = (int) ctx.getAttribute("entityCount");
        ctx.setAttribute("entityCount", count + 1);
        
        Map<String, Integer> stats = (Map<String, Integer>) ctx.getAttribute("statistics");
        stats.merge(entity.getNamespace().getName(), 1, Integer::sum);
        
        return table;
    };
}

@PostExecution
public void printStatistics(TransformationContext ctx) {
    int total = (int) ctx.getAttribute("entityCount");
    Map<String, Integer> stats = (Map<String, Integer>) ctx.getAttribute("statistics");
    
    System.out.println("Transformed " + total + " entities");
    stats.forEach((ns, count) -> System.out.println("  " + ns + ": " + count));
}
```

## Best Practices

### Keep Rules Focused

```java
// Good: One responsibility per rule
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() { ... }

@TransformRule(name = "Attribute2Column")
public TransformFunction<Attribute, Column> attribute2Column() { ... }

// Bad: Too much in one rule
@TransformRule(name = "EntityAndEverything2Table")
public TransformFunction<EntityType, Table> everything() {
    // Transforms entity, attributes, operations, references all in one
}
```

### Use Helper Methods

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class Entity2TableTransformations {
    
    @TransformRule(name = "Entity2Table")
    public TransformFunction<EntityType, Table> entity2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            
            setBasicProperties(entity, table);
            transformAttributes(entity, table, ctx);
            transformReferences(entity, table, ctx);
            
            return table;
        };
    }
    
    private void setBasicProperties(EntityType entity, Table table) {
        table.setName(entity.getName());
        table.setDescription(entity.getDescription());
        table.setAbstract(entity.isAbstract());
    }
    
    private void transformAttributes(EntityType entity, Table table, TransformationContext ctx) {
        for (Attribute attr : entity.getAttributes()) {
            Column column = ctx.equivalent(attr, Column.class);
            table.getColumns().add(column);
        }
    }
    
    private void transformReferences(EntityType entity, Table table, TransformationContext ctx) {
        for (Reference ref : entity.getReferences()) {
            ForeignKey fk = ctx.equivalent(ref, ForeignKey.class);
            table.getForeignKeys().add(fk);
        }
    }
}
```

---

**Previous**: [Core Concepts](core-concepts.md) | **Next**: [Element Resolution](element-resolution.md)
