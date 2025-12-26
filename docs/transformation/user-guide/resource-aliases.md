# Resource Aliases

Resource aliases allow transformation and validation rules to work with multiple EMF ResourceSets, enabling multi-model transformations similar to Epsilon ETL's model binding feature.

## Overview

By default, transformations work with two resources:
- `source` - the input model(s) to transform
- `target` - the output model where results are created

With resource aliases, you can register additional resources and reference them in your rules.

## Registering Resources

### TransformationContext

```java
// Resources are registered with aliases
TransformationContext ctx = new TransformationContext(
    modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);

// Register additional resources
ctx.registerResource("mapping", mappingResourceSet);
ctx.registerResource("rules", rulesResourceSet);

// Access registered resources
ResourceSet mapping = ctx.getResource("mapping");
```

### ValidationContext

```java
ValidationContext ctx = new ValidationContext(
    modelProvider, resourceSet, extensionRegistry);

// Register additional resources
ctx.registerResource("reference", referenceResourceSet);

// Access registered resources
ResourceSet reference = ctx.getResource("reference");
```

## Default Aliases

The following aliases are automatically registered:

| Context | Alias | Resource |
|---------|-------|----------|
| TransformationContext | `source` | sourceResourceSet |
| TransformationContext | `target` | targetResourceSet |
| ValidationContext | `source` | resourceSet |

You can overwrite these defaults by registering a resource with the same alias name.

## Querying Resources

### all(alias, type)

Query elements from a specific aliased resource:

```java
// Get all EntityType instances from the "asm" resource
Collection<EntityType> entities = ctx.all("asm", EntityType.class);

// Get all TypeMapping instances from the "mapping" resource
Collection<TypeMapping> mappings = ctx.all("mapping", TypeMapping.class);
```

### Backward Compatible Methods

Existing methods continue to work and delegate to the `source` alias:

```java
// These are equivalent:
ctx.getAllSource(EntityType.class);
ctx.all("source", EntityType.class);

// For validation:
ctx.getAllInstances(EClass.class);
ctx.all("source", EClass.class);
```

## Creating Elements

### create(type) - Without Containment

Creates an element without adding it to any resource. The rule must explicitly set containment:

```java
Table table = ctx.create(Table.class);
// Element is created but not contained anywhere
// Must manually add to a container:
schema.getTables().add(table);
```

### createTarget(type) - With Containment

Creates an element and adds it to the target resource root (backward compatible):

```java
Table table = ctx.createTarget(Table.class);
// Element is created AND added to target resource root
```

---

## @Transform and @To Annotations

The `@Transform` and `@To` annotations provide a declarative way to specify source and target types with their resource aliases. These annotations are repeatable, allowing rules to work with multiple source types from different resources.

### @Transform Annotation

Defines a source type for a transformation rule with an optional resource alias.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Transforms.class)
public @interface Transform {
    String alias() default "source";  // Resource alias (defaults to "source")
    Class<? extends EObject> type();  // Source element type
}
```

### @To Annotation

Defines a target type for a transformation rule with an optional resource alias.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Tos.class)
public @interface To {
    String alias() default "target";  // Resource alias (defaults to "target")
    Class<? extends EObject> type();  // Target element type
}
```

---

## Annotation Equivalence with @TransformRule Attributes

The new `@Transform` and `@To` annotations provide the same functionality as the legacy `sourceTypes` and `targetTypes` attributes on `@TransformRule`, but with added support for resource aliases.

### Single Source Type

**Legacy style (using sourceTypes attribute):**
```java
@TransformRule(name = "Entity2Table", sourceTypes = {EntityType.class})
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

**New style (using @Transform annotation):**
```java
@TransformRule(name = "Entity2Table")
@Transform(type = EntityType.class)  // alias defaults to "source"
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

Both are equivalent - elements are collected from the default "source" alias.

### Single Source Type with Custom Alias

**New style only (not possible with legacy attributes):**
```java
@TransformRule(name = "Entity2Table")
@Transform(alias = "asm", type = EntityType.class)  // custom alias
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

This collects `EntityType` elements from the "asm" aliased resource instead of the default "source".

### Single Target Type

**Legacy style (using targetTypes attribute):**
```java
@TransformRule(name = "Entity2Table", targetTypes = {Table.class})
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

**New style (using @To annotation):**
```java
@TransformRule(name = "Entity2Table")
@To(type = Table.class)  // alias defaults to "target"
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

### Single Target Type with Custom Alias

**New style only:**
```java
@TransformRule(name = "Entity2Table")
@To(alias = "rdbms", type = Table.class)  // custom alias
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> { ... };
}
```

### Multiple Source Types

**Legacy style:**
```java
@TransformRule(name = "ProcessMultiple", sourceTypes = {EntityType.class, Attribute.class})
public TransformFunction<EntityType, Table> processMultiple() {
    return (source, ctx) -> { ... };
}
```

**New style (with same default alias):**
```java
@TransformRule(name = "ProcessMultiple")
@Transform(type = EntityType.class)   // both use default "source" alias
@Transform(type = Attribute.class)
public TransformFunction<EntityType, Table> processMultiple() {
    return (source, ctx) -> { ... };
}
```

**New style (with different aliases - not possible with legacy):**
```java
@TransformRule(name = "ProcessMultiple")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
public TransformFunction<EntityType, Table> processMultiple() {
    return (source, ctx) -> { ... };
}
```

### Multiple Target Types

**Legacy style:**
```java
@TransformRule(name = "CreateMultiple", targetTypes = {Table.class, Column.class})
public TransformFunction<EntityType, Table> createMultiple() {
    return (source, ctx) -> { ... };
}
```

**New style (with different aliases):**
```java
@TransformRule(name = "CreateMultiple")
@To(alias = "rdbms", type = Table.class)
@To(alias = "index", type = Index.class)
public TransformFunction<EntityType, Table> createMultiple() {
    return (source, ctx) -> { ... };
}
```

---

## Cartesian Product Execution (Multi-Source Rules)

When a transformation rule specifies multiple `@Transform` annotations with **different aliases**, the transformation executor generates a **Cartesian product** of all source elements. This enables rules that need to process combinations of elements from different models.

### How It Works

Given:
- `@Transform(alias = "a", type = X.class)` with elements `[x1, x2, x3]`
- `@Transform(alias = "b", type = Y.class)` with elements `[y1, y2]`

The executor generates **3 × 2 = 6** tuples and invokes the rule 6 times:
- `[x1, y1]`, `[x1, y2]`
- `[x2, y1]`, `[x2, y2]`
- `[x3, y1]`, `[x3, y2]`

### MultiSourceTransformFunction Interface

For multi-source rules, use `MultiSourceTransformFunction` instead of `TransformFunction`:

```java
@FunctionalInterface
public interface MultiSourceTransformFunction<T extends EObject> {
    /**
     * Transform multiple source elements into a target element.
     * 
     * @param sources Array of source elements, one per @Transform annotation
     *                in the order they are declared
     * @param context The transformation context
     * @return The created target element
     */
    T apply(EObject[] sources, TransformationContext context);
}
```

### Example: Cross-Model Mapping

```java
@TransformRule(name = "EntityMappingToTable")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public MultiSourceTransformFunction<Table> entityMappingToTable() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        
        // Only create table if the mapping matches this entity
        if (!mapping.getSourceTypeName().equals(entity.getName())) {
            return null; // Skip non-matching combinations
        }
        
        Table table = ctx.create(Table.class);
        table.setName(mapping.getTargetTableName());
        
        return table;
    };
}
```

### Using Guards with Multi-Source Rules

Guards can filter the Cartesian product before execution using `MultiSourceTransformGuard`:

```java
@FunctionalInterface
public interface MultiSourceTransformGuard {
    /**
     * Tests whether the rule should execute for this tuple.
     * 
     * @param sources Array of source elements
     * @param context The transformation context
     * @return true if the rule should execute
     */
    boolean test(EObject[] sources, TransformationContext context);
}
```

**Example: Filter matching pairs only:**

```java
@TransformRule(name = "MatchedPairsOnly")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
public MultiSourceTransformGuard matchedPairsGuard() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        return mapping.getSourceTypeName().equals(entity.getName());
    };
}

@TransformRule(name = "MatchedPairsOnly")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public MultiSourceTransformFunction<Table> matchedPairsRule() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        
        Table table = ctx.create(Table.class);
        table.setName(mapping.getTargetTableName());
        return table;
    };
}
```

### Source Element Order

The `sources` array contains elements in the same order as the `@Transform` annotations are declared:

```java
@TransformRule(name = "ThreeWayRule")
@Transform(alias = "a", type = A.class)      // sources[0]
@Transform(alias = "b", type = B.class)      // sources[1]  
@Transform(alias = "c", type = C.class)      // sources[2]
public MultiSourceTransformFunction<Result> threeWayRule() {
    return (sources, ctx) -> {
        A a = (A) sources[0];
        B b = (B) sources[1];
        C c = (C) sources[2];
        // Process all three...
    };
}
```

### When to Use Multi-Source Rules

Multi-source rules with Cartesian product are useful for:

1. **Cross-model mappings**: When you need to match elements from a source model with mapping definitions
2. **Rule-based transformations**: Applying transformation rules from a rules model to source elements
3. **Configuration-driven generation**: Combining model elements with configuration options
4. **Model weaving**: Creating relationships between elements from different models

### Performance Considerations

The Cartesian product can grow quickly:
- 100 × 100 = 10,000 rule invocations
- 100 × 100 × 100 = 1,000,000 rule invocations

**Best practices:**
- Use guards to filter non-matching combinations early
- Return `null` from the transform function for combinations that shouldn't produce output
- Consider breaking down into multiple single-source rules when possible
- Use lazy rules if only specific combinations are needed

### Combined Source and Target with Custom Aliases

**Full example with both @Transform and @To:**
```java
@TransformRule(name = "Entity2Table")
@Transform(alias = "asm", type = EntityType.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.create(Table.class);
        table.setName(source.getName().toUpperCase());
        
        // Get the parent schema and add the table
        Schema schema = ctx.equivalent(source.eContainer(), Schema.class);
        schema.getTables().add(table);
        
        return table;
    };
}
```

### Relationship Between @TransformationContext, @TransformRule, @Transform, and @To

The annotations form a hierarchy where more specific annotations override less specific ones:

```
@TransformationContext (class-level defaults)
    └── @TransformRule.sourceTypes / @TransformRule.targetTypes (rule-level override)
            └── @Transform / @To (rule-level override with alias support)
```

**@TransformationContext** (on class):
- Defines **default** source and target types for all rules in the class
- Acts as a fallback when rules don't specify their own types
- Both `source` and `target` are required attributes

**@TransformRule** (on method):
- Defines a transformation rule
- Optional `sourceTypes` and `targetTypes` attributes override @TransformationContext defaults
- These attributes use the default "source" and "target" aliases

**@Transform / @To** (on method):
- Most specific - overrides both @TransformRule attributes and @TransformationContext defaults
- Adds alias support for multi-model transformations
- Repeatable - can specify multiple source/target types with different aliases

### Example: Fallback Chain

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTransformations {

    // Case 1: No @Transform/@To, no sourceTypes/targetTypes
    // Uses @TransformationContext defaults: EntityType -> Table, aliases: "source" -> "target"
    @TransformRule(name = "SimpleRule")
    public TransformFunction<EntityType, Table> simpleRule() {
        return (source, ctx) -> { ... };
    }
    
    // Case 2: Uses sourceTypes attribute, overrides @TransformationContext.source
    // Uses Attribute -> Table (target still from @TransformationContext), aliases: "source" -> "target"
    @TransformRule(name = "AttributeRule", sourceTypes = {Attribute.class})
    public TransformFunction<Attribute, Table> attributeRule() {
        return (source, ctx) -> { ... };
    }
    
    // Case 3: Uses @Transform, overrides everything for source
    // Uses Attribute from "asm" alias -> Table (target from @TransformationContext)
    @TransformRule(name = "AliasedRule")
    @Transform(alias = "asm", type = Attribute.class)
    public TransformFunction<Attribute, Table> aliasedRule() {
        return (source, ctx) -> { ... };
    }
    
    // Case 4: Uses both @Transform and @To, fully overrides @TransformationContext
    // Uses EntityType from "asm" -> Column to "rdbms"
    @TransformRule(name = "FullySpecifiedRule")
    @Transform(alias = "asm", type = EntityType.class)
    @To(alias = "rdbms", type = Column.class)
    public TransformFunction<EntityType, Column> fullySpecifiedRule() {
        return (source, ctx) -> { ... };
    }
}
```

---

## Priority Rules

When multiple ways of specifying source/target types are present, the following priority applies:

1. **@Transform annotations** take precedence over `sourceTypes` attribute
2. **@To annotations** take precedence over `targetTypes` attribute
3. **Attributes** (`sourceTypes`/`targetTypes`) take precedence over `@TransformationContext` defaults
4. **@TransformationContext** defaults are used as fallback

### Example: Mixed Usage

```java
// @Transform takes precedence - uses "asm" alias, ignores sourceTypes
@TransformRule(name = "MixedRule", sourceTypes = {SomeOtherType.class})
@Transform(alias = "asm", type = EntityType.class)
public TransformFunction<EntityType, Table> mixedRule() {
    return (source, ctx) -> { ... };
}
```

---

## Complete Examples

### Multi-Model Transformation

```java
// Setup: Register all models with aliases
TransformationContext ctx = new TransformationContext(
    modelProvider, asmResourceSet, rdbmsResourceSet, extensionRegistry);
ctx.registerResource("asm", asmResourceSet);
ctx.registerResource("mapping", mappingResourceSet);
ctx.registerResource("rdbms", rdbmsResourceSet);

@TransformationContext(source = EntityType.class, target = Table.class)
public class ASM2RDBMSTransformations {

    @TransformRule(name = "Entity2Table")
    @Transform(alias = "asm", type = EntityType.class)
    @To(alias = "rdbms", type = Table.class)
    public TransformFunction<EntityType, Table> entity2Table() {
        return (entity, ctx) -> {
            // Look up mapping for this entity from the "mapping" resource
            Collection<TypeMapping> mappings = ctx.all("mapping", TypeMapping.class);
            TypeMapping mapping = mappings.stream()
                .filter(m -> m.getSourceType().equals(entity.getName()))
                .findFirst()
                .orElse(null);
                
            Table table = ctx.create(Table.class);
            table.setName(mapping != null ? mapping.getTargetName() : entity.getName());
            
            return table;
        };
    }
    
    @TransformRule(name = "Attribute2Column")
    @Transform(alias = "asm", type = Attribute.class)
    @To(alias = "rdbms", type = Column.class)
    public TransformFunction<Attribute, Column> attribute2Column() {
        return (attr, ctx) -> {
            Column column = ctx.create(Column.class);
            column.setName(attr.getName());
            
            // Add to parent table
            Table table = ctx.equivalent(attr.eContainer(), Table.class);
            table.getColumns().add(column);
            
            return column;
        };
    }
}
```

### Accessing Multiple Resources in a Rule

```java
@TransformRule(name = "ApplyTypeMapping")
@Transform(alias = "asm", type = EntityType.class)
@To(alias = "rdbms", type = Table.class)
public TransformFunction<EntityType, Table> applyTypeMapping() {
    return (entity, ctx) -> {
        // Access elements from multiple aliased resources
        Collection<TypeMapping> typeMappings = ctx.all("mapping", TypeMapping.class);
        Collection<NamingRule> namingRules = ctx.all("rules", NamingRule.class);
        
        // Find applicable mapping
        TypeMapping mapping = typeMappings.stream()
            .filter(m -> m.matches(entity))
            .findFirst()
            .orElseThrow();
        
        // Find applicable naming rule
        NamingRule naming = namingRules.stream()
            .filter(r -> r.appliesTo(entity))
            .findFirst()
            .orElse(NamingRule.DEFAULT);
        
        Table table = ctx.create(Table.class);
        table.setName(naming.apply(entity.getName()));
        table.setType(mapping.getTargetType());
        
        return table;
    };
}
```

---

## Validation Rules with Aliases

### @Constraint and @Critique Annotations

Add `resourceAlias` to specify which resource to validate:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidator {

    @Constraint(
        name = "EntityMustHaveName",
        message = "Entity must have a name",
        resourceAlias = "asm"  // Validate entities from "asm" resource
    )
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail();
        };
    }
    
    @Critique(
        name = "EntityShouldHaveDescription",
        message = "Entity should have a description",
        resourceAlias = "asm"
    )
    public ValidationRule entityShouldHaveDescription() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getDescription() != null
                ? ValidationResult.pass()
                : ValidationResult.fail();
        };
    }
}
```

### Default Alias

When `resourceAlias` is not specified, validators use the `source` alias:

```java
@Constraint(name = "MustHaveName", message = "Must have name")
// resourceAlias defaults to "source"
public ValidationRule mustHaveName() { ... }
```

---

## Error Handling

Accessing an unknown alias throws `IllegalArgumentException`:

```java
ctx.getResource("unknown");
// Throws: IllegalArgumentException: Unknown resource alias: 'unknown'. 
//         Available aliases: [source, target, mapping]
```

## Thread Safety

The resource registry uses `ConcurrentHashMap`, making it safe for:
- Parallel transformation execution
- Parallel validation execution
- Registration during pre-execution hooks

Best practice: Register all resources before starting parallel execution.

---

## Quick Reference

| Legacy Style | New Style | Notes |
|--------------|-----------|-------|
| `sourceTypes = {A.class}` | `@Transform(type = A.class)` | Equivalent, both use "source" alias |
| `sourceTypes = {A.class, B.class}` | `@Transform(type = A.class)` `@Transform(type = B.class)` | Equivalent |
| N/A | `@Transform(alias = "asm", type = A.class)` | Custom alias (new feature) |
| `targetTypes = {T.class}` | `@To(type = T.class)` | Equivalent, both use "target" alias |
| `targetTypes = {T.class, U.class}` | `@To(type = T.class)` `@To(type = U.class)` | Equivalent |
| N/A | `@To(alias = "rdbms", type = T.class)` | Custom alias (new feature) |
