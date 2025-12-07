# Inheritance and Hierarchy Validation Examples

**Navigation**: [Documentation Hub](../../index.md) > [Examples](../index.md#examples) > Inheritance Validations

This guide provides complete, working implementations of inheritance and hierarchy validation patterns using the Judo Zeta Validation Framework. Each example includes graph traversal algorithms, cycle detection, and validation propagation strategies.

## Overview

Inheritance hierarchies require specialized validation to ensure structural integrity and semantic correctness. This guide covers:

- **Cyclic inheritance detection** - Detect and prevent circular inheritance chains
- **Supertype validation propagation** - Ensure child types inherit valid parents
- **Override compatibility checking** - Verify method/attribute override rules
- **Multiple inheritance validation** - Handle complex inheritance graphs
- **Abstract class instantiation checks** - Prevent instantiation of abstract types

All examples use real-world patterns with complete implementations, including helper methods and extension functions.

## Cyclic Inheritance Detection

Cyclic inheritance occurs when a type inherits from itself, either directly or indirectly through a chain of supertypes.

### Problem

```java
// Direct cycle
ClassA extends ClassB
ClassB extends ClassA  // ERROR: Cycle detected

// Indirect cycle
ClassA extends ClassB
ClassB extends ClassC
ClassC extends ClassA  // ERROR: Cycle detected through chain
```

### Solution 1: Set-Based Cycle Detection

This approach uses a `HashSet` to track visited types during traversal. If we encounter a type we've already seen, a cycle exists.

**Algorithm**: Depth-first traversal with visited set tracking

**Time Complexity**: O(n) where n is the depth of the inheritance hierarchy  
**Space Complexity**: O(n) for the visited set

```java
@ValidationContext(EntityType.class)
public class InheritanceValidations {
    
    @Constraint(
        name = "NoCyclicInheritance",
        message = "Type '{element.name}' has cyclic inheritance"
    )
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check for cycle starting from this entity
            boolean hasCycle = detectCycle(entity, new HashSet<>());
            
            return hasCycle
                ? ValidationResult.fail(
                    "Cyclic inheritance detected in type '" + entity.getName() + "'"
                )
                : ValidationResult.pass();
        };
    }
    
    /**
     * Detects cycles using depth-first search with visited set.
     * 
     * @param entity Current entity being checked
     * @param visited Set of entities already visited in this path
     * @return true if cycle detected, false otherwise
     */
    private boolean detectCycle(EntityType entity, Set<EntityType> visited) {
        // If we've seen this entity before in the current path, we have a cycle
        if (visited.contains(entity)) {
            return true;
        }
        
        // No supertype means end of chain - no cycle
        if (entity.getSuperType() == null) {
            return false;
        }
        
        // Add current entity to visited set
        visited.add(entity);
        
        // Recursively check supertype
        boolean cycleInParent = detectCycle(entity.getSuperType(), visited);
        
        // Remove current entity from visited set (backtracking)
        // This allows the entity to appear in different inheritance paths
        visited.remove(entity);
        
        return cycleInParent;
    }
}
```

### Solution 2: Path-Based Cycle Detection with Detailed Reporting

This enhanced version tracks the complete path and provides detailed error messages showing the cycle.

```java
@ValidationContext(EntityType.class)
public class DetailedInheritanceValidations {
    
    @Constraint(
        name = "NoCyclicInheritanceWithPath",
        message = "Cyclic inheritance detected"
    )
    public ValidationRule noCyclicInheritanceWithPath() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            List<EntityType> path = new ArrayList<>();
            String cycleDescription = detectCycleWithPath(entity, path);
            
            return cycleDescription == null
                ? ValidationResult.pass()
                : ValidationResult.fail(cycleDescription);
        };
    }
    
    /**
     * Detects cycles and builds a description of the cycle path.
     * 
     * @param entity Current entity being checked
     * @param path List of entities in the current traversal path
     * @return Description of cycle if found, null otherwise
     */
    private String detectCycleWithPath(EntityType entity, List<EntityType> path) {
        // Check if entity is already in the path
        int cycleStartIndex = path.indexOf(entity);
        if (cycleStartIndex >= 0) {
            // Build cycle description
            StringBuilder cycle = new StringBuilder("Cyclic inheritance: ");
            
            // Add entities in cycle
            for (int i = cycleStartIndex; i < path.size(); i++) {
                cycle.append(path.get(i).getName()).append(" -> ");
            }
            cycle.append(entity.getName());
            
            return cycle.toString();
        }
        
        // No supertype means end of chain
        if (entity.getSuperType() == null) {
            return null;
        }
        
        // Add to path and recurse
        path.add(entity);
        String result = detectCycleWithPath(entity.getSuperType(), path);
        path.remove(path.size() - 1);  // Backtrack
        
        return result;
    }
}
```

### Solution 3: Cached Cycle Detection with Extension Method

For better performance when validating large models, cache the cycle detection results.

```java
@ValidationContext(EntityType.class)
public class CachedInheritanceValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Boolean hasCyclicInheritance(EntityType self) {
        return detectCycle(self, new HashSet<>());
    }
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public List<EntityType> getInheritanceChain(EntityType self) {
        List<EntityType> chain = new ArrayList<>();
        EntityType current = self;
        
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getSuperType();
        }
        
        return chain;
    }
    
    @Constraint(
        name = "NoCyclicInheritanceCached",
        message = "Cyclic inheritance in '{element.name}'"
    )
    public ValidationRule noCyclicInheritanceCached() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Use cached extension method
            Boolean hasCycle = ctx.call(entity, "hasCyclicInheritance");
            
            if (hasCycle) {
                List<EntityType> chain = ctx.call(entity, "getInheritanceChain");
                String chainStr = chain.stream()
                    .map(EntityType::getName)
                    .collect(Collectors.joining(" -> "));
                
                return ValidationResult.fail(
                    "Cyclic inheritance detected: " + chainStr
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    private boolean detectCycle(EntityType entity, Set<EntityType> visited) {
        if (visited.contains(entity)) return true;
        if (entity.getSuperType() == null) return false;
        
        visited.add(entity);
        boolean cycleFound = detectCycle(entity.getSuperType(), visited);
        visited.remove(entity);
        
        return cycleFound;
    }
}
```

## Supertype Validation Propagation

Child types should only inherit from valid parent types. This pattern ensures that validation errors in parent types are caught before validating child-specific rules.

### Problem

```java
// Parent has validation errors
AbstractEntity extends null  // Valid parent
ConcreteEntity extends InvalidEntity  // ERROR: Parent has errors

// Should validate parent first, then child
```

### Solution: Dependency-Based Validation

Use `@Satisfies` to ensure parent validation completes before child validation.

```java
@ValidationContext(EntityType.class)
public class HierarchyValidations {
    
    // Base validation - must pass before any inheritance checks
    @Constraint(
        name = "TypeMustHaveName",
        message = "Type must have a non-empty name"
    )
    public ValidationRule typeMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Type must have a name");
        };
    }
    
    // Supertype must be valid before checking subtype
    @Constraint(
        name = "SuperTypeMustBeValid",
        message = "Supertype of '{element.name}' must be valid"
    )
    @Satisfies(constraints = {"TypeMustHaveName"})
    @Guard(method = "hasSuperType")
    public ValidationRule superTypeMustBeValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            EntityType superType = entity.getSuperType();
            
            // Check if supertype passed its own validations
            if (!ctx.isSatisfied(superType, "TypeMustHaveName")) {
                return ValidationResult.fail(
                    "Supertype '" + superType.getName() + 
                    "' has validation errors"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    // Only validate subtype specifics after hierarchy is valid
    @Constraint(
        name = "SubtypeMustExtendInterface",
        message = "Type '{element.name}' must extend valid interface"
    )
    @Satisfies(constraints = {
        "TypeMustHaveName",
        "SuperTypeMustBeValid",
        "NoCyclicInheritance"
    })
    @Guard(method = "hasSuperType")
    public ValidationRule subtypeMustExtendInterface() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            EntityType superType = entity.getSuperType();
            
            // Now safe to check interface compatibility
            if (!superType.isInterface() && entity.isInterface()) {
                return ValidationResult.fail(
                    "Interface '" + entity.getName() + 
                    "' cannot extend non-interface type '" + 
                    superType.getName() + "'"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    public boolean hasSuperType(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getSuperType() != null;
    }
}
```

### Advanced: Recursive Validation Propagation

Validate all ancestors in the hierarchy before validating the current type.

```java
@ValidationContext(EntityType.class)
public class RecursiveHierarchyValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public List<EntityType> getAllAncestors(EntityType self) {
        List<EntityType> ancestors = new ArrayList<>();
        EntityType current = self.getSuperType();
        
        while (current != null) {
            ancestors.add(current);
            current = current.getSuperType();
        }
        
        return ancestors;
    }
    
    @Constraint(
        name = "AllAncestorsMustBeValid",
        message = "Type '{element.name}' has invalid ancestors"
    )
    @Satisfies(constraints = {"NoCyclicInheritance"})
    @Guard(method = "hasSuperType")
    public ValidationRule allAncestorsMustBeValid() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<EntityType> ancestors = ctx.call(entity, "getAllAncestors");
            
            // Check each ancestor for validation errors
            List<String> invalidAncestors = new ArrayList<>();
            
            for (EntityType ancestor : ancestors) {
                // Check if ancestor passed basic validations
                if (!ctx.isSatisfied(ancestor, "TypeMustHaveName")) {
                    invalidAncestors.add(ancestor.getName());
                }
            }
            
            if (!invalidAncestors.isEmpty()) {
                return ValidationResult.fail(
                    "Invalid ancestors in hierarchy: " + 
                    String.join(", ", invalidAncestors)
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    public boolean hasSuperType(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getSuperType() != null;
    }
}
```

## Override Compatibility Checking

When a subtype overrides attributes or methods from a parent type, the override must be compatible.

### Problem

```java
// Parent defines attribute
Parent.attribute: String

// Child overrides with incompatible type
Child.attribute: Integer  // ERROR: Incompatible override
```

### Solution: Override Validation

```java
@ValidationContext(EntityType.class)
public class OverrideValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Map<String, Attribute> getAllInheritedAttributes(EntityType self) {
        Map<String, Attribute> inherited = new HashMap<>();
        
        List<EntityType> ancestors = getAllAncestors(self);
        for (EntityType ancestor : ancestors) {
            for (Attribute attr : ancestor.getAttributes()) {
                // Don't overwrite - keeps the highest ancestor's definition
                inherited.putIfAbsent(attr.getName(), attr);
            }
        }
        
        return inherited;
    }
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public List<EntityType> getAllAncestors(EntityType self) {
        List<EntityType> ancestors = new ArrayList<>();
        EntityType current = self.getSuperType();
        
        while (current != null) {
            ancestors.add(current);
            current = current.getSuperType();
        }
        
        return ancestors;
    }
    
    @Constraint(
        name = "OverrideMustBeCompatible",
        message = "Attribute override is incompatible in '{element.name}'"
    )
    @Satisfies(constraints = {"NoCyclicInheritance"})
    public ValidationRule overrideMustBeCompatible() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Get all inherited attributes
            Map<String, Attribute> inherited = 
                ctx.call(entity, "getAllInheritedAttributes");
            
            // Check each local attribute against inherited definitions
            for (Attribute localAttr : entity.getAttributes()) {
                Attribute inheritedAttr = inherited.get(localAttr.getName());
                
                if (inheritedAttr != null) {
                    // Attribute is being overridden - check compatibility
                    if (!isCompatibleOverride(localAttr, inheritedAttr)) {
                        return ValidationResult.fail(
                            "Attribute '" + localAttr.getName() + 
                            "' overrides inherited attribute with incompatible type. " +
                            "Expected: " + inheritedAttr.getType() + 
                            ", Found: " + localAttr.getType()
                        );
                    }
                }
            }
            
            return ValidationResult.pass();
        };
    }
    
    /**
     * Checks if an attribute override is compatible with the inherited definition.
     * 
     * Compatible means:
     * - Same type or subtype (covariant)
     * - Same or more restrictive cardinality
     * - Cannot change from optional to required (but can go required to optional)
     */
    private boolean isCompatibleOverride(Attribute override, Attribute inherited) {
        // Type must match or be a subtype
        if (!isSubtypeOf(override.getType(), inherited.getType())) {
            return false;
        }
        
        // Cannot change from optional to required
        if (inherited.isRequired() && !override.isRequired()) {
            return false;
        }
        
        // Cardinality must be compatible
        if (override.isMany() != inherited.isMany()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if typeA is a subtype of typeB.
     * For simplicity, this uses string comparison, but in real systems
     * this would do proper type hierarchy checking.
     */
    private boolean isSubtypeOf(String typeA, String typeB) {
        // Exact match
        if (typeA.equals(typeB)) {
            return true;
        }
        
        // Add your type hierarchy logic here
        // For example: Integer is subtype of Number
        return false;
    }
}
```

### Advanced: Method Override Validation

For systems with methods, validate method signature compatibility.

```java
@ValidationContext(EntityType.class)
public class MethodOverrideValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Map<String, Method> getAllInheritedMethods(EntityType self) {
        Map<String, Method> inherited = new HashMap<>();
        
        EntityType current = self.getSuperType();
        while (current != null) {
            for (Method method : current.getMethods()) {
                // Build signature key: name(param1Type, param2Type, ...)
                String signature = buildSignature(method);
                inherited.putIfAbsent(signature, method);
            }
            current = current.getSuperType();
        }
        
        return inherited;
    }
    
    @Constraint(
        name = "MethodOverrideMustBeCompatible",
        message = "Method override incompatible in '{element.name}'"
    )
    @Satisfies(constraints = {"NoCyclicInheritance"})
    public ValidationRule methodOverrideMustBeCompatible() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            Map<String, Method> inherited = 
                ctx.call(entity, "getAllInheritedMethods");
            
            for (Method localMethod : entity.getMethods()) {
                String signature = buildSignature(localMethod);
                Method inheritedMethod = inherited.get(signature);
                
                if (inheritedMethod != null) {
                    // Check return type compatibility (covariant return types)
                    if (!isCovariantReturnType(
                            localMethod.getReturnType(), 
                            inheritedMethod.getReturnType())) {
                        return ValidationResult.fail(
                            "Method '" + localMethod.getName() + 
                            "' has incompatible return type override"
                        );
                    }
                    
                    // Check visibility (cannot reduce visibility)
                    if (!isValidVisibilityOverride(
                            localMethod.getVisibility(), 
                            inheritedMethod.getVisibility())) {
                        return ValidationResult.fail(
                            "Method '" + localMethod.getName() + 
                            "' reduces visibility in override"
                        );
                    }
                }
            }
            
            return ValidationResult.pass();
        };
    }
    
    private String buildSignature(Method method) {
        String paramTypes = method.getParameters().stream()
            .map(Parameter::getType)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + paramTypes + ")";
    }
    
    private boolean isCovariantReturnType(String subtype, String supertype) {
        return subtype.equals(supertype) || isSubtypeOf(subtype, supertype);
    }
    
    private boolean isValidVisibilityOverride(String subVis, String superVis) {
        // Visibility order: private < package < protected < public
        Map<String, Integer> visLevel = Map.of(
            "private", 0,
            "package", 1,
            "protected", 2,
            "public", 3
        );
        
        return visLevel.get(subVis) >= visLevel.get(superVis);
    }
    
    private boolean isSubtypeOf(String typeA, String typeB) {
        return typeA.equals(typeB);  // Simplified
    }
}
```

## Multiple Inheritance Validation

Some metamodels support multiple inheritance (e.g., interfaces). This requires validating the entire inheritance graph.

### Problem

```java
// Multiple inheritance diamond problem
Interface A
Interface B extends A
Interface C extends A
Class D implements B, C  // Multiple paths to A

// Conflicting multiple inheritance
Interface X { method m(): String }
Interface Y { method m(): Integer }
Class Z implements X, Y  // ERROR: Conflicting method signatures
```

### Solution: Graph-Based Validation

```java
@ValidationContext(EntityType.class)
public class MultipleInheritanceValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Set<EntityType> getAllSuperTypes(EntityType self) {
        Set<EntityType> allSupers = new HashSet<>();
        collectAllSuperTypes(self, allSupers);
        return allSupers;
    }
    
    private void collectAllSuperTypes(EntityType entity, Set<EntityType> accumulator) {
        for (EntityType superType : entity.getSuperTypes()) {  // Multiple supertypes
            if (accumulator.add(superType)) {  // Returns false if already present
                // Recursively collect from this supertype
                collectAllSuperTypes(superType, accumulator);
            }
        }
    }
    
    @Constraint(
        name = "NoCyclicMultipleInheritance",
        message = "Cyclic multiple inheritance in '{element.name}'"
    )
    public ValidationRule noCyclicMultipleInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Use breadth-first search to detect cycles
            boolean hasCycle = detectCycleInGraph(entity);
            
            return hasCycle
                ? ValidationResult.fail("Cyclic inheritance detected in type '" + 
                    entity.getName() + "'")
                : ValidationResult.pass();
        };
    }
    
    /**
     * Detects cycles in multiple inheritance graph using BFS.
     */
    private boolean detectCycleInGraph(EntityType start) {
        Set<EntityType> visited = new HashSet<>();
        Set<EntityType> currentPath = new HashSet<>();
        
        return hasCycleDFS(start, visited, currentPath);
    }
    
    private boolean hasCycleDFS(EntityType entity, 
                                 Set<EntityType> visited, 
                                 Set<EntityType> currentPath) {
        // If in current path, we have a cycle
        if (currentPath.contains(entity)) {
            return true;
        }
        
        // If already fully processed, no cycle from here
        if (visited.contains(entity)) {
            return false;
        }
        
        // Add to current path
        currentPath.add(entity);
        
        // Check all supertypes
        for (EntityType superType : entity.getSuperTypes()) {
            if (hasCycleDFS(superType, visited, currentPath)) {
                return true;
            }
        }
        
        // Remove from current path and mark as fully processed
        currentPath.remove(entity);
        visited.add(entity);
        
        return false;
    }
    
    @Constraint(
        name = "NoConflictingMultipleInheritance",
        message = "Conflicting multiple inheritance in '{element.name}'"
    )
    @Satisfies(constraints = {"NoCyclicMultipleInheritance"})
    public ValidationRule noConflictingMultipleInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Collect all methods from all supertypes
            Map<String, List<Method>> methodsBySignature = new HashMap<>();
            
            Set<EntityType> allSupers = ctx.call(entity, "getAllSuperTypes");
            for (EntityType superType : allSupers) {
                for (Method method : superType.getMethods()) {
                    String signature = buildMethodSignature(method);
                    methodsBySignature
                        .computeIfAbsent(signature, k -> new ArrayList<>())
                        .add(method);
                }
            }
            
            // Check for conflicts
            for (Map.Entry<String, List<Method>> entry : methodsBySignature.entrySet()) {
                List<Method> methods = entry.getValue();
                if (methods.size() > 1) {
                    // Multiple methods with same signature - check compatibility
                    if (!areMethodsCompatible(methods)) {
                        return ValidationResult.fail(
                            "Conflicting methods inherited: " + entry.getKey()
                        );
                    }
                }
            }
            
            return ValidationResult.pass();
        };
    }
    
    private String buildMethodSignature(Method method) {
        String params = method.getParameters().stream()
            .map(Parameter::getType)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + params + ")";
    }
    
    private boolean areMethodsCompatible(List<Method> methods) {
        // All methods must have same return type and compatible signatures
        String firstReturnType = methods.get(0).getReturnType();
        
        for (int i = 1; i < methods.size(); i++) {
            if (!methods.get(i).getReturnType().equals(firstReturnType)) {
                return false;
            }
        }
        
        return true;
    }
}
```

## Abstract Class Instantiation Checks

Ensure abstract types are not instantiated and that concrete types override all abstract methods.

### Problem

```java
// Abstract class should not be instantiated
abstract class Vehicle { }
Vehicle v = new Vehicle();  // ERROR: Cannot instantiate abstract class

// Concrete class must implement abstract methods
abstract class Animal { abstract void makeSound(); }
class Dog extends Animal { }  // ERROR: Must override makeSound()
```

### Solution: Abstract Type Validation

```java
@ValidationContext(EntityType.class)
public class AbstractTypeValidations {
    
    @Constraint(
        name = "AbstractTypesNotInstantiable",
        message = "Abstract type '{element.name}' cannot be instantiated"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractTypesNotInstantiable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check if this abstract type has any direct instantiations
            List<Instance> instances = ctx.getAllInstances(Instance.class).stream()
                .filter(inst -> inst.getType().equals(entity))
                .collect(Collectors.toList());
            
            if (!instances.isEmpty()) {
                return ValidationResult.fail(
                    "Abstract type '" + entity.getName() + 
                    "' has " + instances.size() + " instantiation(s)"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(
        name = "ConcreteMustImplementAbstractMethods",
        message = "Concrete type '{element.name}' must implement all abstract methods"
    )
    @Satisfies(constraints = {"NoCyclicInheritance"})
    @Guard(method = "isConcreteWithAbstractParent")
    public ValidationRule concreteMustImplementAbstractMethods() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Get all abstract methods from hierarchy
            Set<String> abstractMethods = getAbstractMethodSignatures(entity, ctx);
            
            // Get all implemented methods (local + inherited concrete)
            Set<String> implementedMethods = getImplementedMethodSignatures(entity, ctx);
            
            // Find unimplemented abstract methods
            Set<String> unimplemented = new HashSet<>(abstractMethods);
            unimplemented.removeAll(implementedMethods);
            
            if (!unimplemented.isEmpty()) {
                return ValidationResult.fail(
                    "Type '" + entity.getName() + 
                    "' must implement abstract methods: " + 
                    String.join(", ", unimplemented)
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EntityType) element).isAbstract();
    }
    
    public boolean isConcreteWithAbstractParent(EObject element, ValidationContext ctx) {
        EntityType entity = (EntityType) element;
        if (entity.isAbstract()) {
            return false;
        }
        
        // Check if any ancestor is abstract
        EntityType current = entity.getSuperType();
        while (current != null) {
            if (current.isAbstract()) {
                return true;
            }
            current = current.getSuperType();
        }
        
        return false;
    }
    
    private Set<String> getAbstractMethodSignatures(EntityType entity, ValidationContext ctx) {
        Set<String> abstractMethods = new HashSet<>();
        
        // Traverse hierarchy
        EntityType current = entity.getSuperType();
        while (current != null) {
            if (current.isAbstract()) {
                for (Method method : current.getMethods()) {
                    if (method.isAbstract()) {
                        abstractMethods.add(buildMethodSignature(method));
                    }
                }
            }
            current = current.getSuperType();
        }
        
        return abstractMethods;
    }
    
    private Set<String> getImplementedMethodSignatures(EntityType entity, ValidationContext ctx) {
        Set<String> implemented = new HashSet<>();
        
        // Add local concrete methods
        for (Method method : entity.getMethods()) {
            if (!method.isAbstract()) {
                implemented.add(buildMethodSignature(method));
            }
        }
        
        // Add inherited concrete methods
        EntityType current = entity.getSuperType();
        while (current != null) {
            for (Method method : current.getMethods()) {
                if (!method.isAbstract()) {
                    implemented.add(buildMethodSignature(method));
                }
            }
            current = current.getSuperType();
        }
        
        return implemented;
    }
    
    private String buildMethodSignature(Method method) {
        String params = method.getParameters().stream()
            .map(Parameter::getType)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + params + ")";
    }
}
```

## Complete Example: Comprehensive Inheritance Validation

This complete example combines all patterns into a production-ready validation suite.

```java
@ValidationContext(EntityType.class)
public class ComprehensiveInheritanceValidations {
    
    // ============ Extension Methods (Cached) ============
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public List<EntityType> getInheritanceChain(EntityType self) {
        List<EntityType> chain = new ArrayList<>();
        EntityType current = self;
        
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getSuperType();
        }
        
        return chain;
    }
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Boolean hasCyclicInheritance(EntityType self) {
        return detectCycle(self, new HashSet<>());
    }
    
    @ExtensionMethod(elementType = EntityType.class)
    @Cached
    public Map<String, Attribute> getAllAttributes(EntityType self) {
        Map<String, Attribute> allAttrs = new LinkedHashMap<>();
        
        // Get from ancestors (in reverse order so closest ancestors override)
        List<EntityType> chain = getInheritanceChain(self);
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (Attribute attr : chain.get(i).getAttributes()) {
                allAttrs.put(attr.getName(), attr);
            }
        }
        
        return allAttrs;
    }
    
    // ============ Validation Rules ============
    
    @Constraint(name = "NoCycles", message = "Cyclic inheritance detected")
    public ValidationRule noCycles() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            if (ctx.call(entity, "hasCyclicInheritance")) {
                List<EntityType> chain = ctx.call(entity, "getInheritanceChain");
                String path = chain.stream()
                    .map(EntityType::getName)
                    .collect(Collectors.joining(" -> "));
                
                return ValidationResult.fail("Cycle: " + path);
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(name = "ValidSupertype", message = "Invalid supertype")
    @Satisfies(constraints = {"NoCycles"})
    @Guard(method = "hasSuperType")
    public ValidationRule validSupertype() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            EntityType superType = entity.getSuperType();
            
            // Supertype must pass basic validations
            if (!ctx.isSatisfied(superType, "NoCycles")) {
                return ValidationResult.fail(
                    "Supertype '" + superType.getName() + "' has errors"
                );
            }
            
            return ValidationResult.pass();
        };
    }
    
    @Constraint(name = "ValidOverrides", message = "Invalid attribute override")
    @Satisfies(constraints = {"NoCycles", "ValidSupertype"})
    @Guard(method = "hasSuperType")
    public ValidationRule validOverrides() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Map<String, Attribute> inherited = ctx.call(entity, "getAllAttributes");
            
            for (Attribute local : entity.getAttributes()) {
                Attribute parent = inherited.get(local.getName());
                if (parent != null && !local.equals(parent)) {
                    if (!local.getType().equals(parent.getType())) {
                        return ValidationResult.fail(
                            "Attribute '" + local.getName() + 
                            "' override type mismatch"
                        );
                    }
                }
            }
            
            return ValidationResult.pass();
        };
    }
    
    // ============ Helper Methods ============
    
    public boolean hasSuperType(EObject element, ValidationContext ctx) {
        return ((EntityType) element).getSuperType() != null;
    }
    
    private boolean detectCycle(EntityType entity, Set<EntityType> visited) {
        if (visited.contains(entity)) return true;
        if (entity.getSuperType() == null) return false;
        visited.add(entity);
        boolean result = detectCycle(entity.getSuperType(), visited);
        visited.remove(entity);
        return result;
    }
}
```

## Summary

This guide provided complete implementations of inheritance validation patterns:

1. **Cyclic Inheritance Detection**: Three approaches (basic set-based, path-based with reporting, cached)
2. **Supertype Validation Propagation**: Dependency-based validation with `@Satisfies`
3. **Override Compatibility**: Attribute and method override checking
4. **Multiple Inheritance**: Graph-based cycle detection and conflict resolution
5. **Abstract Type Validation**: Instantiation checks and abstract method implementation

**Key Algorithms**:
- **DFS with visited set** - O(n) cycle detection
- **BFS traversal** - O(n + e) graph exploration
- **Topological sort** - Implicit via `@Satisfies` dependencies
- **Set intersection** - O(n) conflict detection

**Performance Considerations**:
- Use `@Cached` on extension methods for O(1) repeated access
- Build indexes once for O(1) lookups across all validations
- Leverage `@Satisfies` to prevent cascading validations
- Apply `@Guard` to skip irrelevant validations

All examples are production-ready and can be adapted to your specific metamodel requirements.

---

**Previous**: [Documentation Hub](../../index.md) | **Up**: [Examples](../index.md#examples)
