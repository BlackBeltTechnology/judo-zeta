# ETL to Zeta Syntax Mapping

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Syntax Mapping

Complete reference for converting ETL constructs to Zeta equivalents.

## Rule Declaration

**ETL**:
```etl
rule EntityType2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    t.name = e.name;
}
```

**Zeta**:
```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (e, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setName(e.getName());
        return t;
    };
}
```

## Annotations/Modifiers

| ETL | Zeta |
|-----|------|
| `@lazy` | `@Lazy` |
| `@abstract` | `@Abstract` |
| `@primary` | `@Primary` |
| `@greedy` | `@Greedy` |
| `@greedy @lazy` (activity-based) | `@Greedy @Lazy @ActivityBased` or `etlCompatibilityMode(true)` |
| `extends RuleName` | `@Extends("RuleName")` |
| `guard: condition` | `@Guard(method = "guardMethod")` |

### Behavioral Differences

While the annotations map directly between Epsilon ETL and Zeta, there are behavioral differences to be aware of:

| Aspect | Epsilon ETL | Zeta (default) | Zeta (with @ActivityBased or etlCompatibilityMode) |
|--------|-------------|----------------|---------------------------------------------------|
| **@lazy semantics** | On-demand via `equivalent()` | On-demand via `equivalent()` | On-demand via `equivalent()` |
| **@greedy semantics** | Kind-of type matching (subtypes) | Kind-of type matching (subtypes) | Kind-of type matching (subtypes) |
| **@greedy @lazy coverage** | Only activated elements | **ALL matching instances** | Only activated elements (ETL-matching) |

**Key difference**: In Epsilon ETL, `@greedy @lazy` rules use activity-based semantics - they only process elements that are "activated" during transformation (referenced via `equivalent()`). By default, Zeta processes ALL instances matching the source type. To match ETL behavior, use either:

1. **Explicit annotation**: Add `@ActivityBased` to individual rules
2. **Global mode**: Use `etlCompatibilityMode(true)` on the executor

> **Important**: `@Greedy` controls **type matching only** (kind-of vs type-of). It does NOT control eager vs lazy execution - that's `@Lazy`'s role. These annotations are orthogonal and can be combined.

### @lazy

**ETL**:
```etl
@lazy
rule Reference2ForeignKey
    transform r : ESM!Reference
    to fk : PSM!ForeignKey { ... }
```

**Zeta**:
```java
@TransformRule(name = "Reference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() { ... }
```

### @abstract and extends

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

### guard

**ETL**:
```etl
rule AbstractEntity2AbstractTable
    transform e : ESM!EntityType
    to t : PSM!Table {
    guard: e.isAbstract()
    t.abstract = true;
}
```

**Zeta**:
```java
@TransformRule(name = "AbstractEntity2AbstractTable")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2AbstractTable() {
    return (e, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setAbstract(true);
        return t;
    };
}

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

### @greedy @lazy (Activity-Based Processing)

In Epsilon ETL, `@greedy @lazy` rules implicitly use activity-based semantics - only elements referenced via `equivalent()` are processed. Zeta makes this behavior explicit.

**ETL** (implicit activity-based):
```etl
@greedy
@lazy
rule ClassType2TransferObject
    transform c : ESM!ClassType
    to t : PSM!TransferObjectType {
    t.name = c.name;
}
```

**Zeta Option 1** - Explicit annotation:
```java
@TransformRule(name = "ClassType2TransferObject")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<ClassType, TransferObjectType> classType2TransferObject() {
    return (c, ctx) -> {
        TransferObjectType t = ctx.createTarget(TransferObjectType.class);
        t.setName(c.getName());
        return t;
    };
}
```

**Zeta Option 2** - ETL compatibility mode (applies to ALL `@Greedy @Lazy` rules):
```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // All @Greedy @Lazy rules become activity-based
    .build();
```

**How it works**:
1. **Phase 1**: Eager rules execute. When they call `equivalent()` for an activity-based rule, the element is recorded as "activated" but execution is deferred.
2. **Phase 2**: After Phase 1 completes, activity-based rules execute only for activated elements.
3. **Fixpoint loop**: If activity-based rules activate each other, Phase 2 continues until no new activations occur.

## Element Resolution

| ETL | Zeta |
|-----|------|
| `e.equivalent()` | `ctx.equivalent(e, TargetType.class)` |
| `e.equivalents()` | `ctx.equivalents(e, TargetType.class)` |
| `e.equivalent("RuleName")` | `ctx.equivalent(e, TargetType.class)` with rule lookup |

**ETL**:
```etl
t.columns.addAll(e.attributes.equivalent());
t.parentTable = e.superType.equivalent();
```

**Zeta**:
```java
for (Attribute attr : e.getAttributes()) {
    t.getColumns().add(ctx.equivalent(attr, Column.class));
}
t.setParentTable(ctx.equivalent(e.getSuperType(), Table.class));
```

## Pre/Post Blocks

**ETL**:
```etl
pre {
    var cache = new ConcurrentMap();
}

post {
    cache.clear();
}
```

**Zeta**:
```java
@PreExecution
public void pre(TransformationContext ctx) {
    ctx.setAttribute("cache", new ConcurrentHashMap<>());
}

@PostExecution
public void post(TransformationContext ctx) {
    Map<?, ?> cache = (Map<?, ?>) ctx.getAttribute("cache");
    cache.clear();
}
```

## Complete Syntax Reference

| ETL Construct | Zeta Equivalent |
|---------------|-----------------|
| `rule Name transform s : Source to t : Target` | `@TransformRule(name = "Name") TransformFunction<Source, Target>` |
| `@lazy` | `@Lazy` |
| `@abstract` | `@Abstract` |
| `@primary` | `@Primary` |
| `@greedy` | `@Greedy` |
| `@greedy @lazy` (activity-based) | `@Greedy @Lazy @ActivityBased` or `etlCompatibilityMode(true)` |
| `extends ParentRule` | `@Extends("ParentRule")` |
| `guard: condition` | `@Guard(method = "methodName")` |
| `s.equivalent()` | `ctx.equivalent(s, TargetType.class)` |
| `s.equivalents()` | `ctx.equivalents(s, TargetType.class)` |
| `pre { ... }` | `@PreExecution` method |
| `post { ... }` | `@PostExecution` method |
| `var x = value;` | `Type x = value;` |
| `s.name` | `s.getName()` |
| `t.name = value` | `t.setName(value)` |
| `collection.select(c \| c.condition)` | `collection.stream().filter(c -> c.isCondition()).toList()` |
| `collection.collect(c \| c.property)` | `collection.stream().map(c -> c.getProperty()).toList()` |
| `collection.exists(c \| c.condition)` | `collection.stream().anyMatch(c -> c.isCondition())` |
| `collection.forAll(c \| c.condition)` | `collection.stream().allMatch(c -> c.isCondition())` |

---

**Previous**: [Overview](overview.md) | **Next**: [Migration Guide](migration-guide.md)
