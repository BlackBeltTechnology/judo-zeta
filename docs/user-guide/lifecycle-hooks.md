# Lifecycle Hooks

**Navigation**: [Documentation Hub](../index.md) > [User Guide](core-concepts.md) > Lifecycle Hooks

This guide covers lifecycle hooks in the Judo Zeta Validation Framework, which allow you to execute code before and after validation runs.

## Overview

Lifecycle hooks provide extension points in the validation lifecycle. They enable setup, teardown, logging, metrics collection, and resource management around validation execution.

### Available Hooks

| Annotation | When Executed | Common Use Cases |
|------------|---------------|------------------|
| `@PreValidation` | Before any validation rules run | Setup, initialization, cache warming, logging start |
| `@PostValidation` | After all validation rules complete | Cleanup, teardown, metrics reporting, logging end |

## @PreValidation Hooks

Pre-validation hooks execute before any validation rules run. They receive the `ValidationContext` and can initialize shared state.

### Basic Usage

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @PreValidation
    public void setup(ValidationContext ctx) {
        // Initialize shared resources
        System.out.println("Starting validation...");
    }
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            // Validation logic here
        };
    }
}
```

### Signature Requirements

Pre-validation hooks must:
- Be annotated with `@PreValidation`
- Have `void` return type
- Accept a single `ValidationContext` parameter
- Be in a class annotated with `@ValidationContext`

```java
// ✓ Valid signature
@PreValidation
public void setup(ValidationContext ctx) {
    // Setup code
}

// ❌ Invalid - wrong return type
@PreValidation
public boolean setup(ValidationContext ctx) {
    return true;
}

// ❌ Invalid - missing parameter
@PreValidation
public void setup() {
    // Cannot access context
}

// ❌ Invalid - wrong parameter type
@PreValidation
public void setup(String config) {
    // Wrong parameter
}
```

## @PostValidation Hooks

Post-validation hooks execute after all validation rules complete. They are guaranteed to run even if validation fails or throws exceptions.

### Basic Usage

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @PostValidation
    public void cleanup(ValidationContext ctx) {
        // Clean up resources
        System.out.println("Validation complete");
    }
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            // Validation logic here
        };
    }
}
```

### Signature Requirements

Post-validation hooks have the same requirements as pre-validation hooks:

```java
// ✓ Valid signature
@PostValidation
public void cleanup(ValidationContext ctx) {
    // Cleanup code
}
```

## Hook Execution Order

Hooks execute in a predictable order during validation:

```
1. ValidationExecutor.validate() called
2. Clear satisfies cache
3. → Execute @PreValidation hooks (all validators)
4. Execute validation rules for all elements
5. Filter failed results
6. → Execute @PostValidation hooks (all validators)
7. Clear caches (satisfies, extensions)
8. Return results
```

### Multiple Hooks

When multiple validation classes are registered, all hooks execute:

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    @PreValidation
    public void entitySetup(ValidationContext ctx) {
        System.out.println("Entity setup");
    }
    
    @PostValidation
    public void entityCleanup(ValidationContext ctx) {
        System.out.println("Entity cleanup");
    }
}

@ValidationContext(Attribute.class)
public class AttributeValidations {
    @PreValidation
    public void attributeSetup(ValidationContext ctx) {
        System.out.println("Attribute setup");
    }
    
    @PostValidation
    public void attributeCleanup(ValidationContext ctx) {
        System.out.println("Attribute cleanup");
    }
}

// Execution order:
// 1. Entity setup
// 2. Attribute setup
// 3. ... validation rules execute ...
// 4. Entity cleanup
// 5. Attribute cleanup
```

### Exception Handling

Post-validation hooks execute in a `finally` block, ensuring cleanup occurs even if validation fails:

```java
@PostValidation
public void cleanup(ValidationContext ctx) {
    // This ALWAYS runs, even if validation throws an exception
    releaseResources();
}
```

Hook exceptions are logged but don't stop validation:

```java
@PreValidation
public void setup(ValidationContext ctx) {
    // If this throws, the error is logged
    // Validation continues with other hooks
    throw new RuntimeException("Setup failed");
}
```

## Accessing ValidationContext

Hooks receive the `ValidationContext`, providing access to the model and validation state.

### Available Operations

```java
@PreValidation
public void setup(ValidationContext ctx) {
    // Get all instances of a type
    List<EntityType> entities = ctx.getAllInstances(EntityType.class);
    
    // Cache expensive computations
    Map<String, EntityType> index = buildEntityIndex(entities);
    ctx.putCached(CacheKey.of("entity-index"), index);
    
    // Clear specific caches (usually not needed in pre-hooks)
    ctx.clearCaches();
}

@PostValidation
public void cleanup(ValidationContext ctx) {
    // Retrieve cached data
    Map<String, EntityType> index = 
        (Map<String, EntityType>) ctx.getCached(CacheKey.of("entity-index"));
    
    // Clear all caches (framework does this automatically)
    ctx.clearCaches();
}
```

## Common Use Cases

### Logging Validation Start/End

Track validation execution for debugging and monitoring:

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @PreValidation
    public void logStart(ValidationContext ctx) {
        long timestamp = System.currentTimeMillis();
        int entityCount = ctx.getAllInstances(EntityType.class).size();
        
        System.out.println(
            "Starting entity validation at " + timestamp + 
            " for " + entityCount + " entities"
        );
        
        // Store start time for duration calculation
        ctx.putCached(CacheKey.of("validation-start"), timestamp);
    }
    
    @PostValidation
    public void logEnd(ValidationContext ctx) {
        Long startTime = (Long) ctx.getCached(CacheKey.of("validation-start"));
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.println("Entity validation completed in " + duration + "ms");
    }
}
```

### Initializing Shared State

Build indexes or caches used by multiple validation rules:

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @PreValidation
    public void buildEntityIndex(ValidationContext ctx) {
        List<EntityType> entities = ctx.getAllInstances(EntityType.class);
        
        // Build name-to-entity index
        Map<String, EntityType> nameIndex = entities.stream()
            .collect(Collectors.toMap(
                EntityType::getName,
                e -> e,
                (e1, e2) -> e1 // Keep first on collision
            ));
        
        ctx.putCached(CacheKey.of("entity-name-index"), nameIndex);
    }
    
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Use pre-built index instead of scanning all entities
            Map<String, EntityType> index = 
                (Map<String, EntityType>) ctx.getCached(CacheKey.of("entity-name-index"));
            
            // Validation logic using the index
            return ValidationResult.pass();
        };
    }
}
```

### Collecting Statistics

Gather metrics about validation execution:

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    private static class ValidationStats {
        int totalEntities;
        long startTime;
        long endTime;
    }
    
    @PreValidation
    public void initStats(ValidationContext ctx) {
        ValidationStats stats = new ValidationStats();
        stats.totalEntities = ctx.getAllInstances(EntityType.class).size();
        stats.startTime = System.currentTimeMillis();
        
        ctx.putCached(CacheKey.of("validation-stats"), stats);
    }
    
    @PostValidation
    public void reportStats(ValidationContext ctx) {
        ValidationStats stats = 
            (ValidationStats) ctx.getCached(CacheKey.of("validation-stats"));
        stats.endTime = System.currentTimeMillis();
        
        long duration = stats.endTime - stats.startTime;
        System.out.println("Validated " + stats.totalEntities + 
                          " entities in " + duration + "ms");
    }
}
```

### Clearing Custom Caches

Release resources allocated during validation:

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @PreValidation
    public void warmCaches(ValidationContext ctx) {
        // Pre-compute expensive data structures
        GraphAnalyzer analyzer = new GraphAnalyzer(
            ctx.getAllInstances(EntityType.class)
        );
        ctx.putCached(CacheKey.of("graph-analyzer"), analyzer);
    }
    
    @PostValidation
    public void clearCaches(ValidationContext ctx) {
        // Clean up custom resources
        GraphAnalyzer analyzer = 
            (GraphAnalyzer) ctx.getCached(CacheKey.of("graph-analyzer"));
        
        if (analyzer != null) {
            analyzer.dispose();
        }
        
        // Framework automatically clears context caches
        // but you can manually clear custom caches if needed
    }
}
```

## Best Practices

### Keep Hooks Lightweight

Hooks should be fast and not perform expensive operations:

```java
// ✓ Good - lightweight logging
@PreValidation
public void logStart(ValidationContext ctx) {
    System.out.println("Starting validation");
}

// ❌ Bad - expensive computation in hook
@PreValidation
public void expensiveSetup(ValidationContext ctx) {
    // Don't do heavy computation here
    for (EntityType entity : ctx.getAllInstances(EntityType.class)) {
        for (EntityType other : ctx.getAllInstances(EntityType.class)) {
            // O(n²) operation blocks all validation
        }
    }
}
```

### Handle Errors Gracefully

Don't let hook failures break validation:

```java
@PreValidation
public void setup(ValidationContext ctx) {
    try {
        // Attempt optional setup
        initializeMetrics();
    } catch (Exception e) {
        // Log but don't fail validation
        System.err.println("Metrics initialization failed: " + e.getMessage());
    }
}
```

### Use Caching Wisely

Cache shared data but don't overuse memory:

```java
@PreValidation
public void buildIndices(ValidationContext ctx) {
    // Good - cache frequently accessed data
    Map<String, EntityType> nameIndex = buildNameIndex(ctx);
    ctx.putCached(CacheKey.of("name-index"), nameIndex);
    
    // Bad - don't cache everything unnecessarily
    // ctx.putCached(CacheKey.of("all-data"), hugeDataStructure);
}
```

## Related Topics

- [Core Concepts](core-concepts.md) - Understanding validation lifecycle
- [Validation Rules](validation-rules.md) - Writing validation rules
- [Extension Methods](extension-methods.md) - Reusable helper functions

---

**Previous**: [Guards and Dependencies](guards-and-dependencies.md) | **Next**: [Extension Methods](extension-methods.md)
