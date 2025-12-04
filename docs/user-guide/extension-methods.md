# Extension Methods

**Navigation**: [Documentation Hub](../index.md) > [User Guide](core-concepts.md) > Extension Methods

Extension methods provide a powerful way to define reusable helper functions that can be called from validation rules. They serve a similar purpose to EOL operations in Epsilon, allowing you to encapsulate complex logic and avoid code duplication across validation rules.

## Overview

Extension methods are:
- **Defined once** in a dedicated class annotated with `@ExtensionMethod`
- **Called via** `ctx.call()` from any validation rule
- **Automatically cached** when annotated with `@Cached`
- **Type-safe** with compile-time checking
- **Thread-safe** for parallel validation

## Basic Extension Method

### Defining Extension Methods

Use the `@ExtensionMethod` annotation at the class level to specify which EMF element type the methods extend:

```java
import hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod;
import org.eclipse.emf.ecore.EClass;

@ExtensionMethod(EClass.class)
public class EClassExtensions {
    
    /**
     * Get the class name in uppercase.
     * 
     * @param self the EClass instance (always first parameter)
     * @return uppercase name
     */
    public String getNameUpper(EClass self) {
        return self.getName() != null ? self.getName().toUpperCase() : null;
    }
    
    /**
     * Count the number of attributes in the class.
     * 
     * @param self the EClass instance
     * @return attribute count
     */
    public int getAttributeCount(EClass self) {
        return (int) self.getEStructuralFeatures().stream()
            .filter(f -> f instanceof EAttribute)
            .count();
    }
}
```

**Key Requirements**:
- Class must be annotated with `@ExtensionMethod(ElementType.class)`
- First parameter of each method must be the element type (conventionally named `self`)
- Methods must be `public`
- Class must have a no-args constructor

### Calling Extension Methods

Once registered, extension methods can be called from any validation rule using `ctx.call()`:

```java
@ValidationContext(EClass.class)
public class EClassValidations {
    
    @Constraint(name = "MustHaveAttributes", message = "Class must have attributes")
    public ValidationRule mustHaveAttributes() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            
            // Call extension method via context
            int count = ctx.call(eClass, "getAttributeCount");
            
            return count > 0
                ? ValidationResult.pass()
                : ValidationResult.fail("Class '" + eClass.getName() + 
                    "' has no attributes");
        };
    }
}
```

### Registration

Extension method classes must be registered with the `ExtensionMethodRegistry`:

```java
ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
extensionRegistry.register(EClassExtensions.class);

// Pass to validation context
ValidationContext context = new ValidationContext(
    modelProvider,
    resourceSet,
    extensionRegistry
);
```

## Automatic Caching with @Cached

The `@Cached` annotation enables automatic result caching for expensive computations. Results are cached based on the target object and method arguments, preventing redundant calculations.

### Simple Cached Method

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all attributes including inherited ones.
     * Result is cached per EntityType instance.
     */
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        List<Attribute> attributes = new ArrayList<>(self.getAttributes());
        
        // Recursively collect from supertype
        EntityType superType = self.getSuperType();
        if (superType != null) {
            attributes.addAll(getAllAttributes(superType));
        }
        
        return attributes;
    }
}
```

**How Caching Works**:
1. First call to `ctx.call(entity, "getAllAttributes")` executes the method
2. Result is stored in cache with key: `(entity, "getAllAttributes")`
3. Subsequent calls with the same entity return cached result immediately
4. Cache is cleared after validation completes

### Cached Method with Parameters

When your extension method has additional parameters, the cache key includes those parameters:

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get attributes of a specific type.
     * Cached per (EntityType, AttributeType) combination.
     */
    @Cached
    public List<Attribute> getAttributesOfType(EntityType self, AttributeType type) {
        return self.getAttributes().stream()
            .filter(attr -> attr.getType() == type)
            .collect(Collectors.toList());
    }
}

// Usage in validation rule:
return (element, ctx) -> {
    EntityType entity = (EntityType) element;
    
    // Each type creates a separate cache entry
    List<Attribute> stringAttrs = ctx.call(entity, "getAttributesOfType", AttributeType.STRING);
    List<Attribute> numericAttrs = ctx.call(entity, "getAttributesOfType", AttributeType.NUMERIC);
    
    // ...
};
```

### Cache Performance Benefits

Caching dramatically improves performance for:
- **Recursive traversals** (inheritance hierarchies, graph walking)
- **Expensive computations** (complex aggregations, filtering)
- **Repeated queries** (called multiple times in different validators)

**Example Performance Impact**:
```java
// Without caching: O(n²) for n entities with inheritance
// With caching: O(n) - each entity processed once
@Cached
public List<EntityType> getAllSuperTypes(EntityType self) {
    if (self.getSuperType() == null) {
        return Collections.emptyList();
    }
    
    List<EntityType> supers = new ArrayList<>();
    supers.add(self.getSuperType());
    supers.addAll(getAllSuperTypes(self.getSuperType())); // Recursive call
    
    return supers;
}
```

## Complex Traversal Examples

### Inheritance Hierarchy Traversal

Extension methods excel at navigating complex inheritance structures:

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Collect all supertypes in the inheritance hierarchy.
     */
    @Cached
    public List<EntityType> getAllSuperTypes(EntityType self) {
        List<EntityType> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        
        while (current != null) {
            result.add(current);
            current = current.getSuperType();
        }
        
        return result;
    }
    
    /**
     * Check if this entity inherits from a specific type.
     */
    @Cached
    public boolean inheritsFrom(EntityType self, EntityType ancestorType) {
        return getAllSuperTypes(self).contains(ancestorType);
    }
    
    /**
     * Detect cyclic inheritance.
     */
    @Cached
    public boolean hasCyclicInheritance(EntityType self) {
        Set<EntityType> visited = new HashSet<>();
        EntityType current = self;
        
        while (current != null) {
            if (!visited.add(current)) {
                return true; // Cycle detected
            }
            current = current.getSuperType();
        }
        
        return false;
    }
}
```

### Multi-Level Aggregation

Aggregate data from multiple levels of a model hierarchy:

```java
@ExtensionMethod(Package.class)
public class PackageExtensions {
    
    /**
     * Get all entities including those in subpackages.
     */
    @Cached
    public List<EntityType> getAllEntities(Package self) {
        List<EntityType> entities = new ArrayList<>(self.getEntities());
        
        // Recursively add from subpackages
        for (Package subPackage : self.getSubPackages()) {
            entities.addAll(getAllEntities(subPackage));
        }
        
        return entities;
    }
    
    /**
     * Count total number of entities in package tree.
     */
    @Cached
    public int getTotalEntityCount(Package self) {
        return getAllEntities(self).size();
    }
}
```

### Graph Traversal with Cycle Detection

Navigate complex relationship graphs safely:

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all entities reachable through associations.
     */
    @Cached
    public Set<EntityType> getReachableEntities(EntityType self) {
        return getReachableEntities(self, new HashSet<>());
    }
    
    private Set<EntityType> getReachableEntities(EntityType entity, Set<EntityType> visited) {
        if (!visited.add(entity)) {
            return visited; // Already processed
        }
        
        for (Association assoc : entity.getAssociations()) {
            EntityType target = assoc.getTarget();
            if (target != null) {
                getReachableEntities(target, visited);
            }
        }
        
        return visited;
    }
}
```

## Integration with Validation Rules

### Using Extension Methods in Constraints

Extension methods integrate seamlessly with validation rules:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "NoCyclicInheritance", 
                message = "Entity has cyclic inheritance")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            boolean hasCycle = ctx.call(entity, "hasCyclicInheritance");
            
            if (hasCycle) {
                return ValidationResult.fail(
                    "Entity '" + entity.getName() + "' has cyclic inheritance. " +
                    "Review the inheritance hierarchy."
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(name = "MustHaveUniqueNameInHierarchy",
                message = "Entity name must be unique in inheritance hierarchy")
    public ValidationRule mustHaveUniqueNameInHierarchy() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<EntityType> supers = ctx.call(entity, "getAllSuperTypes");
            
            for (EntityType superType : supers) {
                if (entity.getName().equals(superType.getName())) {
                    return ValidationResult.fail(
                        "Entity '" + entity.getName() + "' has the same name as " +
                        "ancestor '" + superType.getName() + "'"
                    );
                }
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Combining Extension Methods

Chain multiple extension method calls for complex queries:

```java
@Constraint(name = "MustHavePrimaryKey", message = "Entity must have primary key")
public ValidationRule mustHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Get all attributes (including inherited)
        List<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
        
        // Filter to primary key attributes
        boolean hasPK = allAttrs.stream().anyMatch(attr -> 
            ctx.call(attr, "isPrimaryKey") // Another extension method
        );
        
        return hasPK
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity '" + entity.getName() + 
                "' must have at least one primary key attribute");
    };
}
```

## Static Utility Classes vs Instance Extension Methods

Both patterns are valid - choose based on your needs:

### Instance Extension Methods (Recommended)

**Advantages**:
- Automatic caching with `@Cached`
- Called via `ctx.call()` - consistent API
- Can be mocked for testing
- State can be managed if needed

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        // Implementation
    }
}
```

### Static Utility Classes

**Use when**:
- No caching needed
- Simple, stateless operations
- Used outside validation context

```java
public class EntityTypeUtils {
    
    public static boolean isValid(EntityType entity) {
        return entity.getName() != null && !entity.getName().isEmpty();
    }
    
    public static String formatName(EntityType entity) {
        return entity.getName().toUpperCase();
    }
}

// Usage in validation:
return (element, ctx) -> {
    EntityType entity = (EntityType) element;
    
    if (!EntityTypeUtils.isValid(entity)) {
        return ValidationResult.fail("Invalid entity");
    }
    
    return ValidationResult.pass();
};
```

### Hybrid Approach: Extension Methods Delegating to Utilities

Best of both worlds - cached extension methods calling static utilities:

```java
// Static utilities for core logic
public class EsmUtils {
    
    public static List<Attribute> collectAllAttributes(EntityType entity) {
        List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
        
        if (entity.getSuperType() != null) {
            attributes.addAll(collectAllAttributes(entity.getSuperType()));
        }
        
        return attributes;
    }
}

// Extension methods for caching and validation integration
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        // Delegate to utility, but result is cached
        return EsmUtils.collectAllAttributes(self);
    }
}
```

**Benefits**:
- Utilities can be reused outside validation
- Extension methods provide caching layer
- Clear separation: utilities = logic, extensions = integration

## Real-World Example: judo-meta-esm Pattern

The judo-meta-esm project uses a proven pattern combining utilities and extension methods:

```java
// EsmUtils.java - Pure utility methods
public class EsmUtils {
    
    public static Collection<EntityType> getAllSuperTypes(EntityType entityType) {
        if (entityType.getSuperType() == null) {
            return Collections.emptyList();
        }
        
        List<EntityType> supers = new ArrayList<>();
        supers.add(entityType.getSuperType());
        supers.addAll(getAllSuperTypes(entityType.getSuperType()));
        
        return supers;
    }
    
    public static Collection<Attribute> getAllAttributes(EntityType entityType) {
        return Stream.concat(
            entityType.getAttributes().stream(),
            getAllSuperTypes(entityType).stream()
                .flatMap(st -> st.getAttributes().stream())
        ).collect(Collectors.toList());
    }
}

// EntityTypeExtensions.java - Cached extension methods
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) {
        return EsmUtils.getAllSuperTypes(self);
    }
    
    @Cached
    public Collection<Attribute> getAllAttributes(EntityType self) {
        return EsmUtils.getAllAttributes(self);
    }
}

// EntityTypeValidations.java - Using extension methods
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "AttributeNamesMustBeUniqueInHierarchy",
                message = "Attribute names must be unique in inheritance hierarchy")
    public ValidationRule attributeNamesMustBeUniqueInHierarchy() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            Collection<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
            
            Set<String> names = new HashSet<>();
            for (Attribute attr : allAttrs) {
                if (!names.add(attr.getName())) {
                    return ValidationResult.fail(
                        "Duplicate attribute name '" + attr.getName() + 
                        "' in hierarchy of '" + entity.getName() + "'"
                    );
                }
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Best Practices

### 1. Use Descriptive Names

```java
// ❌ Bad: Unclear what it returns
public List<EntityType> get(EntityType self) { }

// ✅ Good: Clear purpose
public List<EntityType> getAllSuperTypes(EntityType self) { }
```

### 2. Cache Expensive Operations

```java
// ❌ Bad: Not cached, expensive traversal
public List<Attribute> getAllAttributes(EntityType self) {
    // Recursive hierarchy traversal
}

// ✅ Good: Cached to prevent redundant traversals
@Cached
public List<Attribute> getAllAttributes(EntityType self) {
    // Recursive hierarchy traversal
}
```

### 3. Document Complex Logic

```java
/**
 * Collects all attributes from this entity and its entire inheritance hierarchy.
 * 
 * <p>Traverses the supertype chain recursively and aggregates attributes at each level.
 * The result includes both direct attributes and inherited attributes.</p>
 * 
 * @param self the entity type to query
 * @return list of all attributes (direct + inherited), may contain duplicates if 
 *         the same attribute is redefined
 */
@Cached
public List<Attribute> getAllAttributes(EntityType self) {
    // Implementation
}
```

### 4. Handle Null Cases Gracefully

```java
@Cached
public List<EntityType> getAllSuperTypes(EntityType self) {
    if (self == null || self.getSuperType() == null) {
        return Collections.emptyList(); // Safe empty list, not null
    }
    
    // Implementation
}
```

### 5. Use Immutable Return Types

```java
// ✅ Good: Return immutable collections to prevent modification
@Cached
public List<Attribute> getAllAttributes(EntityType self) {
    List<Attribute> attributes = new ArrayList<>();
    // ... collect attributes ...
    return Collections.unmodifiableList(attributes);
}
```

## Thread Safety

Extension methods are thread-safe by design:
- Each thread gets its own `ValidationContext` during parallel validation
- Cache uses `ConcurrentHashMap` for thread-safe storage
- Extension method instances are created once and shared (must be stateless)

**Rule**: Extension methods must be stateless or use only thread-safe state.

## Next Steps

- **[Caching Guide](caching.md)** - Deep dive into caching strategies
- **[Best Practices: Extension Delegation](../best-practices/extension-delegation.md)** - Proven patterns
- **[Examples: Inheritance Validations](../examples/inheritance-validations.md)** - Real-world examples

## Related Topics

- [Core Concepts](core-concepts.md) - Understanding ValidationContext API
- [ValidationContext API Reference](../reference/validation-context.md) - Full API documentation
- [Performance Best Practices](../best-practices/performance.md) - Optimization strategies

---

**Previous**: [Caching](caching.md) | **Next**: [Lifecycle Hooks](lifecycle-hooks.md)
