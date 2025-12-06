# Error Message Best Practices

**Navigation**: [Documentation Hub](../../index.md) > [Best Practices](../index.md#best-practices) > Error Messages

Writing clear, actionable error messages is crucial for helping developers quickly identify and fix validation issues. This guide covers best practices, message interpolation techniques, and consistency patterns for the Judo Zeta Validation Framework.

## Core Principles

### 1. Be Clear and Specific

Error messages should clearly state what went wrong without technical jargon or vague descriptions.

```java
// ❌ BAD: Vague and unhelpful
@Constraint(name = "Invalid", message = "Invalid")
public ValidationRule checkValidity() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() == null
            ? ValidationResult.fail("Invalid")
            : ValidationResult.pass();
    };
}

// ✅ GOOD: Clear and specific
@Constraint(name = "EntityMustHaveName", message = "Entity must have a name")
public ValidationRule entityMustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() == null
            ? ValidationResult.fail("Entity must have a name")
            : ValidationResult.pass();
    };
}
```

### 2. Include Context

Always include relevant element names, values, and locations to help users identify the problem.

```java
// ❌ BAD: No context about which entity
return ValidationResult.fail("Name is required");

// ✅ GOOD: Includes entity identification
return ValidationResult.fail(
    "Entity in package '" + pkg.getName() + "' must have a name"
);

// ✅ BETTER: Full context with location
EntityType entity = (EntityType) element;
EObject container = entity.eContainer();
return ValidationResult.fail(
    "Entity at index " + container.eContents().indexOf(entity) + 
    " in package '" + ((Package) container).getName() + "' must have a name"
);
```

### 3. Be Actionable

Tell users not just what's wrong, but how to fix it.

```java
// ❌ BAD: States problem but no solution
return ValidationResult.fail("Circular inheritance detected");

// ✅ GOOD: Explains the issue and suggests a fix
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' has circular inheritance: " +
    buildInheritanceChain(entity) + ". " +
    "Remove one of the inheritance relationships to break the cycle."
);

// ✅ GOOD: Provides specific remediation steps
return ValidationResult.fail(
    "Operation '" + operation.getName() + "' has invalid return type '" + 
    returnType + "'. Use a primitive type (String, Integer, Boolean, Date, Decimal) " +
    "or reference an existing entity type."
);
```

### 4. Use Consistent Terminology

Stick to the same terms throughout all validation messages.

```java
// ❌ BAD: Inconsistent terminology
"Entity must have a name"
"EntityType requires an identifier"
"Class needs to be named"

// ✅ GOOD: Consistent terminology
"Entity must have a name"
"Entity must have a unique name"
"Entity must have a valid name"
```

### 5. Avoid Technical Jargon

Write messages for model designers, not framework developers.

```java
// ❌ BAD: Technical implementation details
return ValidationResult.fail(
    "EReference 'superType' constraint violation in EClass hierarchy"
);

// ✅ GOOD: User-friendly language
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' cannot inherit from concrete entity '" + 
    superType.getName() + "'. Supertype must be abstract."
);
```

## Message Interpolation

The framework supports multiple approaches to creating dynamic messages.

### String Concatenation

The most direct approach using Java's string concatenation.

```java
@Constraint(
    name = "EntityMustHaveName",
    message = "Entity must have a name"
)
public ValidationRule entityMustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null || entity.getName().isEmpty()) {
            // Build message with concatenation
            return ValidationResult.fail(
                "EntityMustHaveName",
                "Entity in package '" + getPackageName(entity) + "' must have a name",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### String.format()

Use Java's String.format() for complex interpolation.

```java
@Constraint(
    name = "NameMustBeUnique",
    message = "Entity name must be unique"
)
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        long duplicateCount = countDuplicateNames(entity, ctx);
        
        if (duplicateCount > 1) {
            return ValidationResult.fail(
                "NameMustBeUnique",
                String.format(
                    "Entity name '%s' is used %d times. Each entity must have a unique name.",
                    entity.getName(),
                    duplicateCount
                ),
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### MessageFormat for Complex Cases

Use java.text.MessageFormat for numbered placeholders.

```java
import java.text.MessageFormat;

@Constraint(
    name = "ValidOperationSignature",
    message = "Operation signature must be valid"
)
public ValidationRule validOperationSignature() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        
        if (hasInvalidParameters(operation)) {
            String message = MessageFormat.format(
                "Operation ''{0}'' in entity ''{1}'' has {2} invalid parameter(s). " +
                "Each parameter must have a name and type.",
                operation.getName(),
                ((EntityType) operation.eContainer()).getName(),
                countInvalidParameters(operation)
            );
            
            return ValidationResult.fail(
                "ValidOperationSignature",
                message,
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Template Placeholders in Annotations

The annotation's message field supports simple placeholder interpolation.

```java
@Constraint(
    name = "EntityTypeHasMapping",
    message = "Entity type: {element.name} must have mapping"
)
public ValidationRule entityTypeHasMapping() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // The annotation message is used as a template
        // {element.name} is automatically replaced
        return entity.getMapping() != null
            ? ValidationResult.pass()
            : ValidationResult.fail(
                "Entity type: " + entity.getName() + " must have mapping"
            );
    };
}
```

### Building Complex Messages

For complex scenarios, build messages incrementally.

```java
@Constraint(
    name = "ValidAttributeDefinition",
    message = "Attribute definition must be valid"
)
public ValidationRule validAttributeDefinition() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        List<String> errors = new ArrayList<>();
        
        if (attr.getName() == null || attr.getName().isEmpty()) {
            errors.add("missing name");
        }
        
        if (attr.getType() == null) {
            errors.add("missing type");
        }
        
        if (!attr.isPrimaryKey() && !attr.isForeignKey() && attr.isNullable() == null) {
            errors.add("nullable property not set");
        }
        
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append("Attribute");
            
            if (attr.getName() != null) {
                message.append(" '").append(attr.getName()).append("'");
            }
            
            EntityType owner = (EntityType) attr.eContainer();
            message.append(" in entity '").append(owner.getName()).append("'");
            message.append(" has validation errors: ");
            message.append(String.join(", ", errors));
            message.append(".");
            
            return ValidationResult.fail(
                "ValidAttributeDefinition",
                message.toString(),
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Message Patterns and Templates

### Pattern: Required Field Missing

```java
// Template: "[Element type] '[element name]' must have [field name]"
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' must have a description"
);

return ValidationResult.fail(
    "Attribute '" + attr.getName() + "' must have a type"
);

return ValidationResult.fail(
    "Operation '" + op.getName() + "' must have a return type"
);
```

### Pattern: Uniqueness Violation

```java
// Template: "[Element type] [field] '[value]' is already used by [other element]"
return ValidationResult.fail(
    "Entity name '" + entity.getName() + "' is already used by another entity in package '" + 
    pkg.getName() + "'"
);

return ValidationResult.fail(
    "Attribute name '" + attr.getName() + "' is already used in entity '" + 
    entity.getName() + "'"
);

// With duplicate count
return ValidationResult.fail(
    "Entity name '" + entity.getName() + "' is used " + count + 
    " times. Each entity must have a unique name."
);
```

### Pattern: Invalid Reference

```java
// Template: "[Element] '[name]' references [target] '[target name]' which [problem]"
return ValidationResult.fail(
    "Foreign key '" + attr.getName() + "' references entity '" + 
    referencedEntity + "' which does not exist in the model"
);

return ValidationResult.fail(
    "Entity '" + entity.getName() + "' extends '" + superType.getName() + 
    "' which is not abstract"
);

return ValidationResult.fail(
    "Operation '" + operation.getName() + "' returns type '" + returnType + 
    "' which is not a valid primitive or entity type"
);
```

### Pattern: Constraint Violation

```java
// Template: "[Element] '[name]' violates constraint: [constraint description]"
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' violates constraint: " +
    "concrete entities must have at least one primary key attribute"
);

return ValidationResult.fail(
    "Attribute '" + attr.getName() + "' violates constraint: " +
    "primary key attributes cannot be nullable"
);
```

### Pattern: Circular Dependency

```java
// Template: "[Element] '[name]' has circular [relationship]: [chain]"
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' has circular inheritance: " +
    buildChain(entity) + ". Remove one inheritance relationship to break the cycle."
);

return ValidationResult.fail(
    "Package '" + pkg.getName() + "' has circular dependency: " +
    buildDependencyChain(pkg) + ". Remove one package import to break the cycle."
);
```

### Pattern: Invalid State Combination

```java
// Template: "[Element] '[name]' cannot be both [state A] and [state B]"
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' cannot be both abstract and have a table mapping"
);

return ValidationResult.fail(
    "Attribute '" + attr.getName() + "' cannot be both a primary key and a foreign key"
);
```

### Pattern: Container Validation

When validating elements in context of their container:

```java
// Template: "Container of [element]: [container path] [constraint]"
return ValidationResult.fail(
    "Container of abstract operation: " + 
    entity.getName() + "." + operation.getName() + 
    " must be an abstract entity type"
);

return ValidationResult.fail(
    "Container of attribute '" + attr.getName() + "': entity '" + 
    entity.getName() + "' must not be abstract"
);
```

## Good vs Bad Examples

### Example 1: Missing Required Field

```java
// ❌ BAD: Generic, no context
return ValidationResult.fail("Required field missing");

// ❌ BAD: Mentions field but not element
return ValidationResult.fail("Name is required");

// ✅ GOOD: Identifies element and field
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' must have a table name"
);

// ✅ EXCELLENT: Includes context and suggestion
return ValidationResult.fail(
    "Concrete entity '" + entity.getName() + "' must have a table name. " +
    "Set the table name or mark the entity as abstract."
);
```

### Example 2: Type Mismatch

```java
// ❌ BAD: Technical jargon
return ValidationResult.fail("Type system constraint violation");

// ❌ BAD: Incomplete information
return ValidationResult.fail("Invalid type");

// ✅ GOOD: Clear type mismatch
return ValidationResult.fail(
    "Attribute '" + attr.getName() + "' has invalid type '" + type + "'"
);

// ✅ EXCELLENT: Explains what's valid
return ValidationResult.fail(
    "Attribute '" + attr.getName() + "' has invalid type '" + type + "'. " +
    "Type must be a primitive (String, Integer, Boolean, Date, Decimal) " +
    "or an existing entity type."
);
```

### Example 3: Duplicate Names

```java
// ❌ BAD: No identification
return ValidationResult.fail("Duplicate name found");

// ❌ BAD: Partial information
return ValidationResult.fail("Name '" + name + "' is duplicated");

// ✅ GOOD: Shows element and scope
return ValidationResult.fail(
    "Entity name '" + entity.getName() + "' is already used in package '" + 
    pkg.getName() + "'"
);

// ✅ EXCELLENT: Shows count and suggests fix
return ValidationResult.fail(
    "Entity name '" + entity.getName() + "' is used " + count + " times " +
    "in package '" + pkg.getName() + "'. " +
    "Choose a unique name like '" + entity.getName() + "Record' or '" + 
    entity.getName() + "Entity'."
);
```

### Example 4: Inheritance Issues

```java
// ❌ BAD: Cryptic message
return ValidationResult.fail("Inheritance error");

// ❌ BAD: States problem without details
return ValidationResult.fail("Circular inheritance detected");

// ✅ GOOD: Shows inheritance chain
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' has circular inheritance: " +
    buildChain(entity)
);

// ✅ EXCELLENT: Shows chain and solution
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' has circular inheritance: " +
    entity.getName() + " → " + superType.getName() + " → " + 
    superSuperType.getName() + " → " + entity.getName() + ". " +
    "Remove '" + superType.getName() + "' from the inheritance chain to break the cycle."
);
```

### Example 5: Constraint Dependencies

```java
// ❌ BAD: No explanation of dependency
return ValidationResult.fail("Constraint violation");

// ❌ BAD: Mentions constraint but not why
return ValidationResult.fail("Primary key constraint failed");

// ✅ GOOD: Explains the constraint
return ValidationResult.fail(
    "Concrete entity '" + entity.getName() + "' must have at least one primary key attribute"
);

// ✅ EXCELLENT: Explains constraint and implications
return ValidationResult.fail(
    "Concrete entity '" + entity.getName() + "' must have at least one primary key attribute. " +
    "Add a primary key attribute or mark the entity as abstract if it's meant to be a base class."
);
```

## Warnings vs Errors

Use different message styles for critiques (warnings) vs constraints (errors).

### Constraint Messages (Errors)

Use imperative, definitive language:

```java
@Constraint(name = "MustHaveName", message = "Entity must have a name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        return entity.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have a name");  // "must"
    };
}
```

### Critique Messages (Warnings)

Use suggestive, advisory language:

```java
@Critique(name = "ShouldHaveDescription", message = "Entity should have description")
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getDescription() == null || entity.getDescription().isEmpty()) {
            return ValidationResult.warn(
                "Entity '" + entity.getName() + "' should have a description " +  // "should"
                "for better documentation"
            );
        }
        
        return ValidationResult.pass();
    };
}

@Critique(name = "ConsiderShorterName", message = "Consider using shorter name")
public ValidationRule considerShorterName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName().length() > 30) {
            return ValidationResult.warn(
                "Entity name '" + entity.getName() + "' is " + 
                entity.getName().length() + " characters long. " +
                "Consider using a shorter name (< 30 characters) " +  // "consider"
                "for better readability"
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Localization Considerations

While the framework doesn't currently support i18n, write messages with future localization in mind.

### Avoid Concatenation Order Issues

```java
// ❌ BAD: Hard to localize word order
return ValidationResult.fail(
    entity.getName() + " must have a name"  // English word order
);

// ✅ GOOD: Complete phrase with placeholder
return ValidationResult.fail(
    "Entity '" + entity.getName() + "' must have a name"
);

// ✅ BETTER: Using MessageFormat for future i18n
return ValidationResult.fail(
    MessageFormat.format(
        "Entity ''{0}'' must have a name",
        entity.getName()
    )
);
```

### Separate Data from Text

```java
// ❌ BAD: Mixing data formatting with message
return ValidationResult.fail(
    "Found " + count + " error(s)"  // Plural form hardcoded
);

// ✅ GOOD: Explicit plural handling
String errorWord = count == 1 ? "error" : "errors";
return ValidationResult.fail(
    "Found " + count + " " + errorWord
);

// ✅ BETTER: Avoid plural issues entirely
return ValidationResult.fail(
    "Found " + count + " validation error(s)"
);
```

### Use Neutral Formatting

```java
// ❌ BAD: Culture-specific formatting
return ValidationResult.fail(
    "Limit exceeded: " + (value * 100.0) + "%"  // Decimal separator varies
);

// ✅ GOOD: Use Java's formatting
return ValidationResult.fail(
    String.format("Limit exceeded: %.2f%%", value * 100.0)
);
```

## Consistency Guidelines

### Capitalization

- **Start with capital letter**: "Entity must have a name"
- **No ending punctuation**: Don't add periods to messages
- **Proper nouns**: Capitalize metamodel element types: "Entity", "Attribute", "Operation"

```java
// ✅ GOOD: Consistent capitalization
"Entity must have a name"
"Attribute must have a type"
"Operation must have a return type"

// ❌ BAD: Inconsistent capitalization
"entity must have a name"
"Attribute Must Have A Type"
"OPERATION must have a return type"
```

### Element References

- **Use single quotes** for element names: `'CustomerEntity'`
- **Use backticks** for code/identifiers in Markdown contexts
- **Be consistent** across all messages

```java
// ✅ GOOD: Quoted element names
"Entity 'Customer' must have a primary key"
"Attribute 'customerId' has invalid type 'Foo'"

// ❌ BAD: Inconsistent quoting
"Entity Customer must have a primary key"
"Attribute `customerId` has invalid type Foo"
```

### Message Structure

Follow a consistent pattern: `[Element identification] [problem statement] [optional: solution]`

```java
// Pattern: Element + Problem
"Entity 'Customer' must have a name"

// Pattern: Element + Problem + Solution
"Entity 'Customer' must have a primary key. Add a primary key attribute or mark as abstract."

// Pattern: Container + Element + Problem
"Container of abstract operation: CustomerEntity.getOrders must be an abstract entity type"
```

## Testing Your Messages

Ensure your error messages are helpful by testing them:

```java
@Test
public void testErrorMessageClarity() {
    EntityType entity = createEntity("Customer");
    entity.setName(null);
    
    ValidationResult result = validator.validate(entity);
    
    // Message should identify the element
    assertTrue(result.getMessage().contains("Customer"));
    
    // Message should state the problem
    assertTrue(result.getMessage().contains("must have a name"));
    
    // Message should be actionable
    assertFalse(result.getMessage().equals("Invalid"));
}
```

## Quick Reference

### Message Do's

- ✅ Include element names and identifiers
- ✅ State what's wrong clearly
- ✅ Explain how to fix it
- ✅ Use consistent terminology
- ✅ Write for your users, not developers
- ✅ Test messages with real users

### Message Don'ts

- ❌ Use vague terms like "invalid" or "error"
- ❌ Use technical jargon (EReference, EClass, etc.)
- ❌ Write messages without context
- ❌ Leave users guessing how to fix issues
- ❌ Mix different styles or terminology
- ❌ Assume users know the metamodel internals

## Related Topics

- [Writing Validation Rules](../user-guide/validation-rules.md) - Core validation patterns
- [Core Concepts](../user-guide/core-concepts.md) - Framework fundamentals
- [Annotations Reference](../reference/annotations.md) - Complete annotation guide
- [Troubleshooting](../reference/troubleshooting.md) - Common issues and solutions

---

**Previous**: [Extension Delegation](extension-delegation.md) | **Next**: [Performance](performance.md)
