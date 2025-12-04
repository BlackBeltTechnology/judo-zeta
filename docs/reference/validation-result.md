# ValidationResult API

**Navigation**: [Documentation Hub](../index.md) > [Reference](validation-result.md) > ValidationResult API

This reference guide documents the `ValidationResult` API, the immutable value object that represents the outcome of a validation rule.

## Overview

`ValidationResult` is a thread-safe, immutable class that encapsulates:
- Whether validation passed or failed
- Severity level (ERROR or WARNING)
- Error/warning message
- Constraint/critique name
- The EMF element that was validated

All validation rules must return a `ValidationResult` instance.

## Package

```java
package hu.blackbelt.judo.zeta.validation.core;
```

## Factory Methods

### Simple Factory Methods

These factory methods provide the most common ways to create validation results.

#### ValidationResult.pass()

Creates a passing validation result.

```java
public static ValidationResult pass()
```

**Returns**: A validation result indicating successful validation

**Example**:
```java
@Constraint(name = "MustHaveName", message = "Entity must have a name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() != null && !entity.getName().isEmpty()) {
            return ValidationResult.pass();
        }
        
        return ValidationResult.fail("Entity must have a name");
    };
}
```

**Properties**:
- `isPassed()` returns `true`
- `isFailed()` returns `false`
- All other properties (`message`, `severity`, `constraintName`, `context`) are `null`

---

#### ValidationResult.fail(String message)

Creates a failing validation result with an ERROR severity.

```java
public static ValidationResult fail(String message)
```

**Parameters**:
- `message` - The error message describing why validation failed

**Returns**: A failing result with ERROR severity

**Example**:
```java
@Constraint(name = "NameRequired", message = "Name is required")
public ValidationRule nameRequired() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        return entity.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have a name");
    };
}
```

**Properties**:
- `isPassed()` returns `false`
- `isFailed()` returns `true`
- `getSeverity()` returns `Severity.ERROR`
- `getMessage()` returns the provided message
- `getConstraintName()` and `getContext()` are `null`

---

#### ValidationResult.warn(String message)

Creates a warning validation result with a WARNING severity.

```java
public static ValidationResult warn(String message)
```

**Parameters**:
- `message` - The warning message

**Returns**: A failing result with WARNING severity

**Example**:
```java
@Critique(name = "ShouldHaveDescription", message = "Description recommended")
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        return entity.getDescription() != null
            ? ValidationResult.pass()
            : ValidationResult.warn("Consider adding a description to improve documentation");
    };
}
```

**Properties**:
- `isPassed()` returns `false`
- `isFailed()` returns `true`
- `getSeverity()` returns `Severity.WARNING`
- `getMessage()` returns the provided message
- `getConstraintName()` and `getContext()` are `null`

---

### Full Factory Methods

These factory methods allow you to specify all metadata fields.

#### ValidationResult.fail(String constraintName, String message, Severity severity, EObject context)

Creates a failing validation result with complete metadata.

```java
public static ValidationResult fail(
    String constraintName,
    String message,
    Severity severity,
    EObject context
)
```

**Parameters**:
- `constraintName` - Name of the constraint that failed
- `message` - Error message
- `severity` - Severity level (`Severity.ERROR` or `Severity.WARNING`)
- `context` - The EMF element that failed validation

**Returns**: A failing result with full metadata

**Example**:
```java
@Constraint(name = "UniqueName", message = "Name must be unique")
public ValidationRule uniqueName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        List<EntityType> duplicates = ctx.getAll(EntityType.class).stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .filter(e -> e != entity)
            .collect(Collectors.toList());
        
        if (!duplicates.isEmpty()) {
            return ValidationResult.fail(
                "UniqueName",
                "Entity name '" + entity.getName() + "' is already used",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

#### ValidationResult.warn(String constraintName, String message, EObject context)

Creates a warning validation result with metadata.

```java
public static ValidationResult warn(
    String constraintName,
    String message,
    EObject context
)
```

**Parameters**:
- `constraintName` - Name of the critique
- `message` - Warning message
- `context` - The EMF element that triggered the warning

**Returns**: A warning result with metadata (severity is automatically set to `Severity.WARNING`)

**Example**:
```java
@Critique(name = "NamingConvention", message = "Follow naming conventions")
public ValidationRule namingConvention() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (!Character.isUpperCase(entity.getName().charAt(0))) {
            return ValidationResult.warn(
                "NamingConvention",
                "Entity name '" + entity.getName() + "' should start with uppercase letter",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

## Properties

### isPassed()

Checks if validation passed.

```java
public boolean isPassed()
```

**Returns**: `true` if validation passed, `false` otherwise

**Example**:
```java
ValidationResult result = ValidationResult.pass();
if (result.isPassed()) {
    System.out.println("Validation successful");
}
```

---

### isFailed()

Checks if validation failed (inverse of `isPassed()`).

```java
public boolean isFailed()
```

**Returns**: `true` if validation failed, `false` otherwise

**Example**:
```java
List<ValidationResult> results = executor.validate(elements);

List<ValidationResult> failures = results.stream()
    .filter(ValidationResult::isFailed)
    .collect(Collectors.toList());

System.out.println("Found " + failures.size() + " validation failures");
```

---

### getSeverity()

Gets the severity level of the validation result.

```java
public Severity getSeverity()
```

**Returns**: 
- `Severity.ERROR` for error-level failures
- `Severity.WARNING` for warning-level failures
- `null` for passing results

**Example**:
```java
List<ValidationResult> results = executor.validate(elements);

long errorCount = results.stream()
    .filter(r -> r.getSeverity() == Severity.ERROR)
    .count();

long warningCount = results.stream()
    .filter(r -> r.getSeverity() == Severity.WARNING)
    .count();

System.out.println("Errors: " + errorCount + ", Warnings: " + warningCount);
```

---

### getMessage()

Gets the validation message.

```java
public String getMessage()
```

**Returns**: 
- Error or warning message for failed validations
- `null` for passing validations

**Example**:
```java
results.stream()
    .filter(ValidationResult::isFailed)
    .forEach(result -> {
        System.err.println("[" + result.getSeverity() + "] " + result.getMessage());
    });
```

---

### getConstraintName()

Gets the name of the constraint or critique.

```java
public String getConstraintName()
```

**Returns**: 
- Constraint/critique name if provided
- `null` if not provided (e.g., simple factory methods)

**Example**:
```java
Map<String, List<ValidationResult>> failuresByConstraint = results.stream()
    .filter(ValidationResult::isFailed)
    .filter(r -> r.getConstraintName() != null)
    .collect(Collectors.groupingBy(ValidationResult::getConstraintName));

failuresByConstraint.forEach((name, failures) -> {
    System.out.println(name + ": " + failures.size() + " failures");
});
```

---

### getContext()

Gets the EMF element that was validated.

```java
public EObject getContext()
```

**Returns**: 
- The EMF element that failed validation
- `null` if not provided

**Example**:
```java
results.stream()
    .filter(ValidationResult::isFailed)
    .filter(r -> r.getContext() != null)
    .forEach(result -> {
        EObject element = result.getContext();
        System.err.println("Failed element: " + element.eClass().getName());
    });
```

## Usage Patterns

### Simple Pass/Fail Pattern

The most common pattern for straightforward validations.

```java
@Constraint(name = "NotNull", message = "Value cannot be null")
public ValidationRule notNull() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        
        return obj.getValue() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Value is required");
    };
}
```

**When to use**:
- Simple boolean conditions
- Single validation check
- No need for detailed metadata

---

### Early Return Pattern

For multiple validation checks, return early on first failure.

```java
@Constraint(name = "ValidConfiguration", message = "Configuration is invalid")
public ValidationRule validConfiguration() {
    return (element, ctx) -> {
        Configuration config = (Configuration) element;
        
        // Check 1: Name required
        if (config.getName() == null) {
            return ValidationResult.fail("Configuration name is required");
        }
        
        // Check 2: Name length
        if (config.getName().length() < 3) {
            return ValidationResult.fail("Configuration name must be at least 3 characters");
        }
        
        // Check 3: Type required
        if (config.getType() == null) {
            return ValidationResult.fail("Configuration type is required");
        }
        
        // All checks passed
        return ValidationResult.pass();
    };
}
```

**When to use**:
- Multiple sequential checks
- Each check is independent
- Want to report first failure only

---

### Conditional Result Pattern

Choose result based on complex conditions.

```java
@Constraint(name = "ValidState", message = "Invalid state transition")
public ValidationRule validState() {
    return (element, ctx) -> {
        StateMachine sm = (StateMachine) element;
        
        boolean hasInitialState = sm.getStates().stream()
            .anyMatch(State::isInitial);
        
        boolean hasFinalState = sm.getStates().stream()
            .anyMatch(State::isFinal);
        
        if (!hasInitialState && !hasFinalState) {
            return ValidationResult.fail("State machine must have initial and final states");
        } else if (!hasInitialState) {
            return ValidationResult.fail("State machine must have an initial state");
        } else if (!hasFinalState) {
            return ValidationResult.warn("State machine should have a final state");
        }
        
        return ValidationResult.pass();
    };
}
```

**When to use**:
- Complex branching logic
- Different messages for different conditions
- Mix of errors and warnings

---

### Collecting Multiple Results

For validations that need to report all issues (not just the first).

```java
@Constraint(name = "AllAttributesValid", message = "Some attributes are invalid")
public ValidationRule allAttributesValid() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        List<String> errors = new ArrayList<>();
        
        for (Attribute attr : entity.getAttributes()) {
            if (attr.getName() == null) {
                errors.add("Attribute at index " + entity.getAttributes().indexOf(attr) + " has no name");
            }
            if (attr.getType() == null) {
                errors.add("Attribute '" + attr.getName() + "' has no type");
            }
        }
        
        if (!errors.isEmpty()) {
            return ValidationResult.fail(
                "AllAttributesValid",
                "Found " + errors.size() + " attribute errors:\n" + String.join("\n", errors),
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**When to use**:
- Need comprehensive error report
- Multiple sub-validations
- Want to fix all issues at once

**Note**: This pattern validates child elements. For truly independent validations, create separate rules instead.

---

### Result Processing Pattern

Common patterns for handling validation results after execution.

```java
// Count failures by severity
List<ValidationResult> results = executor.validate(elements);

long errors = results.stream()
    .filter(r -> r.getSeverity() == Severity.ERROR)
    .count();

long warnings = results.stream()
    .filter(r -> r.getSeverity() == Severity.WARNING)
    .count();

// Group by constraint
Map<String, List<ValidationResult>> byConstraint = results.stream()
    .filter(ValidationResult::isFailed)
    .filter(r -> r.getConstraintName() != null)
    .collect(Collectors.groupingBy(ValidationResult::getConstraintName));

// Print formatted report
results.stream()
    .filter(ValidationResult::isFailed)
    .forEach(result -> {
        String severity = result.getSeverity() == Severity.ERROR ? "ERROR" : "WARN";
        String constraint = result.getConstraintName() != null 
            ? "[" + result.getConstraintName() + "] " 
            : "";
        System.err.println(severity + " " + constraint + result.getMessage());
    });

// Fail build on errors
boolean hasErrors = results.stream()
    .anyMatch(r -> r.getSeverity() == Severity.ERROR);

if (hasErrors) {
    throw new BuildException("Validation failed with errors");
}
```

## Immutability and Thread Safety

`ValidationResult` is **completely immutable** and **thread-safe**.

### Immutability Guarantees

1. All fields are `private final`
2. No setter methods
3. Constructor is private
4. Only factory methods can create instances
5. All properties are either primitives, enums, or immutable types

```java
// This is safe - result cannot be modified
ValidationResult result = ValidationResult.fail("Error");
// No methods to change result properties
```

### Thread Safety

Because `ValidationResult` is immutable, it can be safely:
- Shared across threads
- Returned from parallel streams
- Stored in concurrent collections
- Cached without synchronization

```java
// Safe to use in parallel validation
List<ValidationResult> results = elements.parallelStream()
    .map(element -> validateElement(element))
    .collect(Collectors.toList());

// Safe to cache
private final Map<CacheKey, ValidationResult> resultCache = new ConcurrentHashMap<>();
```

### Equality and Hashing

`ValidationResult` properly implements `equals()` and `hashCode()` based on all fields:

```java
ValidationResult r1 = ValidationResult.fail("Error");
ValidationResult r2 = ValidationResult.fail("Error");

// true - same content
System.out.println(r1.equals(r2));

// Safe to use in sets and maps
Set<ValidationResult> uniqueResults = new HashSet<>(results);
```

## Common Pitfalls

### Pitfall 1: Returning null

**Wrong**:
```java
@Constraint(name = "Test", message = "...")
public ValidationRule test() {
    return (element, ctx) -> {
        if (someCondition) {
            return ValidationResult.pass();
        }
        // Implicitly returns null!
    };
}
```

**Correct**:
```java
@Constraint(name = "Test", message = "...")
public ValidationRule test() {
    return (element, ctx) -> {
        if (someCondition) {
            return ValidationResult.pass();
        }
        return ValidationResult.fail("Condition not met");
    };
}
```

**Why**: Every code path must return a `ValidationResult`. Null results will cause runtime errors.

---

### Pitfall 2: Ignoring Severity

**Wrong**:
```java
// Processing all failures the same way
results.stream()
    .filter(ValidationResult::isFailed)
    .forEach(r -> {
        throw new RuntimeException(r.getMessage()); // Throws on warnings too!
    });
```

**Correct**:
```java
// Handle errors and warnings differently
long errorCount = results.stream()
    .filter(r -> r.getSeverity() == Severity.ERROR)
    .count();

if (errorCount > 0) {
    throw new RuntimeException("Validation failed with " + errorCount + " errors");
}

// Just log warnings
results.stream()
    .filter(r -> r.getSeverity() == Severity.WARNING)
    .forEach(r -> log.warn(r.getMessage()));
```

**Why**: Warnings indicate code quality issues, not build-breaking errors.

---

### Pitfall 3: Mutating Element in Validation

**Wrong**:
```java
@Constraint(name = "FixName", message = "...")
public ValidationRule fixName() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        
        if (obj.getName() == null) {
            obj.setName("Default"); // DON'T MODIFY!
            return ValidationResult.pass();
        }
        
        return ValidationResult.pass();
    };
}
```

**Correct**:
```java
@Constraint(name = "MustHaveName", message = "...")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        
        return obj.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Name is required - please set a name");
    };
}
```

**Why**: Validation rules should **never modify** the model. They only report issues.

---

### Pitfall 4: Creating Result Outside Lambda

**Wrong**:
```java
@Constraint(name = "Test", message = "...")
public ValidationRule test() {
    ValidationResult result = ValidationResult.pass(); // Created once!
    
    return (element, ctx) -> {
        return result; // Same instance reused
    };
}
```

**Correct**:
```java
@Constraint(name = "Test", message = "...")
public ValidationRule test() {
    return (element, ctx) -> {
        // Create fresh result for each validation
        return someCondition(element)
            ? ValidationResult.pass()
            : ValidationResult.fail("Failed");
    };
}
```

**Why**: While results are immutable, you need a fresh result for each element to capture element-specific context and messages.

---

### Pitfall 5: Using Wrong Factory Method

**Wrong**:
```java
@Critique(name = "ShouldHaveDoc", message = "...")
public ValidationRule shouldHaveDoc() {
    return (element, ctx) -> {
        return hasDocumentation(element)
            ? ValidationResult.pass()
            : ValidationResult.fail("Missing documentation"); // ERROR severity!
    };
}
```

**Correct**:
```java
@Critique(name = "ShouldHaveDoc", message = "...")
public ValidationRule shouldHaveDoc() {
    return (element, ctx) -> {
        return hasDocumentation(element)
            ? ValidationResult.pass()
            : ValidationResult.warn("Missing documentation"); // WARNING severity
    };
}
```

**Why**: Use `fail()` for constraints (errors) and `warn()` for critiques (warnings).

## Related Documentation

- **[Core Concepts](../user-guide/core-concepts.md)** - Understanding validation rules
- **[Writing Validation Rules](../user-guide/validation-rules.md)** - Creating constraints and critiques
- **[ValidationContext API](validation-context.md)** - Context methods and utilities
- **[Error Messages](../best-practices/error-messages.md)** - Best practices for messages
- **[Examples](../examples/simple-validations.md)** - Real-world validation examples

---

**Next Steps**: Learn about the [ValidationContext API](validation-context.md) for accessing model elements and extension methods.
