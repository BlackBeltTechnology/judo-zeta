# Cross-Reference Validations

**Navigation**: [Documentation Hub](../../index.md) > [Examples](../index.md#real-world-examples) > Cross-Reference Validations

This guide demonstrates advanced validation patterns for checking relationships and consistency across multiple model elements. Learn how to validate complex cross-references, bidirectional relationships, cardinality constraints, and multi-element consistency rules.

## Overview

Cross-reference validation goes beyond single-element checks to ensure the integrity of relationships between different model elements. This is essential for:

- **Transfer Object ↔ Entity Type bindings** - Ensuring DTOs map to valid entities
- **Reference cardinality validation** - Enforcing 1..1, 0..*, 1..* constraints
- **Bidirectional consistency** - Validating opposite references match
- **Multi-element constraints** - Checking consistency across element collections
- **Reference integrity** - Ensuring targets exist and are valid

### What You'll Learn

- Using `ctx.getAllInstances()` to query across the model
- Validating relationships between different element types
- Checking bidirectional reference consistency
- Implementing cardinality constraints
- Building cross-model validation rules
- Performance optimization for cross-references

## Transfer Object to Entity Type Binding Validation

Transfer Objects (DTOs) often bind to Entity Types in model-driven architectures. These validations ensure the binding is valid and consistent.

### Basic Binding Existence Check

Validate that a transfer object has a valid entity type binding:

```java
@ValidationContext(TransferObjectType.class)
public class TransferObjectBindingValidations {
    
    @Constraint(
        name = "TransferObjectMustHaveEntityTypeBinding",
        message = "Transfer object must have an entity type binding"
    )
    public ValidationRule transferObjectMustHaveEntityTypeBinding() {
        return (element, ctx) -> {
            TransferObjectType transferObject = (TransferObjectType) element;
            EntityType entityType = transferObject.getEntityType();
            
            if (entityType == null) {
                return ValidationResult.fail(
                    "TransferObjectMustHaveEntityTypeBinding",
                    "Transfer object '" + transferObject.getName() + 
                    "' must be bound to an entity type",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Validate Bound Entity Exists in Model

Ensure the bound entity type actually exists in the model:

```java
@ValidationContext(TransferObjectType.class)
public class TransferObjectBindingValidations {
    
    @Constraint(
        name = "BoundEntityTypeMustExist",
        message = "Bound entity type must exist in the model"
    )
    @Satisfies(constraints = {"TransferObjectMustHaveEntityTypeBinding"})
    public ValidationRule boundEntityTypeMustExist() {
        return (element, ctx) -> {
            TransferObjectType transferObject = (TransferObjectType) element;
            EntityType boundEntity = transferObject.getEntityType();
            
            // Query all entity types in the model
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            boolean entityExists = allEntities.stream()
                .anyMatch(e -> e.equals(boundEntity));
            
            if (!entityExists) {
                return ValidationResult.fail(
                    "BoundEntityTypeMustExist",
                    "Transfer object '" + transferObject.getName() + 
                    "' references entity type '" + boundEntity.getName() + 
                    "' which does not exist in the model",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Validate Bound Entity is Valid

Check that the bound entity type satisfies its own constraints:

```java
@ValidationContext(TransferObjectType.class)
public class TransferObjectBindingValidations {
    
    @Constraint(
        name = "BoundEntityTypeMustBeValid",
        message = "Bound entity type must be valid"
    )
    @Satisfies(constraints = {"BoundEntityTypeMustExist"})
    public ValidationRule boundEntityTypeMustBeValid() {
        return (element, ctx) -> {
            TransferObjectType transferObject = (TransferObjectType) element;
            EntityType boundEntity = transferObject.getEntityType();
            
            // Check if bound entity satisfies fundamental constraints
            if (!ctx.satisfies(boundEntity, "EntityMustHaveName")) {
                return ValidationResult.fail(
                    "BoundEntityTypeMustBeValid",
                    "Transfer object '" + transferObject.getName() + 
                    "' is bound to an entity without a name",
                    Severity.ERROR,
                    element
                );
            }
            
            if (!ctx.satisfies(boundEntity, "EntityMustHaveValidStructure")) {
                return ValidationResult.fail(
                    "BoundEntityTypeMustBeValid",
                    "Transfer object '" + transferObject.getName() + 
                    "' is bound to invalid entity '" + boundEntity.getName() + "'",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Attribute Mapping Consistency

Validate that transfer object attributes correctly map to entity attributes:

```java
@ValidationContext(TransferObjectType.class)
public class TransferObjectBindingValidations {
    
    @Constraint(
        name = "TransferAttributesMustMapToEntityAttributes",
        message = "Transfer attributes must map to existing entity attributes"
    )
    @Satisfies(constraints = {"BoundEntityTypeMustBeValid"})
    public ValidationRule transferAttributesMustMapToEntityAttributes() {
        return (element, ctx) -> {
            TransferObjectType transferObject = (TransferObjectType) element;
            EntityType entityType = transferObject.getEntityType();
            
            // Get all entity attributes (including inherited)
            List<Attribute> entityAttributes = ctx.call(
                entityType, 
                "getAllAttributes"
            );
            
            Set<String> entityAttrNames = entityAttributes.stream()
                .map(Attribute::getName)
                .collect(Collectors.toSet());
            
            // Check each transfer attribute
            List<String> invalidMappings = new ArrayList<>();
            for (TransferAttribute transferAttr : transferObject.getAttributes()) {
                String mappedName = transferAttr.getEntityAttributeName();
                
                if (mappedName != null && !entityAttrNames.contains(mappedName)) {
                    invalidMappings.add(transferAttr.getName() + " -> " + mappedName);
                }
            }
            
            if (!invalidMappings.isEmpty()) {
                return ValidationResult.fail(
                    "TransferAttributesMustMapToEntityAttributes",
                    "Transfer object '" + transferObject.getName() + 
                    "' has attributes mapped to non-existent entity attributes: " +
                    String.join(", ", invalidMappings),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Type Compatibility Check

Ensure mapped attributes have compatible types:

```java
@ValidationContext(TransferObjectType.class)
public class TransferObjectBindingValidations {
    
    @Constraint(
        name = "MappedAttributeTypesMustBeCompatible",
        message = "Mapped attribute types must be compatible"
    )
    @Satisfies(constraints = {"TransferAttributesMustMapToEntityAttributes"})
    public ValidationRule mappedAttributeTypesMustBeCompatible() {
        return (element, ctx) -> {
            TransferObjectType transferObject = (TransferObjectType) element;
            EntityType entityType = transferObject.getEntityType();
            
            List<Attribute> entityAttributes = ctx.call(
                entityType, 
                "getAllAttributes"
            );
            
            // Build attribute map for quick lookup
            Map<String, Attribute> entityAttrMap = entityAttributes.stream()
                .collect(Collectors.toMap(Attribute::getName, a -> a));
            
            // Check type compatibility
            List<String> incompatibleTypes = new ArrayList<>();
            for (TransferAttribute transferAttr : transferObject.getAttributes()) {
                String mappedName = transferAttr.getEntityAttributeName();
                
                if (mappedName != null) {
                    Attribute entityAttr = entityAttrMap.get(mappedName);
                    
                    if (entityAttr != null) {
                        if (!areTypesCompatible(transferAttr.getType(), entityAttr.getType())) {
                            incompatibleTypes.add(
                                transferAttr.getName() + " (" + 
                                transferAttr.getType() + ") -> " +
                                mappedName + " (" + entityAttr.getType() + ")"
                            );
                        }
                    }
                }
            }
            
            if (!incompatibleTypes.isEmpty()) {
                return ValidationResult.fail(
                    "MappedAttributeTypesMustBeCompatible",
                    "Transfer object '" + transferObject.getName() + 
                    "' has incompatible type mappings: " +
                    String.join(", ", incompatibleTypes),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    private boolean areTypesCompatible(Type transferType, Type entityType) {
        // Implement type compatibility logic
        // Example: String -> String (exact match)
        //          Integer -> Long (numeric widening)
        //          Date -> Timestamp (temporal conversion)
        
        if (transferType.equals(entityType)) {
            return true;
        }
        
        // Check for compatible numeric types
        if (isNumericType(transferType) && isNumericType(entityType)) {
            return canWiden(transferType, entityType);
        }
        
        // Check for temporal type compatibility
        if (isTemporalType(transferType) && isTemporalType(entityType)) {
            return true;
        }
        
        return false;
    }
    
    private boolean isNumericType(Type type) {
        return type.getName().matches("Integer|Long|Decimal|Double|Float");
    }
    
    private boolean isTemporalType(Type type) {
        return type.getName().matches("Date|Time|Timestamp");
    }
    
    private boolean canWiden(Type from, Type to) {
        // Integer -> Long -> Decimal
        List<String> hierarchy = Arrays.asList("Integer", "Long", "Decimal");
        int fromIndex = hierarchy.indexOf(from.getName());
        int toIndex = hierarchy.indexOf(to.getName());
        return fromIndex >= 0 && toIndex >= 0 && fromIndex <= toIndex;
    }
}
```

## Reference Cardinality Validation

Validate that references respect their cardinality constraints (1..1, 0..*, 1..*, 0..1).

### Required Single Reference (1..1)

```java
@ValidationContext(EntityType.class)
public class ReferenceCardinalityValidations {
    
    @Constraint(
        name = "RequiredReferenceMustBeSet",
        message = "Required reference must be set"
    )
    public ValidationRule requiredReferenceMustBeSet() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check each reference defined on the entity
            List<String> missingReferences = new ArrayList<>();
            
            for (Reference ref : entity.getReferences()) {
                if (ref.isRequired() && !ref.isMany()) {
                    // This is a 1..1 reference - must have exactly one target
                    if (ref.getTarget() == null) {
                        missingReferences.add(ref.getName());
                    }
                }
            }
            
            if (!missingReferences.isEmpty()) {
                return ValidationResult.fail(
                    "RequiredReferenceMustBeSet",
                    "Entity '" + entity.getName() + "' has required references " +
                    "that are not set: " + String.join(", ", missingReferences),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Collection Cardinality (1..*)

```java
@ValidationContext(EntityType.class)
public class ReferenceCardinalityValidations {
    
    @Constraint(
        name = "RequiredCollectionMustNotBeEmpty",
        message = "Required collection reference must have at least one element"
    )
    public ValidationRule requiredCollectionMustNotBeEmpty() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            List<String> emptyCollections = new ArrayList<>();
            
            for (Reference ref : entity.getReferences()) {
                if (ref.isRequired() && ref.isMany()) {
                    // This is a 1..* reference - must have at least one element
                    Collection<?> targets = ref.getTargets();
                    if (targets == null || targets.isEmpty()) {
                        emptyCollections.add(ref.getName());
                    }
                }
            }
            
            if (!emptyCollections.isEmpty()) {
                return ValidationResult.fail(
                    "RequiredCollectionMustNotBeEmpty",
                    "Entity '" + entity.getName() + "' has required collection " +
                    "references that are empty: " + String.join(", ", emptyCollections),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Upper Bound Cardinality

```java
@ValidationContext(EntityType.class)
public class ReferenceCardinalityValidations {
    
    @Constraint(
        name = "CollectionMustRespectUpperBound",
        message = "Collection reference must respect upper bound cardinality"
    )
    public ValidationRule collectionMustRespectUpperBound() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            List<String> violations = new ArrayList<>();
            
            for (Reference ref : entity.getReferences()) {
                if (ref.isMany() && ref.getUpperBound() > 0) {
                    // Has explicit upper bound
                    Collection<?> targets = ref.getTargets();
                    
                    if (targets != null && targets.size() > ref.getUpperBound()) {
                        violations.add(
                            ref.getName() + " (has " + targets.size() + 
                            ", max " + ref.getUpperBound() + ")"
                        );
                    }
                }
            }
            
            if (!violations.isEmpty()) {
                return ValidationResult.fail(
                    "CollectionMustRespectUpperBound",
                    "Entity '" + entity.getName() + "' has references exceeding " +
                    "upper bound cardinality: " + String.join(", ", violations),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Optional Single Reference (0..1)

```java
@ValidationContext(EntityType.class)
public class ReferenceCardinalityValidations {
    
    @Critique(
        name = "OptionalReferenceShouldBeDocumented",
        message = "Optional references should be documented"
    )
    public ValidationRule optionalReferenceShouldBeDocumented() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            List<String> undocumented = new ArrayList<>();
            
            for (Reference ref : entity.getReferences()) {
                if (!ref.isRequired() && !ref.isMany()) {
                    // This is a 0..1 reference
                    if (ref.getDescription() == null || ref.getDescription().isEmpty()) {
                        undocumented.add(ref.getName());
                    }
                }
            }
            
            if (!undocumented.isEmpty()) {
                return ValidationResult.warn(
                    "OptionalReferenceShouldBeDocumented",
                    "Entity '" + entity.getName() + "' has optional references " +
                    "without documentation: " + String.join(", ", undocumented) + 
                    ". Consider documenting when the reference should be set.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Bidirectional Reference Consistency

When references have opposites (bidirectional relationships), both sides must be consistent.

### Basic Opposite Consistency

```java
@ValidationContext(Reference.class)
public class BidirectionalReferenceValidations {
    
    @Constraint(
        name = "OppositeReferenceMustExist",
        message = "Opposite reference must exist"
    )
    @Guard(method = "hasOpposite")
    public ValidationRule oppositeReferenceMustExist() {
        return (element, ctx) -> {
            Reference reference = (Reference) element;
            Reference opposite = reference.getOpposite();
            
            if (opposite == null) {
                return ValidationResult.fail(
                    "OppositeReferenceMustExist",
                    "Reference '" + reference.getName() + "' declares an opposite " +
                    "but the opposite reference is null",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    public boolean hasOpposite(EObject element, ValidationContext ctx) {
        Reference reference = (Reference) element;
        return reference.isSetOpposite();
    }
}
```

### Opposite Symmetry

```java
@ValidationContext(Reference.class)
public class BidirectionalReferenceValidations {
    
    @Constraint(
        name = "OppositeReferenceMustPointBack",
        message = "Opposite reference must point back to this reference"
    )
    @Satisfies(constraints = {"OppositeReferenceMustExist"})
    @Guard(method = "hasOpposite")
    public ValidationRule oppositeReferenceMustPointBack() {
        return (element, ctx) -> {
            Reference reference = (Reference) element;
            Reference opposite = reference.getOpposite();
            
            // Check that opposite's opposite is this reference
            if (opposite.getOpposite() != reference) {
                return ValidationResult.fail(
                    "OppositeReferenceMustPointBack",
                    "Reference '" + reference.getName() + "' has opposite '" + 
                    opposite.getName() + "', but the opposite does not point back. " +
                    "Bidirectional references must be symmetric.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Opposite Type Consistency

```java
@ValidationContext(Reference.class)
public class BidirectionalReferenceValidations {
    
    @Constraint(
        name = "OppositeReferenceTypesMustMatch",
        message = "Opposite reference types must be consistent"
    )
    @Satisfies(constraints = {"OppositeReferenceMustPointBack"})
    @Guard(method = "hasOpposite")
    public ValidationRule oppositeReferenceTypesMustMatch() {
        return (element, ctx) -> {
            Reference reference = (Reference) element;
            Reference opposite = reference.getOpposite();
            
            // This reference's target type should be the opposite's owner type
            EntityType thisTarget = reference.getTargetType();
            EntityType oppositeOwner = (EntityType) opposite.eContainer();
            
            if (!thisTarget.equals(oppositeOwner)) {
                return ValidationResult.fail(
                    "OppositeReferenceTypesMustMatch",
                    "Reference '" + reference.getName() + "' targets '" + 
                    thisTarget.getName() + "' but opposite '" + opposite.getName() + 
                    "' belongs to '" + oppositeOwner.getName() + "'. Types must match.",
                    Severity.ERROR,
                    element
                );
            }
            
            // This reference's owner type should be the opposite's target type
            EntityType thisOwner = (EntityType) reference.eContainer();
            EntityType oppositeTarget = opposite.getTargetType();
            
            if (!thisOwner.equals(oppositeTarget)) {
                return ValidationResult.fail(
                    "OppositeReferenceTypesMustMatch",
                    "Reference '" + reference.getName() + "' belongs to '" + 
                    thisOwner.getName() + "' but opposite '" + opposite.getName() + 
                    "' targets '" + oppositeTarget.getName() + "'. Types must match.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Opposite Cardinality Consistency

```java
@ValidationContext(Reference.class)
public class BidirectionalReferenceValidations {
    
    @Critique(
        name = "OppositeCardinalityShouldBeConsistent",
        message = "Opposite cardinalities should be consistent"
    )
    @Satisfies(constraints = {"OppositeReferenceTypesMustMatch"})
    @Guard(method = "hasOpposite")
    public ValidationRule oppositeCardinalityShouldBeConsistent() {
        return (element, ctx) -> {
            Reference reference = (Reference) element;
            Reference opposite = reference.getOpposite();
            
            // Check for potentially inconsistent cardinalities
            List<String> warnings = new ArrayList<>();
            
            // Both are 1..1 - this is fine
            if (reference.isRequired() && !reference.isMany() &&
                opposite.isRequired() && !opposite.isMany()) {
                // OK: 1..1 <-> 1..1
            }
            // One-to-many with required many side
            else if (reference.isMany() && opposite.isRequired() && !opposite.isMany()) {
                // OK: Entity has 1..1 reference to Collection of items
                // Each item must belong to exactly one Entity
            }
            // Many-to-many
            else if (reference.isMany() && opposite.isMany()) {
                if (reference.isRequired() != opposite.isRequired()) {
                    warnings.add(
                        "Many-to-many relationship has asymmetric required flags: " +
                        reference.getName() + " (required=" + reference.isRequired() + ") <-> " +
                        opposite.getName() + " (required=" + opposite.isRequired() + ")"
                    );
                }
            }
            // Potentially problematic: 1..1 with optional 0..1
            else if (reference.isRequired() && !reference.isMany() &&
                     !opposite.isRequired() && !opposite.isMany()) {
                warnings.add(
                    "Asymmetric cardinality: " + reference.getName() + " is required (1..1) " +
                    "but opposite " + opposite.getName() + " is optional (0..1). " +
                    "Consider if this is intentional."
                );
            }
            
            if (!warnings.isEmpty()) {
                return ValidationResult.warn(
                    "OppositeCardinalityShouldBeConsistent",
                    "Reference '" + reference.getName() + "' has potentially " +
                    "inconsistent cardinality with opposite: " + 
                    String.join("; ", warnings),
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Multi-Element Consistency Checks

Validate consistency across collections of related elements.

### Unique Names Within Collection

```java
@ValidationContext(Package.class)
public class PackageConsistencyValidations {
    
    @Constraint(
        name = "EntityNamesMusentBeUniqueInPackage",
        message = "Entity names must be unique within package"
    )
    public ValidationRule entityNamesMustBeUniqueInPackage() {
        return (element, ctx) -> {
            Package pkg = (Package) element;
            
            // Build frequency map of entity names
            Map<String, Long> nameCounts = pkg.getEntities().stream()
                .collect(Collectors.groupingBy(
                    EntityType::getName,
                    Collectors.counting()
                ));
            
            // Find duplicates
            List<String> duplicates = nameCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(e -> e.getKey() + " (x" + e.getValue() + ")")
                .collect(Collectors.toList());
            
            if (!duplicates.isEmpty()) {
                return ValidationResult.fail(
                    "EntityNamesMustBeUniqueInPackage",
                    "Package '" + pkg.getName() + "' has duplicate entity names: " +
                    String.join(", ", duplicates),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Global Uniqueness Across Model

```java
@ValidationContext(EntityType.class)
public class EntityGlobalConsistencyValidations {
    
    @Constraint(
        name = "EntityFullyQualifiedNameMustBeUnique",
        message = "Entity fully qualified name must be unique across entire model"
    )
    @Cached
    public ValidationRule entityFullyQualifiedNameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String fqn = getFullyQualifiedName(entity);
            
            // Get all entities in the model
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            // Find entities with same FQN
            List<EntityType> entitiesWithSameFQN = allEntities.stream()
                .filter(e -> getFullyQualifiedName(e).equals(fqn))
                .collect(Collectors.toList());
            
            if (entitiesWithSameFQN.size() > 1) {
                return ValidationResult.fail(
                    "EntityFullyQualifiedNameMustBeUnique",
                    "Entity fully qualified name '" + fqn + "' is used by " +
                    entitiesWithSameFQN.size() + " entities. Each entity must have " +
                    "a unique package.name combination.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    private String getFullyQualifiedName(EntityType entity) {
        List<String> parts = new ArrayList<>();
        parts.add(entity.getName());
        
        EObject container = entity.eContainer();
        while (container instanceof Package) {
            Package pkg = (Package) container;
            parts.add(0, pkg.getName());
            container = pkg.eContainer();
        }
        
        return String.join(".", parts);
    }
}
```

### Aggregate Constraints

```java
@ValidationContext(Model.class)
public class ModelAggregateValidations {
    
    @Constraint(
        name = "ModelMustHaveAtLeastOneEntity",
        message = "Model must contain at least one entity"
    )
    public ValidationRule modelMustHaveAtLeastOneEntity() {
        return (element, ctx) -> {
            Model model = (Model) element;
            
            // Get all entities across all packages
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            if (allEntities.isEmpty()) {
                return ValidationResult.fail(
                    "ModelMustHaveAtLeastOneEntity",
                    "Model '" + model.getName() + "' contains no entities. " +
                    "A valid model must define at least one entity type.",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Critique(
        name = "ModelShouldNotExceedEntityLimit",
        message = "Model should not have too many entities"
    )
    public ValidationRule modelShouldNotExceedEntityLimit() {
        return (element, ctx) -> {
            Model model = (Model) element;
            
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            int entityCount = allEntities.size();
            int recommendedLimit = 500;
            
            if (entityCount > recommendedLimit) {
                return ValidationResult.warn(
                    "ModelShouldNotExceedEntityLimit",
                    "Model '" + model.getName() + "' has " + entityCount + 
                    " entities, exceeding recommended limit of " + recommendedLimit + 
                    ". Consider splitting into multiple models for better maintainability.",
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Reference Integrity Validation

Ensure references point to valid, existing elements.

### Reference Target Must Exist

```java
@ValidationContext(Association.class)
public class AssociationIntegrityValidations {
    
    @Constraint(
        name = "AssociationTargetMustExist",
        message = "Association target must exist in model"
    )
    public ValidationRule associationTargetMustExist() {
        return (element, ctx) -> {
            Association association = (Association) element;
            String targetName = association.getTargetEntityName();
            
            if (targetName == null || targetName.isEmpty()) {
                return ValidationResult.fail(
                    "AssociationTargetMustExist",
                    "Association '" + association.getName() + "' has no target entity specified",
                    Severity.ERROR,
                    element
                );
            }
            
            // Query all entities to find the target
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            
            boolean targetExists = allEntities.stream()
                .anyMatch(e -> e.getName().equals(targetName));
            
            if (!targetExists) {
                return ValidationResult.fail(
                    "AssociationTargetMustExist",
                    "Association '" + association.getName() + "' references non-existent " +
                    "entity '" + targetName + "'. Available entities: " +
                    allEntities.stream()
                        .map(EntityType::getName)
                        .limit(10)
                        .collect(Collectors.joining(", ")),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Reference Target Must Be Valid

```java
@ValidationContext(Association.class)
public class AssociationIntegrityValidations {
    
    @Constraint(
        name = "AssociationTargetMustBeValid",
        message = "Association target must be a valid entity"
    )
    @Satisfies(constraints = {"AssociationTargetMustExist"})
    public ValidationRule associationTargetMustBeValid() {
        return (element, ctx) -> {
            Association association = (Association) element;
            EntityType target = association.getTarget();
            
            // Check if target entity satisfies its constraints
            List<String> failedConstraints = new ArrayList<>();
            
            if (!ctx.satisfies(target, "EntityMustHaveName")) {
                failedConstraints.add("EntityMustHaveName");
            }
            
            if (!ctx.satisfies(target, "EntityMustHaveValidStructure")) {
                failedConstraints.add("EntityMustHaveValidStructure");
            }
            
            if (!ctx.satisfies(target, "EntityNameMustBeValid")) {
                failedConstraints.add("EntityNameMustBeValid");
            }
            
            if (!failedConstraints.isEmpty()) {
                return ValidationResult.fail(
                    "AssociationTargetMustBeValid",
                    "Association '" + association.getName() + "' references entity '" +
                    target.getName() + "' which fails validation: " +
                    String.join(", ", failedConstraints),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### No Dangling References

```java
@ValidationContext(Model.class)
public class ModelIntegrityValidations {
    
    @Constraint(
        name = "ModelMustHaveNoDanglingReferences",
        message = "Model must have no dangling references"
    )
    @Cached
    public ValidationRule modelMustHaveNoDanglingReferences() {
        return (element, ctx) -> {
            Model model = (Model) element;
            
            // Collect all entity names
            Set<String> entityNames = ctx.getAllInstances(EntityType.class).stream()
                .map(EntityType::getName)
                .collect(Collectors.toSet());
            
            // Check all associations
            List<Association> allAssociations = ctx.getAllInstances(Association.class);
            List<String> danglingReferences = new ArrayList<>();
            
            for (Association assoc : allAssociations) {
                String targetName = assoc.getTargetEntityName();
                
                if (targetName != null && !entityNames.contains(targetName)) {
                    EntityType owner = (EntityType) assoc.eContainer();
                    danglingReferences.add(
                        owner.getName() + "." + assoc.getName() + " -> " + targetName
                    );
                }
            }
            
            if (!danglingReferences.isEmpty()) {
                return ValidationResult.fail(
                    "ModelMustHaveNoDanglingReferences",
                    "Model '" + model.getName() + "' has " + danglingReferences.size() +
                    " dangling references: " + String.join(", ", danglingReferences),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Performance Optimization for Cross-References

Cross-reference validation can be expensive. Use these optimization patterns.

### Build Index Once with Caching

```java
@ValidationContext(EntityType.class)
public class OptimizedCrossReferenceValidations {
    
    @Constraint(
        name = "EntityNameMustBeUniqueAcrossModel",
        message = "Entity name must be unique"
    )
    public ValidationRule entityNameMustBeUniqueAcrossModel() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Build entity name index once and cache it
            CacheKey indexKey = CacheKey.of("entity-name-index");
            Map<String, List<EntityType>> nameIndex = 
                (Map<String, List<EntityType>>) ctx.getCached(indexKey);
            
            if (nameIndex == null) {
                // Build index on first access
                nameIndex = ctx.getAllInstances(EntityType.class).stream()
                    .collect(Collectors.groupingBy(EntityType::getName));
                ctx.putCached(indexKey, nameIndex);
            }
            
            // Use cached index for O(1) lookup
            List<EntityType> entitiesWithSameName = nameIndex.get(entity.getName());
            
            if (entitiesWithSameName != null && entitiesWithSameName.size() > 1) {
                return ValidationResult.fail(
                    "EntityNameMustBeUniqueAcrossModel",
                    "Entity name '" + entity.getName() + "' is used by " +
                    entitiesWithSameName.size() + " entities",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Use Extension Methods for Repeated Queries

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all entities in the model.
     * Cached to avoid repeated queries.
     */
    @Cached
    public List<EntityType> getAllEntitiesInModel(EntityType self, ValidationContext ctx) {
        return ctx.getAllInstances(EntityType.class);
    }
    
    /**
     * Build entity name to entity map.
     * Cached for efficient lookups.
     */
    @Cached
    public Map<String, EntityType> getEntityNameMap(EntityType self, ValidationContext ctx) {
        return ctx.getAllInstances(EntityType.class).stream()
            .collect(Collectors.toMap(
                EntityType::getName,
                e -> e,
                (e1, e2) -> e1 // Keep first in case of duplicates
            ));
    }
}

// Usage in validation:
@ValidationContext(Association.class)
public class AssociationValidations {
    
    @Constraint(name = "TargetExists", message = "Target must exist")
    public ValidationRule targetExists() {
        return (element, ctx) -> {
            Association assoc = (Association) element;
            EntityType dummy = ctx.getAllInstances(EntityType.class).get(0);
            
            // Use cached extension method
            Map<String, EntityType> entityMap = ctx.call(
                dummy,
                "getEntityNameMap",
                ctx
            );
            
            String targetName = assoc.getTargetEntityName();
            if (!entityMap.containsKey(targetName)) {
                return ValidationResult.fail("Target does not exist: " + targetName);
            }
            
            return ValidationResult.pass();
        };
    }
}
```

### Batch Validation with Model-Level Rules

```java
@ValidationContext(Model.class)
public class ModelBatchValidations {
    
    /**
     * Validate all cross-references in a single pass.
     * More efficient than checking each reference individually.
     */
    @Constraint(
        name = "AllReferencesMustBeValid",
        message = "All references must be valid"
    )
    @Cached
    public ValidationRule allReferencesMustBeValid() {
        return (element, ctx) -> {
            Model model = (Model) element;
            
            // Build entity name set once
            Set<String> entityNames = ctx.getAllInstances(EntityType.class).stream()
                .map(EntityType::getName)
                .collect(Collectors.toSet());
            
            // Check all associations in one pass
            List<Association> allAssociations = ctx.getAllInstances(Association.class);
            Map<String, List<String>> invalidReferences = new HashMap<>();
            
            for (Association assoc : allAssociations) {
                String targetName = assoc.getTargetEntityName();
                
                if (targetName != null && !entityNames.contains(targetName)) {
                    EntityType owner = (EntityType) assoc.eContainer();
                    String ownerName = owner.getName();
                    
                    invalidReferences
                        .computeIfAbsent(ownerName, k -> new ArrayList<>())
                        .add(assoc.getName() + " -> " + targetName);
                }
            }
            
            if (!invalidReferences.isEmpty()) {
                StringBuilder message = new StringBuilder();
                message.append("Model has invalid references:\n");
                
                invalidReferences.forEach((ownerName, refs) -> {
                    message.append("  ").append(ownerName).append(": ")
                           .append(String.join(", ", refs)).append("\n");
                });
                
                return ValidationResult.fail(
                    "AllReferencesMustBeValid",
                    message.toString(),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

## Best Practices

### 1. Use getAllInstances() Efficiently

```java
// ❌ BAD: Calling getAllInstances() multiple times
List<EntityType> entities1 = ctx.getAllInstances(EntityType.class);
List<EntityType> entities2 = ctx.getAllInstances(EntityType.class);
List<EntityType> entities3 = ctx.getAllInstances(EntityType.class);

// ✓ GOOD: Call once and reuse
List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
// Use allEntities multiple times
```

### 2. Build Indexes for O(1) Lookup

```java
// ❌ BAD: O(n) lookup for each element
for (Association assoc : associations) {
    boolean exists = allEntities.stream()
        .anyMatch(e -> e.getName().equals(assoc.getTargetEntityName()));
}

// ✓ GOOD: O(1) lookup with index
Set<String> entityNames = allEntities.stream()
    .map(EntityType::getName)
    .collect(Collectors.toSet());

for (Association assoc : associations) {
    boolean exists = entityNames.contains(assoc.getTargetEntityName());
}
```

### 3. Cache Expensive Cross-Reference Checks

```java
// Mark cross-reference validation as @Cached
@Cached
@Constraint(name = "GlobalConsistency", message = "...")
public ValidationRule globalConsistency() {
    // Result is cached per element
}
```

### 4. Use Satisfies for Cross-Element Dependencies

```java
// Ensure referenced elements are valid before checking references
@Constraint(name = "ReferenceTargetIsValid", message = "...")
@Satisfies(constraints = {"ReferenceTargetExists"})
public ValidationRule referenceTargetIsValid() {
    // Safe to assume target exists
}
```

### 5. Provide Helpful Error Messages

```java
// ✓ GOOD: List available options
return ValidationResult.fail(
    "Entity '" + targetName + "' not found. Available entities: " +
    entityNames.stream().limit(10).collect(Collectors.joining(", "))
);
```

## Related Topics

- [Extension Methods](../user-guide/extension-methods.md) - Reusable cross-reference helpers
- [Caching](../user-guide/caching.md) - Performance optimization strategies
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Control execution flow
- [Performance Best Practices](../best-practices/performance.md) - Optimization techniques

## Summary

Cross-reference validation ensures integrity across model elements:
- **Transfer Object bindings** - Validate DTO to entity mappings
- **Cardinality constraints** - Enforce 1..1, 0..*, 1..* rules
- **Bidirectional consistency** - Verify opposite references match
- **Multi-element checks** - Validate collections and aggregates
- **Reference integrity** - Ensure targets exist and are valid

**Key techniques**:
- Use `ctx.getAllInstances()` for model-wide queries
- Build indexes for efficient lookups
- Cache expensive computations with `@Cached`
- Use `@Satisfies` for cross-element dependencies
- Provide actionable error messages with context

---

**Previous**: [Inheritance Validations](inheritance-validations.md) | **Next**: [Documentation Hub](../../index.md)
