# JUDO Zeta Transformation Core

Annotation-based model transformation framework for EMF metamodels. This module provides a Java replacement for Epsilon Transformation Language (ETL) with compile-time type safety and parallel execution support.

## Features

- **Annotation-based Rules**: Define transformation rules using Java annotations
- **Type-safe Transformations**: Compile-time type checking for source and target types
- **Lazy Evaluation**: Rules can be marked for on-demand execution via `@Lazy`
- **Rule Inheritance**: Support for abstract rules and inheritance via `@Extends`
- **Greedy Type Matching**: Match source types and all subtypes with `@Greedy`
- **Primary Rules**: Mark preferred transformations with `@Primary`
- **Discriminated Equivalence**: Multiple transformations of the same source element
- **Parallel Execution**: Automatic parallel processing for large models
- **Transformation Trace**: Automatic source-to-target mapping with JSON export
- **Extension Methods**: Reusable helper methods with caching support

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

// Execute
TransformationExecutor executor = new TransformationExecutor(registry, context, true);
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

## Dependencies

- `zeta-common` - Shared utilities (ModelProvider, ExtensionMethodRegistry)
- `zeta-annotations` - All framework annotations
- EMF Ecore - Eclipse Modeling Framework
- Gson - JSON serialization for trace export

## License

Eclipse Public License 2.0
