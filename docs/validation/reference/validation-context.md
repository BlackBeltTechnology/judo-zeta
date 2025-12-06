# ValidationContext API Reference

**Navigation**: [Documentation Hub](../../index.md) > [Reference](validation-context.md) > ValidationContext API

This document provides comprehensive reference documentation for the `ValidationContext` API, which serves as the runtime context for validation execution in the Judo Zeta Validation Framework.

## Overview

The `ValidationContext` class provides access to the validation runtime environment, including:

- Model traversal and querying capabilities
- Constraint dependency checking via `satisfies()`
- Extension method invocation
- Manual caching for expensive computations
- Custom attributes for lifecycle hooks
- Thread-safe element access for parallel validation

**Package**: `hu.blackbelt.judo.zeta.validation.core`  
**Thread Safety**: Thread-safe for parallel validation execution  
**Lifecycle**: One context instance per validation run

## Class Signature

```java
public class ValidationContext {
    public ValidationContext(
        ModelProvider modelProvider,
        ResourceSet resourceSet,
        ExtensionMethodRegistry extensionRegistry
    )
}
```

## Core API Methods

### Model Traversal

#### getAllInstances

```java
public <T extends EObject> Collection<T> getAllInstances(Class<T> eClass)
```

Retrieves all instances of a given EMF type from the resource set.

**Parameters**:
- `eClass` - The Java class representing the EMF type (must extend `EObject`)

**Returns**:
- `Collection<T>` - All instances of the specified type in the model

**Behavior**:
- Delegates to the `ModelProvider` for efficient model traversal
- Returns an unmodifiable collection
- Includes instances of subtypes (polymorphic query)
- Performance: O(n) where n is the total number of model elements

**Thread Safety**: Thread-safe, can be called concurrently from multiple validation rules

**Example Usage**:

```java
@Constraint(name = "EntityNameMustBeUnique", message = "Duplicate entity name: {element.name}")
public ValidationRule entityNameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Get all entities in the model
        Collection<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        // Check for name uniqueness
        long duplicates = allEntities.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
            
        return duplicates == 1
            ? ValidationResult.pass()
            : ValidationResult.fail("Duplicate name: " + entity.getName());
    };
}
```

**Common Patterns**:

```java
// Count instances
int entityCount = ctx.getAllInstances(EntityType.class).size();

// Filter instances
List<EntityType> abstractEntities = ctx.getAllInstances(EntityType.class).stream()
    .filter(EntityType::isAbstract)
    .collect(Collectors.toList());

// Group instances
Map<String, List<EntityType>> byPackage = ctx.getAllInstances(EntityType.class).stream()
    .collect(Collectors.groupingBy(EntityType::getPackageName));
```

---

### Constraint Dependency Checking

#### satisfies (Current Element)

```java
public boolean satisfies(String constraintName)
```

Checks if a named constraint is satisfied for the current element being validated.

**Parameters**:
- `constraintName` - The name of the constraint to check (from `@Constraint` or `@Critique` annotation)

**Returns**:
- `boolean` - `true` if the constraint passed, `false` otherwise

**Behavior**:
- Operates on the current element (from `getCurrentElement()`)
- Uses internal caching to avoid re-evaluation
- Handles circular dependencies by assuming satisfaction during cycles
- Evaluates the constraint's guard first - if guard fails, returns `false`
- Evaluates the constraint's own `@Satisfies` dependencies recursively
- Returns `true` if no matching constraint is found (open-world assumption)

**Thread Safety**: Thread-safe via `ThreadLocal` current element and `ConcurrentHashMap` cache

**Example Usage**:

```java
@Satisfies(constraints = {"EntityMustHaveName"})
@Constraint(name = "EntityNameMustBeValid", message = "Invalid entity name format")
public ValidationRule entityNameMustBeValid() {
    return (element, ctx) -> {
        // This rule only runs if EntityMustHaveName passed
        // But you can also check manually:
        if (!ctx.satisfies("EntityMustHaveName")) {
            return ValidationResult.skip("Prerequisite failed");
        }
        
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        
        return name.matches("[A-Z][a-zA-Z0-9]*")
            ? ValidationResult.pass()
            : ValidationResult.fail("Name must be PascalCase");
    };
}
```

**Guard Method Pattern**:

```java
@Guard(method = "hasTable")
@Constraint(name = "TableNameMustBeValid", message = "Invalid table name")
public ValidationRule tableNameMustBeValid() {
    return (element, ctx) -> {
        // This rule only runs if hasTable() returns true
        // You can also check if another constraint with a guard passed:
        if (!ctx.satisfies("EntityMustHaveTable")) {
            return ValidationResult.skip("Entity has no table");
        }
        
        EntityType entity = (EntityType) element;
        String tableName = entity.getTableName();
        
        return tableName.matches("[a-z_]+")
            ? ValidationResult.pass()
            : ValidationResult.fail("Table name must be snake_case");
    };
}

private boolean hasTable(EObject element) {
    return ((EntityType) element).getTableName() != null;
}
```

---

#### satisfies (Any Element)

```java
public boolean satisfies(EObject element, String constraintName)
```

Checks if a named constraint is satisfied for any element (not just the current one). This enables cross-object dependency checking.

**Parameters**:
- `element` - The element to check (can be different from current element)
- `constraintName` - The name of the constraint to check

**Returns**:
- `boolean` - `true` if the constraint passed for the given element, `false` otherwise

**Behavior**:
- Same evaluation logic as `satisfies(String)` but for a specified element
- Enables checking constraints on referenced objects
- Useful for validating relationships and dependencies
- Cache key includes both element identity and constraint name

**Thread Safety**: Thread-safe, cache uses element identity for isolation

**Example Usage**:

```java
@Constraint(name = "ReferencedEntityMustBeValid", 
            message = "Referenced entity is invalid")
public ValidationRule referencedEntityMustBeValid() {
    return (element, ctx) -> {
        EntityReference ref = (EntityReference) element;
        EntityType target = ref.getTargetEntity();
        
        // Check if the referenced entity satisfies its own constraints
        if (!ctx.satisfies(target, "EntityMustHaveName")) {
            return ValidationResult.fail(
                "Cannot reference entity without a name"
            );
        }
        
        if (!ctx.satisfies(target, "EntityMustHaveTable")) {
            return ValidationResult.fail(
                "Cannot reference entity without a table"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Relationship Validation Pattern**:

```java
@Constraint(name = "InheritanceMustBeValid", 
            message = "Invalid inheritance hierarchy")
public ValidationRule inheritanceMustBeValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        EntityType superType = entity.getSuperType();
        
        if (superType == null) {
            return ValidationResult.pass(); // No inheritance
        }
        
        // Ensure supertype satisfies its own constraints first
        if (!ctx.satisfies(superType, "EntityMustNotBeAbstract")) {
            return ValidationResult.warn(
                "Inheriting from abstract entity: " + superType.getName()
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

#### allSatisfy

```java
public boolean allSatisfy(Collection<? extends EObject> elements, String constraintName)
```

Checks if all elements in a collection satisfy a named constraint. Convenience method for `forAll` patterns.

**Parameters**:
- `elements` - Collection of elements to check
- `constraintName` - The name of the constraint to check

**Returns**:
- `boolean` - `true` if all elements satisfy the constraint, `false` if any fails

**Behavior**:
- Short-circuits on first failure for performance
- Returns `true` for empty collections (vacuous truth)
- Equivalent to `elements.stream().allMatch(e -> ctx.satisfies(e, constraintName))`

**Thread Safety**: Thread-safe

**Example Usage**:

```java
@Constraint(name = "AllAttributesMustBeValid", 
            message = "Entity has invalid attributes")
public ValidationRule allAttributesMustBeValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        List<Attribute> attributes = entity.getAttributes();
        
        // Check if all attributes satisfy their constraints
        if (!ctx.allSatisfy(attributes, "AttributeMustHaveName")) {
            return ValidationResult.fail(
                "Some attributes are missing names"
            );
        }
        
        if (!ctx.allSatisfy(attributes, "AttributeTypeMustBeValid")) {
            return ValidationResult.fail(
                "Some attributes have invalid types"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

### Extension Method Invocation

#### call (Target Element)

```java
public <T> T call(EObject target, String methodName, Object... args)
```

Invokes a registered extension method on any EObject, with result caching.

**Parameters**:
- `target` - The target object to invoke the method on
- `methodName` - The name of the extension method (from `@ExtensionMethod` annotation)
- `args` - Additional arguments to pass to the method (varargs)

**Returns**:
- `T` - The result of the method invocation (type inferred from context)

**Behavior**:
- Looks up the method in the `ExtensionMethodRegistry`
- Validates that the method is registered for the target's type
- Generates a cache key from `(target, methodName, args)`
- Returns cached result if available (for methods annotated with `@Cached`)
- Invokes the method and caches the result if not cached
- Throws `IllegalArgumentException` if method not found or type mismatch

**Thread Safety**: Thread-safe, uses concurrent caching

**Example Usage**:

```java
// First, define the extension method in your validation class:
@ExtensionMethod(elementType = EntityType.class)
public List<Attribute> getAllAttributes(EntityType entity) {
    List<Attribute> result = new ArrayList<>(entity.getAttributes());
    if (entity.getSuperType() != null) {
        result.addAll(getAllAttributes(entity.getSuperType()));
    }
    return result;
}

// Then use it in validation rules:
@Constraint(name = "MustHavePrimaryKey", message = "No primary key found")
public ValidationRule mustHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Call the extension method
        List<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
        
        boolean hasPK = allAttrs.stream()
            .anyMatch(Attribute::isPrimaryKey);
            
        return hasPK
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have a primary key");
    };
}
```

**Passing Arguments**:

```java
@ExtensionMethod(elementType = EntityType.class)
public boolean hasAttributeOfType(EntityType entity, String typeName) {
    return entity.getAttributes().stream()
        .anyMatch(attr -> attr.getType().getName().equals(typeName));
}

// Usage:
boolean hasStringAttr = ctx.call(entity, "hasAttributeOfType", "String");
```

---

#### call (Current Element)

```java
public <T> T call(String methodName, Object... args)
```

Invokes an extension method on the current element being validated. Convenience overload.

**Parameters**:
- `methodName` - The name of the extension method
- `args` - Additional arguments to pass to the method (varargs)

**Returns**:
- `T` - The result of the method invocation

**Behavior**:
- Equivalent to `call(getCurrentElement(), methodName, args)`
- More concise when operating on the current element

**Thread Safety**: Thread-safe via `ThreadLocal` current element

**Example Usage**:

```java
@Constraint(name = "MustHaveUniqueAttributeNames", 
            message = "Duplicate attribute names found")
public ValidationRule mustHaveUniqueAttributeNames() {
    return (element, ctx) -> {
        // Call extension method on current element
        List<Attribute> allAttrs = ctx.call("getAllAttributes");
        
        Set<String> names = new HashSet<>();
        for (Attribute attr : allAttrs) {
            if (!names.add(attr.getName())) {
                return ValidationResult.fail(
                    "Duplicate attribute: " + attr.getName()
                );
            }
        }
        
        return ValidationResult.pass();
    };
}
```

---

### Manual Caching

#### getCached

```java
public Object getCached(CacheKey key)
```

Retrieves a cached value for a given cache key.

**Parameters**:
- `key` - The cache key (created via `CacheKeyBuilder`)

**Returns**:
- `Object` - The cached value, or `null` if not cached

**Behavior**:
- Returns `null` if the key has never been cached
- Cache persists for the duration of the validation run
- Cleared automatically after validation completes (via `clearCaches()`)
- Not cleared between elements in the same validation run

**Thread Safety**: Thread-safe via `ConcurrentHashMap`

**Example Usage**:

```java
import hu.blackbelt.judo.zeta.validation.core.CacheKey;
import hu.blackbelt.judo.zeta.validation.core.CacheKeyBuilder;

@Constraint(name = "NoCyclicInheritance", 
            message = "Cyclic inheritance detected")
public ValidationRule noCyclicInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Create cache key
        CacheKey cacheKey = CacheKeyBuilder.build(element, "inheritanceChain");
        
        // Check cache first
        @SuppressWarnings("unchecked")
        Set<EntityType> chain = (Set<EntityType>) ctx.getCached(cacheKey);
        
        if (chain == null) {
            // Not cached, compute the inheritance chain
            chain = new HashSet<>();
            EntityType current = entity;
            
            while (current != null) {
                if (!chain.add(current)) {
                    // Cycle detected
                    ctx.putCached(cacheKey, chain);
                    return ValidationResult.fail("Cyclic inheritance detected");
                }
                current = current.getSuperType();
            }
            
            // Cache the result
            ctx.putCached(cacheKey, chain);
        }
        
        return ValidationResult.pass();
    };
}
```

---

#### putCached

```java
public void putCached(CacheKey key, Object value)
```

Stores a value in the cache for a given cache key.

**Parameters**:
- `key` - The cache key (created via `CacheKeyBuilder`)
- `value` - The value to cache (any object, including `null`)

**Returns**: void

**Behavior**:
- Overwrites any existing cached value for the same key
- Value persists for the duration of the validation run
- Can cache `null` values (distinguished from "not cached")
- Memory usage grows with unique cache keys

**Thread Safety**: Thread-safe via `ConcurrentHashMap`

**Example Usage**:

```java
@Constraint(name = "TableNameMustBeUnique", 
            message = "Duplicate table name")
public ValidationRule tableNameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Create a global cache key (not element-specific)
        CacheKey indexKey = CacheKeyBuilder.build(
            ctx.getResourceSet(), 
            "tableNameIndex"
        );
        
        // Get or build the table name index
        @SuppressWarnings("unchecked")
        Map<String, List<EntityType>> tableIndex = 
            (Map<String, List<EntityType>>) ctx.getCached(indexKey);
            
        if (tableIndex == null) {
            // Build index once for all entities
            tableIndex = new HashMap<>();
            for (EntityType e : ctx.getAllInstances(EntityType.class)) {
                if (e.getTableName() != null) {
                    tableIndex.computeIfAbsent(
                        e.getTableName(), 
                        k -> new ArrayList<>()
                    ).add(e);
                }
            }
            ctx.putCached(indexKey, tableIndex);
        }
        
        // Use the cached index
        String tableName = entity.getTableName();
        if (tableName != null && tableIndex.get(tableName).size() > 1) {
            return ValidationResult.fail(
                "Duplicate table name: " + tableName
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

#### clearCaches

```java
public void clearCaches()
```

Clears all manually cached values (does not clear `satisfies()` cache or extension method cache).

**Parameters**: None

**Returns**: void

**Behavior**:
- Removes all entries from the manual cache (from `getCached`/`putCached`)
- Does NOT clear the internal `satisfies()` cache
- Does NOT clear the extension method cache (use `clearExtensionCache()` for that)
- Typically called automatically by the framework between validation runs
- Can be called manually in `@PostValidation` hooks if needed

**Thread Safety**: Thread-safe

**Example Usage**:

```java
@PostValidation
public void cleanupCaches(ValidationContext ctx) {
    // Clear manual caches after validation
    ctx.clearCaches();
    
    // Optionally clear extension method cache
    ctx.clearExtensionCache();
}
```

**Cache Lifecycle**:

```java
// Framework lifecycle:
// 1. ValidationExecutor creates context
// 2. PreValidation hooks run
// 3. Validation rules execute (can use getCached/putCached)
// 4. PostValidation hooks run
// 5. clearCaches() called automatically
```

---

### Element Access

#### getCurrentElement

```java
public EObject getCurrentElement()
```

Gets the current element being validated.

**Parameters**: None

**Returns**:
- `EObject` - The current element, or `null` if not set

**Behavior**:
- Uses `ThreadLocal` storage for thread-safety
- Each thread has its own current element in parallel validation
- Set automatically by the `ValidationExecutor` before invoking rules
- Should not be called outside validation rule execution

**Thread Safety**: Thread-safe via `ThreadLocal`

**Example Usage**:

```java
@Constraint(name = "ExampleConstraint", message = "Example")
public ValidationRule exampleConstraint() {
    return (element, ctx) -> {
        // element parameter is same as ctx.getCurrentElement()
        assert element == ctx.getCurrentElement();
        
        // Usually you use the element parameter directly:
        EntityType entity = (EntityType) element;
        
        // But getCurrentElement() is useful in helper methods:
        return helperMethod(ctx);
    };
}

private ValidationResult helperMethod(ValidationContext ctx) {
    // Access current element without passing it as parameter
    EObject current = ctx.getCurrentElement();
    EntityType entity = (EntityType) current;
    // ... validation logic
    return ValidationResult.pass();
}
```

---

### Custom Attributes

#### setAttribute

```java
public void setAttribute(String key, Object value)
```

Sets a custom attribute for cross-hook communication.

**Parameters**:
- `key` - The attribute key (string identifier)
- `value` - The attribute value (any object)

**Returns**: void

**Behavior**:
- Stores key-value pairs in the context
- Persists for the lifetime of the validation run
- Primarily used for communication between lifecycle hooks
- Overwrites existing value for the same key

**Thread Safety**: Thread-safe via `ConcurrentHashMap`

**Example Usage**:

```java
@PreValidation
public void recordStartTime(ValidationContext ctx) {
    // Store start time for performance measurement
    ctx.setAttribute("validation.startTime", System.currentTimeMillis());
}

@PostValidation
public void logDuration(ValidationContext ctx) {
    Long startTime = ctx.getAttribute("validation.startTime");
    if (startTime != null) {
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Validation took " + duration + "ms");
    }
}
```

---

#### getAttribute

```java
public <T> T getAttribute(String key)
```

Gets a custom attribute value.

**Parameters**:
- `key` - The attribute key (string identifier)

**Returns**:
- `T` - The attribute value (cast to expected type), or `null` if not set

**Behavior**:
- Returns `null` if key not found
- Type casting is caller's responsibility
- Throws `ClassCastException` if type mismatch

**Thread Safety**: Thread-safe

**Example Usage**:

```java
@PreValidation
public void setupValidation(ValidationContext ctx) {
    // Build global index
    Map<String, EntityType> index = buildEntityIndex(ctx);
    ctx.setAttribute("entity.index", index);
}

@Constraint(name = "ReferenceMustBeValid", message = "Invalid reference")
public ValidationRule referenceMustBeValid() {
    return (element, ctx) -> {
        // Use the pre-built index
        @SuppressWarnings("unchecked")
        Map<String, EntityType> index = ctx.getAttribute("entity.index");
        
        if (index == null) {
            return ValidationResult.fail("Entity index not initialized");
        }
        
        EntityReference ref = (EntityReference) element;
        EntityType target = index.get(ref.getTargetName());
        
        return target != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Unknown entity: " + ref.getTargetName());
    };
}
```

---

## Advanced Methods

### clearSatisfiesCache

```java
public void clearSatisfiesCache()
```

Clears the internal `satisfies()` constraint evaluation cache.

**Behavior**:
- Called automatically by the framework between validation runs
- Rarely needed in user code
- Does not affect manual cache (from `getCached`/`putCached`)

---

### clearExtensionCache

```java
public void clearExtensionCache()
```

Clears the extension method result cache.

**Behavior**:
- Delegates to `ExtensionMethodRegistry.clearCache()`
- Called automatically by the framework between validation runs
- Useful if extension method results change during validation

---

### clearCurrentElement

```java
public void clearCurrentElement()
```

Removes the current element from `ThreadLocal` storage to prevent memory leaks.

**Behavior**:
- Called automatically by the framework after each element validation
- Should not be called in user code
- Critical for preventing memory leaks in long-running applications

---

## Thread Safety Guarantees

The `ValidationContext` is designed for safe parallel validation:

| Component | Thread Safety Mechanism |
|-----------|------------------------|
| Current element | `ThreadLocal` - isolated per thread |
| Satisfies cache | `ConcurrentHashMap` - thread-safe reads/writes |
| Manual cache | `ConcurrentHashMap` - thread-safe reads/writes |
| Attributes | `ConcurrentHashMap` - thread-safe reads/writes |
| Extension methods | `ConcurrentHashMap` cache in registry |

**Parallel Validation Safety**:

```java
// Thread 1 validates Entity A
ctx.setCurrentElement(entityA);  // ThreadLocal - isolated
boolean result = ctx.satisfies("ConstraintX");  // ConcurrentHashMap - safe

// Thread 2 validates Entity B (simultaneously)
ctx.setCurrentElement(entityB);  // ThreadLocal - isolated, doesn't affect Thread 1
boolean result = ctx.satisfies("ConstraintX");  // ConcurrentHashMap - safe
```

---

## Best Practices

### 1. Prefer Extension Methods Over Manual Caching

```java
// GOOD: Reusable, cached automatically
@ExtensionMethod(elementType = EntityType.class)
@Cached
public List<Attribute> getAllAttributes(EntityType entity) {
    // Implementation
}

// AVOID: Manual cache management in every rule
@Constraint(name = "SomeConstraint", message = "...")
public ValidationRule someConstraint() {
    return (element, ctx) -> {
        CacheKey key = CacheKeyBuilder.build(element, "attrs");
        List<Attribute> attrs = (List) ctx.getCached(key);
        if (attrs == null) {
            attrs = computeAttributes();
            ctx.putCached(key, attrs);
        }
        // ...
    };
}
```

### 2. Use Specific Cache Keys

```java
// GOOD: Unique cache keys prevent collisions
CacheKey key1 = CacheKeyBuilder.build(element, "inheritanceChain");
CacheKey key2 = CacheKeyBuilder.build(element, "attributeMap", "includeSuperTypes");

// AVOID: Generic keys may collide
CacheKey key = CacheKeyBuilder.build(element, "data");
```

### 3. Check `satisfies()` for Prerequisites

```java
// GOOD: Explicit dependency checking
@Satisfies(constraints = {"EntityMustHaveName"})
@Constraint(name = "NameMustBeValid", message = "Invalid name format")
public ValidationRule nameMustBeValid() {
    return (element, ctx) -> {
        // Name is guaranteed to exist due to @Satisfies
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        return name.matches("[A-Z][a-zA-Z0-9]*")
            ? ValidationResult.pass()
            : ValidationResult.fail("Name must be PascalCase");
    };
}
```

### 4. Build Global Indexes in `@PreValidation`

```java
// GOOD: Build once, use many times
@PreValidation
public void buildIndexes(ValidationContext ctx) {
    Map<String, EntityType> nameIndex = ctx.getAllInstances(EntityType.class).stream()
        .collect(Collectors.toMap(EntityType::getName, Function.identity()));
    ctx.setAttribute("entity.name.index", nameIndex);
}

@Constraint(name = "ReferenceTargetExists", message = "Unknown entity")
public ValidationRule referenceTargetExists() {
    return (element, ctx) -> {
        Map<String, EntityType> index = ctx.getAttribute("entity.name.index");
        // Use pre-built index
    };
}
```

### 5. Use Type-Safe Cache Retrieval

```java
// GOOD: Explicit type handling
@SuppressWarnings("unchecked")
Map<String, List<EntityType>> index = (Map) ctx.getCached(key);
if (index == null) {
    index = new HashMap<>();
    ctx.putCached(key, index);
}

// AVOID: Unchecked type assumptions
Map index = (Map) ctx.getCached(key);  // Unsafe
```

---

## Common Patterns

### Pattern 1: Unique Name Validation

```java
@Constraint(name = "EntityNameMustBeUnique", message = "Duplicate name")
public ValidationRule entityNameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        long count = ctx.getAllInstances(EntityType.class).stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
            
        return count == 1
            ? ValidationResult.pass()
            : ValidationResult.fail("Duplicate: " + entity.getName());
    };
}
```

### Pattern 2: Cross-Reference Validation

```java
@Constraint(name = "ReferencedEntityMustBeValid", message = "Invalid reference")
public ValidationRule referencedEntityMustBeValid() {
    return (element, ctx) -> {
        EntityReference ref = (EntityReference) element;
        EntityType target = ref.getTarget();
        
        if (!ctx.satisfies(target, "EntityMustHaveName")) {
            return ValidationResult.fail("Referenced entity has no name");
        }
        
        return ValidationResult.pass();
    };
}
```

### Pattern 3: Cached Graph Traversal

```java
@Constraint(name = "NoCyclicReferences", message = "Cycle detected")
public ValidationRule noCyclicReferences() {
    return (element, ctx) -> {
        CacheKey key = CacheKeyBuilder.build(element, "visited");
        
        @SuppressWarnings("unchecked")
        Set<EObject> visited = (Set<EObject>) ctx.getCached(key);
        
        if (visited == null) {
            visited = new HashSet<>();
            if (!traverseGraph(element, visited)) {
                return ValidationResult.fail("Cyclic reference detected");
            }
            ctx.putCached(key, visited);
        }
        
        return ValidationResult.pass();
    };
}
```

---

## Related Documentation

- [Core Concepts](../user-guide/core-concepts.md) - Overview of validation framework
- [Validation Rules](../user-guide/validation-rules.md) - Writing validation rules
- [Extension Methods](../user-guide/extension-methods.md) - Creating extension methods
- [Caching Guide](../user-guide/caching.md) - Caching strategies and patterns
- [Lifecycle Hooks](../user-guide/lifecycle-hooks.md) - Pre/post validation hooks

---

## See Also

- `CacheKeyBuilder` - Building cache keys
- `ValidationResult` - Validation result API
- `ModelProvider` - Model traversal interface
- `ExtensionMethodRegistry` - Extension method management
