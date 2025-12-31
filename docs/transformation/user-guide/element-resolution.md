# Element Resolution

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [User Guide](core-concepts.md) > Element Resolution

Element resolution is the core mechanism for finding or creating transformed equivalents of source elements. This guide explains how `equivalent()`, `equivalents()`, and `equivalentDiscriminated()` work.

## Overview

When transforming a model, you often need to reference target elements that correspond to source elements. The transformation framework maintains a cache that tracks these source-to-target mappings.

```mermaid
graph LR
    A[Source Element] -->|equivalent()| B[Resolution Cache]
    B -->|cache hit| C[Return cached target]
    B -->|cache miss| D[Find applicable rule]
    D -->|execute rule| E[Create target]
    E -->|store in cache| B
    E --> C
```

## equivalent() - Single Target Resolution

The `equivalent()` method returns the primary transformed target for a source element:

```java
<T extends EObject> T equivalent(EObject source, Class<T> targetClass);
```

### Basic Usage

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Get transformed equivalent of supertype
        if (entity.getSuperType() != null) {
            Table parentTable = ctx.equivalent(entity.getSuperType(), Table.class);
            table.setParentTable(parentTable);
        }
        
        // Get transformed equivalents of attributes
        for (Attribute attr : entity.getAttributes()) {
            Column column = ctx.equivalent(attr, Column.class);
            table.getColumns().add(column);
        }
        
        return table;
    };
}
```

### Cache Behavior

1. **First call**: Executes the applicable transformation rule, creates target, stores in cache
2. **Subsequent calls**: Returns cached target immediately (no re-execution)

```java
// First call - executes Attribute2Column rule
Column col1 = ctx.equivalent(attr, Column.class);

// Second call - returns cached Column (same instance)
Column col2 = ctx.equivalent(attr, Column.class);

assert col1 == col2;  // Same object reference
```

### XMI ID-based Lookup (ETL Semantics)

When `useStructuredIds` is enabled (default), `equivalent()` uses XMI ID-based lookup before executing lazy rules. This matches ETL semantics where equivalent lookups find elements by their XMI IDs.

```java
// Element is found by its structured XMI ID: Customer/(esm/_abc123)/Entity2Table
Table table = ctx.equivalent(customerEntity, Table.class);
```

This enables:
- Cross-phase element discovery (elements created in previous phases are found)
- Cross-rule references work correctly regardless of rule execution order
- Manually created elements with matching XMI IDs are discovered

### Rule Selection

When `equivalent()` is called:

1. Look up source element in object-reference cache
2. If cached, return the `@Primary` result (or first result if no primary)
3. If not cached:
   - Find rules matching source type
   - If `useStructuredIds` is enabled, generate expected XMI ID and look up by ID
   - If found by XMI ID, cache it and return
   - Otherwise, if rule is `@Lazy`, execute it now
   - If rule is not lazy but not yet executed, this is an ordering issue

## equivalents() - Multiple Target Resolution

The `equivalents()` method returns ALL transformed targets for a source element:

```java
<T extends EObject> List<T> equivalents(EObject source, Class<T> targetClass);
```

### When to Use

Use `equivalents()` when multiple rules transform the same source to different targets:

```java
// Rule 1: Create main table
@TransformRule(name = "Entity2MainTable")
@Primary
public TransformFunction<EntityType, Table> entity2MainTable() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        return table;
    };
}

// Rule 2: Create audit table
@TransformRule(name = "Entity2AuditTable")
public TransformFunction<EntityType, Table> entity2AuditTable() {
    return (entity, ctx) -> {
        Table auditTable = ctx.createTarget(Table.class);
        auditTable.setName(entity.getName() + "_audit");
        return auditTable;
    };
}
```

```java
// Get all tables created from entity
List<Table> allTables = ctx.equivalents(entity, Table.class);
// Returns: [mainTable, auditTable]

// Get primary table only
Table mainTable = ctx.equivalent(entity, Table.class);
// Returns: mainTable (because it's @Primary)
```

### Result Ordering

1. `@Primary` results come first
2. Non-primary results follow in rule registration order

## @Primary - Preferred Resolution

Mark a rule as `@Primary` to make its result the preferred `equivalent()` return value:

```java
@TransformRule(name = "Entity2PersistentTable")
@Primary  // This result returned by equivalent()
public TransformFunction<EntityType, Table> entity2PersistentTable() { ... }

@TransformRule(name = "Entity2ViewTable")
// Only returned by equivalents()
public TransformFunction<EntityType, Table> entity2ViewTable() { ... }
```

**Without @Primary**: First registered rule's result is returned by `equivalent()`

## Lazy Rule Execution

Lazy rules execute on-demand when `equivalent()` is called:

```java
@TransformRule(name = "Reference2ForeignKey")
@Lazy  // Does NOT execute during eager phase
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() {
    return (ref, ctx) -> {
        ForeignKey fk = ctx.createTarget(ForeignKey.class);
        fk.setName("fk_" + ref.getName());
        fk.setReferencedTable(ctx.equivalent(ref.getTarget(), Table.class));
        return fk;
    };
}
```

```java
// Lazy rules execute when equivalent() is called
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        for (Reference ref : entity.getReferences()) {
            // This triggers Reference2ForeignKey lazy rule
            ForeignKey fk = ctx.equivalent(ref, ForeignKey.class);
            table.getForeignKeys().add(fk);
        }
        
        return table;
    };
}
```

### Lazy vs Eager

| Aspect | Eager Rules | Lazy Rules |
|--------|------------|------------|
| Execution | During eager phase | On-demand via equivalent() |
| Default | Yes (no annotation) | Requires `@Lazy` |
| Use case | Always-needed targets | Conditionally-needed targets |
| Performance | Upfront cost | Deferred cost |

## equivalentDiscriminated() - Multiple Outputs from Same Source

> **Note**: Discriminated equivalence is a **Zeta-specific workaround**, not part of the original Epsilon ETL specification. In ETL, you can create multiple targets using `to t1, t2, t3` syntax in a single rule. Since Zeta follows a single-source-single-target pattern, `equivalentDiscriminated()` provides an alternative mechanism for creating multiple related target elements from a single source element.

Create multiple distinct targets from the same source using discriminators:

```java
<T extends EObject> T equivalentDiscriminated(
    EObject source, 
    Class<T> targetClass, 
    String ruleName, 
    String discriminator
);
```

### Use Case: CRUD Operations

```java
@TransformRule(name = "Relation2Operations")
public TransformFunction<Relation, Void> relation2Operations() {
    return (relation, ctx) -> {
        String targetName = relation.getTarget().getName();
        
        // Create multiple operations from one relation
        Operation create = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "create"
        );
        create.setName("create" + targetName);
        create.setHttpMethod("POST");
        
        Operation read = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "read"
        );
        read.setName("get" + targetName);
        read.setHttpMethod("GET");
        
        Operation update = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "update"
        );
        update.setName("update" + targetName);
        update.setHttpMethod("PUT");
        
        Operation delete = ctx.equivalentDiscriminated(
            relation, Operation.class, "RelationOperation", "delete"
        );
        delete.setName("delete" + targetName);
        delete.setHttpMethod("DELETE");
        
        return null;
    };
}
```

### ID Generation

Discriminated elements get unique IDs:

```
Base ID: relation-customer-orders
Discriminated IDs:
  - relation-customer-orders/(discriminator/create)
  - relation-customer-orders/(discriminator/read)
  - relation-customer-orders/(discriminator/update)
  - relation-customer-orders/(discriminator/delete)
```

### Cache Structure

```java
// Conceptual cache structure for discriminated elements
cache[source][ruleName][discriminator] = targetElement

// Example:
cache[relationElement]["RelationOperation"]["create"] = createOperation
cache[relationElement]["RelationOperation"]["read"]   = readOperation
cache[relationElement]["RelationOperation"]["update"] = updateOperation
cache[relationElement]["RelationOperation"]["delete"] = deleteOperation
```

### Retrieving Discriminated Elements

```java
// Later, retrieve specific discriminated element
Operation createOp = ctx.equivalentDiscriminated(
    relation, Operation.class, "RelationOperation", "create"
);
// Returns cached createOperation
```

## Structured XMI IDs

ZETA generates ETL-style structured XMI IDs for traceability and ID-based lookup:

```
Format: <source-name>/(<alias>/<source-id>)/<rule-name>
Example: Customer/(esm/_abc123)/Entity2Table
```

The `<alias>` is the registered resource alias (e.g., "esm", "asm", "mapping", "source").

For discriminated equivalents, the discriminator is appended:

```
Format: <base-id>/(discriminator/<discriminator-value>)
Example: Customer/(esm/_abc123)/TableAction/(discriminator/relation1)
```

### Disabling Structured IDs

To use sequence-based IDs instead (for compatibility or debugging):

```java
context.setUseStructuredIds(false);  // Uses _seq0, _seq1, etc.
```

When disabled, XMI ID-based lookup is also disabled, falling back to object-reference caching only.

## Resolution with @Greedy Rules

Greedy rules match source type and ALL subtypes:

```java
@TransformRule(name = "NamedElement2NamedType")
@Greedy  // Matches NamedElement, EntityType, ActorType, OperationType...
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        return target;
    };
}
```

When `equivalent()` is called with an `EntityType`:
1. Exact-match rules for `EntityType` are checked first
2. Then greedy rules for `NamedElement` (supertype) are checked

## Resolution Null Handling

### Source is Null

```java
// Returns null if source is null
Column column = ctx.equivalent(null, Column.class);
// column == null
```

### No Applicable Rule

```java
// Throws exception if no rule found
try {
    Table table = ctx.equivalent(unknownElement, Table.class);
} catch (TransformationException e) {
    // "No rule found for source type: UnknownType"
}
```

### Safe Resolution Pattern

```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Safe resolution with null check
        if (entity.getSuperType() != null) {
            Table parentTable = ctx.equivalent(entity.getSuperType(), Table.class);
            if (parentTable != null) {
                table.setParentTable(parentTable);
            }
        }
        
        return table;
    };
}
```

## Performance Considerations

### Cache Hit Optimization

```java
// Inefficient: Multiple equivalent() calls for same element
for (int i = 0; i < 100; i++) {
    Table t = ctx.equivalent(entity, Table.class);  // Cache hit each time, but overhead
    processTable(t);
}

// Better: Cache locally
Table t = ctx.equivalent(entity, Table.class);
for (int i = 0; i < 100; i++) {
    processTable(t);
}
```

### Lazy Rule Benefits

```java
// Use @Lazy for rules that may not always be needed
@TransformRule(name = "ComplexElement2DetailedTarget")
@Lazy  // Only executed if equivalent() is called
public TransformFunction<ComplexElement, DetailedTarget> complexTransform() {
    return (source, ctx) -> {
        // Expensive transformation logic
        // Only runs if actually needed
    };
}
```

## Debugging Resolution Issues

### Enable Debug Logging

```java
// In logback.xml or log4j2.xml
<logger name="hu.blackbelt.judo.zeta.transformation" level="DEBUG"/>
```

### Check Transformation Trace

```java
TransformationResult result = executor.transform(elements);
TransformationTrace trace = result.getTrace();

// Find all entries for a specific source
trace.getEntries().stream()
    .filter(e -> e.getSource().getId().equals("my-element-id"))
    .forEach(System.out::println);
```

---

**Previous**: [Transformation Rules](transformation-rules.md) | **Next**: [Guards and Conditions](guards-and-conditions.md)
