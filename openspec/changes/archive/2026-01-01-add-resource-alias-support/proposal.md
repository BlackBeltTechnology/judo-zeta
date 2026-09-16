# Proposal: Add Resource Alias Support

**Change ID**: `add-resource-alias-support`  
**Status**: Implemented  
**Created**: 2025-12-09  
**Implemented**: 2025-12-09  
**Type**: Feature

## Summary

Add resource alias support to Zeta transformation and validation frameworks, enabling rules to specify which registered resources to use via `@Source` and `@TargetModel` annotations. This mirrors the ETL `Model!Type` pattern where models are registered with names and rules specify which model to operate on.

## Motivation

The judo-tatami transformations (e.g., ASM2RDBMS) often need to access multiple models beyond just source and target:
- **Mapping models** - Type mappings, relation mappings
- **Rules models** - FK rules, junction table rules
- **Reference models** - Lookup data from other transformations

Currently in ETL, this is handled via the `Model!Type` syntax:
```etl
rule EntityType2Table
    transform e : ASM!EntityType    -- ASM model alias
    to t : RDBMS!Table {            -- RDBMS model alias
    
    var mapping = MAPPING!TypeMapping.all.selectOne(m | m.asmType = e);
}
```

The current Zeta implementation only supports a single source and single target ResourceSet, requiring workarounds like loading additional models via `@PreExecution` hooks and storing them in context attributes.

## Goals

1. **Resource Registry**: Add a registry to TransformationContext and ValidationContext for registering ResourceSets with aliases
2. **@Transform Annotation**: New repeatable annotation for defining source types with their aliases
3. **@To Annotation**: New repeatable annotation for defining target types with their aliases
4. **Validation Support**: Add `resourceAlias` attribute to `@Constraint` and `@Critique` annotations
5. **Backward Compatibility**: Default aliases "source" and "target" maintain existing behavior; `sourceTypes`/`targetTypes` still work
6. **ETL-like Element Creation**: Add `create(Class)` method that creates elements without containment (matching ETL behavior)

## Non-Goals

- Full ETL `Model!Type` syntax parsing (we use annotations instead)
- Dynamic model loading (models must be pre-registered)
- Cross-transformation model sharing (each transformation has its own registry)
- EPackage registry per alias (rules handle containment explicitly)

## Scope

### In Scope

- `TransformationContext.registerResource(alias, resourceSet)`
- `TransformationContext.getResource(alias)`
- `TransformationContext.all(alias, Class<T>)` - get all elements of type from aliased resource
- `TransformationContext.create(Class<T>)` - create element without containment (ETL-like)
- `ValidationContext.registerResource(alias, resourceSet)`
- `ValidationContext.getResource(alias)`
- `ValidationContext.all(alias, Class<T>)`
- `@Transform(alias="...", type=...)` - repeatable annotation for source types
- `@To(alias="...", type=...)` - repeatable annotation for target types
- `@Constraint(resourceAlias="...")`
- `@Critique(resourceAlias="...")`
- TransformationExecutor changes to use aliases from @Transform annotations
- ValidationExecutor changes to use aliases from validator annotations
- Tests and documentation

### Out of Scope

- IDE tooling for alias completion
- Runtime alias validation (invalid alias throws clear exception)
- Automatic model discovery

## Proposed Solution

### API Design

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

**TransformationContext additions**:
```java
public class TransformationContext {
    private final Map<String, ResourceSet> resourceRegistry = new ConcurrentHashMap<>();
    
    // Constructor registers default aliases
    public TransformationContext(...) {
        resourceRegistry.put("source", sourceResourceSet);
        resourceRegistry.put("target", targetResourceSet);
    }
    
    // Register additional resource with alias
    public void registerResource(String alias, ResourceSet resourceSet);
    
    // Get resource by alias
    public ResourceSet getResource(String alias);
    
    // Get all elements of type from aliased resource
    public <T extends EObject> Collection<T> all(String alias, Class<T> type);
    
    // Create element WITHOUT adding to any resource (ETL-like behavior)
    public <T extends EObject> T create(Class<T> type);
}
```

**ValidationContext additions**:
```java
public class ValidationContext {
    private final Map<String, ResourceSet> resourceRegistry = new ConcurrentHashMap<>();
    
    public void registerResource(String alias, ResourceSet resourceSet);
    public ResourceSet getResource(String alias);
    public <T extends EObject> Collection<T> all(String alias, Class<T> type);
}
```

### Usage Example

```java
// Setup - register all models with aliases
TransformationContext ctx = new TransformationContext(
    modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry
);
ctx.registerResource("mapping", mappingResourceSet);
ctx.registerResource("rules", rulesResourceSet);

// Multi-source transformation rule
@TransformRule(name = "ApplyMapping")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<Object[], Table> applyMapping() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        
        // Create element without containment (ETL-like)
        Table table = ctx.create(Table.class);
        table.setName(entity.getName());
        table.setRdbmsType(mapping.getRdbmsType());
        
        // Explicitly set containment
        Schema schema = ctx.equivalent(entity.eContainer(), Schema.class);
        schema.getTables().add(table);
        
        return table;
    };
}

// Simple single-source rule (backward compatible style)
@TransformRule(name = "Entity2Table")
@Transform(type = EntityType.class)
@To(type = Table.class)
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.create(Table.class);
        table.setName(source.getName());
        // ... set containment explicitly
        return table;
    };
}

// Validation with custom alias
@Constraint(name = "ValidEntityName", resourceAlias = "esm")
public ValidationRule<EntityType> validEntityName() {
    return (entity, ctx) -> {
        // Can also access other resources
        Collection<Rule> rules = ctx.all("rules", Rule.class);
        // ...
    };
}
```

## Benefits

1. **Multi-Model Support**: Transformations can access any number of registered models
2. **Per-Type Aliases**: Each source/target type can have its own alias via @Transform/@To
3. **ETL Parity**: Matches ETL's model alias concept and element creation behavior
4. **Type Safety**: Generic methods with Class<T> parameter
5. **Thread Safety**: ConcurrentHashMap for parallel execution
6. **Backward Compatible**: Default aliases and sourceTypes/targetTypes preserve existing behavior

## Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Invalid alias at runtime | Medium | Low | Clear exception message with available aliases |
| Breaking existing code | High | Low | Default aliases and backward compatible attributes |
| Learning curve for new annotations | Low | Medium | Clear documentation and examples |

## Dependencies

- zeta-annotations module (new @Transform, @To annotations; updated @Constraint, @Critique)
- transformation-core module (context and executor changes)
- validation-core module (context and executor changes)

## References

- Epsilon ETL Model Aliasing: https://eclipse.dev/epsilon/doc/etl/
- Current TransformationContext: `transformation-core/src/main/java/.../TransformationContext.java`
- Current ValidationContext: `validation-core/src/main/java/.../ValidationContext.java`

## Testing Requirements

### Annotation Tests

Comprehensive tests for the new `@Transform` and `@To` annotations:

1. **Single @Transform annotation**: Test rule with single source type and default alias
2. **Single @Transform with custom alias**: Test rule with single source type and custom alias
3. **Multiple @Transform annotations**: Test rule with multiple source types from different aliases
4. **Single @To annotation**: Test rule with single target type and default alias
5. **Single @To with custom alias**: Test rule with single target type and custom alias
6. **Multiple @To annotations**: Test rule with multiple target types to different aliases
7. **Combined @Transform and @To**: Test rules using both annotations together
8. **Backward compatibility**: Test that `sourceTypes`/`targetTypes` attributes still work
9. **Annotation reflection**: Verify annotations are correctly extracted via reflection
10. **Registry integration**: Verify TransformationRegistry correctly processes annotations into TransformDefinition/ToDefinition
