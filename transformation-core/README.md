# JUDO Zeta Transformation Core

Annotation-based model transformation framework for EMF metamodels. This module provides a Java replacement for Epsilon Transformation Language (ETL) with compile-time type safety and parallel execution support.

## Features

- **Annotation-based Rules**: Define transformation rules using Java annotations
- **Type-safe Transformations**: Compile-time type checking for source and target types
- **Resource Aliases**: Work with multiple EMF ResourceSets via `@Transform` and `@To` annotations
- **Multi-Source Rules**: Cartesian product execution for rules with multiple source types
- **Lazy Evaluation**: Rules can be marked for on-demand execution via `@Lazy`
- **Rule Inheritance**: Support for abstract rules and inheritance via `@Extends`
- **Greedy Type Matching**: Match source types and all subtypes with `@Greedy`
- **Primary Rules**: Mark preferred transformations with `@Primary`
- **Discriminated Equivalence**: Multiple transformations of the same source element
- **Parallel Execution**: Thread-safe parallel processing for large models with staging infrastructure
- **Transformation Trace**: Automatic source-to-target mapping with JSON export
- **Extension Methods**: Reusable helper methods with caching support
- **Fail-Fast Error Handling**: Immediate abort on first error with detailed context
- **Deterministic Ordering**: Maintains element creation order even in parallel mode

## Quick Start

### 1. Define a Transformation Context

```java
import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.transformation.core.*;

@TransformationContext(source = EntityType.class, target = Table.class)
public class Entity2TableTransformations {

    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            
            // Transform related elements
            for (Attribute attr : entity.getAttributes()) {
                Column column = ctx.equivalent(attr, Column.class);
                table.getColumns().add(column);
            }
            
            return table;
        };
    }

    @TransformRule(name = "Attribute2Column")
    public TransformFunction<Attribute, Column> attribute2Column() {
        return (attr, ctx) -> {
            Column column = ctx.createTarget(Column.class);
            column.setName(attr.getName());
            column.setType(mapType(attr.getType()));
            return column;
        };
    }
}
```

### 2. Execute the Transformation

```java
// Setup
TransformationRegistry registry = new TransformationRegistry();
registry.register(Entity2TableTransformations.class);

ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
ModelProvider modelProvider = new MyModelProvider();

TransformationContext context = new TransformationContext(
    modelProvider,
    sourceResourceSet,
    targetResourceSet,
    extensionRegistry
);
context.setTransformationRegistry(registry);
context.setTargetPackage(TargetPackage.eINSTANCE);

// Execute with builder pattern
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)                    // Enable parallel execution (default: true)
    .parallelThreshold(1000)           // Min elements for parallel (default: 1000)
    .build();

Collection<EObject> sourceElements = modelProvider.getAllContents(sourceResourceSet, EntityType.class);
TransformationResult result = executor.transform(sourceElements);

// Access results
ResourceSet targetModel = result.getTargetResourceSet();
TransformationTrace trace = result.getTrace();
trace.saveToJson(new File("trace.json"));
```

## Annotations Reference

### Context Annotations

| Annotation | Target | Description |
|------------|--------|-------------|
| `@TransformationContext` | Class | Marks a class as containing transformation rules |
| `@TransformRule` | Method | Defines a transformation rule |

### Rule Modifiers

| Annotation | Description |
|------------|-------------|
| `@Lazy` | Rule executes on-demand via `equivalent()` calls |
| `@Abstract` | Rule only executes via `executeParentRule()` |
| `@Primary` | Rule's result takes precedence in `equivalent()` |
| `@Greedy` | Matches source type AND all subtypes |
| `@Extends` | Inherits from parent rules |
| `@Guard` | Conditional execution based on guard method |

### Lifecycle Hooks

| Annotation | Description |
|------------|-------------|
| `@PreExecution` | Method runs before transformation starts |
| `@PostExecution` | Method runs after transformation completes |

## Advanced Features

### Rule Inheritance

```java
@TransformRule(name = "BaseNamedElement")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        return target;
    };
}

@TransformRule(name = "EntityType2Table")
@Extends("BaseNamedElement")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Execute parent rule first
        Table table = ctx.executeParentRule("BaseNamedElement", entity);
        // Add entity-specific logic
        table.setSchema(ctx.equivalent(entity.getNamespace(), Schema.class));
        return table;
    };
}
```

### Greedy Type Matching

```java
@TransformRule(name = "NamedElement2NamedType")
@Greedy  // Matches NamedElement AND all subtypes (EntityType, ActorType, etc.)
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType type = ctx.createTarget(NamedType.class);
        type.setName(source.getName());
        return type;
    };
}
```

### Discriminated Equivalence

```java
@TransformRule(name = "Relation2Operations")
public TransformFunction<Relation, Void> relation2Operations() {
    return (relation, ctx) -> {
        // Create multiple operations from same source
        Operation create = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "create");
        create.setName("create" + relation.getTarget().getName());
        
        Operation update = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "update");
        update.setName("update" + relation.getTarget().getName());
        
        return null;
    };
}
```

### Guards

```java
@TransformRule(name = "AbstractEntity2Table")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2Table() {
    return (entity, ctx) -> {
        // Only executes for abstract entities
        Table table = ctx.createTarget(Table.class);
        table.setAbstract(true);
        return table;
    };
}

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

### Resource Aliases and Multi-Model Transformations

The framework supports working with multiple EMF ResourceSets through resource aliases, similar to ETL's model binding. This enables complex transformation scenarios involving multiple input models.

#### Registering Resources

```java
TransformationContext ctx = new TransformationContext(
    modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);

// Register additional resources with aliases
ctx.registerResource("asm", asmResourceSet);
ctx.registerResource("mapping", mappingResourceSet);
ctx.registerResource("rdbms", rdbmsResourceSet);

// Access registered resources
ResourceSet mapping = ctx.getResource("mapping");
```

#### Using @Transform and @To Annotations

```java
@TransformRule(name = "Entity2Table")
@Transform(alias = "asm", type = EntityType.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.create(Table.class);
        table.setName(entity.getName());
        return table;
    };
}
```

#### Multi-Source Rules (Cartesian Product)

When specifying multiple `@Transform` annotations with different aliases, the executor generates a Cartesian product of all source elements:

```java
@TransformRule(name = "EntityMappingToTable")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public MultiSourceTransformFunction<Table> entityMappingToTable() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        
        // Skip non-matching combinations
        if (!mapping.getSourceTypeName().equals(entity.getName())) {
            return null;
        }
        
        Table table = ctx.create(Table.class);
        table.setName(mapping.getTargetTableName());
        return table;
    };
}
```

For detailed documentation on resource aliases and multi-source transformations, see [Resource Aliases User Guide](../docs/transformation/user-guide/resource-aliases.md).

### Transformation Trace

The framework automatically tracks all source-to-target mappings:

```java
TransformationResult result = executor.transform(sourceElements);
TransformationTrace trace = result.getTrace();

// Export to JSON
trace.saveToJson(new File("trace.json"));

// Or get as string
String json = trace.toJson();
```

JSON output format:
```json
{
  "traceEntries": [
    {
      "ruleName": "EntityType2Table",
      "source": {
        "type": "EntityType",
        "id": "entity-123",
        "name": "Customer"
      },
      "target": {
        "type": "Table",
        "id": "table-456",
        "name": "Customer"
      },
      "primary": true
    }
  ],
  "entryCount": 1,
  "timestamp": 1699123456789
}
```

## Parallel Execution

The transformation framework supports thread-safe parallel execution for large models using a two-phase staging approach.

### How It Works

1. **Phase 1 (Parallel)**: Elements are created and transformed in parallel threads
   - Created elements are staged in a thread-safe queue
   - Element ordering is tracked via atomic sequence numbers
   - XMI IDs are deferred until commit phase

2. **Phase 2 (Sequential)**: Staged elements are committed to the target Resource
   - Elements are sorted by creation sequence for deterministic ordering
   - XMI IDs are applied after elements are added to the Resource
   - Single-threaded to ensure EMF thread-safety

### Configuration

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)                    // Enable parallel (default: true)
    .parallelThreshold(1000)           // Min elements for parallel (default: 1000)
    .chunkSize(100)                    // Elements per work unit (default: 100)
    .build();
```

### Thread-Safety Guidelines

When writing transformation rules that will execute in parallel:

**Safe Operations (DO):**
- Create new target elements via `ctx.createTarget()`
- Set properties on elements you created
- Reference elements obtained via `ctx.equivalent()`
- Read from source elements (source model is read-only)
- Use `ctx.call()` for extension methods

**Unsafe Operations (DON'T):**
- Modify source elements
- Modify target elements created by other rules
- Use shared mutable state between rules
- Store results in non-thread-safe collections

### Example: Thread-Safe Rule

```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Safe: create new target element
        Table table = ctx.createTarget(Table.class);
        
        // Safe: set properties on our created element
        table.setName(entity.getName());
        
        // Safe: get equivalent (thread-safe lazy execution)
        Schema schema = ctx.equivalent(entity.getNamespace(), Schema.class);
        table.setSchema(schema);
        
        // Safe: read from source and transform children
        for (Attribute attr : entity.getAttributes()) {
            Column column = ctx.equivalent(attr, Column.class);
            table.getColumns().add(column);
        }
        
        return table;
    };
}
```

### Error Handling

The transformation uses fail-fast error handling:

```java
try {
    TransformationResult result = executor.transform(sourceElements);
} catch (TransformationException e) {
    // Get context about the failure
    EObject failedElement = e.getFailedElement();
    String ruleName = e.getRuleName();
    Throwable cause = e.getCause();
    
    log.error("Transformation failed in rule '{}': {}", ruleName, cause.getMessage());
}
```

### Executor Reuse

The executor can be reused for multiple transformations:

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .build();

// First transformation - state is automatically reset
TransformationResult result1 = executor.transform(sourceElements1);

// Second transformation
TransformationResult result2 = executor.transform(sourceElements2);
```

### Performance Characteristics

| Metric | Value |
|--------|-------|
| Default parallel threshold | 1000 elements |
| Default chunk size | 100 elements |
| Thread pool | ForkJoinPool (work-stealing) |
| Expected speedup | 2-4x on 8-core CPU |

## ETL to Java Migration

| ETL Concept | Java Equivalent |
|-------------|-----------------|
| `rule Entity2Table transform e: Entity to t: Table` | `@TransformRule(name = "Entity2Table")` |
| `@lazy` | `@Lazy` |
| `@abstract` | `@Abstract` |
| `@primary` | `@Primary` |
| `@greedy` | `@Greedy` |
| `extends ParentRule` | `@Extends("ParentRule")` |
| `guard: e.isAbstract()` | `@Guard(method = "guardMethod")` |
| `e.equivalent()` | `ctx.equivalent(e, TargetType.class)` |
| `e.equivalents()` | `ctx.equivalents(e, TargetType.class)` |
| `pre { ... }` | `@PreExecution` |
| `post { ... }` | `@PostExecution` |
| `rule R transform a: ASM!Entity...` (model binding) | `@Transform(alias = "asm", type = Entity.class)` |
| `rule R ... to t: RDBMS!Table` (model binding) | `@To(alias = "rdbms", type = Table.class)` |
| Multiple sources (Cartesian product) | Multiple `@Transform` annotations + `MultiSourceTransformFunction` |

## Dependencies

- `zeta-common` - Shared utilities (ModelProvider, ExtensionMethodRegistry)
- `zeta-annotations` - All framework annotations
- EMF Ecore - Eclipse Modeling Framework
- Gson - JSON serialization for trace export

## License

Eclipse Public License 2.0
