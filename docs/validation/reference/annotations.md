# Annotation Reference

**Navigation**: [Documentation Hub](../../index.md) > [Reference](README.md) > Annotations

This is a complete API reference for all validation framework annotations. For practical usage examples, see the [User Guide](../user-guide/writing-constraints.md).

## Table of Contents

- [Overview](#overview)
- [Class-Level Annotations](#class-level-annotations)
  - [@ValidationContext](#validationcontext)
  - [@ExtensionMethod](#extensionmethod)
- [Method-Level Annotations](#method-level-annotations)
  - [@Constraint](#constraint)
  - [@Critique](#critique)
  - [@Guard](#guard)
  - [@Satisfies](#satisfies)
  - [@Cached](#cached)
  - [@PreValidation](#prevalidation)
  - [@PostValidation](#postvalidation)
- [Common Patterns](#common-patterns)
- [Annotation Combinations](#annotation-combinations)

---

## Overview

The Judo Zeta Validation Framework provides nine annotations for defining validation rules, extension methods, and execution control flow. All annotations are located in the `hu.blackbelt.judo.zeta.validation.annotation` package.

**Key Concepts:**

- **Class-level annotations** define the element type a validator or extension class operates on
- **Method-level annotations** define individual validation rules and their behavior
- **Guard annotations** control conditional execution of rules
- **Dependency annotations** establish execution order between rules
- **Lifecycle annotations** provide hooks before and after validation

---

## Class-Level Annotations

### @ValidationContext

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.ValidationContext`

**Purpose**: Marks a class as a validator for a specific EClass type. All validation rules in the class apply to instances of the specified type.

**Target**: `ElementType.TYPE` (classes only)

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `value` | `Class<? extends EObject>` | Yes | The EClass type this validator handles |

#### Usage Example

```java
import hu.blackbelt.judo.zeta.validation.annotation.ValidationContext;
import hu.blackbelt.judo.zeta.validation.annotation.Constraint;
import org.eclipse.emf.ecore.EClass;

@ValidationContext(EClass.class)
public class EClassValidations {
    
    @Constraint(
        name = "EClassMustHaveName",
        message = "EClass must have a name"
    )
    public ValidationRule eClassMustHaveName() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            return eClass.getName() != null && !eClass.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "EClassMustHaveName",
                    "EClass must have a name",
                    Severity.ERROR,
                    element
                );
        };
    }
}
```

#### Common Patterns

**Pattern 1: Multiple validators for different types**

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    // Rules for EntityType
}

@ValidationContext(DataType.class)
public class DataTypeValidations {
    // Rules for DataType
}
```

**Pattern 2: Organizing validators by concern**

```java
@ValidationContext(EntityType.class)
public class EntityTypeStructuralValidations {
    // Structural integrity rules
}

@ValidationContext(EntityType.class)
public class EntityTypeNamingValidations {
    // Naming convention rules
}
```

#### Notes

- One class can only have one `@ValidationContext` annotation
- The validator will be invoked for all instances of the specified type in the model
- Type hierarchy is respected: validators for `EObject.class` will match all elements
- Multiple validator classes can target the same EClass type

#### Related Annotations

- [@Constraint](#constraint) - Define error-level rules
- [@Critique](#critique) - Define warning-level rules
- [@ExtensionMethod](#extensionmethod) - Define helper methods for the same type

---

### @ExtensionMethod

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod`

**Purpose**: Marks a class as providing extension methods for a specific EClass type. Extension methods add behavior to EMF types without modifying generated code, similar to EOL operations.

**Target**: `ElementType.TYPE` (classes only)

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `value` | `Class<? extends EObject>` | Yes | The EClass type this class extends |

#### Usage Example

```java
import hu.blackbelt.judo.zeta.validation.annotation.ExtensionMethod;
import hu.blackbelt.judo.zeta.validation.annotation.Cached;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Get all super types recursively.
     */
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) {
        return self.getGeneralizations().stream()
            .map(Generalization::getTarget)
            .flatMap(t -> Stream.concat(
                Stream.of(t),
                getAllSuperTypes(t).stream()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Check if entity has a specific attribute.
     */
    public boolean hasAttribute(EntityType self, String attributeName) {
        return self.getAttributes().stream()
            .anyMatch(attr -> attributeName.equals(attr.getName()));
    }
    
    /**
     * Get all data members including inherited ones.
     */
    @Cached
    public Collection<DataMember> getAllDataMembers(EntityType self) {
        Collection<DataMember> inherited = getAllSuperTypes(self).stream()
            .flatMap(type -> type.getDataMembers().stream())
            .collect(Collectors.toList());
        
        inherited.addAll(self.getDataMembers());
        return inherited;
    }
}
```

#### Common Patterns

**Pattern 1: Computed properties**

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    public boolean isAbstract(EntityType self) {
        return self.getAbstract() != null && self.getAbstract();
    }
    
    @Cached
    public String getFullyQualifiedName(EntityType self) {
        return self.getNamespace() + "::" + self.getName();
    }
}
```

**Pattern 2: Navigation helpers**

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeNavigators {
    
    @Cached
    public Collection<Relation> getOutgoingRelations(EntityType self) {
        return self.getRelations().stream()
            .filter(r -> r.getSource().equals(self))
            .collect(Collectors.toList());
    }
    
    @Cached
    public Collection<Relation> getIncomingRelations(EntityType self) {
        return ((Model) self.eContainer())
            .getAllRelations().stream()
            .filter(r -> r.getTarget().equals(self))
            .collect(Collectors.toList());
    }
}
```

**Pattern 3: Validation helpers**

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeValidationHelpers {
    
    public boolean hasMapping(EntityType self) {
        return self.getMapping() != null;
    }
    
    @Cached
    public boolean hasCircularDependency(EntityType self) {
        return getAllSuperTypes(self).contains(self);
    }
}
```

#### Notes

- Extension methods must be public
- The first parameter is conventionally named `self` (the instance being extended)
- Extension methods can be used in validation rules and other extension methods
- Methods can have additional parameters beyond `self`
- Use `@Cached` for expensive computations

#### Related Annotations

- [@Cached](#cached) - Cache expensive extension method results
- [@ValidationContext](#validationcontext) - Similar syntax for validators

---

## Method-Level Annotations

### @Constraint

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.Constraint`

**Purpose**: Defines an error-level validation rule. Constraints represent hard errors that must be fixed for the model to be valid. Failed constraints prevent the model from being considered correct.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `name` | `String` | Yes | - | Unique constraint name for cross-referencing |
| `message` | `String` | Yes | - | Error message template (supports placeholders) |

#### Usage Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityTypeHasMapping",
        message = "Entity type '{element.name}' must have mapping"
    )
    public ValidationRule entityTypeHasMapping() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (entity.getMapping() == null) {
                return ValidationResult.fail(
                    "EntityTypeHasMapping",
                    String.format("Entity type '%s' must have mapping", entity.getName()),
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "EntityTypeNameIsValid",
        message = "Entity type name must match pattern [A-Z][a-zA-Z0-9]*"
    )
    public ValidationRule entityTypeNameIsValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            if (name == null || !name.matches("[A-Z][a-zA-Z0-9]*")) {
                return ValidationResult.fail(
                    "EntityTypeNameIsValid",
                    "Entity type name '" + name + "' must match pattern [A-Z][a-zA-Z0-9]*",
                    Severity.ERROR,
                    element
                );
            }
            
            return ValidationResult.pass();
        };
    }
}
```

#### Common Patterns

**Pattern 1: Simple existence check**

```java
@Constraint(
    name = "EntityMustHaveName",
    message = "Entity must have a name"
)
public ValidationRule entityMustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null && !entity.getName().isEmpty()
            ? ValidationResult.pass()
            : ValidationResult.fail("EntityMustHaveName", "Entity must have a name", Severity.ERROR, element);
    };
}
```

**Pattern 2: Cross-reference validation**

```java
@Constraint(
    name = "RelationTargetExists",
    message = "Relation target must exist in model"
)
public ValidationRule relationTargetExists() {
    return (element, ctx) -> {
        Relation relation = (Relation) element;
        Model model = (Model) relation.eContainer();
        
        boolean targetExists = model.getEntityTypes().contains(relation.getTarget());
        
        return targetExists
            ? ValidationResult.pass()
            : ValidationResult.fail(
                "RelationTargetExists",
                "Relation target does not exist in model",
                Severity.ERROR,
                element
            );
    };
}
```

**Pattern 3: Uniqueness validation**

```java
@Constraint(
    name = "EntityTypeNamesAreUnique",
    message = "Entity type names must be unique"
)
public ValidationRule entityTypeNamesAreUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        Model model = (Model) entity.eContainer();
        
        long count = model.getEntityTypes().stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        return count <= 1
            ? ValidationResult.pass()
            : ValidationResult.fail(
                "EntityTypeNamesAreUnique",
                "Multiple entity types named '" + entity.getName() + "'",
                Severity.ERROR,
                element
            );
    };
}
```

#### Notes

- Constraint names must be unique within the validator class
- Failed constraints produce ERROR severity results
- Constraints can be referenced by `@Satisfies` and `@Guard` annotations
- Message templates support placeholders, but must be interpolated in the rule logic

#### Related Annotations

- [@Critique](#critique) - Warning-level alternative
- [@Guard](#guard) - Conditional execution
- [@Satisfies](#satisfies) - Dependency ordering

---

### @Critique

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.Critique`

**Purpose**: Defines a warning-level validation rule. Critiques represent recommendations or best practices that should be followed but are not critical errors. Models can be valid even with critique violations.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `name` | `String` | Yes | - | Unique critique name for cross-referencing |
| `message` | `String` | Yes | - | Warning message template (supports placeholders) |

#### Usage Example

```java
@ValidationContext(EClass.class)
public class EClassValidations {
    
    @Critique(
        name = "EClassShouldStartWithCapital",
        message = "EClass name should start with capital letter"
    )
    public ValidationRule eClassShouldStartWithCapital() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            
            if (eClass.getName() == null || eClass.getName().isEmpty()) {
                return ValidationResult.pass();
            }
            
            return Character.isUpperCase(eClass.getName().charAt(0))
                ? ValidationResult.pass()
                : ValidationResult.warn(
                    "EClassShouldStartWithCapital",
                    "EClass name '" + eClass.getName() + "' should start with capital letter",
                    element
                );
        };
    }
    
    @Critique(
        name = "EClassShouldHaveDocumentation",
        message = "EClass should have documentation"
    )
    public ValidationRule eClassShouldHaveDocumentation() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            EAnnotation docAnnotation = eClass.getEAnnotation("http://www.eclipse.org/emf/2002/GenModel");
            
            boolean hasDoc = docAnnotation != null 
                && docAnnotation.getDetails().containsKey("documentation");
            
            return hasDoc
                ? ValidationResult.pass()
                : ValidationResult.warn(
                    "EClassShouldHaveDocumentation",
                    "EClass '" + eClass.getName() + "' should have documentation",
                    element
                );
        };
    }
}
```

#### Common Patterns

**Pattern 1: Naming conventions**

```java
@Critique(
    name = "AttributesShouldBeCamelCase",
    message = "Attributes should use camelCase naming"
)
public ValidationRule attributesShouldBeCamelCase() {
    return (element, ctx) -> {
        Attribute attr = (Attribute) element;
        boolean isCamelCase = attr.getName().matches("[a-z][a-zA-Z0-9]*");
        
        return isCamelCase
            ? ValidationResult.pass()
            : ValidationResult.warn(
                "AttributesShouldBeCamelCase",
                "Attribute '" + attr.getName() + "' should use camelCase",
                element
            );
    };
}
```

**Pattern 2: Best practice recommendations**

```java
@Critique(
    name = "EntityShouldHavePrimaryKey",
    message = "Entity should have a primary key attribute"
)
public ValidationRule entityShouldHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        boolean hasPrimaryKey = entity.getAttributes().stream()
            .anyMatch(attr -> attr.isPrimaryKey());
        
        return hasPrimaryKey
            ? ValidationResult.pass()
            : ValidationResult.warn(
                "EntityShouldHavePrimaryKey",
                "Entity '" + entity.getName() + "' should have a primary key",
                element
            );
    };
}
```

**Pattern 3: Performance recommendations**

```java
@Critique(
    name = "LargeCollectionShouldBeIndexed",
    message = "Large collections should have indexes for performance"
)
public ValidationRule largeCollectionShouldBeIndexed() {
    return (element, ctx) -> {
        Relation relation = (Relation) element;
        
        if (relation.isCollection() && relation.getUpperBound() > 100) {
            boolean hasIndex = relation.getAnnotations().stream()
                .anyMatch(ann -> "index".equals(ann.getKey()));
            
            if (!hasIndex) {
                return ValidationResult.warn(
                    "LargeCollectionShouldBeIndexed",
                    "Collection '" + relation.getName() + "' should have index",
                    element
                );
            }
        }
        
        return ValidationResult.pass();
    };
}
```

#### Notes

- Critiques produce WARNING severity results
- Critiques are optional - models can be valid with critique violations
- Use critiques for style guides, best practices, and performance recommendations
- Critique names can be referenced by `@Satisfies` (though less common)

#### Related Annotations

- [@Constraint](#constraint) - Error-level alternative
- [@Guard](#guard) - Conditional execution

---

### @Guard

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.Guard`

**Purpose**: References a predicate method that determines whether a validation rule should execute. Guards enable conditional validation based on element state or context.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | `String` | Yes | Name of the guard method in the same class |

#### Guard Method Signature

```java
public boolean guardMethodName(EObject element, ValidationContext ctx)
```

#### Usage Example

```java
@ValidationContext(EClass.class)
public class EClassValidations {
    
    @Constraint(
        name = "AbstractClassMustHaveSubclasses",
        message = "Abstract class must have subclasses"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractClassMustHaveSubclasses() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            EPackage pkg = eClass.getEPackage();
            
            boolean hasSubclasses = pkg.getEClassifiers().stream()
                .filter(c -> c instanceof EClass)
                .map(c -> (EClass) c)
                .anyMatch(c -> c.getESuperTypes().contains(eClass));
            
            return hasSubclasses
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "AbstractClassMustHaveSubclasses",
                    "Abstract class '" + eClass.getName() + "' must have subclasses",
                    Severity.ERROR,
                    element
                );
        };
    }
    
    /**
     * Guard predicate - only validate abstract classes.
     */
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EClass) element).isAbstract();
    }
    
    @Constraint(
        name = "MappedEntityMustHaveTable",
        message = "Mapped entity must have table name"
    )
    @Guard(method = "isMapped")
    public ValidationRule mappedEntityMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            return entity.getTableName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "MappedEntityMustHaveTable",
                    "Mapped entity '" + entity.getName() + "' must have table name",
                    Severity.ERROR,
                    element
                );
        };
    }
    
    public boolean isMapped(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getMapping() != null;
    }
}
```

#### Common Patterns

**Pattern 1: Type-based guards**

```java
public boolean isEntity(EObject element, ValidationContext ctx) {
    return element instanceof EntityType;
}

public boolean isValueType(EObject element, ValidationContext ctx) {
    return element instanceof ValueType;
}
```

**Pattern 2: State-based guards**

```java
public boolean hasContainer(EObject element, ValidationContext ctx) {
    return element.eContainer() != null;
}

public boolean isConfigured(EObject element, ValidationContext ctx) {
    NamedElement named = (NamedElement) element;
    return named.getConfiguration() != null;
}
```

**Pattern 3: Context-based guards**

```java
public boolean isInProduction(EObject element, ValidationContext ctx) {
    Boolean productionMode = ctx.getAttribute("productionMode");
    return Boolean.TRUE.equals(productionMode);
}

public boolean hasValidationEnabled(EObject element, ValidationContext ctx) {
    return !ctx.hasAttribute("skipValidation");
}
```

**Pattern 4: Shared guards**

```java
// Multiple rules can use the same guard
public boolean isMapped(EObject element, ValidationContext ctx) {
    return ((EntityType) element).getMapping() != null;
}

@Constraint(name = "Rule1", message = "...")
@Guard(method = "isMapped")
public ValidationRule rule1() { ... }

@Constraint(name = "Rule2", message = "...")
@Guard(method = "isMapped")
public ValidationRule rule2() { ... }
```

#### Notes

- Guard methods must be `public` and in the same class
- Guard methods must return `boolean`
- Guard methods receive the element and validation context
- If guard returns `false`, the rule is skipped entirely
- Guards are evaluated before dependency resolution
- Failed guards do not count as validation failures

#### Related Annotations

- [@Constraint](#constraint) - Can be guarded
- [@Critique](#critique) - Can be guarded
- [@Satisfies](#satisfies) - Works with guards

---

### @Satisfies

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.Satisfies`

**Purpose**: Declares dependencies on other constraints. Ensures a validation rule only executes after its dependencies have passed. Used for establishing execution order and preventing cascading errors.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `constraints` | `String[]` | Yes | Array of constraint names this rule depends on |

#### Usage Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(
        name = "EntityHasName",
        message = "Entity must have a name"
    )
    public ValidationRule entityHasName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "EntityHasName",
                    "Entity must have a name",
                    Severity.ERROR,
                    element
                );
        };
    }
    
    @Constraint(
        name = "EntityNameIsValid",
        message = "Entity name must match pattern"
    )
    @Satisfies(constraints = {"EntityHasName"})
    public ValidationRule entityNameIsValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            // Safe to access name - EntityHasName passed
            String name = entity.getName();
            
            return name.matches("[A-Z][a-zA-Z0-9]*")
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "EntityNameIsValid",
                    "Entity name '" + name + "' must match pattern [A-Z][a-zA-Z0-9]*",
                    Severity.ERROR,
                    element
                );
        };
    }
    
    @Constraint(
        name = "EntityNameIsUnique",
        message = "Entity name must be unique"
    )
    @Satisfies(constraints = {"EntityHasName", "EntityNameIsValid"})
    public ValidationRule entityNameIsUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Model model = (Model) entity.eContainer();
            
            // Safe - name exists and is valid
            long count = model.getEntityTypes().stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
            
            return count <= 1
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "EntityNameIsUnique",
                    "Multiple entities named '" + entity.getName() + "'",
                    Severity.ERROR,
                    element
                );
        };
    }
}
```

#### Common Patterns

**Pattern 1: Null-safety chain**

```java
@Constraint(name = "HasContainer", message = "...")
public ValidationRule hasContainer() { ... }

@Constraint(name = "ContainerIsValid", message = "...")
@Satisfies(constraints = {"HasContainer"})
public ValidationRule containerIsValid() {
    // Safe to access container
}
```

**Pattern 2: Multi-level dependencies**

```java
@Constraint(name = "Level1", message = "...")
public ValidationRule level1() { ... }

@Constraint(name = "Level2", message = "...")
@Satisfies(constraints = {"Level1"})
public ValidationRule level2() { ... }

@Constraint(name = "Level3", message = "...")
@Satisfies(constraints = {"Level2"})  // Transitively depends on Level1
public ValidationRule level3() { ... }
```

**Pattern 3: Multiple dependencies**

```java
@Constraint(name = "HasMapping", message = "...")
public ValidationRule hasMapping() { ... }

@Constraint(name = "HasTarget", message = "...")
public ValidationRule hasTarget() { ... }

@Constraint(name = "MappingAndTargetAreCompatible", message = "...")
@Satisfies(constraints = {"HasMapping", "HasTarget"})
public ValidationRule mappingAndTargetAreCompatible() {
    // Both dependencies satisfied
}
```

#### Notes

- Constraint names in `constraints` array must exist in the same validator class
- If any dependency fails, the dependent rule is skipped
- Skipped rules (due to failed dependencies) don't count as failures themselves
- Circular dependencies will cause a runtime error
- Use dependencies to prevent cascading error messages
- Dependencies are resolved per element instance

#### Related Annotations

- [@Constraint](#constraint) - Define constraints to depend on
- [@Critique](#critique) - Can also be dependencies (less common)
- [@Guard](#guard) - Evaluated before dependency checking

---

### @Cached

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.Cached`

**Purpose**: Enables result caching for extension methods. Cached methods store results based on the target object and method arguments, improving performance for expensive computations.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

None - this annotation has no parameters.

#### Cache Key Computation

Cache keys are computed from method arguments:

- **EObject**: XMI ID via `Resource.getURIFragment()` or `EcoreUtil.getURI()`
- **Primitives**: Direct value (String, Number, Boolean, Enum)
- **Collections**: Ordered list of key parts from elements
- **Maps**: Sorted by key, then processed as key-value pairs
- **Other objects**: `toString()` representation

#### Usage Example

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    /**
     * Expensive recursive computation - cache results.
     */
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) {
        return self.getGeneralizations().stream()
            .map(Generalization::getTarget)
            .flatMap(t -> Stream.concat(
                Stream.of(t),
                getAllSuperTypes(t).stream()  // Recursive - benefits from caching
            ))
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * Cache with multiple parameters.
     */
    @Cached
    public Collection<Attribute> getAttributesByType(EntityType self, DataType type) {
        // Cached per (EntityType, DataType) combination
        return self.getAttributes().stream()
            .filter(attr -> type.equals(attr.getType()))
            .collect(Collectors.toList());
    }
    
    /**
     * Simple getter - no caching needed.
     */
    public String getName(EntityType self) {
        return self.getName();  // Fast, no cache needed
    }
    
    /**
     * Traverses entire model hierarchy.
     */
    @Cached
    public Collection<EntityType> getAllReachableTypes(EntityType self) {
        Set<EntityType> visited = new HashSet<>();
        Queue<EntityType> queue = new LinkedList<>();
        queue.add(self);
        
        while (!queue.isEmpty()) {
            EntityType current = queue.poll();
            if (visited.add(current)) {
                current.getRelations().stream()
                    .map(Relation::getTarget)
                    .filter(target -> !visited.contains(target))
                    .forEach(queue::add);
            }
        }
        
        return visited;
    }
}
```

#### Common Patterns

**Pattern 1: Recursive computations**

```java
@Cached
public int getDepth(EntityType self) {
    Collection<EntityType> superTypes = getAllSuperTypes(self);
    return superTypes.isEmpty() 
        ? 0 
        : superTypes.stream()
            .mapToInt(this::getDepth)
            .max()
            .orElse(0) + 1;
}
```

**Pattern 2: Model-wide queries**

```java
@Cached
public Collection<EntityType> getAllEntitiesInModel(EntityType self) {
    Model model = (Model) self.eResource().getContents().get(0);
    return model.getAllEntityTypes();
}
```

**Pattern 3: Complex filtering**

```java
@Cached
public Collection<Relation> getNavigableRelations(EntityType self, boolean bidirectional) {
    return self.getRelations().stream()
        .filter(r -> r.isNavigable())
        .filter(r -> !bidirectional || r.getOpposite() != null)
        .collect(Collectors.toList());
}
```

#### When to Use @Cached

**Use caching for:**
- Recursive computations
- Model-wide traversals
- Complex filtering or aggregations
- Methods called multiple times with same arguments
- Expensive computations (> 1ms)

**Avoid caching for:**
- Simple property access
- Methods with side effects
- Methods that return different values over time
- Methods with many unique argument combinations

#### Performance Considerations

```java
@ExtensionMethod(EntityType.class)
public class PerformanceExample {
    
    // GOOD: Recursive, called multiple times
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) { ... }
    
    // GOOD: Expensive model traversal
    @Cached
    public Set<String> getAllUsedNames(EntityType self) { ... }
    
    // BAD: Simple getter, caching overhead > benefit
    public String getName(EntityType self) {
        return self.getName();
    }
    
    // BAD: Called once per element with unique args
    public boolean hasAttributeNamed(EntityType self, String name) {
        return self.getAttributes().stream()
            .anyMatch(a -> name.equals(a.getName()));
    }
}
```

#### Notes

- Cache is scoped to the validation execution
- Cache is cleared after validation completes
- Cache key collision will cause incorrect results
- Cached methods should be deterministic (same inputs = same outputs)
- Thread-safe implementation

#### Related Annotations

- [@ExtensionMethod](#extensionmethod) - Required on the class
- No direct relationship with validation annotations

---

### @PreValidation

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.PreValidation`

**Purpose**: Marks a method as a pre-validation hook. Executed before any validation rules run, useful for initialization and setup.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

None - this annotation has no parameters.

#### Method Signature

```java
@PreValidation
public void methodName(hu.blackbelt.judo.zeta.validation.core.ValidationContext ctx)
```

#### Usage Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @PreValidation
    public void setup(hu.blackbelt.judo.zeta.validation.core.ValidationContext ctx) {
        // Initialize shared cache
        Map<String, EntityType> nameCache = new HashMap<>();
        ctx.setAttribute("entityNameCache", nameCache);
        
        // Collect all entities for uniqueness checks
        Collection<EntityType> allEntities = new ArrayList<>();
        ctx.setAttribute("allEntities", allEntities);
        
        // Set up counters
        ctx.setAttribute("validationCount", new AtomicInteger(0));
        
        // Log validation start
        System.out.println("Starting EntityType validation");
    }
    
    @Constraint(
        name = "EntityNameIsUnique",
        message = "Entity name must be unique"
    )
    public ValidationRule entityNameIsUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Use pre-computed cache
            @SuppressWarnings("unchecked")
            Map<String, EntityType> cache = ctx.getAttribute("entityNameCache");
            
            if (cache.containsKey(entity.getName())) {
                return ValidationResult.fail(
                    "EntityNameIsUnique",
                    "Duplicate entity name: " + entity.getName(),
                    Severity.ERROR,
                    element
                );
            }
            
            cache.put(entity.getName(), entity);
            return ValidationResult.pass();
        };
    }
}
```

#### Common Patterns

**Pattern 1: Shared data structures**

```java
@PreValidation
public void initializeSharedState(ValidationContext ctx) {
    ctx.setAttribute("processedElements", new HashSet<EObject>());
    ctx.setAttribute("errorCount", new AtomicInteger(0));
}
```

**Pattern 2: Resource loading**

```java
@PreValidation
public void loadExternalData(ValidationContext ctx) {
    // Load configuration
    Properties config = loadConfiguration();
    ctx.setAttribute("config", config);
    
    // Load schema
    Schema schema = loadSchema();
    ctx.setAttribute("schema", schema);
}
```

**Pattern 3: Performance monitoring**

```java
@PreValidation
public void startMonitoring(ValidationContext ctx) {
    ctx.setAttribute("validationStartTime", System.currentTimeMillis());
    ctx.setAttribute("ruleExecutionTimes", new HashMap<String, Long>());
}
```

#### Notes

- Called once before any validation rules execute
- Can modify ValidationContext attributes
- Multiple `@PreValidation` methods are supported (execution order undefined)
- Exceptions in pre-validation hooks will abort validation
- Shares context with validation rules and post-validation hooks

#### Related Annotations

- [@PostValidation](#postvalidation) - Cleanup counterpart
- [@ValidationContext](#validationcontext) - Required on class

---

### @PostValidation

**Full Name**: `hu.blackbelt.judo.zeta.validation.annotation.PostValidation`

**Purpose**: Marks a method as a post-validation hook. Executed after all validation rules complete, useful for cleanup and reporting.

**Target**: `ElementType.METHOD`

**Retention**: `RetentionPolicy.RUNTIME`

#### Parameters

None - this annotation has no parameters.

#### Method Signature

```java
@PostValidation
public void methodName(hu.blackbelt.judo.zeta.validation.core.ValidationContext ctx)
```

#### Usage Example

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @PreValidation
    public void setup(ValidationContext ctx) {
        ctx.setAttribute("cache", new HashMap<String, Object>());
        ctx.setAttribute("validationStartTime", System.currentTimeMillis());
    }
    
    @PostValidation
    public void cleanup(ValidationContext ctx) {
        // Clear cache
        Map<?, ?> cache = ctx.getAttribute("cache");
        if (cache != null) {
            cache.clear();
        }
        
        // Report performance
        Long startTime = ctx.getAttribute("validationStartTime");
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("Validation completed in " + duration + "ms");
        }
        
        // Log summary
        System.out.println("EntityType validation complete");
    }
    
    @Constraint(name = "SomeConstraint", message = "...")
    public ValidationRule someConstraint() {
        return (element, ctx) -> {
            // Use cache from @PreValidation
            Map<String, Object> cache = ctx.getAttribute("cache");
            // ... validation logic
            return ValidationResult.pass();
        };
    }
}
```

#### Common Patterns

**Pattern 1: Resource cleanup**

```java
@PostValidation
public void releaseResources(ValidationContext ctx) {
    // Close connections
    Connection conn = ctx.getAttribute("connection");
    if (conn != null) {
        conn.close();
    }
    
    // Clear large data structures
    ctx.removeAttribute("largeCache");
}
```

**Pattern 2: Validation reporting**

```java
@PostValidation
public void generateReport(ValidationContext ctx) {
    Integer errorCount = ctx.getAttribute("errorCount");
    Integer warningCount = ctx.getAttribute("warningCount");
    
    System.out.println("Validation Summary:");
    System.out.println("  Errors: " + errorCount);
    System.out.println("  Warnings: " + warningCount);
}
```

**Pattern 3: Metrics collection**

```java
@PostValidation
public void collectMetrics(ValidationContext ctx) {
    Map<String, Long> executionTimes = ctx.getAttribute("ruleExecutionTimes");
    
    // Log slowest rules
    executionTimes.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(5)
        .forEach(entry -> 
            System.out.println(entry.getKey() + ": " + entry.getValue() + "ms")
        );
}
```

#### Notes

- Called once after all validation rules complete
- Executes even if validation rules fail
- Multiple `@PostValidation` methods are supported (execution order undefined)
- Exceptions in post-validation hooks are logged but don't affect validation results
- Shares context with pre-validation hooks and validation rules

#### Related Annotations

- [@PreValidation](#prevalidation) - Setup counterpart
- [@ValidationContext](#validationcontext) - Required on class

---

## Common Patterns

### Pattern: Layered Validation

Organize validation rules in dependency layers:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Layer 1: Basic existence
    @Constraint(name = "HasName", message = "Entity must have name")
    public ValidationRule hasName() { ... }
    
    // Layer 2: Format validation (depends on existence)
    @Constraint(name = "NameIsValid", message = "Name must be valid")
    @Satisfies(constraints = {"HasName"})
    public ValidationRule nameIsValid() { ... }
    
    // Layer 3: Uniqueness (depends on valid format)
    @Constraint(name = "NameIsUnique", message = "Name must be unique")
    @Satisfies(constraints = {"NameIsValid"})
    public ValidationRule nameIsUnique() { ... }
}
```

### Pattern: Conditional Validation Sets

Use guards to apply different rules based on element type:

```java
@ValidationContext(Type.class)
public class TypeValidations {
    
    @Constraint(name = "EntityRule", message = "...")
    @Guard(method = "isEntity")
    public ValidationRule entityRule() { ... }
    
    @Constraint(name = "ValueTypeRule", message = "...")
    @Guard(method = "isValueType")
    public ValidationRule valueTypeRule() { ... }
    
    public boolean isEntity(EObject element, ValidationContext ctx) {
        return element instanceof EntityType;
    }
    
    public boolean isValueType(EObject element, ValidationContext ctx) {
        return element instanceof ValueType;
    }
}
```

### Pattern: Shared Extension Methods

Create reusable extension methods used by multiple validators:

```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached
    public Collection<EntityType> getAllSuperTypes(EntityType self) { ... }
    
    @Cached
    public boolean isAbstract(EntityType self) { ... }
}

@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Inject
    private EntityTypeExtensions extensions;
    
    @Constraint(name = "Rule1", message = "...")
    public ValidationRule rule1() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Collection<EntityType> supers = extensions.getAllSuperTypes(entity);
            // Use extension method
        };
    }
}
```

---

## Annotation Combinations

### Valid Combinations

| Annotation | Can combine with |
|------------|------------------|
| `@Constraint` | `@Guard`, `@Satisfies` |
| `@Critique` | `@Guard`, `@Satisfies` |
| `@Guard` | `@Constraint`, `@Critique` |
| `@Satisfies` | `@Constraint`, `@Critique`, `@Guard` |
| `@Cached` | (Only on extension methods) |
| `@PreValidation` | (Standalone) |
| `@PostValidation` | (Standalone) |

### Example: Full Feature Constraint

```java
@ValidationContext(EntityType.class)
public class ComplexValidations {
    
    @PreValidation
    public void setup(ValidationContext ctx) {
        ctx.setAttribute("initialized", true);
    }
    
    @Constraint(name = "BaseConstraint", message = "Base must be valid")
    public ValidationRule baseConstraint() { ... }
    
    @Constraint(
        name = "ComplexConstraint",
        message = "Complex validation failed"
    )
    @Guard(method = "shouldValidate")
    @Satisfies(constraints = {"BaseConstraint"})
    public ValidationRule complexConstraint() {
        return (element, ctx) -> {
            // Executes only if:
            // 1. shouldValidate returns true
            // 2. BaseConstraint passed
            return ValidationResult.pass();
        };
    }
    
    public boolean shouldValidate(EObject element, ValidationContext ctx) {
        return ctx.hasAttribute("initialized");
    }
    
    @PostValidation
    public void cleanup(ValidationContext ctx) {
        ctx.removeAttribute("initialized");
    }
}
```

---

## See Also

- [Writing Constraints](../user-guide/writing-constraints.md) - Practical guide
- [Extension Methods](../user-guide/extension-methods.md) - Using @ExtensionMethod and @Cached
- [Architecture Overview](../architecture/validation-engine.md) - How annotations are processed
- [API Reference](validation-api.md) - Core classes and interfaces

---

**Navigation**: [Documentation Hub](../../index.md) > [Reference](README.md) > Annotations
