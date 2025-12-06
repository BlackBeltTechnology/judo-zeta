# Lazy Rule Examples

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Lazy Rule Examples

Examples of on-demand transformation patterns using @Lazy.

## On-Demand Reference Resolution

```java
// Only transform references when actually needed
@TransformRule(name = "Reference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() {
    return (ref, ctx) -> {
        ForeignKey fk = ctx.createTarget(ForeignKey.class);
        fk.setName("fk_" + ref.getName());
        fk.setTargetTable(ctx.equivalent(ref.getTarget(), Table.class));
        return fk;
    };
}

// Main rule that triggers lazy resolution
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        
        // Only references that are actually used get transformed
        for (Reference ref : entity.getReferences()) {
            if (ref.isNavigable()) {
                // Triggers lazy Reference2ForeignKey
                ForeignKey fk = ctx.equivalent(ref, ForeignKey.class);
                table.getForeignKeys().add(fk);
            }
        }
        
        return table;
    };
}
```

## Lazy Navigation Property

```java
@TransformRule(name = "Reference2NavigationProperty")
@Lazy
public TransformFunction<Reference, NavigationProperty> reference2NavigationProperty() {
    return (ref, ctx) -> {
        NavigationProperty nav = ctx.createTarget(NavigationProperty.class);
        nav.setName(ref.getName());
        nav.setTargetType(ctx.equivalent(ref.getTarget(), Table.class));
        nav.setCardinality(mapCardinality(ref));
        return nav;
    };
}
```

## Performance Optimization with Lazy

```java
// Expensive transformation - only when needed
@TransformRule(name = "ComplexType2DetailedSchema")
@Lazy
public TransformFunction<ComplexType, DetailedSchema> complexType2DetailedSchema() {
    return (complex, ctx) -> {
        DetailedSchema schema = ctx.createTarget(DetailedSchema.class);
        
        // Expensive computations
        schema.setAllProperties(computeAllInheritedProperties(complex));
        schema.setConstraints(computeAllConstraints(complex));
        schema.setValidations(computeValidationRules(complex));
        
        return schema;
    };
}

// Fast rule checks if detailed schema is needed
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Only resolve detailed schema for complex entities
        if (entity.isComplex()) {
            DetailedSchema schema = ctx.equivalent(entity, DetailedSchema.class);
            table.setDetailedSchema(schema);
        }
        
        return table;
    };
}
```

## Lazy with Guard Combination

```java
// Lazy AND guarded
@TransformRule(name = "MappedReference2Binding")
@Lazy
@Guard(method = "isMapped")
public TransformFunction<Reference, Binding> mappedReference2Binding() {
    return (ref, ctx) -> {
        Binding binding = ctx.createTarget(Binding.class);
        binding.setSource(ctx.equivalent(ref.getOwner(), Table.class));
        binding.setTarget(ctx.equivalent(ref.getTarget(), Table.class));
        return binding;
    };
}

private boolean isMapped(EObject element, TransformationContext ctx) {
    Reference ref = (Reference) element;
    return ref.getMapping() != null;
}
```

---

**Previous**: [Service Transformations](service-transformations.md) | **Next**: [Discriminated Examples](discriminated-examples.md)
