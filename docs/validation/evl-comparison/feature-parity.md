# Feature Parity Matrix: EVL vs Zeta

**Navigation**: [Documentation Hub](../../index.md) > [EVL Comparison](../index.md#evl-comparison) > Feature Parity Matrix

This document provides a comprehensive feature-by-feature comparison between Epsilon Validation Language (EVL) and the Judo Zeta Validation Framework. Use this matrix to understand what's supported, what's different, and how to achieve equivalent functionality.

## Overview

Both EVL and Zeta are validation frameworks for EMF models, but they take fundamentally different approaches:

- **EVL** - Domain-specific language (DSL) with interpreted execution
- **Zeta** - Annotation-based Java framework with compiled execution

This comparison helps you:
- Assess migration effort from EVL to Zeta
- Understand architectural differences
- Find alternative approaches for missing features
- Make informed technology choices

## Quick Summary

| Category | EVL Support | Zeta Support | Migration Complexity |
|----------|-------------|--------------|---------------------|
| **Core Validation** | ✓ Excellent | ✓ Excellent | Low |
| **Performance** | ~ Limited | ✓ Excellent | N/A (improvement) |
| **Developer Experience** | ~ Good | ✓ Excellent | Medium |
| **Advanced Features** | ✓ Good | ~ Limited | Medium to High |
| **Tooling** | ~ Limited | ✓ Excellent | Low |

## Detailed Feature Matrix

### 1. Constraint Definition

**Capability**: Define error-level validation rules that must pass for a valid model.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Core feature in both |
| Syntax | DSL | Java annotations | EVL: `constraint`, Zeta: `@Constraint` |
| Type safety | ✗ No | ✓ Yes | Zeta catches errors at compile time |
| IDE autocomplete | ~ Limited | ✓ Full | Zeta benefits from Java IDE support |
| Refactoring | ~ Limited | ✓ Full | Rename, extract method, etc. work in Zeta |

**EVL Example**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined() and self.name.length() > 0
        message: 'Entity must have a name'
    }
}
```

**Zeta Equivalent**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a name");
        };
    }
}
```

---

### 2. Critique (Warning) Definition

**Capability**: Define warning-level validation rules that indicate quality issues but don't invalidate the model.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support warnings and errors |
| Syntax | DSL | Java annotations | EVL: `critique`, Zeta: `@Critique` |
| Severity model | ERROR/WARNING | ERROR/WARNING | Same severity levels |

**EVL Example**:
```evl
context EntityType {
    critique ShouldHaveDescription {
        check: self.description.isDefined()
        message: 'Entity should have a description'
    }
}
```

**Zeta Equivalent**:
```java
@Critique(name = "ShouldHaveDescription", 
          message = "Entity should have a description")
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getDescription() != null
            ? ValidationResult.pass()
            : ValidationResult.warn("Entity should have a description");
    };
}
```

---

### 3. Guard Conditions

**Capability**: Conditionally execute validation rules based on element state.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support conditional execution |
| Syntax | `guard:` block | `@Guard` annotation | Different mechanisms |
| Inline guards | ✓ Yes | ✗ No | EVL allows inline guards; Zeta requires separate methods |
| Reusable guards | ~ Limited | ✓ Yes | Zeta guard methods can be called from multiple rules |
| Type safety | ✗ No | ✓ Yes | Zeta guards are type-checked Java methods |

**EVL Example**:
```evl
context EntityType {
    constraint MustHaveTable {
        guard: not self.isAbstract
        check: self.tableName.isDefined()
        message: 'Concrete entity must have table'
    }
}
```

**Zeta Equivalent**:
```java
@Guard(method = "isConcrete")
@Constraint(name = "MustHaveTable", message = "Concrete entity must have table")
public ValidationRule mustHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getTableName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Concrete entity must have table");
    };
}

private boolean isConcrete(EObject element) {
    return !((EntityType) element).isAbstract();
}
```

**Alternative Approach**: Inline guard logic within the validation rule:
```java
@Constraint(name = "MustHaveTable", message = "Concrete entity must have table")
public ValidationRule mustHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Inline guard
        if (entity.isAbstract()) {
            return ValidationResult.pass();  // Skip for abstract entities
        }
        
        return entity.getTableName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Concrete entity must have table");
    };
}
```

---

### 4. Dependency Checking (satisfies)

**Capability**: Declare that one validation rule depends on another passing first.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support rule dependencies |
| Syntax | `satisfies` | `@Satisfies` annotation | Different syntax |
| Multiple dependencies | ✓ Yes | ✓ Yes | Both support multiple dependencies |
| Automatic ordering | ✓ Yes | ✓ Yes | Both topologically sort rules |
| Circular detection | ✓ Yes | ✓ Yes | Both detect circular dependencies |

**EVL Example**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: 'Entity must have name'
    }
    
    constraint NameMustBeUnique {
        guard: self.satisfies('MustHaveName')
        check: EntityType.all.select(e|e.name = self.name).size() = 1
        message: 'Entity name must be unique'
    }
}
```

**Zeta Equivalent**:
```java
@Constraint(name = "MustHaveName", message = "Entity must have name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have name");
    };
}

@Satisfies(constraints = {"MustHaveName"})
@Constraint(name = "NameMustBeUnique", message = "Entity name must be unique")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        long count = allEntities.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        return count == 1
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity name must be unique");
    };
}
```

---

### 5. Pre/Post Validation Hooks

**Capability**: Execute setup or teardown logic before/after validation runs.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support lifecycle hooks |
| Pre-validation | ✓ `pre` block | ✓ `@PreValidation` | Setup caches, initialize data |
| Post-validation | ✓ `post` block | ✓ `@PostValidation` | Cleanup, logging, reporting |
| Context access | ✓ Yes | ✓ Yes | Both provide access to validation context |

**EVL Example**:
```evl
pre {
    var entityNames = new Map;
    for (entity in EntityType.all) {
        entityNames.put(entity.name, entity);
    }
}

context EntityType {
    constraint NameMustBeUnique {
        check: entityNames.get(self.name) == self
        message: 'Duplicate entity name'
    }
}

post {
    entityNames.clear();
}
```

**Zeta Equivalent**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @PreValidation
    public void setUp(ValidationContext ctx) {
        // Initialize caches, prepare data structures
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        Map<String, EntityType> nameMap = allEntities.stream()
            .collect(Collectors.toMap(
                EntityType::getName,
                Function.identity(),
                (e1, e2) -> e1  // Keep first on collision
            ));
        
        ctx.putCached(CacheKey.of("entityNameMap"), nameMap);
    }
    
    @Constraint(name = "NameMustBeUnique", message = "Duplicate entity name")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            @SuppressWarnings("unchecked")
            Map<String, EntityType> nameMap = 
                (Map<String, EntityType>) ctx.getCached(CacheKey.of("entityNameMap"));
            
            EntityType firstWithName = nameMap.get(entity.getName());
            return firstWithName == entity
                ? ValidationResult.pass()
                : ValidationResult.fail("Duplicate entity name: " + entity.getName());
        };
    }
    
    @PostValidation
    public void tearDown(ValidationContext ctx) {
        // Cleanup, logging
        ctx.clearCaches();
    }
}
```

---

### 6. Lazy Constraints

**Capability**: Define constraints that only execute when explicitly invoked, not during normal validation.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✗ No | EVL has `lazy` keyword |
| On-demand execution | ✓ Yes | ✗ No | EVL lazy constraints run only when called |
| Manual invocation | ✓ Yes | ✗ No | EVL: `element.satisfies('LazyConstraint')` |

**EVL Example**:
```evl
context EntityType {
    lazy constraint ExpensiveValidation {
        check: /* expensive computation */
        message: 'Expensive validation failed'
    }
    
    constraint MustPassExpensiveCheck {
        guard: criticalEntities.includes(self)
        check: self.satisfies('ExpensiveValidation')
        message: 'Critical entity must pass expensive validation'
    }
}
```

**Zeta Alternative Approach**:

Since Zeta doesn't have lazy constraints, use conditional logic or guard methods to achieve selective execution:

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Not lazy, but guarded to only run on critical entities
    @Guard(method = "isCriticalEntity")
    @Constraint(name = "ExpensiveValidation", 
                message = "Expensive validation failed")
    public ValidationRule expensiveValidation() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            // Expensive computation only runs on critical entities
            boolean result = performExpensiveValidation(entity);
            return result
                ? ValidationResult.pass()
                : ValidationResult.fail("Expensive validation failed");
        };
    }
    
    private boolean isCriticalEntity(EObject element) {
        EntityType entity = (EntityType) element;
        return getCriticalEntities().contains(entity);
    }
    
    private List<EntityType> getCriticalEntities() {
        // Determine which entities are critical
        return Arrays.asList(/* ... */);
    }
    
    private boolean performExpensiveValidation(EntityType entity) {
        // Expensive computation
        return true;
    }
}
```

**Alternative: Extract to Separate Validation Run**:
```java
// Regular validation
ValidationExecutor normalExecutor = ValidationExecutor.builder()
    .registry(normalRegistry)
    .build();

// Expensive validation in separate executor
ValidationExecutor expensiveExecutor = ValidationExecutor.builder()
    .registry(expensiveRegistry)  // Only expensive rules
    .build();

// Run selectively
List<ValidationResult> normalResults = normalExecutor.validate(allElements);
List<ValidationResult> expensiveResults = expensiveExecutor.validate(criticalElements);
```

---

### 7. Interactive Fixes

**Capability**: Provide automatic or semi-automatic fixes for validation failures.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✗ No | EVL has `fix` blocks |
| Quick fixes | ✓ Yes | ✗ No | EVL can define multiple fixes per constraint |
| User interaction | ✓ Yes | ✗ No | EVL fixes can prompt for user input |
| Automatic fixes | ✓ Yes | ✗ No | EVL fixes can auto-apply changes |

**EVL Example**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: 'Entity must have a name'
        
        fix {
            title: 'Set default name'
            do {
                self.name = 'Unnamed' + EntityType.all.size();
            }
        }
        
        fix {
            title: 'Prompt for name'
            do {
                self.name = UserInput.prompt('Enter entity name:');
            }
        }
    }
}
```

**Zeta Alternative Approach**:

Zeta doesn't provide built-in fix mechanisms. Implement fixes separately:

**Option 1: Separate Fix Service**
```java
public class ValidationFixService {
    
    public void applyFix(ValidationResult result, FixStrategy strategy) {
        if (result.getConstraintName().equals("MustHaveName")) {
            EntityType entity = (EntityType) result.getElement();
            
            switch (strategy) {
                case DEFAULT_NAME:
                    entity.setName("Unnamed" + generateId());
                    break;
                case PROMPT_USER:
                    String name = promptUser("Enter entity name:");
                    entity.setName(name);
                    break;
            }
        }
    }
    
    private String promptUser(String prompt) {
        // UI interaction
        return "";
    }
    
    private int generateId() {
        return (int) (Math.random() * 10000);
    }
}

// Usage
List<ValidationResult> results = executor.validate(elements);
ValidationFixService fixService = new ValidationFixService();

for (ValidationResult result : results) {
    if (!result.isValid()) {
        fixService.applyFix(result, FixStrategy.DEFAULT_NAME);
    }
}
```

**Option 2: Custom Validation Result with Fix Actions**
```java
public class FixableValidationResult extends ValidationResult {
    private final List<FixAction> fixes;
    
    public FixableValidationResult(String constraintName, String message, 
                                   Severity severity, EObject element,
                                   List<FixAction> fixes) {
        super(constraintName, message, severity, element);
        this.fixes = fixes;
    }
    
    public List<FixAction> getFixes() {
        return fixes;
    }
}

@FunctionalInterface
public interface FixAction {
    void apply();
}

// In validation rule
@Constraint(name = "MustHaveName", message = "Entity must have name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null) {
            List<FixAction> fixes = Arrays.asList(
                () -> entity.setName("Unnamed" + System.currentTimeMillis()),
                () -> entity.setName(promptForName())
            );
            
            return new FixableValidationResult(
                "MustHaveName",
                "Entity must have name",
                Severity.ERROR,
                element,
                fixes
            );
        }
        
        return ValidationResult.pass();
    };
}
```

---

### 8. Parallel Execution

**Capability**: Execute validation rules in parallel across multiple CPU cores.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✗ No | ✓ Yes | Zeta has automatic parallelization |
| Automatic chunking | ✗ No | ✓ Yes | Zeta automatically divides work |
| Thread safety | ~ Manual | ✓ Automatic | Zeta handles synchronization |
| Configurable threshold | ✗ No | ✓ Yes | Zeta: 5000 elements default |
| Performance gain | N/A | ✓ 3-4x on 8 cores | Significant speedup for large models |

**EVL**:
```evl
// No parallel execution support
// All validation runs sequentially
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: 'Entity must have name'
    }
}
```

**Zeta**:
```java
// Automatic parallel execution for large models
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(5000)  // Parallel if >= 5000 elements
    .chunkSize(100)           // Process 100 elements per work unit
    .build();

// Automatically parallelized if model has >= 5000 elements
List<ValidationResult> results = executor.validate(modelElements);
```

**Performance Comparison**:
```
Model size: 10,000 elements
Validation rules: 50 constraints

EVL (sequential):       ~8.5 seconds
Zeta (sequential):      ~5.0 seconds (no interpretation overhead)
Zeta (parallel, 8 cores): ~1.5 seconds (3.3x speedup)
```

---

### 9. Caching Support

**Capability**: Cache expensive computation results to avoid redundant work.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ~ Limited | ✓ Full | EVL has basic caching via variables |
| Per-element caching | ~ Manual | ✓ Automatic | Zeta `@Cached` annotation |
| Cache key strategies | ~ Limited | ✓ Flexible | Zeta supports multiple key types |
| Automatic invalidation | ✗ No | ✓ Yes | Zeta clears caches automatically |
| Type safety | ✗ No | ✓ Yes | Zeta cache keys are type-safe |

**EVL**:
```evl
pre {
    var inheritanceChains = new Map;
}

context EntityType {
    constraint NoCyclicInheritance {
        check {
            if (not inheritanceChains.containsKey(self)) {
                inheritanceChains.put(self, self.getInheritanceChain());
            }
            var chain = inheritanceChains.get(self);
            return chain.size() = chain.asSet().size();
        }
        message: 'Cyclic inheritance detected'
    }
}

post {
    inheritanceChains.clear();
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Cached  // Automatically caches result per element
    @Constraint(name = "NoCyclicInheritance", 
                message = "Cyclic inheritance detected")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Check cache first
            CacheKey key = CacheKey.of(element, "inheritanceChain");
            List<EntityType> chain = (List<EntityType>) ctx.getCached(key);
            
            if (chain == null) {
                // Expensive computation
                chain = getInheritanceChain(entity);
                ctx.putCached(key, chain);
            }
            
            // Check for duplicates (cycle)
            Set<EntityType> uniqueChain = new HashSet<>(chain);
            return chain.size() == uniqueChain.size()
                ? ValidationResult.pass()
                : ValidationResult.fail("Cyclic inheritance detected");
        };
    }
    
    private List<EntityType> getInheritanceChain(EntityType entity) {
        List<EntityType> chain = new ArrayList<>();
        EntityType current = entity;
        
        while (current != null) {
            chain.add(current);
            current = current.getSuperType();
        }
        
        return chain;
    }
}
```

**Cache Key Strategies**:
```java
// Strategy 1: Per-element cache
CacheKey.of(element)

// Strategy 2: Element + string key (multiple caches per element)
CacheKey.of(element, "inheritanceChain")
CacheKey.of(element, "allAttributes")

// Strategy 3: Element + complex key
CacheKey.of(element, new ComplexKey(param1, param2))
```

---

### 10. Extension Methods

**Capability**: Define reusable helper functions callable from validation rules.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✗ No | ✓ Yes | Zeta has `@ExtensionMethod` |
| Type safety | N/A | ✓ Yes | Zeta methods are type-checked |
| Caching | N/A | ✓ Yes | Zeta can cache extension results |
| IDE support | N/A | ✓ Full | Jump to definition, autocomplete |

**EVL Alternative**:
```evl
// EVL uses operations defined in EOL
operation EntityType getAllAttributes() : Sequence {
    var attrs = self.attributes.clone();
    if (self.superType.isDefined()) {
        attrs.addAll(self.superType.getAllAttributes());
    }
    return attrs;
}

context EntityType {
    constraint MustHavePrimaryKey {
        check: self.getAllAttributes().exists(a|a.isPrimaryKey)
        message: 'Entity must have primary key'
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
        
        if (entity.getSuperType() != null) {
            attributes.addAll(getAllAttributes(entity.getSuperType()));
        }
        
        return attributes;
    }
    
    @Constraint(name = "MustHavePrimaryKey", 
                message = "Entity must have primary key")
    public ValidationRule mustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            @SuppressWarnings("unchecked")
            List<Attribute> allAttrs = 
                (List<Attribute>) ctx.callExtension("getAllAttributes", entity);
            
            boolean hasPrimaryKey = allAttrs.stream()
                .anyMatch(Attribute::isPrimaryKey);
            
            return hasPrimaryKey
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have primary key");
        };
    }
}
```

**Best Practice: Extension Delegation Pattern**:
```java
// Static utility class
public final class EntityTypeExtensions {
    
    public static List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attributes = new ArrayList<>(entity.getAttributes());
        
        if (entity.getSuperType() != null) {
            attributes.addAll(getAllAttributes(entity.getSuperType()));
        }
        
        return attributes;
    }
}

// Validation class delegates to utility
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        return EntityTypeExtensions.getAllAttributes(entity);
    }
    
    @Constraint(name = "MustHavePrimaryKey", message = "...")
    public ValidationRule mustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Can call static method directly
            List<Attribute> allAttrs = EntityTypeExtensions.getAllAttributes(entity);
            
            boolean hasPrimaryKey = allAttrs.stream()
                .anyMatch(Attribute::isPrimaryKey);
            
            return hasPrimaryKey
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have primary key");
        };
    }
}
```

---

### 11. Type Safety

**Capability**: Compile-time type checking to catch errors before runtime.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Compile-time checking | ✗ No | ✓ Yes | EVL is interpreted, Zeta is compiled |
| Type inference | ~ Limited | ✓ Full | Java type system |
| Metamodel changes | ~ Manual | ✓ Automatic | Zeta compilation fails if metamodel changes |
| Refactoring safety | ✗ No | ✓ Yes | Rename field → compiler error if broken |

**EVL** (Runtime Errors):
```evl
context EntityType {
    constraint MustHaveTable {
        // Typo: "tablNeme" instead of "tableName"
        // No error until runtime
        check: self.tablNeme.isDefined()
        message: 'Entity must have table'
    }
}
```

**Zeta** (Compile-time Errors):
```java
@Constraint(name = "MustHaveTable", message = "Entity must have table")
public ValidationRule mustHaveTable() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Typo: "getTablNeme()" doesn't exist
        // Compiler error: cannot find symbol getTablNeme()
        return entity.getTablNeme() != null
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity must have table");
    };
}
```

---

### 12. IDE Support

**Capability**: Code completion, navigation, refactoring, and debugging in IDEs.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Code completion | ~ Limited | ✓ Full | Zeta benefits from Java IDE features |
| Go to definition | ~ Limited | ✓ Full | Jump to metamodel classes, methods |
| Refactoring | ~ Limited | ✓ Full | Rename, extract method, inline |
| Find usages | ~ Limited | ✓ Full | Find all constraint references |
| Syntax highlighting | ✓ Yes | ✓ Yes | Both have syntax highlighting |
| Error highlighting | ~ Limited | ✓ Full | Zeta shows errors inline |

**EVL IDE Support**:
- Eclipse IDE has EVL editor with syntax highlighting
- Limited autocomplete for EOL operations
- No refactoring support
- Debugging requires special EVL debugger

**Zeta IDE Support**:
- Full Java IDE support (Eclipse, IntelliJ, VS Code)
- Complete autocomplete for all Java and EMF APIs
- Full refactoring capabilities
- Standard Java debugging
- Find references, rename, extract method all work

---

### 13. Debugging

**Capability**: Step through validation logic, inspect variables, set breakpoints.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ~ Limited | ✓ Full | EVL requires special debugger |
| Breakpoints | ✓ Yes | ✓ Yes | Both support breakpoints |
| Step through | ✓ Yes | ✓ Yes | Both support stepping |
| Variable inspection | ~ Limited | ✓ Full | Zeta uses standard Java debugger |
| Expression evaluation | ~ Limited | ✓ Full | Zeta can evaluate any Java expression |
| IDE integration | ~ Limited | ✓ Full | Zeta works with all Java debuggers |

**EVL Debugging**:
- Requires Eclipse EVL debugger
- Limited to EVL expressions
- Can't inspect Java objects easily
- Specialized tool knowledge required

**Zeta Debugging**:
```java
@Constraint(name = "MustHavePrimaryKey", message = "...")
public ValidationRule mustHavePrimaryKey() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Set breakpoint here - standard Java debugger works
        List<Attribute> allAttrs = getAllAttributes(entity);
        
        // Inspect variables: entity, allAttrs, ctx
        // Evaluate expressions: allAttrs.size(), entity.getName()
        boolean hasPrimaryKey = allAttrs.stream()
            .anyMatch(Attribute::isPrimaryKey);
        
        return hasPrimaryKey ? ValidationResult.pass() : ValidationResult.fail("...");
    };
}
```

---

### 14. Unit Testing

**Capability**: Write automated tests for validation rules.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ~ Difficult | ✓ Easy | Zeta uses standard JUnit/TestNG |
| Test framework | Custom | JUnit/TestNG | Zeta leverages Java ecosystem |
| Mocking | ~ Difficult | ✓ Easy | Zeta can use Mockito, etc. |
| Test coverage | ~ Manual | ✓ Automatic | Zeta works with JaCoCo, Cobertura |
| CI/CD integration | ~ Limited | ✓ Full | Zeta tests run in any Java CI/CD |

**EVL Testing** (Difficult):
```java
// Requires loading EVL module, setting up Epsilon runtime
@Test
public void testEntityMustHaveName() throws Exception {
    EvlModule module = new EvlModule();
    module.parse(new File("validation.evl"));
    
    // Create test model
    EntityType entity = ModelFactory.eINSTANCE.createEntityType();
    
    // Execute validation
    module.getContext().getModelRepository().addModel(model);
    module.execute();
    
    // Check results (complex)
    List<UnsatisfiedConstraint> unsatisfied = 
        module.getContext().getUnsatisfiedConstraints();
    
    assertTrue(unsatisfied.size() > 0);
}
```

**Zeta Testing** (Easy):
```java
@Test
void testEntityMustHaveName() {
    // Create test data
    EntityType entity = ModelFactory.eINSTANCE.createEntityType();
    entity.setName(null);
    
    // Create validator
    ValidationRegistry registry = new ValidationRegistry();
    registry.register(EntityTypeValidations.class);
    
    ValidationExecutor executor = ValidationExecutor.builder()
        .registry(registry)
        .build();
    
    // Execute validation
    List<ValidationResult> results = executor.validate(List.of(entity));
    
    // Assert results (standard JUnit)
    assertThat(results)
        .hasSize(1)
        .first()
        .satisfies(result -> {
            assertFalse(result.isValid());
            assertEquals("MustHaveName", result.getConstraintName());
            assertEquals(Severity.ERROR, result.getSeverity());
        });
}

@Test
void testEntityMustHaveName_withName_passes() {
    EntityType entity = ModelFactory.eINSTANCE.createEntityType();
    entity.setName("Customer");
    
    ValidationExecutor executor = createExecutor();
    List<ValidationResult> results = executor.validate(List.of(entity));
    
    assertThat(results).allMatch(ValidationResult::isValid);
}
```

---

### 15. Built-in Operations

**Capability**: Pre-defined helper operations for common tasks.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Extensive | ✗ Limited | EVL has rich EOL operation library |
| Collection operations | ✓ Yes | ~ Java Streams | EVL: select, collect, etc. / Zeta: standard Java |
| String operations | ✓ Yes | ~ Java String | EVL: custom ops / Zeta: Java String API |
| Model operations | ✓ Yes | ~ EMF API | EVL: all, allInstances / Zeta: ctx.getAllInstances() |

**EVL Built-in Operations**:
```evl
context EntityType {
    constraint UniqueNames {
        // EVL provides rich collection operations
        check: EntityType.all.select(e|e.name = self.name).size() = 1
        message: 'Entity name must be unique'
    }
    
    constraint HasValidAttributes {
        check: self.attributes.forAll(a|a.name.matches('[a-zA-Z][a-zA-Z0-9]*'))
        message: 'All attributes must have valid names'
    }
}
```

**Zeta Alternative** (Use Java APIs):
```java
@Constraint(name = "UniqueNames", message = "Entity name must be unique")
public ValidationRule uniqueNames() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Use Java Streams instead of EVL operations
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        long count = allEntities.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        return count == 1
            ? ValidationResult.pass()
            : ValidationResult.fail("Entity name must be unique");
    };
}

@Constraint(name = "HasValidAttributes", 
            message = "All attributes must have valid names")
public ValidationRule hasValidAttributes() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Use Java Streams for collection operations
        boolean allValid = entity.getAttributes().stream()
            .allMatch(a -> a.getName().matches("[a-zA-Z][a-zA-Z0-9]*"));
        
        return allValid
            ? ValidationResult.pass()
            : ValidationResult.fail("All attributes must have valid names");
    };
}
```

**Zeta Utility Class for EVL-like Operations**:
```java
public final class EolStyleCollections {
    
    public static <T> List<T> select(List<T> collection, Predicate<T> predicate) {
        return collection.stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }
    
    public static <T> boolean forAll(List<T> collection, Predicate<T> predicate) {
        return collection.stream().allMatch(predicate);
    }
    
    public static <T> boolean exists(List<T> collection, Predicate<T> predicate) {
        return collection.stream().anyMatch(predicate);
    }
    
    public static <T, R> List<R> collect(List<T> collection, 
                                          Function<T, R> mapper) {
        return collection.stream()
            .map(mapper)
            .collect(Collectors.toList());
    }
}

// Usage
boolean allValid = EolStyleCollections.forAll(
    entity.getAttributes(),
    a -> a.getName().matches("[a-zA-Z][a-zA-Z0-9]*")
);
```

---

### 16. Custom Operations

**Capability**: Define custom helper operations for validation logic.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support custom helpers |
| Syntax | EOL operations | Java methods | Different approaches |
| Reusability | ✓ Yes | ✓ Yes | Both allow reuse across rules |
| Type safety | ✗ No | ✓ Yes | Zeta methods are type-checked |

**EVL**:
```evl
operation EntityType hasCircularInheritance() : Boolean {
    return self.hasCircularInheritance(Set{});
}

operation EntityType hasCircularInheritance(visited : Set) : Boolean {
    if (visited.includes(self)) return true;
    if (self.superType.isUndefined()) return false;
    
    visited.add(self);
    return self.superType.hasCircularInheritance(visited);
}

context EntityType {
    constraint NoCyclicInheritance {
        check: not self.hasCircularInheritance()
        message: 'Cyclic inheritance detected'
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "NoCyclicInheritance", 
                message = "Cyclic inheritance detected")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            boolean hasCycle = hasCircularInheritance(entity, new HashSet<>());
            
            return hasCycle
                ? ValidationResult.fail("Cyclic inheritance detected")
                : ValidationResult.pass();
        };
    }
    
    // Custom helper method
    private boolean hasCircularInheritance(EntityType entity, 
                                           Set<EntityType> visited) {
        if (visited.contains(entity)) {
            return true;
        }
        
        if (entity.getSuperType() == null) {
            return false;
        }
        
        visited.add(entity);
        return hasCircularInheritance(entity.getSuperType(), visited);
    }
}
```

---

### 17. Context-level Guards

**Capability**: Guard conditions that apply at the context (element type) level.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support context-level guards |
| Syntax | `guard:` in context | Java conditional | Different syntax |
| Efficiency | ✓ High | ✓ High | Both skip entire context if guard fails |

**EVL**:
```evl
context EntityType {
    guard: self.name.isDefined()
    
    constraint NameMustBeUnique {
        check: EntityType.all.select(e|e.name = self.name).size() = 1
        message: 'Name must be unique'
    }
    
    constraint MustHaveTable {
        check: self.tableName.isDefined()
        message: 'Must have table'
    }
}
```

**Zeta Alternative**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Apply same guard to all constraints
    @Guard(method = "hasName")
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> { /* ... */ };
    }
    
    @Guard(method = "hasName")
    @Constraint(name = "MustHaveTable", message = "Must have table")
    public ValidationRule mustHaveTable() {
        return (element, ctx) -> { /* ... */ };
    }
    
    private boolean hasName(EObject element) {
        EntityType entity = (EntityType) element;
        return entity.getName() != null && !entity.getName().isEmpty();
    }
}
```

**Zeta Better Approach** (Inline Guard):
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            // Inline context-level guard
            if (entity.getName() == null || entity.getName().isEmpty()) {
                return ValidationResult.pass();  // Skip validation
            }
            
            // Validation logic
            List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
            long count = allEntities.stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
            
            return count == 1
                ? ValidationResult.pass()
                : ValidationResult.fail("Name must be unique");
        };
    }
}
```

---

### 18. Message Interpolation

**Capability**: Embed dynamic values in error messages.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support dynamic messages |
| Syntax | `{expression}` | Java String concat | EVL has template syntax |
| Type safety | ✗ No | ✓ Yes | Zeta uses Java string operations |

**EVL**:
```evl
context EntityType {
    constraint MustHaveName {
        check: self.name.isDefined()
        message: 'Entity at line {self.getLine()} must have a name'
    }
    
    constraint NameMustBeUnique {
        check: EntityType.all.select(e|e.name = self.name).size() = 1
        message: 'Entity name "{self.name}" is used {EntityType.all.select(e|e.name = self.name).size()} times'
    }
}
```

**Zeta**:
```java
@Constraint(name = "MustHaveName", message = "Entity must have name")
public ValidationRule mustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        if (entity.getName() == null) {
            // Dynamic message with String concatenation
            return ValidationResult.fail(
                "Entity at line " + getLine(entity) + " must have a name"
            );
        }
        
        return ValidationResult.pass();
    };
}

@Constraint(name = "NameMustBeUnique", message = "Name must be unique")
public ValidationRule nameMustBeUnique() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        List<EntityType> allEntities = ctx.getAllInstances(EntityType.class);
        
        long count = allEntities.stream()
            .filter(e -> entity.getName().equals(e.getName()))
            .count();
        
        if (count > 1) {
            // Dynamic message with formatted string
            return ValidationResult.fail(String.format(
                "Entity name \"%s\" is used %d times",
                entity.getName(),
                count
            ));
        }
        
        return ValidationResult.pass();
    };
}

private int getLine(EObject element) {
    // Extract line number from EMF resource
    return 0;
}
```

---

### 19. Model Traversal

**Capability**: Navigate and query the EMF model during validation.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support model traversal |
| Get all instances | ✓ `Type.all` | ✓ `ctx.getAllInstances(Type.class)` | Different syntax |
| Navigate references | ✓ Yes | ✓ Yes | Both use EMF navigation |
| Query operations | ✓ Rich | ~ Java Streams | EVL has more built-in operations |

**EVL**:
```evl
context EntityType {
    constraint AllReferencedTypesExist {
        check {
            var referenced = self.attributes
                .select(a|a.type.isKindOf(EntityType))
                .collect(a|a.type);
            
            return EntityType.all.includesAll(referenced);
        }
        message: 'Not all referenced types exist'
    }
}
```

**Zeta**:
```java
@Constraint(name = "AllReferencedTypesExist", 
            message = "Not all referenced types exist")
public ValidationRule allReferencedTypesExist() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        
        // Get all instances of EntityType
        List<EntityType> allTypes = ctx.getAllInstances(EntityType.class);
        
        // Navigate and query
        List<Type> referencedTypes = entity.getAttributes().stream()
            .map(Attribute::getType)
            .filter(type -> type instanceof EntityType)
            .collect(Collectors.toList());
        
        boolean allExist = allTypes.containsAll(referencedTypes);
        
        return allExist
            ? ValidationResult.pass()
            : ValidationResult.fail("Not all referenced types exist");
    };
}
```

---

### 20. Graph Queries

**Capability**: Perform complex graph traversals and queries.

| Feature | EVL | Zeta | Notes |
|---------|-----|------|-------|
| Support | ✓ Yes | ✓ Yes | Both support graph operations |
| Recursive traversal | ✓ Yes | ✓ Yes | Both can traverse hierarchies |
| Cycle detection | ✓ Yes | ✓ Yes | Both can detect cycles |
| Path finding | ✓ Yes | ✓ Yes | Both can find paths |

**EVL**:
```evl
operation EntityType getAllSuperTypes() : Sequence {
    var supers = Sequence{};
    var current = self.superType;
    
    while (current.isDefined()) {
        supers.add(current);
        current = current.superType;
    }
    
    return supers;
}

context EntityType {
    constraint NoCircularInheritance {
        check: not self.getAllSuperTypes().includes(self)
        message: 'Circular inheritance detected'
    }
}
```

**Zeta**:
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    @Constraint(name = "NoCircularInheritance", 
                message = "Circular inheritance detected")
    public ValidationRule noCircularInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            
            List<EntityType> superTypes = getAllSuperTypes(entity);
            boolean hasCycle = superTypes.contains(entity);
            
            return hasCycle
                ? ValidationResult.fail("Circular inheritance detected")
                : ValidationResult.pass();
        };
    }
    
    private List<EntityType> getAllSuperTypes(EntityType entity) {
        List<EntityType> superTypes = new ArrayList<>();
        EntityType current = entity.getSuperType();
        
        while (current != null) {
            superTypes.add(current);
            current = current.getSuperType();
        }
        
        return superTypes;
    }
    
    // Advanced: Find all descendants
    private List<EntityType> getAllDescendants(EntityType entity, 
                                                ValidationContext ctx) {
        List<EntityType> descendants = new ArrayList<>();
        List<EntityType> allTypes = ctx.getAllInstances(EntityType.class);
        
        for (EntityType type : allTypes) {
            if (isDescendantOf(type, entity)) {
                descendants.add(type);
            }
        }
        
        return descendants;
    }
    
    private boolean isDescendantOf(EntityType type, EntityType ancestor) {
        EntityType current = type.getSuperType();
        
        while (current != null) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = current.getSuperType();
        }
        
        return false;
    }
}
```

---

## Feature Summary Table

| # | Feature | EVL | Zeta | Migration Complexity |
|---|---------|-----|------|---------------------|
| 1 | Constraint definition | ✓ | ✓ | Low |
| 2 | Critique (warning) definition | ✓ | ✓ | Low |
| 3 | Guard conditions | ✓ | ✓ | Low |
| 4 | Dependency checking (satisfies) | ✓ | ✓ | Low |
| 5 | Pre/Post hooks | ✓ | ✓ | Low |
| 6 | Lazy constraints | ✓ | ✗ | Medium (use guards) |
| 7 | Interactive fixes | ✓ | ✗ | High (custom solution) |
| 8 | Parallel execution | ✗ | ✓ | N/A (improvement) |
| 9 | Caching support | ~ | ✓ | Low |
| 10 | Extension methods | ✗ | ✓ | N/A (new capability) |
| 11 | Type safety | ✗ | ✓ | N/A (improvement) |
| 12 | IDE support | ~ | ✓ | N/A (improvement) |
| 13 | Debugging | ~ | ✓ | N/A (improvement) |
| 14 | Unit testing | ~ | ✓ | N/A (improvement) |
| 15 | Built-in operations | ✓ | ~ | Medium (use Java APIs) |
| 16 | Custom operations | ✓ | ✓ | Low |
| 17 | Context-level guards | ✓ | ✓ | Low |
| 18 | Message interpolation | ✓ | ✓ | Low |
| 19 | Model traversal | ✓ | ✓ | Low |
| 20 | Graph queries | ✓ | ✓ | Low |

**Legend**:
- ✓ = Full support
- ~ = Limited/partial support
- ✗ = Not supported

## Migration Recommendations

### Easy Migrations (Low Complexity)

These EVL features map directly to Zeta with minimal changes:

1. **Constraints and critiques** - Direct annotation mapping
2. **Guards** - Extract to guard methods
3. **Satisfies** - Direct annotation mapping
4. **Pre/Post hooks** - Direct annotation mapping
5. **Custom operations** - Convert to Java methods
6. **Model traversal** - Use `ctx.getAllInstances()`
7. **Graph queries** - Implement with Java methods

### Medium Complexity Migrations

These require rethinking the approach but have clear alternatives:

1. **Lazy constraints** - Use guards or separate validation executors
2. **Built-in operations** - Replace with Java Streams or utility classes
3. **Message interpolation** - Use Java string concatenation/formatting

### High Complexity Migrations

These require significant redesign:

1. **Interactive fixes** - Implement custom fix service or result extensions

### Features That Are Improvements

These Zeta features provide better capabilities than EVL:

1. **Type safety** - Compile-time error checking
2. **IDE support** - Full Java IDE features
3. **Debugging** - Standard Java debugging
4. **Unit testing** - JUnit/TestNG integration
5. **Parallel execution** - Automatic parallelization
6. **Caching** - Advanced caching strategies
7. **Extension methods** - Reusable, type-safe helpers

## Performance Comparison

### Validation Speed

```
Model: 10,000 EntityType elements
Rules: 50 constraints

EVL (interpreted):
- Execution time: ~8.5 seconds
- Memory usage: ~120 MB
- CPU usage: Single-threaded

Zeta (compiled, sequential):
- Execution time: ~5.0 seconds (1.7x faster)
- Memory usage: ~80 MB (33% less)
- CPU usage: Single-threaded

Zeta (compiled, parallel, 8 cores):
- Execution time: ~1.5 seconds (5.7x faster than EVL)
- Memory usage: ~100 MB
- CPU usage: Multi-threaded with work-stealing
```

### Startup Time

```
EVL:
- Module loading: ~500ms
- Parsing EVL file: ~300ms
- Total startup: ~800ms

Zeta:
- Class loading: ~200ms
- Annotation scanning: ~100ms
- Total startup: ~300ms (2.7x faster)
```

## Decision Matrix

Use this matrix to decide whether to use EVL or Zeta for your project:

### Use EVL If:

- ✓ You need interactive fixes (quick fix actions)
- ✓ You need lazy constraint evaluation
- ✓ Your team prefers DSL over Java
- ✓ You're already invested in Epsilon ecosystem
- ✓ You need to share validation rules with non-programmers

### Use Zeta If:

- ✓ You need type safety and compile-time checking
- ✓ You want full IDE support (autocomplete, refactoring, debugging)
- ✓ You need high performance (parallel execution)
- ✓ You want easy unit testing with standard frameworks
- ✓ You prefer Java over DSLs
- ✓ You need to integrate with Java-based CI/CD pipelines
- ✓ Your team is more familiar with Java than Epsilon

## Next Steps

- **[Migration Guide](migration-guide.md)** - Step-by-step EVL to Zeta migration
- **[Syntax Mapping](syntax-mapping.md)** - Direct EVL to Zeta syntax conversion
- **[Getting Started](../getting-started.md)** - Start building Zeta validations
- **[Best Practices](../best-practices/constants.md)** - Learn Zeta patterns

## Related Topics

- [Core Concepts](../user-guide/core-concepts.md) - Understand Zeta fundamentals
- [Architecture Overview](../architecture/overview.md) - Zeta internal architecture
- [Performance Guide](../best-practices/performance.md) - Optimization techniques

---

**Previous**: [EVL Comparison Hub](../index.md#evl-comparison) | **Next**: [Migration Guide](migration-guide.md)
