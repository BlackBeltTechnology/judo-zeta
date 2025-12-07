# Constants Pattern for Validation Names

**Navigation**: [Documentation Hub](../../index.md) > [Best Practices](../index.md#best-practices) > Constants Pattern

This guide covers the constants pattern for validation constraint names, guard method names, and extension method names. This pattern improves type safety, refactoring capabilities, and developer experience when working with the Judo Zeta validation framework.

## Overview

The constants pattern centralizes validation-related string literals into dedicated constant classes. This approach prevents typos, enables IDE autocomplete, and makes refactoring safer and easier.

### What You'll Learn

- Why use constants instead of string literals
- The ConstraintNames pattern
- The GuardMethodNames pattern
- The ExtensionMethodNames pattern
- How to organize constants effectively
- Real-world examples from production code

## The Problem with String Literals

String literals for validation names create several maintenance challenges:

### Typos and Runtime Errors

```java
// ❌ PROBLEM: Typo in @Satisfies causes runtime failure
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "EntityMustHaveName", message = "Entity must have name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Typo: "EntityMustHaveNane" instead of "EntityMustHaveName"
    // This fails at runtime, not compile time
    @Satisfies(constraints = {"EntityMustHaveNane"})
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> { /* ... */ };
    }
}
```

### Difficult Refactoring

```java
// ❌ PROBLEM: Changing constraint name requires finding all references
@Constraint(name = "EntityMustHaveName", message = "...")
public ValidationRule entityMustHaveName() { /* ... */ }

// Used in @Satisfies annotations:
@Satisfies(constraints = {"EntityMustHaveName"})

// Used in guard methods:
if (ctx.satisfies("EntityMustHaveName")) { /* ... */ }

// Used in tests:
assertThat(results).hasConstraint("EntityMustHaveName");

// If you rename to "EntityRequiresName", you must find and update ALL occurrences
// String search can find false positives and miss dynamic usages
```

### No IDE Support

```java
// ❌ PROBLEM: No autocomplete, no compile-time validation
@Satisfies(constraints = {"EntityMus"})  // IDE can't suggest completion
@Constraint(name = "NameMustBeUnique", message = "...")
public ValidationRule nameMustBeUnique() { /* ... */ }
```

### Inconsistent Naming

```java
// ❌ PROBLEM: Inconsistent naming conventions
@Constraint(name = "EntityMustHaveName", message = "...")
public ValidationRule entityMustHaveName() { /* ... */ }

@Constraint(name = "entity_must_have_table", message = "...")
public ValidationRule entityMustHaveTable() { /* ... */ }

@Constraint(name = "ENTITY_MUST_HAVE_PK", message = "...")
public ValidationRule entityMustHavePrimaryKey() { /* ... */ }

// Three different naming styles make the codebase inconsistent
```

## The Constants Pattern Solution

Centralize all validation-related names into dedicated constant classes.

### Benefits

1. **Type Safety** - Compiler catches typos immediately
2. **Refactoring** - Change constant value once, all references update
3. **IDE Autocomplete** - Type-ahead suggestions for constraint names
4. **Consistency** - Enforce naming conventions in one place
5. **Documentation** - Constants serve as a catalog of all validations
6. **Navigation** - Jump to definition from any usage

## ConstraintNames Pattern

### Basic Implementation

Create a dedicated class for constraint name constants:

```java
package com.example.mymodel.validation;

/**
 * Centralized constants for all validation constraint and critique names.
 * 
 * <p>Usage:</p>
 * <pre>
 * import static com.example.mymodel.validation.ConstraintNames.*;
 * 
 * {@literal @}Constraint(name = ENTITY_MUST_HAVE_NAME, message = "...")
 * public ValidationRule entityMustHaveName() { ... }
 * </pre>
 */
public final class ConstraintNames {
    
    // Private constructor prevents instantiation
    private ConstraintNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // Entity Type Constraints
    public static final String ENTITY_MUST_HAVE_NAME = "EntityMustHaveName";
    public static final String ENTITY_NAMES_ARE_UNIQUE = "EntityNamesAreUnique";
    public static final String ENTITY_MUST_HAVE_TABLE = "EntityMustHaveTable";
    public static final String ENTITY_MUST_HAVE_PRIMARY_KEY = "EntityMustHavePrimaryKey";
    public static final String NO_CYCLIC_INHERITANCE = "NoCyclicInheritance";
    public static final String SUPERTYPE_MUST_BE_ABSTRACT = "SupertypeMustBeAbstract";
    
    // Attribute Constraints
    public static final String ATTRIBUTE_MUST_HAVE_NAME = "AttributeMustHaveName";
    public static final String ATTRIBUTE_MUST_HAVE_TYPE = "AttributeMustHaveType";
    public static final String ATTRIBUTE_NAMES_UNIQUE_IN_ENTITY = "AttributeNamesUniqueInEntity";
    public static final String PRIMARY_KEY_MUST_BE_NON_NULLABLE = "PrimaryKeyMustBeNonNullable";
    
    // Operation Constraints
    public static final String OPERATION_MUST_HAVE_NAME = "OperationMustHaveName";
    public static final String OPERATION_MUST_HAVE_RETURN_TYPE = "OperationMustHaveReturnType";
    public static final String OPERATION_NAMES_UNIQUE_IN_ENTITY = "OperationNamesUniqueInEntity";
    
    // Relationship Constraints
    public static final String FOREIGN_KEY_REFERENCES_VALID_ENTITY = "ForeignKeyReferencesValidEntity";
    public static final String ASSOCIATION_ENDS_MUST_EXIST = "AssociationEndsMustExist";
}
```

### Usage with Static Import

Import the constants class statically for clean, readable code:

```java
package com.example.mymodel.validation.entity;

import com.example.mymodel.EntityType;
import hu.blackbelt.judo.meta.validation.annotation.*;
import hu.blackbelt.judo.meta.validation.core.*;

import static com.example.mymodel.validation.ConstraintNames.*;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = ENTITY_MUST_HAVE_NAME, message = "Entity must have name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail(ENTITY_MUST_HAVE_NAME, "Entity must have a name");
        };
    }
    
    @Satisfies(constraints = {ENTITY_MUST_HAVE_NAME})
    @Critique(name = ENTITY_NAMES_ARE_UNIQUE, message = "Entity names must be unique")
    public ValidationRule entityNamesAreUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            long count = allEntities.stream()
                .filter(e -> entity.getName().equalsIgnoreCase(e.getName()))
                .count();
            
            return count == 1
                ? ValidationResult.pass()
                : ValidationResult.warn(ENTITY_NAMES_ARE_UNIQUE, 
                    "Entity name '" + entity.getName() + "' is used " + count + " times");
        };
    }
    
    @Satisfies(constraints = {ENTITY_MUST_HAVE_NAME})
    @Constraint(name = NO_CYCLIC_INHERITANCE, message = "No cyclic inheritance")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            if (hasCycle(entity, new HashSet<>())) {
                return ValidationResult.fail(NO_CYCLIC_INHERITANCE,
                    "Entity '" + entity.getName() + "' has circular inheritance");
            }
            return ValidationResult.pass();
        };
    }
    
    private boolean hasCycle(EntityType entity, Set<EntityType> visited) {
        if (visited.contains(entity)) return true;
        if (entity.getSuperType() == null) return false;
        visited.add(entity);
        return hasCycle(entity.getSuperType(), visited);
    }
}
```

### Before and After Comparison

**Before (String Literals):**

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "EntityMustHaveName", message = "Entity must have name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Typo risk: "EntityMustHaveName" vs "EntityMustHaveNane"
    @Satisfies(constraints = {"EntityMustHaveName"})
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Different naming convention
    @Satisfies(constraints = {"NameMustBeUnique"})
    @Constraint(name = "entity_no_cyclic_inheritance", message = "...")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> { /* ... */ };
    }
}
```

**After (Constants):**

```java
import static com.example.mymodel.validation.ConstraintNames.*;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = ENTITY_MUST_HAVE_NAME, message = "Entity must have name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Compile-time error if typo: ENTITY_MUST_HAVE_NANE doesn't exist
    @Satisfies(constraints = {ENTITY_MUST_HAVE_NAME})
    @Constraint(name = NAME_MUST_BE_UNIQUE, message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Consistent naming enforced by constants
    @Satisfies(constraints = {NAME_MUST_BE_UNIQUE})
    @Constraint(name = NO_CYCLIC_INHERITANCE, message = "...")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> { /* ... */ };
    }
}
```

## GuardMethodNames Pattern

Guard methods often need to be referenced by name when creating conditional validation rules. The same constants pattern applies.

### Implementation

```java
package com.example.mymodel.validation;

/**
 * Centralized constants for guard method names.
 * 
 * <p>Usage:</p>
 * <pre>
 * import static com.example.mymodel.validation.GuardMethodNames.*;
 * 
 * {@literal @}Guard(method = IS_CONCRETE)
 * {@literal @}Constraint(name = "EntityMustHaveTable", message = "...")
 * public ValidationRule entityMustHaveTable() { ... }
 * </pre>
 */
public final class GuardMethodNames {
    
    private GuardMethodNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // Entity Type Guards
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String IS_CONCRETE = "isConcrete";
    public static final String HAS_SUPERTYPE = "hasSuperType";
    public static final String HAS_NO_SUPERTYPE = "hasNoSuperType";
    
    // Attribute Guards
    public static final String IS_PRIMARY_KEY = "isPrimaryKey";
    public static final String IS_FOREIGN_KEY = "isForeignKey";
    public static final String IS_NULLABLE = "isNullable";
    public static final String IS_REQUIRED = "isRequired";
    
    // Operation Guards
    public static final String IS_STATIC_OPERATION = "isStaticOperation";
    public static final String IS_INSTANCE_OPERATION = "isInstanceOperation";
    public static final String HAS_PARAMETERS = "hasParameters";
    public static final String HAS_RETURN_VALUE = "hasReturnValue";
}
```

### Usage Example

```java
import static com.example.mymodel.validation.ConstraintNames.*;
import static com.example.mymodel.validation.GuardMethodNames.*;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Guard ensures this only runs on concrete (non-abstract) entities
    @Guard(method = IS_CONCRETE)
    @Constraint(name = ENTITY_MUST_HAVE_TABLE, message = "Concrete entity must have table")
    public ValidationRule entityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTableName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail(ENTITY_MUST_HAVE_TABLE,
                    "Concrete entity '" + entity.getName() + "' must have a table name");
        };
    }
    
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
    
    // Different guard for abstract entities
    @Guard(method = IS_ABSTRACT)
    @Critique(name = ABSTRACT_ENTITY_SHOULD_HAVE_SUBCLASSES, 
              message = "Abstract entity should have subclasses")
    public ValidationRule abstractEntityShouldHaveSubclasses() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            boolean hasSubclasses = allEntities.stream()
                .anyMatch(e -> entity.equals(e.getSuperType()));
            
            return hasSubclasses
                ? ValidationResult.pass()
                : ValidationResult.warn(ABSTRACT_ENTITY_SHOULD_HAVE_SUBCLASSES,
                    "Abstract entity '" + entity.getName() + "' has no subclasses");
        };
    }
    
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EntityType) element).isAbstract();
    }
}
```

### Before and After Comparison

**Before (String Literals):**

```java
@Guard(method = "isConcrete")  // String literal, no autocomplete
@Constraint(name = "EntityMustHaveTable", message = "...")
public ValidationRule entityMustHaveTable() { /* ... */ }

public boolean isConcrete(EObject element, ValidationContext ctx) { /* ... */ }

// Typo in guard method name causes runtime failure
@Guard(method = "isConrete")  // Missing 'c', no compile error
@Constraint(name = "MustHaveMapping", message = "...")
public ValidationRule mustHaveMapping() { /* ... */ }
```

**After (Constants):**

```java
import static com.example.mymodel.validation.GuardMethodNames.*;

@Guard(method = IS_CONCRETE)  // Constant, autocomplete works
@Constraint(name = ENTITY_MUST_HAVE_TABLE, message = "...")
public ValidationRule entityMustHaveTable() { /* ... */ }

public boolean isConcrete(EObject element, ValidationContext ctx) { /* ... */ }

// Typo causes compile error immediately
@Guard(method = IS_CONRETE)  // Compiler error: cannot find symbol IS_CONRETE
@Constraint(name = MUST_HAVE_MAPPING, message = "...")
public ValidationRule mustHaveMapping() { /* ... */ }
```

## ExtensionMethodNames Pattern

Extension methods are reusable helper functions called from validation rules. Using constants for their names provides the same benefits.

### Implementation

```java
package com.example.mymodel.validation;

/**
 * Centralized constants for extension method names.
 * 
 * <p>Usage:</p>
 * <pre>
 * import static com.example.mymodel.validation.ExtensionMethodNames.*;
 * 
 * // Define extension method
 * {@literal @}ExtensionMethod(elementType = EntityType.class)
 * public List&lt;Attribute&gt; getAllAttributes(EntityType entity) { ... }
 * 
 * // Call from validation
 * List&lt;Attribute&gt; attrs = ctx.callExtension(GET_ALL_ATTRIBUTES, element);
 * </pre>
 */
public final class ExtensionMethodNames {
    
    private ExtensionMethodNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // Entity Type Extension Methods
    public static final String GET_ALL_ATTRIBUTES = "getAllAttributes";
    public static final String GET_ALL_OPERATIONS = "getAllOperations";
    public static final String GET_INHERITANCE_CHAIN = "getInheritanceChain";
    public static final String GET_ALL_SUPERTYPES = "getAllSuperTypes";
    public static final String IS_SUBTYPE_OF = "isSubtypeOf";
    
    // Attribute Extension Methods
    public static final String GET_EFFECTIVE_TYPE = "getEffectiveType";
    public static final String IS_INHERITED = "isInherited";
    public static final String GET_DECLARING_ENTITY = "getDeclaringEntity";
    
    // Operation Extension Methods
    public static final String GET_OVERRIDDEN_OPERATION = "getOverriddenOperation";
    public static final String GET_PARAMETER_TYPES = "getParameterTypes";
    public static final String IS_OVERRIDE = "isOverride";
}
```

### Usage Example

```java
import static com.example.mymodel.validation.ConstraintNames.*;
import static com.example.mymodel.validation.ExtensionMethodNames.*;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Define extension method with constant name
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
        
        // Include inherited attributes
        EntityType superType = entity.getSuperType();
        while (superType != null) {
            attributes.addAll(superType.getAttributes());
            superType = superType.getSuperType();
        }
        
        return attributes;
    }
    
    // Use extension method in validation
    @Constraint(name = ENTITY_MUST_HAVE_PRIMARY_KEY, 
                message = "Entity must have primary key")
    public ValidationRule entityMustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Call extension method using constant name
            @SuppressWarnings("unchecked")
            List<Attribute> allAttrs = 
                (List<Attribute>) ctx.callExtension(GET_ALL_ATTRIBUTES, entity);
            
            boolean hasPrimaryKey = allAttrs.stream()
                .anyMatch(Attribute::isPrimaryKey);
            
            return hasPrimaryKey
                ? ValidationResult.pass()
                : ValidationResult.fail(ENTITY_MUST_HAVE_PRIMARY_KEY,
                    "Entity '" + entity.getName() + "' must have a primary key attribute");
        };
    }
    
    @ExtensionMethod(elementType = EntityType.class)
    public List<EntityType> getInheritanceChain(EntityType entity) {
        List<EntityType> chain = new ArrayList<>();
        EntityType current = entity;
        
        while (current != null) {
            chain.add(current);
            current = current.getSuperType();
        }
        
        return chain;
    }
    
    @Constraint(name = NO_CYCLIC_INHERITANCE, message = "No cyclic inheritance")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            @SuppressWarnings("unchecked")
            List<EntityType> chain = 
                (List<EntityType>) ctx.callExtension(GET_INHERITANCE_CHAIN, entity);
            
            // Check for duplicates in chain (indicates cycle)
            Set<EntityType> seen = new HashSet<>();
            for (EntityType type : chain) {
                if (!seen.add(type)) {
                    return ValidationResult.fail(NO_CYCLIC_INHERITANCE,
                        "Entity '" + entity.getName() + "' has circular inheritance");
                }
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Organizing Constants

Choose an organization strategy based on your project size and complexity.

### Strategy 1: Single Constants Class

For small to medium projects, keep all constants in one file:

```java
public final class ValidationConstants {
    
    private ValidationConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // Constraint Names
    public static final String ENTITY_MUST_HAVE_NAME = "EntityMustHaveName";
    public static final String ENTITY_NAMES_ARE_UNIQUE = "EntityNamesAreUnique";
    public static final String ATTRIBUTE_MUST_HAVE_TYPE = "AttributeMustHaveType";
    
    // Guard Method Names
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String IS_CONCRETE = "isConcrete";
    
    // Extension Method Names
    public static final String GET_ALL_ATTRIBUTES = "getAllAttributes";
    public static final String GET_INHERITANCE_CHAIN = "getInheritanceChain";
}
```

**Pros:**
- Simple, single file to maintain
- Easy to find all validation constants
- No decision about which class to use

**Cons:**
- Can become large and unwieldy
- Harder to navigate with many constants
- Less semantic organization

### Strategy 2: Separate Constants Classes

For large projects, separate by purpose:

```java
// ConstraintNames.java
public final class ConstraintNames {
    public static final String ENTITY_MUST_HAVE_NAME = "EntityMustHaveName";
    public static final String ENTITY_NAMES_ARE_UNIQUE = "EntityNamesAreUnique";
    // ...
}

// GuardMethodNames.java
public final class GuardMethodNames {
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String IS_CONCRETE = "isConcrete";
    // ...
}

// ExtensionMethodNames.java
public final class ExtensionMethodNames {
    public static final String GET_ALL_ATTRIBUTES = "getAllAttributes";
    public static final String GET_INHERITANCE_CHAIN = "getInheritanceChain";
    // ...
}
```

**Pros:**
- Clear separation of concerns
- Each file has focused purpose
- Easier to navigate related constants

**Cons:**
- Multiple imports needed
- More files to maintain
- Need discipline to keep organized

### Strategy 3: Nested Classes by Domain

For very large projects, organize by metamodel element:

```java
public final class ValidationConstants {
    
    private ValidationConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    public static final class EntityType {
        private EntityType() {}
        
        public static final String MUST_HAVE_NAME = "EntityMustHaveName";
        public static final String NAMES_ARE_UNIQUE = "EntityNamesAreUnique";
        public static final String MUST_HAVE_TABLE = "EntityMustHaveTable";
    }
    
    public static final class Attribute {
        private Attribute() {}
        
        public static final String MUST_HAVE_NAME = "AttributeMustHaveName";
        public static final String MUST_HAVE_TYPE = "AttributeMustHaveType";
        public static final String NAMES_UNIQUE_IN_ENTITY = "AttributeNamesUniqueInEntity";
    }
    
    public static final class Operation {
        private Operation() {}
        
        public static final String MUST_HAVE_NAME = "OperationMustHaveName";
        public static final String MUST_HAVE_RETURN_TYPE = "OperationMustHaveReturnType";
    }
}

// Usage
import static com.example.mymodel.validation.ValidationConstants.EntityType.*;
import static com.example.mymodel.validation.ValidationConstants.Attribute.*;

@Constraint(name = MUST_HAVE_NAME, message = "...")  // EntityType.MUST_HAVE_NAME
```

**Pros:**
- Excellent organization by domain
- Reduced naming conflicts (MUST_HAVE_NAME in each domain)
- Single file with logical grouping

**Cons:**
- More complex imports
- Longer constant references if not using static import
- Requires planning domain boundaries

## Recommended Approach

For most Judo Zeta validation projects, **Strategy 2 (Separate Constants Classes)** is recommended:

```
com.example.mymodel.validation/
├── ConstraintNames.java        # All constraint/critique names
├── GuardMethodNames.java       # All guard method names
├── ExtensionMethodNames.java   # All extension method names
└── entity/
    ├── EntityTypeValidations.java
    ├── AttributeValidations.java
    └── OperationValidations.java
```

This provides:
- Clear separation of concerns
- Easy to find related constants
- Simple static imports
- Scales well as project grows

## Real-World Example from judo-meta-esm

The judo-meta-esm metamodel uses this pattern extensively:

### ConstraintNames.java

```java
package hu.blackbelt.judo.meta.esm.validation;

public final class ConstraintNames {
    
    private ConstraintNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    // Entity Type Constraints
    public static final String ENTITY_TYPE_NAMES_ARE_UNIQUE = "EntityTypeNamesAreUnique";
    public static final String ENTITY_TYPE_HAS_MAPPING = "EntityTypeHasMapping";
    public static final String ENTITY_TYPE_SUPERTYPE_IS_ABSTRACT = "EntityTypeSupertypeIsAbstract";
    public static final String ENTITY_TYPE_NO_CYCLIC_INHERITANCE = "EntityTypeNoCyclicInheritance";
    
    // Transfer Object Constraints
    public static final String TRANSFER_OBJECT_NAMES_ARE_UNIQUE = "TransferObjectNamesAreUnique";
    public static final String TRANSFER_OBJECT_HAS_ENTITY_TYPE = "TransferObjectHasEntityType";
    
    // Operation Constraints
    public static final String ABSTRACT_OPERATION_IS_VALID = "AbstractOperationIsValid";
    public static final String INSTANCE_OPERATION_IS_VALID = "InstanceOperationIsValid";
    public static final String MAPPED_OPERATION_IS_VALID = "MappedOperationIsValid";
}
```

### GuardMethodNames.java

```java
package hu.blackbelt.judo.meta.esm.validation;

public final class GuardMethodNames {
    
    private GuardMethodNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    public static final String GUARD_ENTITY_TYPE_NAMES_ARE_UNIQUE = "guardEntityTypeNamesAreUnique";
    public static final String GUARD_ENTITY_TYPE_HAS_SUPERTYPE = "guardEntityTypeHasSupertype";
    public static final String GUARD_IS_ABSTRACT_OPERATION = "guardIsAbstractOperation";
    public static final String GUARD_IS_INSTANCE_OPERATION = "guardIsInstanceOperation";
    public static final String GUARD_IS_MAPPED_OPERATION = "guardIsMappedOperation";
}
```

### Usage in Validation Class

```java
package hu.blackbelt.judo.meta.esm.validation;

import static hu.blackbelt.judo.meta.esm.validation.ConstraintNames.*;
import static hu.blackbelt.judo.meta.esm.validation.GuardMethodNames.*;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Critique(
        name = ENTITY_TYPE_NAMES_ARE_UNIQUE,
        message = "There are two or more entity types of the same name: {name}"
    )
    @Guard(method = GUARD_ENTITY_TYPE_NAMES_ARE_UNIQUE)
    public ValidationRule entityTypeNamesAreUnique() {
        return (element, ctx) -> {
            EntityType self = (EntityType) element;
            Collection<EntityType> allEntityTypes = ctx.getAllInstances(EntityType.class);
            
            boolean hasDuplicate = allEntityTypes.stream()
                .filter(t -> t != self)
                .anyMatch(t -> t.getName().equalsIgnoreCase(self.getName()));
            
            return hasDuplicate
                ? ValidationResult.warn(ENTITY_TYPE_NAMES_ARE_UNIQUE,
                    "Duplicate entity type name: " + self.getName())
                : ValidationResult.pass();
        };
    }
    
    public boolean guardEntityTypeNamesAreUnique(EObject element, ValidationContext ctx) {
        EntityType entityType = (EntityType) element;
        return entityType.getName() != null && !entityType.getName().isEmpty();
    }
}
```

## Advanced Pattern: Constant Documentation

Add Javadoc to constants for better documentation:

```java
public final class ConstraintNames {
    
    private ConstraintNames() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Validates that every entity has a non-null, non-empty name.
     * 
     * <p><b>Severity:</b> ERROR</p>
     * <p><b>Element Type:</b> EntityType</p>
     * <p><b>Dependencies:</b> None</p>
     */
    public static final String ENTITY_MUST_HAVE_NAME = "EntityMustHaveName";
    
    /**
     * Validates that entity names are unique (case-insensitive).
     * 
     * <p><b>Severity:</b> WARNING</p>
     * <p><b>Element Type:</b> EntityType</p>
     * <p><b>Dependencies:</b> {@link #ENTITY_MUST_HAVE_NAME}</p>
     */
    public static final String ENTITY_NAMES_ARE_UNIQUE = "EntityNamesAreUnique";
    
    /**
     * Validates that there are no cycles in the inheritance hierarchy.
     * 
     * <p><b>Severity:</b> ERROR</p>
     * <p><b>Element Type:</b> EntityType</p>
     * <p><b>Dependencies:</b> {@link #ENTITY_MUST_HAVE_NAME}</p>
     */
    public static final String NO_CYCLIC_INHERITANCE = "NoCyclicInheritance";
}
```

## Testing with Constants

Constants make tests more maintainable too:

```java
import static com.example.mymodel.validation.ConstraintNames.*;

class EntityTypeValidationsTest {
    
    @Test
    void testEntityMustHaveName() {
        EntityType entity = createEntity(null);  // Name is null
        
        ValidationResult result = validator.validate(entity);
        
        // Use constant instead of string literal
        assertThat(result.getConstraintName()).isEqualTo(ENTITY_MUST_HAVE_NAME);
        assertThat(result.getSeverity()).isEqualTo(Severity.ERROR);
    }
    
    @Test
    void testNameUniqueness() {
        EntityType entity1 = createEntity("Customer");
        EntityType entity2 = createEntity("Customer");
        
        List<ValidationResult> results = validator.validateAll(List.of(entity1, entity2));
        
        // Use constant for assertion
        assertThat(results)
            .hasViolation(ENTITY_NAMES_ARE_UNIQUE)
            .withSeverity(Severity.WARNING);
    }
}
```

## Summary

### Key Takeaways

1. **Always use constants** for constraint names, guard method names, and extension method names
2. **Organize thoughtfully** - choose single class, separate classes, or nested classes based on project size
3. **Static import** for clean, readable validation code
4. **Document constants** with Javadoc for better understanding
5. **Update tests** to use constants for maintainability

### Quick Start Checklist

- [ ] Create `ConstraintNames.java` in validation package
- [ ] Create `GuardMethodNames.java` in validation package
- [ ] Create `ExtensionMethodNames.java` if using extension methods
- [ ] Add private constructor to prevent instantiation
- [ ] Define constants as `public static final String`
- [ ] Use PascalCase or UPPER_SNAKE_CASE consistently
- [ ] Add static imports to validation classes
- [ ] Replace all string literals with constant references
- [ ] Add Javadoc to constants (optional but recommended)
- [ ] Update tests to use constants

### Benefits Achieved

When you adopt this pattern, you gain:

- **Compile-time safety** - Typos caught immediately
- **Better refactoring** - Rename once, change everywhere
- **IDE support** - Autocomplete and navigation
- **Consistency** - Enforced naming conventions
- **Documentation** - Constants catalog all validations
- **Maintainability** - Easier to understand and modify

## Related Topics

- [Writing Validation Rules](../user-guide/validation-rules.md) - How to write effective validation rules
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Using guard methods and @Satisfies
- [Extension Methods](../user-guide/extension-methods.md) - Creating reusable helper functions
- [Extension Delegation Pattern](extension-delegation.md) - Static utility class pattern

---

**Previous**: [Best Practices Hub](../index.md#best-practices) | **Next**: [Extension Delegation](extension-delegation.md)
