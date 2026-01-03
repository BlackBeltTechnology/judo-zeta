# Design: Add Resource Alias Support

**Change ID**: `add-resource-alias-support`  
**Status**: Implemented

## Overview

This document describes the design for adding resource alias support to the Zeta transformation and validation frameworks.

## Architecture

### Current State

```
TransformationContext
├── sourceResourceSet      (single source)
├── targetResourceSet      (single target)
├── getAllSource(Class<T>) (queries sourceResourceSet)
└── createTarget(Class<T>) (creates in targetResourceSet)

ValidationContext
├── resourceSet            (single resource)
└── getAllInstances(Class<T>) (queries resourceSet)
```

### Target State

```
TransformationContext
├── sourceResourceSet      (backward compat)
├── targetResourceSet      (backward compat)
├── resourceRegistry       (Map<String, ResourceSet>)
│   ├── "source" → sourceResourceSet (auto-registered)
│   ├── "target" → targetResourceSet (auto-registered)
│   ├── "mapping" → mappingResourceSet (user-registered)
│   └── "rules" → rulesResourceSet (user-registered)
├── getAllSource(Class<T>) (unchanged - uses "source")
├── create(Class<T>)       (NEW - creates uncontained element)
├── all(alias, Class<T>)   (NEW - queries aliased resource)
├── registerResource(alias, rs) (NEW)
└── getResource(alias)     (NEW)

ValidationContext
├── resourceSet            (backward compat)
├── resourceRegistry       (Map<String, ResourceSet>)
│   ├── "source" → resourceSet (auto-registered)
│   └── ... (user-registered)
├── getAllInstances(Class<T>) (unchanged)
├── all(alias, Class<T>)   (NEW)
├── registerResource(alias, rs) (NEW)
└── getResource(alias)     (NEW)
```

## Design Decisions

### AD-1: Registry vs Constructor Parameters

**Decision**: Use a mutable registry instead of constructor parameters

**Rationale**:
- Flexible: Resources can be registered after construction
- Pre-execution hooks can register resources
- No breaking changes to existing constructors
- Matches ETL's dynamic model binding pattern

**Alternative Considered**: Pass all resources in constructor
- Rejected: Would break existing code and limit flexibility

### AD-2: Default Alias Names

**Decision**: Use "source" and "target" as default alias names

**Rationale**:
- Intuitive naming matching transformation concepts
- Backward compatible with existing `getAllSource()` and `createTarget()` methods
- Matches ETL conventions

### AD-3: Error Handling for Unknown Aliases

**Decision**: Throw `IllegalArgumentException` with clear message

**Rationale**:
- Fail-fast behavior catches configuration errors early
- Clear error message helps debugging
- Consistent with Java conventions

**Example**:
```java
public ResourceSet getResource(String alias) {
    ResourceSet rs = resourceRegistry.get(alias);
    if (rs == null) {
        throw new IllegalArgumentException(
            "Unknown resource alias: '" + alias + "'. " +
            "Available aliases: " + resourceRegistry.keySet()
        );
    }
    return rs;
}
```

### AD-4: No EPackage Registry

**Decision**: Do NOT maintain a separate EPackage registry. Rules handle containment themselves.

**Rationale**:
- Matches ETL behavior where created elements are not automatically added to resources
- Rules explicitly add elements to containment references (e.g., `parent.getChildren().add(child)`)
- Elements without containment appear in model root
- Simpler implementation without package tracking

**ETL Pattern**:
```etl
rule EntityType2Table
    transform e : ASM!EntityType
    to t : RDBMS!Table {
    -- t is created but NOT automatically added to resource
    -- Rule must explicitly set containment:
    e.eContainer().equivalent().tables.add(t);
}
```

**Java Equivalent**:
```java
@TransformRule(name = "EntityType2Table")
@Source(alias = "asm", type = EntityType.class)
@Target(alias = "rdbms", type = Table.class)
public TransformFunction<EntityType, Table> entityType2Table() {
    return (source, ctx) -> {
        Table table = ctx.create(Table.class);  // Creates but doesn't add to resource
        // Rule explicitly sets containment:
        Schema schema = ctx.equivalent(source.eContainer(), Schema.class);
        schema.getTables().add(table);
        return table;
    };
}
```

### AD-5: No Reserved Aliases

**Decision**: Allow overwriting "source" and "target" aliases

**Rationale**:
- Maximum flexibility for advanced use cases
- Users can reassign defaults if needed
- No artificial constraints

### AD-6: @Transform and @To Annotations for Per-Type Aliases

**Decision**: Introduce `@Transform` and `@To` repeatable annotations for defining source/target types with their aliases

**Rationale**:
- Each source type can have its own alias
- Each target type can have its own alias
- More expressive than array attributes
- Backward compatible: `sourceTypes`/`targetTypes` still work with default aliases
- Names mirror ETL syntax: `transform ... to ...`

**New Annotations**:
```java
@Repeatable(Transforms.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Transform {
    String alias() default "source";
    Class<? extends EObject> type();
}

@Repeatable(Tos.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface To {
    String alias() default "target";
    Class<? extends EObject> type();
}
```

**Usage**:
```java
// Simple case - single source/target with default aliases
@TransformRule(name = "Entity2Table")
@Transform(type = EntityType.class)
@To(type = Table.class)
public TransformFunction<EntityType, Table> entity2Table() { ... }

// Multi-model case - different aliases per type
@TransformRule(name = "ApplyMapping")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<Object[], Table> applyMapping() { ... }

// Backward compatible - sourceTypes still works
@TransformRule(name = "OldStyle", sourceTypes = {EntityType.class})
public TransformFunction<EntityType, Table> oldStyle() { ... }
```

### AD-7: create() vs createTarget()

**Decision**: Add `create(Class<T>)` method that creates elements without adding to any resource

**Rationale**:
- Matches ETL's `new Target!Type` behavior
- Element is created but not contained anywhere
- Rule must explicitly set containment
- Existing `createTarget(Class<T>)` can remain for backward compatibility (adds to target resource root)

```java
// New method - creates without containment
public <T extends EObject> T create(Class<T> type);

// Existing method - creates and adds to target resource root (backward compat)
public <T extends EObject> T createTarget(Class<T> targetType);
```

## Component Changes

### 1. New Annotations (zeta-annotations)

**@Transform**:
```java
@Repeatable(Transforms.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Transform {
    String alias() default "source";
    Class<? extends EObject> type();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Transforms {
    Transform[] value();
}
```

**@To**:
```java
@Repeatable(Tos.class)
@java.lang.annotation.Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface To {
    String alias() default "target";
    Class<? extends EObject> type();
}

@java.lang.annotation.Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tos {
    To[] value();
}
```

### 2. TransformationContext

**New Fields**:
```java
private final Map<String, ResourceSet> resourceRegistry = new ConcurrentHashMap<>();
```

**New Methods**:
```java
public void registerResource(String alias, ResourceSet resourceSet);
public ResourceSet getResource(String alias);
public <T extends EObject> Collection<T> all(String alias, Class<T> type);
public <T extends EObject> T create(Class<T> type);  // Creates without containment
```

**Constructor Changes**:
```java
public TransformationContext(...) {
    // ... existing initialization ...
    resourceRegistry.put("source", sourceResourceSet);
    resourceRegistry.put("target", targetResourceSet);
}
```

### 3. ValidationContext

**New Fields**:
```java
private final Map<String, ResourceSet> resourceRegistry = new ConcurrentHashMap<>();
```

**New Methods**:
```java
public void registerResource(String alias, ResourceSet resourceSet);
public ResourceSet getResource(String alias);
public <T extends EObject> Collection<T> all(String alias, Class<T> type);
```

**Constructor Changes**:
```java
public ValidationContext(...) {
    // ... existing initialization ...
    resourceRegistry.put("source", resourceSet);
}
```

### 4. @Constraint and @Critique Annotations

**Changes**:
```java
@Constraint
public @interface Constraint {
    String name();
    String description() default "";
    String message() default "";
    
    // NEW
    String resourceAlias() default "source";
}

@Critique
public @interface Critique {
    String name();
    String description() default "";
    String message() default "";
    
    // NEW
    String resourceAlias() default "source";
}
```

### 5. TransformRuleDescriptor

**Changes**:
```java
public class TransformRuleDescriptor {
    // ... existing fields ...
    
    // NEW - list of transform definitions (alias + type pairs)
    private final List<TransformDefinition> transforms;
    // NEW - list of to definitions (alias + type pairs)  
    private final List<ToDefinition> tos;
    
    public static class TransformDefinition {
        private final String alias;
        private final Class<? extends EObject> type;
    }
    
    public static class ToDefinition {
        private final String alias;
        private final Class<? extends EObject> type;
    }
}
```

### 6. TransformationExecutor

**Changes to element collection**:
```java
// For each transform definition, collect elements from the appropriate alias
for (TransformDefinition transform : descriptor.getTransforms()) {
    Collection<?> elements = context.all(transform.getAlias(), transform.getType());
    // ... process elements
}
```

### 7. ValidatorDescriptor

**Changes**:
```java
public class ValidatorDescriptor {
    // ... existing fields ...
    
    // NEW
    private final String resourceAlias;
    
    public String getResourceAlias() { return resourceAlias; }
}
```

### 8. ValidationExecutor

**Changes to element collection**:
```java
String alias = descriptor.getResourceAlias();
Collection<?> elements = context.all(alias, descriptor.getTargetType());
```

## Thread Safety

All registry operations use `ConcurrentHashMap`:
- Thread-safe reads during parallel transformation/validation
- Registration typically happens in single-threaded setup phase
- No synchronization needed for reads after setup

## Backward Compatibility

| Existing API | Behavior After Change |
|--------------|----------------------|
| `getAllSource(Class<T>)` | Unchanged - delegates to `all("source", Class<T>)` |
| `createTarget(Class<T>)` | Unchanged - creates and adds to target resource root |
| `getAllInstances(Class<T>)` | Unchanged - delegates to `all("source", Class<T>)` |
| `@TransformRule(sourceTypes=...)` | Works - uses default "source" alias |
| `@TransformRule(targetTypes=...)` | Works - uses default "target" alias |
| `@Constraint` without alias | Works - defaults to "source" |

## Error Messages

Clear error messages for common mistakes:

```
Unknown resource alias: 'mappng'. Available aliases: [source, target, mapping, rules]
```

## Testing Strategy

1. **Unit Tests**: Test registry operations, alias resolution, error handling
2. **Integration Tests**: Test multi-model transformations with @Source/@Target annotations
3. **Backward Compatibility Tests**: Verify existing tests pass unchanged
4. **Parallel Execution Tests**: Verify thread-safety of registry access
