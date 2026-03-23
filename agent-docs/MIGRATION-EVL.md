# EVL to Zeta Validation Migration Reference

Focused reference for migrating EVL validations to Zeta. For full details, see `docs/validation/evl-comparison/`.

## Quick Syntax Mapping

| EVL | Zeta |
|-----|------|
| `context Type { }` | `@ValidationContext(Type.class)` |
| `constraint Name { }` | `@Constraint(name="Name", message="...")` |
| `critique Name { }` | `@Critique(name="Name", message="...")` |
| `guard: expr` | `@Guard(method="guardMethod")` |
| `guard: self.satisfies("Rule")` | `@Satisfies(constraints={"Rule"})` |
| `check: expr` | `return expr ? pass() : fail(...)` |
| `message: "..."` | `ValidationResult.fail("...")` |
| `self.name` | `element.getName()` |
| `self.isDefined()` | `element != null` |
| `self.isUndefined()` | `element == null` |

## Constraint Migration Template

**EVL:**
```evl
context PSM!EntityType {
    constraint MustHaveName {
        guard: self.eContainer.isDefined()
        check: self.name.isDefined() and self.name.length() > 0
        message: "Entity must have a name"
    }
}
```

**Zeta:**
```java
@ValidationContext(EntityType.class)
public class EntityValidations {

    public boolean hasContainer(EObject elem, ValidationContext ctx) {
        return elem.eContainer() != null;
    }

    @Guard(method = "hasContainer")
    @Constraint(name = "MustHaveName", message = "Entity must have a name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            if (entity.getName() == null || entity.getName().isEmpty()) {
                return ValidationResult.fail(
                    "MustHaveName",
                    "Entity must have a name",
                    Severity.ERROR,
                    element
                );
            }
            return ValidationResult.pass();
        };
    }
}
```

## Critique (Warning) Pattern

**EVL:**
```evl
critique ShouldHaveDescription {
    check: self.description.isDefined()
    message: "Should have description"
}
```

**Zeta:**
```java
@Critique(name = "ShouldHaveDescription", message = "Should have description")
public ValidationRule shouldHaveDescription() {
    return (element, ctx) -> {
        MyType obj = (MyType) element;
        if (obj.getDescription() == null) {
            return ValidationResult.warn(
                "ShouldHaveDescription",
                "Should have description",
                element
            );
        }
        return ValidationResult.pass();
    };
}
```

## Satisfies Dependency

**EVL:**
```evl
constraint RuleB {
    guard: self.satisfies("RuleA")
    check: ...
}
```

**Zeta:**
```java
@Satisfies(constraints = {"RuleA"})
@Constraint(name = "RuleB", message = "...")
public ValidationRule ruleB() {
    return (element, ctx) -> { ... };
}
```

## EOL to Java Collections

| EOL | Java |
|-----|------|
| `col.first()` | `col.isEmpty() ? null : col.get(0)` |
| `col.select(x \| cond)` | `col.stream().filter(x -> cond).toList()` |
| `col.collect(x \| expr)` | `col.stream().map(x -> expr).toList()` |
| `col.exists(x \| cond)` | `col.stream().anyMatch(x -> cond)` |
| `col.forAll(x \| cond)` | `col.stream().allMatch(x -> cond)` |
| `Type.allInstances()` | `ctx.getAllInstances(Type.class)` |

## Cross-Reference Validation

**EVL:**
```evl
constraint ReferenceMustExist {
    check: MyType.allInstances().exists(t | t.name = self.refName)
    message: "Referenced type does not exist"
}
```

**Zeta:**
```java
@Constraint(name = "ReferenceMustExist", message = "Referenced type must exist")
public ValidationRule referenceMustExist() {
    return (element, ctx) -> {
        MyElement elem = (MyElement) element;
        boolean exists = ctx.getAllInstances(MyType.class).stream()
            .anyMatch(t -> elem.getRefName().equals(t.getName()));
        
        if (!exists) {
            return ValidationResult.fail("Reference not found");
        }
        return ValidationResult.pass();
    };
}
```

## Directory Structure

```
src/main/java/.../validation/
├── <Module>Validator.java           # Main validator class
├── <Module>ValidationException.java # Custom exception
└── rules/
    ├── NamespaceValidations.java
    ├── TypeValidations.java
    └── DataValidations.java
```

## Validator Class

```java
public class PsmValidator {
    public static List<ValidationResult> validate(Logger log, PsmModel model) {
        ValidationRegistry registry = new ValidationRegistry();
        registry.register(NamespaceValidations.class);
        registry.register(TypeValidations.class);
        registry.register(DataValidations.class);
        
        ValidationExecutor executor = ValidationExecutor.builder()
            .registry(registry)
            .parallel(true)
            .build();
        
        return executor.validate(model.getResourceSet());
    }
}
```

## Common Pitfalls

| Issue | Solution |
|-------|----------|
| NPE in guard | Always check `eContainer() != null` first |
| Wrong severity | Use `@Critique` for warnings, `@Constraint` for errors |
| Logic inverted | EVL check=true passes; Zeta return fail() on error |
| Missing @Satisfies | Use `@Satisfies` not `@Guard` for dependencies |
| Context guard missing | Add `@Guard` to ALL rules if EVL context has guard |

## Dual-Engine Testing Pattern

```java
enum ValidationEngine { EVL, JAVA }

@ParameterizedTest(name = "{0} with {1}")
@MethodSource("testCases")
void testValidation(ValidationTestCase tc, ValidationEngine engine) {
    tc.modelSetup.accept(model);
    
    Set<String> violations = (engine == ValidationEngine.EVL)
        ? runEvlValidation() : runJavaValidation();
    
    for (String expected : tc.expectedConstraints) {
        assertTrue(violations.contains(expected));
    }
}
```

## Consistency Test

```java
@Test
void testEvlJavaConsistency() {
    setupModel(model);
    Set<String> evlViolations = runEvlValidation();
    
    model = createFreshModel();
    setupModel(model);
    Set<String> javaViolations = runJavaValidation();
    
    assertEquals(evlViolations, javaViolations);
}
```

## Performance Test Pattern

```java
@Test
void testPerformance() {
    generateLargeModel(100); // 100 entities
    
    // Warmup
    for (int i = 0; i < 3; i++) {
        runJavaValidation();
        runEvlValidation();
    }
    
    // Measure
    long javaTime = measureMs(() -> runJavaValidation());
    long evlTime = measureMs(() -> runEvlValidation());
    
    log.info("Java: {}ms, EVL: {}ms, Speedup: {}x", 
        javaTime, evlTime, (double)evlTime/javaTime);
}
```

## ValidationResult Methods

```java
// Pass
return ValidationResult.pass();

// Error
return ValidationResult.fail(constraintName, message, Severity.ERROR, element);

// Warning  
return ValidationResult.warn(constraintName, message, element);

// With details
return ValidationResult.builder()
    .constraintName("RuleName")
    .message("Detailed message")
    .severity(Severity.ERROR)
    .element(element)
    .build();
```
