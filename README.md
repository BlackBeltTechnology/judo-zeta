# Judo Zeta Validation Framework

[![Build Status](https://github.com/BlackBeltTechnology/judo-zeta/actions/workflows/build.yml/badge.svg)](https://github.com/BlackBeltTechnology/judo-zeta/actions/workflows/build.yml)

A lightweight, standalone validation framework for Eclipse Modeling Framework (EMF) metamodels. Provides annotation-based validation rules with parallel execution, dependency resolution, and comprehensive caching support.

## Features

- **Annotation-Driven Rules** - Define validation rules using Java annotations instead of Epsilon Validation Language (EVL)
- **Parallel Execution** - Automatic parallelization for large models (5000+ elements)
- **Dependency Resolution** - Topological sorting of rules based on `@Satisfies` dependencies
- **Caching Support** - Built-in result caching for expensive validations
- **Extension Methods** - Custom helper methods accessible from validation rules
- **Lifecycle Hooks** - Pre/post-validation hooks for setup and cleanup
- **OSGi Compatible** - Works as OSGi bundle or standalone library
- **Eclipse P2 Distribution** - Available as Eclipse plugin via P2 update site

## Quick Start

```java
import hu.blackbelt.judo.meta.validation.*;
import hu.blackbelt.judo.meta.validation.annotation.*;
import hu.blackbelt.judo.meta.validation.core.*;

// 1. Define validation rules
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
    
    @Satisfies(constraints = {"MustHaveName"})
    @Constraint(name = "NameMustBeUnique", message = "Name must be unique")
    public ValidationRule nameMustBeUnique() {
        return (element, ctx) -> {
            EntityType entity = (EntityType) element;
            List<EntityType> all = ctx.getAll(EntityType.class);
            long count = all.stream()
                .filter(e -> entity.getName().equals(e.getName()))
                .count();
            return count == 1 
                ? ValidationResult.pass() 
                : ValidationResult.fail("Duplicate name: " + entity.getName());
        };
    }
}

// 2. Execute validation
ValidationRegistry registry = new ValidationRegistry();
registry.register(EntityValidations.class);

ValidationExecutor executor = ValidationExecutor.builder()
    .registry(registry)
    .build();

List<ValidationResult> results = executor.validate(modelElements);

// 3. Process results
results.stream()
    .filter(r -> !r.isValid())
    .forEach(r -> System.err.println(r.getSeverity() + ": " + r.getMessage()));
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

## Parallel Validation

The framework automatically parallelizes validation for large models:

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

## Documentation

- **[AGENTS.md](AGENTS.md)** - Comprehensive developer documentation
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
