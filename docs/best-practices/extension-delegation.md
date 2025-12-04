# Extension Delegation Pattern

**Navigation**: [Documentation Hub](../index.md) > [Best Practices](../index.md#best-practices) > Extension Delegation

This guide documents the proven static utility class pattern used in production codebases, showing when to use static utilities versus instance extension methods, and how to combine both approaches for maximum flexibility and performance.

## Overview

The extension delegation pattern separates concerns between:
- **Static utility classes** - Pure, stateless logic that can be reused anywhere
- **Extension method classes** - Integration layer providing caching and validation context access
- **Validation rules** - Business rules that call extension methods via `ctx.call()`

This three-layer architecture provides:
- **Reusability** - Utilities can be used outside the validation framework
- **Performance** - Extension methods provide automatic caching
- **Testability** - Each layer can be tested independently
- **Maintainability** - Clear separation of concerns

## Pattern Comparison

### Pattern 1: Instance Extension Methods (Recommended for Validation)

Use instance extension methods when you need caching or integration with the validation framework.

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all attributes including inherited ones.
     * Result is automatically cached per EntityType instance.
     */
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        List<Attribute> attributes = new ArrayList<>(self.getAttributes());
        
        // Recursively collect from supertype
        EntityType superType = self.getSuperType();
        if (superType != null) {
            attributes.addAll(getAllAttributes(superType));
        }
        
        return Collections.unmodifiableList(attributes);
    }
}
```

**Advantages**:
- Automatic caching with `@Cached` annotation
- Called via `ctx.call()` - consistent validation API
- Can be mocked for testing
- Instances can maintain state if needed (rare)

**Use when**:
- Need automatic result caching
- Called from validation rules
- Result depends on expensive traversals
- Integration with ValidationContext is required

### Pattern 2: Static Utility Classes (For Standalone Logic)

Use static utilities for pure logic that doesn't need caching or validation context.

```java
public class EntityTypeUtils {
    
    /**
     * Check if entity name is valid.
     * Simple validation that doesn't need caching.
     */
    public static boolean isValidName(EntityType entity) {
        String name = entity.getName();
        return name != null 
            && !name.isEmpty() 
            && Character.isUpperCase(name.charAt(0));
    }
    
    /**
     * Format entity name for display.
     * Pure formatting logic, no caching needed.
     */
    public static String formatDisplayName(EntityType entity) {
        return entity.getName() != null 
            ? entity.getName().toUpperCase() 
            : "<unnamed>";
    }
    
    /**
     * Get entity namespace prefix.
     * Simple property access, no traversal.
     */
    public static String getNamespacePrefix(EntityType entity) {
        String namespace = entity.getNamespace();
        return namespace != null && namespace.contains(".")
            ? namespace.substring(0, namespace.lastIndexOf('.'))
            : namespace;
    }
}
```

**Advantages**:
- No framework dependency
- Can be used anywhere (tests, utilities, other modules)
- Explicit - no hidden caching or state
- Easy to understand and test

**Use when**:
- Simple, stateless operations
- No caching needed
- Logic needs to be reused outside validation
- Utilities are called from multiple contexts

### Pattern 3: Hybrid Delegation (Best of Both Worlds)

Combine static utilities with extension methods for maximum flexibility.

```java
// ========================================
// Layer 1: Static Utilities (Pure Logic)
// ========================================
public class EntityTypeUtils {
    
    /**
     * Recursively collect all supertypes in the inheritance hierarchy.
     * Pure utility method with no framework dependencies.
     * 
     * @param entity the entity to start from
     * @return collection of all ancestor types
     */
    public static Collection<EntityType> collectAllSuperTypes(EntityType entity) {
        if (entity == null || entity.getSuperType() == null) {
            return Collections.emptyList();
        }
        
        List<EntityType> supers = new ArrayList<>();
        supers.add(entity.getSuperType());
        supers.addAll(collectAllSuperTypes(entity.getSuperType()));
        
        return supers;
    }
    
    /**
     * Recursively collect all attributes from entity and supertypes.
     * Can be used independently of the validation framework.
     * 
     * @param entity the entity to query
     * @return all attributes (direct + inherited)
     */
    public static Collection<Attribute> collectAllAttributes(EntityType entity) {
        if (entity == null) {
            return Collections.emptyList();
        }
        
        return Stream.concat(
            entity.getAttributes().stream(),
            collectAllSuperTypes(entity).stream()
                .flatMap(superType -> superType.getAttributes().stream())
        ).collect(Collectors.toList());
    }
    
    /**
     * Check if entity has cyclic inheritance.
     * Pure algorithm, no framework coupling.
     */
    public static boolean hasCyclicInheritance(EntityType entity) {
        Set<EntityType> visited = new HashSet<>();
        EntityType current = entity;
        
        while (current != null) {
            if (!visited.add(current)) {
                return true; // Cycle detected
            }
            current = current.getSuperType();
        }
        
        return false;
    }
}

// ========================================
// Layer 2: Extension Methods (Caching)
// ========================================
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all supertypes with automatic caching.
     * Delegates to utility but provides cached results.
     */
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) {
        return EntityTypeUtils.collectAllSuperTypes(self);
    }
    
    /**
     * Get all attributes with automatic caching.
     * Delegates to utility but provides cached results.
     */
    @Cached
    public Collection<Attribute> getAllAttributes(EntityType self) {
        return EntityTypeUtils.collectAllAttributes(self);
    }
    
    /**
     * Check cyclic inheritance with caching.
     * Delegates to utility but provides cached results.
     */
    @Cached
    public boolean hasCyclicInheritance(EntityType self) {
        return EntityTypeUtils.hasCyclicInheritance(self);
    }
}

// ========================================
// Layer 3: Validation Rules (Business Logic)
// ========================================
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "NoCyclicInheritance",
        message = "Entity must not have cyclic inheritance"
    )
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            boolean hasCycle = ctx.call(entity, "hasCyclicInheritance");
            
            if (hasCycle) {
                return ValidationResult.fail(
                    "Entity '" + entity.getName() + "' has cyclic inheritance. " +
                    "Review the inheritance hierarchy to remove the cycle."
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "AttributeNamesMustBeUniqueInHierarchy",
        message = "Attribute names must be unique across inheritance hierarchy"
    )
    public ValidationRule attributeNamesMustBeUniqueInHierarchy() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            Collection<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
            
            // Check for duplicate names
            Set<String> names = new HashSet<>();
            for (Attribute attr : allAttrs) {
                if (!names.add(attr.getName())) {
                    return ValidationResult.fail(
                        "Entity '" + entity.getName() + "' has duplicate " +
                        "attribute name '" + attr.getName() + "' in its " +
                        "inheritance hierarchy"
                    );
                }
            }
            
            return ValidationResult.pass();
        };
    }
}
```

**Benefits**:
- **Utilities** are reusable outside validation (tests, generators, other tools)
- **Extension methods** provide caching for expensive operations
- **Validation rules** get simple, clean API via `ctx.call()`
- Each layer can be tested independently
- Complex logic lives in utilities (easy to unit test)
- Caching is transparent (managed by framework)

## Real-World Example: judo-meta-esm Pattern

The judo-meta-esm project demonstrates this pattern at scale. Here's how it structures code:

### EsmUtils.java - Static Utilities

```java
package hu.blackbelt.judo.meta.esm.util;

import hu.blackbelt.judo.meta.esm.*;
import java.util.*;
import java.util.stream.*;

/**
 * Static utility methods for ESM (Entity State Machine) model operations.
 * These utilities provide core logic without framework dependencies.
 */
public class EsmUtils {
    
    /**
     * Collect all supertypes of an entity type.
     * Traverses the inheritance hierarchy from bottom to top.
     * 
     * @param entityType the entity to start from
     * @return collection of all ancestor types (empty if no supertypes)
     */
    public static Collection<EntityType> getAllSuperTypes(EntityType entityType) {
        if (entityType == null || entityType.getSuperType() == null) {
            return Collections.emptyList();
        }
        
        List<EntityType> supers = new ArrayList<>();
        supers.add(entityType.getSuperType());
        supers.addAll(getAllSuperTypes(entityType.getSuperType()));
        
        return supers;
    }
    
    /**
     * Collect all attributes including those inherited from supertypes.
     * Attributes from subtype override same-named attributes from supertypes.
     * 
     * @param entityType the entity to query
     * @return all attributes (direct + inherited)
     */
    public static Collection<Attribute> getAllAttributes(EntityType entityType) {
        if (entityType == null) {
            return Collections.emptyList();
        }
        
        return Stream.concat(
            entityType.getAttributes().stream(),
            getAllSuperTypes(entityType).stream()
                .flatMap(st -> st.getAttributes().stream())
        ).collect(Collectors.toList());
    }
    
    /**
     * Collect all relations including those inherited from supertypes.
     * 
     * @param entityType the entity to query
     * @return all relations (direct + inherited)
     */
    public static Collection<Relation> getAllRelations(EntityType entityType) {
        if (entityType == null) {
            return Collections.emptyList();
        }
        
        return Stream.concat(
            entityType.getRelations().stream(),
            getAllSuperTypes(entityType).stream()
                .flatMap(st -> st.getRelations().stream())
        ).collect(Collectors.toList());
    }
    
    /**
     * Get the fully qualified name of an entity.
     * Combines package namespace with entity name.
     * 
     * @param entityType the entity
     * @return fully qualified name (e.g., "com.example.Customer")
     */
    public static String getFullyQualifiedName(EntityType entityType) {
        if (entityType == null) {
            return null;
        }
        
        String namespace = getNamespace(entityType);
        String name = entityType.getName();
        
        if (namespace != null && !namespace.isEmpty()) {
            return namespace + "." + name;
        }
        
        return name;
    }
    
    /**
     * Get the namespace of an entity (from its containing package).
     * 
     * @param entityType the entity
     * @return namespace or null if not in a package
     */
    public static String getNamespace(EntityType entityType) {
        if (entityType == null || entityType.eContainer() == null) {
            return null;
        }
        
        if (entityType.eContainer() instanceof Package) {
            Package pkg = (Package) entityType.eContainer();
            return pkg.getNamespace();
        }
        
        return null;
    }
    
    /**
     * Check if an entity is mapped to a database table.
     * An entity is mapped if it has a table name annotation.
     * 
     * @param entityType the entity to check
     * @return true if mapped, false otherwise
     */
    public static boolean isMapped(EntityType entityType) {
        if (entityType == null) {
            return false;
        }
        
        // Check for table name annotation
        return entityType.getAnnotations().stream()
            .anyMatch(ann -> "table".equals(ann.getName()));
    }
}
```

### EntityTypeExtensions.java - Cached Extension Methods

```java
package hu.blackbelt.judo.meta.esm.validation.extensions;

import hu.blackbelt.judo.meta.esm.*;
import hu.blackbelt.judo.meta.esm.util.EsmUtils;
import hu.blackbelt.judo.zeta.validation.annotation.Cached;
import hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod;

import java.util.Collection;

/**
 * Extension methods for EntityType validation.
 * These methods delegate to EsmUtils but provide automatic caching.
 */
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all supertypes with caching.
     * Each entity's supertype list is computed once and cached.
     */
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) {
        return EsmUtils.getAllSuperTypes(self);
    }
    
    /**
     * Get all attributes with caching.
     * Expensive operation (recursive) - caching provides significant speedup.
     */
    @Cached
    public Collection<Attribute> getAllAttributes(EntityType self) {
        return EsmUtils.getAllAttributes(self);
    }
    
    /**
     * Get all relations with caching.
     */
    @Cached
    public Collection<Relation> getAllRelations(EntityType self) {
        return EsmUtils.getAllRelations(self);
    }
    
    /**
     * Get fully qualified name with caching.
     */
    @Cached
    public String getFullyQualifiedName(EntityType self) {
        return EsmUtils.getFullyQualifiedName(self);
    }
    
    /**
     * Get namespace with caching.
     */
    @Cached
    public String getNamespace(EntityType self) {
        return EsmUtils.getNamespace(self);
    }
    
    /**
     * Check if mapped with caching.
     */
    @Cached
    public boolean isMapped(EntityType self) {
        return EsmUtils.isMapped(self);
    }
}
```

### EntityTypeValidations.java - Validation Rules

```java
package hu.blackbelt.judo.meta.esm.validation.rules;

import hu.blackbelt.judo.meta.esm.*;
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;

import java.util.*;

/**
 * Validation rules for EntityType elements.
 * Uses extension methods for complex queries.
 */
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityTypeMustHaveName",
        message = "Entity type must have a name"
    )
    public ValidationRule entityTypeMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getName() == null || entity.getName().isEmpty()) {
                return ValidationResult.fail(
                    "Entity type must have a non-empty name"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "EntityTypeNameMustBeUniqueInPackage",
        message = "Entity type name must be unique within package"
    )
    public ValidationRule entityTypeNameMustBeUniqueInPackage() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (!(entity.eContainer() instanceof Package)) {
                return ValidationResult.pass();
            }
            
            Package pkg = (Package) entity.eContainer();
            long count = pkg.getEntityTypes().stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
            
            if (count > 1) {
                return ValidationResult.fail(
                    "Entity type '" + entity.getName() + "' is not unique " +
                    "in package '" + pkg.getName() + "'"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "MappedEntityMustHaveAttributes",
        message = "Mapped entity must have at least one attribute"
    )
    @Guard(method = "isMapped")
    public ValidationRule mappedEntityMustHaveAttributes() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            Collection<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
            
            if (allAttrs.isEmpty()) {
                return ValidationResult.fail(
                    "Mapped entity '" + entity.getName() + "' must have " +
                    "at least one attribute (including inherited attributes)"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Guard method checking if entity is mapped.
     */
    public boolean isMapped(EntityType entity, ValidationContext ctx) {
        // Delegate to cached extension method
        return ctx.call(entity, "isMapped");
    }
    
    @Constraint(
        name = "AttributeNamesMustBeUniqueInHierarchy",
        message = "Attribute names must be unique in inheritance hierarchy"
    )
    public ValidationRule attributeNamesMustBeUniqueInHierarchy() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call cached extension method
            Collection<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
            
            // Find duplicates
            Set<String> seen = new HashSet<>();
            for (Attribute attr : allAttrs) {
                if (!seen.add(attr.getName())) {
                    return ValidationResult.fail(
                        "Entity '" + entity.getName() + "' has duplicate " +
                        "attribute name '" + attr.getName() + "' in its " +
                        "inheritance hierarchy"
                    );
                }
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Code Organization Strategies

### Strategy 1: Co-located Files

Keep utilities and extensions in the same package:

```
src/main/java/
  com/example/model/
    util/
      EntityTypeUtils.java          // Static utilities
      PackageUtils.java
    validation/
      extensions/
        EntityTypeExtensions.java   // Extension methods
        PackageExtensions.java
      rules/
        EntityTypeValidations.java  // Validation rules
        PackageValidations.java
```

**Benefits**:
- Clear separation of concerns
- Easy to find related code
- Utilities can be published as separate module

### Strategy 2: Feature-based Organization

Group by domain feature:

```
src/main/java/
  com/example/entity/
    EntityTypeUtils.java
    EntityTypeExtensions.java
    EntityTypeValidations.java
  com/example/package/
    PackageUtils.java
    PackageExtensions.java
    PackageValidations.java
```

**Benefits**:
- Related code lives together
- Easy to understand feature scope
- Simpler navigation

### Strategy 3: Layer-based Organization

Organize by architectural layer:

```
src/main/java/
  com/example/
    utils/           // Layer 1: Pure utilities
      EntityTypeUtils.java
      PackageUtils.java
    extensions/      // Layer 2: Cached extensions
      EntityTypeExtensions.java
      PackageExtensions.java
    validations/     // Layer 3: Business rules
      EntityTypeValidations.java
      PackageValidations.java
```

**Benefits**:
- Clear architectural boundaries
- Easy to enforce dependencies
- Testability at each layer

## Benefits and Tradeoffs

### Benefits

| Benefit | Description |
|---------|-------------|
| **Reusability** | Utilities can be used in code generators, tests, other tools |
| **Performance** | Extension methods provide transparent caching |
| **Testability** | Each layer can be tested independently |
| **Maintainability** | Clear separation makes code easier to understand |
| **Flexibility** | Can call utilities directly when caching not needed |
| **Framework Independence** | Utilities have no validation framework dependency |

### Tradeoffs

| Tradeoff | Impact | Mitigation |
|----------|--------|------------|
| **More classes** | More files to maintain | Use clear naming conventions |
| **Indirection** | Extension methods add a layer | Keep delegation simple (one-liners) |
| **Duplication** | Method signatures in utils and extensions | Generate extensions from utils |
| **Learning curve** | Developers must understand pattern | Document well, provide examples |

## When to Use Each Pattern

### Use Static Utilities Only

```java
// Simple, stateless operations
public class NameUtils {
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Z][a-zA-Z0-9]*");
    }
}
```

**When**:
- Simple operations (< 5 lines)
- No traversals or recursion
- Called from many contexts
- No caching benefit

### Use Extension Methods Only

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        // Complex logic directly in extension
    }
}
```

**When**:
- Only used in validation
- Caching is essential
- No need to reuse outside framework

### Use Hybrid Delegation

```java
// Utils + Extensions pattern
public class EsmUtils {
    public static List<Attribute> collectAllAttributes(...) { }
}

@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    @Cached
    public List<Attribute> getAllAttributes(EntityType self) {
        return EsmUtils.collectAllAttributes(self);
    }
}
```

**When**:
- Logic is complex (> 10 lines)
- Used in multiple contexts (validation, generation, etc.)
- Caching provides benefit
- Want framework independence

## Testing Strategies

### Testing Static Utilities

```java
public class EntityTypeUtilsTest {
    
    @Test
    public void testCollectAllAttributes() {
        // Create test model
        EntityType entity = EsmFactory.eINSTANCE.createEntityType();
        entity.setName("Customer");
        
        Attribute attr1 = EsmFactory.eINSTANCE.createAttribute();
        attr1.setName("id");
        entity.getAttributes().add(attr1);
        
        // Test utility directly
        Collection<Attribute> attrs = EntityTypeUtils.collectAllAttributes(entity);
        
        assertEquals(1, attrs.size());
        assertTrue(attrs.contains(attr1));
    }
}
```

### Testing Extension Methods

```java
public class EntityTypeExtensionsTest {
    
    private ExtensionMethodRegistry registry;
    private ValidationContext context;
    
    @Before
    public void setup() {
        registry = new ExtensionMethodRegistry();
        registry.register(EntityTypeExtensions.class);
        
        context = new ValidationContext(
            modelProvider,
            resourceSet,
            registry
        );
    }
    
    @Test
    public void testGetAllAttributesCaching() {
        EntityType entity = createTestEntity();
        
        // First call
        Collection<Attribute> attrs1 = context.call(entity, "getAllAttributes");
        
        // Second call should return cached result
        Collection<Attribute> attrs2 = context.call(entity, "getAllAttributes");
        
        assertSame(attrs1, attrs2); // Same instance = cached
    }
}
```

## Best Practices

### 1. Keep Utilities Pure

```java
// ✅ Good: Pure, stateless utility
public static Collection<Attribute> getAllAttributes(EntityType entity) {
    return collectAttributes(entity);
}

// ❌ Bad: Utility with hidden state
private static Map<EntityType, Collection<Attribute>> cache = new HashMap<>();
public static Collection<Attribute> getAllAttributes(EntityType entity) {
    return cache.computeIfAbsent(entity, EsmUtils::collectAttributes);
}
```

### 2. Extension Methods Should Delegate Only

```java
// ✅ Good: Simple delegation
@Cached
public Collection<Attribute> getAllAttributes(EntityType self) {
    return EsmUtils.collectAllAttributes(self);
}

// ❌ Bad: Complex logic in extension method
@Cached
public Collection<Attribute> getAllAttributes(EntityType self) {
    List<Attribute> attrs = new ArrayList<>(self.getAttributes());
    // 50 lines of complex logic...
    return attrs;
}
```

### 3. Document the Pattern

```java
/**
 * Extension methods for EntityType validation.
 * 
 * <p>These methods delegate to {@link EsmUtils} for core logic but provide
 * automatic caching for performance. Use these methods from validation rules
 * via {@code ctx.call()}. Use EsmUtils directly when caching is not needed.</p>
 * 
 * @see EsmUtils
 * @see EntityTypeValidations
 */
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions { }
```

## Next Steps

- **[Extension Methods Guide](../user-guide/extension-methods.md)** - Complete extension method documentation
- **[Performance Best Practices](performance.md)** - Optimization strategies
- **[Caching Guide](../user-guide/caching.md)** - Deep dive into caching behavior

## Related Topics

- [Guard Methods](guard-methods.md) - Using extension methods in guards
- [Constants](constants.md) - Naming conventions for methods
- [Core Concepts](../user-guide/core-concepts.md) - ValidationContext and calling extension methods

---

**Previous**: [Constants](constants.md) | **Next**: [Guard Methods](guard-methods.md)
