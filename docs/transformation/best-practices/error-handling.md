# Error Handling

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Error Handling

Handle errors gracefully in transformation rules.

## Handling Missing Source Elements

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Safe null handling
        if (entity.getSuperType() != null) {
            Table parent = ctx.equivalent(entity.getSuperType(), Table.class);
            table.setParentTable(parent);
        }
        
        // Handle potentially empty collections
        for (Attribute attr : Objects.requireNonNullElse(entity.getAttributes(), List.of())) {
            table.getColumns().add(ctx.equivalent(attr, Column.class));
        }
        
        return table;
    };
}
```

## Dealing with Unresolved Proxies

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        // Check for unresolved EMF proxy
        if (entity.eIsProxy()) {
            log.warn("Skipping unresolved proxy: {}", EcoreUtil.getURI(entity));
            return null;  // Skip this element
        }
        
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        return table;
    };
}
```

## Logging and Diagnostics

```java
private static final Logger log = LoggerFactory.getLogger(EntityTransformations.class);

@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        log.debug("Transforming entity: {}", entity.getName());
        
        try {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            
            log.trace("Created table {} for entity {}", table.getName(), entity.getName());
            return table;
            
        } catch (Exception e) {
            log.error("Failed to transform entity {}: {}", entity.getName(), e.getMessage(), e);
            throw new TransformationException("Entity transformation failed: " + entity.getName(), e);
        }
    };
}
```

## Transformation Trace for Debugging

```java
TransformationResult result = executor.transform(elements);
TransformationTrace trace = result.getTrace();

// Export trace for analysis
trace.saveToJson(new File("debug-trace.json"));

// Find specific entries
trace.getEntries().stream()
    .filter(e -> e.getRuleName().equals("Entity2Table"))
    .forEach(e -> log.info("Transformed: {} -> {}", 
        e.getSource().getName(), e.getTarget().getName()));
```

## Validation in Rules

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        // Validate input
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new TransformationException("Entity name is required");
        }
        
        if (entity.getAttributes().isEmpty() && !entity.isAbstract()) {
            log.warn("Concrete entity {} has no attributes", entity.getName());
        }
        
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        return table;
    };
}
```

## Post-Transformation Validation

```java
@PostExecution
public void validateResults(TransformationContext ctx) {
    Collection<Table> tables = ctx.getAllTarget(Table.class);
    
    List<String> errors = new ArrayList<>();
    
    for (Table table : tables) {
        if (table.getColumns().isEmpty() && !table.isAbstract()) {
            errors.add("Table " + table.getName() + " has no columns");
        }
        if (table.getPrimaryKey() == null) {
            errors.add("Table " + table.getName() + " has no primary key");
        }
    }
    
    if (!errors.isEmpty()) {
        log.warn("Transformation completed with {} warnings:\n{}", 
            errors.size(), String.join("\n", errors));
    }
}
```

---

**Previous**: [Performance](performance.md) | **Next**: [Testing](testing.md)
