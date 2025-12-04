# EVL to Zeta Syntax Mapping

**Navigation**: [Documentation Hub](../index.md) > [EVL Comparison](index.md) > Syntax Mapping

This document provides side-by-side comparisons of Epsilon Validation Language (EVL) syntax and its Zeta Validation Framework equivalents. Use this as a reference when migrating validation rules or learning Zeta if you're familiar with EVL.

---

## Table of Contents

1. [Context Declaration](#context-declaration)
2. [Constraint Definition](#constraint-definition)
3. [Critique Definition](#critique-definition)
4. [Guard Conditions (Context-Level)](#guard-conditions-context-level)
5. [Guard Conditions (Constraint-Level)](#guard-conditions-constraint-level)
6. [Message Blocks](#message-blocks)
7. [Pre/Post Blocks](#prepost-blocks)
8. [Constraint Dependencies (satisfies)](#constraint-dependencies-satisfies)
9. [EOL to Java Operations](#eol-to-java-operations)
10. [Complete Examples](#complete-examples)

---

## Context Declaration

### EVL
```evl
context EntityType {
    constraint MustHaveName { ... }
    constraint NameMustBeUnique { ... }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "...")
    public ValidationRule mustHaveName() { ... }
    
    @Constraint(name = "NameMustBeUnique", message = "...")
    public ValidationRule nameMustBeUnique() { ... }
}
```

**Key Differences**:
- EVL uses `context` keyword; Zeta uses `@ValidationContext` annotation
- EVL is declarative; Zeta is Java class-based
- EVL groups constraints in one block; Zeta uses separate methods per rule
- Zeta allows multiple validation classes per element type

---

## Constraint Definition

### EVL
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: "Entity must have a name"
    }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a name");
        };
    }
}
```

**Key Differences**:
- EVL uses `constraint` keyword; Zeta uses `@Constraint` annotation
- EVL `check:` becomes Java lambda expression in Zeta
- EVL `self` becomes explicit cast to element type in Zeta
- EVL returns boolean; Zeta returns `ValidationResult`
- Zeta requires explicit `pass()` and `fail()` methods

---

## Critique Definition

### EVL
```evl
context EntityType {
    critique ShouldHaveDescription {
        check: self.description.isDefined()
        message: "Entity should have a description for documentation"
    }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Critique(
        name = "ShouldHaveDescription",
        message = "Entity should have a description for documentation"
    )
    public ValidationRule shouldHaveDescription() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getDescription() != null
                ? ValidationResult.pass()
                : ValidationResult.warn("Entity should have a description");
        };
    }
}
```

**Key Differences**:
- EVL uses `critique` keyword; Zeta uses `@Critique` annotation
- EVL treats critiques as warnings automatically; Zeta uses `ValidationResult.warn()`
- Same severity semantics (non-blocking warnings)

---

## Guard Conditions (Context-Level)

### EVL
```evl
context EntityType {
    guard: not self.isAbstract
    
    constraint ConcreteEntityMustHaveTable {
        check: self.table.isDefined()
        message: "Concrete entity must have table mapping"
    }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Guard(method = "isNotAbstract")
    @Constraint(
        name = "ConcreteEntityMustHaveTable",
        message = "Concrete entity must have table mapping"
    )
    public ValidationRule concreteEntityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTable() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity must have table mapping");
        };
    }
    
    private boolean isNotAbstract(EObject element) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Key Differences**:
- EVL context-level guards apply to all constraints; Zeta guards are per-rule
- EVL uses inline guard expression; Zeta uses separate guard method
- Zeta guards are reusable across multiple validation rules
- EVL guard syntax `guard:`; Zeta uses `@Guard(method = "methodName")`

---

## Guard Conditions (Constraint-Level)

### EVL
```evl
context EntityType {
    constraint NameMustBeUnique {
        guard: self.name.isDefined()
        check: EntityType.allInstances()
                  .excluding(self)
                  .forAll(e | e.name <> self.name)
        message: "Entity name must be unique"
    }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Guard(method = "hasName")
    @Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Collection<EntityType> allInstances = ctx.getAllInstances(EntityType.class);
            
            boolean hasDuplicate = allInstances.stream()
                .filter(e -> e != entity)
                .anyMatch(e -> entity.getName().equals(e.getName()));
            
            return hasDuplicate
                ? ValidationResult.fail("Duplicate entity name: " + entity.getName())
                : ValidationResult.pass();
        };
    }
    
    private boolean hasName(EObject element) {
        return ((EntityType) element).getName() != null;
    }
}
```

**Key Differences**:
- EVL constraint-level guards are inline; Zeta uses separate method reference
- EVL guard prevents check execution; Zeta guard prevents entire rule execution
- Zeta guards can be reused across constraints

---

## Message Blocks

### EVL (Simple Message)
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: "Entity must have a name"
    }
}
```

### EVL (Dynamic Message)
```evl
context EntityType {
    constraint NameTooShort {
        check: self.name.length() >= 3
        message: "Entity name '" + self.name + "' is too short (minimum 3 characters)"
    }
}
```

### Zeta Validation (Simple)
```java
@Constraint(name = "MustHaveName", message = "Entity must have a name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have a name");
    };
}
```

### Zeta Validation (Dynamic)
```java
@Constraint(name = "NameTooShort", message = "Entity name is too short")
public ValidationRule nameTooShort() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        if (entity.getName() == null || entity.getName().length() >= 3) {
            return ValidationResult.pass();
        }
        return ValidationResult.fail(
            "Entity name '" + entity.getName() + "' is too short (minimum 3 characters)"
        );
    };
}
```

**Key Differences**:
- EVL has separate `message:` block; Zeta passes message to `fail()` or `warn()`
- EVL message in annotation is template; Zeta message in annotation is fallback
- Both support dynamic string concatenation
- Zeta allows full Java string formatting (String.format, MessageFormat, etc.)

---

## Pre/Post Blocks

### EVL
```evl
pre Setup {
    var entityCount = EntityType.allInstances().size();
    ("Validating " + entityCount + " entity types").println();
}

context EntityType {
    constraint MustHaveName { ... }
}

post Teardown {
    "Validation complete".println();
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @PreValidation
    public void setup(ValidationContext ctx) {
        Collection<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        System.out.println("Validating " + allEntities.size() + " entity types");
    }
    
    @Constraint(name = "MustHaveName", message = "...")
    public ValidationRule mustHaveName() { ... }
    
    @PostValidation
    public void teardown(ValidationContext ctx) {
        System.out.println("Validation complete");
    }
}
```

**Key Differences**:
- EVL uses `pre`/`post` keywords; Zeta uses `@PreValidation`/`@PostValidation` annotations
- EVL pre/post blocks are global; Zeta hooks are per validation class
- EVL variables in pre block not accessible in constraints; Zeta can use instance fields
- Zeta provides access to `ValidationContext` in lifecycle methods

---

## Constraint Dependencies (satisfies)

### EVL
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: "Entity must have a name"
    }
    
    constraint NameMustBeUnique {
        guard: self.satisfies("MustHaveName")
        check: EntityType.allInstances()
                  .excluding(self)
                  .forAll(e | e.name <> self.name)
        message: "Entity name must be unique"
    }
}
```

### Zeta Validation
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a name");
        };
    }
    
    @Satisfies(constraints = {"MustHaveName"})
    @Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Collection<EntityType> allInstances = ctx.getAllInstances(EntityType.class);
            
            boolean hasDuplicate = allInstances.stream()
                .filter(e -> e != entity)
                .anyMatch(e -> entity.getName().equals(e.getName()));
            
            return hasDuplicate
                ? ValidationResult.fail("Duplicate entity name: " + entity.getName())
                : ValidationResult.pass();
        };
    }
}
```

**Key Differences**:
- EVL uses `self.satisfies("name")` in guard; Zeta uses `@Satisfies` annotation
- EVL checks satisfaction at runtime; Zeta resolves dependencies at registration time
- Zeta builds dependency graph and topologically sorts rules
- Zeta can declare multiple dependencies: `@Satisfies(constraints = {"Rule1", "Rule2"})`
- EVL has `satisfies()`, `satisfiesAll()`, `satisfiesOne()`; Zeta only needs annotation

---

## EOL to Java Operations

### 1. isDefined() Check

**EVL**:
```evl
check: self.name.isDefined()
```

**Zeta**:
```java
entity.getName() != null
```

---

### 2. String Length Check

**EVL**:
```evl
check: self.name.length() > 0
```

**Zeta**:
```java
entity.getName() != null && !entity.getName().isEmpty()
```

Or more concise:
```java
entity.getName() != null && entity.getName().length() > 0
```

---

### 3. Collection isEmpty()

**EVL**:
```evl
check: self.attributes.isEmpty()
```

**Zeta**:
```java
entity.getAttributes().isEmpty()
```

---

### 4. Container Navigation (eContainer)

**EVL**:
```evl
check: self.eContainer().isDefined()
```

**Zeta**:
```java
entity.eContainer() != null
```

---

### 5. All Instances

**EVL**:
```evl
EntityType.allInstances()
```

**Zeta**:
```java
ctx.getAllInstances(EntityType.class)
```

---

### 6. Collection forAll()

**EVL**:
```evl
check: self.attributes.forAll(a | a.name.isDefined())
```

**Zeta**:
```java
entity.getAttributes().stream()
    .allMatch(a -> a.getName() != null)
```

---

### 7. Collection exists()

**EVL**:
```evl
check: self.attributes.exists(a | a.isPrimaryKey)
```

**Zeta**:
```java
entity.getAttributes().stream()
    .anyMatch(a -> a.isPrimaryKey())
```

---

### 8. Collection select()

**EVL**:
```evl
check: self.attributes.select(a | a.isPrimaryKey).size() == 1
```

**Zeta**:
```java
entity.getAttributes().stream()
    .filter(a -> a.isPrimaryKey())
    .count() == 1
```

Or with collect:
```java
List<Attribute> primaryKeys = entity.getAttributes().stream()
    .filter(a -> a.isPrimaryKey())
    .collect(Collectors.toList());
return primaryKeys.size() == 1;
```

---

### 9. Collection excluding()

**EVL**:
```evl
EntityType.allInstances().excluding(self)
```

**Zeta**:
```java
ctx.getAllInstances(EntityType.class).stream()
    .filter(e -> e != entity)
    .collect(Collectors.toList())
```

---

### 10. Type Checking (isTypeOf/isKindOf)

**EVL**:
```evl
check: self.isTypeOf(ConcreteEntity)
check: self.isKindOf(BaseEntity)
```

**Zeta**:
```java
// Exact type check
entity.getClass() == ConcreteEntity.class

// Instance check (includes subclasses)
entity instanceof BaseEntity
```

---

### 11. String Comparison (Case-Insensitive)

**EVL**:
```evl
check: self.name.equalsIgnoreCase(other.name)
```

**Zeta**:
```java
entity.getName().equalsIgnoreCase(other.getName())
```

---

### 12. String Pattern Matching

**EVL**:
```evl
check: self.name.matches("[A-Z][a-zA-Z0-9]*")
```

**Zeta**:
```java
entity.getName().matches("[A-Z][a-zA-Z0-9]*")
```

---

### 13. Collection Size/Count

**EVL**:
```evl
check: self.attributes.size() > 0
```

**Zeta**:
```java
entity.getAttributes().size() > 0
// or
!entity.getAttributes().isEmpty()
```

---

### 14. Null-Safe Navigation

**EVL**:
```evl
check: self.superType.isDefined() implies self.superType.name.isDefined()
```

**Zeta**:
```java
entity.getSuperType() == null || entity.getSuperType().getName() != null
```

---

### 15. Complex Boolean Logic

**EVL**:
```evl
check: (self.isAbstract and self.operations.notEmpty()) 
       or (not self.isAbstract and self.table.isDefined())
```

**Zeta**:
```java
(entity.isAbstract() && !entity.getOperations().isEmpty())
    || (!entity.isAbstract() && entity.getTable() != null)
```

---

## Complete Examples

### Example 1: Entity Name Validation

**EVL**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined() and self.name.length() > 0
        message: "Entity must have a non-empty name"
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have a non-empty name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a non-empty name");
        };
    }
}
```

---

### Example 2: Name Uniqueness with Guard

**EVL**:
```evl
context EntityType {
    constraint NameMustBeUnique {
        guard: self.name.isDefined()
        check: EntityType.allInstances()
                  .excluding(self)
                  .forAll(e | e.name <> self.name)
        message: "Entity name '" + self.name + "' is not unique"
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Guard(method = "hasName")
    @Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Collection<EntityType> allInstances = ctx.getAllInstances(EntityType.class);
            
            boolean hasDuplicate = allInstances.stream()
                .filter(e -> e != entity)
                .anyMatch(e -> entity.getName().equals(e.getName()));
            
            return hasDuplicate
                ? ValidationResult.fail("Entity name '" + entity.getName() + "' is not unique")
                : ValidationResult.pass();
        };
    }
    
    private boolean hasName(EObject element) {
        return ((EntityType) element).getName() != null;
    }
}
```

---

### Example 3: Primary Key Validation

**EVL**:
```evl
context EntityType {
    constraint MustHavePrimaryKey {
        guard: not self.isAbstract
        check: self.attributes.exists(a | a.isPrimaryKey)
        message: "Concrete entity must have at least one primary key attribute"
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Guard(method = "isNotAbstract")
    @Constraint(
        name = "MustHavePrimaryKey",
        message = "Concrete entity must have at least one primary key attribute"
    )
    public ValidationRule mustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            boolean hasPrimaryKey = entity.getAttributes().stream()
                .anyMatch(a -> a.isPrimaryKey());
            
            return hasPrimaryKey
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity must have at least one primary key attribute");
        };
    }
    
    private boolean isNotAbstract(EObject element) {
        return !((EntityType) element).isAbstract();
    }
}
```

---

### Example 4: Cyclic Inheritance Detection

**EVL**:
```evl
context EntityType {
    constraint NoCyclicInheritance {
        check: not self.hasCyclicInheritance()
        message: "Entity has cyclic inheritance: " + self.name
    }
}

operation EntityType hasCyclicInheritance() : Boolean {
    return self.getAllSuperTypes().includes(self);
}

operation EntityType getAllSuperTypes() : Sequence(EntityType) {
    var supers = new Sequence;
    var current = self.superType;
    while (current.isDefined()) {
        supers.add(current);
        current = current.superType;
    }
    return supers;
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "NoCyclicInheritance", message = "Entity has cyclic inheritance")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            boolean hasCycle = hasCyclicInheritance(entity);
            
            return hasCycle
                ? ValidationResult.fail("Entity has cyclic inheritance: " + entity.getName())
                : ValidationResult.pass();
        };
    }
    
    private boolean hasCyclicInheritance(EntityType entity) {
        Set<EntityType> visited = new HashSet<>();
        EntityType current = entity.getSuperType();
        
        while (current != null) {
            if (current == entity || visited.contains(current)) {
                return true;
            }
            visited.add(current);
            current = current.getSuperType();
        }
        return false;
    }
}
```

---

### Example 5: Cross-Reference Validation

**EVL**:
```evl
context Operation {
    constraint ParameterTypesMustExist {
        check: self.parameters.forAll(p | 
            p.type.isDefined() and 
            EntityType.allInstances().includes(p.type)
        )
        message: "All operation parameters must have valid types"
    }
}
```

**Zeta**:
```java
@ValidationContext(Operation.class)
public class OperationValidations {
    
    @Constraint(
        name = "ParameterTypesMustExist",
        message = "All operation parameters must have valid types"
    )
    public ValidationRule parameterTypesMustExist() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            Collection<EntityType> allTypes = ctx.getAllInstances(EntityType.class);
            
            boolean allTypesValid = operation.getParameters().stream()
                .allMatch(p -> p.getType() != null && allTypes.contains(p.getType()));
            
            return allTypesValid
                ? ValidationResult.pass()
                : ValidationResult.fail("All operation parameters must have valid types");
        };
    }
}
```

---

### Example 6: Multi-Constraint Dependencies

**EVL**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: "Entity must have a name"
    }
    
    constraint NameMustBeValid {
        guard: self.satisfies("MustHaveName")
        check: self.name.matches("[A-Z][a-zA-Z0-9]*")
        message: "Entity name must start with uppercase letter"
    }
    
    constraint NameMustBeUnique {
        guard: self.satisfies("MustHaveName") and self.satisfies("NameMustBeValid")
        check: EntityType.allInstances()
                  .excluding(self)
                  .forAll(e | e.name <> self.name)
        message: "Entity name must be unique"
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a name");
        };
    }
    
    @Satisfies(constraints = {"MustHaveName"})
    @Constraint(name = "NameMustBeValid", message = "Entity name must start with uppercase letter")
    public ValidationRule nameMustBeValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName().matches("[A-Z][a-zA-Z0-9]*")
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity name must start with uppercase letter");
        };
    }
    
    @Satisfies(constraints = {"MustHaveName", "NameMustBeValid"})
    @Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Collection<EntityType> allInstances = ctx.getAllInstances(EntityType.class);
            
            boolean hasDuplicate = allInstances.stream()
                .filter(e -> e != entity)
                .anyMatch(e -> entity.getName().equals(e.getName()));
            
            return hasDuplicate
                ? ValidationResult.fail("Entity name must be unique")
                : ValidationResult.pass();
        };
    }
}
```

---

## Summary: Quick Reference Table

| EVL Construct | Zeta Equivalent |
|---------------|-----------------|
| `context EntityType { }` | `@ValidationContext(EntityType.class)` |
| `constraint Name { }` | `@Constraint(name = "Name", message = "...")` |
| `critique Name { }` | `@Critique(name = "Name", message = "...")` |
| `guard: expression` | `@Guard(method = "guardMethod")` |
| `check: expression` | Lambda body with conditional logic |
| `message: "text"` | `ValidationResult.fail("text")` |
| `pre Name { }` | `@PreValidation public void name() { }` |
| `post Name { }` | `@PostValidation public void name() { }` |
| `self.satisfies("Name")` | `@Satisfies(constraints = {"Name"})` |
| `self.property` | `entity.getProperty()` |
| `Type.allInstances()` | `ctx.getAllInstances(Type.class)` |
| `collection.forAll(x \| condition)` | `.stream().allMatch(x -> condition)` |
| `collection.exists(x \| condition)` | `.stream().anyMatch(x -> condition)` |
| `collection.select(x \| condition)` | `.stream().filter(x -> condition)` |
| `collection.excluding(item)` | `.stream().filter(x -> x != item)` |
| `value.isDefined()` | `value != null` |
| `string.length()` | `string.length()` |
| `string.matches(pattern)` | `string.matches(pattern)` |
| `collection.isEmpty()` | `collection.isEmpty()` |
| `collection.size()` | `collection.size()` |
| `self.eContainer()` | `entity.eContainer()` |

---

## Related Topics

- [EVL Overview](overview.md) - High-level comparison and philosophy
- [Migration Guide](migration-guide.md) - Step-by-step migration process
- [Feature Parity](feature-parity.md) - What's supported and what's not
- [Validation Rules](../user-guide/validation-rules.md) - Detailed Zeta validation guide

---

**Previous**: [EVL Overview](overview.md) | **Next**: [Migration Guide](migration-guide.md)
