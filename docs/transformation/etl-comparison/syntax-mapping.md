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
| `extends RuleName` | `@Extends("RuleName")` |
| `guard: condition` | `@Guard(method = "guardMethod")` |

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
