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
// Thread-safe and atomic: concurrent calls return same result
Table base = ctx.executeParentRule("BaseTransform", source);
```

**Thread-Safety**: Uses atomic `getOrCreate()` pattern internally. Multiple concurrent calls for the same `(source, parentRuleName)` pair execute the parent rule exactly once - the first thread runs it, others wait and get the cached result.

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

### Atomic getOrCreate() (Thread-Safe Pattern)

```java
// Atomic check-and-execute: prevents duplicate target creation
T target = cache.getOrCreate(source, ruleName, () -> {
    // Supplier executes ONLY on cache miss
    // Guard evaluation and rule execution happen inside lock
    if (!evaluateGuard(source)) {
        return null;  // Rejection cached, won't re-evaluate
    }
    return executeRule(source);
}, isPrimary);
```

**Signature**:
```java
<T extends EObject> T getOrCreate(
    EObject source,        // Source element (key part 1)
    String ruleName,       // Rule name (key part 2)
    Supplier<T> supplier,  // Executes under lock on cache miss
    boolean isPrimary      // Mark as primary transformation
)
```

**Thread-Safety Guarantees**:
- Per-key locking: `(source, ruleName)` pairs lock independently
- First thread executes supplier, others wait and get cached result
- If supplier returns `null`, rejection is cached (guards won't re-run)
- Fast path: cache hit returns without locking

**When Supplier Executes**:
- Only on cache miss (after acquiring per-key lock)
- Never executes more than once per `(source, ruleName)` pair
- Rejection (`null` return) is cached to avoid redundant guard evaluation

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

## Structured XMI IDs

ETL-style structured IDs provide meaningful, traceable identifiers.

### Configuration

```java
// Enable/disable (default: true)
ctx.setUseStructuredIds(true);

// Check if enabled
boolean enabled = ctx.isUseStructuredIds();

// Include element name prefix (default: false)
ctx.setIncludeElementNameInStructuredIds(true);

// Set preferred source alias for ID generation
ctx.registerResource("esm", sourceResourceSet);
ctx.setPreferredSourceAlias("esm");
```

### ID Format

Default format (element name excluded):
```
(<alias>/<source-id>)/<rule-name>
```

**Example**: `(esm/_abc123)/Entity2Package`

With element name included (`setIncludeElementNameInStructuredIds(true)`):
```
<element-name>/(<alias>/<source-id>)/<rule-name>
```

**Example**: `Customer/(esm/_abc123)/Entity2Package`

For discriminated:
```
(<alias>/<source-id>)/<rule-name>/(discriminator/<value>)
```

### Edge Cases (Falls Back to `_seqN`)

| Condition | Result |
|-----------|--------|
| `createTarget()` outside rule | `_seqN` |
| Source not in Resource | `_<hashcode>` in path |
| Source has no XMI ID | `_<hashcode>` in path |
| `useStructuredIds=false` | `_seqN` |

### Ensuring Structured IDs Work

```java
// 1. Set XMI IDs on source elements
((XMIResource) sourceResource).setID(entity, "_id123");

// 2. Only call createTarget() inside rules (not static helpers)

// 3. Use setPreferredSourceAlias() for multi-resource scenarios
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
