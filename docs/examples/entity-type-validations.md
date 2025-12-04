# EntityType Validation Examples

**Navigation**: [Documentation Hub](../index.md) > [Examples](../index.md#examples) > EntityType Validations

This guide provides comprehensive, real-world validation examples for EntityType elements, inspired by patterns from judo-meta-esm. Each example demonstrates complete validation rule implementation with guards, dependencies, and context-aware error messages.

## Table of Contents

1. [Overview](#overview)
2. [Name Uniqueness Validation](#name-uniqueness-validation)
3. [Entity Mapping Validation](#entity-mapping-validation)
4. [Abstract Entity Validation](#abstract-entity-validation)
5. [Concrete Entity Table Validation](#concrete-entity-table-validation)
6. [Entity Attributes Validation](#entity-attributes-validation)
7. [Complete Validation Class](#complete-validation-class)
8. [Testing Validations](#testing-validations)

## Overview

EntityType validation is critical for ensuring model integrity in domain modeling systems. These examples demonstrate:

- **Context-aware validation**: Using `ctx.getAllInstances()` for cross-element checks
- **Conditional validation**: Guards that skip validation based on element state
- **Dependency management**: Rules that depend on other rules passing first
- **Clear error messages**: Actionable feedback with element context

### Prerequisites

```xml
<!-- Maven dependency -->
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>hu.blackbelt.judo.zeta.validation-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Common Imports

All examples assume these imports:

```java
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;
import java.util.*;
import java.util.stream.Collectors;
```

## Name Uniqueness Validation

Entity names must be unique within their container (package or model), using case-insensitive comparison for better user experience.

### Basic Name Uniqueness

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Critique: Entity names should be unique (case-insensitive).
     * 
     * This validation:
     * - Uses getAllInstances() to get all entities in the model
     * - Performs case-insensitive comparison
     * - Reports all duplicates with their count
     * - Uses @Critique (warning) instead of @Constraint (error)
     */
    @Critique(
        name = "EntityNameShouldBeUnique",
        message = "Entity name should be unique (case-insensitive)"
    )
    public ValidationRule entityNameShouldBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            // Skip if name is null (other validation will catch this)
            if (name == null || name.isEmpty()) {
                return ValidationResult.pass();
            }
            
            // Get all instances of EntityType
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            // Count entities with same name (case-insensitive)
            long duplicateCount = allEntities.stream()
                .filter(e -> e.getName() != null)
                .filter(e -> e.getName().equalsIgnoreCase(name))
                .count();
            
            if (duplicateCount > 1) {
                // Build list of duplicate locations for better diagnostics
                String locations = allEntities.stream()
                    .filter(e -> e.getName() != null)
                    .filter(e -> e.getName().equalsIgnoreCase(name))
                    .map(e -> getEntityLocation(e))
                    .collect(Collectors.joining(", "));
                
                return ValidationResult.warn(
                    "EntityNameShouldBeUnique",
                    "Entity name '" + name + "' is used " + duplicateCount + 
                    " times (case-insensitive). Locations: " + locations + 
                    ". Consider using unique names to avoid confusion.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Helper method to get entity location for diagnostics
     */
    private String getEntityLocation(EntityType entity) {
        EObject container = entity.eContainer();
        if (container != null && container instanceof Package) {
            return ((Package) container).getName() + "." + entity.getName();
        }
        return entity.getName();
    }
}
```

### Optimized Name Uniqueness with Caching

For large models, cache the name index to avoid repeated scans:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Optimized uniqueness check using caching.
     * 
     * This version:
     * - Builds a name index once and caches it
     * - Reuses the index for all entity validations
     * - Significantly faster for models with 1000+ entities
     */
    @Critique(
        name = "EntityNameShouldBeUnique",
        message = "Entity name should be unique"
    )
    public ValidationRule entityNameShouldBeUniqueOptimized() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            if (name == null || name.isEmpty()) {
                return ValidationResult.pass();
            }
            
            // Build and cache name index (case-insensitive)
            CacheKey cacheKey = CacheKey.of("entity-name-index-ci");
            Map<String, List<EntityType>> nameIndex = 
                (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
            
            if (nameIndex == null) {
                nameIndex = ctx.getAllInstances(EntityType.class).stream()
                    .filter(e -> e.getName() != null)
                    .collect(Collectors.groupingBy(
                        e -> e.getName().toLowerCase()
                    ));
                ctx.putCached(cacheKey, nameIndex);
            }
            
            // Check for duplicates using the index
            List<EntityType> duplicates = nameIndex.get(name.toLowerCase());
            
            if (duplicates != null && duplicates.size() > 1) {
                return ValidationResult.warn(
                    "EntityNameShouldBeUnique",
                    "Entity name '" + name + "' is used " + duplicates.size() + 
                    " times. Each entity should have a unique name.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Package-Scoped Name Uniqueness

More strict validation: names must be unique within the same package:

```java
@Critique(
    name = "EntityNameUniqueInPackage",
    message = "Entity name must be unique within package"
)
public ValidationRule entityNameUniqueInPackage() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        EObject container = entity.eContainer();
        
        // Skip if name is null or no container
        if (name == null || name.isEmpty() || container == null) {
            return ValidationResult.pass();
        }
        
        // Only validate if container is a Package
        if (!(container instanceof Package)) {
            return ValidationResult.pass();
        }
        
        Package pkg = (Package) container;
        
        // Count entities with same name in the same package
        long duplicateCount = pkg.getEntities().stream()
            .filter(e -> e.getName() != null)
            .filter(e -> e.getName().equalsIgnoreCase(name))
            .count();
        
        if (duplicateCount > 1) {
            return ValidationResult.warn(
                "EntityNameUniqueInPackage",
                "Entity name '" + name + "' is used " + duplicateCount + 
                " times in package '" + pkg.getName() + "'. " +
                "Each entity in a package should have a unique name.",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Entity Mapping Validation

Non-abstract entities must have a mapping to a persistence layer (database table, document, etc.).

### Basic Mapping Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Guard: Only validate concrete (non-abstract) entities
     */
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        EntityType entity = (EntityType) element;
        return !entity.isAbstract();
    }
    
    /**
     * Constraint: Concrete entity must have mapping.
     * 
     * This validation:
     * - Uses guard to skip abstract entities
     * - Checks if mapping is defined
     * - Provides actionable error message
     */
    @Guard(method = "isConcrete")
    @Constraint(
        name = "EntityMustHaveMapping",
        message = "Concrete entity must have mapping"
    )
    public ValidationRule entityMustHaveMapping() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check if mapping exists
            if (entity.getMapping() == null) {
                return ValidationResult.fail(
                    "EntityMustHaveMapping",
                    "Concrete entity '" + entity.getName() + "' must have a mapping. " +
                    "Set the mapping property or mark the entity as abstract.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Mapping Reference Validation

Ensure the mapping references a valid target:

```java
/**
 * Constraint: Entity mapping must reference existing table.
 * Depends on: EntityMustHaveMapping
 * 
 * This validation:
 * - Only runs after EntityMustHaveMapping passes
 * - Cross-references mapping target
 * - Validates the mapping target exists
 */
@Guard(method = "isConcrete")
@Satisfies(constraints = {"EntityMustHaveMapping"})
@Constraint(
    name = "EntityMappingMustReferenceExistingTable",
    message = "Entity mapping must reference existing table"
)
public ValidationRule entityMappingMustReferenceExistingTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        EntityMapping mapping = entity.getMapping();
        
        if (mapping == null) {
            // Should not happen due to @Satisfies dependency
            return ValidationResult.pass();
        }
        
        String tableName = mapping.getTableName();
        
        if (tableName == null || tableName.isEmpty()) {
            return ValidationResult.fail(
                "EntityMappingMustReferenceExistingTable",
                "Entity '" + entity.getName() + "' mapping must specify a table name.",
                Severity.ERROR,
                element
            );
        }
        
        // Get all table definitions from the model
        List<TableDefinition> allTables = ctx.getAllInstances(TableDefinition.class);
        
        boolean tableExists = allTables.stream()
            .anyMatch(table -> tableName.equals(table.getName()));
        
        if (!tableExists) {
            return ValidationResult.fail(
                "EntityMappingMustReferenceExistingTable",
                "Entity '" + entity.getName() + "' references table '" + 
                tableName + "' which does not exist in the model. " +
                "Available tables: " + getAllTableNames(allTables),
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Helper method to build comma-separated list of table names
 */
private String getAllTableNames(List<TableDefinition> tables) {
    if (tables.isEmpty()) {
        return "(none defined)";
    }
    
    return tables.stream()
        .map(TableDefinition::getName)
        .sorted()
        .limit(10)  // Limit to first 10 to avoid huge messages
        .collect(Collectors.joining(", "));
}
```

## Abstract Entity Validation

Abstract entities serve as base classes and must have abstract operations.

### Abstract Entity Operations Check

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Guard: Only validate abstract entities
     */
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        EntityType entity = (EntityType) element;
        return entity.isAbstract();
    }
    
    /**
     * Critique: Abstract entity should have abstract operations.
     * 
     * This validation:
     * - Uses guard to only check abstract entities
     * - Warns if abstract entity has no abstract operations
     * - Suggests marking entity as concrete if no abstract operations
     */
    @Guard(method = "isAbstract")
    @Critique(
        name = "AbstractEntityShouldHaveAbstractOperations",
        message = "Abstract entity should define abstract operations"
    )
    public ValidationRule abstractEntityShouldHaveAbstractOperations() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check if entity has any abstract operations
            boolean hasAbstractOperations = entity.getOperations().stream()
                .anyMatch(op -> op.getOperationType() == OperationType.ABSTRACT);
            
            if (!hasAbstractOperations) {
                return ValidationResult.warn(
                    "AbstractEntityShouldHaveAbstractOperations",
                    "Abstract entity '" + entity.getName() + "' has no abstract operations. " +
                    "Consider marking it as concrete if it doesn't define any abstract behavior, " +
                    "or add abstract operations to define the contract for subclasses.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Abstract Entity Cannot Have Table

```java
/**
 * Constraint: Abstract entity cannot have table mapping.
 * 
 * This validation:
 * - Ensures abstract entities don't have direct table mappings
 * - Abstract entities should only define structure
 * - Concrete subclasses provide the actual mapping
 */
@Guard(method = "isAbstract")
@Constraint(
    name = "AbstractEntityCannotHaveTable",
    message = "Abstract entity cannot have table mapping"
)
public ValidationRule abstractEntityCannotHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getTableName() != null && !entity.getTableName().isEmpty()) {
            return ValidationResult.fail(
                "AbstractEntityCannotHaveTable",
                "Abstract entity '" + entity.getName() + "' cannot have table name '" + 
                entity.getTableName() + "'. Abstract entities should not be mapped to tables. " +
                "Remove the table name or mark the entity as concrete.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Abstract Entity Inheritance Chain

```java
/**
 * Critique: Deep abstract inheritance chain may indicate design issue.
 * 
 * This validation:
 * - Warns about inheritance chains deeper than recommended
 * - Helps identify over-engineered hierarchies
 * - Suggests refactoring for maintainability
 */
@Guard(method = "isAbstract")
@Critique(
    name = "AbstractEntityInheritanceDepth",
    message = "Abstract entity inheritance depth should be reasonable"
)
public ValidationRule abstractEntityInheritanceDepth() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        int depth = calculateInheritanceDepth(entity);
        
        final int MAX_RECOMMENDED_DEPTH = 5;
        
        if (depth > MAX_RECOMMENDED_DEPTH) {
            String chain = buildInheritanceChain(entity);
            return ValidationResult.warn(
                "AbstractEntityInheritanceDepth",
                "Abstract entity '" + entity.getName() + "' has inheritance depth of " + 
                depth + " (chain: " + chain + "). " +
                "Consider flattening the hierarchy for better maintainability " +
                "(recommended: < " + MAX_RECOMMENDED_DEPTH + " levels).",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Calculate inheritance depth (distance from root)
 */
private int calculateInheritanceDepth(EntityType entity) {
    int depth = 0;
    EntityType current = entity.getSuperType();
    
    while (current != null) {
        depth++;
        current = current.getSuperType();
    }
    
    return depth;
}

/**
 * Build inheritance chain string for diagnostics
 */
private String buildInheritanceChain(EntityType entity) {
    List<String> chain = new ArrayList<>();
    EntityType current = entity;
    
    while (current != null) {
        chain.add(current.getName());
        current = current.getSuperType();
    }
    
    return String.join(" → ", chain);
}
```

## Concrete Entity Table Validation

Concrete entities must have table mappings with valid table names.

### Table Name Required

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Constraint: Concrete entity must have table name.
     * 
     * This validation:
     * - Only applies to concrete (non-abstract) entities
     * - Ensures table name is specified
     * - Provides actionable guidance
     */
    @Guard(method = "isConcrete")
    @Constraint(
        name = "ConcreteEntityMustHaveTableName",
        message = "Concrete entity must have table name"
    )
    public ValidationRule concreteEntityMustHaveTableName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String tableName = entity.getTableName();
            
            if (tableName == null || tableName.isEmpty()) {
                return ValidationResult.fail(
                    "ConcreteEntityMustHaveTableName",
                    "Concrete entity '" + entity.getName() + "' must have a table name. " +
                    "Set the tableName property or mark the entity as abstract.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Valid SQL Identifier

```java
/**
 * Constraint: Table name must be valid SQL identifier.
 * Depends on: ConcreteEntityMustHaveTableName
 * 
 * This validation:
 * - Checks SQL identifier rules
 * - Detects reserved words
 * - Validates length constraints
 */
@Guard(method = "isConcrete")
@Satisfies(constraints = {"ConcreteEntityMustHaveTableName"})
@Constraint(
    name = "TableNameMustBeValidSqlIdentifier",
    message = "Table name must be valid SQL identifier"
)
public ValidationRule tableNameMustBeValidSqlIdentifier() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String tableName = entity.getTableName();
        
        if (tableName == null || tableName.isEmpty()) {
            // Should not happen due to @Satisfies dependency
            return ValidationResult.pass();
        }
        
        // Check SQL identifier rules
        List<String> violations = new ArrayList<>();
        
        // Must start with letter or underscore
        if (!Character.isLetter(tableName.charAt(0)) && tableName.charAt(0) != '_') {
            violations.add("must start with letter or underscore");
        }
        
        // Can only contain letters, digits, underscores
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            violations.add("can only contain letters, digits, and underscores");
        }
        
        // Check against SQL reserved words
        if (isSqlReservedWord(tableName)) {
            violations.add("'" + tableName + "' is a SQL reserved word");
        }
        
        // Check length (varies by database, using conservative limit)
        final int MAX_SQL_IDENTIFIER_LENGTH = 64;
        if (tableName.length() > MAX_SQL_IDENTIFIER_LENGTH) {
            violations.add("exceeds maximum length of " + MAX_SQL_IDENTIFIER_LENGTH + 
                          " characters (current: " + tableName.length() + ")");
        }
        
        if (!violations.isEmpty()) {
            return ValidationResult.fail(
                "TableNameMustBeValidSqlIdentifier",
                "Table name '" + tableName + "' for entity '" + entity.getName() + 
                "' is not a valid SQL identifier: " + String.join(", ", violations) + ".",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Check if identifier is a SQL reserved word
 */
private boolean isSqlReservedWord(String identifier) {
    // Common SQL reserved words (subset for demonstration)
    Set<String> reservedWords = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER",
        "TABLE", "INDEX", "VIEW", "FROM", "WHERE", "JOIN", "ON", "AS",
        "ORDER", "GROUP", "BY", "HAVING", "UNION", "ALL", "DISTINCT",
        "COUNT", "SUM", "AVG", "MIN", "MAX", "NULL", "NOT", "AND", "OR",
        "IN", "EXISTS", "LIKE", "BETWEEN", "IS", "CASE", "WHEN", "THEN"
    );
    
    return reservedWords.contains(identifier.toUpperCase());
}
```

### Table Name Uniqueness

```java
/**
 * Constraint: Table names must be unique across all entities.
 * Depends on: ConcreteEntityMustHaveTableName
 * 
 * This validation:
 * - Ensures no table name conflicts
 * - Uses cached index for performance
 * - Reports all entities using the same table
 */
@Guard(method = "isConcrete")
@Satisfies(constraints = {"ConcreteEntityMustHaveTableName"})
@Constraint(
    name = "TableNameMustBeUnique",
    message = "Table name must be unique"
)
public ValidationRule tableNameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String tableName = entity.getTableName();
        
        if (tableName == null || tableName.isEmpty()) {
            return ValidationResult.pass();
        }
        
        // Build and cache table name index
        CacheKey cacheKey = CacheKey.of("table-name-index");
        Map<String, List<EntityType>> tableIndex = 
            (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
        
        if (tableIndex == null) {
            tableIndex = ctx.getAllInstances(EntityType.class).stream()
                .filter(e -> !e.isAbstract())
                .filter(e -> e.getTableName() != null && !e.getTableName().isEmpty())
                .collect(Collectors.groupingBy(EntityType::getTableName));
            ctx.putCached(cacheKey, tableIndex);
        }
        
        // Check for duplicates
        List<EntityType> entitiesUsingTable = tableIndex.get(tableName);
        
        if (entitiesUsingTable != null && entitiesUsingTable.size() > 1) {
            String entityNames = entitiesUsingTable.stream()
                .map(EntityType::getName)
                .collect(Collectors.joining(", "));
            
            return ValidationResult.fail(
                "TableNameMustBeUnique",
                "Table name '" + tableName + "' is used by " + 
                entitiesUsingTable.size() + " entities: " + entityNames + ". " +
                "Each entity must map to a unique table.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Entity Attributes Validation

Validate that entities have proper attribute definitions.

### Entity Must Have Attributes

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    /**
     * Critique: Concrete entity should have attributes.
     * 
     * This validation:
     * - Warns if concrete entity has no attributes
     * - Skips abstract entities (they may only define structure)
     * - Suggests adding attributes or marking as abstract
     */
    @Guard(method = "isConcrete")
    @Critique(
        name = "ConcreteEntityShouldHaveAttributes",
        message = "Concrete entity should have attributes"
    )
    public ValidationRule concreteEntityShouldHaveAttributes() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getAttributes().isEmpty()) {
                return ValidationResult.warn(
                    "ConcreteEntityShouldHaveAttributes",
                    "Concrete entity '" + entity.getName() + "' has no attributes. " +
                    "Consider adding attributes to store data, or mark as abstract " +
                    "if this is a base class.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Primary Key Validation

```java
/**
 * Constraint: Concrete entity must have primary key.
 * 
 * This validation:
 * - Ensures concrete entities can be uniquely identified
 * - Checks for at least one primary key attribute
 * - Considers inherited attributes
 */
@Guard(method = "isConcrete")
@Critique(
    name = "ConcreteEntityShouldHavePrimaryKey",
    message = "Concrete entity should have primary key"
)
public ValidationRule concreteEntityShouldHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Get all attributes including inherited
        List<Attribute> allAttributes = getAllAttributes(entity);
        
        boolean hasPrimaryKey = allAttributes.stream()
            .anyMatch(attr -> attr.isPrimaryKey());
        
        if (!hasPrimaryKey) {
            return ValidationResult.warn(
                "ConcreteEntityShouldHavePrimaryKey",
                "Concrete entity '" + entity.getName() + "' has no primary key attribute. " +
                "Add a primary key attribute to uniquely identify instances, " +
                "or inherit from an entity that defines a primary key.",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Get all attributes including inherited from supertype hierarchy
 */
private List<Attribute> getAllAttributes(EntityType entity) {
    List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
    
    EntityType current = entity.getSuperType();
    while (current != null) {
        attributes.addAll(current.getAttributes());
        current = current.getSuperType();
    }
    
    return attributes;
}
```

### Attribute Name Conflicts

```java
/**
 * Constraint: Entity attributes must have unique names.
 * 
 * This validation:
 * - Checks for duplicate attribute names within entity
 * - Uses case-sensitive comparison (following Java conventions)
 * - Reports all duplicate names at once
 */
@Constraint(
    name = "EntityAttributeNamesMustBeUnique",
    message = "Entity attribute names must be unique"
)
public ValidationRule entityAttributeNamesMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Group attributes by name
        Map<String, List<Attribute>> nameGroups = entity.getAttributes().stream()
            .filter(attr -> attr.getName() != null)
            .collect(Collectors.groupingBy(Attribute::getName));
        
        // Find duplicates
        List<String> duplicateNames = nameGroups.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        if (!duplicateNames.isEmpty()) {
            return ValidationResult.fail(
                "EntityAttributeNamesMustBeUnique",
                "Entity '" + entity.getName() + "' has duplicate attribute names: " + 
                String.join(", ", duplicateNames) + ". " +
                "Each attribute must have a unique name within the entity.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Complete Validation Class

Here's a complete validation class combining all the patterns above:

```java
package com.example.model.validation;

import com.example.model.*;
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Comprehensive validation rules for EntityType elements.
 * 
 * Validates:
 * - Name uniqueness (case-insensitive)
 * - Entity mappings for concrete entities
 * - Abstract entity constraints
 * - Table name validity and uniqueness
 * - Attribute presence and uniqueness
 * 
 * Demonstrates:
 * - Using ctx.getAllInstances() for cross-element validation
 * - Guards for conditional validation
 * - @Satisfies dependencies between rules
 * - Caching for performance optimization
 * - Clear, actionable error messages
 */
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // ========================================================================
    // Guard Methods
    // ========================================================================
    
    /**
     * Guard: Only validate concrete (non-abstract) entities
     */
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
    
    /**
     * Guard: Only validate abstract entities
     */
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EntityType) element).isAbstract();
    }
    
    // ========================================================================
    // Name Validation Rules
    // ========================================================================
    
    @Critique(
        name = "EntityNameShouldBeUnique",
        message = "Entity name should be unique (case-insensitive)"
    )
    public ValidationRule entityNameShouldBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            if (name == null || name.isEmpty()) {
                return ValidationResult.pass();
            }
            
            // Build and cache name index (case-insensitive)
            CacheKey cacheKey = CacheKey.of("entity-name-index-ci");
            Map<String, List<EntityType>> nameIndex = 
                (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
            
            if (nameIndex == null) {
                nameIndex = ctx.getAllInstances(EntityType.class).stream()
                    .filter(e -> e.getName() != null)
                    .collect(Collectors.groupingBy(
                        e -> e.getName().toLowerCase()
                    ));
                ctx.putCached(cacheKey, nameIndex);
            }
            
            List<EntityType> duplicates = nameIndex.get(name.toLowerCase());
            
            if (duplicates != null && duplicates.size() > 1) {
                return ValidationResult.warn(
                    "EntityNameShouldBeUnique",
                    "Entity name '" + name + "' is used " + duplicates.size() + 
                    " times (case-insensitive). Each entity should have a unique name.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // Mapping Validation Rules (Concrete Entities)
    // ========================================================================
    
    @Guard(method = "isConcrete")
    @Constraint(
        name = "EntityMustHaveMapping",
        message = "Concrete entity must have mapping"
    )
    public ValidationRule entityMustHaveMapping() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getMapping() == null) {
                return ValidationResult.fail(
                    "EntityMustHaveMapping",
                    "Concrete entity '" + entity.getName() + "' must have a mapping. " +
                    "Set the mapping property or mark the entity as abstract.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // Abstract Entity Validation Rules
    // ========================================================================
    
    @Guard(method = "isAbstract")
    @Critique(
        name = "AbstractEntityShouldHaveAbstractOperations",
        message = "Abstract entity should define abstract operations"
    )
    public ValidationRule abstractEntityShouldHaveAbstractOperations() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            boolean hasAbstractOperations = entity.getOperations().stream()
                .anyMatch(op -> op.getOperationType() == OperationType.ABSTRACT);
            
            if (!hasAbstractOperations) {
                return ValidationResult.warn(
                    "AbstractEntityShouldHaveAbstractOperations",
                    "Abstract entity '" + entity.getName() + "' has no abstract operations. " +
                    "Consider marking it as concrete if it doesn't define abstract behavior.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Guard(method = "isAbstract")
    @Constraint(
        name = "AbstractEntityCannotHaveTable",
        message = "Abstract entity cannot have table mapping"
    )
    public ValidationRule abstractEntityCannotHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getTableName() != null && !entity.getTableName().isEmpty()) {
                return ValidationResult.fail(
                    "AbstractEntityCannotHaveTable",
                    "Abstract entity '" + entity.getName() + "' cannot have table name '" + 
                    entity.getTableName() + "'. Remove the table name or mark as concrete.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // Table Validation Rules (Concrete Entities)
    // ========================================================================
    
    @Guard(method = "isConcrete")
    @Constraint(
        name = "ConcreteEntityMustHaveTableName",
        message = "Concrete entity must have table name"
    )
    public ValidationRule concreteEntityMustHaveTableName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String tableName = entity.getTableName();
            
            if (tableName == null || tableName.isEmpty()) {
                return ValidationResult.fail(
                    "ConcreteEntityMustHaveTableName",
                    "Concrete entity '" + entity.getName() + "' must have a table name. " +
                    "Set the tableName property or mark the entity as abstract.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Guard(method = "isConcrete")
    @Satisfies(constraints = {"ConcreteEntityMustHaveTableName"})
    @Constraint(
        name = "TableNameMustBeUnique",
        message = "Table name must be unique"
    )
    public ValidationRule tableNameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String tableName = entity.getTableName();
            
            if (tableName == null || tableName.isEmpty()) {
                return ValidationResult.pass();
            }
            
            // Build and cache table name index
            CacheKey cacheKey = CacheKey.of("table-name-index");
            Map<String, List<EntityType>> tableIndex = 
                (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
            
            if (tableIndex == null) {
                tableIndex = ctx.getAllInstances(EntityType.class).stream()
                    .filter(e -> !e.isAbstract())
                    .filter(e -> e.getTableName() != null && !e.getTableName().isEmpty())
                    .collect(Collectors.groupingBy(EntityType::getTableName));
                ctx.putCached(cacheKey, tableIndex);
            }
            
            List<EntityType> entitiesUsingTable = tableIndex.get(tableName);
            
            if (entitiesUsingTable != null && entitiesUsingTable.size() > 1) {
                String entityNames = entitiesUsingTable.stream()
                    .map(EntityType::getName)
                    .collect(Collectors.joining(", "));
                
                return ValidationResult.fail(
                    "TableNameMustBeUnique",
                    "Table name '" + tableName + "' is used by " + 
                    entitiesUsingTable.size() + " entities: " + entityNames + ". " +
                    "Each entity must map to a unique table.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // Attribute Validation Rules
    // ========================================================================
    
    @Guard(method = "isConcrete")
    @Critique(
        name = "ConcreteEntityShouldHaveAttributes",
        message = "Concrete entity should have attributes"
    )
    public ValidationRule concreteEntityShouldHaveAttributes() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getAttributes().isEmpty()) {
                return ValidationResult.warn(
                    "ConcreteEntityShouldHaveAttributes",
                    "Concrete entity '" + entity.getName() + "' has no attributes. " +
                    "Consider adding attributes or marking as abstract.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "EntityAttributeNamesMustBeUnique",
        message = "Entity attribute names must be unique"
    )
    public ValidationRule entityAttributeNamesMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            Map<String, List<Attribute>> nameGroups = entity.getAttributes().stream()
                .filter(attr -> attr.getName() != null)
                .collect(Collectors.groupingBy(Attribute::getName));
            
            List<String> duplicateNames = nameGroups.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
            if (!duplicateNames.isEmpty()) {
                return ValidationResult.fail(
                    "EntityAttributeNamesMustBeUnique",
                    "Entity '" + entity.getName() + "' has duplicate attribute names: " + 
                    String.join(", ", duplicateNames) + ". " +
                    "Each attribute must have a unique name.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Get all attributes including inherited from supertype hierarchy
     */
    private List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
        
        EntityType current = entity.getSuperType();
        while (current != null) {
            attributes.addAll(current.getAttributes());
            current = current.getSuperType();
        }
        
        return attributes;
    }
}
```

## Testing Validations

Example test class demonstrating how to test these validations:

```java
package com.example.model.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import hu.blackbelt.judo.zeta.validation.core.*;
import com.example.model.*;

import java.util.List;

public class EntityTypeValidationsTest {
    
    private ValidationRegistry registry;
    private ValidationExecutor executor;
    
    @BeforeEach
    public void setUp() {
        registry = new ValidationRegistry();
        registry.register(EntityTypeValidations.class);
        
        executor = ValidationExecutor.builder()
            .registry(registry)
            .build();
    }
    
    @Test
    @DisplayName("Entity name uniqueness validation catches duplicates")
    public void testEntityNameUniqueness() {
        // Create entities with duplicate names (different case)
        EntityType entity1 = createEntity("Customer", false);
        EntityType entity2 = createEntity("CUSTOMER", false);
        
        List<ValidationResult> results = executor.validate(List.of(entity1, entity2));
        
        // Should have warnings for both entities
        long warningCount = results.stream()
            .filter(r -> r.getConstraintName().equals("EntityNameShouldBeUnique"))
            .filter(r -> r.getSeverity() == Severity.WARNING)
            .count();
        
        assertEquals(2, warningCount, "Both entities should have uniqueness warnings");
    }
    
    @Test
    @DisplayName("Concrete entity without mapping fails validation")
    public void testConcreteEntityMustHaveMapping() {
        EntityType entity = createEntity("Customer", false);
        entity.setMapping(null); // No mapping
        
        List<ValidationResult> results = executor.validate(List.of(entity));
        
        ValidationResult result = findResult(results, "EntityMustHaveMapping");
        assertNotNull(result, "Should have EntityMustHaveMapping result");
        assertFalse(result.isValid(), "Should fail without mapping");
        assertEquals(Severity.ERROR, result.getSeverity());
    }
    
    @Test
    @DisplayName("Abstract entity with table name fails validation")
    public void testAbstractEntityCannotHaveTable() {
        EntityType entity = createEntity("BaseEntity", true);
        entity.setTableName("base_entity"); // Abstract shouldn't have table
        
        List<ValidationResult> results = executor.validate(List.of(entity));
        
        ValidationResult result = findResult(results, "AbstractEntityCannotHaveTable");
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("cannot have table name"));
    }
    
    @Test
    @DisplayName("Concrete entity without table name fails validation")
    public void testConcreteEntityMustHaveTableName() {
        EntityType entity = createEntity("Customer", false);
        entity.setTableName(null); // No table name
        
        List<ValidationResult> results = executor.validate(List.of(entity));
        
        ValidationResult result = findResult(results, "ConcreteEntityMustHaveTableName");
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("must have a table name"));
    }
    
    @Test
    @DisplayName("Valid concrete entity passes all validations")
    public void testValidConcreteEntity() {
        EntityType entity = createEntity("Customer", false);
        entity.setTableName("customers");
        entity.setMapping(createMapping("customers"));
        entity.getAttributes().add(createAttribute("id", "Integer", true));
        entity.getAttributes().add(createAttribute("name", "String", false));
        
        List<ValidationResult> results = executor.validate(List.of(entity));
        
        // All constraint results should pass
        long failureCount = results.stream()
            .filter(r -> r.getSeverity() == Severity.ERROR)
            .filter(r -> !r.isValid())
            .count();
        
        assertEquals(0, failureCount, "Valid entity should have no errors");
    }
    
    // Helper methods
    
    private EntityType createEntity(String name, boolean isAbstract) {
        EntityType entity = ModelFactory.eINSTANCE.createEntityType();
        entity.setName(name);
        entity.setAbstract(isAbstract);
        return entity;
    }
    
    private EntityMapping createMapping(String tableName) {
        EntityMapping mapping = ModelFactory.eINSTANCE.createEntityMapping();
        mapping.setTableName(tableName);
        return mapping;
    }
    
    private Attribute createAttribute(String name, String type, boolean isPrimaryKey) {
        Attribute attr = ModelFactory.eINSTANCE.createAttribute();
        attr.setName(name);
        attr.setType(type);
        attr.setPrimaryKey(isPrimaryKey);
        return attr;
    }
    
    private ValidationResult findResult(List<ValidationResult> results, String constraintName) {
        return results.stream()
            .filter(r -> constraintName.equals(r.getConstraintName()))
            .findFirst()
            .orElse(null);
    }
}
```

## Related Topics

- [Writing Validation Rules](../user-guide/validation-rules.md) - Core validation patterns
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Advanced control flow
- [Error Messages](../best-practices/error-messages.md) - Message formatting best practices
- [Performance](../best-practices/performance.md) - Optimization strategies
- [EVL Migration Guide](../evl-comparison/migration-guide.md) - Migrating from EVL

---

**Navigation**: [Documentation Hub](../index.md) > [Examples](../index.md#examples) > EntityType Validations
