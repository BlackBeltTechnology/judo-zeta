# Migration Guide: EVL to Zeta

**Navigation**: [Documentation Hub](../index.md) > [EVL Comparison](overview.md) > Migration Guide

This guide provides a step-by-step process for migrating validation rules from Epsilon Validation Language (EVL) to the Judo Zeta validation framework, using a real-world example from the ESM operation validations.

## Table of Contents

1. [Migration Process Overview](#migration-process-overview)
2. [Step-by-Step Migration Example](#step-by-step-migration-example)
3. [Common Patterns and Their Equivalents](#common-patterns-and-their-equivalents)
4. [Migration Checklist](#migration-checklist)
5. [Testing Migrated Validations](#testing-migrated-validations)
6. [Common Pitfalls and Solutions](#common-pitfalls-and-solutions)

## Migration Process Overview

### High-Level Migration Steps

```
EVL File (operation.evl)
         ↓
1. Analyze EVL contexts and rules
         ↓
2. Create Java validation class(es)
         ↓
3. Convert each context → @ValidationContext
         ↓
4. Convert each constraint/critique → @Constraint/@Critique methods
         ↓
5. Convert guards → @Guard annotations or inline checks
         ↓
6. Convert satisfies → @Satisfies dependencies
         ↓
7. Migrate helper functions → @ExtensionMethod
         ↓
8. Test and verify behavior
         ↓
Java Validation Class (OperationValidations.java)
```

### Key Transformation Rules

| EVL Construct | Zeta Equivalent |
|---------------|-----------------|
| `context ESM!Operation { ... }` | `@ValidationContext(Operation.class)` |
| `constraint Name { ... }` | `@Constraint(name = "Name", message = "...")` |
| `critique Name { ... }` | `@Critique(name = "Name", message = "...")` |
| `guard: expression` | `@Guard(method = "guardMethodName")` |
| `guard: self.satisfies("Rule")` | `@Satisfies(constraints = {"Rule"})` |
| `check: expression` | `return expression ? pass() : fail(...)` |
| `message: "..."` | `return ValidationResult.fail("...")` |
| EVL helper operations | `@ExtensionMethod` or static utility methods |

### Prerequisites

Before migrating, ensure you have:
- ✓ EMF metamodel classes available in Java
- ✓ Judo Zeta validation-core dependency added
- ✓ Understanding of the EVL validation logic
- ✓ Test cases or expected validation behavior documented

## Step-by-Step Migration Example

We'll migrate the abstract operation validations from `operation.evl` to Java. This is a complex example with guards, dependencies, and helper method calls.

### Original EVL Code

```evl
context ESM!Operation {

    guard: self.satisfies("NamedElementHasContainer") 
           and self.operationType == ESM!OperationType#ABSTRACT
    
    constraint AbstractOperationBelongsToEntityType {
        check: self.eContainer.isKindOf(ESM!EntityType)
        message: "Container of abstract operation: " + self.eContainer.name + "." + 
                 self.name + " must be an abstract entity type"
    }
    
    constraint AbstractOperationBelongsToAbstractEntityType {
        guard: self.satisfies("AbstractOperationBelongsToEntityType")
        check: self.eContainer.`abstract`
        message: "Container of abstract operation: " + self.eContainer.name + "." + 
                 self.name + " must be an abstract entity type"
    }
    
    constraint AbstractOperationIsValid {
        check: esmUtils.getInheritedNonAbstractOperationsByName(
                   self.eContainer, self.name).size() == 0
        message: "Operation: " + self.eContainer.name + "." + self.name + 
                 " cannot be abstract, if it has non abstract bases."
    }

    critique AbstractOperationCannotHaveImplementation {
        guard: self.satisfies("AbstractOperationIsValid")
        check: self.body.isUndefined() or self.body.trim().length() == 0
        message: "Abstract operation: " + self.eContainer.name + "." + self.name + 
                 " should not have implementation."
    }
}
```

### Step 1: Create the Java Class Structure

First, create a Java class for operation validations:

```java
package hu.blackbelt.judo.meta.esm.validation;

import hu.blackbelt.judo.meta.esm.runtime.Operation;
import hu.blackbelt.judo.meta.esm.runtime.OperationType;
import hu.blackbelt.judo.meta.esm.runtime.EntityType;
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;

/**
 * Validation rules for ESM Operation elements.
 * Migrated from operation.evl
 */
@ValidationContext(Operation.class)
public class OperationValidations {
    
    // Validation rules will go here
    
}
```

### Step 2: Convert Context-Level Guard

The EVL context has a guard that filters operations by type. In Zeta, this becomes a guard method used by multiple rules.

**EVL:**
```evl
context ESM!Operation {
    guard: self.satisfies("NamedElementHasContainer") 
           and self.operationType == ESM!OperationType#ABSTRACT
    // ...
}
```

**Zeta:**
```java
/**
 * Guard method: Only validate abstract operations
 */
public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    
    // First check if container exists (equivalent to satisfies("NamedElementHasContainer"))
    if (operation.eContainer() == null) {
        return false;
    }
    
    // Then check operation type
    return operation.getOperationType() == OperationType.ABSTRACT;
}
```

### Step 3: Convert First Constraint

**EVL:**
```evl
constraint AbstractOperationBelongsToEntityType {
    check: self.eContainer.isKindOf(ESM!EntityType)
    message: "Container of abstract operation: " + self.eContainer.name + "." + 
             self.name + " must be an abstract entity type"
}
```

**Zeta:**
```java
@Guard(method = "isAbstractOperation")
@Constraint(
    name = "AbstractOperationBelongsToEntityType",
    message = "Container of abstract operation must be an entity type"
)
public ValidationRule abstractOperationBelongsToEntityType() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EObject container = operation.eContainer();
        
        // Check if container is an EntityType
        if (!(container instanceof EntityType)) {
            EntityType entityContainer = (EntityType) container;
            return ValidationResult.fail(
                "AbstractOperationBelongsToEntityType",
                "Container of abstract operation: " + entityContainer.getName() + 
                "." + operation.getName() + " must be an entity type",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Step 4: Convert Constraint with Satisfies Dependency

**EVL:**
```evl
constraint AbstractOperationBelongsToAbstractEntityType {
    guard: self.satisfies("AbstractOperationBelongsToEntityType")
    check: self.eContainer.`abstract`
    message: "Container of abstract operation: " + self.eContainer.name + "." + 
             self.name + " must be an abstract entity type"
}
```

**Zeta:**
```java
@Guard(method = "isAbstractOperation")
@Satisfies(constraints = {"AbstractOperationBelongsToEntityType"})
@Constraint(
    name = "AbstractOperationBelongsToAbstractEntityType",
    message = "Container of abstract operation must be abstract"
)
public ValidationRule abstractOperationBelongsToAbstractEntityType() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType container = (EntityType) operation.eContainer();
        
        // Check if container is abstract
        if (!container.isAbstract()) {
            return ValidationResult.fail(
                "AbstractOperationBelongsToAbstractEntityType",
                "Container of abstract operation: " + container.getName() + 
                "." + operation.getName() + " must be an abstract entity type",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Key Transformation:**
- `guard: self.satisfies("...")` → `@Satisfies(constraints = {"..."})`
- The Zeta framework ensures this rule only runs after `AbstractOperationBelongsToEntityType` passes
- If the dependency fails, this rule is automatically skipped

### Step 5: Convert Constraint with Helper Method Call

**EVL:**
```evl
constraint AbstractOperationIsValid {
    check: esmUtils.getInheritedNonAbstractOperationsByName(
               self.eContainer, self.name).size() == 0
    message: "Operation: " + self.eContainer.name + "." + self.name + 
             " cannot be abstract, if it has non abstract bases."
}
```

**Zeta - Option A: Extension Method**
```java
// First, define the extension method
@ExtensionMethod(elementType = EntityType.class)
public List<Operation> getInheritedNonAbstractOperationsByName(
        EntityType entityType, String operationName) {
    List<Operation> result = new ArrayList<>();
    
    // Walk up the inheritance hierarchy
    EntityType current = entityType.getSuperType();
    while (current != null) {
        current.getOperations().stream()
            .filter(op -> op.getName().equals(operationName))
            .filter(op -> op.getOperationType() != OperationType.ABSTRACT)
            .forEach(result::add);
        current = current.getSuperType();
    }
    
    return result;
}

// Then use it in the validation rule
@Guard(method = "isAbstractOperation")
@Constraint(
    name = "AbstractOperationIsValid",
    message = "Operation cannot be abstract if it has non-abstract bases"
)
public ValidationRule abstractOperationIsValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType container = (EntityType) operation.eContainer();
        
        // Call the extension method
        List<Operation> inheritedNonAbstract = 
            ctx.callExtension("getInheritedNonAbstractOperationsByName", 
                             container, operation.getName());
        
        if (!inheritedNonAbstract.isEmpty()) {
            return ValidationResult.fail(
                "AbstractOperationIsValid",
                "Operation: " + container.getName() + "." + operation.getName() + 
                " cannot be abstract, if it has non-abstract bases.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Zeta - Option B: Static Utility Class (Recommended for Complex Logic)**
```java
// In EsmValidationUtils.java
public class EsmValidationUtils {
    
    public static List<Operation> getInheritedNonAbstractOperationsByName(
            EntityType entityType, String operationName) {
        List<Operation> result = new ArrayList<>();
        EntityType current = entityType.getSuperType();
        
        while (current != null) {
            current.getOperations().stream()
                .filter(op -> op.getName().equals(operationName))
                .filter(op -> op.getOperationType() != OperationType.ABSTRACT)
                .forEach(result::add);
            current = current.getSuperType();
        }
        
        return result;
    }
}

// In OperationValidations.java
@Guard(method = "isAbstractOperation")
@Constraint(
    name = "AbstractOperationIsValid",
    message = "Operation cannot be abstract if it has non-abstract bases"
)
public ValidationRule abstractOperationIsValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType container = (EntityType) operation.eContainer();
        
        // Call static utility method directly
        List<Operation> inheritedNonAbstract = 
            EsmValidationUtils.getInheritedNonAbstractOperationsByName(
                container, operation.getName());
        
        if (!inheritedNonAbstract.isEmpty()) {
            return ValidationResult.fail(
                "AbstractOperationIsValid",
                "Operation: " + container.getName() + "." + operation.getName() + 
                " cannot be abstract, if it has non-abstract bases.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Step 6: Convert Critique

**EVL:**
```evl
critique AbstractOperationCannotHaveImplementation {
    guard: self.satisfies("AbstractOperationIsValid")
    check: self.body.isUndefined() or self.body.trim().length() == 0
    message: "Abstract operation: " + self.eContainer.name + "." + self.name + 
             " should not have implementation."
}
```

**Zeta:**
```java
@Guard(method = "isAbstractOperation")
@Satisfies(constraints = {"AbstractOperationIsValid"})
@Critique(
    name = "AbstractOperationCannotHaveImplementation",
    message = "Abstract operation should not have implementation"
)
public ValidationRule abstractOperationCannotHaveImplementation() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        String body = operation.getBody();
        
        // Check if body is defined and not empty
        if (body != null && !body.trim().isEmpty()) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.warn(
                "AbstractOperationCannotHaveImplementation",
                "Abstract operation: " + container.getName() + "." + 
                operation.getName() + " should not have implementation.",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Key Transformation:**
- `critique` → `@Critique` annotation
- `message` → `ValidationResult.warn()` instead of `fail()`
- Warnings don't prevent model from being valid

### Step 7: Complete Migrated Class

Here's the complete Java class with all rules migrated:

```java
package hu.blackbelt.judo.meta.esm.validation;

import hu.blackbelt.judo.meta.esm.runtime.Operation;
import hu.blackbelt.judo.meta.esm.runtime.OperationType;
import hu.blackbelt.judo.meta.esm.runtime.EntityType;
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation rules for ESM Operation elements (abstract operations).
 * Migrated from operation.evl - abstract operation context.
 * 
 * These rules validate:
 * - Abstract operations belong to entity types
 * - Abstract operations belong to abstract entity types
 * - Abstract operations don't override non-abstract operations
 * - Abstract operations should not have implementation (warning)
 */
@ValidationContext(Operation.class)
public class AbstractOperationValidations {
    
    /**
     * Guard method: Only validate abstract operations that have containers
     */
    public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
        Operation operation = (Operation) element;
        
        // Check container exists (replaces satisfies("NamedElementHasContainer"))
        if (operation.eContainer() == null) {
            return false;
        }
        
        // Check operation type is ABSTRACT
        return operation.getOperationType() == OperationType.ABSTRACT;
    }
    
    /**
     * Constraint: Abstract operation container must be an EntityType
     * 
     * EVL equivalent:
     * constraint AbstractOperationBelongsToEntityType {
     *     check: self.eContainer.isKindOf(ESM!EntityType)
     * }
     */
    @Guard(method = "isAbstractOperation")
    @Constraint(
        name = "AbstractOperationBelongsToEntityType",
        message = "Container of abstract operation must be an entity type"
    )
    public ValidationRule abstractOperationBelongsToEntityType() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            EObject container = operation.eContainer();
            
            if (!(container instanceof EntityType)) {
                return ValidationResult.fail(
                    "AbstractOperationBelongsToEntityType",
                    "Container of abstract operation: " + operation.getName() + 
                    " must be an entity type",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Constraint: Abstract operation container must be abstract
     * Depends on: AbstractOperationBelongsToEntityType
     * 
     * EVL equivalent:
     * constraint AbstractOperationBelongsToAbstractEntityType {
     *     guard: self.satisfies("AbstractOperationBelongsToEntityType")
     *     check: self.eContainer.`abstract`
     * }
     */
    @Guard(method = "isAbstractOperation")
    @Satisfies(constraints = {"AbstractOperationBelongsToEntityType"})
    @Constraint(
        name = "AbstractOperationBelongsToAbstractEntityType",
        message = "Container of abstract operation must be abstract"
    )
    public ValidationRule abstractOperationBelongsToAbstractEntityType() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            EntityType container = (EntityType) operation.eContainer();
            
            if (!container.isAbstract()) {
                return ValidationResult.fail(
                    "AbstractOperationBelongsToAbstractEntityType",
                    "Container of abstract operation: " + container.getName() + 
                    "." + operation.getName() + " must be an abstract entity type",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Constraint: Abstract operation cannot override non-abstract operations
     * 
     * EVL equivalent:
     * constraint AbstractOperationIsValid {
     *     check: esmUtils.getInheritedNonAbstractOperationsByName(
     *                self.eContainer, self.name).size() == 0
     * }
     */
    @Guard(method = "isAbstractOperation")
    @Constraint(
        name = "AbstractOperationIsValid",
        message = "Operation cannot be abstract if it has non-abstract bases"
    )
    public ValidationRule abstractOperationIsValid() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            EntityType container = (EntityType) operation.eContainer();
            
            List<Operation> inheritedNonAbstract = 
                getInheritedNonAbstractOperationsByName(container, operation.getName());
            
            if (!inheritedNonAbstract.isEmpty()) {
                return ValidationResult.fail(
                    "AbstractOperationIsValid",
                    "Operation: " + container.getName() + "." + operation.getName() + 
                    " cannot be abstract, if it has non-abstract bases.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Critique: Abstract operations should not have implementation
     * Depends on: AbstractOperationIsValid
     * 
     * EVL equivalent:
     * critique AbstractOperationCannotHaveImplementation {
     *     guard: self.satisfies("AbstractOperationIsValid")
     *     check: self.body.isUndefined() or self.body.trim().length() == 0
     * }
     */
    @Guard(method = "isAbstractOperation")
    @Satisfies(constraints = {"AbstractOperationIsValid"})
    @Critique(
        name = "AbstractOperationCannotHaveImplementation",
        message = "Abstract operation should not have implementation"
    )
    public ValidationRule abstractOperationCannotHaveImplementation() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            String body = operation.getBody();
            
            if (body != null && !body.trim().isEmpty()) {
                EntityType container = (EntityType) operation.eContainer();
                return ValidationResult.warn(
                    "AbstractOperationCannotHaveImplementation",
                    "Abstract operation: " + container.getName() + "." + 
                    operation.getName() + " should not have implementation.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Helper: Get inherited non-abstract operations with the given name
     * Migrated from esmUtils.getInheritedNonAbstractOperationsByName()
     */
    private List<Operation> getInheritedNonAbstractOperationsByName(
            EntityType entityType, String operationName) {
        List<Operation> result = new ArrayList<>();
        EntityType current = entityType.getSuperType();
        
        while (current != null) {
            current.getOperations().stream()
                .filter(op -> op.getName().equals(operationName))
                .filter(op -> op.getOperationType() != OperationType.ABSTRACT)
                .forEach(result::add);
            current = current.getSuperType();
        }
        
        return result;
    }
}
```

## Common Patterns and Their Equivalents

### Pattern 1: Simple Check

**EVL:**
```evl
constraint MustHaveName {
    check: self.name.isDefined() and self.name.length() > 0
    message: "Element must have a name"
}
```

**Zeta:**
```java
@Constraint(name = "MustHaveName", message = "Element must have a name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        
        if (obj.getName() == null || obj.getName().isEmpty()) {
            return ValidationResult.fail("Element must have a name");
        }
        
        return ValidationResult.pass();
    };
}
```

### Pattern 2: Collection Iteration

**EVL:**
```evl
constraint AllAttributesMustHaveType {
    check: self.attributes.forAll(a | a.type.isDefined())
    message: "All attributes must have a type"
}
```

**Zeta:**
```java
@Constraint(
    name = "AllAttributesMustHaveType",
    message = "All attributes must have a type"
)
public ValidationRule allAttributesMustHaveType() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        List<Attribute> attributesWithoutType = entity.getAttributes().stream()
            .filter(attr -> attr.getType() == null)
            .collect(Collectors.toList());
        
        if (!attributesWithoutType.isEmpty()) {
            String attrNames = attributesWithoutType.stream()
                .map(Attribute::getName)
                .collect(Collectors.joining(", "));
            
            return ValidationResult.fail(
                "AllAttributesMustHaveType",
                "Attributes without type: " + attrNames,
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Pattern 3: Cross-Reference Validation

**EVL:**
```evl
constraint ReferenceMustExist {
    check: MyType.allInstances().exists(t | t.name == self.referenceName)
    message: "Referenced type '" + self.referenceName + "' does not exist"
}
```

**Zeta:**
```java
@Constraint(
    name = "ReferenceMustExist",
    message = "Referenced type must exist"
)
public ValidationRule referenceMustExist() {
    return (element, ctx) -> {
        MyElement elem = (MyElement) element;
        String referenceName = elem.getReferenceName();
        
        if (referenceName == null) {
            return ValidationResult.pass();
        }
        
        boolean exists = ctx.getAllInstances(MyType.class).stream()
            .anyMatch(t -> referenceName.equals(t.getName()));
        
        if (!exists) {
            return ValidationResult.fail(
                "ReferenceMustExist",
                "Referenced type '" + referenceName + "' does not exist",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Pattern 4: Negation and Logical Operators

**EVL:**
```evl
constraint ValidState {
    check: not self.isAbstract or self.operations.isEmpty()
    message: "Abstract types should not have operations"
}
```

**Zeta:**
```java
@Constraint(name = "ValidState", message = "Abstract types should not have operations")
public ValidationRule validState() {
    return (element, ctx) -> {
        MyType type = (MyType) element;
        
        // not self.isAbstract or self.operations.isEmpty()
        // Equivalent to: !isAbstract || operations.isEmpty()
        // Or: isAbstract implies operations.isEmpty()
        
        if (type.isAbstract() && !type.getOperations().isEmpty()) {
            return ValidationResult.fail(
                "ValidState",
                "Abstract type '" + type.getName() + "' should not have operations",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

### Pattern 5: String Operations

**EVL:**
```evl
constraint ValidIdentifier {
    check: self.name.matches('[A-Za-z][A-Za-z0-9_]*')
    message: "Name must be a valid identifier"
}
```

**Zeta:**
```java
@Constraint(name = "ValidIdentifier", message = "Name must be a valid identifier")
public ValidationRule validIdentifier() {
    return (element, ctx) -> {
        MyType type = (MyType) element;
        String name = type.getName();
        
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            return ValidationResult.fail(
                "ValidIdentifier",
                "Name '" + name + "' must be a valid identifier " +
                "(start with letter, contain only letters, numbers, underscores)",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Migration Checklist

Use this checklist for each EVL file you migrate:

### Analysis Phase
- [ ] Read and understand the EVL file completely
- [ ] Identify all contexts (element types)
- [ ] List all constraints and critiques
- [ ] Map all guard conditions
- [ ] Identify all satisfies dependencies
- [ ] List all helper operations used
- [ ] Document expected validation behavior

### Setup Phase
- [ ] Create Java validation class for each context
- [ ] Add `@ValidationContext` annotation with correct element type
- [ ] Import necessary EMF model classes
- [ ] Import Zeta framework annotations and classes

### Conversion Phase
- [ ] Convert context-level guards to guard methods
- [ ] Convert each constraint to `@Constraint` method
- [ ] Convert each critique to `@Critique` method
- [ ] Add `@Guard` annotations where needed
- [ ] Add `@Satisfies` dependencies
- [ ] Migrate helper operations to extension methods or utilities

### Testing Phase
- [ ] Register validation class with ValidationRegistry
- [ ] Run validation on sample models
- [ ] Compare results with original EVL validation
- [ ] Test guard conditions work correctly
- [ ] Test dependency ordering is correct
- [ ] Verify error messages are clear and helpful

### Documentation Phase
- [ ] Add Javadoc comments to validation class
- [ ] Document each validation rule
- [ ] Note EVL equivalents in comments
- [ ] Update validation documentation

## Testing Migrated Validations

### Test Setup

Create a test class to verify your migrated validations:

```java
package hu.blackbelt.judo.meta.esm.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import hu.blackbelt.judo.zeta.validation.core.*;
import hu.blackbelt.judo.meta.esm.runtime.*;

public class AbstractOperationValidationsTest {
    
    private ValidationRegistry registry;
    private ValidationExecutor executor;
    
    @BeforeEach
    public void setUp() {
        registry = new ValidationRegistry();
        registry.register(AbstractOperationValidations.class);
        
        executor = ValidationExecutor.builder()
            .registry(registry)
            .build();
    }
    
    @Test
    public void testAbstractOperationMustBelongToEntityType() {
        // Create test model
        Operation operation = createAbstractOperation("testOp");
        NonEntityType nonEntity = createNonEntityType();
        nonEntity.getOperations().add(operation);
        
        // Validate
        List<ValidationResult> results = executor.validate(List.of(operation));
        
        // Assert
        ValidationResult result = findResult(results, "AbstractOperationBelongsToEntityType");
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("must be an entity type"));
    }
    
    @Test
    public void testAbstractOperationMustBelongToAbstractEntity() {
        // Create test model
        EntityType concreteEntity = createEntityType("Customer", false);
        Operation operation = createAbstractOperation("testOp");
        concreteEntity.getOperations().add(operation);
        
        // Validate
        List<ValidationResult> results = executor.validate(List.of(operation));
        
        // Assert
        ValidationResult result = findResult(results, "AbstractOperationBelongsToAbstractEntityType");
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("must be an abstract entity type"));
    }
    
    @Test
    public void testAbstractOperationCannotHaveImplementation() {
        // Create test model
        EntityType abstractEntity = createEntityType("BaseEntity", true);
        Operation operation = createAbstractOperation("testOp");
        operation.setBody("return 42;"); // Implementation - should trigger warning
        abstractEntity.getOperations().add(operation);
        
        // Validate
        List<ValidationResult> results = executor.validate(List.of(operation));
        
        // Assert
        ValidationResult result = findResult(results, "AbstractOperationCannotHaveImplementation");
        assertNotNull(result);
        assertEquals(Severity.WARNING, result.getSeverity()); // Critique = warning
        assertTrue(result.getMessage().contains("should not have implementation"));
    }
    
    @Test
    public void testValidAbstractOperation() {
        // Create test model
        EntityType abstractEntity = createEntityType("BaseEntity", true);
        Operation operation = createAbstractOperation("testOp");
        abstractEntity.getOperations().add(operation);
        
        // Validate
        List<ValidationResult> results = executor.validate(List.of(operation));
        
        // Assert - all rules should pass
        assertTrue(results.stream().allMatch(ValidationResult::isValid));
    }
    
    // Helper methods
    private Operation createAbstractOperation(String name) {
        Operation op = RuntimeFactory.eINSTANCE.createOperation();
        op.setName(name);
        op.setOperationType(OperationType.ABSTRACT);
        return op;
    }
    
    private EntityType createEntityType(String name, boolean isAbstract) {
        EntityType entity = RuntimeFactory.eINSTANCE.createEntityType();
        entity.setName(name);
        entity.setAbstract(isAbstract);
        return entity;
    }
    
    private ValidationResult findResult(List<ValidationResult> results, String constraintName) {
        return results.stream()
            .filter(r -> constraintName.equals(r.getConstraintName()))
            .findFirst()
            .orElse(null);
    }
}
```

### Comparison Testing

Compare EVL and Zeta results on the same model:

```java
@Test
public void testMigrationEquivalence() {
    // Load test model
    Resource resource = loadTestModel("testmodel.xmi");
    List<EObject> elements = getAllElements(resource);
    
    // Run original EVL validation (if still available)
    // List<ValidationIssue> evlResults = runEvlValidation(elements);
    
    // Run Zeta validation
    List<ValidationResult> zetaResults = executor.validate(elements);
    
    // Compare results
    // assertEquals(evlResults.size(), zetaResults.size());
    // ... compare individual results
}
```

## Common Pitfalls and Solutions

### Pitfall 1: Null Container in Guard Methods

**Problem:**
```java
public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    // NPE if eContainer() returns null!
    return operation.eContainer() instanceof EntityType;
}
```

**Solution:**
```java
public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    
    // Always check for null first
    if (operation.eContainer() == null) {
        return false;
    }
    
    return operation.eContainer() instanceof EntityType
        && operation.getOperationType() == OperationType.ABSTRACT;
}
```

### Pitfall 2: Forgetting to Handle Null Values

**Problem:**
```java
// EVL: self.name.length() > 0
// This NPEs if name is null!
if (entity.getName().length() > 0) { ... }
```

**Solution:**
```java
// Always check null first
if (entity.getName() != null && entity.getName().length() > 0) {
    return ValidationResult.pass();
}
```

### Pitfall 3: Incorrect Satisfies Mapping

**Problem:**
```evl
// EVL uses satisfies in guard
constraint RuleB {
    guard: self.satisfies("RuleA")
    check: ...
}
```

```java
// Wrong: using @Guard instead of @Satisfies
@Guard(method = "satisfiesRuleA")
@Constraint(name = "RuleB", ...)
public ValidationRule ruleB() { ... }
```

**Solution:**
```java
// Correct: use @Satisfies for dependency
@Satisfies(constraints = {"RuleA"})
@Constraint(name = "RuleB", ...)
public ValidationRule ruleB() { ... }
```

### Pitfall 4: Not Inverting Logic Correctly

**Problem:**
```evl
// EVL: check passes if condition is true
constraint MustBeAbstract {
    check: self.isAbstract
    message: "Must be abstract"
}
```

```java
// Wrong: returns fail when true!
return entity.isAbstract()
    ? ValidationResult.fail("Must be abstract")
    : ValidationResult.pass();
```

**Solution:**
```java
// Correct: fail when condition is false
if (!entity.isAbstract()) {
    return ValidationResult.fail("Must be abstract");
}
return ValidationResult.pass();
```

### Pitfall 5: Missing Guard on Dependent Rules

**Problem:**
```evl
context ESM!Operation {
    guard: self.operationType == ESM!OperationType#ABSTRACT
    
    constraint Rule1 { ... }
    constraint Rule2 { ... }
}
```

```java
// Wrong: only Rule1 has the guard!
@Guard(method = "isAbstract")
@Constraint(name = "Rule1", ...)
public ValidationRule rule1() { ... }

@Constraint(name = "Rule2", ...)  // Missing guard!
public ValidationRule rule2() { ... }
```

**Solution:**
```java
// Correct: both rules need the guard
@Guard(method = "isAbstract")
@Constraint(name = "Rule1", ...)
public ValidationRule rule1() { ... }

@Guard(method = "isAbstract")  // Guard added
@Constraint(name = "Rule2", ...)
public ValidationRule rule2() { ... }
```

### Pitfall 6: Inefficient getAllInstances() Usage

**Problem:**
```java
// Calling getAllInstances() in every validation
@Constraint(name = "NameMustBeUnique", ...)
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        // Called for EVERY element - very inefficient!
        List<EntityType> all = ctx.getAllInstances(EntityType.class);
        // ...
    };
}
```

**Solution:**
```java
// Use caching for expensive queries
@Constraint(name = "NameMustBeUnique", ...)
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Build and cache name index once
        CacheKey cacheKey = CacheKey.of("entity-name-index");
        Map<String, List<EntityType>> nameIndex = 
            (Map<String, List<EntityType>>) ctx.getCached(cacheKey);
        
        if (nameIndex == null) {
            nameIndex = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(EntityType::getName));
            ctx.putCached(cacheKey, nameIndex);
        }
        
        // Use cached index
        List<EntityType> duplicates = nameIndex.get(entity.getName());
        if (duplicates != null && duplicates.size() > 1) {
            return ValidationResult.fail("Name must be unique");
        }
        
        return ValidationResult.pass();
    };
}
```

### Pitfall 7: Wrong Severity Level

**Problem:**
```java
// Using @Constraint for a warning
@Constraint(name = "ShouldHaveDescription", ...)  // Wrong!
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        // This should be a warning, not an error
    };
}
```

**Solution:**
```java
// Use @Critique for warnings/recommendations
@Critique(name = "ShouldHaveDescription", ...)  // Correct
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        if (obj.getDescription() == null) {
            return ValidationResult.warn("Should have description");
        }
        return ValidationResult.pass();
    };
}
```

## Next Steps

After completing your migration:

1. **Review** the migrated code with your team
2. **Test** thoroughly with real models
3. **Document** any deviations from original EVL behavior
4. **Optimize** using caching and parallel execution features
5. **Monitor** validation performance in production

## Related Documentation

- [EVL Syntax Mapping](syntax-mapping.md) - Detailed EVL to Zeta syntax reference
- [Feature Parity](feature-parity.md) - What's supported and what's not
- [Validation Rules Guide](../user-guide/validation-rules.md) - Best practices
- [Extension Methods](../user-guide/extension-methods.md) - Migrating helper operations
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Advanced control flow

---

**Previous**: [Syntax Mapping](syntax-mapping.md) | **Next**: [Feature Parity](feature-parity.md)
