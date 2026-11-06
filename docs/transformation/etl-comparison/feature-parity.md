# ETL Feature Parity

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Feature Parity

Comparison of ETL and Zeta Transformation Framework features.

## Feature Matrix

| Feature | ETL | Zeta | Notes |
|---------|-----|------|-------|
| **Rule Definition** | | | |
| Basic rules | Yes | Yes | Full support |
| @lazy rules | Yes | Yes | `@Lazy` annotation |
| @abstract rules | Yes | Yes | `@Abstract` annotation |
| @primary rules | Yes | Yes | `@Primary` annotation |
| @greedy rules | Yes | Yes | `@Greedy` annotation |
| Rule inheritance (extends) | Yes | Yes | `@Extends` annotation |
| Guard conditions | Yes | Yes | `@Guard` annotation |
| **Element Resolution** | | | |
| equivalent() | Yes | Yes | `ctx.equivalent()` |
| equivalents() | Yes | Yes | `ctx.equivalents()` |
| equivalent("ruleName") | Yes | Partial | Via cache lookup |
| **Lifecycle** | | | |
| pre blocks | Yes | Yes | `@PreExecution` |
| post blocks | Yes | Yes | `@PostExecution` |
| **Collections** | | | |
| select/filter | Yes | Yes | Java streams |
| collect/map | Yes | Yes | Java streams |
| exists/anyMatch | Yes | Yes | Java streams |
| forAll/allMatch | Yes | Yes | Java streams |
| **Execution** | | | |
| Sequential execution | Yes | Yes | Default mode |
| Parallel execution | Partial | Yes | Automatic for large models |
| **Debugging** | | | |
| Step debugging | ETL debugger | Java debugger | Standard IDE tools |
| Trace output | Epsilon trace | JSON trace | Built-in export |
| **Type Safety** | | | |
| Compile-time checking | No | Yes | Major Zeta advantage |
| IDE autocomplete | Limited | Full | Standard Java support |

## Supported Features

### Fully Supported

- Basic transformation rules
- All rule modifiers (@lazy, @abstract, @primary, @greedy)
- Rule inheritance with @Extends
- Guard conditions
- Pre/post execution hooks
- Element resolution (equivalent/equivalents)
- Extension methods with caching
- Parallel execution

### Partially Supported

| Feature | ETL | Zeta Alternative |
|---------|-----|------------------|
| Multiple target elements | `to t1, t2, t3` | Use separate rules or discriminated equivalence (Zeta-specific workaround) |
| EOL operations | Built-in | Use Java methods or extension methods |
| Dynamic typing | Yes | Use generics or Object type |

## Unsupported ETL Features in Zeta

This section explicitly documents ETL features that are **NOT supported** in Zeta Transformation Framework, along with recommended workarounds and alternatives.

---

### 1. Multiple Target Types in One Rule

**ETL Feature**: A single transformation rule can create multiple target elements of different types.

**Status**: ❌ Not Supported

**ETL Syntax**:
```etl
rule Entity2TableAndView
    transform e : ESM!EntityType
    to t : PSM!Table, v : PSM!View {
    t.name = e.name;
    v.name = e.name + "View";
}
```

**Why Not Supported**: Zeta rules follow a single-source-single-target pattern for simpler semantics, better type safety, and parallel execution compatibility.

**Workarounds**:

1. **Use Multiple Rules**: Define separate rules for each target type
   ```java
   @TransformRule(name = "Entity2Table")
   public TransformFunction<EntityType, Table> entity2Table() {
       return (source, ctx) -> {
           Table table = PsmFactory.eINSTANCE.createTable();
           table.setName(source.getName());
           return table;
       };
   }
   
   @TransformRule(name = "Entity2View")
   public TransformFunction<EntityType, View> entity2View() {
       return (source, ctx) -> {
           View view = PsmFactory.eINSTANCE.createView();
           view.setName(source.getName() + "View");
           return view;
       };
   }
   ```

2. **Use Discriminated Equivalence** (Zeta-specific workaround): Create related elements with discriminator keys. Note that this is NOT part of the original ETL specification - it's a Zeta extension designed to work around the single-target limitation.
   ```java
   @TransformRule(name = "Entity2Table")
   public TransformFunction<EntityType, Table> entity2Table() {
       return (source, ctx) -> {
           Table table = ctx.createTarget(PsmPackage.Literals.TABLE);
           table.setName(source.getName());
           
           // Create related view using discriminated equivalent
           // This is a Zeta-specific workaround, not ETL standard
           View view = (View) ctx.equivalentDiscriminated(source, "view");
           if (view != null) {
               table.setRelatedView(view);
           }
           
           return table;
       };
   }
   ```

---

### 2. Dynamic Typing and Reflection

**ETL Feature**: ETL uses dynamic typing through EOL, allowing runtime type checks and property access by name.

**Status**: ❌ Not Supported (by design)

**ETL Syntax**:
```etl
rule GenericTransform
    transform s : Source!Element
    to t : Target!Element {
    // Dynamic property access
    if (s.isDefined("name")) {
        t.name = s.name;
    }
    
    // Runtime type checking
    if (s.isKindOf(Source!SpecialElement)) {
        t.special = true;
    }
}
```

**Why Not Supported**: Zeta uses compile-time type safety intentionally. Dynamic typing leads to runtime errors that are harder to diagnose.

**Workarounds**:

1. **Use Java Type Checking**: Leverage Java's instanceof and type casting
   ```java
   @TransformRule(name = "ElementTransform")
   public TransformFunction<Element, TargetElement> transformElement() {
       return (source, ctx) -> {
           TargetElement target = factory.createTargetElement();
           
           if (source.getName() != null) {
               target.setName(source.getName());
           }
           
           if (source instanceof SpecialElement) {
               target.setSpecial(true);
           }
           
           return target;
       };
   }
   ```

2. **Use EMF Reflection for Special Cases**: When dynamic access is truly needed
   ```java
   EStructuralFeature nameFeature = source.eClass().getEStructuralFeature("name");
   if (nameFeature != null && source.eIsSet(nameFeature)) {
       target.setName((String) source.eGet(nameFeature));
   }
   ```

---

### 3. EOL Built-in Operations

**ETL Feature**: ETL has access to EOL's rich library of collection and string operations.

**Status**: ⚠️ Partially Supported (via Java alternatives)

**ETL Syntax**:
```etl
// Collection operations
e.attributes.first()
e.attributes.select(a | a.isPrimaryKey)
e.attributes.collect(a | a.name)
e.attributes.flatten()
e.attributes.sortBy(a | a.name)

// String operations
e.name.firstToUpperCase()
e.name.split("_")

// Model queries
EntityType.all.select(t | t.name = self.name)
```

**Zeta Alternatives**:

| EOL Operation | Java Equivalent |
|--------------|-----------------|
| `collection.first()` | `collection.isEmpty() ? null : collection.get(0)` |
| `collection.select(pred)` | `collection.stream().filter(pred).toList()` |
| `collection.collect(mapper)` | `collection.stream().map(mapper).toList()` |
| `collection.flatten()` | `collection.stream().flatMap(Collection::stream).toList()` |
| `collection.sortBy(key)` | `collection.stream().sorted(Comparator.comparing(key)).toList()` |
| `collection.exists(pred)` | `collection.stream().anyMatch(pred)` |
| `collection.forAll(pred)` | `collection.stream().allMatch(pred)` |
| `string.firstToUpperCase()` | `StringUtils.capitalize(string)` or custom method |
| `Type.all` | `ctx.getAllInstances(Type.class)` |

**Utility Class Example**:
```java
public final class CollectionOps {
    public static <T> T first(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }
    
    public static <T> T last(List<T> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
    
    public static <T> List<T> select(List<T> list, Predicate<T> predicate) {
        return list.stream().filter(predicate).toList();
    }
    
    public static <T, R> List<R> collect(List<T> list, Function<T, R> mapper) {
        return list.stream().map(mapper).toList();
    }
}
```

---

### 4. Rule Overriding by Name

**ETL Feature**: Rules can override other rules with the same name from imported modules.

**Status**: ❌ Not Supported

**ETL Syntax**:
```etl
// base.etl
rule Entity2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    t.name = e.name;
}

// custom.etl
import "base.etl";

@override
rule Entity2Table
    transform e : ESM!EntityType
    to t : PSM!Table {
    t.name = "TBL_" + e.name.toUpperCase();
}
```

**Why Not Supported**: Zeta uses Java class inheritance for rule extension, not name-based overriding.

**Workarounds**:

1. **Use Java Inheritance**: Extend the base transformation class
   ```java
   public class BaseTransformations {
       @TransformRule(name = "Entity2Table")
       public TransformFunction<EntityType, Table> entity2Table() {
           return (source, ctx) -> {
               Table table = factory.createTable();
               table.setName(source.getName());
               return table;
           };
       }
   }
   
   public class CustomTransformations extends BaseTransformations {
       @Override
       @TransformRule(name = "Entity2Table")
       public TransformFunction<EntityType, Table> entity2Table() {
           return (source, ctx) -> {
               Table table = factory.createTable();
               table.setName("TBL_" + source.getName().toUpperCase());
               return table;
           };
       }
   }
   ```

2. **Use Template Method Pattern**: Allow subclasses to customize behavior
   ```java
   @TransformRule(name = "Entity2Table")
   public TransformFunction<EntityType, Table> entity2Table() {
       return (source, ctx) -> {
           Table table = factory.createTable();
           table.setName(formatTableName(source.getName()));
           return table;
       };
   }
   
   protected String formatTableName(String name) {
       return name;  // Override in subclass
   }
   ```

---

### 5. Bidirectional Transformations

**ETL Feature**: ETL (via FLOCK or custom code) can support bidirectional model updates.

**Status**: ❌ Not Supported

**Why Not Supported**: Zeta focuses on unidirectional source-to-target transformations. Bidirectional transformations require additional tracking and conflict resolution mechanisms.

**Workarounds**:

1. **Define Reverse Transformation**: Create a separate transformation for the reverse direction
   ```java
   // Forward: ESM -> PSM
   public class EsmToPsmTransformation { ... }
   
   // Reverse: PSM -> ESM
   public class PsmToEsmTransformation { ... }
   ```

2. **Use Traceability**: Leverage trace links to coordinate updates
   ```java
   // Store trace during forward transformation
   TransformationTrace trace = executor.getTrace();
   trace.export(traceFile);
   
   // Use trace in reverse transformation
   TransformationTrace trace = TransformationTrace.load(traceFile);
   ReverseTransformationExecutor reverseExecutor = 
       ReverseTransformationExecutor.builder()
           .trace(trace)
           .build();
   ```

---

### 6. Incremental Transformations

**ETL Feature**: Some Epsilon tools support incremental re-execution when source models change.

**Status**: ❌ Not Supported

**Why Not Supported**: Zeta re-executes the entire transformation. Change detection and partial re-execution are not implemented.

**Workarounds**:

1. **Full Re-execution**: For most use cases, full transformation is fast enough due to parallel execution
2. **External Change Detection**: Use model comparison tools to detect changes before transformation
3. **Selective Transformation**: Pass only changed elements to the transformation executor
   ```java
   List<EObject> changedElements = detectChanges(oldModel, newModel);
   executor.transform(changedElements);
   ```

---

### 7. Model Merging (Multiple Source Models)

**ETL Feature**: ETL can access multiple source models simultaneously.

**Status**: ⚠️ Partially Supported

**ETL Syntax**:
```etl
rule MergeEntities
    transform e1 : Model1!EntityType
    to t : Target!Entity {
    // Access elements from another source model
    var e2 = Model2!Extension.all.selectOne(x | x.name = e1.name);
    t.name = e1.name;
    if (e2.isDefined()) {
        t.description = e2.description;
    }
}
```

**Zeta Alternative**: Use TransformationContext to access multiple models
```java
@TransformRule(name = "MergeEntities")
public TransformFunction<EntityType, Entity> mergeEntities() {
    return (source, ctx) -> {
        Entity target = factory.createEntity();
        target.setName(source.getName());
        
        // Access second model through context
        List<Extension> extensions = ctx.getAllInstances(Extension.class);
        Extension ext = extensions.stream()
            .filter(e -> e.getName().equals(source.getName()))
            .findFirst()
            .orElse(null);
        
        if (ext != null) {
            target.setDescription(ext.getDescription());
        }
        
        return target;
    };
}
```

---

### 8. Interactive Transformations

**ETL Feature**: ETL can prompt for user input during transformation execution.

**Status**: ❌ Not Supported

**Why Not Supported**: Zeta transformations are designed for batch execution. User interaction would break parallel execution and create non-deterministic results.

**Workarounds**:

1. **Pre-collect User Input**: Gather decisions before transformation starts
   ```java
   Map<String, String> userDecisions = collectUserInput(sourceElements);
   ctx.putCached(CacheKey.of("decisions"), userDecisions);
   executor.transform(sourceElements);
   ```

2. **Two-Phase Approach**: First phase identifies decision points, second phase applies decisions
   ```java
   // Phase 1: Identify ambiguous mappings
   List<DecisionPoint> decisions = analyzer.findDecisionPoints(sourceModel);
   
   // User reviews and decides
   for (DecisionPoint dp : decisions) {
       dp.setChoice(getUserChoice(dp));
   }
   
   // Phase 2: Execute with decisions
   ctx.putCached(CacheKey.of("decisions"), decisions);
   executor.transform(sourceModel);
   ```

---

### 9. Transformation Annotations in Model

**ETL Feature**: ETL can read and write annotations stored in the model itself.

**Status**: ⚠️ Partially Supported

**Zeta Alternative**: Use EMF's EAnnotation mechanism directly
```java
@TransformRule(name = "AnnotatedTransform")
public TransformFunction<EntityType, Table> annotatedTransform() {
    return (source, ctx) -> {
        Table table = factory.createTable();
        
        // Read source annotation
        EAnnotation annotation = source.getEAnnotation("http://custom/annotation");
        if (annotation != null) {
            String value = annotation.getDetails().get("key");
            // Use annotation value
        }
        
        // Add target annotation
        EAnnotation targetAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
        targetAnnotation.setSource("http://transform/trace");
        targetAnnotation.getDetails().put("sourceId", 
            EcoreUtil.getID(source));
        table.getEAnnotations().add(targetAnnotation);
        
        return table;
    };
}
```

---

### 10. Native EOL Expressions in Rules

**ETL Feature**: ETL uses EOL expressions which have different syntax than Java.

**Status**: ❌ Not Supported (by design)

**Common Syntax Differences**:

| EOL Expression | Java Equivalent |
|---------------|-----------------|
| `self.name` | `source.getName()` |
| `self.isDefined()` | `source != null` |
| `self.isUndefined()` | `source == null` |
| `self.attributes.size()` | `source.getAttributes().size()` |
| `a and b` | `a && b` |
| `a or b` | `a \|\| b` |
| `not a` | `!a` |
| `a = b` (comparison) | `Objects.equals(a, b)` |
| `a := b` (assignment) | `a = b` |
| `a.println()` | `System.out.println(a)` |

**Why Not Supported**: Zeta intentionally uses Java for type safety, IDE support, and debugging capabilities.

---

## Known ETL Bugs - Compound XMI IDs

Zeta intentionally **does not replicate** certain ETL behaviors that are bugs, not features.

### Compound XMI ID Generation Bug

**ETL Behavior (BUG)**: When multiple rules call `equivalent(source, "RuleName")` for the same source element, ETL includes the **calling context** in its cache key. This leads to:

1. **Multiple targets created** for the same (source, rule) pair
2. **Compound XMI IDs** like `((esm/Operation)_CreateOperationBody)_((esm/Transfer)_CreateOperationBody)`
3. **Non-idempotent behavior** violating transformation correctness

**Example of ETL Bug**:
```
// Rule A calls: equivalent(op, "CreateOperationBody")
// Rule B calls: equivalent(op, "CreateOperationBody")

// ETL creates TWO targets with compound IDs:
// ((esm/A_context)/op)_CreateOperationBody
// ((esm/B_context)/op)_CreateOperationBody
```

**Zeta Correct Behavior**: The cache key is computed from `(source, ruleName)` only. The calling context is **intentionally excluded**.

```
// Rule A calls: ctx.equivalent(op, "CreateOperationBody")
// Rule B calls: ctx.equivalent(op, "CreateOperationBody")

// Zeta creates ONE target with simple ID:
// (esm/op)/CreateOperationBody

// Both callers receive the SAME target instance
```

### Why This is Correct

Idempotent caching requires that `equivalent(source, "RuleName")` always returns the same target for a given source, regardless of which rule is asking. This ensures:

| Property | ETL (Bug) | Zeta (Correct) |
|----------|-----------|----------------|
| XMI ID format | Compound (context-dependent) | Simple (context-independent) |
| Cache hits | Depends on caller | Deterministic |
| Object graph | Varies by execution order | Consistent |
| Rule execution | May execute multiple times | Exactly once per (source, rule) |

### Expected XMI ID Differences

When comparing Zeta output to ETL, some XMI ID differences are **expected and correct**:

| Pattern | ETL Output | Zeta Output | Explanation |
|---------|------------|-------------|-------------|
| Multi-context targets | `((A)/x)_((B)/x)` | `(source)/Rule` | Zeta avoids compound IDs |
| Nested equivalent() calls | Compound hierarchy | Simple ID | Idempotent caching |

These differences indicate Zeta's **correct** behavior, not bugs.

### Test Coverage

The `IdempotentCachingTest.java` verifies this correct behavior:
- `testSameSourceSameTargetDifferentCallers`: Multiple callers get same target
- `testCacheKeyIsContextIndependent`: Cache key excludes calling context
- `testXmiIdIsSimpleFormat`: No compound IDs generated

---

## Summary: Unsupported Features Quick Reference

| Feature | Status | Recommended Alternative |
|---------|--------|------------------------|
| Multiple target types | ❌ | Use separate rules |
| Dynamic typing | ❌ | Use Java type system |
| EOL operations | ⚠️ | Use Java Streams |
| Rule overriding | ❌ | Use Java inheritance |
| Bidirectional transforms | ❌ | Define reverse transformation |
| Incremental transforms | ❌ | Full re-execution (fast with parallel) |
| Model merging | ⚠️ | Access via TransformationContext |
| Interactive transforms | ❌ | Pre-collect input or two-phase |
| Model annotations | ⚠️ | Use EMF EAnnotation API |
| EOL expressions | ❌ | Use Java expressions |

**Legend**:
- ❌ = Not supported
- ⚠️ = Partially supported or has workaround

## Future Roadmap

Potential future additions:
- Bidirectional transformations
- Incremental transformations (re-execute only changed elements)
- Model merging (multiple source models)
- Validation integration with validation-core
- Transformation composition (chaining)

---

**Previous**: [Migration Guide](migration-guide.md) | **Next**: [Examples](../examples/simple-transformations.md)
