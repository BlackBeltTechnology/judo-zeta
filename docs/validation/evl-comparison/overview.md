# EVL vs Judo Zeta: High-Level Comparison

[Documentation](../index.md) > [EVL Comparison](./overview.md)

## Overview

This document provides a comprehensive comparison between **Epsilon Validation Language (EVL)** and **Judo Zeta**, two distinct approaches to validating EMF (Eclipse Modeling Framework) models. While both frameworks serve the same fundamental purpose—ensuring model consistency and quality—they differ significantly in philosophy, implementation, and capabilities.

## Philosophy Comparison

### EVL: Declarative DSL Approach

EVL is a **domain-specific language (DSL)** built on top of the Epsilon Object Language (EOL). It provides a declarative, text-based syntax for defining validation rules that are interpreted at runtime.

**Key Characteristics:**
- Validation rules written in a custom language
- Interpreted execution model
- Tight integration with Eclipse modeling ecosystem
- Rule-based, declarative specification
- Interactive validation with user-driven fixes

**Philosophy:**
> "Separate the validation logic from the implementation language, providing a high-level abstraction specifically tailored for model validation tasks."

### Judo Zeta: Programmatic Java Approach

Judo Zeta is an **annotation-driven Java framework** that embeds validation logic directly in Java code using compile-time annotations and functional interfaces.

**Key Characteristics:**
- Validation rules written in standard Java
- Compiled code execution
- Standalone framework with minimal dependencies
- Annotation-based, programmatic specification
- Validation-focused with no interactive fixes

**Philosophy:**
> "Leverage the full power of Java and modern programming practices while maintaining type safety, IDE support, and performance through compilation."

## High-Level Differences

### 1. Language & Development Experience

| Aspect | EVL | Judo Zeta |
|--------|-----|-----------|
| **Rule Definition** | Custom DSL (EVL/EOL) | Java with annotations |
| **Syntax** | Declarative, text-based | Programmatic, Java-based |
| **IDE Support** | Eclipse-specific tooling | Any Java IDE (IntelliJ, Eclipse, VS Code) |
| **Type Safety** | Runtime type checking | Compile-time type safety |
| **Learning Curve** | Learn EVL/EOL syntax | Use existing Java knowledge |
| **Code Completion** | EVL editor support | Full Java IDE support |
| **Refactoring** | Limited | Full Java refactoring tools |
| **Debugging** | EOL debugger | Standard Java debugger |

### 2. Execution Model

| Aspect | EVL | Judo Zeta |
|--------|-----|-----------|
| **Execution** | Interpreted | Compiled (JIT/AOT) |
| **Performance** | Good for small models | Optimized for large models |
| **Parallelization** | Sequential only | Automatic parallel execution (5000+ elements) |
| **Chunking** | Not supported | Configurable chunk size (default: 100) |
| **Thread Model** | Single-threaded | ForkJoinPool with work-stealing |

### 3. Feature Set

| Feature | EVL | Judo Zeta |
|---------|-----|-----------|
| **Constraints (errors)** | ✓ | ✓ |
| **Critiques (warnings)** | ✓ | ✓ |
| **Guards** | ✓ | ✓ |
| **Dependency Resolution** | `satisfies()` operations | `@Satisfies` annotation |
| **Lazy Constraints** | ✓ (`@lazy`) | ✗ (all rules execute) |
| **Interactive Fixes** | ✓ | ✗ |
| **Pre/Post Blocks** | ✓ | ✓ |
| **Extension Methods** | EOL operations | `@ExtensionMethod` |
| **Caching** | Manual | `@Cached` annotation |
| **Topological Sorting** | Manual via guards | Automatic via `@Satisfies` |

### 4. Integration & Deployment

| Aspect | EVL | Judo Zeta |
|--------|-----|-----------|
| **Runtime** | Epsilon runtime required | Standalone JAR |
| **OSGi** | Eclipse/OSGi bundle | OSGi bundle or standalone |
| **Dependencies** | Epsilon + EMF + EOL | EMF only |
| **File Format** | `.evl` text files | `.java` source files |
| **Distribution** | Eclipse plugins | Maven/P2 repository |
| **Deployment** | Eclipse workspace/plugins | Any Java application |

## Similarities

Despite their different approaches, EVL and Judo Zeta share several core concepts:

### 1. Model Validation Foundation
Both frameworks validate **EMF models** by checking constraints against model elements and producing validation results.

### 2. Constraint Severity Levels
- **EVL:** `constraint` (error) vs `critique` (warning)
- **Zeta:** `@Constraint` (error) vs `@Critique` (warning)

### 3. Conditional Execution (Guards)
Both support **guard conditions** that determine when a validation rule should execute:
- **EVL:** `guard` block in contexts and constraints
- **Zeta:** `@Guard` annotation with method reference

### 4. Dependency Management
Both can express that one constraint depends on another:
- **EVL:** `satisfies("ConstraintName")` operation
- **Zeta:** `@Satisfies(constraints = {"ConstraintName"})` annotation

### 5. Lifecycle Hooks
Both provide **pre/post validation hooks**:
- **EVL:** `pre` and `post` named blocks
- **Zeta:** `@PreValidation` and `@PostValidation` methods

### 6. Reusable Helper Functions
Both support defining reusable validation logic:
- **EVL:** EOL operations and helper functions
- **Zeta:** `@ExtensionMethod` annotated methods

### 7. Context-Based Organization
Both organize rules by element type:
- **EVL:** `context ElementType { ... }`
- **Zeta:** `@ValidationContext(ElementType.class)`

## Key Differences in Detail

### 1. Built-in Operations vs Java Methods

**EVL** provides a rich set of built-in operations tailored for model validation:
```evl
constraint HasValidName {
    check: self.name.isDefined() and self.name.matches('[A-Z][a-zA-Z0-9]*')
    message: 'Name must start with uppercase letter'
}
```

**Judo Zeta** uses standard Java APIs and custom extension methods:
```java
@Constraint(name = "HasValidName", message = "Name must start with uppercase letter")
public ValidationRule hasValidName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null && 
               entity.getName().matches("[A-Z][a-zA-Z0-9]*")
            ? ValidationResult.pass()
            : ValidationResult.fail("Name must start with uppercase letter");
    };
}
```

### 2. Parallel Execution

**EVL** executes constraints **sequentially** in the order they are defined, with guard-based ordering.

**Judo Zeta** automatically **parallelizes validation** for large models:
- Detects when element count ≥ 5000 (configurable)
- Splits work into chunks of 100 elements
- Uses ForkJoinPool for work-stealing parallelism
- Achieves 3-4x speedup on 8-core CPUs

### 3. Dependency Resolution Strategy

**EVL** uses **guard-based dependencies** with runtime checks:
```evl
constraint NameIsUnique {
    guard: self.satisfies('HasName')
    check: EntityType.allInstances().select(e|e.name = self.name).size() = 1
}
```

**Judo Zeta** uses **compile-time dependency declaration** with topological sorting:
```java
@Satisfies(constraints = {"HasName"})
@Constraint(name = "NameIsUnique", message = "Name must be unique")
public ValidationRule nameIsUnique() {
    return (element, ctx) -> {
        // Guaranteed that HasName constraint passed
    };
}
```

The framework automatically orders rule execution based on dependencies, preventing evaluation of dependent rules when prerequisites fail.

### 4. Interactive Fixes vs Validation-Only

**EVL** provides **interactive quick-fixes** that users can invoke:
```evl
constraint HasName {
    check: self.name.isDefined() and self.name <> ''
    message: 'Entity must have a name'
    fix {
        title: 'Set default name'
        do {
            self.name := 'DefaultEntity';
        }
    }
}
```

**Judo Zeta** focuses purely on **validation and reporting**:
- No built-in fix mechanism
- Validation results returned as data structures
- Client code responsible for corrective actions
- Separation of validation from mutation

### 5. Lazy vs Eager Constraint Evaluation

**EVL** supports **lazy constraints** that execute only on demand:
```evl
@lazy
constraint ExpensiveCheck {
    check: // Only evaluated when explicitly invoked
}
```

**Judo Zeta** executes **all applicable rules** for each element:
- No lazy evaluation mechanism
- Guards determine applicability upfront
- Caching mechanism for expensive computations (`@Cached`)

### 6. Caching Strategy

**EVL** requires **manual caching** through EOL code or custom operations.

**Judo Zeta** provides **declarative caching** via `@Cached` annotation:
```java
@Cached
@Constraint(name = "NoCyclicDependencies", message = "Cyclic dependency detected")
public ValidationRule noCyclicDependencies() {
    return (element, ctx) -> {
        // Result automatically cached per element
        // Expensive graph traversal executed once
    };
}
```

Cache keys can be element-based, string-based, or object-based for fine-grained control.

## Feature Comparison Table

| Feature | EVL | Judo Zeta | Notes |
|---------|-----|-----------|-------|
| **Language** | DSL (EVL/EOL) | Java | EVL requires learning new syntax |
| **Type Safety** | Runtime | Compile-time | Zeta catches errors at compile time |
| **IDE Support** | Eclipse-specific | Any Java IDE | Zeta works in IntelliJ, VS Code, etc. |
| **Execution** | Interpreted | Compiled | Zeta has performance advantage |
| **Parallelization** | No | Yes (automatic) | Zeta scales to large models |
| **Chunk Size** | N/A | Configurable | Zeta default: 100 elements |
| **Thread Model** | Single | ForkJoinPool | Zeta uses work-stealing |
| **Constraints** | ✓ | ✓ | Both support error-level rules |
| **Critiques** | ✓ | ✓ | Both support warning-level rules |
| **Guards** | ✓ | ✓ | Both support conditional execution |
| **Context Guards** | ✓ | ✗ | EVL supports context-level guards |
| **Constraint Guards** | ✓ | ✓ | Both support rule-level guards |
| **Dependencies** | `satisfies()` | `@Satisfies` | Both check prerequisites |
| **Dependency Ordering** | Manual (guards) | Automatic (topological sort) | Zeta resolves automatically |
| **Lazy Evaluation** | ✓ (`@lazy`) | ✗ | EVL supports on-demand execution |
| **Interactive Fixes** | ✓ | ✗ | EVL provides user-invoked fixes |
| **Quick Fixes** | ✓ | ✗ | EVL integrates with Eclipse UI |
| **Pre Blocks** | ✓ | ✓ | Both support pre-validation hooks |
| **Post Blocks** | ✓ | ✓ | Both support post-validation hooks |
| **Extension Methods** | EOL operations | `@ExtensionMethod` | Both support helpers |
| **Caching** | Manual | Declarative (`@Cached`) | Zeta provides built-in caching |
| **Built-in Operations** | Rich (isDefined, matches, etc.) | Standard Java | EVL has model-specific operations |
| **String Matching** | `matches()` | `String.matches()` | EVL has concise syntax |
| **Collection Operations** | EOL collections | Java Streams | Different programming styles |
| **Validation Trace** | Interactive UI | Data structures | EVL integrates with Eclipse |
| **Error Messages** | Template strings | Java strings | EVL supports interpolation |
| **Message Interpolation** | `'Entity ' + self.name` | String concatenation | Both support dynamic messages |
| **OSGi Support** | ✓ | ✓ | Both deployable as OSGi bundles |
| **Standalone** | Requires Epsilon | ✓ | Zeta is self-contained |
| **Dependencies** | Epsilon + EMF | EMF only | Zeta has fewer dependencies |
| **File Format** | `.evl` text | `.java` source | Different development workflows |
| **Versioning** | Text files | Git-friendly Java | Zeta integrates with version control |
| **Testing** | EVL test harness | JUnit | Zeta uses standard testing |
| **Debugging** | EOL debugger | Java debugger | Different debugging workflows |
| **Refactoring** | Limited | Full Java refactoring | Zeta leverages IDE refactoring |
| **Code Generation** | Not applicable | Java compiler | Zeta benefits from compilation |
| **Performance** | Good | Excellent (parallel) | Zeta optimized for large models |
| **Memory Usage** | Moderate | Configurable | Zeta allows tuning |
| **Learning Curve** | Steep (new language) | Gentle (Java developers) | Depends on background |

## Pros and Cons

### EVL Advantages

**Pros:**
1. **Declarative Syntax** - Concise, readable validation rules
2. **Built-in Operations** - Rich set of model-specific operations (`isDefined`, `matches`, `allInstances`)
3. **Interactive Fixes** - Users can invoke corrective actions directly from validation results
4. **Lazy Constraints** - Avoid evaluating expensive constraints unnecessarily
5. **Eclipse Integration** - Tight integration with Eclipse modeling tools
6. **Validation UI** - Built-in interactive validation trace viewer
7. **Quick Development** - Less boilerplate for simple constraints
8. **Template Messages** - Clean message interpolation syntax
9. **Context Guards** - Apply guards at context level for all contained constraints
10. **Mature Ecosystem** - Part of established Epsilon project

**Cons:**
1. **Learning Curve** - Requires learning EVL/EOL syntax
2. **IDE Dependency** - Best experience requires Eclipse
3. **Runtime Interpretation** - Performance overhead compared to compiled code
4. **Sequential Execution** - No parallel validation for large models
5. **Limited Refactoring** - Difficult to rename, extract, or restructure rules
6. **No Compile-Time Checks** - Errors discovered at runtime
7. **Debugging Complexity** - Separate debugger for EOL code
8. **Dependency on Epsilon** - Requires Epsilon runtime infrastructure
9. **Text-Based** - Version control diffs less meaningful than Java
10. **Limited IDE Support** - Not well supported outside Eclipse

### Judo Zeta Advantages

**Pros:**
1. **Java Native** - No new language to learn for Java developers
2. **Type Safety** - Compile-time error detection
3. **IDE Agnostic** - Works in any Java IDE (IntelliJ, Eclipse, VS Code)
4. **Parallel Execution** - Automatic parallelization for large models (3-4x speedup)
5. **Performance** - Compiled code runs faster than interpreted
6. **Refactoring** - Full Java refactoring support (rename, extract, inline)
7. **Debugging** - Standard Java debugger
8. **Testing** - Use JUnit and standard testing frameworks
9. **Standalone** - Minimal dependencies (EMF only)
10. **Caching** - Built-in declarative caching via `@Cached`
11. **Dependency Resolution** - Automatic topological sorting of rules
12. **Version Control** - Git-friendly Java source files
13. **Code Completion** - Full IDE code completion
14. **Extensibility** - Easy to integrate with other Java libraries
15. **Deployment** - Deploy as JAR, OSGi bundle, or P2 update site

**Cons:**
1. **More Boilerplate** - Java syntax more verbose than EVL
2. **No Interactive Fixes** - Must implement corrective actions separately
3. **No Lazy Evaluation** - All applicable rules execute (use guards and caching)
4. **Manual Setup** - More configuration compared to EVL's declarative style
5. **Java Knowledge Required** - Not accessible to non-Java developers
6. **Larger Artifacts** - Compiled code larger than EVL text files
7. **No Built-in Model Operations** - Must use EMF APIs or create extension methods
8. **Less Concise** - Functional interface syntax less readable than EVL DSL

## When to Use EVL

Choose **EVL** when:

1. **Eclipse-Centric Workflow** - Your team already uses Eclipse modeling tools
2. **Interactive Validation** - You need user-invoked quick-fixes
3. **Small to Medium Models** - Model size < 5000 elements
4. **Non-Java Developers** - Team prefers declarative DSL over programming
5. **Rapid Prototyping** - Need to quickly specify simple constraints
6. **Epsilon Ecosystem** - Already using other Epsilon languages (ETL, EGL, etc.)
7. **Lazy Evaluation** - Need selective constraint execution
8. **GUI Integration** - Want built-in validation trace viewer
9. **Text-Based Rules** - Prefer storing rules in text files
10. **Declarative Style** - Team values concise, declarative specifications

**Example Use Cases:**
- Model editors with integrated validation
- Educational environments teaching model validation
- Rapid constraint prototyping
- Small-scale model validation in Eclipse
- Integration with GMF/Sirius editors

## When to Use Judo Zeta

Choose **Judo Zeta** when:

1. **Large Models** - Model size > 5000 elements requiring parallel execution
2. **Performance Critical** - Validation speed is important (batch processing, CI/CD)
3. **Java Developers** - Team is comfortable with Java
4. **Type Safety** - Need compile-time error detection
5. **IDE Flexibility** - Team uses multiple IDEs (IntelliJ, VS Code, Eclipse)
6. **Standalone Deployment** - Need validation outside Eclipse
7. **Minimal Dependencies** - Want lightweight framework
8. **Advanced Caching** - Need sophisticated caching strategies
9. **Automated Testing** - Want to use JUnit and standard testing tools
10. **Refactoring Support** - Need to maintain and evolve validation rules
11. **CI/CD Integration** - Validation runs in automated pipelines
12. **Version Control** - Want Git-friendly source files with meaningful diffs
13. **OSGi/Karaf Deployment** - Need OSGi bundle deployment
14. **Validation-Only** - Don't need interactive fix mechanisms

**Example Use Cases:**
- Server-side model validation in microservices
- Batch validation in CI/CD pipelines
- High-performance validation of large models (10k+ elements)
- Standalone Java applications with EMF models
- API validation services
- Model quality gates in automated workflows

## Migration Considerations

### EVL to Judo Zeta Migration

**Conceptual Mapping:**

```evl
// EVL
context EntityType {
    constraint HasName {
        check: self.name.isDefined() and self.name <> ''
        message: 'Entity must have a name'
    }
}
```

```java
// Judo Zeta
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    @Constraint(name = "HasName", message = "Entity must have a name")
    public ValidationRule hasName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity must have a name");
        };
    }
}
```

**Migration Steps:**
1. Create `@ValidationContext` class for each EVL context
2. Convert each constraint/critique to annotated method
3. Replace EVL guard blocks with `@Guard` annotations
4. Replace `satisfies()` calls with `@Satisfies` annotations
5. Convert EOL operations to Java code or extension methods
6. Replace lazy constraints with guards or caching
7. Remove fix blocks (implement separately if needed)
8. Convert pre/post blocks to annotated methods
9. Test thoroughly with JUnit

**Challenges:**
- EVL's concise syntax becomes more verbose in Java
- Interactive fixes require custom implementation
- Lazy constraints need guards or manual invocation
- Built-in operations require Java equivalents

### Judo Zeta to EVL Migration

Less common, but possible:

**Conceptual Reverse Mapping:**
1. Convert `@ValidationContext` to EVL context
2. Convert `@Constraint`/`@Critique` to constraint/critique blocks
3. Convert `@Guard` to guard blocks
4. Convert `@Satisfies` to `satisfies()` calls in guards
5. Convert Java code to EOL operations
6. Remove `@Cached` (implement manual caching if needed)
7. Add fix blocks for corrective actions
8. Convert lifecycle hooks to pre/post blocks

**Challenges:**
- Java logic may be complex to translate to EOL
- Parallel execution lost (EVL is sequential)
- Type safety lost (runtime checking in EVL)
- Caching must be reimplemented

## Hybrid Approach

In some scenarios, you might use **both** frameworks:

1. **EVL for prototyping** - Quickly specify constraints in EVL
2. **Zeta for production** - Migrate to Zeta for performance and deployment
3. **EVL for interactive validation** - Use EVL in Eclipse editors
4. **Zeta for batch validation** - Use Zeta in CI/CD pipelines
5. **EVL for model editing** - Use EVL with quick-fixes during development
6. **Zeta for API validation** - Use Zeta in REST APIs and services

## Conclusion

**EVL** and **Judo Zeta** represent two complementary philosophies for EMF model validation:

- **EVL** excels in **Eclipse-centric, interactive workflows** with its declarative DSL and quick-fix integration
- **Judo Zeta** excels in **high-performance, production deployments** with parallel execution and Java tooling

The choice depends on your:
- **Team skills** (DSL vs Java)
- **Tooling preferences** (Eclipse vs any IDE)
- **Performance requirements** (small vs large models)
- **Deployment targets** (Eclipse plugins vs standalone applications)
- **Development workflow** (interactive vs automated)

Neither approach is universally superior—each has its strengths and ideal use cases. Understanding these differences enables you to choose the right tool for your specific validation requirements.

---

**Further Reading:**
- [EVL Documentation](https://eclipse.dev/epsilon/doc/evl/)
- [Judo Zeta User Guide](../user-guide/core-concepts.md)
- [Validation Rules](../user-guide/validation-rules.md)
- [Guards and Dependencies](../user-guide/guards-and-dependencies.md)
- [Performance Best Practices](../best-practices/performance.md)

**Sources:**
- [Model Validation (EVL) - Epsilon](https://eclipse.dev/epsilon/doc/evl/)
- [EVL-EMF Validation Integration](https://eclipse.dev/epsilon/doc/articles/evl-emf-integration/)
- [Epsilon Overview](https://eclipse.dev/epsilon/doc/)
