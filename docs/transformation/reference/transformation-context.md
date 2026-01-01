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

**Package Resolution**: The EPackage is auto-discovered from the Java class. No registration needed.

### createTarget() with Explicit Package

Creates a new target element in a specific package. **Only needed for dynamic EMF models.**

```java
<T extends EObject> T createTarget(Class<T> targetClass, EPackage targetPackage)
```

```java
// For dynamic EMF only
EObject obj = ctx.createTarget(dynamicType, dynamicPackage);
```

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

> **Zeta-specific**: This method is a Zeta extension, not part of the original ETL specification. It provides a workaround for creating multiple target elements from a single source element.

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

Sets a single EPackage for target element creation. Clears any previously registered packages.

```java
void setTargetPackage(EPackage targetPackage)
```

```java
context.setTargetPackage(TargetPackage.eINSTANCE);
```

**Note**: This method does NOT include sub-packages for backward compatibility. Use `registerTargetPackage(pkg, true)` to include sub-packages.

### registerTargetPackage()

Registers an additional target EPackage without clearing existing packages.

```java
void registerTargetPackage(EPackage targetPackage)
void registerTargetPackage(EPackage targetPackage, boolean includeSubpackages)
```

```java
// Register single package (no sub-packages)
context.registerTargetPackage(UiPackage.eINSTANCE);

// Register package with all sub-packages (opt-in)
context.registerTargetPackage(RootPackage.eINSTANCE, true);
```

**Parameters**:
- `targetPackage` - The EPackage to register (required, non-null)
- `includeSubpackages` - If `true`, recursively registers all sub-packages

**Use Cases**:
- Transformations spanning multiple EMF packages
- Hierarchical package structures with nested packages
- Cross-module transformations

### getTargetPackages()

Returns all registered target packages as an unmodifiable list.

```java
List<EPackage> getTargetPackages()
```

```java
List<EPackage> packages = context.getTargetPackages();
for (EPackage pkg : packages) {
    System.out.println("Registered: " + pkg.getNsURI());
}
```

### setTransformationRegistry()

Sets the transformation registry.

```java
void setTransformationRegistry(TransformationRegistry registry)
```

## Package Resolution

### Generated Metamodels (Default)

For generated EMF metamodels, **no package registration is needed**:

```java
// Just use the generated Java class - EPackage is auto-discovered
Table table = ctx.createTarget(Table.class);
Column column = ctx.create(Column.class);
```

The framework finds the EPackage by looking for `*Package.eINSTANCE` in the same Java package.

### Dynamic EMF

For dynamic EMF models (created at runtime), register packages explicitly:

```java
context.registerTargetPackage(dynamicPackage);

// With sub-packages
context.registerTargetPackage(dynamicRootPackage, true);
```

Then use explicit package specification:

```java
EObject obj = ctx.createTarget(dynamicType, dynamicPackage);
```

## XMI ID Management

### applyAllPendingXmiIds()

Applies all pending XMI IDs to elements in the target resource.

```java
void applyAllPendingXmiIds()
```

```java
// After transformation completes
executor.transform(sourceElements);
ctx.applyAllPendingXmiIds();
```

**When to use**: Call this method after transformation completes to ensure all elements have their XMI IDs properly set. Elements added through containment references (not via `addToResource()`) may have pending IDs that were never applied during the normal commit phase.

**How it works**:
1. Iterates through all elements in the target resource
2. Checks the pending XMI ID map for each element
3. Applies any pending IDs that haven't been set yet

**Example**:

```java
TransformationResult result = executor.transform(sourceElements);

// Ensure all XMI IDs are applied, including those on
// elements added via containment references
ctx.applyAllPendingXmiIds();

// Now safe to serialize the target resource
targetResource.save(saveOptions);
```

---

**Previous**: [Annotations](annotations.md) | **Next**: [TransformationResult](transformation-result.md)
