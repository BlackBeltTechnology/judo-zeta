# TransformationContext API

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > TransformationContext

Complete API reference for TransformationContext.

## Element Creation

### createTarget()

Creates a new target element.

```java
<T extends EObject> T createTarget(Class<T> targetClass)
```

```java
Table table = ctx.createTarget(Table.class);
```

**Requirements**: Target EPackage must be set via `context.setTargetPackage()`

## Element Resolution

### equivalent()

Returns the primary transformed target for a source element.

```java
<T extends EObject> T equivalent(EObject source, Class<T> targetClass)
```

```java
Table parentTable = ctx.equivalent(entity.getSuperType(), Table.class);
```

**Returns**: Cached target or executes lazy rule if needed. Returns `null` if source is null.

### equivalents()

Returns all transformed targets for a source element.

```java
<T extends EObject> List<T> equivalents(EObject source, Class<T> targetClass)
```

```java
List<Table> allTables = ctx.equivalents(entity, Table.class);
```

**Returns**: All targets, with @Primary results first.

### equivalentDiscriminated()

Returns a discriminated target for multiple outputs from same source.

```java
<T extends EObject> T equivalentDiscriminated(
    EObject source, 
    Class<T> targetClass, 
    String ruleName, 
    String discriminator
)
```

```java
Operation createOp = ctx.equivalentDiscriminated(
    relation, Operation.class, "RelationCRUD", "create"
);
```

## Rule Inheritance

### executeParentRule()

Executes a parent rule and returns its result.

```java
<T extends EObject> T executeParentRule(String parentRuleName, EObject source)
```

```java
Table table = ctx.executeParentRule("BaseNamedElement", entity);
```

## Model Queries

### getAllSource()

Returns all source elements of a given type.

```java
<T extends EObject> Collection<T> getAllSource(Class<T> type)
```

```java
Collection<EntityType> allEntities = ctx.getAllSource(EntityType.class);
```

### getAllTarget()

Returns all created target elements of a given type.

```java
<T extends EObject> Collection<T> getAllTarget(Class<T> type)
```

```java
Collection<Table> allTables = ctx.getAllTarget(Table.class);
```

## Custom Attributes

### setAttribute()

Stores a custom attribute.

```java
void setAttribute(String key, Object value)
```

```java
ctx.setAttribute("statistics", new HashMap<>());
```

### getAttribute()

Retrieves a custom attribute.

```java
Object getAttribute(String key)
```

```java
Map<String, Integer> stats = (Map<String, Integer>) ctx.getAttribute("statistics");
```

## Extension Methods

### call()

Invokes a registered extension method.

```java
<T> T call(EObject target, String methodName, Object... args)
```

```java
String qualifiedName = ctx.call(entity, "getQualifiedName");
List<Attribute> allAttrs = ctx.call(entity, "getAllAttributes");
```

## Configuration

### setTargetPackage()

Sets the EPackage for target element creation.

```java
void setTargetPackage(EPackage targetPackage)
```

```java
context.setTargetPackage(TargetPackage.eINSTANCE);
```

### setTransformationRegistry()

Sets the transformation registry.

```java
void setTransformationRegistry(TransformationRegistry registry)
```

---

**Previous**: [Annotations](annotations.md) | **Next**: [TransformationResult](transformation-result.md)
