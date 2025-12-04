# Caching

**Navigation**: [Documentation Hub](../index.md) > [User Guide](core-concepts.md) > Caching

This guide explains how to use caching in the Judo Zeta Validation Framework to optimize performance for expensive operations and repeated queries.

## Overview

Caching is a critical performance optimization technique that stores the results of expensive computations so they can be reused instead of recalculated. The framework provides two caching mechanisms:

1. **`@Cached` annotation** - Automatic caching for extension methods
2. **ValidationContext cache API** - Manual caching for validation rules

### When to Use Caching

Cache results when:
- **Graph traversal** - Walking inheritance hierarchies or relationships
- **Model-wide queries** - Finding all instances of a type
- **Expensive computations** - Complex algorithms or calculations
- **Repeated operations** - Called multiple times with same inputs
- **Cross-element validation** - Checking relationships between elements

Don't cache when:
- Operation is trivial (simple field access)
- Result is only needed once
- Data changes between calls
- Memory usage is a concern

## @Cached Annotation for Extension Methods

The `@Cached` annotation automatically caches extension method results based on the target object and method arguments.

### Basic Usage

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public List<EntityType> getAllSuperTypes(EntityType self) {
        // Expensive recursive traversal - cached per EntityType instance
        if (self.getSuperType() == null) {
            return Collections.emptyList();
        }
        
        List<EntityType> result = new ArrayList<>();
        result.add(self.getSuperType());
        result.addAll(getAllSuperTypes(self.getSuperType()));
        return result;
    }
    
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        // Cached per EntityType - includes inherited attributes
        List<Attribute> attrs = new ArrayList<>(self.getAttributes());
        for (EntityType superType : getAllSuperTypes(self)) {
            attrs.addAll(superType.getAttributes());
        }
        return attrs;
    }
}
```

### Multi-Argument Caching

Cache keys automatically include all method arguments:

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public List<Attribute> getAttributesByType(EntityType self, String typeName) {
        // Cached per (EntityType, typeName) combination
        return self.getAttributes().stream()
            .filter(a -> a.getType().equals(typeName))
            .collect(Collectors.toList());
    }
    
    @Cached
    public boolean hasAttribute(EntityType self, String name, boolean includeInherited) {
        // Cached per (EntityType, name, includeInherited) combination
        List<Attribute> attrs = includeInherited 
            ? getAllAttributes(self) 
            : self.getAttributes();
        return attrs.stream().anyMatch(a -> a.getName().equals(name));
    }
}
```

### Using Cached Extension Methods

Call cached extension methods via `ValidationContext`:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHavePrimaryKey", message = "Entity must have primary key")
    public ValidationRule mustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // First call: expensive computation executed
            List<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
            
            // Subsequent calls with same entity: cached result returned
            boolean hasPK = allAttrs.stream().anyMatch(Attribute::isPrimaryKey);
            
            return hasPK
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have at least one primary key");
        };
    }
}
```

### Performance Impact Example

**Without caching:**
```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    // NO @Cached annotation
    public List<EntityType> getAllSuperTypes(EntityType self) {
        // Called 1000 times on same entity = 1000 traversals
        if (self.getSuperType() == null) {
            return Collections.emptyList();
        }
        List<EntityType> result = new ArrayList<>();
        result.add(self.getSuperType());
        result.addAll(getAllSuperTypes(self.getSuperType()));
        return result;
    }
}

// Validation calling this 1000 times
@Constraint(name = "CheckInheritance", message = "...")
public ValidationRule checkInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        for (int i = 0; i < 1000; i++) {
            // Each call traverses entire hierarchy - SLOW
            List<EntityType> supers = ctx.call(entity, "getAllSuperTypes");
        }
        return ValidationResult.pass();
    };
}
```

**With caching:**
```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached  // Add this annotation
    public List<EntityType> getAllSuperTypes(EntityType self) {
        // Called once per unique entity, then cached
        if (self.getSuperType() == null) {
            return Collections.emptyList();
        }
        List<EntityType> result = new ArrayList<>();
        result.add(self.getSuperType());
        result.addAll(getAllSuperTypes(self.getSuperType()));
        return result;
    }
}

// Same validation now 100x faster
@Constraint(name = "CheckInheritance", message = "...")
public ValidationRule checkInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        for (int i = 0; i < 1000; i++) {
            // First call: traverses hierarchy
            // Calls 2-1000: instant cache hit
            List<EntityType> supers = ctx.call(entity, "getAllSuperTypes");
        }
        return ValidationResult.pass();
    };
}
```

**Performance improvement: ~100x faster for repeated calls**

## CacheKey Generation

Cache keys are generated automatically using type-specific strategies to ensure stable, unique identifiers.

### CacheKey Components

```java
public class CacheKeyBuilder {
    
    // Cache key parts based on argument types:
    // - EObject: XMI ID (stable across sessions)
    // - Primitives: value directly (String, Number, Boolean, Enum)
    // - Collection: ordered list of element keys
    // - Map: sorted by key, then key-value pairs
    // - Null: special NULL_MARKER
    // - Other: identity hash code (fallback)
}
```

### Example Cache Keys

```java
@ExtensionMethod(EntityType.class)
public class Examples {
    
    @Cached
    public String getNameUpper(EntityType self) {
        // Key: [XMI_ID(self), "getNameUpper"]
        return self.getName().toUpperCase();
    }
    
    @Cached
    public List<Attribute> getAttributesByType(EntityType self, String type) {
        // Key: [XMI_ID(self), "getAttributesByType", "String"]
        return self.getAttributes().stream()
            .filter(a -> a.getType().equals(type))
            .collect(Collectors.toList());
    }
    
    @Cached
    public boolean hasRelationship(EntityType self, EntityType target, boolean bidirectional) {
        // Key: [XMI_ID(self), "hasRelationship", XMI_ID(target), true]
        return checkRelationship(self, target, bidirectional);
    }
    
    @Cached
    public List<String> getNames(EntityType self, List<EntityType> entities) {
        // Key: [XMI_ID(self), "getNames", [XMI_ID(e1), XMI_ID(e2), ...]]
        return entities.stream()
            .map(EntityType::getName)
            .collect(Collectors.toList());
    }
}
```

## ValidationContext Cache API

For manual caching within validation rules, use the `ValidationContext` cache API.

### Manual Cache Methods

```java
public interface ValidationContext {
    
    // Get cached value for key (returns null if not cached)
    Object getCached(CacheKey key);
    
    // Store value in cache
    void putCached(CacheKey key, Object value);
    
    // Clear all caches (called between validation runs)
    void clearCaches();
}
```

### Creating Cache Keys

Use static factory methods to create cache keys:

```java
import hu.blackbelt.judo.zeta.validation.core.CacheKey;
import hu.blackbelt.judo.zeta.validation.core.CacheKeyBuilder;

// Simple element-based key
CacheKey key1 = CacheKeyBuilder.build(element, "myCache");

// Element + string key
CacheKey key2 = CacheKeyBuilder.build(element, "myCache", "additionalKey");

// Element + multiple arguments
CacheKey key3 = CacheKeyBuilder.build(element, "myCache", "arg1", 42, true);

// For satisfies checks (internal use)
CacheKey key4 = CacheKeyBuilder.buildSatisfiesKey(element, "ConstraintName");
```

### Manual Caching Pattern

```java
@Constraint(name = "NoCyclicInheritance", message = "Cyclic inheritance detected")
public ValidationRule noCyclicInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Check cache first
        CacheKey cacheKey = CacheKeyBuilder.build(entity, "cyclicInheritance");
        Boolean cached = (Boolean) ctx.getCached(cacheKey);
        
        if (cached != null) {
            // Cache hit - return cached result
            return cached
                ? ValidationResult.fail("Cyclic inheritance detected")
                : ValidationResult.pass();
        }
        
        // Cache miss - perform expensive check
        boolean hasCycle = detectCycle(entity, new HashSet<>());
        
        // Store in cache for next time
        ctx.putCached(cacheKey, hasCycle);
        
        return hasCycle
            ? ValidationResult.fail("Cyclic inheritance detected")
            : ValidationResult.pass();
    };
}

private boolean detectCycle(EntityType entity, Set<EntityType> visited) {
    if (visited.contains(entity)) {
        return true;
    }
    if (entity.getSuperType() == null) {
        return false;
    }
    visited.add(entity);
    return detectCycle(entity.getSuperType(), visited);
}
```

### Model-Wide Cache Pattern

Build an index once and cache it for all validation rules:

```java
@Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Build name index once and cache it
        CacheKey indexKey = CacheKeyBuilder.build(ctx.getResourceSet(), "entityNameIndex");
        Map<String, List<EntityType>> nameIndex = 
            (Map<String, List<EntityType>>) ctx.getCached(indexKey);
        
        if (nameIndex == null) {
            // First time - build index for entire model
            nameIndex = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(EntityType::getName));
            ctx.putCached(indexKey, nameIndex);
        }
        
        // Use cached index - instant lookup
        List<EntityType> duplicates = nameIndex.get(entity.getName());
        
        if (duplicates.size() > 1) {
            return ValidationResult.fail(
                "NameMustBeUnique",
                "Entity name '" + entity.getName() + "' is used by " + 
                duplicates.size() + " entities",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Performance improvement: O(n) → O(1) lookup per element**

## Cache Invalidation

The framework automatically clears caches at appropriate times.

### Automatic Cache Clearing

```java
// ValidationExecutor lifecycle:
public List<ValidationResult> validate(List<EObject> elements) {
    // 1. Clear all caches before validation
    context.clearExtensionCache();
    context.clearSatisfiesCache();
    
    // 2. Run pre-validation hooks
    registry.invokePreValidationHooks(context);
    
    // 3. Execute validation rules (caches populate)
    List<ValidationResult> results = executeRules(elements);
    
    // 4. Run post-validation hooks
    registry.invokePostValidationHooks(context);
    
    // 5. Clear all caches after validation
    context.clearExtensionCache();
    context.clearSatisfiesCache();
    
    return results;
}
```

### Manual Cache Clearing

Clear caches manually in lifecycle hooks:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @PreValidation
    public void setUp(ValidationContext ctx) {
        // Clear specific caches before validation starts
        ctx.clearExtensionCache();
        
        // Initialize custom caches
        ctx.setAttribute("customIndex", buildCustomIndex(ctx));
    }
    
    @PostValidation
    public void tearDown(ValidationContext ctx) {
        // Clean up after validation
        ctx.clearExtensionCache();
        ctx.clearSatisfiesCache();
    }
}
```

### Cache Scope

**Cache lifetime:**
- **Extension method cache** - Lives for entire validation run
- **Satisfies cache** - Lives for entire validation run
- **Manual cache** - Lives for entire validation run
- **All caches** - Cleared before and after each `validate()` call

**Cache sharing:**
- Thread-safe for parallel validation
- Shared across all elements in same validation run
- NOT shared across multiple validation runs

## Performance Best Practices

### 1. Cache Expensive Graph Traversals

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public Set<EntityType> getAllReachableTypes(EntityType self) {
        // Expensive graph traversal - definitely cache this
        Set<EntityType> reachable = new HashSet<>();
        collectReachableTypes(self, reachable);
        return reachable;
    }
    
    private void collectReachableTypes(EntityType current, Set<EntityType> visited) {
        if (!visited.add(current)) {
            return;
        }
        for (Relationship rel : current.getRelationships()) {
            collectReachableTypes(rel.getTarget(), visited);
        }
    }
}
```

### 2. Build Indexes for Uniqueness Checks

```java
@Constraint(name = "TableNameMustBeUnique", message = "Table name must be unique")
public ValidationRule tableNameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Build table name index once for all entities
        CacheKey key = CacheKeyBuilder.build(ctx.getResourceSet(), "tableNameIndex");
        Map<String, Long> tableNameCounts = 
            (Map<String, Long>) ctx.getCached(key);
        
        if (tableNameCounts == null) {
            tableNameCounts = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(
                    EntityType::getTableName,
                    Collectors.counting()
                ));
            ctx.putCached(key, tableNameCounts);
        }
        
        Long count = tableNameCounts.get(entity.getTableName());
        return (count != null && count > 1)
            ? ValidationResult.fail("Table name '" + entity.getTableName() + "' is used " + count + " times")
            : ValidationResult.pass();
    };
}
```

### 3. Cache Type Collections

```java
@PreValidation
public void setUp(ValidationContext ctx) {
    // Pre-compute and cache expensive collections
    List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
    List<Attribute> allAttributes = ctx.getAllInstances(Attribute.class);
    
    ctx.setAttribute("allEntities", allEntities);
    ctx.setAttribute("allAttributes", allAttributes);
    
    // Build lookup maps
    Map<String, EntityType> entityByName = allEntities.stream()
        .collect(Collectors.toMap(EntityType::getName, e -> e));
    ctx.setAttribute("entityByName", entityByName);
}

@Constraint(name = "ValidReference", message = "Reference must target existing entity")
public ValidationRule validReference() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        
        // Use pre-cached map - instant lookup
        Map<String, EntityType> entityByName = ctx.getAttribute("entityByName");
        
        if (attr.getReferencedEntity() != null) {
            boolean exists = entityByName.containsKey(attr.getReferencedEntity());
            if (!exists) {
                return ValidationResult.fail("Referenced entity '" + 
                    attr.getReferencedEntity() + "' does not exist");
            }
        }
        
        return ValidationResult.pass();
    };
}
```

### 4. Don't Cache Trivial Operations

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    // ❌ BAD: Caching trivial field access is wasteful
    @Cached
    public String getName(EntityType self) {
        return self.getName();
    }
    
    // ✓ GOOD: Only cache expensive operations
    @Cached
    public List<EntityType> getAllSuperTypes(EntityType self) {
        // Recursive traversal - worth caching
        List<EntityType> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        while (current != null) {
            result.add(current);
            current = current.getSuperType();
        }
        return result;
    }
}
```

## Real-World Performance Examples

### Example 1: Inheritance Hierarchy Validation

**Before caching:**
```java
@Constraint(name = "NoMultipleInheritance", message = "...")
public ValidationRule noMultipleInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Traverses hierarchy 3 times for each entity - SLOW
        List<EntityType> supers1 = getAllSuperTypes(entity);  // 1st traversal
        List<EntityType> supers2 = getAllSuperTypes(entity);  // 2nd traversal
        List<EntityType> supers3 = getAllSuperTypes(entity);  // 3rd traversal
        
        return ValidationResult.pass();
    };
}
```
**Performance: 100 entities × 3 traversals × 50ms = 15 seconds**

**After caching:**
```java
@Cached  // Add @Cached to getAllSuperTypes method
public List<EntityType> getAllSuperTypes(EntityType self) { ... }

@Constraint(name = "NoMultipleInheritance", message = "...")
public ValidationRule noMultipleInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // First call: 50ms, subsequent calls: <1ms each
        List<EntityType> supers1 = ctx.call(entity, "getAllSuperTypes");  // 50ms
        List<EntityType> supers2 = ctx.call(entity, "getAllSuperTypes");  // <1ms
        List<EntityType> supers3 = ctx.call(entity, "getAllSuperTypes");  // <1ms
        
        return ValidationResult.pass();
    };
}
```
**Performance: 100 entities × 50ms = 5 seconds (3x faster)**

### Example 2: Uniqueness Validation

**Before caching:**
```java
@Constraint(name = "NameMustBeUnique", message = "...")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Gets ALL entities for EACH entity - O(n²)
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        long count = allEntities.stream()
            .filter(e -> e.getName().equals(entity.getName()))
            .count();
        
        return count > 1
            ? ValidationResult.fail("Duplicate name")
            : ValidationResult.pass();
    };
}
```
**Performance: 1000 entities × 10ms = 10 seconds**

**After caching:**
```java
@Constraint(name = "NameMustBeUnique", message = "...")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Build index once, use 1000 times - O(n)
        CacheKey key = CacheKeyBuilder.build(ctx.getResourceSet(), "nameIndex");
        Map<String, List<EntityType>> nameIndex = (Map) ctx.getCached(key);
        
        if (nameIndex == null) {
            nameIndex = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(EntityType::getName));
            ctx.putCached(key, nameIndex);
        }
        
        long count = nameIndex.get(entity.getName()).size();
        return count > 1
            ? ValidationResult.fail("Duplicate name")
            : ValidationResult.pass();
    };
}
```
**Performance: 100ms index build + 1000 × <1ms = ~1 second (10x faster)**

## Related Topics

- [Extension Methods](extension-methods.md) - Reusable helper functions with @Cached
- [Performance](../best-practices/performance.md) - Optimization strategies and parallelization
- [Lifecycle Hooks](lifecycle-hooks.md) - Pre/post validation for cache setup
- [ValidationContext API](../reference/validation-context.md) - Complete API reference

---

**Previous**: [Guards and Dependencies](guards-and-dependencies.md) | **Next**: [Extension Methods](extension-methods.md)
