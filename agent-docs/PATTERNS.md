# Advanced Transformation Patterns

## Rule Inheritance

### Abstract Parent Rule
```java
@TransformRule(name = "BaseEntity2Table")
@Abstract
public TransformFunction<EntityType, Table> baseEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(source.getName());
        // Common logic
        return table;
    };
}
```

### Child Rule with @Extends
```java
@TransformRule(name = "Entity2Table")
@Extends({"BaseEntity2Table"})
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        // Execute parent first (idempotent)
        Table table = ctx.executeParentRule("BaseEntity2Table", source);
        
        // Add child-specific logic
        table.setSchema("public");
        return table;
    };
}
```

### Multiple Inheritance
```java
@TransformRule(name = "FullEntity2Table")
@Extends({"BaseEntity2Table", "AuditableEntity2Table"})
public TransformFunction<EntityType, Table> fullEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.executeParentRule("BaseEntity2Table", source);
        ctx.executeParentRule("AuditableEntity2Table", source);
        // Combine results
        return table;
    };
}
```

**Key**: `executeParentRule()` is idempotent and atomic - same result on repeated calls, even from multiple concurrent threads.

## Multi-Source (Cartesian Product)

### Two Sources
```java
@TransformRule(name = "EntityMappingToTable")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@To(alias = "rdbms", type = Table.class)
public MultiSourceTransformFunction<Table> entityMappingToTable() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];  // Order matches @Transform order
        TypeMapping mapping = (TypeMapping) sources[1];
        
        Table table = ctx.createTarget(Table.class);
        table.setName(mapping.mapName(entity.getName()));
        return table;
    };
}
```

### Three Sources
```java
@TransformRule(name = "ThreeWayTransform")
@Transform(alias = "model", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@Transform(alias = "rules", type = ValidationRule.class)
public MultiSourceTransformFunction<Table> threeWayTransform() {
    return (sources, ctx) -> {
        EntityType entity = (EntityType) sources[0];
        TypeMapping mapping = (TypeMapping) sources[1];
        ValidationRule rule = (ValidationRule) sources[2];
        // ...
    };
}
```

### Multi-Source Guard
```java
@TransformRule(name = "GuardedMultiSource")
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
@Guard(method = "matchesMapping")
public MultiSourceTransformFunction<Table> guardedMultiSource() {
    return (sources, ctx) -> { /* ... */ };
}

// Guard receives array
private boolean matchesMapping(EObject[] sources, TransformationContext ctx) {
    EntityType entity = (EntityType) sources[0];
    TypeMapping mapping = (TypeMapping) sources[1];
    return mapping.appliesTo(entity);
}
```

## Lazy Rules

### Define Lazy Rule
```java
@TransformRule(name = "Attribute2Column")
@Lazy
public TransformFunction<Attribute, Column> attribute2Column() {
    return (source, ctx) -> {
        Column col = ctx.createTarget(Column.class);
        col.setName(source.getName());
        return col;
    };
}
```

### Trigger Lazy Execution
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        for (Attribute attr : source.getAttributes()) {
            // Triggers lazy rule if not cached
            Column col = ctx.equivalent(attr, Column.class);
            if (col != null) {
                table.getColumns().add(col);
            }
        }
        return table;
    };
}
```

**Use case**: Transform elements only when actually needed.

## Greedy Matching

### Default (Exact Type)
```java
@TransformRule(name = "EntityType2Table")
@Transform(type = EntityType.class)  // Matches ONLY EntityType
public TransformFunction<EntityType, Table> entityType2Table() { }
```

### Greedy (Type + Subtypes)
```java
@TransformRule(name = "NamedElement2Named")
@Transform(type = NamedElement.class)
@Greedy  // Matches NamedElement, EntityType, DataType, etc.
public TransformFunction<NamedElement, Named> namedElement2Named() { }
```

## Activity-Based Greedy (ETL Compatibility)

Standard `@Greedy @Lazy` processes ALL matching elements during lazy phase.
With `@ActivityBased`, only elements activated via `equivalent()` are processed.

### Standard @Greedy @Lazy (Processes All)
```java
@TransformRule(name = "Type2Element")
@Greedy
@Lazy
public TransformFunction<Type, Element> type2Element() { }
// Processes ALL Type elements and subtypes during lazy phase
```

### Activity-Based (Processes Only Activated)
```java
@TransformRule(name = "Type2Element")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<Type, Element> type2Element() { }
// ONLY processes elements that were passed to ctx.equivalent()
```

### When to Use
- **Standard**: When all matching elements should be transformed
- **ActivityBased**: When orphan/unreferenced elements should be skipped

### ETL Compatibility Mode Alternative
```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // All @Greedy @Lazy rules become activity-based
    .build();
```

## Primary Rules

When multiple rules produce same target type:

```java
@TransformRule(name = "Entity2Table")
@Primary  // This result returned by equivalent()
public TransformFunction<EntityType, Table> entity2Table() { }

@TransformRule(name = "Entity2AuditTable")
public TransformFunction<EntityType, Table> entity2AuditTable() { }
```

```java
// Returns result from @Primary rule
Table table = ctx.equivalent(entity, Table.class);

// Returns all results
List<Table> tables = ctx.equivalents(entity, Table.class);
```

## Discriminated Equivalence

Multiple named outputs from single source:

```java
@TransformRule(name = "CrudOperations")
public TransformFunction<EntityType, Operation> crudOperations() {
    return (source, ctx) -> {
        ElementResolutionCache cache = ctx.getElementResolutionCache();
        
        for (String op : Arrays.asList("create", "read", "update", "delete")) {
            Operation operation = ctx.createTarget(Operation.class);
            operation.setName(op + source.getName());
            
            // Store with discriminator
            cache.addDiscriminatedMapping(source, operation, "CrudOperations", op);
        }
        
        return null;  // No single return value
    };
}
```

Retrieval:
```java
Operation create = ctx.equivalentDiscriminated(
    entity, Operation.class, "CrudOperations", "create");
Operation update = ctx.equivalentDiscriminated(
    entity, Operation.class, "CrudOperations", "update");
```

## Extension Methods

### Define Extension Class
```java
@ExtensionMethod(EntityType.class)
public class EntityTypeExtensions {
    
    @Cached  // Cache per EntityType instance
    public List<Attribute> getAllInheritedAttributes(EntityType self) {
        List<Attribute> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        while (current != null) {
            result.addAll(current.getAttributes());
            current = current.getSuperType();
        }
        return result;
    }
}
```

### Use in Rule
```java
@TransformRule(name = "Entity2Table")
public TransformFunction<EntityType, Table> entity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        
        // Call extension method
        List<Attribute> inherited = ctx.call(source, "getAllInheritedAttributes");
        for (Attribute attr : inherited) {
            // ...
        }
        return table;
    };
}
```

### Register Extensions
```java
ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
extensionRegistry.register(EntityTypeExtensions.class);

TransformationContext context = new TransformationContext(
    provider, sourceRS, targetRS, extensionRegistry);
```

## Lifecycle Hooks

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class MyTransform {
    
    private Map<String, Integer> stats;
    
    @PreExecution
    public void setup(TransformationContext ctx) {
        stats = new HashMap<>();
        ctx.setAttribute("startTime", System.currentTimeMillis());
    }
    
    @TransformRule(name = "Entity2Table")
    public TransformFunction<EntityType, Table> entity2Table() {
        return (source, ctx) -> {
            stats.merge("entities", 1, Integer::sum);
            // ...
        };
    }
    
    @PostExecution
    public void cleanup(TransformationContext ctx) {
        long duration = System.currentTimeMillis() -
            (Long) ctx.getAttribute("startTime");
        log.info("Transformed {} entities in {}ms",
            stats.get("entities"), duration);
    }
}
```

## Thread-Safe Transformation Pattern

Rules execute in parallel when element count >= 1000. Write rules that are thread-safe.

### Safe Operations
```java
@TransformRule(name = "SafeRule")
public TransformFunction<EntityType, Table> safeRule() {
    return (source, ctx) -> {
        // SAFE: All these operations are thread-safe
        Table table = ctx.createTarget(Table.class);    // Thread-safe
        table.setName(source.getName());                 // Set own properties

        Column col = ctx.equivalent(attr, Column.class); // Atomic
        table.getColumns().add(col);                     // Modify own element

        Table parent = ctx.executeParentRule("Base", source); // Atomic

        String mode = ctx.getAttribute("mode");          // Thread-safe read
        return table;
    };
}
```

### Unsafe Operations to Avoid
```java
// BAD: Shared mutable state
private List<String> processedNames = new ArrayList<>();  // Non-thread-safe

@TransformRule(name = "UnsafeRule")
public TransformFunction<EntityType, Table> unsafeRule() {
    return (source, ctx) -> {
        // UNSAFE: Modifying shared state
        processedNames.add(source.getName());  // Race condition!

        // UNSAFE: Direct Resource modification
        ctx.getTargetResourceSet().getResources().get(0)
            .getContents().add(element);  // Race condition!

        // UNSAFE: Static field access
        GlobalCounter.increment();  // Race condition!

        return null;
    };
}
```

### Thread-Safe Shared State Pattern
```java
// Use ConcurrentHashMap for shared state
private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

@TransformRule(name = "ThreadSafeCounter")
public TransformFunction<EntityType, Table> threadSafeCounter() {
    return (source, ctx) -> {
        // SAFE: Atomic operations on concurrent map
        counters.computeIfAbsent(source.getName(), k -> new AtomicInteger())
                .incrementAndGet();
        // ...
    };
}
```

## Migration from Direct EMF Factory Pattern

When migrating existing transformations (like tatami-base) from direct EMF factory usage to Zeta's thread-safe patterns.

### Before: Direct Factory (Parallel-Unsafe)

```java
// OLD PATTERN - NOT thread-safe for parallel execution
public class Asm2RdbmsTransformation {
    private RdbmsFactory rdbmsFactory = RdbmsFactory.eINSTANCE;
    private Resource targetResource;

    public void transformEntityClass(EClass eClass) {
        // PROBLEM 1: Direct factory bypasses staging
        RdbmsTable table = rdbmsFactory.createRdbmsTable();

        // PROBLEM 2: Direct property setting (OK if own element)
        table.setName(eClass.getName());
        table.setSqlName(toSnakeCase(eClass.getName()));

        // PROBLEM 3: Direct Resource modification - race condition!
        targetResource.getContents().add(table);

        // PROBLEM 4: Direct child creation without staging
        RdbmsIdentifierField idField = rdbmsFactory.createRdbmsIdentifierField();
        idField.setName(eClass.getName() + "_id");
        table.getFields().add(idField);
        table.setPrimaryKey(idField);
    }
}
```

### After: Zeta Pattern (Parallel-Safe)

```java
// NEW PATTERN - Thread-safe for parallel execution
@TransformationContext(source = EClass.class, target = RdbmsTable.class)
public class Asm2RdbmsZetaTransform {

    @TransformRule(name = "EClass2RdbmsTable")
    @Transform(type = EClass.class)
    @To(type = RdbmsTable.class)
    public TransformFunction<EClass, RdbmsTable> eClass2RdbmsTable() {
        return (eClass, ctx) -> {
            // SAFE: createTarget() uses staging mechanism
            RdbmsTable table = ctx.createTarget(RdbmsTable.class);

            // SAFE: Setting properties on own element
            table.setName(eClass.getName());
            table.setSqlName(toSnakeCase(eClass.getName()));

            // SAFE: Create contained element (no staging needed)
            RdbmsIdentifierField idField = ctx.create(RdbmsIdentifierField.class);
            idField.setName(eClass.getName() + "_id");

            // SAFE: Add to own element's list
            table.getFields().add(idField);
            table.setPrimaryKey(idField);

            // Return element - staging adds to Resource automatically
            return table;
        };
    }
}
```

### Key Migration Steps

| Step | Before (Unsafe) | After (Safe) |
|------|-----------------|--------------|
| **1. Element Creation** | `factory.createXxx()` | `ctx.createTarget(Xxx.class)` |
| **2. Contained Elements** | `factory.createXxx()` | `ctx.create(Xxx.class)` |
| **3. Resource Addition** | `resource.getContents().add(e)` | Return from rule (automatic) |
| **4. Caching/Tracing** | Manual `Map<Source, Target>` | `ctx.equivalent()` (automatic) |
| **5. Lookup Existing** | Manual map lookup | `ctx.equivalent(source, Type.class)` |

### Handling Manual Orchestration (Non-Rule Based)

For transformations that don't use `@TransformRule` but manually orchestrate:

```java
// Before: Manual orchestration with direct factory
public void execute() {
    for (EClass eClass : sourceModel.getContents()) {
        transformEntityClass(eClass);  // Uses factory directly
    }
}

// After: Manual orchestration with ctx helper methods
public void execute(TransformationContext ctx) {
    for (EClass eClass : sourceModel.getContents()) {
        // Use ctx even in manual orchestration
        RdbmsTable table = ctx.createTarget(RdbmsTable.class);
        table.setName(eClass.getName());

        // For contained elements
        RdbmsField field = ctx.create(RdbmsField.class);
        table.getFields().add(field);

        // Manual tracing if needed
        ctx.getElementResolutionCache().addMapping(
            eClass, "EClass2Table", table, true);
    }
}
```

### Synchronized Helper Pattern (For Legacy Code)

If full migration is not possible, use synchronized helpers:

```java
// Helper class for thread-safe EMF operations
public class TransformationHelper {

    public static <T extends EObject> void synchronizedAdd(
            EList<T> list, T element) {
        synchronized (list) {
            list.add(element);
        }
    }

    public static void addToResource(Resource resource, EObject element) {
        synchronized (resource) {
            if (!resource.getContents().contains(element)) {
                resource.getContents().add(element);
            }
        }
    }

    public static void setXmiId(EObject element, String id) {
        Resource resource = element.eResource();
        if (resource instanceof XMLResource) {
            synchronized (resource) {
                ((XMLResource) resource).setID(element, id);
            }
        }
    }
}

// Usage in legacy code
RdbmsTable table = rdbmsFactory.createRdbmsTable();
table.setName(eClass.getName());
TransformationHelper.addToResource(targetResource, table);  // Thread-safe
```

**Note**: Prefer full migration to `ctx.createTarget()` pattern. Synchronized helpers should be temporary until migration is complete.
