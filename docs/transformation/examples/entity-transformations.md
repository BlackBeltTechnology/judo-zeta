# Entity Transformations

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Entity Transformations

Examples of entity-to-table transformations (ESM to PSM patterns from judo-tatami).

## EntityType to Table

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            table.setAbstract(entity.isAbstract());
            
            // Transform attributes to columns
            for (Attribute attr : entity.getAttributes()) {
                Column column = ctx.equivalent(attr, Column.class);
                table.getColumns().add(column);
            }
            
            // Handle inheritance
            if (entity.getSuperType() != null) {
                Table parentTable = ctx.equivalent(entity.getSuperType(), Table.class);
                table.setParentTable(parentTable);
            }
            
            return table;
        };
    }
}
```

## Attribute to Column

```java
@TransformRule(name = "Attribute2Column")
public TransformFunction<Attribute, Column> attribute2Column() {
    return (attr, ctx) -> {
        Column column = ctx.createTarget(Column.class);
        column.setName(toSnakeCase(attr.getName()));
        column.setType(mapDataType(attr.getType()));
        column.setNullable(!attr.isRequired());
        
        if (attr.getDefaultValue() != null) {
            column.setDefaultValue(attr.getDefaultValue());
        }
        
        return column;
    };
}

private String mapDataType(DataType type) {
    return switch (type.getName()) {
        case "String" -> "VARCHAR(255)";
        case "Integer" -> "INT";
        case "Long" -> "BIGINT";
        case "Boolean" -> "BOOLEAN";
        case "Date" -> "DATE";
        case "Timestamp" -> "TIMESTAMP";
        case "Decimal" -> "DECIMAL(19,4)";
        default -> "TEXT";
    };
}
```

## Handling Inheritance

```java
@TransformRule(name = "BaseNamedElement")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        return target;
    };
}

@TransformRule(name = "EntityType2Table")
@Extends("BaseNamedElement")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        Table table = ctx.executeParentRule("BaseNamedElement", entity);
        table.setAbstract(entity.isAbstract());
        table.setSchema(ctx.equivalent(entity.getNamespace(), Schema.class));
        return table;
    };
}
```

## Reference to ForeignKey (Lazy)

```java
@TransformRule(name = "Reference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() {
    return (ref, ctx) -> {
        ForeignKey fk = ctx.createTarget(ForeignKey.class);
        fk.setName("fk_" + ref.getOwner().getName() + "_" + ref.getName());
        fk.setSourceTable(ctx.equivalent(ref.getOwner(), Table.class));
        fk.setTargetTable(ctx.equivalent(ref.getTarget(), Table.class));
        fk.setNullable(!ref.isRequired());
        return fk;
    };
}
```

---

**Previous**: [Simple Transformations](simple-transformations.md) | **Next**: [Namespace Transformations](namespace-transformations.md)
