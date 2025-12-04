# Getting Started with Judo Zeta Validation

**Navigation**: [Documentation Hub](index.md) > Getting Started

This guide will help you install the Judo Zeta Validation Framework and write your first validation rule in under 10 minutes.

## Prerequisites

- Java 21 JDK (Zulu, Temurin, or Oracle)
- Maven 3.9.4+ or your build tool of choice
- An EMF metamodel (e.g., Ecore model)
- Basic understanding of Java and EMF

## Installation

### Maven Dependency

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>hu.blackbelt.judo.zeta.validation-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Eclipse P2 Update Site

For Eclipse IDE plugin installation:

1. Open **Help → Install New Software**
2. Click **Add...** and enter:
   - **Name**: `Judo Zeta Validation`
   - **Location**: `https://nexus.judo.technology/repository/p2-judong/judo-zeta/develop/`
3. Select **Judo Zeta Validation Framework**
4. Click **Next**, accept licenses, and **Finish**
5. Restart Eclipse

### OSGi Bundle (Apache Karaf)

```bash
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.validation-core/1.0.0-SNAPSHOT
```

## Your First Validation Rule

Let's create a simple validation for an `EntityType` metamodel element that ensures every entity has a name.

### Step 1: Create a Validation Class

```java
package com.example.validation;

import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import com.example.model.EntityType; // Your metamodel class

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityMustHaveName",
        message = "Entity must have a name"
    )
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getName() == null || entity.getName().isEmpty()) {
                return ValidationResult.fail("Entity name is required");
            }
            
            return ValidationResult.pass();
        };
    }
}
```

**What's happening here?**

1. **`@ValidationContext(EntityType.class)`** - Declares this class contains validation rules for `EntityType` elements
2. **`@Constraint`** - Marks this as an error-level validation rule (must be fixed)
3. **`name`** - Unique identifier for this constraint
4. **`message`** - Default error message (can be overridden in the rule)
5. **`ValidationRule`** - Functional interface that takes an element and context, returns a result
6. **`ValidationResult.fail()`** - Creates a failing result with an error message
7. **`ValidationResult.pass()`** - Creates a passing result

### Step 2: Register and Execute Validation

```java
import hu.blackbelt.judo.zeta.validation.core.*;
import java.util.List;

public class ValidationExample {
    
    public static void main(String[] args) {
        // 1. Create a registry and register your validation class
        ValidationRegistry registry = new ValidationRegistry();
        registry.register(EntityTypeValidations.class);
        
        // 2. Create an executor
        ValidationExecutor executor = ValidationExecutor.builder()
            .registry(registry)
            .build();
        
        // 3. Load your model elements (example)
        List<EObject> modelElements = loadYourModel();
        
        // 4. Execute validation
        List<ValidationResult> results = executor.validate(modelElements);
        
        // 5. Process results
        results.stream()
            .filter(r -> !r.isValid())
            .forEach(r -> {
                System.err.println(
                    r.getSeverity() + ": " + r.getMessage() + 
                    " [" + r.getConstraintName() + "]"
                );
            });
        
        // 6. Check if validation passed
        boolean allValid = results.stream().allMatch(ValidationResult::isValid);
        System.out.println("Validation " + (allValid ? "PASSED" : "FAILED"));
    }
}
```

### Step 3: Run and See Results

When you run this code with an `EntityType` that has no name, you'll see:

```
ERROR: Entity name is required [EntityMustHaveName]
Validation FAILED
```

## Adding a Warning (Critique)

Let's add a warning that recommends entities should have a description:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "EntityMustHaveName", message = "Entity must have a name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity name is required");
        };
    }
    
    // NEW: Warning-level validation
    @Critique(
        name = "EntityShouldHaveDescription",
        message = "Entity should have a description"
    )
    public ValidationRule entityShouldHaveDescription() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getDescription() != null && !entity.getDescription().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.warn("Consider adding a description for better documentation");
        };
    }
}
```

Output will now include:

```
ERROR: Entity name is required [EntityMustHaveName]
WARNING: Consider adding a description for better documentation [EntityShouldHaveDescription]
Validation FAILED
```

## Adding Conditional Validation (Guards)

Sometimes you only want to validate certain elements. Use guards:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Only validate non-abstract entities
    @Constraint(
        name = "ConcreteEntityMustHaveTable",
        message = "Concrete entity must have table name"
    )
    @Guard(method = "isNotAbstract")
    public ValidationRule concreteEntityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTableName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity requires table name");
        };
    }
    
    // Guard method
    private boolean isNotAbstract(
        org.eclipse.emf.ecore.EObject element,
        hu.blackbelt.judo.zeta.validation.core.ValidationContext ctx
    ) {
        EntityType entity = (EntityType) element;
        return !entity.isAbstract();
    }
}
```

The `concreteEntityMustHaveTable` rule will only run for entities where `isNotAbstract()` returns `true`.

## Declaring Dependencies Between Rules

Use `@Satisfies` to ensure one rule only runs if another passes:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "EntityMustHaveName", message = "Entity must have a name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity name is required");
        };
    }
    
    // This rule only runs if EntityMustHaveName passed
    @Constraint(
        name = "NameMustBeUnique",
        message = "Entity name must be unique"
    )
    @Satisfies(constraints = {"EntityMustHaveName"})
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Safe to use getName() here because EntityMustHaveName already passed
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            long count = allEntities.stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
                
            return count == 1
                ? ValidationResult.pass()
                : ValidationResult.fail("Duplicate entity name: " + entity.getName());
        };
    }
}
```

## Quick Reference Card

| Pattern | Code |
|---------|------|
| **Error-level rule** | `@Constraint(name = "RuleName", message = "...")` |
| **Warning-level rule** | `@Critique(name = "RuleName", message = "...")` |
| **Conditional rule** | `@Guard(method = "guardMethodName")` |
| **Rule dependency** | `@Satisfies(constraints = {"OtherRule"})` |
| **Pass result** | `ValidationResult.pass()` |
| **Fail result** | `ValidationResult.fail("error message")` |
| **Warn result** | `ValidationResult.warn("warning message")` |
| **Get all instances** | `ctx.getAllInstances(MyType.class)` |

## Next Steps

Now that you have a basic validation working, explore:

- **[Core Concepts](user-guide/core-concepts.md)** - Deep dive into annotations and validation context
- **[Writing Validation Rules](user-guide/validation-rules.md)** - Advanced rule patterns
- **[Examples](examples/simple-validations.md)** - More real-world examples
- **[Best Practices](best-practices/constants.md)** - Production-ready patterns

## Common Setup Patterns

### OSGi Declarative Services

```java
import org.osgi.service.component.annotations.*;

@Component(immediate = true)
public class ModelValidator {
    
    @Reference
    private ModelProvider modelProvider;
    
    @Activate
    public void activate() {
        ValidationRegistry registry = new ValidationRegistry();
        registry.register(EntityTypeValidations.class);
        
        ValidationExecutor executor = ValidationExecutor.builder()
            .registry(registry)
            .build();
            
        List<ValidationResult> results = executor.validate(
            modelProvider.getModel().getContents()
        );
        
        // Process results...
    }
}
```

### Multiple Validation Classes

```java
ValidationRegistry registry = new ValidationRegistry();
registry.register(EntityTypeValidations.class);
registry.register(AttributeValidations.class);
registry.register(OperationValidations.class);
registry.register(RelationshipValidations.class);
```

### Parallel Execution for Large Models

```java
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(5000)  // Enable parallel for models with 5000+ elements
    .chunkSize(100)           // Process 100 elements per work unit
    .build();
```

## Troubleshooting

**Problem**: Rules not executing

**Solution**: Make sure:
1. Class has `@ValidationContext` annotation
2. Class is registered with `registry.register(MyClass.class)`
3. Elements being validated match the context type

**Problem**: `ClassNotFoundException` in OSGi

**Solution**: Install required EMF bundles:
```bash
bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.ecore/2.38.0
bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.common/2.41.0
```

---

**Previous**: [Documentation Hub](index.md) | **Next**: [Core Concepts](user-guide/core-concepts.md)
