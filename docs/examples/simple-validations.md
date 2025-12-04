# Simple Validation Examples

**Navigation**: [Documentation Hub](../index.md) > Examples > Simple Validations

This guide provides basic, easy-to-understand validation examples to help you learn the fundamentals of the Judo Zeta Validation Framework. Each example includes complete working code, explanations, and common variations.

## Overview

These examples demonstrate the most common validation patterns you'll use in everyday development. Start here if you're new to the framework or want quick reference examples.

### What You'll Learn

- Basic constraint validation (required fields, non-null checks)
- Simple critique validation (recommendations and warnings)
- Guard conditions (conditional validation)
- String validation (patterns, length, format)
- Numeric validation (ranges, comparisons)
- Boolean validation (flag combinations)
- Collection validation (size, emptiness)
- Enum validation (allowed values)

## 1. Basic Constraint - Name Not Null

The most fundamental validation: ensuring required fields are present.

### Complete Example

```java
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;
import com.example.model.EntityType;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityMustHaveName",
        message = "Entity must have a name"
    )
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check if name is null or empty
            if (entity.getName() == null || entity.getName().trim().isEmpty()) {
                return ValidationResult.fail(
                    "EntityMustHaveName",
                    "Entity must have a name. Please provide a non-empty name.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Explanation

1. **@ValidationContext(EntityType.class)** - This class validates `EntityType` elements
2. **@Constraint** - Error-level validation that must pass
3. **name = "EntityMustHaveName"** - Unique identifier for this constraint
4. **ValidationRule** - Functional interface that returns a validation result
5. **null check** - Validates both null and empty strings
6. **trim()** - Handles whitespace-only strings as invalid
7. **ValidationResult.fail()** - Creates a failing result with error message
8. **ValidationResult.pass()** - Indicates validation succeeded

### Common Variations

**Simple version (concise)**:
```java
@Constraint(name = "MustHaveName", message = "Name is required")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null && !entity.getName().isEmpty()
            ? ValidationResult.pass()
            : ValidationResult.fail("Name is required");
    };
}
```

**With better error message**:
```java
@Constraint(name = "MustHaveName", message = "Name is required")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null) {
            return ValidationResult.fail("Entity name cannot be null. Set a valid name.");
        }
        
        if (entity.getName().trim().isEmpty()) {
            return ValidationResult.fail(
                "Entity name cannot be empty or contain only whitespace. " +
                "Provide a meaningful name."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Checking multiple required fields**:
```java
@Constraint(name = "RequiredFieldsPresent", message = "Required fields must be set")
public ValidationRule requiredFieldsPresent() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null || entity.getName().isEmpty()) {
            return ValidationResult.fail("Entity name is required");
        }
        
        if (entity.getType() == null) {
            return ValidationResult.fail("Entity type is required");
        }
        
        if (entity.getNamespace() == null) {
            return ValidationResult.fail("Entity namespace is required");
        }
        
        return ValidationResult.pass();
    };
}
```

## 2. Simple Critique - Should Have Description

Recommendations and best practices using warning-level validation.

### Complete Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Critique(
        name = "EntityShouldHaveDescription",
        message = "Entity should have a description for documentation"
    )
    public ValidationRule entityShouldHaveDescription() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Only warn if description is missing - this is optional
            if (entity.getDescription() == null || entity.getDescription().trim().isEmpty()) {
                return ValidationResult.warn(
                    "EntityShouldHaveDescription",
                    "Entity '" + entity.getName() + "' should have a description. " +
                    "Adding documentation helps other developers understand the entity's purpose.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Explanation

1. **@Critique** - Warning-level validation (doesn't prevent deployment)
2. **ValidationResult.warn()** - Creates a warning instead of error
3. **Actionable message** - Explains why the recommendation matters
4. **Optional field** - Unlike constraints, critiques validate best practices

### Common Variations

**Multiple documentation recommendations**:
```java
@Critique(name = "ShouldHaveDocumentation", message = "Should have documentation")
public ValidationRule shouldHaveDocumentation() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        List<String> warnings = new ArrayList<>();
        
        if (entity.getDescription() == null || entity.getDescription().isEmpty()) {
            warnings.add("missing description");
        }
        
        if (entity.getExample() == null || entity.getExample().isEmpty()) {
            warnings.add("missing usage example");
        }
        
        if (entity.getAuthor() == null || entity.getAuthor().isEmpty()) {
            warnings.add("missing author");
        }
        
        if (!warnings.isEmpty()) {
            return ValidationResult.warn(
                "Entity '" + entity.getName() + "' has incomplete documentation: " +
                String.join(", ", warnings)
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Conditional critique**:
```java
@Critique(name = "PublicEntityShouldHaveDescription", message = "...")
public ValidationRule publicEntityShouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Only warn for public entities
        if (!entity.isPublic()) {
            return ValidationResult.pass();
        }
        
        if (entity.getDescription() == null || entity.getDescription().isEmpty()) {
            return ValidationResult.warn(
                "Public entity '" + entity.getName() + "' should have a description " +
                "since it's part of the public API"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## 3. Guard Condition - Only Validate If Abstract

Conditional validation using guard methods.

### Complete Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "AbstractEntityCannotHaveTable",
        message = "Abstract entity cannot have table mapping"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractEntityCannotHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // This only runs for abstract entities
            if (entity.getTableName() != null && !entity.getTableName().isEmpty()) {
                return ValidationResult.fail(
                    "AbstractEntityCannotHaveTable",
                    "Abstract entity '" + entity.getName() + "' cannot have a table mapping. " +
                    "Remove the table name or make the entity concrete.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // Guard method - must have this exact signature
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        EntityType entity = (EntityType) element;
        return entity.isAbstract();
    }
}
```

### Explanation

1. **@Guard(method = "isAbstract")** - References the guard method by name
2. **Guard method signature** - Must be `boolean methodName(EObject, ValidationContext)`
3. **Guard returns true** - Validation rule executes
4. **Guard returns false** - Validation rule is skipped (returns pass automatically)
5. **Separation of concerns** - Guard checks applicability, rule checks validity

### Common Variations

**Inverse guard (concrete entities)**:
```java
@Constraint(name = "ConcreteEntityMustHaveTable", message = "...")
@Guard(method = "isConcrete")
public ValidationRule concreteEntityMustHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getTableName() == null || entity.getTableName().isEmpty()) {
            return ValidationResult.fail(
                "Concrete entity '" + entity.getName() + "' must have a table mapping. " +
                "Provide a table name or mark the entity as abstract."
            );
        }
        
        return ValidationResult.pass();
    };
}

public boolean isConcrete(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return !entity.isAbstract();
}
```

**Complex guard condition**:
```java
@Constraint(name = "MappedConcreteEntityConstraint", message = "...")
@Guard(method = "isMappedAndConcrete")
public ValidationRule mappedConcreteEntityConstraint() {
    return (element, ctx) -> {
        // Validation logic for mapped concrete entities
        return ValidationResult.pass();
    };
}

public boolean isMappedAndConcrete(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return !entity.isAbstract() && entity.getMapping() != null;
}
```

**Guard with context check**:
```java
@Constraint(name = "ValidateIfHasContainer", message = "...")
@Guard(method = "hasContainer")
public ValidationRule validateIfHasContainer() {
    return (element, ctx) -> {
        // Validation logic
        return ValidationResult.pass();
    };
}

public boolean hasContainer(EObject element, ValidationContext ctx) {
    // Check if element has a parent container
    return element.eContainer() != null;
}
```

## 4. String Validation

Pattern matching, length checking, and format validation for strings.

### Pattern Matching

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "NameMustBeValidIdentifier",
        message = "Name must be a valid identifier"
    )
    public ValidationRule nameMustBeValidIdentifier() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            if (name == null || name.isEmpty()) {
                return ValidationResult.pass(); // Let other constraint handle this
            }
            
            // Pattern: starts with letter, followed by letters/digits/underscores
            String pattern = "^[A-Za-z][A-Za-z0-9_]*$";
            
            if (!name.matches(pattern)) {
                return ValidationResult.fail(
                    "NameMustBeValidIdentifier",
                    "Entity name '" + name + "' is not a valid identifier. " +
                    "Must start with a letter and contain only letters, digits, or underscores.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Length Validation

```java
@Constraint(name = "NameLengthValid", message = "Name length must be valid")
public ValidationRule nameLengthValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        
        if (name == null || name.isEmpty()) {
            return ValidationResult.pass();
        }
        
        final int MIN_LENGTH = 2;
        final int MAX_LENGTH = 50;
        
        if (name.length() < MIN_LENGTH) {
            return ValidationResult.fail(
                "Name '" + name + "' is too short (minimum: " + MIN_LENGTH + " characters)"
            );
        }
        
        if (name.length() > MAX_LENGTH) {
            return ValidationResult.fail(
                "Name '" + name + "' is too long (maximum: " + MAX_LENGTH + " characters). " +
                "Consider using an abbreviation."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Format Validation

```java
@Constraint(name = "EmailFormatValid", message = "Email format must be valid")
public ValidationRule emailFormatValid() {
    return (element, ctx) -> {
        Contact contact = (Contact) element;
        String email = contact.getEmail();
        
        if (email == null || email.isEmpty()) {
            return ValidationResult.pass();
        }
        
        // Simple email pattern
        String emailPattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        
        if (!email.matches(emailPattern)) {
            return ValidationResult.fail(
                "Email '" + email + "' has invalid format. " +
                "Expected format: username@domain.com"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Common String Patterns

```java
@Constraint(name = "NameFollowsConventions", message = "Name must follow conventions")
public ValidationRule nameFollowsConventions() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String name = entity.getName();
        
        if (name == null || name.isEmpty()) {
            return ValidationResult.pass();
        }
        
        // PascalCase pattern
        if (!name.matches("^[A-Z][a-zA-Z0-9]*$")) {
            return ValidationResult.fail(
                "Name '" + name + "' must follow PascalCase convention " +
                "(start with uppercase, no underscores or spaces)"
            );
        }
        
        // No consecutive uppercase letters (avoid acronyms like "XMLParser")
        if (name.matches(".*[A-Z]{2,}.*")) {
            return ValidationResult.warn(
                "Name '" + name + "' contains consecutive uppercase letters. " +
                "Consider using camelCase for acronyms (e.g., 'XmlParser')"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## 5. Numeric Validation

Range checking, comparisons, and numeric constraints.

### Range Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "PriorityInValidRange",
        message = "Priority must be in valid range"
    )
    public ValidationRule priorityInValidRange() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            int priority = entity.getPriority();
            
            final int MIN_PRIORITY = 1;
            final int MAX_PRIORITY = 10;
            
            if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
                return ValidationResult.fail(
                    "PriorityInValidRange",
                    "Priority " + priority + " is out of range. " +
                    "Must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY + ".",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Positive/Negative Checks

```java
@Constraint(name = "QuantityMustBePositive", message = "Quantity must be positive")
public ValidationRule quantityMustBePositive() {
    return (element, ctx) -> {
        OrderItem item = (OrderItem) element;
        int quantity = item.getQuantity();
        
        if (quantity <= 0) {
            return ValidationResult.fail(
                "Quantity must be a positive number (got: " + quantity + ")"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Comparison Validation

```java
@Constraint(name = "MinMaxRangeValid", message = "Min/max range must be valid")
public ValidationRule minMaxRangeValid() {
    return (element, ctx) -> {
        RangeType range = (RangeType) element;
        int min = range.getMinValue();
        int max = range.getMaxValue();
        
        if (min > max) {
            return ValidationResult.fail(
                "MinMaxRangeValid",
                "Minimum value (" + min + ") cannot be greater than maximum value (" + max + "). " +
                "Swap the values or correct the range.",
                Severity.ERROR,
                element
            );
        }
        
        if (min == max) {
            return ValidationResult.warn(
                "Minimum and maximum values are equal (" + min + "). " +
                "This creates a single-value range."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Decimal Validation

```java
@Constraint(name = "PriceValid", message = "Price must be valid")
public ValidationRule priceValid() {
    return (element, ctx) -> {
        Product product = (Product) element;
        double price = product.getPrice();
        
        if (price < 0) {
            return ValidationResult.fail("Price cannot be negative (got: " + price + ")");
        }
        
        if (price == 0) {
            return ValidationResult.warn(
                "Price is zero for product '" + product.getName() + "'. " +
                "Ensure this is intentional."
            );
        }
        
        // Check decimal places (max 2 for currency)
        String priceStr = String.format("%.10f", price);
        int decimalPlaces = priceStr.split("\\.")[1].replaceAll("0*$", "").length();
        
        if (decimalPlaces > 2) {
            return ValidationResult.warn(
                "Price " + price + " has more than 2 decimal places. " +
                "Consider rounding for currency values."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## 6. Boolean Validation

Validating flag combinations and boolean logic.

### Flag Combination Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "AbstractAndFinalMutuallyExclusive",
        message = "Entity cannot be both abstract and final"
    )
    public ValidationRule abstractAndFinalMutuallyExclusive() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.isAbstract() && entity.isFinal()) {
                return ValidationResult.fail(
                    "AbstractAndFinalMutuallyExclusive",
                    "Entity '" + entity.getName() + "' cannot be both abstract and final. " +
                    "Abstract entities are meant to be extended, final entities cannot be extended.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Required Flag Combinations

```java
@Constraint(name = "PublicRequiresDocumentation", message = "...")
public ValidationRule publicRequiresDocumentation() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // If public, must be documented
        if (entity.isPublic() && !entity.isDocumented()) {
            return ValidationResult.fail(
                "Public entity '" + entity.getName() + "' must be documented. " +
                "Set the 'documented' flag or make the entity private."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Boolean Dependency Validation

```java
@Constraint(name = "DeprecatedRequiresReplacement", message = "...")
public ValidationRule deprecatedRequiresReplacement() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        
        if (operation.isDeprecated()) {
            if (operation.getReplacement() == null || operation.getReplacement().isEmpty()) {
                return ValidationResult.fail(
                    "Deprecated operation '" + operation.getName() + "' must specify a replacement. " +
                    "Set the 'replacement' field to guide users to the new API."
                );
            }
        }
        
        return ValidationResult.pass();
    };
}
```

## 7. Collection Validation

Validating collections for size, emptiness, and content.

### Not Empty Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityMustHaveAttributes",
        message = "Entity must have at least one attribute"
    )
    public ValidationRule entityMustHaveAttributes() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getAttributes() == null || entity.getAttributes().isEmpty()) {
                return ValidationResult.fail(
                    "EntityMustHaveAttributes",
                    "Entity '" + entity.getName() + "' must have at least one attribute. " +
                    "Add attributes to define the entity structure.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Size Limit Validation

```java
@Constraint(name = "AttributeCountInRange", message = "Attribute count must be in range")
public ValidationRule attributeCountInRange() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        int attributeCount = entity.getAttributes() != null 
            ? entity.getAttributes().size() 
            : 0;
        
        final int MAX_ATTRIBUTES = 50;
        
        if (attributeCount > MAX_ATTRIBUTES) {
            return ValidationResult.fail(
                "Entity '" + entity.getName() + "' has " + attributeCount + " attributes. " +
                "Maximum recommended: " + MAX_ATTRIBUTES + ". " +
                "Consider splitting into multiple entities."
            );
        }
        
        if (attributeCount > 30) {
            return ValidationResult.warn(
                "Entity '" + entity.getName() + "' has " + attributeCount + " attributes. " +
                "Consider refactoring for better maintainability."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Collection Content Validation

```java
@Constraint(name = "AllAttributesHaveType", message = "All attributes must have type")
public ValidationRule allAttributesHaveType() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getAttributes() == null || entity.getAttributes().isEmpty()) {
            return ValidationResult.pass();
        }
        
        List<String> invalidAttributes = new ArrayList<>();
        
        for (Attribute attr : entity.getAttributes()) {
            if (attr.getType() == null || attr.getType().isEmpty()) {
                invalidAttributes.add(attr.getName() != null ? attr.getName() : "(unnamed)");
            }
        }
        
        if (!invalidAttributes.isEmpty()) {
            return ValidationResult.fail(
                "AllAttributesHaveType",
                "Entity '" + entity.getName() + "' has attributes without type: " +
                String.join(", ", invalidAttributes) + ". " +
                "All attributes must have a defined type.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Uniqueness in Collection

```java
@Constraint(name = "AttributeNamesUnique", message = "Attribute names must be unique")
public ValidationRule attributeNamesUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getAttributes() == null || entity.getAttributes().isEmpty()) {
            return ValidationResult.pass();
        }
        
        Map<String, Long> nameCounts = entity.getAttributes().stream()
            .filter(attr -> attr.getName() != null)
            .collect(Collectors.groupingBy(Attribute::getName, Collectors.counting()));
        
        List<String> duplicateNames = nameCounts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        if (!duplicateNames.isEmpty()) {
            return ValidationResult.fail(
                "AttributeNamesUnique",
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
```

## 8. Enum Validation

Validating enum values and allowed choices.

### Basic Enum Validation

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "TypeMustBeValid",
        message = "Type must be a valid enum value"
    )
    public ValidationRule typeMustBeValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String type = entity.getType();
            
            if (type == null || type.isEmpty()) {
                return ValidationResult.fail("Type is required");
            }
            
            // Define valid types
            List<String> validTypes = List.of(
                "TABLE", "VIEW", "ABSTRACT", "INTERFACE"
            );
            
            if (!validTypes.contains(type.toUpperCase())) {
                return ValidationResult.fail(
                    "TypeMustBeValid",
                    "Type '" + type + "' is not valid. " +
                    "Allowed values: " + String.join(", ", validTypes),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Enum with EMF EEnum

```java
@Constraint(name = "StatusValid", message = "Status must be valid")
public ValidationRule statusValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        EntityStatus status = entity.getStatus(); // EMF enum type
        
        if (status == null) {
            return ValidationResult.fail("Status is required");
        }
        
        // Validate based on enum literal
        switch (status) {
            case DRAFT:
            case ACTIVE:
            case DEPRECATED:
                return ValidationResult.pass();
            
            case ARCHIVED:
                // Additional validation for archived status
                if (entity.getArchiveDate() == null) {
                    return ValidationResult.fail(
                        "Archived entity '" + entity.getName() + "' must have archive date"
                    );
                }
                return ValidationResult.pass();
            
            default:
                return ValidationResult.fail(
                    "Unknown status: " + status
                );
        }
    };
}
```

### Conditional Enum Validation

```java
@Constraint(name = "VisibilityValid", message = "Visibility must be valid")
public ValidationRule visibilityValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        String visibility = operation.getVisibility();
        
        if (visibility == null || visibility.isEmpty()) {
            visibility = "PUBLIC"; // Default
        }
        
        List<String> validVisibilities = List.of("PUBLIC", "PRIVATE", "PROTECTED", "INTERNAL");
        
        if (!validVisibilities.contains(visibility.toUpperCase())) {
            return ValidationResult.fail(
                "Visibility '" + visibility + "' is not valid. " +
                "Use: " + String.join(", ", validVisibilities)
            );
        }
        
        // Abstract operations cannot be private
        if ("PRIVATE".equalsIgnoreCase(visibility) && operation.isAbstract()) {
            return ValidationResult.fail(
                "Abstract operation '" + operation.getName() + "' cannot be private. " +
                "Change visibility to PUBLIC or PROTECTED."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Multiple Enum Combination

```java
@Constraint(name = "AccessLevelCombinationValid", message = "...")
public ValidationRule accessLevelCombinationValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        String scope = entity.getScope(); // PUBLIC, INTERNAL, PRIVATE
        String security = entity.getSecurity(); // OPEN, RESTRICTED, CONFIDENTIAL
        
        // PUBLIC entities cannot be CONFIDENTIAL
        if ("PUBLIC".equalsIgnoreCase(scope) && "CONFIDENTIAL".equalsIgnoreCase(security)) {
            return ValidationResult.fail(
                "Entity '" + entity.getName() + "' cannot be PUBLIC and CONFIDENTIAL. " +
                "Change scope to INTERNAL or PRIVATE."
            );
        }
        
        // PRIVATE entities should not be OPEN
        if ("PRIVATE".equalsIgnoreCase(scope) && "OPEN".equalsIgnoreCase(security)) {
            return ValidationResult.warn(
                "Entity '" + entity.getName() + "' is PRIVATE but marked OPEN. " +
                "Consider changing security to RESTRICTED."
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Complete Example - Putting It All Together

Here's a comprehensive validation class combining multiple patterns:

```java
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;
import com.example.model.*;
import java.util.*;
import java.util.stream.Collectors;

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // 1. Basic constraint - name not null
    @Constraint(name = "EntityMustHaveName", message = "Entity must have a name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().trim().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity name is required");
        };
    }
    
    // 2. Simple critique - should have description
    @Critique(name = "EntityShouldHaveDescription", message = "...")
    public ValidationRule entityShouldHaveDescription() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getDescription() != null && !entity.getDescription().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.warn("Entity '" + entity.getName() + "' should have a description");
        };
    }
    
    // 3. Guard condition - only for concrete entities
    @Constraint(name = "ConcreteEntityMustHaveTable", message = "...")
    @Guard(method = "isConcrete")
    public ValidationRule concreteEntityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTableName() != null && !entity.getTableName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity must have table name");
        };
    }
    
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
    
    // 4. String validation - pattern matching
    @Constraint(name = "NameMustBeValidIdentifier", message = "...")
    @Satisfies(constraints = {"EntityMustHaveName"})
    public ValidationRule nameMustBeValidIdentifier() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            if (!name.matches("^[A-Za-z][A-Za-z0-9_]*$")) {
                return ValidationResult.fail(
                    "Name '" + name + "' must be a valid identifier " +
                    "(letters, digits, underscores; start with letter)"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // 5. Numeric validation - range checking
    @Constraint(name = "PriorityInRange", message = "Priority must be in range")
    public ValidationRule priorityInRange() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            int priority = entity.getPriority();
            
            if (priority < 1 || priority > 10) {
                return ValidationResult.fail("Priority must be between 1 and 10 (got: " + priority + ")");
            }
            
            return ValidationResult.pass();
        };
    }
    
    // 6. Boolean validation - flag combination
    @Constraint(name = "AbstractAndFinalMutuallyExclusive", message = "...")
    public ValidationRule abstractAndFinalMutuallyExclusive() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.isAbstract() && entity.isFinal()) {
                return ValidationResult.fail("Entity cannot be both abstract and final");
            }
            
            return ValidationResult.pass();
        };
    }
    
    // 7. Collection validation - not empty
    @Constraint(name = "ConcreteEntityMustHaveAttributes", message = "...")
    @Guard(method = "isConcrete")
    public ValidationRule concreteEntityMustHaveAttributes() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getAttributes() == null || entity.getAttributes().isEmpty()) {
                return ValidationResult.fail("Concrete entity must have at least one attribute");
            }
            
            return ValidationResult.pass();
        };
    }
    
    // 8. Enum validation - valid type
    @Constraint(name = "TypeMustBeValid", message = "Type must be valid")
    public ValidationRule typeMustBeValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String type = entity.getType();
            
            List<String> validTypes = List.of("TABLE", "VIEW", "ABSTRACT", "INTERFACE");
            
            if (type == null || !validTypes.contains(type.toUpperCase())) {
                return ValidationResult.fail(
                    "Type must be one of: " + String.join(", ", validTypes)
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Testing Your Validations

Here's how to test these validation rules:

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EntityTypeValidationsTest {
    
    @Test
    public void testEntityMustHaveName() {
        ValidationRegistry registry = new ValidationRegistry();
        registry.register(EntityTypeValidations.class);
        
        ValidationExecutor executor = ValidationExecutor.builder()
            .registry(registry)
            .build();
        
        // Create entity without name
        EntityType entity = createEntity(null);
        
        List<ValidationResult> results = executor.validate(List.of(entity));
        
        // Should fail
        assertFalse(results.stream().allMatch(ValidationResult::isValid));
        assertTrue(results.stream()
            .anyMatch(r -> "EntityMustHaveName".equals(r.getConstraintName())));
    }
    
    @Test
    public void testNamePattern() {
        EntityType entity1 = createEntity("ValidName");
        EntityType entity2 = createEntity("123Invalid");
        EntityType entity3 = createEntity("Invalid-Name");
        
        ValidationExecutor executor = createExecutor();
        
        assertTrue(executor.validate(List.of(entity1)).stream()
            .allMatch(ValidationResult::isValid));
        
        assertFalse(executor.validate(List.of(entity2)).stream()
            .allMatch(ValidationResult::isValid));
        
        assertFalse(executor.validate(List.of(entity3)).stream()
            .allMatch(ValidationResult::isValid));
    }
}
```

## Related Topics

- **[Getting Started](../getting-started.md)** - Introduction and first validation
- **[Validation Rules Guide](../user-guide/validation-rules.md)** - Advanced rule patterns
- **[Guards and Dependencies](../user-guide/guards-and-dependencies.md)** - Complex control flow
- **[Best Practices](../best-practices/error-messages.md)** - Production-ready patterns

## Summary

This guide covered the eight most common validation patterns:

1. **Basic Constraints** - Required field validation
2. **Simple Critiques** - Warning-level recommendations
3. **Guard Conditions** - Conditional validation based on properties
4. **String Validation** - Patterns, length, format checking
5. **Numeric Validation** - Range and comparison validation
6. **Boolean Validation** - Flag combination checking
7. **Collection Validation** - Size, emptiness, content validation
8. **Enum Validation** - Allowed values and combinations

Use these examples as templates for your own validations. Start simple, then combine patterns for more sophisticated validation logic.

---

**Previous**: [Documentation Hub](../index.md) | **Next**: [Entity Type Validations](entity-type-validations.md)
