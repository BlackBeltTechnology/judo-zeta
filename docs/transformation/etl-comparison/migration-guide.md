# ETL to Zeta Migration Guide

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Migration Guide

Step-by-step guide for migrating ETL transformations to Zeta.

## Migration Process

### Step 1: Create Java Class Structure

For each ETL file, create a corresponding Java class:

**ETL**: `entityToTable.etl`
```etl
rule EntityType2Table
    transform e : ESM!EntityType
    to t : PSM!Table { ... }

rule Attribute2Column
    transform a : ESM!Attribute
    to c : PSM!Column { ... }
```

**Zeta**:
```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityToTableTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() { ... }
    
    @TransformRule(name = "Attribute2Column")
    public TransformFunction<Attribute, Column> attribute2Column() { ... }
}
```

### Step 2: Convert Rule Bodies

**ETL**:
```etl
rule EntityType2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    t.name = e.name;
    t.abstract = e.isAbstract();
    t.columns.addAll(e.attributes.equivalent());
    
    if (e.superType.isDefined()) {
        t.parentTable = e.superType.equivalent();
    }
}
```

**Zeta**:
```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (e, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setName(e.getName());
        t.setAbstract(e.isAbstract());
        
        for (Attribute attr : e.getAttributes()) {
            t.getColumns().add(ctx.equivalent(attr, Column.class));
        }
        
        if (e.getSuperType() != null) {
            t.setParentTable(ctx.equivalent(e.getSuperType(), Table.class));
        }
        
        return t;
    };
}
```

### Step 3: Convert Guards

**ETL**:
```etl
rule AbstractEntity2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    guard: e.isAbstract()
    ...
}
```

**Zeta**:
```java
@TransformRule(name = "AbstractEntity2Table")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2Table() { ... }

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

### Step 4: Convert Rule Inheritance

**ETL**:
```etl
@abstract
rule NamedElement2NamedType
    transform s : ESM!NamedElement
    to t : PSM!NamedType {
    t.name = s.name;
}

rule EntityType2Table
    transform e : ESM!EntityType
    to t : PSM!Table
    extends NamedElement2NamedType {
    t.schema = e.namespace.equivalent();
}
```

**Zeta**:
```java
@TransformRule(name = "NamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (s, ctx) -> {
        NamedType t = ctx.createTarget(NamedType.class);
        t.setName(s.getName());
        return t;
    };
}

@TransformRule(name = "EntityType2Table")
@Extends("NamedElement2NamedType")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (e, ctx) -> {
        Table t = ctx.executeParentRule("NamedElement2NamedType", e);
        t.setSchema(ctx.equivalent(e.getNamespace(), Schema.class));
        return t;
    };
}
```

### Step 5: Convert Pre/Post Blocks

**ETL**:
```etl
pre {
    var cache = new ConcurrentMap();
    "Starting transformation".println();
}

post {
    ("Finished: " + cache.size() + " elements").println();
}
```

**Zeta**:
```java
@PreExecution
public void pre(TransformationContext ctx) {
    ctx.setAttribute("cache", new ConcurrentHashMap<>());
    System.out.println("Starting transformation");
}

@PostExecution
public void post(TransformationContext ctx) {
    Map<?, ?> cache = (Map<?, ?>) ctx.getAttribute("cache");
    System.out.println("Finished: " + cache.size() + " elements");
}
```

### Step 6: Convert EOL Collections

**ETL**:
```etl
e.attributes.select(a | a.isPersistent()).equivalent()
e.operations.collect(o | o.name)
e.references.exists(r | r.isContainment())
```

**Zeta**:
```java
e.getAttributes().stream()
    .filter(Attribute::isPersistent)
    .map(a -> ctx.equivalent(a, Column.class))
    .toList();

e.getOperations().stream()
    .map(Operation::getName)
    .toList();

e.getReferences().stream()
    .anyMatch(Reference::isContainment);
```

## Common Migration Pitfalls

### 1. Forgetting to Return Target

**ETL** (implicit return):
```etl
rule Entity2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    t.name = e.name;  // t is automatically returned
}
```

**Zeta** (explicit return required):
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (e, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setName(e.getName());
        return t;  // Don't forget!
    };
}
```

### 2. equivalent() Type Parameter

**ETL** (inferred):
```etl
e.superType.equivalent()  // Returns correct type
```

**Zeta** (explicit):
```java
ctx.equivalent(e.getSuperType(), Table.class)  // Must specify target type
```

### 3. Null Checks

**ETL** (isDefined()):
```etl
if (e.superType.isDefined()) { ... }
```

**Zeta** (null check):
```java
if (e.getSuperType() != null) { ... }
```

## Testing Migrated Transformations

```java
@Test
void migratedTransformation_producesEquivalentOutput() {
    // Given: Same source model
    ResourceSet source = loadModel("test-model.esm");
    
    // When: Execute Zeta transformation
    TransformationResult result = executeZetaTransformation(source);
    
    // Then: Compare with expected output (from original ETL)
    ResourceSet expected = loadModel("expected-output.psm");
    assertModelsEqual(result.getTargetResourceSet(), expected);
}
```

---

**Previous**: [Syntax Mapping](syntax-mapping.md) | **Next**: [Feature Parity](feature-parity.md)
