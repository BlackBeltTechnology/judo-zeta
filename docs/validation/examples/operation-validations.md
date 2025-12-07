# Complex Operation Validation Examples

**Navigation**: [Documentation Hub](../../index.md) > [Examples](../index.md#examples) > Operation Validations

This document showcases complex, real-world validation examples from the judo-meta-esm metamodel, focusing on operation validations with sophisticated dependency chains, guard methods, and extension method calls.

---

## Table of Contents

1. [Overview](#overview)
2. [Abstract Operation Validations](#abstract-operation-validations)
3. [Instance Operation Validations](#instance-operation-validations)
4. [Mapped Operation Validations](#mapped-operation-validations)
5. [Parameter Compatibility Checks](#parameter-compatibility-checks)
6. [Override Validation with Satisfies Chains](#override-validation-with-satisfies-chains)
7. [Complete Validation Class](#complete-validation-class)
8. [Extension Methods for Complex Queries](#extension-methods-for-complex-queries)

---

## Overview

Operation validations in enterprise metamodels like ESM demonstrate the full power of the Zeta validation framework:

- **Complex @Satisfies chains** with multiple levels of dependencies
- **Guard methods** that filter by operation type (ABSTRACT, INSTANCE, MAPPED)
- **Extension method calls** for sophisticated model queries
- **Rich error messages** with full context about container, operation name, and hierarchy
- **Cross-object validation** checking inheritance and override compatibility

This guide shows patterns extracted from production ESM validation code, adapted for the Zeta framework.

### Operation Types in ESM

The ESM metamodel defines three operation types:

```java
public enum OperationType {
    ABSTRACT,   // Operation declared in abstract entity, no implementation
    INSTANCE,   // Regular operation with implementation
    MAPPED      // Operation that delegates to mapped backend operation
}
```

Each type has distinct validation requirements, controlled by guard methods.

---

## Abstract Operation Validations

Abstract operations are declared in abstract entity types and must be implemented by concrete subclasses. These validations form a dependency chain ensuring proper containment and structure.

### Validation Chain: Level 1 - Container Type Check

**Constraint**: Abstract operation must belong to an EntityType

```java
@ValidationContext(Operation.class)
public class AbstractOperationValidations {
    
    /**
     * Level 1: Abstract operation container must be an EntityType
     * 
     * This is the base constraint in the chain. It ensures that:
     * - The operation has a container (checked by guard)
     * - The container is specifically an EntityType
     * 
     * EVL equivalent:
     *   guard: self.satisfies("NamedElementHasContainer") 
     *          and self.operationType == ESM!OperationType#ABSTRACT
     *   constraint AbstractOperationBelongsToEntityType {
     *       check: self.eContainer.isKindOf(ESM!EntityType)
     *       message: "Container of abstract operation must be an entity type"
     *   }
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
            
            // Check if container is an EntityType (not some other type)
            if (!(container instanceof EntityType)) {
                return ValidationResult.fail(
                    "AbstractOperationBelongsToEntityType",
                    "Container of abstract operation '" + operation.getName() + 
                    "' must be an EntityType, but found: " + 
                    container.eClass().getName(),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Guard: Only validate abstract operations with containers
     * 
     * This guard combines two checks:
     * 1. Operation has a container (replaces EVL satisfies("NamedElementHasContainer"))
     * 2. Operation type is ABSTRACT
     * 
     * Returns false for:
     * - Operations without containers
     * - INSTANCE operations
     * - MAPPED operations
     */
    public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
        Operation operation = (Operation) element;
        
        // First check: container exists
        if (operation.eContainer() == null) {
            return false;
        }
        
        // Second check: operation type is ABSTRACT
        return operation.getOperationType() == OperationType.ABSTRACT;
    }
}
```

**Key Features**:
- **Guard method** filters by operation type, preventing validation of INSTANCE/MAPPED operations
- **Container null check** in guard prevents NullPointerException
- **Error message** includes operation name and actual container type for debugging
- **EVL mapping** shows equivalent Epsilon Validation Language syntax

### Validation Chain: Level 2 - Abstract Entity Check

**Constraint**: Abstract operation can only belong to abstract entity type

**Dependencies**: `AbstractOperationBelongsToEntityType`

```java
/**
 * Level 2: Container must be an abstract entity type
 * 
 * Depends on: AbstractOperationBelongsToEntityType
 * 
 * This builds on the previous constraint:
 * - Level 1 ensures container is EntityType
 * - Level 2 ensures that EntityType is abstract
 * 
 * The @Satisfies dependency prevents this from running if Level 1 failed,
 * avoiding cascading errors and NullPointerExceptions.
 * 
 * EVL equivalent:
 *   guard: self.satisfies("AbstractOperationBelongsToEntityType")
 *   constraint AbstractOperationBelongsToAbstractEntityType {
 *       check: self.eContainer.`abstract`
 *       message: "Container must be abstract entity type"
 *   }
 */
@Guard(method = "isAbstractOperation")
@Satisfies(constraints = {"AbstractOperationBelongsToEntityType"})
@Constraint(
    name = "AbstractOperationBelongsToAbstractEntityType",
    message = "Abstract operation can only belong to abstract entity type"
)
public ValidationRule abstractOperationBelongsToAbstractEntityType() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType container = (EntityType) operation.eContainer();
        
        // Safe to cast - Level 1 constraint ensures this is EntityType
        if (!container.isAbstract()) {
            return ValidationResult.fail(
                "AbstractOperationBelongsToAbstractEntityType",
                "Abstract operation '" + container.getName() + "." + 
                operation.getName() + "' cannot belong to concrete entity type. " +
                "Either mark entity '" + container.getName() + 
                "' as abstract or change operation type to INSTANCE.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Key Features**:
- **@Satisfies dependency** ensures Level 1 passed before running
- **Safe casting** to EntityType because dependency guarantees container type
- **Actionable error message** suggests two ways to fix the problem
- **Full qualified name** in message (EntityType.operationName) for clarity

### Validation Chain: Level 3 - Override Validity

**Constraint**: Abstract operation cannot override non-abstract base operations

**Dependencies**: `AbstractOperationBelongsToAbstractEntityType` (implicitly includes Level 1)

```java
/**
 * Level 3: Abstract operation cannot override non-abstract operations
 * 
 * Depends on: AbstractOperationBelongsToAbstractEntityType
 * (which depends on: AbstractOperationBelongsToEntityType)
 * 
 * This validates that an abstract operation doesn't incorrectly override
 * a concrete implementation in a supertype. This would be semantically invalid:
 * - BaseEntity.calculate() - INSTANCE (has implementation)
 * - DerivedEntity.calculate() - ABSTRACT (declares no implementation)
 * 
 * The operation walks up the inheritance hierarchy checking for non-abstract
 * operations with the same name.
 * 
 * EVL equivalent:
 *   constraint AbstractOperationIsValid {
 *       check: esmUtils.getInheritedNonAbstractOperationsByName(
 *                  self.eContainer, self.name).size() == 0
 *       message: "Operation cannot be abstract if it has non-abstract bases."
 *   }
 */
@Guard(method = "isAbstractOperation")
@Satisfies(constraints = {"AbstractOperationBelongsToAbstractEntityType"})
@Constraint(
    name = "AbstractOperationIsValid",
    message = "Abstract operation cannot override non-abstract base operations"
)
public ValidationRule abstractOperationIsValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType container = (EntityType) operation.eContainer();
        
        // Call extension method to get inherited non-abstract operations
        List<Operation> inheritedNonAbstract = 
            ctx.callExtension(
                "getInheritedNonAbstractOperationsByName",
                container,
                operation.getName()
            );
        
        if (!inheritedNonAbstract.isEmpty()) {
            // Build detailed error message showing inheritance chain
            StringBuilder chain = new StringBuilder();
            for (Operation inherited : inheritedNonAbstract) {
                EntityType inheritedOwner = (EntityType) inherited.eContainer();
                chain.append("\n  - ")
                     .append(inheritedOwner.getName())
                     .append(".")
                     .append(inherited.getName())
                     .append(" (")
                     .append(inherited.getOperationType())
                     .append(")");
            }
            
            return ValidationResult.fail(
                "AbstractOperationIsValid",
                "Operation '" + container.getName() + "." + operation.getName() + 
                "' cannot be ABSTRACT because it overrides non-abstract operations:" +
                chain.toString() + "\n" +
                "Change this operation to INSTANCE or make the base operations ABSTRACT.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Key Features**:
- **Extension method call** via `ctx.callExtension()` for complex query
- **Inheritance traversal** checks all supertypes in hierarchy
- **Detailed error message** lists all conflicting base operations
- **Multi-line formatting** shows inheritance chain clearly

### Level 4 - Implementation Warning (Critique)

**Critique**: Abstract operations should not have implementation

**Dependencies**: `AbstractOperationIsValid`

```java
/**
 * Level 4: Abstract operation should not have implementation (warning)
 * 
 * Depends on: AbstractOperationIsValid
 * 
 * This is a CRITIQUE (warning), not a CONSTRAINT (error):
 * - Abstract operations typically have no body
 * - However, they might have documentation or placeholder comments
 * - We warn but don't fail validation
 * 
 * The @Satisfies ensures we only warn if the operation structure is valid.
 * No point warning about implementation if the operation itself is invalid.
 * 
 * EVL equivalent:
 *   guard: self.satisfies("AbstractOperationIsValid")
 *   critique AbstractOperationCannotHaveImplementation {
 *       check: self.body.isUndefined() or self.body.trim().length() == 0
 *       message: "Abstract operation should not have implementation."
 *   }
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
        
        // Check if body is defined and not empty (ignoring whitespace)
        if (body != null && !body.trim().isEmpty()) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.warn(
                "AbstractOperationCannotHaveImplementation",
                "Abstract operation '" + container.getName() + "." + 
                operation.getName() + "' has implementation code: \"" + 
                (body.length() > 50 ? body.substring(0, 47) + "..." : body) + "\". " +
                "Consider removing the implementation or changing operation type to INSTANCE.",
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

**Key Features**:
- **@Critique** annotation produces warning, not error
- **ValidationResult.warn()** instead of fail()
- **Body preview** in message (truncated to 50 chars)
- **Dependency chain** ensures all prerequisites are valid before warning

---

## Instance Operation Validations

Instance operations are regular operations with implementations. They validate implementation requirements and parameter handling.

### Instance Operation Must Have Implementation

```java
/**
 * Instance operations require implementation
 * 
 * Guard ensures we only check INSTANCE type operations.
 * Instance operations must have a non-empty body.
 * 
 * EVL equivalent:
 *   guard: self.operationType == ESM!OperationType#INSTANCE
 *   constraint InstanceOperationMustHaveImplementation {
 *       check: self.body.isDefined() and self.body.trim().length() > 0
 *       message: "Instance operation must have implementation"
 *   }
 */
@Guard(method = "isInstanceOperation")
@Constraint(
    name = "InstanceOperationMustHaveImplementation",
    message = "Instance operation must have implementation"
)
public ValidationRule instanceOperationMustHaveImplementation() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        String body = operation.getBody();
        
        if (body == null || body.trim().isEmpty()) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.fail(
                "InstanceOperationMustHaveImplementation",
                "Instance operation '" + container.getName() + "." + 
                operation.getName() + "' must have implementation. " +
                "Add operation body or change type to ABSTRACT if this is " +
                "meant to be implemented by subclasses.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Guard: Only validate instance operations
 */
public boolean isInstanceOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    return operation.getOperationType() == OperationType.INSTANCE;
}
```

### Instance Operation Parameter Validation

```java
/**
 * Instance operation parameters must have valid types
 * 
 * Validates that all parameters reference existing entity types.
 * Uses cross-object validation to check parameter types.
 */
@Guard(method = "isInstanceOperation")
@Constraint(
    name = "InstanceOperationParametersValid",
    message = "Instance operation parameters must have valid types"
)
public ValidationRule instanceOperationParametersValid() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        List<Parameter> parameters = operation.getParameters();
        List<EntityType> allTypes = ctx.getAllInstances(EntityType.class);
        
        List<String> invalidParams = new ArrayList<>();
        
        for (Parameter param : parameters) {
            if (param.getType() == null) {
                invalidParams.add(param.getName() + " (no type specified)");
            } else {
                // Check if type exists in model
                boolean typeExists = allTypes.stream()
                    .anyMatch(t -> t.getName().equals(param.getType().getName()));
                
                if (!typeExists) {
                    invalidParams.add(
                        param.getName() + " (type '" + 
                        param.getType().getName() + "' not found)"
                    );
                }
            }
        }
        
        if (!invalidParams.isEmpty()) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.fail(
                "InstanceOperationParametersValid",
                "Instance operation '" + container.getName() + "." + 
                operation.getName() + "' has invalid parameters:\n" +
                String.join("\n  - ", invalidParams) + "\n" +
                "Ensure all parameter types are defined in the model.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

## Mapped Operation Validations

Mapped operations delegate to backend operations and must have valid bindings.

### Mapped Operation Binding Chain

```java
/**
 * Level 1: Mapped operation must have binding
 * 
 * Mapped operations delegate to backend operations via bindings.
 * The binding specifies the target operation to invoke.
 */
@Guard(method = "isMappedOperation")
@Constraint(
    name = "MappedOperationMustHaveBinding",
    message = "Mapped operation must have binding"
)
public ValidationRule mappedOperationMustHaveBinding() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        OperationBinding binding = operation.getBinding();
        
        if (binding == null) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.fail(
                "MappedOperationMustHaveBinding",
                "Mapped operation '" + container.getName() + "." + 
                operation.getName() + "' must have a binding specifying " +
                "the target backend operation. Add binding or change " +
                "operation type to INSTANCE.",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Level 2: Binding target must exist
 * 
 * Depends on: MappedOperationMustHaveBinding
 */
@Guard(method = "isMappedOperation")
@Satisfies(constraints = {"MappedOperationMustHaveBinding"})
@Constraint(
    name = "MappedOperationBindingTargetExists",
    message = "Binding target operation must exist"
)
public ValidationRule mappedOperationBindingTargetExists() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        OperationBinding binding = operation.getBinding();
        String targetName = binding.getTargetOperationName();
        
        // Query all backend operations
        List<BackendOperation> backendOps = 
            ctx.callExtension("getAllBackendOperations");
        
        boolean targetExists = backendOps.stream()
            .anyMatch(op -> op.getName().equals(targetName));
        
        if (!targetExists) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.fail(
                "MappedOperationBindingTargetExists",
                "Mapped operation '" + container.getName() + "." + 
                operation.getName() + "' binds to non-existent backend " +
                "operation '" + targetName + "'. Available operations: " +
                backendOps.stream()
                    .map(BackendOperation::getName)
                    .collect(Collectors.joining(", ")),
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Level 3: Binding signature must match
 * 
 * Depends on: MappedOperationBindingTargetExists
 */
@Guard(method = "isMappedOperation")
@Satisfies(constraints = {"MappedOperationBindingTargetExists"})
@Constraint(
    name = "MappedOperationBindingSignatureMatches",
    message = "Binding signature must match target operation"
)
public ValidationRule mappedOperationBindingSignatureMatches() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        OperationBinding binding = operation.getBinding();
        String targetName = binding.getTargetOperationName();
        
        // Get target operation (safe because previous constraint verified it exists)
        List<BackendOperation> backendOps = 
            ctx.callExtension("getAllBackendOperations");
        
        BackendOperation target = backendOps.stream()
            .filter(op -> op.getName().equals(targetName))
            .findFirst()
            .get();
        
        // Check parameter count
        if (operation.getParameters().size() != target.getParameters().size()) {
            EntityType container = (EntityType) operation.eContainer();
            return ValidationResult.fail(
                "MappedOperationBindingSignatureMatches",
                "Mapped operation '" + container.getName() + "." + 
                operation.getName() + "' has " + 
                operation.getParameters().size() + " parameters, but " +
                "target backend operation '" + targetName + "' expects " +
                target.getParameters().size() + " parameters.",
                Severity.ERROR,
                element
            );
        }
        
        // Check parameter types
        for (int i = 0; i < operation.getParameters().size(); i++) {
            Parameter param = operation.getParameters().get(i);
            Parameter targetParam = target.getParameters().get(i);
            
            if (!typesAreCompatible(param.getType(), targetParam.getType(), ctx)) {
                EntityType container = (EntityType) operation.eContainer();
                return ValidationResult.fail(
                    "MappedOperationBindingSignatureMatches",
                    "Parameter '" + param.getName() + "' in mapped operation '" +
                    container.getName() + "." + operation.getName() + 
                    "' has incompatible type. Expected: " + 
                    targetParam.getType().getName() + ", found: " +
                    param.getType().getName(),
                    Severity.ERROR,
                    element
                );
            }
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Guard: Only validate mapped operations
 */
public boolean isMappedOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    return operation.getOperationType() == OperationType.MAPPED;
}

/**
 * Helper: Check if types are compatible for binding
 */
private boolean typesAreCompatible(Type t1, Type t2, ValidationContext ctx) {
    if (t1 == null || t2 == null) {
        return false;
    }
    
    // Exact match
    if (t1.equals(t2)) {
        return true;
    }
    
    // Check if t1 is subtype of t2 (covariance)
    return ctx.callExtension("isSubtypeOf", t1, t2);
}
```

---

## Parameter Compatibility Checks

Advanced parameter validation for operation overrides.

### Override Parameter Type Compatibility

```java
/**
 * Overriding operation parameters must be compatible with base operation
 * 
 * This implements parameter contravariance checking:
 * - Parameter types can be wider (supertypes) in override
 * - Return types can be narrower (subtypes) in override
 * 
 * Depends on multiple base constraints ensuring operation structure is valid.
 */
@Guard(method = "isOverridingOperation")
@Satisfies(constraints = {
    "OperationHasName",
    "OperationBelongsToEntity",
    "OperationInheritanceValid"
})
@Constraint(
    name = "OverridingOperationParametersCompatible",
    message = "Override parameters must be compatible with base operation"
)
public ValidationRule overridingOperationParametersCompatible() {
    return (element, ctx) -> {
        Operation operation = (Operation) element;
        EntityType owner = (EntityType) operation.eContainer();
        
        // Find overridden operation in supertype
        Operation baseOperation = ctx.callExtension(
            "findBaseOperation",
            owner,
            operation.getName()
        );
        
        if (baseOperation == null) {
            // Not actually overriding (might be defining new operation)
            return ValidationResult.pass();
        }
        
        // Check parameter count matches
        if (operation.getParameters().size() != baseOperation.getParameters().size()) {
            EntityType baseOwner = (EntityType) baseOperation.eContainer();
            return ValidationResult.fail(
                "OverridingOperationParametersCompatible",
                "Override operation '" + owner.getName() + "." + 
                operation.getName() + "' has " + 
                operation.getParameters().size() + " parameters, but " +
                "base operation in '" + baseOwner.getName() + "' has " +
                baseOperation.getParameters().size() + " parameters. " +
                "Override must have same parameter count.",
                Severity.ERROR,
                element
            );
        }
        
        // Check each parameter type (contravariance)
        List<String> incompatibleParams = new ArrayList<>();
        
        for (int i = 0; i < operation.getParameters().size(); i++) {
            Parameter overrideParam = operation.getParameters().get(i);
            Parameter baseParam = baseOperation.getParameters().get(i);
            
            // Override parameter type must be same or supertype of base
            boolean compatible = ctx.callExtension(
                "isContravariantWith",
                overrideParam.getType(),
                baseParam.getType()
            );
            
            if (!compatible) {
                incompatibleParams.add(
                    "Parameter " + (i + 1) + " '" + overrideParam.getName() + 
                    "': expected " + baseParam.getType().getName() + 
                    " or supertype, found " + overrideParam.getType().getName()
                );
            }
        }
        
        if (!incompatibleParams.isEmpty()) {
            EntityType baseOwner = (EntityType) baseOperation.eContainer();
            return ValidationResult.fail(
                "OverridingOperationParametersCompatible",
                "Override operation '" + owner.getName() + "." + 
                operation.getName() + "' has incompatible parameters " +
                "compared to base operation in '" + baseOwner.getName() + "':\n" +
                String.join("\n  - ", incompatibleParams) + "\n" +
                "Parameter types must be contravariant (same or wider types).",
                Severity.ERROR,
                element
            );
        }
        
        return ValidationResult.pass();
    };
}

/**
 * Guard: Only check operations that override base operations
 */
public boolean isOverridingOperation(EObject element, ValidationContext ctx) {
    Operation operation = (Operation) element;
    EntityType owner = (EntityType) operation.eContainer();
    
    // Check if owner has supertype
    if (owner == null || owner.getSuperType() == null) {
        return false;
    }
    
    // Check if supertype has operation with same name
    EntityType superType = owner.getSuperType();
    return superType.getOperations().stream()
        .anyMatch(op -> op.getName().equals(operation.getName()));
}
```

---

## Override Validation with Satisfies Chains

Complete example showing multi-level satisfies dependencies.

### Five-Level Validation Chain

```java
/**
 * Complete override validation chain with 5 dependency levels
 */
@ValidationContext(Operation.class)
public class OperationOverrideValidations {
    
    // Level 1: Basic structure
    @Constraint(name = "OperationHasName", message = "Operation must have name")
    public ValidationRule operationHasName() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            return op.getName() != null && !op.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Operation name required");
        };
    }
    
    @Constraint(name = "OperationBelongsToEntity", message = "Operation must belong to entity")
    public ValidationRule operationBelongsToEntity() {
        return (element, ctx) -> {
            return element.eContainer() instanceof EntityType
                ? ValidationResult.pass()
                : ValidationResult.fail("Operation must be owned by EntityType");
        };
    }
    
    // Level 2: Inheritance structure
    @Satisfies(constraints = {"OperationHasName", "OperationBelongsToEntity"})
    @Constraint(name = "OperationInheritanceValid", message = "Operation inheritance valid")
    public ValidationRule operationInheritanceValid() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            EntityType owner = (EntityType) op.eContainer();
            
            if (owner.getSuperType() == null) {
                return ValidationResult.pass();
            }
            
            // Check for inheritance cycles
            if (hasCyclicInheritance(owner, ctx)) {
                return ValidationResult.fail(
                    "OperationInheritanceValid",
                    "Owner entity '" + owner.getName() + 
                    "' has cyclic inheritance, cannot validate operation overrides",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // Level 3: Override identification
    @Guard(method = "isOverridingOperation")
    @Satisfies(constraints = {"OperationInheritanceValid"})
    @Constraint(name = "OverrideIsValid", message = "Override is valid")
    public ValidationRule overrideIsValid() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            EntityType owner = (EntityType) op.eContainer();
            
            Operation baseOp = findBaseOperation(owner, op.getName(), ctx);
            
            if (baseOp == null) {
                // Shouldn't happen if guard works correctly
                return ValidationResult.pass();
            }
            
            // Check that we're not overriding a final operation
            if (baseOp.isFinal()) {
                EntityType baseOwner = (EntityType) baseOp.eContainer();
                return ValidationResult.fail(
                    "OverrideIsValid",
                    "Cannot override final operation '" + baseOwner.getName() + 
                    "." + baseOp.getName() + "'",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // Level 4: Signature compatibility
    @Guard(method = "isOverridingOperation")
    @Satisfies(constraints = {"OverrideIsValid"})
    @Constraint(name = "OverrideSignatureCompatible", message = "Override signature compatible")
    public ValidationRule overrideSignatureCompatible() {
        return (element, ctx) -> {
            // Parameter compatibility check (shown in previous section)
            // Return type covariance check
            // Access modifier compatibility
            // ... (implementation from previous examples)
            return ValidationResult.pass();
        };
    }
    
    // Level 5: Semantic correctness
    @Guard(method = "isOverridingOperation")
    @Satisfies(constraints = {"OverrideSignatureCompatible"})
    @Constraint(
        name = "OverrideSemanticsValid",
        message = "Override semantics must be valid"
    )
    public ValidationRule overrideSemanticsValid() {
        return (element, ctx) -> {
            Operation op = (Operation) element;
            EntityType owner = (EntityType) op.eContainer();
            Operation baseOp = findBaseOperation(owner, op.getName(), ctx);
            
            // Check: Cannot make abstract operation concrete if entity is abstract
            if (baseOp.getOperationType() == OperationType.ABSTRACT 
                && op.getOperationType() != OperationType.ABSTRACT
                && owner.isAbstract()) {
                
                EntityType baseOwner = (EntityType) baseOp.eContainer();
                return ValidationResult.fail(
                    "OverrideSemanticsValid",
                    "Operation '" + owner.getName() + "." + op.getName() + 
                    "' cannot provide implementation for abstract operation '" +
                    baseOwner.getName() + "." + baseOp.getName() + 
                    "' because owner entity '" + owner.getName() + 
                    "' is still abstract. Either make '" + owner.getName() + 
                    "' concrete or keep operation abstract.",
                    Severity.ERROR,
                    element
                );
            }
            
            // Check: Cannot change MAPPED to INSTANCE without updating binding
            if (baseOp.getOperationType() == OperationType.MAPPED
                && op.getOperationType() == OperationType.INSTANCE) {
                
                return ValidationResult.warn(
                    "OverrideSemanticsValid",
                    "Operation '" + owner.getName() + "." + op.getName() + 
                    "' changes from MAPPED to INSTANCE. Ensure this is intentional " +
                    "and that the instance implementation is complete.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // Helper methods
    private boolean hasCyclicInheritance(EntityType entity, ValidationContext ctx) {
        return ctx.callExtension("detectCyclicInheritance", entity);
    }
    
    private Operation findBaseOperation(EntityType owner, String name, ValidationContext ctx) {
        return ctx.callExtension("findBaseOperation", owner, name);
    }
    
    public boolean isOverridingOperation(EObject element, ValidationContext ctx) {
        Operation op = (Operation) element;
        EntityType owner = (EntityType) op.eContainer();
        
        if (owner == null || owner.getSuperType() == null) {
            return false;
        }
        
        return findBaseOperation(owner, op.getName(), ctx) != null;
    }
}
```

**Dependency Graph**:
```
Level 1: OperationHasName, OperationBelongsToEntity
           ↓
Level 2: OperationInheritanceValid
           ↓
Level 3: OverrideIsValid
           ↓
Level 4: OverrideSignatureCompatible
           ↓
Level 5: OverrideSemanticsValid
```

---

## Complete Validation Class

Full validation class with all operation types.

```java
package hu.blackbelt.judo.meta.esm.validation;

import hu.blackbelt.judo.meta.esm.runtime.*;
import hu.blackbelt.judo.zeta.validation.annotation.*;
import hu.blackbelt.judo.zeta.validation.core.*;
import org.eclipse.emf.ecore.EObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Complete operation validations for ESM metamodel.
 * 
 * Validates three operation types:
 * - ABSTRACT: Declared in abstract entities, implemented in concrete subclasses
 * - INSTANCE: Regular operations with implementations
 * - MAPPED: Operations that delegate to backend operations via bindings
 * 
 * Demonstrates:
 * - Multi-level @Satisfies dependency chains
 * - Guard methods for operation type filtering
 * - Extension method calls for complex queries
 * - Rich error messages with context
 * - Cross-object validation
 */
@ValidationContext(Operation.class)
public class OperationValidations {
    
    // ========================================================================
    // ABSTRACT OPERATION VALIDATIONS
    // ========================================================================
    
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
                    "Container of abstract operation '" + operation.getName() + 
                    "' must be an EntityType, but found: " + 
                    container.eClass().getName(),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Guard(method = "isAbstractOperation")
    @Satisfies(constraints = {"AbstractOperationBelongsToEntityType"})
    @Constraint(
        name = "AbstractOperationBelongsToAbstractEntityType",
        message = "Abstract operation can only belong to abstract entity type"
    )
    public ValidationRule abstractOperationBelongsToAbstractEntityType() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            EntityType container = (EntityType) operation.eContainer();
            
            if (!container.isAbstract()) {
                return ValidationResult.fail(
                    "AbstractOperationBelongsToAbstractEntityType",
                    "Abstract operation '" + container.getName() + "." + 
                    operation.getName() + "' cannot belong to concrete entity type. " +
                    "Either mark entity '" + container.getName() + 
                    "' as abstract or change operation type to INSTANCE.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Guard(method = "isAbstractOperation")
    @Satisfies(constraints = {"AbstractOperationBelongsToAbstractEntityType"})
    @Constraint(
        name = "AbstractOperationIsValid",
        message = "Abstract operation cannot override non-abstract base operations"
    )
    public ValidationRule abstractOperationIsValid() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            EntityType container = (EntityType) operation.eContainer();
            
            List<Operation> inheritedNonAbstract = 
                ctx.callExtension(
                    "getInheritedNonAbstractOperationsByName",
                    container,
                    operation.getName()
                );
            
            if (!inheritedNonAbstract.isEmpty()) {
                StringBuilder chain = new StringBuilder();
                for (Operation inherited : inheritedNonAbstract) {
                    EntityType inheritedOwner = (EntityType) inherited.eContainer();
                    chain.append("\n  - ")
                         .append(inheritedOwner.getName())
                         .append(".")
                         .append(inherited.getName())
                         .append(" (")
                         .append(inherited.getOperationType())
                         .append(")");
                }
                
                return ValidationResult.fail(
                    "AbstractOperationIsValid",
                    "Operation '" + container.getName() + "." + operation.getName() + 
                    "' cannot be ABSTRACT because it overrides non-abstract operations:" +
                    chain.toString() + "\n" +
                    "Change this operation to INSTANCE or make the base operations ABSTRACT.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
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
                    "Abstract operation '" + container.getName() + "." + 
                    operation.getName() + "' has implementation code: \"" + 
                    (body.length() > 50 ? body.substring(0, 47) + "..." : body) + "\". " +
                    "Consider removing the implementation or changing operation type to INSTANCE.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // INSTANCE OPERATION VALIDATIONS
    // ========================================================================
    
    @Guard(method = "isInstanceOperation")
    @Constraint(
        name = "InstanceOperationMustHaveImplementation",
        message = "Instance operation must have implementation"
    )
    public ValidationRule instanceOperationMustHaveImplementation() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            String body = operation.getBody();
            
            if (body == null || body.trim().isEmpty()) {
                EntityType container = (EntityType) operation.eContainer();
                return ValidationResult.fail(
                    "InstanceOperationMustHaveImplementation",
                    "Instance operation '" + container.getName() + "." + 
                    operation.getName() + "' must have implementation. " +
                    "Add operation body or change type to ABSTRACT if this is " +
                    "meant to be implemented by subclasses.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // MAPPED OPERATION VALIDATIONS
    // ========================================================================
    
    @Guard(method = "isMappedOperation")
    @Constraint(
        name = "MappedOperationMustHaveBinding",
        message = "Mapped operation must have binding"
    )
    public ValidationRule mappedOperationMustHaveBinding() {
        return (element, ctx) -> {
            Operation operation = (Operation) element;
            OperationBinding binding = operation.getBinding();
            
            if (binding == null) {
                EntityType container = (EntityType) operation.eContainer();
                return ValidationResult.fail(
                    "MappedOperationMustHaveBinding",
                    "Mapped operation '" + container.getName() + "." + 
                    operation.getName() + "' must have a binding specifying " +
                    "the target backend operation. Add binding or change " +
                    "operation type to INSTANCE.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ========================================================================
    // GUARD METHODS
    // ========================================================================
    
    /**
     * Guard: Only validate abstract operations with containers
     */
    public boolean isAbstractOperation(EObject element, ValidationContext ctx) {
        Operation operation = (Operation) element;
        
        if (operation.eContainer() == null) {
            return false;
        }
        
        return operation.getOperationType() == OperationType.ABSTRACT;
    }
    
    /**
     * Guard: Only validate instance operations
     */
    public boolean isInstanceOperation(EObject element, ValidationContext ctx) {
        Operation operation = (Operation) element;
        return operation.getOperationType() == OperationType.INSTANCE;
    }
    
    /**
     * Guard: Only validate mapped operations
     */
    public boolean isMappedOperation(EObject element, ValidationContext ctx) {
        Operation operation = (Operation) element;
        return operation.getOperationType() == OperationType.MAPPED;
    }
}
```

---

## Extension Methods for Complex Queries

Extension methods encapsulate complex model queries.

### Extension Method Class

```java
package hu.blackbelt.judo.meta.esm.validation.extensions;

import hu.blackbelt.judo.meta.esm.runtime.*;
import hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extension methods for ESM operation validations.
 * 
 * These methods encapsulate complex model queries used by validation rules.
 * They can be called via ctx.callExtension("methodName", args...)
 */
public class OperationExtensions {
    
    /**
     * Get inherited non-abstract operations by name.
     * 
     * Walks up the inheritance hierarchy and collects all non-abstract
     * operations with the given name.
     * 
     * Used by: AbstractOperationIsValid constraint
     * 
     * EVL equivalent:
     *   operation EntityType getInheritedNonAbstractOperationsByName(name : String) {
     *       var result = new Sequence;
     *       var current = self.superType;
     *       while (current.isDefined()) {
     *           result.addAll(current.operations
     *               .select(op | op.name == name and op.operationType <> OperationType#ABSTRACT));
     *           current = current.superType;
     *       }
     *       return result;
     *   }
     */
    @ExtensionMethod(elementType = EntityType.class)
    public List<Operation> getInheritedNonAbstractOperationsByName(
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
    
    /**
     * Find base operation being overridden.
     * 
     * Returns the first operation with the given name found in the
     * inheritance hierarchy, or null if not found.
     */
    @ExtensionMethod(elementType = EntityType.class)
    public Operation findBaseOperation(EntityType entityType, String operationName) {
        EntityType current = entityType.getSuperType();
        
        while (current != null) {
            Operation found = current.getOperations().stream()
                .filter(op -> op.getName().equals(operationName))
                .findFirst()
                .orElse(null);
            
            if (found != null) {
                return found;
            }
            
            current = current.getSuperType();
        }
        
        return null;
    }
    
    /**
     * Detect cyclic inheritance.
     * 
     * Returns true if the entity type is part of a cyclic inheritance chain.
     */
    @ExtensionMethod(elementType = EntityType.class)
    public boolean detectCyclicInheritance(EntityType entityType) {
        Set<EntityType> visited = new HashSet<>();
        EntityType current = entityType;
        
        while (current != null) {
            if (visited.contains(current)) {
                return true; // Cycle detected
            }
            
            visited.add(current);
            current = current.getSuperType();
        }
        
        return false;
    }
    
    /**
     * Check if type1 is subtype of type2.
     * 
     * Walks up the inheritance hierarchy of type1 checking if type2 is found.
     */
    @ExtensionMethod(elementType = Type.class)
    public boolean isSubtypeOf(Type type1, Type type2) {
        if (type1 == null || type2 == null) {
            return false;
        }
        
        if (type1.equals(type2)) {
            return true;
        }
        
        if (!(type1 instanceof EntityType)) {
            return false;
        }
        
        EntityType current = ((EntityType) type1).getSuperType();
        while (current != null) {
            if (current.equals(type2)) {
                return true;
            }
            current = current.getSuperType();
        }
        
        return false;
    }
    
    /**
     * Check contravariance for parameter types.
     * 
     * Returns true if overrideType is contravariant with baseType,
     * meaning overrideType is same or supertype of baseType.
     */
    @ExtensionMethod(elementType = Type.class)
    public boolean isContravariantWith(Type overrideType, Type baseType) {
        if (overrideType == null || baseType == null) {
            return false;
        }
        
        // Exact match is always valid
        if (overrideType.equals(baseType)) {
            return true;
        }
        
        // Check if baseType is subtype of overrideType (contravariance)
        return isSubtypeOf(baseType, overrideType);
    }
    
    /**
     * Get all backend operations available for mapping.
     * 
     * This would typically query the backend model or configuration.
     * Simplified example shown here.
     */
    @ExtensionMethod
    public List<BackendOperation> getAllBackendOperations() {
        // In real implementation, this would query the backend model
        // For now, return empty list
        return new ArrayList<>();
    }
    
    /**
     * Get all operations in inheritance hierarchy.
     * 
     * Returns all operations from this entity and all supertypes.
     */
    @ExtensionMethod(elementType = EntityType.class)
    public List<Operation> getAllInheritedOperations(EntityType entityType) {
        List<Operation> result = new ArrayList<>(entityType.getOperations());
        EntityType current = entityType.getSuperType();
        
        while (current != null) {
            result.addAll(current.getOperations());
            current = current.getSuperType();
        }
        
        return result;
    }
}
```

### Using Extension Methods

```java
// Register extension methods
ValidationRegistry registry = new ValidationRegistry();
registry.register(OperationValidations.class);
registry.registerExtensions(OperationExtensions.class);

// In validation rules, call via context
@Constraint(name = "SomeConstraint", message = "...")
public ValidationRule someConstraint() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Call extension method
        List<Operation> inherited = ctx.callExtension(
            "getInheritedNonAbstractOperationsByName",
            entity,
            "operationName"
        );
        
        // Use results in validation logic
        if (!inherited.isEmpty()) {
            // ... validation logic
        }
        
        return ValidationResult.pass();
    };
}
```

---

## Summary

This document demonstrated complex, production-grade validation patterns:

**Key Patterns**:
- **Multi-level @Satisfies chains** (up to 5 levels deep)
- **Type-specific guards** filtering by OperationType enum
- **Extension method calls** for complex model queries
- **Rich error messages** with context and actionable suggestions
- **Cross-object validation** checking inheritance hierarchies
- **Mixed severities** using both @Constraint and @Critique

**Design Principles**:
1. **Fail fast with clear messages** - Each level checks one concern
2. **Prevent cascading errors** - Use @Satisfies to skip dependent rules
3. **Encapsulate complex logic** - Extension methods for reusable queries
4. **Provide context** - Include owner names, operation types, inheritance chains
5. **Suggest solutions** - Error messages explain how to fix problems

These patterns scale to enterprise metamodels with hundreds of validation rules while maintaining clarity and performance.

---

**Related Documentation**:
- [Writing Validation Rules](../user-guide/validation-rules.md)
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md)
- [Extension Methods](../user-guide/extension-methods.md)
- [EVL Migration Guide](../evl-comparison/migration-guide.md)

---

**Previous**: [Examples Overview](../index.md#examples) | **Next**: [Entity Validations](entity-validations.md)
