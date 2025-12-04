# Writing Validation Rules

**Navigation**: [Documentation Hub](../index.md) > [User Guide](core-concepts.md) > Writing Validation Rules

This guide covers best practices and advanced patterns for writing effective validation rules in the Judo Zeta framework.

## Overview

Validation rules are the heart of your validation logic. This guide will teach you how to write rules that are clear, maintainable, and performant.

### What You'll Learn

- Choosing between `@Constraint` and `@Critique`
- Crafting effective error messages
- Using `ValidationResult` patterns
- Organizing validation classes
- Advanced rule patterns and optimizations

## Constraints vs Critiques

The framework provides two severity levels for validation rules, each serving a distinct purpose.

### @Constraint - Error Level

Use `@Constraint` for rules that **must** be satisfied for the model to be valid. These are hard requirements that prevent code generation or deployment.

```java
@Constraint(
    name = "EntityMustHaveName",
    message = "Entity must have a name"
)
public ValidationRule entityMustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null || entity.getName().isEmpty()) {
            return ValidationResult.fail(
                "EntityMustHaveName",
                "Entity must have a name",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**When to use @Constraint:**
- Required fields are missing (name, type, etc.)
- Invalid references or relationships
- Type violations or incompatible types
- Cyclic dependencies that break the model
- Any condition that prevents code generation
- Business rules that must be enforced

### @Critique - Warning Level

Use `@Critique` for recommendations and best practices. These are quality improvements that don't prevent the model from functioning.

```java
@Critique(
    name = "EntityShouldHaveDescription",
    message = "Entity should have a description for documentation"
)
public ValidationRule entityShouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getDescription() == null || entity.getDescription().isEmpty()) {
            return ValidationResult.warn(
                "EntityShouldHaveDescription",
                "Entity '" + entity.getName() + "' should have a description",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**When to use @Critique:**
- Missing documentation or comments
- Naming convention violations
- Performance recommendations
- Code style suggestions
- Deprecated pattern usage
- Missing optional metadata

### Decision Guide

| Scenario | Use @Constraint | Use @Critique |
|----------|----------------|---------------|
| Name is null or empty | ✓ | |
| Name doesn't follow PascalCase | | ✓ |
| Required relationship is missing | ✓ | |
| Optional description is missing | | ✓ |
| Circular inheritance detected | ✓ | |
| Deep inheritance hierarchy (>5 levels) | | ✓ |
| Invalid SQL identifier characters | ✓ | |
| Table name exceeds recommended length | | ✓ |
| Type mismatch in operation | ✓ | |
| Missing @deprecated annotation | | ✓ |

## Message Formatting

Clear, actionable error messages help users fix problems quickly. The framework supports multiple message formatting approaches.

### Basic Messages

Simple static messages in the annotation:

```java
@Constraint(
    name = "MustHaveName",
    message = "Element must have a name"
)
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        return obj.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Element must have a name");
    };
}
```

### Contextual Messages

Include element details in the message for better diagnostics:

```java
@Constraint(
    name = "NameMustBeUnique",
    message = "Name must be unique"
)
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        long count = allEntities.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        if (count > 1) {
            return ValidationResult.fail(
                "NameMustBeUnique",
                "Entity name '" + entity.getName() + "' is used " + count + 
                " times. Each entity must have a unique name.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Actionable Messages

Tell users **what** is wrong and **how** to fix it:

```java
@Constraint(
    name = "NoCyclicInheritance",
    message = "Circular inheritance is not allowed"
)
public ValidationRule noCyclicInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (hasCyclicInheritance(entity)) {
            String chain = getInheritanceChain(entity);
            return ValidationResult.fail(
                "NoCyclicInheritance",
                "Entity '" + entity.getName() + "' has circular inheritance: " +
                chain + ". Remove one of the inheritance relationships to break the cycle.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Message Best Practices

```java
// ❌ BAD: Vague and unhelpful
return ValidationResult.fail("Invalid");

// ❌ BAD: Technical jargon without context
return ValidationResult.fail("Constraint violation in EReference");

// ❌ BAD: No guidance on how to fix
return ValidationResult.fail("Name conflict detected");

// ✓ GOOD: Clear, specific, actionable
return ValidationResult.fail(
    "NameMustBeUnique",
    "Entity name 'Customer' is already used by another entity. " +
    "Choose a unique name like 'CustomerRecord' or 'CustomerEntity'.",
    Severity.ERROR,
    element
);

// ✓ GOOD: Lists all problems at once
return ValidationResult.fail(
    "ValidIdentifier",
    "Entity name 'My-Entity!' contains invalid characters: '-', '!'. " +
    "Use only letters, numbers, and underscores.",
    Severity.ERROR,
    element
);

// ✓ GOOD: Explains the impact
return ValidationResult.warn(
    "AvoidLongNames",
    "Entity name '" + entity.getName() + "' is " + length + " characters long. " +
    "Consider using a shorter name (recommended: < 30 characters) for better readability.",
    element
);
```

## ValidationResult Patterns

The `ValidationResult` class provides several factory methods for different scenarios.

### Simple Pass/Fail

For straightforward validations:

```java
@Constraint(name = "MustBeAbstract", message = "Must be abstract")
public ValidationRule mustBeAbstract() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.isAbstract()
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must be marked as abstract");
    };
}
```

### Full Metadata

For integration with validation frameworks and detailed reporting:

```java
@Constraint(name = "ValidSuperType", message = "Supertype must be valid")
public ValidationRule validSuperType() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        EntityType superType = entity.getSuperType();
        
        if (superType == null) {
            return ValidationResult.pass();
        }
        
        if (!superType.isAbstract()) {
            return ValidationResult.fail(
                "ValidSuperType",                    // Constraint name
                "Supertype '" + superType.getName() + 
                "' must be abstract",                // Message
                Severity.ERROR,                       // Severity
                element                               // Context element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Warnings

For critiques and recommendations:

```java
@Critique(name = "FollowNamingConvention", message = "Follow naming convention")
public ValidationRule followNamingConvention() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        
        if (name == null || name.isEmpty()) {
            return ValidationResult.pass(); // Don't warn if name is missing (constraint handles that)
        }
        
        if (!Character.isUpperCase(name.charAt(0))) {
            return ValidationResult.warn(
                "FollowNamingConvention",
                "Entity name '" + name + "' should start with an uppercase letter (PascalCase)",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Early Returns

Keep validation logic clean with early returns:

```java
@Constraint(name = "ValidAttributeType", message = "Attribute must have valid type")
public ValidationRule validAttributeType() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        
        // Early return for valid cases
        if (attr.getType() != null) {
            return ValidationResult.pass();
        }
        
        // Only build error message when needed
        EntityType owner = (EntityType) attr.eContainer();
        return ValidationResult.fail(
            "ValidAttributeType",
            "Attribute '" + attr.getName() + "' in entity '" + 
            owner.getName() + "' must have a type",
            Severity.ERROR,
            element
        );
    };
}
```

## Rule Organization

Organize validation rules for maintainability and clarity.

### One Class Per Element Type

Create separate validation classes for each metamodel element:

```java
// EntityTypeValidations.java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    // All EntityType validation rules
}

// AttributeValidations.java
@ValidationContext(Attribute.class)
public class AttributeValidations {
    // All Attribute validation rules
}

// OperationValidations.java
@ValidationContext(Operation.class)
public class OperationValidations {
    // All Operation validation rules
}
```

### Group by Concern

For complex types, split by validation concern:

```java
// EntityStructureValidations.java
@ValidationContext(EntityType.class)
public class EntityStructureValidations {
    // Rules about entity structure: name, attributes, operations
}

// EntityInheritanceValidations.java
@ValidationContext(EntityType.class)
public class EntityInheritanceValidations {
    // Rules about inheritance: supertypes, abstract classes, overrides
}

// EntityRelationshipValidations.java
@ValidationContext(EntityType.class)
public class EntityRelationshipValidations {
    // Rules about relationships: associations, foreign keys
}
```

### Package Structure

Organize validation classes in a logical package hierarchy:

```
com.example.mymodel.validation/
├── entity/
│   ├── EntityTypeValidations.java
│   ├── AttributeValidations.java
│   └── OperationValidations.java
├── relationship/
│   ├── AssociationValidations.java
│   └── ReferenceValidations.java
└── constraints/
    ├── NamingValidations.java
    └── TypeValidations.java
```

### Naming Conventions

Use clear, descriptive names for validation methods:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // ✓ GOOD: Verb phrases that describe the rule
    @Constraint(name = "EntityMustHaveName", message = "...")
    public ValidationRule entityMustHaveName() { ... }
    
    @Constraint(name = "NameMustBeUnique", message = "...")
    public ValidationRule nameMustBeUnique() { ... }
    
    @Critique(name = "ShouldHaveDescription", message = "...")
    public ValidationRule shouldHaveDescription() { ... }
    
    // ❌ BAD: Unclear or generic names
    @Constraint(name = "Check1", message = "...")
    public ValidationRule check1() { ... }
    
    @Constraint(name = "Validate", message = "...")
    public ValidationRule validate() { ... }
}
```

## Advanced Validation Patterns

Powerful patterns for complex validation scenarios.

### Cross-Element Validation

Validate relationships between multiple elements:

```java
@Constraint(
    name = "ForeignKeyMustReferenceExistingEntity",
    message = "Foreign key must reference an existing entity"
)
public ValidationRule foreignKeyMustReferenceExistingEntity() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        
        if (!attr.isForeignKey()) {
            return ValidationResult.pass();
        }
        
        String referencedEntityName = attr.getReferencedEntity();
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        boolean exists = allEntities.stream()
            .anyMatch(e -> e.getName().equals(referencedEntityName));
        
        if (!exists) {
            return ValidationResult.fail(
                "ForeignKeyMustReferenceExistingEntity",
                "Foreign key '" + attr.getName() + "' references entity '" + 
                referencedEntityName + "' which does not exist in the model",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Collection Validation

Validate collections and aggregates:

```java
@Constraint(
    name = "EntityMustHavePrimaryKey",
    message = "Entity must have at least one primary key attribute"
)
public ValidationRule entityMustHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Skip abstract entities
        if (entity.isAbstract()) {
            return ValidationResult.pass();
        }
        
        boolean hasPrimaryKey = entity.getAttributes().stream()
            .anyMatch(Attribute::isPrimaryKey);
        
        if (!hasPrimaryKey) {
            return ValidationResult.fail(
                "EntityMustHavePrimaryKey",
                "Entity '" + entity.getName() + "' must have at least one " +
                "attribute marked as primary key",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Graph Traversal

Detect cycles and validate graph structures:

```java
@Constraint(
    name = "NoCyclicInheritance",
    message = "Circular inheritance is not allowed"
)
public ValidationRule noCyclicInheritance() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (hasCycle(entity, new HashSet<>())) {
            String chain = buildCycleChain(entity);
            return ValidationResult.fail(
                "NoCyclicInheritance",
                "Entity '" + entity.getName() + "' has circular inheritance: " + chain,
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

private boolean hasCycle(EntityType entity, Set<EntityType> visited) {
    if (visited.contains(entity)) {
        return true;
    }
    
    EntityType superType = entity.getSuperType();
    if (superType == null) {
        return false;
    }
    
    visited.add(entity);
    return hasCycle(superType, visited);
}

private String buildCycleChain(EntityType entity) {
    StringBuilder chain = new StringBuilder();
    EntityType current = entity;
    Set<EntityType> seen = new HashSet<>();
    
    while (current != null && !seen.contains(current)) {
        if (chain.length() > 0) {
            chain.append(" → ");
        }
        chain.append(current.getName());
        seen.add(current);
        current = current.getSuperType();
    }
    
    if (current != null) {
        chain.append(" → ").append(current.getName());
    }
    
    return chain.toString();
}
```

### Type Checking

Validate type compatibility and conversions:

```java
@Constraint(
    name = "OperationReturnTypeMustBeValid",
    message = "Operation return type must be a valid type"
)
public ValidationRule operationReturnTypeMustBeValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        String returnType = operation.getReturnType();
        
        if (returnType == null || returnType.isEmpty()) {
            return ValidationResult.pass(); // void return type is valid
        }
        
        // Check if it's a primitive type
        if (isPrimitiveType(returnType)) {
            return ValidationResult.pass();
        }
        
        // Check if it's a defined entity type
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        boolean isValidEntity = allEntities.stream()
            .anyMatch(e -> e.getName().equals(returnType));
        
        if (!isValidEntity) {
            return ValidationResult.fail(
                "OperationReturnTypeMustBeValid",
                "Operation '" + operation.getName() + "' has invalid return type '" + 
                returnType + "'. Must be a primitive type or an existing entity.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

private boolean isPrimitiveType(String type) {
    return List.of("String", "Integer", "Boolean", "Date", "Decimal")
        .contains(type);
}
```

### Conditional Validation

Different rules for different element states:

```java
@Constraint(
    name = "ConcreteEntityMustHaveTable",
    message = "Concrete entity must have a table mapping"
)
@Guard(method = "isConcrete")
public ValidationRule concreteEntityMustHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getTableName() == null || entity.getTableName().isEmpty()) {
            return ValidationResult.fail(
                "ConcreteEntityMustHaveTable",
                "Concrete entity '" + entity.getName() + "' must have a table name. " +
                "Set the table name or mark the entity as abstract.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

public boolean isConcrete(EObject element, ValidationContext ctx) {
    return !((EntityType) element).isAbstract();
}
```

## Performance Optimization

Write efficient validation rules for large models.

### Avoid Repeated Queries

Cache expensive lookups in the validation context:

```java
@Constraint(name = "NameMustBeUnique", message = "Name must be unique")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Build a name index once and cache it
        CacheKey cacheKey = CacheKey.of("entity-name-index");
        Map<String, List<EntityType>> nameIndex = 
            (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
        
        if (nameIndex == null) {
            nameIndex = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(EntityType::getName));
            ctx.putCached(cacheKey, nameIndex);
        }
        
        List<EntityType> entitiesWithSameName = nameIndex.get(entity.getName());
        
        if (entitiesWithSameName.size() > 1) {
            return ValidationResult.fail(
                "NameMustBeUnique",
                "Entity name '" + entity.getName() + "' is used by " + 
                entitiesWithSameName.size() + " entities",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Use @Cached Annotation

Mark expensive validation rules for automatic caching:

```java
@Cached
@Constraint(name = "NoCyclicDependencies", message = "No cyclic dependencies")
public ValidationRule noCyclicDependencies() {
    return (element, ctx) -> {
        // Expensive graph traversal - result cached per element
        EntityType entity = (EntityType) element;
        return hasCyclicDependencies(entity, ctx)
            ? ValidationResult.fail("Cyclic dependencies detected")
            : ValidationResult.pass();
    };
}
```

### Limit Scope with Guards

Use guards to skip unnecessary validation:

```java
@Constraint(name = "AbstractEntityConstraint", message = "Abstract entity constraint")
@Guard(method = "isAbstract")
public ValidationRule abstractEntityConstraint() {
    return (element, ctx) -> {
        // Only runs for abstract entities
        // Non-abstract entities skip this rule entirely
    };
}

public boolean isAbstract(EObject element, ValidationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

## Related Topics

- [Core Concepts](core-concepts.md) - Understanding the framework fundamentals
- [Guards and Dependencies](guards-and-dependencies.md) - Advanced control flow
- [Extension Methods](extension-methods.md) - Reusable helper functions
- [Performance Tuning](../best-practices/performance.md) - Optimization strategies

---

**Previous**: [Core Concepts](core-concepts.md) | **Next**: [Guards and Dependencies](guards-and-dependencies.md)
