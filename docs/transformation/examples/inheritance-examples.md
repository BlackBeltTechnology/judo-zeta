# Rule Inheritance Examples

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Inheritance Examples

Examples of code reuse patterns using @Abstract and @Extends.

## Abstract Base Rule

```java
@TransformRule(name = "BaseNamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setQualifiedName(buildQualifiedName(source));
        return target;
    };
}
```

## Single Inheritance

```java
@TransformRule(name = "EntityType2Table")
@Extends("BaseNamedElement2NamedType")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Execute parent first - gets name, description, qualifiedName
        Table table = ctx.executeParentRule("BaseNamedElement2NamedType", entity);
        
        // Add entity-specific properties
        table.setAbstract(entity.isAbstract());
        table.setSchema(ctx.equivalent(entity.getNamespace(), Schema.class));
        
        // Transform attributes
        for (Attribute attr : entity.getAttributes()) {
            table.getColumns().add(ctx.equivalent(attr, Column.class));
        }
        
        return table;
    };
}
```

## Multi-Level Inheritance Chain

```java
// Level 1: Base properties
@TransformRule(name = "NamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        return target;
    };
}

// Level 2: Add typing
@TransformRule(name = "TypedElement2TypedType")
@Abstract
@Extends("NamedElement2NamedType")
public TransformFunction<TypedElement, TypedType> typedElement2TypedType() {
    return (source, ctx) -> {
        TypedType target = ctx.executeParentRule("NamedElement2NamedType", source);
        target.setType(mapType(source.getType()));
        return target;
    };
}

// Level 3: Concrete rule
@TransformRule(name = "Attribute2Column")
@Extends("TypedElement2TypedType")
public TransformFunction<Attribute, Column> attribute2Column() {
    return (attr, ctx) -> {
        Column column = ctx.executeParentRule("TypedElement2TypedType", attr);
        column.setNullable(!attr.isRequired());
        column.setDefaultValue(attr.getDefaultValue());
        return column;
    };
}
```

## Inheritance with Guards

```java
// Abstract base
@TransformRule(name = "BaseEntity2Table")
@Abstract
public TransformFunction<EntityType, Table> baseEntity2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        return table;
    };
}

// Concrete for abstract entities
@TransformRule(name = "AbstractEntity2AbstractTable")
@Extends("BaseEntity2Table")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2AbstractTable() {
    return (entity, ctx) -> {
        Table table = ctx.executeParentRule("BaseEntity2Table", entity);
        table.setAbstract(true);
        table.setDiscriminator(entity.getName() + "_type");
        return table;
    };
}

// Concrete for concrete entities
@TransformRule(name = "ConcreteEntity2ConcreteTable")
@Extends("BaseEntity2Table")
@Guard(method = "isConcrete")
public TransformFunction<EntityType, Table> concreteEntity2ConcreteTable() {
    return (entity, ctx) -> {
        Table table = ctx.executeParentRule("BaseEntity2Table", entity);
        table.setAbstract(false);
        return table;
    };
}

private boolean isAbstract(EObject e, TransformationContext ctx) {
    return ((EntityType) e).isAbstract();
}

private boolean isConcrete(EObject e, TransformationContext ctx) {
    return !((EntityType) e).isAbstract();
}
```

## Common Pattern: Feature Extraction

```java
// Extract common features into abstract rule
@TransformRule(name = "BaseAuditedElement")
@Abstract
public TransformFunction<AuditedElement, AuditedRecord> baseAuditedElement() {
    return (source, ctx) -> {
        AuditedRecord record = ctx.createTarget(AuditedRecord.class);
        record.setCreatedAt(source.getCreatedAt());
        record.setCreatedBy(source.getCreatedBy());
        record.setModifiedAt(source.getModifiedAt());
        record.setModifiedBy(source.getModifiedBy());
        return record;
    };
}

// Reuse in multiple concrete rules
@TransformRule(name = "Order2OrderRecord")
@Extends("BaseAuditedElement")
public TransformFunction<Order, OrderRecord> order2OrderRecord() {
    return (order, ctx) -> {
        OrderRecord record = ctx.executeParentRule("BaseAuditedElement", order);
        record.setOrderNumber(order.getNumber());
        record.setTotal(order.getTotal());
        return record;
    };
}

@TransformRule(name = "Invoice2InvoiceRecord")
@Extends("BaseAuditedElement")
public TransformFunction<Invoice, InvoiceRecord> invoice2InvoiceRecord() {
    return (invoice, ctx) -> {
        InvoiceRecord record = ctx.executeParentRule("BaseAuditedElement", invoice);
        record.setInvoiceNumber(invoice.getNumber());
        record.setAmount(invoice.getAmount());
        return record;
    };
}
```

---

**Previous**: [Discriminated Examples](discriminated-examples.md) | **Next**: [Architecture Overview](../architecture/overview.md)
