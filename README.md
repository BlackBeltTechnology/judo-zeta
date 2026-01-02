# Judo Zeta Framework

[![Build Status](https://github.com/BlackBeltTechnology/judo-zeta/actions/workflows/build.yml/badge.svg)](https://github.com/BlackBeltTechnology/judo-zeta/actions/workflows/build.yml)

A lightweight, standalone framework for Eclipse Modeling Framework (EMF) metamodels providing:

- **Validation Framework** - Annotation-based validation rules with parallel execution, dependency resolution, and comprehensive caching support
- **Transformation Framework** - Annotation-based model-to-model transformations replacing Epsilon ETL with type-safe Java implementations

## Features

### Validation Framework
- **Annotation-Driven Rules** - Define validation rules using Java annotations instead of Epsilon Validation Language (EVL)
- **Parallel Execution** - Automatic parallelization for large models (5000+ elements)
- **Dependency Resolution** - Topological sorting of rules based on `@Satisfies` dependencies
- **Caching Support** - Built-in result caching for expensive validations

### Transformation Framework
- **Type-Safe Transformations** - Replace ETL scripts with Java-based transformation rules
- **Element Resolution** - `equivalent()`, `equivalents()`, and `equivalentDiscriminated()` for target lookup
- **Rule Inheritance** - `@Abstract`, `@Extends` for reusable transformation hierarchies
- **Lazy Evaluation** - On-demand transformation execution with `@Lazy` annotation
- **Greedy Matching** - Type hierarchy matching with `@Greedy` annotation
- **Activity-Based Greedy** - ETL-compatible `@ActivityBased` for processing only activated elements
- **Multi-Model Support** - `@Transform`/`@To` annotations with resource aliases for multi-model transformations
- **Thread-Safe Parallel Execution** - Two-phase staging approach for EMF thread-safety (threshold: 1000 elements)
- **Atomic Rule Execution** - Per-key locking prevents duplicate elements in parallel mode
- **Fail-Fast Error Handling** - Immediate abort with `TransformationException` containing element/rule context
- **Deterministic Ordering** - Maintains element creation order in parallel mode

### Shared Features
- **Extension Methods** - Custom helper methods accessible from rules
- **Lifecycle Hooks** - Pre/post-execution hooks for setup and cleanup
- **OSGi Compatible** - Works as OSGi bundle or standalone library
- **Eclipse P2 Distribution** - Available as Eclipse plugin via P2 update site

## Quick Start

### Validation Example

```java
@ValidationContext(EntityType.class)
public class EntityValidations {
    
    @Constraint(name = "MustHaveName", message = "Entity must have name")
    public ValidationRule mustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null 
                ? ValidationResult.pass() 
                : ValidationResult.fail("Name is required");
        };
    }
}

// Execute validation
ValidationRegistry registry = new ValidationRegistry();
registry.register(EntityValidations.class);
ValidationExecutor executor = ValidationExecutor.builder().registry(registry).build();
List<ValidationResult> results = executor.validate(modelElements);
```

### Transformation Example

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            // Transform attributes to columns
            entity.getAttributes().forEach(attr -> {
                Column col = ctx.equivalent(attr, Column.class);
                table.getColumns().add(col);
            });
            return table;
        };
    }
}

// Execute transformation with parallel support
TransformationRegistry registry = new TransformationRegistry();
registry.register(EntityTransformations.class);

TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)                    // Enable parallel (default)
    .parallelThreshold(1000)           // Min elements for parallel
    .build();

try {
    TransformationResult result = executor.transform(sourceModel);
} catch (TransformationException e) {
    // Fail-fast with full error context
    log.error("Failed in rule '{}' on element {}: {}",
        e.getRuleName(), e.getFailedElement(), e.getCause().getMessage());
}
```

## Project Structure

```
judo-zeta/
├── validation-core/          # Core validation framework (OSGi bundle)
├── p2/                        # Eclipse P2 update site packaging
├── osgi-itest/               # Pax Exam OSGi integration tests
├── .github/                  # GitHub Actions CI/CD workflows
└── .mvn/                     # Maven wrapper configuration
```

### Modules

| Module | Type | Purpose |
|--------|------|---------|
| **validation-core** | OSGi bundle | Core validation framework with annotations, execution engine, and utilities |
| **p2** | P2 repository | Eclipse P2 update site for plugin installation |
| **osgi-itest** | Integration test | Pax Exam tests for OSGi/Karaf deployment |

## Technology Stack

- **Java 21** - Target language
- **Maven 3.9.4+** - Build system with wrapper included
- **Eclipse EMF 2.38.0+** - Metamodel foundation
- **OSGi 7.0.0** - Modularity framework
- **Apache Karaf 4.4.7** - OSGi runtime for testing
- **Pax Exam 4.13.5** - OSGi integration testing
- **JUnit Jupiter 5.11.3** - Unit testing
- **Lombok 1.18.34** - Annotation processing

## Documentation

Comprehensive documentation is available in the [docs/](docs/) directory:

### Documentation Hub

- **[Documentation Index](docs/index.md)** - Central navigation for all documentation

### Validation Framework

- **[Getting Started](docs/validation/getting-started.md)** - Install and write your first validation rule
- **[User Guide](docs/validation/user-guide/core-concepts.md)** - Core concepts, rules, guards, caching
- **[EVL Comparison](docs/validation/evl-comparison/overview.md)** - Migration from Epsilon EVL
- **[Best Practices](docs/validation/best-practices/constants.md)** - Production patterns
- **[Examples](docs/validation/examples/simple-validations.md)** - Working examples
- **[Reference](docs/validation/reference/annotations.md)** - API reference

### Transformation Framework

- **[Getting Started](docs/transformation/getting-started.md)** - Install and write your first transformation
- **[User Guide](docs/transformation/user-guide/core-concepts.md)** - Core concepts, rules, element resolution
- **[ETL Comparison](docs/transformation/etl-comparison/overview.md)** - Migration from Epsilon ETL
- **[Best Practices](docs/transformation/best-practices/rule-naming.md)** - Production patterns
- **[Examples](docs/transformation/examples/simple-transformations.md)** - Working examples
- **[Architecture](docs/transformation/architecture/overview.md)** - System internals
- **[Reference](docs/transformation/reference/annotations.md)** - API reference

## Installation

### Maven Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>hu.blackbelt.judo.zeta.validation-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Eclipse P2 Update Site

1. Open Eclipse IDE
2. Go to **Help → Install New Software**
3. Click **Add...** and enter:
   - Name: `Judo Zeta Validation`
   - Location: `https://nexus.judo.technology/repository/p2-judong/judo-zeta/develop/`
4. Select **Judo Zeta Validation Framework**
5. Click **Next**, accept licenses, and **Finish**
6. Restart Eclipse

### OSGi Bundle (Karaf)

```bash
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.validation-core/1.0.0-SNAPSHOT
```

## Building from Source

### Prerequisites

- Java 21 JDK (Zulu, Temurin, or Oracle)
- Maven 3.9.4+ (or use included wrapper)
- Git

### Clone and Build

```bash
git clone https://github.com/BlackBeltTechnology/judo-zeta.git
cd judo-zeta
./mvnw clean install
```

### Maven Settings

For access to internal dependencies, configure `~/.m2/settings.xml`:

```xml
<settings>
    <servers>
        <server>
            <id>judong-nexus-distribution</id>
            <username>${env.NEXUS_USERNAME}</username>
            <password>${env.NEXUS_PASSWORD}</password>
        </server>
    </servers>
    
    <mirrors>
        <mirror>
            <id>judong-nexus-mirror</id>
            <name>Judo NG Nexus Mirror</name>
            <url>https://nexus.judo.technology/repository/maven-public/</url>
            <mirrorOf>central</mirrorOf>
        </mirror>
    </mirrors>
</settings>
```

### Build Profiles

| Profile | Purpose |
|---------|---------|
| `modules` (default) | Builds all 3 modules |
| `sign-artifacts` | GPG signing for Maven Central releases |
| `release-central` | Deploy to Maven Central (requires GPG) |
| `release-judong` | Deploy to internal Nexus repository |
| `release-dummy` | Test deployment to `/tmp` directory |

#### Examples

```bash
# Full build with tests
./mvnw clean install

# Skip tests
./mvnw clean install -DskipTests

# Build parent only
./mvnw clean install -DskipModules=true

# Deploy to Judong Nexus
./mvnw clean deploy -Prelease-judong

# Deploy to Maven Central (requires GPG)
./mvnw clean deploy -Prelease-central -Psign-artifacts
```

## Validation Annotations

### Core Annotations

| Annotation | Purpose |
|------------|---------|
| `@ValidationContext` | Marks a class as containing validation rules for a specific EMF element type |
| `@Constraint` | Defines an error-level validation rule |
| `@Critique` | Defines a warning-level validation rule |
| `@Guard` | Conditionally enables a rule based on a guard method |
| `@Satisfies` | Declares dependencies on other validation rules |
| `@Cached` | Caches the result of expensive validation computations |
| `@ExtensionMethod` | Registers a helper method accessible from validation rules |
| `@PreValidation` | Lifecycle hook executed before validation |
| `@PostValidation` | Lifecycle hook executed after validation |

### Example: Complete Validation Class

```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    
    // Error-level constraint
    @Constraint(name = "EntityMustHaveName", message = "Entity must have a name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getName() != null && !entity.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("Entity name is required");
        };
    }
    
    // Warning-level critique
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
    
    // Conditional validation with guard
    @Guard(method = "isNotAbstract")
    @Satisfies(constraints = {"EntityMustHaveName"})
    @Constraint(name = "ConcreteMustHaveTable", message = "Concrete entity must have table")
    public ValidationRule concreteMustHaveTable() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            return entity.getTableName() != null
                ? ValidationResult.pass()
                : ValidationResult.fail("Concrete entity requires table name");
        };
    }
    
    private boolean isNotAbstract(EObject element) {
        return !((EntityType) element).isAbstract();
    }
    
    // Extension method (helper)
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        List<Attribute> attrs = new ArrayList<>(entity.getAttributes());
        if (entity.getSuperType() != null) {
            attrs.addAll(getAllAttributes(entity.getSuperType()));
        }
        return attrs;
    }
    
    // Cached expensive validation
    @Cached
    @Constraint(name = "NoCyclicInheritance", message = "Cyclic inheritance detected")
    public ValidationRule noCyclicInheritance() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            Set<EntityType> visited = new HashSet<>();
            EntityType current = entity;
            
            while (current != null) {
                if (!visited.add(current)) {
                    return ValidationResult.fail(
                        "Cyclic inheritance: " + entity.getName()
                    );
                }
                current = current.getSuperType();
            }
            return ValidationResult.pass();
        };
    }
    
    // Pre-validation setup
    @PreValidation
    public void setUp(ValidationContext ctx) {
        System.out.println("Starting EntityType validation...");
    }
    
    // Post-validation cleanup
    @PostValidation
    public void tearDown(ValidationContext ctx) {
        System.out.println("EntityType validation complete.");
    }
}
```

## Parallel Execution

Both validation and transformation frameworks support parallel execution for large models.

### Parallel Validation

- **Threshold:** 5000 elements (configurable)
- **Chunk Size:** 100 elements per work unit (configurable)
- **Thread Pool:** ForkJoinPool with work-stealing
- **Speedup:** ~3-4x on 8-core CPU for models with >10k elements

```java
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(5000)  // Optional: default is 5000
    .chunkSize(100)           // Optional: default is 100
    .build();

List<ValidationResult> results = executor.validate(elements);
```

### Parallel Transformation

The transformation framework uses a **two-phase staging approach** for thread-safe parallel execution:

1. **Phase 1 (Parallel):** Elements created and staged in thread-safe queue
2. **Phase 2 (Sequential):** Staged elements committed to EMF Resource

- **Threshold:** 1000 elements (configurable)
- **Thread-Safe:** Uses `ConcurrentLinkedQueue` for staging
- **Deterministic:** Maintains element creation order
- **Fail-Fast:** Aborts on first error with context

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .parallel(true)                    // Enable parallel (default)
    .parallelThreshold(1000)           // Min elements for parallel
    .chunkSize(100)                    // Elements per work unit
    .build();

// Executor is reusable - state resets automatically
TransformationResult result1 = executor.transform(sourceElements1);
TransformationResult result2 = executor.transform(sourceElements2);
```

### Thread-Safety Guidelines for Transformation Rules

**Safe Operations:**
- `ctx.createTarget()` - Creates staged elements
- `ctx.equivalent()` - Thread-safe lazy rule execution
- Setting properties on elements you created
- Reading from source elements

**Avoid:**
- Modifying source elements
- Modifying target elements created by other rules
- Shared mutable state between rules

## OSGi Integration

### Declarative Services Component

```java
import org.osgi.service.component.annotations.*;

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
            
        List<ValidationResult> results = executor.validate(
            modelProvider.getModel().getContents()
        );
        
        results.stream()
            .filter(r -> !r.isValid())
            .forEach(r -> System.err.println(
                r.getSeverity() + ": " + r.getMessage()
            ));
    }
}
```

### Bundle Manifest

The validation-core bundle exports:

- `hu.blackbelt.judo.zeta.validation` - Core interfaces
- `hu.blackbelt.judo.zeta.validation.annotation` - All annotations
- `hu.blackbelt.judo.zeta.validation.core` - Validation engine
- `hu.blackbelt.judo.zeta.validation.util` - Utilities

## Testing

### Run Tests

```bash
# All tests (unit + integration)
./mvnw clean verify

# Unit tests only
./mvnw test

# Integration tests only
./mvnw verify -Dit.test=*ITest

# With code coverage
./mvnw clean verify
# Report: target/site/jacoco/index.html
```

### OSGi Integration Tests

The `osgi-itest` module uses Pax Exam with Apache Karaf:

```java
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class ZetaLoadITest {
    
    @Configuration
    public Option[] config() {
        return options(
            karafDistributionConfiguration()
                .frameworkUrl(maven()
                    .groupId("org.apache.karaf")
                    .artifactId("apache-karaf")
                    .type("tar.gz")
                    .versionAsInProject())
                .karafVersion(karafVersion),
            features(getFeaturesUrl(), "judo-zeta-validation-test")
        );
    }
    
    @Test
    public void testBundleActivation() {
        // Validates bundle loads correctly in OSGi
    }
}
```

## CI/CD Pipeline

The project uses GitHub Actions for continuous integration and deployment.

### Build Workflow

**Triggers:** Push to `develop`, PRs to `develop`/`master`/`increment/*`/`release/*`

**Steps:**

1. Calculate version (timestamp-based for branches)
2. Build with Maven and JDK 21
3. Deploy to Nexus (Maven artifacts)
4. Deploy to P2 repository
5. Run SonarQube analysis (develop only)
6. Create GitHub release (tags)
7. Send Discord notification

**Versioning:**

- Develop: `1.0.0.20251204_181032_abc123def_develop`
- PR: `1.0.0.20251204_181032_abc123def_PR_42`
- Master: `1.0.0` (release version)

### Deployment Targets

- **Maven:** https://nexus.judo.technology/repository/maven-judong-snapshots/
- **P2 (versioned):** https://nexus.judo.technology/repository/p2-judong/judo-zeta/{version}/
- **P2 (develop):** https://nexus.judo.technology/repository/p2-judong/judo-zeta/develop/

## Performance

### Benchmarks

**Test Configuration:** 10,000 elements, 50 validation rules

| Mode | Time | Speedup |
|------|------|---------|
| Sequential | ~5.0s | 1.0x |
| Parallel (4 cores) | ~2.0s | 2.5x |
| Parallel (8 cores) | ~1.5s | 3.3x |

### Memory Usage

- **Baseline:** ~50MB for framework
- **Per Element:** ~1KB (model-dependent)
- **Cache Overhead:** ~10-20% with caching enabled

### Rule Execution Speed

- **Simple rules:** ~0.01ms per element
- **Complex rules:** ~0.1-1ms per element
- **Cached rules:** ~0.001ms per element (cache hit)

## Troubleshooting

### Common Issues

#### ClassNotFoundException in OSGi

**Problem:** Cannot find EMF classes in OSGi environment

**Solution:** Ensure required EMF bundles are installed:

```bash
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.ecore/2.38.0
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.common/2.41.0
karaf@root()> bundle:install -s mvn:org.eclipse.emf/org.eclipse.emf.ecore.xmi/2.38.0
```

#### Validation Rules Not Discovered

**Problem:** Rules not executing

**Solution:** Check `@ValidationContext` annotation:

- Must be present on validation class
- Element type must match validated elements
- Class must be registered: `registry.register(MyValidations.class)`

#### Parallel Validation Not Triggering

**Problem:** Sequential execution on large models

**Solution:** Verify threshold and element count:

```java
// Lower threshold for smaller models
ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .parallelThreshold(1000)  // Default is 5000
    .build();
```

#### Cache Not Working

**Problem:** Expensive computations not cached

**Solution:** Ensure proper cache key usage:

```java
@Cached
@Constraint(name = "ExpensiveRule", message = "...")
public ValidationRule expensiveRule() {
    return (element, ctx) -> {
        CacheKey key = CacheKey.of(element);
        Object cached = ctx.getCached(key);
        if (cached != null) {
            return ValidationResult.pass();
        }
        
        Object result = expensiveComputation(element);
        ctx.putCached(key, result);
        return ValidationResult.pass();
    };
}
```

### Debug Logging

Enable debug logging in `logback.xml`:

```xml
<logger name="hu.blackbelt.judo.meta.validation" level="DEBUG"/>
```

Log output shows:

- Rule registration and discovery
- Dependency resolution order
- Parallel execution decisions
- Cache hit/miss statistics
- Validation timing

## Additional Resources

- **[Comprehensive Documentation](docs/index.md)** - Complete user guide, examples, and reference
- **[AGENTS.md](AGENTS.md)** - Developer documentation for project contributors
- **JavaDoc** - Inline API documentation
- **OpenSpec** - Spec-driven development workflow (`openspec/AGENTS.md`)

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit changes: `git commit -am 'Add new feature'`
4. Push to branch: `git push origin feature/my-feature`
5. Submit a pull request to `develop` branch

### Coding Standards

- Java 21 features encouraged
- Follow existing code style
- Add unit tests for new features
- Update documentation
- Use conventional commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`

## License

Eclipse Public License 2.0 (EPL-2.0)

Copyright (c) 2018-2025 BlackBelt Technology

See [LICENSE](LICENSE) file for full license text.

## Support

- **Issues:** https://github.com/BlackBeltTechnology/judo-zeta/issues
- **Documentation:** [AGENTS.md](AGENTS.md)
- **Email:** support@blackbelt.hu
- **Nexus:** https://nexus.judo.technology
- **SonarQube:** https://sonar.judo.technology

## Related Projects

- [judo-meta-esm](https://github.com/BlackBeltTechnology/judo-meta-esm) - Enterprise Service Model metamodel
- [judo-runtime-core](https://github.com/BlackBeltTechnology/judo-runtime-core) - Judo runtime platform
- [epsilon-runtime](https://github.com/BlackBeltTechnology/epsilon-runtime) - Epsilon runtime for Java

## Contributors

- **Róbert Csákány** ([@robertcsakany](https://github.com/robertcsakany)) - Core developer
- BlackBelt Technology Team

## Releases

All releases are available at: https://github.com/BlackBeltTechnology/judo-zeta/releases

**Current Version:** 1.0.0-SNAPSHOT (in development)
