# TransformationContext API

## Element Creation

```java
// Create target and add to Resource (standard approach)
Table table = ctx.createTarget(Table.class);

// Create without adding to Resource (for manual placement)
Table table = ctx.create(Table.class);
```

## Element Resolution

### equivalent() - Get Single Target
```java
// Returns cached target or triggers lazy rule
Column col = ctx.equivalent(attribute, Column.class);

// Returns null if no transformation exists
if (col == null) { /* handle missing */ }
```

### equivalents() - Get All Targets
```java
// When multiple rules produce same target type from source
List<Column> columns = ctx.equivalents(attribute, Column.class);
```

### equivalentDiscriminated() - Multiple Named Outputs
```java
// Store discriminated mapping
ctx.getElementResolutionCache().addDiscriminatedMapping(
    source, target, "RuleName", "discriminator");

// Retrieve discriminated mapping
Operation createOp = ctx.equivalentDiscriminated(
    entity, Operation.class, "CrudOps", "create");
```

## Parent Rule Execution

```java
// For rules with @Extends annotation
// Idempotent: same result on repeated calls
Table base = ctx.executeParentRule("BaseTransform", source);
```

## Resource Management

### Register Resources
```java
ctx.registerResource("asm", asmResourceSet);
ctx.registerResource("rdbms", rdbmsResourceSet);
ctx.registerResource("mapping", mappingResourceSet);
```

### Get Resource
```java
ResourceSet rs = ctx.getResource("asm");
```

### Query Elements

```java
// From specific alias
Collection<EntityType> entities = ctx.all("asm", EntityType.class);

// From default source
Collection<EntityType> entities = ctx.getAllSource(EntityType.class);

// From default target
Collection<Table> tables = ctx.getAllTarget(Table.class);
```

## Attributes (Session State)

```java
// Store session data
ctx.setAttribute("statistics", new HashMap<>());

// Retrieve session data
Map<String, Integer> stats = ctx.getAttribute("statistics");
```

## Extension Methods

```java
// Call extension method on specific object
List<Type> types = ctx.call(entity, "getAllSuperTypes");

// Call on current source element (inside rule)
List<Type> types = ctx.call("getAllSuperTypes");

// Clear extension cache (rarely needed)
ctx.clearExtensionCache();
```

## Current Source (Inside Rule)

```java
// Get current source being transformed (thread-safe)
EObject current = ctx.getCurrentSource();
```

## Cache Access

```java
ElementResolutionCache cache = ctx.getElementResolutionCache();

// Direct cache operations
cache.addMapping(source, "RuleName", target, isPrimary);
cache.getByRule(source, "RuleName");
cache.getEquivalent(source, TargetType.class);
cache.getEquivalents(source, TargetType.class);
```

## Staging (Parallel Execution)

```java
// Check if staging is active (parallel mode)
boolean staging = ctx.isStagingEnabled();

// Get count of staged elements
int count = ctx.getStagedElementCount();

// Get deterministic sequence for element (ordering)
long seq = ctx.getElementSequence(element);
```

## Registry Access

```java
// Get transformation registry
TransformationRegistry registry = ctx.getTransformationRegistry();

// Set registry (during setup)
ctx.setTransformationRegistry(registry);
```

## Target Package

```java
// Set target EPackage for element creation
ctx.setTargetPackage(RDBMSPackage.eINSTANCE);
```

## Complete Setup Example

```java
// Create context
TransformationContext context = new TransformationContext(
    modelProvider,      // EcoreModelProvider
    sourceResourceSet,  // Source ResourceSet
    targetResourceSet,  // Target ResourceSet  
    extensionRegistry   // ExtensionMethodRegistry
);

// Configure
context.setTargetPackage(RDBMSPackage.eINSTANCE);
context.setTransformationRegistry(registry);

// Register multiple resources
context.registerResource("asm", asmResourceSet);
context.registerResource("mapping", mappingResourceSet);
context.registerResource("rdbms", targetResourceSet);

// Ready for execution
```
