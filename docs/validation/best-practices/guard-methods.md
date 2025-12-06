# Guard Methods Best Practices

**Navigation**: [Documentation Hub](../../index.md) > [Best Practices](../index.md#best-practices) > Guard Methods

This guide covers best practices for writing effective guard methods that control when validation rules execute.

## Overview

Guard methods are predicates that determine whether a validation rule should run for a specific element. Well-designed guards improve performance, clarity, and maintainability by ensuring rules only execute when applicable.

### What You'll Learn

- Guard method fundamentals and signatures
- Naming conventions and patterns
- Complex guard conditions with multiple checks
- Reusing guards across multiple constraints
- Combining guards with `satisfies()` checks
- Testing strategies for guard methods
- Common guard patterns for type, null, and state checking

## Guard Method Fundamentals

### Required Signature

Guard methods must follow this exact signature:

```java
public boolean guardMethodName(EObject element, ValidationContext ctx) {
    // Return true to execute the rule, false to skip
}
```

**Parameters**:
- `element` - The element being validated (type `EObject`, cast to your specific type)
- `ctx` - The validation context providing access to model, cache, and satisfies checks

**Return value**:
- `true` - Guard passes, execute the validation rule
- `false` - Guard fails, skip validation (returns `ValidationResult.pass()`)

### Basic Guard Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "ConcreteEntityMustHaveTable",
        message = "Concrete entity must have table mapping"
    )
    @Guard(method = "isConcrete")
    public ValidationRule concreteEntityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTableName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity requires table mapping");
        };
    }
    
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        EntityType entity = (EntityType) element;
        return !entity.isAbstract();
    }
}
```

## Naming Conventions

### Use Descriptive, Question-Like Names

Guard method names should read like yes/no questions about the element:

```java
// ✓ GOOD: Clear, question-like names
public boolean isAbstract(EObject element, ValidationContext ctx) { ... }
public boolean isConcrete(EObject element, ValidationContext ctx) { ... }
public boolean isMapped(EObject element, ValidationContext ctx) { ... }
public boolean hasContainer(EObject element, ValidationContext ctx) { ... }
public boolean hasValidMapping(EObject element, ValidationContext ctx) { ... }
public boolean isOverridingAbstractOperation(EObject element, ValidationContext ctx) { ... }

// ❌ BAD: Generic or unclear names
public boolean check(EObject element, ValidationContext ctx) { ... }
public boolean validate(EObject element, ValidationContext ctx) { ... }
public boolean guard1(EObject element, ValidationContext ctx) { ... }
public boolean condition(EObject element, ValidationContext ctx) { ... }
```

### Standard Prefixes

Use standard prefixes for consistency:

| Prefix | Use Case | Example |
|--------|----------|---------|
| `is` | Boolean state/property check | `isAbstract()`, `isMapped()`, `isValid()` |
| `has` | Existence/presence check | `hasContainer()`, `hasMapping()`, `hasSuperType()` |
| `should` | Conditional applicability | `shouldValidateMapping()`, `shouldCheckInheritance()` |
| `can` | Permission/ability check | `canBeOverridden()`, `canHaveAttributes()` |

### Negative vs Positive Guards

Choose the form that makes the rule most readable:

```java
// When the rule applies to CONCRETE entities:
@Guard(method = "isConcrete")
@Constraint(name = "ConcreteEntityMustHaveTable", message = "...")
public ValidationRule concreteEntityMustHaveTable() { ... }

public boolean isConcrete(EObject element, ValidationContext ctx) {
    return !((EntityType) element).isAbstract();
}

// Alternative: Use negative guard name (less clear)
@Guard(method = "isNotAbstract")  // Less clear than "isConcrete"
@Constraint(name = "ConcreteEntityMustHaveTable", message = "...")
public ValidationRule concreteEntityMustHaveTable() { ... }

public boolean isNotAbstract(EObject element, ValidationContext ctx) {
    return !((EntityType) element).isAbstract();
}
```

**Best Practice**: Prefer positive guard names (`isConcrete`) over negative ones (`isNotAbstract`) for clarity.

## Common Guard Patterns

### 1. Type Checking Guards

Check element properties or types:

```java
@Guard(method = "isAbstract")
@Constraint(name = "AbstractEntityConstraint", message = "...")
public ValidationRule abstractEntityConstraint() {
    return (element, ctx) -> {
        // Only runs for abstract entities
        EntityType entity = (EntityType) element;
        // Validation logic here
    };
}

public boolean isAbstract(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return entity.isAbstract();
}
```

**Common type checks**:
```java
// Abstract vs concrete
public boolean isAbstract(EObject element, ValidationContext ctx) {
    return ((EntityType) element).isAbstract();
}

// Mapped vs unmapped
public boolean isMapped(EObject element, ValidationContext ctx) {
    return ((EntityType) element).getMapping() != null;
}

// Has specific attribute set
public boolean isTransient(EObject element, ValidationContext ctx) {
    return ((Attribute) element).isTransient();
}
```

### 2. Null Checking Guards

Verify required relationships exist before validation:

```java
@Guard(method = "hasContainer")
@Constraint(name = "OperationMustBelongToEntity", message = "...")
public ValidationRule operationMustBelongToEntity() {
    return (element, ctx) -> {
        Operation op = (Operation) element;
        EntityType owner = (EntityType) op.eContainer();
        // Safe to use owner - guard ensures it's not null
        return owner instanceof EntityType
            ? ValidationResult.pass()
            : ValidationResult.fail("Operation must belong to EntityType");
    };
}

public boolean hasContainer(EObject element, ValidationContext ctx) {
    return element.eContainer() != null;
}
```

**Common null checks**:
```java
// Container existence
public boolean hasContainer(EObject element, ValidationContext ctx) {
    return element.eContainer() != null;
}

// Reference existence
public boolean hasTarget(EObject element, ValidationContext ctx) {
    return ((Reference) element).getTarget() != null;
}

// Optional field existence
public boolean hasDescription(EObject element, ValidationContext ctx) {
    String desc = ((NamedElement) element).getDescription();
    return desc != null && !desc.isEmpty();
}
```

### 3. State Checking Guards

Verify element state before complex validation:

```java
@Guard(method = "isOverridingOperation")
@Constraint(
    name = "OverrideSignatureMustMatch",
    message = "Override must have compatible signature"
)
public ValidationRule overrideSignatureMustMatch() {
    return (element, ctx) -> {
        Operation op = (Operation) element;
        // Only runs for operations that override base operations
        // Complex override validation logic here
    };
}

public boolean isOverridingOperation(EObject element, ValidationContext ctx) {
    Operation op = (Operation) element;
    EntityType owner = (EntityType) op.eContainer();
    
    if (owner == null || owner.getSuperType() == null) {
        return false;
    }
    
    // Check if supertype has operation with same name
    return owner.getSuperType().getOperations().stream()
        .anyMatch(baseOp -> baseOp.getName().equals(op.getName()));
}
```

### 4. Container-Based Guards

Check properties of containing elements:

```java
@Guard(method = "belongsToAbstractEntity")
@Constraint(
    name = "AbstractOperationRules",
    message = "Abstract operation validation"
)
public ValidationRule abstractOperationRules() {
    return (element, ctx) -> {
        // Only runs for operations in abstract entities
    };
}

public boolean belongsToAbstractEntity(EObject element, ValidationContext ctx) {
    Operation op = (Operation) element;
    EntityType owner = (EntityType) op.eContainer();
    return owner != null && owner.isAbstract();
}
```

## Complex Guard Conditions

### Multiple Checks in One Guard

Combine multiple conditions in a single guard method:

```java
@Guard(method = "isConcreteAndMapped")
@Constraint(name = "MappedConcreteMustHaveBinding", message = "...")
public ValidationRule mappedConcreteMustHaveBinding() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getBinding() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Concrete, mapped entity requires binding");
    };
}

public boolean isConcreteAndMapped(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return !entity.isAbstract() && entity.getMapping() != null;
}
```

**Best Practice**: Use descriptive names that indicate all conditions:
- `isConcreteAndMapped()` - clearly shows both conditions
- `isMappedConcrete()` - alternative form
- Avoid: `isValid()` - too generic

### Early Return Pattern

Use early returns for clarity:

```java
public boolean isOverridingAbstractOperation(EObject element, ValidationContext ctx) {
    Operation op = (Operation) element;
    EntityType owner = (EntityType) op.eContainer();
    
    // Early return for common cases
    if (owner == null) {
        return false;
    }
    
    if (owner.getSuperType() == null) {
        return false;
    }
    
    // Main logic
    return owner.getSuperType().getOperations().stream()
        .anyMatch(baseOp -> baseOp.getName().equals(op.getName()) 
                         && baseOp.isAbstract());
}
```

### Extracting Helper Methods

For very complex guards, extract helper methods:

```java
@Guard(method = "requiresInheritanceValidation")
@Constraint(name = "InheritanceHierarchyValid", message = "...")
public ValidationRule inheritanceHierarchyValid() {
    return (element, ctx) -> {
        // Complex inheritance validation
    };
}

public boolean requiresInheritanceValidation(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return hasSuperType(entity) 
        && !isTopLevelEntity(entity)
        && !hasBeenValidated(entity, ctx);
}

private boolean hasSuperType(EntityType entity) {
    return entity.getSuperType() != null;
}

private boolean isTopLevelEntity(EntityType entity) {
    return entity.getSuperType() == null;
}

private boolean hasBeenValidated(EntityType entity, ValidationContext ctx) {
    return ctx.satisfies(entity, "BasicEntityStructureValid");
}
```

## Reusing Guards Across Multiple Constraints

### Single Guard for Multiple Rules

Share guard methods across related validation rules:

```java
@ValidationContext(Operation.class)
public class OperationValidations {
    
    // Multiple constraints use the same guard
    @Constraint(
        name = "AbstractOperationBelongsToEntityType",
        message = "Abstract operation must belong to entity type"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractOperationBelongsToEntityType() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            return op.eContainer() instanceof EntityType
                ? ValidationResult.pass()
                : ValidationResult.fail("Must belong to EntityType");
        };
    }
    
    @Constraint(
        name = "AbstractOperationBelongsToAbstractEntity",
        message = "Abstract operation can only belong to abstract entity"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractOperationBelongsToAbstractEntity() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            EntityType owner = (EntityType) op.eContainer();
            return owner.isAbstract()
                ? ValidationResult.pass()
                : ValidationResult.fail("Abstract operation requires abstract entity");
        };
    }
    
    @Constraint(name = "AbstractOperationIsValid", message = "...")
    @Guard(method = "isAbstract")
    public ValidationRule abstractOperationIsValid() {
        return (element, ctx) -> {
            // More abstract operation validation
        };
    }
    
    // Shared guard method
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        Operation op = (Operation) element;
        EntityType owner = (EntityType) op.eContainer();
        return owner != null && owner.isAbstract();
    }
}
```

**Benefits of reuse**:
- Consistent behavior across related rules
- Single point of change if guard logic evolves
- Clearer grouping of related validations
- Less code duplication

### Guard Families

Create families of related guards:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Abstract entity guards
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EntityType) element).isAbstract();
    }
    
    public boolean isConcrete(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
    
    // Mapping guards
    public boolean isMapped(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getMapping() != null;
    }
    
    public boolean isUnmapped(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getMapping() == null;
    }
    
    // Inheritance guards
    public boolean hasSuperType(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getSuperType() != null;
    }
    
    public boolean isRootEntity(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getSuperType() == null;
    }
}
```

## Combining Guards with satisfies()

### Checking Previous Constraints

Guards can verify that prerequisite constraints passed:

```java
@Guard(method = "hasSatisfiedMapping")
@Constraint(name = "BindingMustMatchMapping", message = "...")
public ValidationRule bindingMustMatchMapping() {
    return (element, ctx) -> {
        // Only runs if MappingIsValid constraint passed
        EntityType entity = (EntityType) element;
        // Validation logic assuming valid mapping
    };
}

public boolean hasSatisfiedMapping(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    return entity.getMapping() != null 
        && ctx.satisfies(entity, "MappingIsValid");
}
```

### Complex Guard Dependencies

Combine property checks with constraint satisfaction:

```java
@Guard(method = "canValidateOverride")
@Constraint(name = "OverrideIsValid", message = "...")
public ValidationRule overrideIsValid() {
    return (element, ctx) -> {
        // Complex override validation
    };
}

public boolean canValidateOverride(EObject element, ValidationContext ctx) {
    Operation op = (Operation) element;
    
    // Check basic properties
    if (op.eContainer() == null) {
        return false;
    }
    
    // Check that basic validations passed
    if (!ctx.satisfies(op, "OperationHasName")) {
        return false;
    }
    
    if (!ctx.satisfies(op, "OperationHasOwner")) {
        return false;
    }
    
    // Check if this is actually an override
    EntityType owner = (EntityType) op.eContainer();
    return owner.getSuperType() != null
        && owner.getSuperType().getOperations().stream()
            .anyMatch(baseOp -> baseOp.getName().equals(op.getName()));
}
```

### Guard vs Satisfies Decision

**Use Guard when**:
- The rule doesn't apply to certain elements (filtering)
- Checking element properties (abstract, mapped, etc.)
- Verifying relationships exist

**Use @Satisfies when**:
- The rule depends on other validations passing
- Building validation chains (level 1, 2, 3...)
- Preventing cascading errors

**Combine both when**:
- Rule applies to subset of elements AND depends on other validations
- Example: "For abstract operations (guard), validate override rules (satisfies base checks)"

## Testing Guard Methods

### Unit Testing Guards

Test guard methods independently:

```java
@Test
public void testIsAbstractGuard() {
    OperationValidations validator = new OperationValidations();
    ValidationContext ctx = createMockContext();
    
    // Test with abstract operation
    Operation abstractOp = createOperation("testOp");
    EntityType abstractEntity = createAbstractEntity();
    abstractEntity.getOperations().add(abstractOp);
    
    assertTrue(validator.isAbstract(abstractOp, ctx),
        "Guard should return true for operations in abstract entities");
    
    // Test with concrete operation
    Operation concreteOp = createOperation("testOp");
    EntityType concreteEntity = createConcreteEntity();
    concreteEntity.getOperations().add(concreteOp);
    
    assertFalse(validator.isAbstract(concreteOp, ctx),
        "Guard should return false for operations in concrete entities");
}
```

### Testing Guard Behavior

Verify that guards actually prevent rule execution:

```java
@Test
public void testGuardPreventsExecution() {
    ValidationRegistry registry = new ValidationRegistry();
    registry.register(OperationValidations.class);
    
    ValidationExecutor executor = ValidationExecutor.builder()
        .registry(registry)
        .build();
    
    // Create concrete operation (guard should fail)
    Operation concreteOp = createOperation("testOp");
    EntityType concreteEntity = createConcreteEntity();
    concreteEntity.getOperations().add(concreteOp);
    
    List<ValidationResult> results = executor.validate(
        List.of(concreteOp)
    );
    
    // Verify guarded constraint didn't run (returns pass when guard fails)
    assertTrue(results.stream()
        .filter(r -> r.getConstraintName().equals("AbstractOperationConstraint"))
        .allMatch(ValidationResult::isPassed),
        "Guarded constraint should pass (skip) for concrete operations");
}
```

### Testing Complex Guards

For guards with multiple conditions:

```java
@Test
public void testComplexGuard() {
    EntityTypeValidations validator = new EntityTypeValidations();
    ValidationContext ctx = createMockContext();
    
    // Test all condition combinations
    
    // Concrete + Mapped = true
    EntityType entity1 = createEntity("Test1");
    entity1.setAbstract(false);
    entity1.setMapping(createMapping());
    assertTrue(validator.isConcreteAndMapped(entity1, ctx));
    
    // Abstract + Mapped = false
    EntityType entity2 = createEntity("Test2");
    entity2.setAbstract(true);
    entity2.setMapping(createMapping());
    assertFalse(validator.isConcreteAndMapped(entity2, ctx));
    
    // Concrete + Not Mapped = false
    EntityType entity3 = createEntity("Test3");
    entity3.setAbstract(false);
    entity3.setMapping(null);
    assertFalse(validator.isConcreteAndMapped(entity3, ctx));
    
    // Abstract + Not Mapped = false
    EntityType entity4 = createEntity("Test4");
    entity4.setAbstract(true);
    entity4.setMapping(null);
    assertFalse(validator.isConcreteAndMapped(entity4, ctx));
}
```

### Integration Testing

Test guards in the context of full validation:

```java
@Test
public void testGuardedValidationChain() {
    // Register validators
    ValidationRegistry registry = new ValidationRegistry();
    registry.register(OperationValidations.class);
    
    ValidationExecutor executor = ValidationExecutor.builder()
        .registry(registry)
        .build();
    
    // Create test model
    EntityType abstractEntity = createAbstractEntity();
    Operation abstractOp = createOperation("doSomething");
    abstractEntity.getOperations().add(abstractOp);
    
    // Validate
    List<ValidationResult> results = executor.validate(
        List.of(abstractEntity, abstractOp)
    );
    
    // Verify that guarded rules executed correctly
    long abstractOpErrors = results.stream()
        .filter(r -> r.getElement() == abstractOp)
        .filter(r -> !r.isPassed())
        .count();
    
    assertTrue(abstractOpErrors > 0,
        "Abstract operation validations should have run");
}
```

## Performance Considerations

### Guard Execution Cost

Guards are evaluated before every validation, so keep them lightweight:

```java
// ✓ GOOD: Simple, fast guard
public boolean isAbstract(EObject element, ValidationContext ctx) {
    return ((EntityType) element).isAbstract();
}

// ❌ BAD: Expensive guard with traversal
public boolean hasComplexStructure(EObject element, ValidationContext ctx) {
    EntityType entity = (EntityType) element;
    // DON'T: Expensive traversal in guard
    return getAllAttributes(entity, ctx).size() > 10
        && getAllOperations(entity, ctx).size() > 5
        && getInheritanceDepth(entity) > 3;
}
```

**Best Practice**: If the guard logic is expensive, consider:
1. Moving it into the validation rule itself
2. Using `@Satisfies` to check a cached constraint result
3. Caching the guard result in the validation context

### Caching Guard Results

For expensive guards, cache results in the context:

```java
public boolean hasComplexStructure(EObject element, ValidationContext ctx) {
    CacheKey key = CacheKey.of(element, "complexStructure");
    Boolean cached = (Boolean) ctx.getCached(key);
    
    if (cached != null) {
        return cached;
    }
    
    // Expensive computation
    EntityType entity = (EntityType) element;
    boolean result = computeComplexStructure(entity);
    
    ctx.putCached(key, result);
    return result;
}
```

### Guard Order Optimization

When using multiple guards, put the cheapest/most selective first:

```java
// If these were individual guards (using ValidationRuleBuilder):
ValidationRuleBuilder.create()
    .guard((e, ctx) -> e.eContainer() != null)  // Cheap null check first
    .guard((e, ctx) -> !((EntityType) e).isAbstract())  // Simple property check
    .guard((e, ctx) -> hasValidInheritance(e, ctx))  // More expensive check last
    .check((e, ctx) -> {
        // Validation logic
    })
    .build();
```

## Common Patterns from Production Code

### Pattern 1: Operation Type Check

From `OperationValidations`:

```java
@Guard(method = "isAbstract")
@Constraint(
    name = "AbstractOperationBelongsToEntityType",
    message = "Abstract operation must belong to an entity type"
)
public ValidationRule abstractOperationBelongsToEntityType() {
    return (element, ctx) -> {
        Operation op = (Operation) element;
        return op.eContainer() instanceof EntityType
            ? ValidationResult.pass()
            : ValidationResult.fail("Abstract operation must belong to EntityType");
    };
}

public boolean isAbstract(EObject element, ValidationContext ctx) {
    Operation op = (Operation) element;
    EntityType owner = (EntityType) op.eContainer();
    // operationType property checked from container
    return owner != null && owner.isAbstract();
}
```

**Use Case**: Validating operations differently based on whether their containing entity is abstract.

### Pattern 2: Container Existence Check

```java
@Guard(method = "hasEntityContainer")
@Constraint(name = "AttributeBelongsToEntity", message = "...")
public ValidationRule attributeBelongsToEntity() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        EntityType owner = (EntityType) attr.eContainer();
        // Safe to use owner - guard ensures it exists
    };
}

public boolean hasEntityContainer(EObject element, ValidationContext ctx) {
    return element.eContainer() != null 
        && element.eContainer() instanceof EntityType;
}
```

**Use Case**: Ensuring element has proper containment before validating container relationships.

### Pattern 3: Prerequisite Constraint Check

```java
@Guard(method = "hasValidBasicStructure")
@Constraint(name = "AdvancedEntityValidation", message = "...")
public ValidationRule advancedEntityValidation() {
    return (element, ctx) -> {
        // Complex validation that requires basic structure to be valid
    };
}

public boolean hasValidBasicStructure(EObject element, ValidationContext ctx) {
    return ctx.satisfies(element, "EntityHasName")
        && ctx.satisfies(element, "EntityHasValidType")
        && ctx.satisfies(element, "EntityHasContainer");
}
```

**Use Case**: Only run expensive validations when prerequisites are satisfied.

## Anti-Patterns to Avoid

### Don't Duplicate Validation Logic in Guards

```java
// ❌ BAD: Duplicating validation logic
public boolean hasValidName(EObject element, ValidationContext ctx) {
    String name = ((NamedElement) element).getName();
    // Don't validate in guard - just check applicability
    return name != null && name.matches("[A-Z][a-zA-Z0-9]*");
}

// ✓ GOOD: Guard checks applicability only
public boolean hasName(EObject element, ValidationContext ctx) {
    return ((NamedElement) element).getName() != null;
}
```

### Don't Modify Model in Guards

```java
// ❌ BAD: Side effects in guard
public boolean hasDefaultName(EObject element, ValidationContext ctx) {
    NamedElement ne = (NamedElement) element;
    if (ne.getName() == null) {
        ne.setName("DEFAULT");  // DON'T modify in guard!
    }
    return true;
}

// ✓ GOOD: Guards are read-only
public boolean hasName(EObject element, ValidationContext ctx) {
    return ((NamedElement) element).getName() != null;
}
```

### Don't Use Overly Generic Guards

```java
// ❌ BAD: Too generic to be useful
public boolean isValid(EObject element, ValidationContext ctx) {
    // What does "valid" mean? Too vague.
    return true;
}

// ✓ GOOD: Specific and clear
public boolean hasRequiredMapping(EObject element, ValidationContext ctx) {
    return ((EntityType) element).getMapping() != null;
}
```

## Summary

Guard methods are powerful tools for controlling validation rule execution:

- **Use descriptive names** with prefixes like `is`, `has`, `can`, `should`
- **Keep guards simple** - they run before every validation
- **Reuse guards** across multiple related constraints
- **Combine with satisfies()** for prerequisite checking
- **Test guards independently** to ensure correct filtering
- **Cache expensive guards** if necessary
- **Follow the single responsibility principle** - guards check applicability, not validity

Well-designed guards improve:
- **Performance** - Skip unnecessary validations
- **Clarity** - Make rule applicability explicit
- **Maintainability** - Centralize applicability logic
- **Error messages** - Avoid confusing errors for non-applicable elements

## Related Topics

- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Comprehensive guide to guards and @Satisfies
- [Writing Validation Rules](../user-guide/validation-rules.md) - Creating effective validation rules
- [Performance Best Practices](performance.md) - Optimization strategies
- [Operation Validations Example](../examples/operation-validations.md) - Real-world guard usage

---

**Previous**: [Extension Delegation](extension-delegation.md) | **Next**: [Error Messages](error-messages.md)
