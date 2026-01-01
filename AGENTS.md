# Judo Zeta Validation Framework Documentation

## Project Overview

**Repository:** BlackBeltTechnology/judo-zeta  
**License:** Eclipse Public License 2.0 (EPL-2.0)  
**Java Version:** 21  
**Build System:** Maven 3.9.4+ with Maven Wrapper  
**Current Version:** 1.0.0-SNAPSHOT  
**Main Branch:** develop

This is a **lightweight, standalone validation framework for EMF metamodels**. It provides a modern, annotation-based alternative to Epsilon Validation Language (EVL) with parallel execution, dependency resolution, and comprehensive caching support.

**Key Features:**
- Annotation-driven validation rules (no EVL required)
- Parallel validation with intelligent chunking
- Rule dependency resolution via `@Satisfies`
- Caching support for expensive computations
- Extension methods for custom helper functions
- Pre/post-validation hooks
- Both OSGi and standalone deployment

## Project Structure

This project consists of **3 modules**:

```
judo-zeta/
├── validation-core/          # Core validation framework (OSGi bundle)
├── p2/                        # Eclipse P2 update site packaging
├── osgi-itest/               # Pax Exam integration tests
├── .github/                  # GitHub Actions CI/CD workflows
├── .mvn/                     # Maven wrapper and configuration
└── openspec/                 # OpenSpec change management
```

## Core Modules

| Module | Type | Artifact ID | Purpose |
|--------|------|-------------|---------|
| **validation-core** | OSGi bundle | `hu.blackbelt.judo.zeta.validation-core` | Reusable Java validation framework for EMF metamodels |
| **p2** | P2 repository | `hu.blackbelt.judo.zeta.p2` | Eclipse P2 update site packaging |
| **osgi-itest** | Integration test | `hu.blackbelt.judo.zeta.osgi.itest` | Pax Exam OSGi/Karaf integration tests |

## Validation Core Architecture

### Package Structure

```
validation-core/src/main/java/hu/blackbelt/judo/meta/validation/
├── ModelProvider.java                    # Metamodel integration interface
├── annotation/                           # Validation annotations
│   ├── Constraint.java                  # Error-level validation rule
│   ├── Critique.java                    # Warning-level validation rule
│   ├── Guard.java                       # Conditional execution guard
│   ├── Satisfies.java                   # Rule dependency declaration
│   ├── Cached.java                      # Result caching annotation
│   ├── ExtensionMethod.java             # Helper method annotation
│   ├── PreValidation.java               # Pre-validation hook
│   ├── PostValidation.java              # Post-validation hook
│   └── ValidationContext.java           # Element type context
├── core/                                 # Core validation engine
│   ├── ValidationRegistry.java          # Rule registration and discovery
│   ├── ValidationExecutor.java          # Parallel execution engine
│   ├── ValidationContext.java           # Execution context
│   ├── ValidationResult.java            # Result wrapper
│   ├── ValidationRule.java              # Functional interface for rules
│   ├── ValidationRuleBuilder.java       # Fluent builder API
│   ├── ValidatorDescriptor.java         # Rule metadata
│   ├── ExtensionMethodRegistry.java     # Extension method management
│   ├── ExtensionMethodDescriptor.java   # Extension method metadata
│   ├── CacheKey.java                    # Cache key implementation
│   ├── CacheKeyBuilder.java             # Cache key builder
│   ├── Guard.java                       # Guard function interface
│   └── Severity.java                    # ERROR/WARNING severity enum
└── util/
    └── EolStyleCollections.java         # Epsilon-like collection utilities
```

### Annotation System

The validation framework provides 8 core annotations:

#### 1. `@ValidationContext`
Marks a class as containing validation rules for a specific EMF element type.

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    // Validation rules for EntityType elements
}
```

#### 2. `@Constraint`
Defines an error-level validation rule.

```java
@Constraint(name = "EntityMustHaveName", message = "Entity {element.name} must have a name")
public ValidationRule entityMustHaveName() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getName() != null && !entity.getName().isEmpty() 
            ? ValidationResult.pass() 
            : ValidationResult.fail("Entity must have a name");
    };
}
```

#### 3. `@Critique`
Defines a warning-level validation rule.

```java
@Critique(name = "EntityShouldHaveDescription", 
          message = "Entity {element.name} should have a description")
public ValidationRule entityShouldHaveDescription() {
    return (element, ctx) -> {
        EntityType entity = (EntityType) element;
        return entity.getDescription() != null 
            ? ValidationResult.pass() 
            : ValidationResult.warn("Consider adding a description");
    };
}
```

#### 4. `@Guard`
Conditionally enables a validation rule based on a guard method.

```java
@Guard(method = "isNotAbstract")
@Constraint(name = "EntityMustHaveTable", message = "Concrete entity must have table")
public ValidationRule entityMustHaveTable() {
    return (element, ctx) -> { /* ... */ };
}

private boolean isNotAbstract(EObject element) {
    return !((EntityType) element).isAbstract();
}
```

#### 5. `@Satisfies`
Declares dependencies on other validation rules. The annotated rule only runs after its dependencies pass.

```java
@Satisfies(constraints = {"EntityMustHaveName"})
@Constraint(name = "EntityNameMustBeUnique", message = "Entity name must be unique")
public ValidationRule entityNameMustBeUnique() {
    return (element, ctx) -> { /* ... */ };
}
```

#### 6. `@Cached`
Caches the result of expensive validation computations.

```java
@Cached
@Constraint(name = "NoCyclicReferences", message = "Cyclic references detected")
public ValidationRule noCyclicReferences() {
    return (element, ctx) -> {
        // Expensive graph traversal cached per element
    };
}
```

#### 7. `@ExtensionMethod`
Registers a helper method accessible from validation rules via context.

```java
@ExtensionMethod(elementType = EntityType.class)
public List<Attribute> getAllAttributes(EntityType entity) {
    // Helper method implementation
}

// Usage in validation rule:
return (element, ctx) -> {
    List<Attribute> attrs = ctx.callExtension("getAllAttributes", element);
    // ...
};
```

#### 8. `@PreValidation` / `@PostValidation`
Lifecycle hooks executed before/after validation.

```java
@PreValidation
public void setUp(ValidationContext ctx) {
    // Initialize caches, prepare data
}

@PostValidation
public void tearDown(ValidationContext ctx) {
    // Cleanup, logging
}
```

### Parallel Validation Engine

The `ValidationExecutor` automatically parallelizes validation when:
- **Element count ≥ 5000** (configurable threshold)
- **Chunk size ≥ 100 elements** per work unit
- Uses **ForkJoinPool** with work-stealing

```java
// Automatic parallel execution for large models
ValidationRegistry registry = new ValidationRegistry();
registry.register(EntityTypeValidations.class);

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(5000)  // Optional, defaults to 5000
    .chunkSize(100)           // Optional, defaults to 100
    .build();

List<ValidationResult> results = executor.validate(modelElements);
```

## Transformation Framework

### Transformation Core Architecture

The `transformation-core` module provides annotation-based model-to-model transformations:

```
transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/
├── TransformationExecutor.java        # Parallel execution engine with staging
├── TransformationContext.java         # Execution context with staging infrastructure
├── TransformationRegistry.java        # Rule registration and discovery
├── TransformationResult.java          # Result wrapper
├── TransformationTrace.java           # Source-to-target mapping export
├── TransformationException.java       # Fail-fast error handling
├── TransformRuleDescriptor.java       # Rule metadata
├── ElementResolutionCache.java        # Thread-safe source→target cache
└── RuleInheritanceGraph.java          # Rule dependency resolution
```

### Parallel Transformation Execution

The transformation framework uses a **two-phase staging approach** for thread-safe parallel execution:

**Phase 1 (Parallel):**
- Elements are created in parallel threads
- Created elements are staged in `ConcurrentLinkedQueue`
- Element ordering tracked via `AtomicLong` sequence numbers
- XMI IDs stored in `ConcurrentHashMap` for deferred assignment

**Phase 2 (Sequential):**
- Staged elements sorted by creation sequence
- Elements committed to target Resource (single-threaded)
- XMI IDs applied after elements added to Resource

**Post-Transformation XMI ID Application:**
- Elements added through containment references (not via `addToResource()`) may have pending XMI IDs
- Call `applyAllPendingXmiIds()` after transformation completes to ensure all elements have their XMI IDs properly set
- This method iterates through the target resource and applies any pending IDs that weren't applied during the commit phase

```java
// Configure parallel transformation
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)                    // Enable parallel (default: true)
    .parallelThreshold(1000)           // Min elements for parallel (default: 1000)
    .chunkSize(100)                    // Elements per work unit (default: 100)
    .build();

// Execute - executor is reusable
TransformationResult result = executor.transform(sourceElements);
```

### Package Resolution

**Generated Metamodels** - No registration needed, EPackage is auto-discovered:
```java
Table table = ctx.createTarget(Table.class);  // Auto-discovers SchemaPackage
Column col = ctx.create(Column.class);        // Auto-discovers SchemaPackage
```

**Dynamic EMF** - Register packages explicitly:
```java
ctx.registerTargetPackage(dynamicPackage);
EObject obj = ctx.createTarget(dynamicType, dynamicPackage);
```

### Thread-Safety in Transformation Rules

**Safe Operations:**
- `ctx.createTarget()` - Creates staged elements
- `ctx.createTarget(Class, EPackage)` - Creates in specific package
- `ctx.equivalent()` - Thread-safe lazy rule execution via `computeIfAbsent`
- `ctx.equivalentDiscriminated()` - Thread-safe discriminated equivalence
- Setting properties on elements you created
- Reading from source elements

**Unsafe Operations (avoid):**
- Modifying source elements
- Modifying target elements created by other rules
- Shared mutable state between rules

### Fail-Fast Error Handling

```java
try {
    TransformationResult result = executor.transform(sourceElements);
} catch (TransformationException e) {
    EObject failedElement = e.getFailedElement();
    String ruleName = e.getRuleName();
    Throwable cause = e.getCause();
    // Handle error with full context
}
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `TransformationExecutor` | Parallel execution engine with Builder pattern |
| `TransformationContext` | Execution context with staging infrastructure |
| `TransformationException` | RuntimeException with element/rule context |
| `ElementResolutionCache` | Thread-safe ConcurrentHashMap-based cache |
| `TransformationTrace` | JSON-exportable source→target mapping |

### Transformation Annotations

| Annotation | Description |
|------------|-------------|
| `@TransformationContext` | Marks a class as containing transformation rules |
| `@TransformRule` | Defines a transformation rule method |
| `@Lazy` | Rule executes on-demand via `equivalent()` calls |
| `@Abstract` | Rule only executes via parent rule inheritance |
| `@Primary` | Rule's result takes precedence in `equivalent()` |
| `@Greedy` | Matches source type AND all subtypes |
| `@Extends` | Inherits from parent rules (automatic execution) |
| `@Guard` | Conditional execution based on guard method |
| `@Detached` | Output NOT added to Resource.contents (caller adds to container) |
| `@Transform` | Specifies source type and resource alias |
| `@To` | Specifies target type and resource alias |
| `@PreExecution` | Method runs before transformation starts |
| `@PostExecution` | Method runs after transformation completes |

### Dependency Resolution

The framework topologically sorts rules based on `@Satisfies` annotations:

```java
// Rule execution order automatically determined:
// 1. EntityMustHaveName (no dependencies)
// 2. EntityNameMustBeUnique (depends on EntityMustHaveName)
// 3. EntityMustHaveTable (depends on EntityMustHaveName via guard)
```

### Caching Strategy

Three cache key types:
1. **Element-based:** `CacheKey.of(element)` - cache per EMF object
2. **Element + String:** `CacheKey.of(element, "key")` - multiple caches per element
3. **Element + Object:** `CacheKey.of(element, complexKey)` - arbitrary cache keys

```java
@Cached
@Constraint(name = "ExpensiveValidation", message = "...")
public ValidationRule expensiveValidation() {
    return (element, ctx) -> {
        // Result cached automatically based on element
        Object cached = ctx.getCached(CacheKey.of(element));
        if (cached != null) return ValidationResult.pass();
        
        Object result = expensiveComputation(element);
        ctx.putCached(CacheKey.of(element), result);
        return ValidationResult.pass();
    };
}
```

## Technology Stack

### Core Technologies
| Technology | Version | Purpose |
|------------|---------|---------|
| **Java** | 21 | Target language |
| **Maven** | 3.9.4+ | Build system |
| **Eclipse EMF** | 2.38.0 / 2.41.0 | Metamodel foundation |
| **OSGi** | 7.0.0 | Modularity framework |
| **SLF4J** | 2.0.16 | Logging facade |
| **Logback** | 1.5.12 | Logging implementation |
| **Lombok** | 1.18.34 | Annotation processing |

### Build & Testing
| Technology | Version | Purpose |
|------------|---------|---------|
| **Maven Bundle Plugin** | 6.0.0 | OSGi bundle creation |
| **Apache Karaf** | 4.4.7 | OSGi runtime container |
| **Pax Exam** | 4.13.5 | OSGi integration testing |
| **JUnit Jupiter** | 5.11.3 | Unit testing |
| **JaCoCo** | 0.8.12 | Code coverage |

### P2 Repository
| Technology | Version | Purpose |
|------------|---------|---------|
| **p2-maven-plugin** | 2.0.0 (Reficio) | P2 site generation |
| **Maven Assembly Plugin** | 3.4.2 | ZIP packaging |

## Build Commands

### Standard Build
```bash
# Full build with tests
./mvnw clean install

# Skip modules (parent only)
./mvnw clean install -DskipModules=true

# Skip tests
./mvnw clean install -DskipTests

# With code coverage
./mvnw clean verify
```

### Maven Profiles

| Profile | Purpose | Command |
|---------|---------|---------|
| `modules` | Default - builds all 3 modules | (active by default) |
| `sign-artifacts` | GPG signing for releases | `-Psign-artifacts` |
| `release-central` | Maven Central deployment | `-Prelease-central` |
| `release-judong` | Internal Nexus deployment | `-Prelease-judong` |
| `release-dummy` | Test deployment to /tmp | `-Prelease-dummy` |

### Deployment

```bash
# Deploy to Judong Nexus (requires credentials)
./mvnw clean deploy -Prelease-judong

# Deploy to Maven Central (requires GPG)
./mvnw clean deploy -Prelease-central -Psign-artifacts
```

## Usage Examples

### Basic Validation Setup

```java
import hu.blackbelt.judo.meta.validation.*;
import hu.blackbelt.judo.meta.validation.annotation.*;
import hu.blackbelt.judo.meta.validation.core.*;

// 1. Define validation rules
@ValidationContext(MyEntityType.class)
public class MyEntityValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            MyEntityType entity = (MyEntityType) element;
            return entity.getName() != null 
                ? ValidationResult.pass() 
                : ValidationResult.fail("Name is required");
        };
    }
    
    @Satisfies(constraints = {"MustHaveName"})
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            MyEntityType entity = (MyEntityType) element;
            List<MyEntityType> allEntities = ctx.getAll(MyEntityType.class);
            
            long count = allEntities.stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
                
            return count == 1 
                ? ValidationResult.pass() 
                : ValidationResult.fail("Duplicate name: " + entity.getName());
        };
    }
}

// 2. Register and execute
ValidationRegistry registry = new ValidationRegistry();
registry.register(MyEntityValidations.class);

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

// 3. Validate model
List<EObject> elements = myModel.getContents();
List<ValidationResult> results = executor.validate(elements);

// 4. Process results
for (ValidationResult result : results) {
    if (!result.isValid()) {
        System.err.println(result.getSeverity() + ": " + result.getMessage());
    }
}
```

### Using Extension Methods

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    // Define reusable helper
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attrs = new ArrayList<>(entity.getAttributes());
        if (entity.getSuperType() != null) {
            attrs.addAll(getAllAttributes(entity.getSuperType()));
        }
        return attrs;
    }
    
    // Use in validation
    @Constraint(name = "MustHavePrimaryKey", message = "Entity must have primary key")
    public ValidationRule mustHavePrimaryKey() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<Attribute> allAttrs = ctx.callExtension("getAllAttributes", entity);
            
            boolean hasPK = allAttrs.stream().anyMatch(Attribute::isPrimaryKey);
            return hasPK 
                ? ValidationResult.pass() 
                : ValidationResult.fail("No primary key found");
        };
    }
}
```

### OSGi Integration

```java
// OSGi Declarative Services component
@Component(immediate = true)
public class MyModelValidator {
    
    @Reference
    private ModelProvider modelProvider;
    
    @Activate
    public void activate() {
        ValidationRegistry registry = new ValidationRegistry();
        registry.register(EntityTypeValidations.class);
        registry.register(AttributeValidations.class);
        
        ValidationExecutor executor = ValidationExecutor.builder()
            .registry(registry)
            .modelProvider(modelProvider)
            .build();
            
        // Validate on model load
        List<ValidationResult> results = executor.validate(
            modelProvider.getModel().getContents()
        );
        
        results.stream()
            .filter(r -> !r.isValid())
            .forEach(r -> System.err.println(r.getMessage()));
    }
}
```

## OSGi Bundle Configuration

### Exported Packages
- `hu.blackbelt.judo.zeta.validation` - Core interfaces (ModelProvider)
- `hu.blackbelt.judo.zeta.validation.annotation` - All 8 annotations
- `hu.blackbelt.judo.zeta.validation.core` - Validation engine classes
- `hu.blackbelt.judo.zeta.validation.util` - Utility classes

### Bundle Manifest
```
Bundle-SymbolicName: hu.blackbelt.judo.zeta.validation-core
Bundle-Version: 1.0.0.SNAPSHOT
Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=21))"
Export-Package: 
  hu.blackbelt.judo.zeta.validation,
  hu.blackbelt.judo.zeta.validation.annotation,
  hu.blackbelt.judo.zeta.validation.core,
  hu.blackbelt.judo.zeta.validation.util
Import-Package:
  org.eclipse.emf.ecore;version="[2.21,3)",
  org.slf4j;version="[1.6,3)",
  org.osgi.framework;version="[1.8,2.0)"
```

## CI/CD Pipeline

### GitHub Actions Workflow

The project uses a comprehensive CI/CD pipeline (`.github/workflows/build.yml`):

**Trigger Events:**
- Push to `develop` branch
- Pull requests to `develop`, `master`, `increment/*`, `release/*`

**Build Steps:**
1. **Version Calculation** - Timestamp-based versioning
   - Develop: `1.0.0.20251204_181032_abc123def_develop`
   - PR: `1.0.0.20251204_181032_abc123def_PR_42`
   - Master: `1.0.0` (no suffix)

2. **Build & Test** - Maven build with JDK 21
   ```bash
   ./mvnw clean install -Prelease-judong
   ```

3. **Deploy** - Dual deployment strategy:
   - **Maven artifacts** → `https://nexus.judo.technology/repository/maven-judong-snapshots/`
   - **P2 repository** → `https://nexus.judo.technology/repository/p2-judong/judo-zeta/{version}/`

4. **Quality Analysis** - SonarQube integration
   - URL: `https://sonar.judo.technology`
   - Develop branch only

5. **Release Management** - Automated tagging and GitHub releases

**Runner:** Self-hosted `judong` runner  
**Timeout:** 30 minutes  
**Notification:** Discord webhook on completion

## Development Environment

### Required Tools
- **Java 21 JDK** (Zulu, Temurin, or Oracle)
- **Maven 3.9.4+** (or use `./mvnw` wrapper)
- **Git**

### Optional Tools
- **Eclipse IDE** (with m2e, OSGi, and modeling tools)
- **IntelliJ IDEA** (with OSGi and Maven plugins)
- **VS Code** (with Java and Maven extensions)

### JVM Configuration

The build uses these JVM arguments (`.mvn/jvm.config`):
```
-Xms1024m
-Xmx2048m
-Dfile.encoding=UTF-8
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.time=ALL-UNNAMED
-Dtycho.disableP2Mirrors=true
-Djansi.force=true
```

### IDE Setup

**Eclipse:**
1. Import as "Existing Maven Projects"
2. Install OSGi bundle development tools
3. Configure Java 21 JDK
4. Run `./mvnw clean install` first

**IntelliJ IDEA:**
1. Open `pom.xml` as project
2. Configure Project SDK to Java 21
3. Enable Maven auto-import
4. Mark `target/generated-sources` as source roots

## Key Configuration Files

| File | Purpose |
|------|---------|
| `/pom.xml` | Parent POM with 3 modules, dependency management, profiles |
| `/validation-core/pom.xml` | Core validation bundle configuration |
| `/p2/pom.xml` | P2 repository generation |
| `/osgi-itest/pom.xml` | Integration test configuration |
| `/.mvn/jvm.config` | JVM arguments for build |
| `/.mvn/extensions.xml` | Maven extensions (Wagon WebDAV) |
| `/.github/workflows/build.yml` | Main CI/CD pipeline |
| `/logback-test.xml` | Test logging configuration |

## Testing

### OSGi Integration Tests

Located in `osgi-itest/`, using Pax Exam with Apache Karaf 4.4.7:

```java
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class ZetaLoadITest {
    
    @Configuration
    public Option[] config() {
        return options(
            karafDistributionConfiguration()
                .frameworkUrl(maven().groupId("org.apache.karaf")
                    .artifactId("apache-karaf").type("tar.gz").versionAsInProject())
                .karafVersion(karafVersion),
            features(getFeaturesUrl(), "judo-zeta-validation-test")
        );
    }
    
    @Test
    public void testBundlesLoaded() {
        // Validates OSGi bundle activation
    }
}
```

**Test Features:**
- Bundle activation verification
- Service registration checks
- Karaf feature installation
- EMF integration validation

### Running Tests

```bash
# Run all tests
./mvnw clean verify

# Run only unit tests
./mvnw test

# Run only integration tests
./mvnw verify -Dit.test=*ITest

# With code coverage
./mvnw clean verify
# Coverage report: target/site/jacoco/index.html
```

## Distribution

### Maven Artifacts

**Group ID:** `hu.blackbelt.judo.zeta`  
**Artifact ID:** `hu.blackbelt.judo.zeta.validation-core`  
**Version:** `1.0.0-SNAPSHOT`

**Maven Dependency:**
```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>hu.blackbelt.judo.zeta.validation-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### P2 Update Site

**URL:** `https://nexus.judo.technology/repository/p2-judong/judo-zeta/`

**Eclipse Installation:**
1. Help → Install New Software
2. Add site: `https://nexus.judo.technology/repository/p2-judong/judo-zeta/develop/`
3. Select "Judo Zeta Validation Framework"
4. Install and restart

**Feature ID:** `hu.blackbelt.judo.zeta.validation.feature`

### OSGi Bundle

Direct bundle deployment to Karaf:
```bash
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.validation-core/1.0.0-SNAPSHOT
```

## Git Workflow

### Branching Strategy
- **develop** - Main development branch
- **master** - Stable releases
- **increment/*** - Version increment branches
- **release/*** - Release preparation branches
- **feature/*** - Feature branches (merge to develop)
- **hotfix/*** - Hotfix branches (merge to master)

### Versioning
- **SNAPSHOT:** `1.0.0-SNAPSHOT` (development)
- **CI Build:** `1.0.0.20251204_181032_abc123def_develop`
- **Release:** `1.0.0` (no suffix)

### Commit Guidelines
- Use conventional commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- Reference Jira tickets: `[JUDO-123] feat: Add parallel validation`
- Sign commits with GPG (recommended)

## OpenSpec Workflow

This project uses OpenSpec for spec-driven development. See `openspec/AGENTS.md` for details.

**When to use OpenSpec:**
- Adding new validation annotations
- Changing parallel execution strategy
- Modifying dependency resolution algorithm
- Breaking API changes
- Major architectural decisions

**OpenSpec Commands:**
- `/openspec:proposal` - Create new change proposal
- `/openspec:apply` - Implement approved proposal
- `/openspec:archive` - Archive deployed change

## Performance Characteristics

### Parallel Validation
- **Threshold:** 5000 elements (configurable)
- **Chunk Size:** 100 elements per work unit
- **Thread Pool:** ForkJoinPool with work-stealing
- **Speedup:** ~3-4x on 8-core CPU for large models (>10k elements)

### Memory Usage
- **Baseline:** ~50MB for framework
- **Per Element:** ~1KB (model-dependent)
- **Cache Overhead:** Configurable, ~10-20% increase with caching

### Validation Speed
- **Simple rules:** ~0.01ms per element
- **Complex rules:** ~0.1-1ms per element
- **Cached rules:** ~0.001ms per element (cache hit)

**Benchmark Example (10,000 elements, 50 rules):**
- Sequential: ~5 seconds
- Parallel (8 cores): ~1.5 seconds

## Troubleshooting

### Common Issues

**1. ClassNotFoundException in OSGi**
```
Solution: Ensure all required EMF bundles are installed
- org.eclipse.emf.ecore
- org.eclipse.emf.common
- org.eclipse.emf.ecore.xmi
```

**2. Validation Rules Not Discovered**
```
Solution: Check @ValidationContext annotation on class
- Must be present on validation class
- Element type must match validated elements
```

**3. Parallel Validation Not Triggering**
```
Solution: Verify element count and threshold
- Default threshold: 5000 elements
- Adjust with ValidationExecutor.builder().parallelThreshold(n)
```

**4. Cache Not Working**
```
Solution: Ensure @Cached annotation and unique cache keys
- Use CacheKey.of(element) or CacheKey.of(element, key)
- Check cache key equality (hashCode/equals)
```

### Debug Logging

Enable debug logging via `logback.xml`:
```xml
<logger name="hu.blackbelt.judo.meta.validation" level="DEBUG"/>
```

Log output shows:
- Rule registration
- Dependency resolution
- Parallel execution strategy
- Cache hit/miss rates
- Validation timing

## Security Considerations

- **Input Validation:** Framework validates EMF models only (type-safe)
- **Code Injection:** Not applicable (annotation-based, compile-time)
- **Resource Limits:** Configurable chunk size and thread pool size
- **OSGi Security:** Standard OSGi security manager compatible

## License

Eclipse Public License 2.0 (EPL-2.0)

Copyright (c) 2018-2025 BlackBelt Technology

See `LICENSE` file for full license text.

## Contributors

- **Róbert Csákány** ([@robertcsakany](https://github.com/robertcsakany)) - Core developer

## Related Projects

- **judo-meta-esm** - Enterprise Service Model metamodel
- **judo-runtime-core** - Judo runtime platform
- **epsilon-runtime** - Epsilon validation language runtime

## Support

- **Issues:** https://github.com/BlackBeltTechnology/judo-zeta/issues
- **Email:** support@blackbelt.hu
- **Documentation:** This file + inline Javadocs

## Changelog

See individual commit messages and GitHub releases for detailed changelog.

**Version 1.0.0-SNAPSHOT (Current):**
- Initial validation framework implementation
- Annotation-based rule definition
- Parallel validation support
- Dependency resolution
- Caching infrastructure
- OSGi bundle packaging
- P2 repository distribution
