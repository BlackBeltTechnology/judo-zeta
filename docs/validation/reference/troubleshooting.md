# Troubleshooting

**Navigation**: [Documentation Hub](../../index.md) > [Reference](annotations.md) > Troubleshooting

This guide covers common issues, error messages, and solutions when working with the Judo Zeta Validation Framework. Each issue includes symptoms, root causes, solutions with code examples, and prevention tips.

## Quick Diagnostic Checklist

Before diving into specific issues, verify these basics:

- [ ] `@ValidationContext` annotation present on validator class
- [ ] Element type in `@ValidationContext` matches validated elements
- [ ] Validator class registered with `ValidationRegistry`
- [ ] All required EMF bundles installed (OSGi environments)
- [ ] Guard method signatures match expected format
- [ ] `@Satisfies` constraint names match exactly
- [ ] Extension methods have correct element type
- [ ] Debug logging enabled for diagnostics

## 1. Rules Not Executing

### Symptoms

- Validation completes successfully but no results returned
- Expected constraint failures not reported
- Rules appear to be skipped silently
- No errors or warnings in logs

### Root Cause 1: Missing @ValidationContext Annotation

The most common cause—validator class not annotated with `@ValidationContext`.

**Problem code:**
```java
// ❌ Missing @ValidationContext annotation
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Name required");
        };
    }
}
```

**Solution:**
```java
// ✓ Add @ValidationContext with correct element type
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Name required");
        };
    }
}
```

**Prevention:**
- Always annotate validator classes with `@ValidationContext`
- Use IDE templates/snippets for validator class scaffolding
- Enable compiler warnings for missing annotations

### Root Cause 2: Not Registered with ValidationRegistry

Validator class exists but not registered with the registry.

**Problem code:**
```java
ValidationRegistry registry = new ValidationRegistry();
// ❌ Forgot to register validator class
// registry.register(EntityTypeValidations.class);

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

// No validations execute - registry is empty
List<ValidationResult> results = executor.validate(elements);
```

**Solution:**
```java
ValidationRegistry registry = new ValidationRegistry();

// ✓ Register all validator classes
registry.register(EntityTypeValidations.class);
registry.register(AttributeValidations.class);
registry.register(OperationValidations.class);

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

List<ValidationResult> results = executor.validate(elements);
```

**Prevention:**
- Call `registry.register()` for each validator class
- Use a central registration method:
  ```java
  public static void registerAllValidators(ValidationRegistry registry) {
      registry.register(EntityTypeValidations.class);
      registry.register(AttributeValidations.class);
      // ... all validators
  }
  ```
- Enable debug logging to verify registration:
  ```xml
  <logger name="hu.blackbelt.judo.zeta.validation.core.ValidationRegistry" level="DEBUG"/>
  ```

### Root Cause 3: Element Type Mismatch

The `@ValidationContext` element type doesn't match the actual elements being validated.

**Problem code:**
```java
// ❌ Validator declared for EntityType
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "ValidAttribute", message = "...")
    public ValidationRule validAttribute() {
        return (element, ctx) -> {
            // Element is actually Attribute, not EntityType
            Attribute attr = (Attribute) element;  // ClassCastException!
            return ValidationResult.pass();
        };
    }
}

// Validating Attribute instances
List<EObject> attributes = model.getAllInstances(Attribute.class);
executor.validate(attributes);  // Rules don't execute - type mismatch
```

**Solution:**
```java
// ✓ Use correct element type in @ValidationContext
@ValidationContext(Attribute.class)
public class AttributeValidations {
    
    @Constraint(name = "ValidAttribute", message = "...")
    public ValidationRule validAttribute() {
        return (element, ctx) -> {
            Attribute attr = (Attribute) element;  // Correct type
            return ValidationResult.pass();
        };
    }
}
```

**Prevention:**
- Match `@ValidationContext` type to elements being validated
- Use one validator class per element type
- Enable debug logging to see which rules are executed:
  ```
  DEBUG ValidationExecutor - Validating 100 elements of type Attribute
  DEBUG ValidationExecutor - Found 5 validators for Attribute
  ```

## 2. ClassNotFoundException in OSGi

### Symptoms

```
java.lang.ClassNotFoundException: org.eclipse.emf.ecore.EObject
java.lang.NoClassDefFoundError: org/eclipse/emf/common/util/URI
Bundle cannot be resolved
```

### Root Cause: Missing EMF Bundles

OSGi container doesn't have required EMF bundles installed.

**Required bundles:**
- `org.eclipse.emf.ecore` (version 2.21+)
- `org.eclipse.emf.common` (version 2.21+)
- `org.eclipse.emf.ecore.xmi` (version 2.16+)

**Solution for Apache Karaf:**

```bash
# Install EMF feature
karaf@root()> feature:repo-add mvn:org.apache.karaf.features/enterprise/4.4.7/xml/features

# Install EMF bundles
karaf@root()> feature:install emf

# Or install bundles individually
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.ecore/2.38.0
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.common/2.30.0
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.ecore.xmi/2.39.0

# Verify bundles are active
karaf@root()> bundle:list | grep emf
```

**Solution for Eclipse/P2:**

```bash
# Add EMF update site
Help -> Install New Software
Add: https://download.eclipse.org/modeling/emf/emf/builds/release/

# Install:
- EMF - Eclipse Modeling Framework SDK
- EMF - Eclipse Modeling Framework Runtime and Tools
```

**Solution for Maven (standalone):**

```xml
<dependencies>
    <dependency>
        <groupId>org.eclipse.emf</groupId>
        <artifactId>org.eclipse.emf.ecore</artifactId>
        <version>2.38.0</version>
    </dependency>
    <dependency>
        <groupId>org.eclipse.emf</groupId>
        <artifactId>org.eclipse.emf.common</artifactId>
        <version>2.30.0</version>
    </dependency>
    <dependency>
        <groupId>org.eclipse.emf</groupId>
        <artifactId>org.eclipse.emf.ecore.xmi</artifactId>
        <version>2.39.0</version>
    </dependency>
</dependencies>
```

**Verification:**

```java
// Test EMF availability
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcorePackage;

public class EMFTest {
    public static void main(String[] args) {
        System.out.println("EMF Ecore Package: " + EcorePackage.eINSTANCE);
        System.out.println("EMF available: OK");
    }
}
```

**Prevention:**
- Use OSGi feature files to declare dependencies
- Include EMF in your Karaf feature definition:
  ```xml
  <feature name="my-validator" version="1.0.0">
      <feature>emf</feature>
      <bundle>mvn:my.group/my-validator/1.0.0</bundle>
  </feature>
  ```
- Document EMF version requirements in README

## 3. Validation Not Finding Elements

### Symptoms

- Rules execute but don't find expected elements
- `ctx.getAllInstances()` returns empty list
- Cross-reference validations fail to find targets
- Model appears to be empty

### Root Cause 1: Wrong Element Type in @ValidationContext

**Problem code:**
```java
@ValidationContext(EObject.class)  // ❌ Too generic
public class EntityValidations {
    
    @Constraint(name = "CheckEntity", message = "...")
    public ValidationRule checkEntity() {
        return (element, ctx) -> {
            // Looking for EntityType instances
            List<EntityType> entities = ctx.getAllInstances(EntityType.class);
            
            // Returns empty - validator only registered for EObject,
            // not for specific EntityType class
            System.out.println("Found entities: " + entities.size());  // 0
            
            return ValidationResult.pass();
        };
    }
}
```

**Solution:**
```java
// ✓ Use specific element type
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "CheckEntity", message = "...")
    public ValidationRule checkEntity() {
        return (element, ctx) -> {
            List<EntityType> entities = ctx.getAllInstances(EntityType.class);
            System.out.println("Found entities: " + entities.size());  // Correct count
            return ValidationResult.pass();
        };
    }
}
```

**Prevention:**
- Use specific element types in `@ValidationContext`
- Only use `EObject.class` for rules that apply to all element types

### Root Cause 2: Model Not Loaded Properly

**Problem code:**
```java
// ❌ Model not loaded into ResourceSet
ResourceSet resourceSet = new ResourceSetImpl();
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .modelProvider(new SimpleModelProvider(resourceSet))
    .build();

// Validating elements not in ResourceSet
List<EObject> elements = createElementsInMemory();
executor.validate(elements);  // Model provider can't find related elements
```

**Solution:**
```java
// ✓ Load model into ResourceSet
ResourceSet resourceSet = new ResourceSetImpl();
resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put(
    "xmi", new XMIResourceFactoryImpl()
);

Resource resource = resourceSet.createResource(URI.createFileURI("model.xmi"));
resource.load(Collections.emptyMap());

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .modelProvider(new SimpleModelProvider(resourceSet))
    .build();

// Validate elements from loaded resource
List<EObject> elements = resource.getContents();
executor.validate(elements);  // Model provider can traverse full model
```

**Prevention:**
- Always load models into ResourceSet before validation
- Verify resource is loaded:
  ```java
  System.out.println("Resource loaded: " + resource.isLoaded());
  System.out.println("Root elements: " + resource.getContents().size());
  ```
- Enable EMF debug logging:
  ```xml
  <logger name="org.eclipse.emf" level="DEBUG"/>
  ```

## 4. Guards Not Working

### Symptoms

- Rules execute even when guard condition is false
- `@Guard` annotation appears to be ignored
- Guard method throws NullPointerException
- Guard method not found error

### Root Cause 1: Wrong Guard Method Signature

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    @Guard(method = "isNotAbstract")
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // ❌ Wrong signature - missing ValidationContext parameter
    public boolean isNotAbstract(EObject element) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Error:**
```
java.lang.IllegalStateException: Guard method 'isNotAbstract' not found or has incorrect signature
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    @Guard(method = "isNotAbstract")
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // ✓ Correct signature
    public boolean isNotAbstract(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Valid guard signatures:**
```java
// Option 1: With context
public boolean guardName(EObject element, ValidationContext ctx) { }

// Option 2: Without context (also supported)
public boolean guardName(EObject element) { }
```

**Prevention:**
- Always include `ValidationContext ctx` parameter (recommended)
- Copy guard method template from documentation
- Enable compiler warnings for unused parameters

### Root Cause 2: Guard Method Not Found

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    @Guard(method = "isNotAbstract")  // ❌ Method name typo
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> { /* ... */ };
    }
    
    // Method name doesn't match @Guard annotation
    public boolean isNotAbstact(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    @Guard(method = "isNotAbstract")  // ✓ Matches method name exactly
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> { /* ... */ };
    }
    
    public boolean isNotAbstract(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Prevention:**
- Use constants for guard method names:
  ```java
  public static final String GUARD_IS_NOT_ABSTRACT = "isNotAbstract";
  
  @Guard(method = GUARD_IS_NOT_ABSTRACT)
  public ValidationRule concreteMustHaveTable() { }
  
  public boolean isNotAbstract(EObject element, ValidationContext ctx) { }
  ```
- Use IDE refactoring to rename methods (updates string references)

### Root Cause 3: NullPointerException in Guard

**Problem code:**
```java
public boolean hasContainer(EObject element, ValidationContext ctx) {
    // ❌ No null check - throws NPE if container is null
    return element.eContainer().eClass().getName().equals("Package");
}
```

**Solution:**
```java
public boolean hasContainer(EObject element, ValidationContext ctx) {
    // ✓ Defensive null checks
    if (element == null) {
        return false;
    }
    
    EObject container = element.eContainer();
    if (container == null) {
        return false;
    }
    
    EClass containerClass = container.eClass();
    if (containerClass == null) {
        return false;
    }
    
    return "Package".equals(containerClass.getName());
}
```

**Prevention:**
- Always null-check in guards
- Use safe navigation pattern:
  ```java
  public boolean hasContainer(EObject element, ValidationContext ctx) {
      return element != null 
          && element.eContainer() != null 
          && "Package".equals(element.eContainer().eClass().getName());
  }
  ```

## 5. Satisfies Cache Issues

### Symptoms

- Circular dependency errors
- `@Satisfies` dependencies not respected
- Rules execute in wrong order
- Infinite loop during validation

### Root Cause 1: Circular Dependencies

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ❌ Circular dependency: A depends on B, B depends on A
    @Constraint(name = "ConstraintA", message = "...")
    @Satisfies(constraints = {"ConstraintB"})
    public ValidationRule constraintA() {
        return (element, ctx) -> ValidationResult.pass();
    }
    
    @Constraint(name = "ConstraintB", message = "...")
    @Satisfies(constraints = {"ConstraintA"})
    public ValidationRule constraintB() {
        return (element, ctx) -> ValidationResult.pass();
    }
}
```

**Error:**
```
java.lang.IllegalStateException: Circular dependency detected in @Satisfies: 
ConstraintA -> ConstraintB -> ConstraintA
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ✓ Linear dependency chain
    @Constraint(name = "ConstraintA", message = "...")
    // No dependencies
    public ValidationRule constraintA() {
        return (element, ctx) -> ValidationResult.pass();
    }
    
    @Constraint(name = "ConstraintB", message = "...")
    @Satisfies(constraints = {"ConstraintA"})  // Only B depends on A
    public ValidationRule constraintB() {
        return (element, ctx) -> ValidationResult.pass();
    }
}
```

**Prevention:**
- Draw dependency graph before implementing:
  ```
  MustHaveName (no dependencies)
       ↓
  NameMustBeValid (depends on MustHaveName)
       ↓
  NameMustBeUnique (depends on NameMustBeValid)
  ```
- Keep dependency chains linear, not circular
- Use topological sort mentally to verify order

### Root Cause 2: Cache Not Cleared Between Runs

**Problem code:**
```java
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

// First validation run
List<ValidationResult> results1 = executor.validate(elements);

// Model changes
element.setName("NewName");

// ❌ Second validation - satisfies cache still has old results
List<ValidationResult> results2 = executor.validate(elements);
```

**Solution:**
```java
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

// First validation run
List<ValidationResult> results1 = executor.validate(elements);

// Model changes
element.setName("NewName");

// ✓ Framework automatically clears cache before each validate() call
// No manual intervention needed
List<ValidationResult> results2 = executor.validate(elements);
```

**Manual cache clearing (if needed):**
```java
@PostValidation
public void cleanup(ValidationContext ctx) {
    // Manual cache clear after validation
    ctx.clearSatisfiesCache();
}
```

**Prevention:**
- Framework automatically clears caches between `validate()` calls
- Only manually clear if reusing `ValidationContext` across runs
- Don't reuse `ValidationContext` instances - create new executor for each run

## 6. Parallel Validation Issues

### Symptoms

- Thread safety violations
- ConcurrentModificationException
- Inconsistent validation results
- Performance worse than sequential

### Root Cause 1: Thread Safety Violations

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ❌ Shared mutable state - NOT thread-safe
    private int validationCount = 0;
    private List<String> errors = new ArrayList<>();
    
    @Constraint(name = "CountValidations", message = "...")
    public ValidationRule countValidations() {
        return (element, ctx) -> {
            validationCount++;  // Race condition!
            errors.add("Error");  // ConcurrentModificationException!
            return ValidationResult.pass();
        };
    }
}
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ✓ No shared mutable state
    @Constraint(name = "ValidEntity", message = "...")
    public ValidationRule validEntity() {
        return (element, ctx) -> {
            // All state is local to this lambda - thread-safe
            EntityType entity = (EntityType) element;
            String name = entity.getName();
            
            return name != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Name required");
        };
    }
}
```

**If you need to collect statistics:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ✓ Use thread-safe collections
    private final AtomicInteger validationCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> errorCounts = new ConcurrentHashMap<>();
    
    @Constraint(name = "ValidEntity", message = "...")
    public ValidationRule validEntity() {
        return (element, ctx) -> {
            validationCount.incrementAndGet();  // Thread-safe
            errorCounts.merge("EntityErrors", 1, Integer::sum);  // Thread-safe
            return ValidationResult.pass();
        };
    }
}
```

**Prevention:**
- Avoid instance variables in validator classes
- Use only local variables in validation rules
- If shared state is needed, use concurrent collections

### Root Cause 2: Performance Worse Than Sequential

**Problem code:**
```java
// ❌ Parallel execution forced on small model
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(1)  // Always parallel
    .build();

// Only 500 elements - parallel overhead dominates
List<ValidationResult> results = executor.validate(elements);
// Time: 150ms (sequential would be 100ms)
```

**Solution:**
```java
// ✓ Use default threshold (5000 elements)
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

// Automatic decision: sequential for <5000, parallel for ≥5000
List<ValidationResult> results = executor.validate(elements);
// Time: 100ms for 500 elements (sequential)
// Time: 1500ms for 10000 elements (parallel, 3x faster)
```

**Prevention:**
- Trust default parallel threshold (5000 elements)
- Only lower threshold if rules are very expensive (>5ms per element)
- Benchmark before changing parallelization settings

## 7. Memory Leaks

### Symptoms

- OutOfMemoryError after multiple validation runs
- Heap size grows unbounded
- Garbage collection thrashing
- ThreadLocal memory leaks

### Root Cause 1: Caches Not Cleared

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ❌ Manual cache never cleared
    private final Map<CacheKey, Object> manualCache = new HashMap<>();
    
    @Constraint(name = "ExpensiveCheck", message = "...")
    public ValidationRule expensiveCheck() {
        return (element, ctx) -> {
            CacheKey key = CacheKeyBuilder.build(element, "check");
            Object cached = manualCache.get(key);
            
            if (cached == null) {
                cached = expensiveComputation(element);
                manualCache.put(key, cached);  // Never removed!
            }
            
            return ValidationResult.pass();
        };
    }
}
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ✓ Use framework cache (automatically cleared)
    @Constraint(name = "ExpensiveCheck", message = "...")
    public ValidationRule expensiveCheck() {
        return (element, ctx) -> {
            CacheKey key = CacheKeyBuilder.build(element, "check");
            Object cached = ctx.getCached(key);
            
            if (cached == null) {
                cached = expensiveComputation(element);
                ctx.putCached(key, cached);  // Framework clears after validation
            }
            
            return ValidationResult.pass();
        };
    }
}
```

**Or use @PostValidation hook:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    private final Map<CacheKey, Object> manualCache = new HashMap<>();
    
    @PostValidation
    public void cleanup(ValidationContext ctx) {
        // ✓ Clear manual cache after validation
        manualCache.clear();
    }
}
```

**Prevention:**
- Use `ValidationContext` cache API instead of manual caches
- Implement `@PostValidation` hooks to clear manual caches
- Monitor heap usage during validation

### Root Cause 2: ThreadLocal Not Cleaned

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ❌ ThreadLocal never cleaned
    private final ThreadLocal<List<String>> threadData = new ThreadLocal<>();
    
    @Constraint(name = "CollectData", message = "...")
    public ValidationRule collectData() {
        return (element, ctx) -> {
            List<String> data = threadData.get();
            if (data == null) {
                data = new ArrayList<>();
                threadData.set(data);  // Never removed!
            }
            data.add(element.toString());
            return ValidationResult.pass();
        };
    }
}
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    private final ThreadLocal<List<String>> threadData = new ThreadLocal<>();
    
    @Constraint(name = "CollectData", message = "...")
    public ValidationRule collectData() {
        return (element, ctx) -> {
            try {
                List<String> data = threadData.get();
                if (data == null) {
                    data = new ArrayList<>();
                    threadData.set(data);
                }
                data.add(element.toString());
                return ValidationResult.pass();
            } finally {
                // ✓ Clean up after use
                threadData.remove();
            }
        };
    }
}
```

**Prevention:**
- Always call `ThreadLocal.remove()` in `finally` block
- Avoid ThreadLocal in validator classes (framework manages it)
- Use `@PostValidation` to clean ThreadLocal:
  ```java
  @PostValidation
  public void cleanup(ValidationContext ctx) {
      threadData.remove();
  }
  ```

## 8. Performance Problems

### Symptoms

- Validation takes too long
- CPU usage low during validation
- Sequential execution when parallel expected
- Repeated expensive computations

### Root Cause 1: No Caching

**Problem code:**
```java
@ExtensionMethod(EntityType.class)
public class EntityExtensions {
    
    // ❌ No @Cached annotation - traverses hierarchy every call
    public List<EntityType> getAllSuperTypes(EntityType self) {
        List<EntityType> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        while (current != null) {
            result.add(current);
            current = current.getSuperType();
        }
        return result;
    }
}

// Called 1000 times on same entity = 1000 traversals
```

**Solution:**
```java
@ExtensionMethod(EntityType.class)
public class EntityExtensions {
    
    // ✓ Add @Cached annotation
    @Cached
    public List<EntityType> getAllSuperTypes(EntityType self) {
        List<EntityType> result = new ArrayList<>();
        EntityType current = self.getSuperType();
        while (current != null) {
            result.add(current);
            current = current.getSuperType();
        }
        return result;
    }
}

// First call: 5ms, next 999 calls: <0.1ms each
```

**Prevention:**
- Add `@Cached` to all graph traversal methods
- Cache model-wide queries
- See [Caching Guide](../user-guide/caching.md) for details

### Root Cause 2: Guards Not Used

**Problem code:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ❌ No guard - rule executes for ALL entities
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check inside rule - still wastes execution
            if (entity.isAbstract()) {
                return ValidationResult.pass();
            }
            
            return entity.getTable() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Table required");
        };
    }
}
```

**Solution:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // ✓ Guard filters out abstract entities
    @Constraint(name = "ConcreteMustHaveTable", message = "...")
    @Guard(method = "isNotAbstract")
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Only concrete entities reach here
            return entity.getTable() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Table required");
        };
    }
    
    public boolean isNotAbstract(EObject element, ValidationContext ctx) {
        return !((EntityType) element).isAbstract();
    }
}
```

**Prevention:**
- Use guards to skip irrelevant elements
- See [Guards Guide](../user-guide/guards-and-dependencies.md)

### Root Cause 3: Inefficient Queries

**Problem code:**
```java
@Constraint(name = "NameMustBeUnique", message = "...")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // ❌ O(n²) - gets all entities for EACH entity
        List<EntityType> all = ctx.getAllInstances(EntityType.class);
        long count = all.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        return count > 1
            ? ValidationResult.fail("Duplicate name")
            : ValidationResult.pass();
    };
}
```

**Solution:**
```java
@Constraint(name = "NameMustBeUnique", message = "...")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // ✓ O(n) - build index once, use many times
        CacheKey key = CacheKeyBuilder.build(ctx.getResourceSet(), "nameIndex");
        Map<String, List<EntityType>> index = (Map) ctx.getCached(key);
        
        if (index == null) {
            index = ctx.getAllInstances(EntityType.class).stream()
                .collect(Collectors.groupingBy(EntityType::getName));
            ctx.putCached(key, index);
        }
        
        long count = index.get(entity.getName()).size();
        return count > 1
            ? ValidationResult.fail("Duplicate name")
            : ValidationResult.pass();
    };
}
```

**Prevention:**
- Build indexes for uniqueness checks
- Cache model-wide queries
- See [Performance Guide](../best-practices/performance.md)

## Debug Logging Configuration

Enable comprehensive debug logging to diagnose issues.

### Logback Configuration

**logback.xml:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    
    <!-- Console appender -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- File appender -->
    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>validation-debug.log</file>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- Validation framework loggers -->
    <logger name="hu.blackbelt.judo.zeta.validation" level="DEBUG"/>
    <logger name="hu.blackbelt.judo.zeta.validation.core.ValidationRegistry" level="DEBUG"/>
    <logger name="hu.blackbelt.judo.zeta.validation.core.ValidationExecutor" level="DEBUG"/>
    <logger name="hu.blackbelt.judo.zeta.validation.core.ValidationContext" level="DEBUG"/>
    
    <!-- EMF logging -->
    <logger name="org.eclipse.emf" level="INFO"/>
    
    <!-- Root logger -->
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <appender-ref ref="FILE"/>
    </root>
    
</configuration>
```

### Debug Output Examples

**Registry registration:**
```
DEBUG ValidationRegistry - Registering validator class: EntityTypeValidations
DEBUG ValidationRegistry - Found 12 validation rules in EntityTypeValidations
DEBUG ValidationRegistry - Registered constraint: MustHaveName for EntityType
DEBUG ValidationRegistry - Registered constraint: NameMustBeUnique for EntityType
```

**Execution flow:**
```
DEBUG ValidationExecutor - Validating 10,000 elements
DEBUG ValidationExecutor - Using parallel execution (threshold: 5000)
DEBUG ValidationExecutor - Processor count: 8
DEBUG ValidationExecutor - Chunk size: 1,250
DEBUG ValidationExecutor - Created 8 chunks
DEBUG ValidationExecutor - Parallel validation completed in 1,456ms
```

**Cache performance:**
```
DEBUG ValidationContext - Extension cache hits: 8,543 / 10,000 (85.4%)
DEBUG ValidationContext - Satisfies cache hits: 2,198 / 3,000 (73.3%)
```

**Guard evaluation:**
```
DEBUG ValidatorDescriptor - Evaluating guard 'isNotAbstract' for element EntityType@abc123
DEBUG ValidatorDescriptor - Guard returned: false, skipping rule ConcreteMustHaveTable
```

### Programmatic Logging

Enable logging in code for specific diagnostics:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ValidationContext(EntityType.class)
public class EntityValidations {
    
    private static final Logger log = LoggerFactory.getLogger(EntityValidations.class);
    
    @Constraint(name = "NameMustBeUnique", message = "...")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            log.debug("Checking uniqueness for entity: {}", entity.getName());
            
            List<EntityType> all = ctx.getAllInstances(EntityType.class);
            log.debug("Found {} total entities", all.size());
            
            long count = all.stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
            
            log.debug("Found {} entities with name '{}'", count, entity.getName());
            
            return count > 1
                ? ValidationResult.fail("Duplicate name")
                : ValidationResult.pass();
        };
    }
}
```

## Related Topics

- [Core Concepts](../user-guide/core-concepts.md) - Understanding the framework
- [Performance Optimization](../best-practices/performance.md) - Tuning for speed
- [Caching](../user-guide/caching.md) - Optimization strategies
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md) - Conditional validation
- [ValidationContext API](validation-context.md) - Complete API reference

## Summary

**Most common issues:**
1. **Missing `@ValidationContext`** - Always annotate validator classes
2. **Not registered** - Call `registry.register()` for each validator
3. **Type mismatch** - Match `@ValidationContext` type to validated elements
4. **Guard signature** - Include `ValidationContext ctx` parameter
5. **No caching** - Add `@Cached` to expensive methods
6. **Thread safety** - Avoid shared mutable state in validators

**Quick fixes:**
- Enable debug logging to see what's happening
- Verify `@ValidationContext` annotation and element type
- Check validator class is registered with registry
- Verify guard method signatures match expected format
- Use framework cache API instead of manual caches
- Add `@Cached` to graph traversal methods
- Use guards to skip irrelevant elements

**When in doubt:**
1. Enable DEBUG logging
2. Check the logs for registration and execution
3. Verify element types match expectations
4. Test with simple model before complex model
5. Consult the [Examples](../examples/simple-validations.md) for working patterns

---

**Previous**: [ValidationContext API](validation-context.md) | **Up**: [Reference](annotations.md) | **Home**: [Documentation Hub](../../index.md)
