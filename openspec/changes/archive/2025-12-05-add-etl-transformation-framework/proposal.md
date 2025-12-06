# Proposal: Add ETL Transformation Framework

**Change ID**: `add-etl-transformation-framework`  
**Status**: Draft  
**Created**: 2025-12-05  
**Type**: Feature

## Summary

Create an annotation-based Java transformation framework with a modular architecture:

1. **zeta-common** - New shared utilities module (ModelProvider, CacheKey, ExtensionMethodRegistry)
2. **zeta-annotations** - New shared annotations module (all validation + transformation annotations)
3. **transformation-core** - New transformation module (ETL→Java replacement)
4. **validation-core** - Refactored to use shared modules

This provides the Java equivalent of Epsilon Transformation Language (ETL) for model-to-model transformations, while establishing a clean modular architecture for code sharing between validation and transformation frameworks.

## Motivation

The judo-tatami projects currently rely on Epsilon Transformation Language (ETL) for model-to-model transformations. Similar to how the `validation-core` module replaced EVL (Epsilon Validation Language) with annotation-based Java validation, we need a Java-based transformation framework that:

- **Eliminates ETL Runtime Dependencies**: Reduces deployment complexity by removing Epsilon runtime requirements
- **Provides Type Safety**: Leverage Java's compile-time type checking instead of dynamic ETL scripts
- **Enables IDE Support**: Full autocomplete, refactoring, and debugging capabilities
- **Improves Performance**: Direct Java execution without script interpretation overhead
- **Maintains Feature Parity**: Support all essential ETL capabilities (lazy rules, inheritance, element resolution)
- **Follows Proven Patterns**: Reuse the successful architecture from `validation-core`

Currently, judo-tatami transformation code exists in ETL scripts that are:
- Difficult to debug
- Lack compile-time type safety
- Require separate runtime infrastructure
- Cannot leverage Java ecosystem tools

## Goals

1. **Core Transformation Module**: Create `transformation-core` OSGi bundle with annotation-based transformation framework
2. **Annotation System**: Design comprehensive annotations matching ETL capabilities:
   - `@TransformationContext` - Mark transformation context classes
   - `@TransformRule` - Define transformation rules (source → target)
   - `@Lazy` - Mark lazy-evaluated rules
   - `@Abstract` - Mark abstract rules for inheritance
   - `@Primary` - Mark primary rules for element resolution
   - `@Greedy` - Enable broader type matching (kind-of vs type-of semantics)
   - `@Guard` - Conditional rule execution
   - `@Extends` - Rule inheritance
   - `@PreTransformation` / `@PostTransformation` - Lifecycle hooks
3. **Element Resolution**: Implement `equivalent()` and `equivalents()` operations with transformation trace
4. **Advanced Element Resolution**: Implement `equivalentDiscriminated()` for multiple transformations of same source with different discriminators
5. **Transformation Trace**: Automatic collection of source→target mappings with rule names, timestamps, and JSON export capability
6. **Rule Execution Engine**: Build executor supporting:
   - Two-phase execution (eager + lazy)
   - Dependency resolution and inheritance
   - Abstract rule inheritance chain execution
   - Parallel execution for large models
   - Pre/post transformation hooks
7. **Type Matching Strategies**: Support both exact type matching and greedy inheritance-aware matching
8. **Integration**: Seamless EMF model integration (input/output models)
9. **Documentation**: Comprehensive documentation and migration guide from ETL
10. **Testing**: Full test coverage following `validation-core` patterns

## Non-Goals

- **Full ETL Language Parser**: Not implementing an ETL-to-Java compiler
- **EOL Runtime**: Not replacing all Epsilon Object Language features
- **Graphical Editors**: No visual transformation editors
- **Model Comparison**: Separate concern, not part of transformation framework
- **Migration Tools**: Automated ETL-to-Java conversion tools (may be future work)
- **Backward Compatibility**: No requirement to support legacy ETL scripts directly

## Scope

### In Scope

- `transformation-core` module with OSGi bundle packaging
- Annotation-based transformation API
- Transformation registry and rule discovery
- Transformation executor with parallel execution
- Element resolution cache (transformation trace)
- Rule inheritance mechanism
- Guard condition evaluation
- Lazy and eager rule execution
- Pre/post transformation hooks
- Extension method support (helper functions)
- EMF ResourceSet integration
- Comprehensive test suite
- Documentation and migration guide
- Maven build integration

### Out of Scope

- IDE plugins or tooling
- Graphical transformation designers
- Model diff/merge capabilities
- Transaction management (handled by EMF)
- Distributed transformations
- Real-time streaming transformations
- ETL script parsing or compatibility layer

## Problem Statement

Current judo-tatami projects use ETL scripts with advanced features like:

### Basic Transformation Example
```etl
rule EntityType2Table
    transform e : ESM!EntityType
    to t : RDBMS!Table {
    
    guard : not e.isAbstract
    
    t.name = e.name;
    t.schema ::= e.entityNamespace;
    
    for (attr in e.attributes) {
        var col = new RDBMS!Column;
        col.name = attr.name;
        t.columns.add(col);
    }
}
```

### Advanced Features Required

**@greedy Annotation and Inheritance Matching**:
```etl
@greedy
rule NamedElement2NamedType
    transform s : ESM!NamedElement
    to t : PSM!NamedType {
    // Matches NamedElement AND all its subtypes (EntityType, ActorType, etc.)
}
```

**Abstract Rules as Templates**:
```etl
@abstract
rule CreateNamedElement
    transform s : ESM!NamedElement
    to t : PSM!NamedElement {
    t.name = s.name;
}

rule CreateOperation extends CreateNamedElement
    transform s : ESM!Operation
    to t : PSM!BoundOperation {
    // Parent logic executes first (setting name)
    // Then child-specific logic
    t.binding = s.binding;
}
```

**Discriminated Equivalence for Multiple Transformations**:
```etl
// Transform same source differently based on context
var createOp = s.equivalentDiscriminated("CreateOperation", "create");
var updateOp = s.equivalentDiscriminated("CreateOperation", "update");
// Both transformations of same source, different instances with unique IDs
```

This requires:
- ETL runtime at deployment
- Separate script files to maintain
- No compile-time validation
- Limited debugging capabilities
- Complex IDE setup
- Advanced type matching and inheritance logic
- Discriminated transformation caching

The equivalent Java code for basic transformation should be:

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations {
    
    @TransformRule(name = "EntityType2Table")
    @Guard(method = "isNotAbstract")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (source, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(source.getName());
            table.setSchema(ctx.equivalent(source.getEntityNamespace(), Schema.class));
            
            for (Attribute attr : source.getAttributes()) {
                Column col = ctx.create(Column.class);
                col.setName(attr.getName());
                table.getColumns().add(col);
            }
            
            return table;
        };
    }
    
    private boolean isNotAbstract(EntityType entity) {
        return !entity.isAbstract();
    }
}
```

### Advanced Java Patterns

**Greedy Type Matching**:
```java
@TransformationContext(source = NamedElement.class, target = NamedType.class)
public class NamedElementTransformations {
    
    @TransformRule(name = "NamedElement2NamedType")
    @Greedy  // Matches NamedElement and ALL subtypes
    public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
        return (source, ctx) -> {
            NamedType type = ctx.createTarget(NamedType.class);
            type.setName(source.getName());
            return type;
        };
    }
}
```

**Abstract Rule Inheritance**:
```java
@TransformationContext(source = NamedElement.class, target = NamedElement.class)
public class NamedElementTransformations {
    
    @TransformRule(name = "CreateNamedElement")
    @Abstract  // Never executes directly
    public TransformFunction<NamedElement, NamedElement> createNamedElement() {
        return (source, ctx) -> {
            NamedElement target = ctx.createTarget(NamedElement.class);
            target.setName(source.getName());
            return target;
        };
    }
    
    @TransformRule(name = "CreateOperation")
    @Extends("CreateNamedElement")  // Parent logic executes first
    public TransformFunction<Operation, BoundOperation> createOperation() {
        return (source, ctx) -> {
            // Execute parent rule first
            BoundOperation op = ctx.executeParentRule("CreateNamedElement", source);
            // Then child-specific logic
            op.setBinding(source.getBinding());
            return op;
        };
    }
}
```

**Discriminated Equivalence**:
```java
@TransformRule(name = "ProcessRelation")
public TransformFunction<Relation, void> processRelation() {
    return (source, ctx) -> {
        // Create different transformations with discriminators
        Operation createOp = ctx.equivalentDiscriminated(
            source, Operation.class, "CreateOperation", "create"
        );
        Operation updateOp = ctx.equivalentDiscriminated(
            source, Operation.class, "CreateOperation", "update"
        );
        
        // Each has unique ID: baseId/(discriminator/create) and baseId/(discriminator/update)
        // Results are cached separately
        
        return null;
    };
}
```

## Proposed Solution

### Architecture Overview

The transformation framework will use a **modular architecture** with shared modules for common functionality:

```
NEW MODULES:
============

zeta-common/                                  # NEW: Shared utilities module
├── src/main/java/hu/blackbelt/judo/zeta/common/
│   ├── ModelProvider.java                   # Model traversal interface
│   ├── CacheKey.java                        # Immutable cache key
│   ├── CacheKeyBuilder.java                 # Cache key builder (EObject→XMI ID, etc.)
│   ├── ExtensionMethodRegistry.java         # Extension method invocation with caching
│   └── ExtensionMethodDescriptor.java       # Extension method metadata
└── pom.xml

zeta-annotations/                             # NEW: Shared annotations module
├── src/main/java/hu/blackbelt/judo/zeta/annotation/
│   ├── Guard.java                           # Conditional execution (validation + transformation)
│   ├── ExtensionMethod.java                 # Extension method class marker
│   ├── Cached.java                          # Method result caching
│   ├── PreExecution.java                    # Pre-execution hook (renamed from PreValidation)
│   ├── PostExecution.java                   # Post-execution hook (renamed from PostValidation)
│   │
│   │   # Validation-specific annotations
│   ├── ValidationContext.java              # Validation context marker
│   ├── Constraint.java                     # Constraint rule
│   ├── Critique.java                       # Critique rule
│   ├── Satisfies.java                      # Satisfies check
│   │
│   │   # Transformation-specific annotations
│   ├── TransformationContext.java          # Transformation context marker
│   ├── TransformRule.java                  # Transformation rule
│   ├── Lazy.java                           # Lazy evaluation
│   ├── Abstract.java                       # Abstract rule
│   ├── Primary.java                        # Primary rule
│   ├── Greedy.java                         # Greedy type matching
│   └── Extends.java                        # Rule inheritance
└── pom.xml

UPDATED MODULES:
================

validation-core/                              # UPDATED: Depends on common + annotations
├── src/main/java/hu/blackbelt/judo/zeta/validation/
│   ├── core/
│   │   ├── ValidationRegistry.java          # Rule registration
│   │   ├── ValidationExecutor.java          # Parallel execution
│   │   ├── ValidationContext.java           # Execution context
│   │   ├── ValidationResult.java            # Result wrapper
│   │   ├── ValidationRule.java              # Functional interface
│   │   ├── ValidatorDescriptor.java         # Rule metadata
│   │   └── Severity.java                    # Error/warning severity
│   └── util/
│       └── EolStyleCollections.java         # EOL-style collection helpers
└── pom.xml                                   # Depends on: zeta-common, zeta-annotations

transformation-core/                          # NEW: Transformation module
├── src/main/java/hu/blackbelt/judo/zeta/transformation/
│   ├── core/
│   │   ├── TransformationRegistry.java      # Rule registration
│   │   ├── TransformationExecutor.java      # Parallel execution
│   │   ├── TransformationContext.java       # Execution context
│   │   ├── TransformationResult.java        # Result wrapper
│   │   ├── TransformFunction.java           # Functional interface
│   │   ├── TransformRuleDescriptor.java     # Rule metadata
│   │   ├── ElementResolutionCache.java      # Element resolution cache
│   │   └── RuleInheritanceGraph.java        # Dependency graph
│   ├── trace/                                # Transformation trace support
│   │   ├── TransformationTrace.java         # Trace container
│   │   ├── TraceEntry.java                  # Individual trace record
│   │   └── TraceExporter.java               # JSON export functionality
│   └── util/
│       └── EmfModelHelper.java              # EMF utilities (if needed)
└── pom.xml                                   # Depends on: zeta-common, zeta-annotations
```

### Module Dependency Graph

```
                    ┌─────────────────┐
                    │  zeta-common    │
                    │  (utilities)    │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ zeta-annotations│
                    │  (all annots)   │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼────────┐    │    ┌─────────▼─────────┐
     │ validation-core │    │    │transformation-core│
     │  (EVL→Java)     │    │    │   (ETL→Java)      │
     └─────────────────┘    │    └───────────────────┘
                            │
                   (future modules)
```

### Key Components

#### 1. TransformationContext (Execution Context)

Provides transformation execution environment:

```java
public class TransformationContext {
    private final ModelProvider modelProvider;
    private final ResourceSet sourceResourceSet;
    private final ResourceSet targetResourceSet;
    private final ElementResolutionCache resolutionCache;
    private final ExtensionMethodRegistry extensionRegistry;
    private final TransformationTrace trace;  // Automatic trace collection
    private final Map<String, Object> attributes;
    
    // Element resolution (ETL equivalent() operation)
    // Note: These automatically record to trace
    public <T extends EObject> T equivalent(EObject source, Class<T> targetType);
    public <T extends EObject> List<T> equivalents(EObject source, Class<T> targetType);
    
    // Element creation
    public <T extends EObject> T create(Class<T> targetType);
    public <T extends EObject> T createTarget(Class<T> targetType);
    
    // Helper queries
    public <T extends EObject> List<T> getAllSource(Class<T> sourceType);
    public <T extends EObject> List<T> getAllTarget(Class<T> targetType);
    
    // Extension methods
    public <R> R callExtension(String methodName, Object... args);
    
    // Custom attributes
    public void setAttribute(String key, Object value);
    public Object getAttribute(String key);
    
    // Trace access
    public TransformationTrace getTrace();
}
```

#### 2. TransformationRegistry (Rule Discovery)

Discovers and registers transformation rules:

```java
public class TransformationRegistry {
    private final Map<Class<?>, List<TransformRuleDescriptor>> rulesBySourceType;
    private final Map<String, TransformRuleDescriptor> rulesByName;
    private final RuleInheritanceGraph inheritanceGraph;
    
    public void register(Class<?> transformationClass);
    public Collection<TransformRuleDescriptor> getRulesForSource(Class<?> sourceType);
    public TransformRuleDescriptor getRuleByName(String name);
    public void invokePreTransformationHooks(TransformationContext ctx);
    public void invokePostTransformationHooks(TransformationContext ctx);
}
```

#### 3. TransformationExecutor (Execution Engine)

Executes transformation rules in two phases:

```java
public class TransformationExecutor {
    private final TransformationRegistry registry;
    private final TransformationContext context;
    private final boolean parallel;
    
    public TransformationResult transform(
        Collection<? extends EObject> sourceElements
    ) {
        // Phase 1: Execute eager (non-lazy) rules
        for (EObject source : sourceElements) {
            executeEagerRulesFor(source);
        }
        
        // Phase 2: Lazy rules executed on-demand via equivalent()
        // (already handled by element resolution)
        
        return new TransformationResult(context);
    }
    
    private void executeEagerRulesFor(EObject source);
    private void executeLazyRuleFor(EObject source, Class<?> targetType);
}
```

#### 4. ElementResolutionCache

Tracks source → target element mappings for element resolution:

```java
public class ElementResolutionCache {
    // Map: source element → target type → list of target instances
    private final Map<EObject, Map<String, List<EObject>>> cache;
    
    public <T extends EObject> void addMapping(
        EObject source,
        T target,
        boolean isPrimary
    );
    
    public <T extends EObject> T getEquivalent(
        EObject source,
        Class<T> targetType
    );
    
    public <T extends EObject> List<T> getEquivalents(
        EObject source,
        Class<T> targetType
    );
    
    public void clear();
}
```

#### 5. TransformationTrace (Trace Collection)

Automatically collects detailed transformation trace information:

```java
public class TransformationTrace {
    private final List<TraceEntry> entries = new ArrayList<>();
    private final Instant startTime;
    private Instant endTime;
    private String transformationName;
    
    // Automatically called by executor during transformation
    public void recordTransformation(
        EObject source,
        EObject target,
        String ruleName,
        String discriminator  // null if not discriminated
    );
    
    // Query methods
    public List<TraceEntry> getEntriesForSource(EObject source);
    public List<TraceEntry> getEntriesForTarget(EObject target);
    public List<TraceEntry> getEntriesForRule(String ruleName);
    public List<TraceEntry> getAllEntries();
    
    // Statistics
    public int getTotalTransformations();
    public Map<String, Integer> getTransformationsPerRule();
    public Duration getDuration();
    
    // Export
    public void saveToJson(Path filePath) throws IOException;
    public void saveToJson(OutputStream outputStream) throws IOException;
    public String toJson();
}

/**
 * Individual trace record capturing a single transformation.
 */
public class TraceEntry {
    private final String sourceId;           // XMI ID of source element
    private final String sourceType;         // EClass name of source
    private final String sourceName;         // Name attribute if available
    private final String targetId;           // XMI ID of target element
    private final String targetType;         // EClass name of target
    private final String targetName;         // Name attribute if available
    private final String ruleName;           // Transformation rule name
    private final String discriminator;      // Discriminator if equivalentDiscriminated used
    private final Instant timestamp;         // When transformation occurred
    
    // Getters...
}

/**
 * JSON export utility for transformation traces.
 */
public class TraceExporter {
    
    /**
     * Export trace to JSON format.
     * 
     * Example output:
     * {
     *   "transformationName": "ESM2PSM",
     *   "startTime": "2025-12-05T10:30:00Z",
     *   "endTime": "2025-12-05T10:30:05Z",
     *   "durationMs": 5000,
     *   "totalTransformations": 1250,
     *   "transformationsPerRule": {
     *     "EntityType2Table": 45,
     *     "Attribute2Column": 380,
     *     ...
     *   },
     *   "entries": [
     *     {
     *       "sourceId": "entity_Customer",
     *       "sourceType": "EntityType",
     *       "sourceName": "Customer",
     *       "targetId": "table_Customer",
     *       "targetType": "Table",
     *       "targetName": "Customer",
     *       "ruleName": "EntityType2Table",
     *       "discriminator": null,
     *       "timestamp": "2025-12-05T10:30:01Z"
     *     },
     *     ...
     *   ]
     * }
     */
    public static String toJson(TransformationTrace trace);
    public static void saveToFile(TransformationTrace trace, Path filePath) throws IOException;
}
```

### Execution Flow

1. **Initialization**:
   - Register transformation context classes
   - Build rule inheritance graph
   - Initialize element resolution cache
   - **Initialize transformation trace** (start time, transformation name)

2. **Pre-Transformation**:
   - Invoke all `@PreTransformation` hooks
   - Prepare source and target ResourceSets

3. **Eager Transformation Phase**:
   - For each source element:
     - Find applicable non-lazy rules
     - Check guards
     - Execute rule (create targets, populate properties)
     - Store source→target mapping in cache
     - **Record transformation in trace** (source, target, rule name, timestamp)
     - Handle rule inheritance

4. **Lazy Transformation Phase**:
   - Triggered by `equivalent()` calls
   - Execute lazy rules on-demand
   - Cache results for subsequent calls
   - **Record lazy transformations in trace**

5. **Post-Transformation**:
   - Invoke all `@PostTransformation` hooks
   - **Finalize trace** (end time, statistics)
   - Optionally persist target models
   - **Optionally export trace to JSON**

### Parallel Execution Strategy

Similar to `validation-core`:
- Threshold: 5000 source elements
- Chunk size: 100 elements per work unit
- ForkJoinPool with work-stealing
- Thread-safe element resolution cache

### Rule Inheritance

Rules can extend other rules:

```java
@TransformRule(name = "BaseEntity2Table")
@Abstract
public TransformFunction<EntityType, Table> baseEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(source.getName());
        return table;
    };
}

@TransformRule(name = "ConcreteEntity2Table")
@Extends("BaseEntity2Table")
public TransformFunction<EntityType, Table> concreteEntity2Table() {
    return (source, ctx) -> {
        Table table = ctx.executeParentRule("BaseEntity2Table", source);
        // Additional transformations
        table.setSchema(ctx.equivalent(source.getNamespace(), Schema.class));
        return table;
    };
}
```

## Benefits

1. **Type Safety**: Compile-time checking vs runtime script errors
2. **IDE Support**: Full IntelliJ/Eclipse support with autocomplete and refactoring
3. **Performance**: ~10-20% faster execution (no script interpretation)
4. **Debugging**: Standard Java debugging tools
5. **Testing**: Standard JUnit tests with mocking frameworks
6. **Maintenance**: Easier refactoring and code navigation
7. **Reuse**: Leverage existing Java libraries and patterns
8. **Consistency**: Unified approach with `validation-core`
9. **Deployment**: Fewer runtime dependencies
10. **Documentation**: JavaDoc and standard documentation tools

## Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Incomplete ETL feature coverage | High | Medium | Phased implementation; start with core features; gather feedback |
| Complex element resolution logic | Medium | Medium | Reuse proven patterns; comprehensive tests; clear documentation |
| Performance issues with large models | Medium | Low | Parallel execution; caching; profiling and optimization |
| Migration effort for existing ETL | High | High | Provide migration guide; example transformations; gradual migration path |
| Learning curve for developers | Medium | Medium | Clear documentation; examples; training sessions |
| Inheritance graph complexity | Medium | Low | Use topological sort; detect cycles; limit inheritance depth |

## Dependencies

### Technical Dependencies
- EMF ECore (already present)
- SLF4J for logging (already present)
- JUnit 5 for testing (already present)
- Lombok for builders (already present)

### Module Dependencies

**zeta-common** (new module):
- EMF ECore

**zeta-annotations** (new module):
- `zeta-common`
- EMF ECore (for EObject reference in annotations)

**validation-core** (refactored):
- `zeta-common`
- `zeta-annotations`

**transformation-core** (new module):
- `zeta-common`
- `zeta-annotations`

### External Dependencies
- None - all modules are self-contained within judo-zeta

## New Shared Modules

This change introduces two new shared modules to enable code reuse between validation-core and transformation-core.

### zeta-common Module

Contains shared utility classes extracted from validation-core:

| Class | Package | Purpose |
|-------|---------|---------|
| `ModelProvider` | `hu.blackbelt.judo.zeta.common` | Model traversal interface (`getAllContents`, `getName`, `getTypeName`) |
| `CacheKey` | `hu.blackbelt.judo.zeta.common` | Immutable cache key for method results |
| `CacheKeyBuilder` | `hu.blackbelt.judo.zeta.common` | Builds cache keys (EObject→XMI ID, primitives, collections, maps) |
| `ExtensionMethodRegistry` | `hu.blackbelt.judo.zeta.common` | Registers and invokes extension methods with caching |
| `ExtensionMethodDescriptor` | `hu.blackbelt.judo.zeta.common` | Metadata descriptor for extension methods |

### zeta-annotations Module

Contains all annotations for both validation and transformation:

**Shared Annotations** (used by both validation-core and transformation-core):

| Annotation | Package | Purpose |
|------------|---------|---------|
| `@Guard` | `hu.blackbelt.judo.zeta.annotation` | Conditional execution |
| `@ExtensionMethod` | `hu.blackbelt.judo.zeta.annotation` | Extension method class marker |
| `@Cached` | `hu.blackbelt.judo.zeta.annotation` | Method result caching |
| `@PreExecution` | `hu.blackbelt.judo.zeta.annotation` | Pre-execution hook (generic) |
| `@PostExecution` | `hu.blackbelt.judo.zeta.annotation` | Post-execution hook (generic) |

**Validation-Specific Annotations**:

| Annotation | Package | Purpose |
|------------|---------|---------|
| `@ValidationContext` | `hu.blackbelt.judo.zeta.annotation` | Validation context class marker |
| `@Constraint` | `hu.blackbelt.judo.zeta.annotation` | Constraint rule (error) |
| `@Critique` | `hu.blackbelt.judo.zeta.annotation` | Critique rule (warning) |
| `@Satisfies` | `hu.blackbelt.judo.zeta.annotation` | Satisfies check |

**Transformation-Specific Annotations**:

| Annotation | Package | Purpose |
|------------|---------|---------|
| `@TransformationContext` | `hu.blackbelt.judo.zeta.annotation` | Transformation context class marker |
| `@TransformRule` | `hu.blackbelt.judo.zeta.annotation` | Transformation rule |
| `@Lazy` | `hu.blackbelt.judo.zeta.annotation` | Lazy evaluation |
| `@Abstract` | `hu.blackbelt.judo.zeta.annotation` | Abstract rule (template) |
| `@Primary` | `hu.blackbelt.judo.zeta.annotation` | Primary rule for element resolution |
| `@Greedy` | `hu.blackbelt.judo.zeta.annotation` | Greedy type matching (kind-of semantics) |
| `@Extends` | `hu.blackbelt.judo.zeta.annotation` | Rule inheritance |

### Migration Impact on validation-core

The validation-core module will be refactored to:

1. **Remove** classes moved to `zeta-common`
2. **Remove** annotations moved to `zeta-annotations`
3. **Add dependencies** on `zeta-common` and `zeta-annotations`
4. **Update imports** in all classes
5. **Rename** `@PreValidation` → `@PreExecution`, `@PostValidation` → `@PostExecution` (or keep aliases for backward compatibility)

### Benefits of Modular Architecture

1. **Clean separation** - Annotations, utilities, and core logic in separate modules
2. **No circular dependencies** - Clear dependency hierarchy
3. **Independent versioning** - Annotations can be stable while implementations evolve
4. **Lightweight consumers** - Projects only need annotations module for compile-time
5. **Future extensibility** - Easy to add more Epsilon-replacement modules (EGL, EOL, etc.)
6. **Consistent API** - Same annotations used across all modules

### Maven POM Dependencies

**zeta-annotations pom.xml**:
```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>zeta-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

**validation-core pom.xml**:
```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>zeta-common</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>zeta-annotations</artifactId>
    <version>${project.version}</version>
</dependency>
```

**transformation-core pom.xml**:
```xml
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>zeta-common</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>hu.blackbelt.judo.zeta</groupId>
    <artifactId>zeta-annotations</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Timeline Estimate

### Phase 1: Create zeta-common Module (4-6 hours)
- Create module structure and Maven POM
- Move `ModelProvider`, `CacheKey`, `CacheKeyBuilder` from validation-core
- Move `ExtensionMethodRegistry`, `ExtensionMethodDescriptor` from validation-core
- OSGi bundle configuration
- Unit tests for moved classes

### Phase 2: Create zeta-annotations Module (4-6 hours)
- Create module structure and Maven POM
- Move shared annotations: `@Guard`, `@ExtensionMethod`, `@Cached`
- Move validation annotations: `@ValidationContext`, `@Constraint`, `@Critique`, `@Satisfies`
- Rename `@PreValidation` → `@PreExecution`, `@PostValidation` → `@PostExecution`
- Create transformation annotations: `@TransformationContext`, `@TransformRule`, `@Lazy`, `@Abstract`, `@Primary`, `@Greedy`, `@Extends`
- JavaDoc documentation

### Phase 3: Refactor validation-core (3-4 hours)
- Update dependencies to use zeta-common and zeta-annotations
- Update all imports
- Remove moved classes and annotations
- Verify all tests pass
- Update OSGi exports/imports

### Phase 4: Create transformation-core Module (3-4 hours)
- Create module structure and Maven POM
- Dependencies on zeta-common and zeta-annotations
- OSGi bundle configuration
- Basic package structure

### Phase 5: Transformation Execution Engine (10-12 hours)
- TransformationContext implementation (uses ModelProvider from zeta-common)
- TransformationRegistry implementation
- TransformationExecutor implementation
- Basic element resolution
- Uses ExtensionMethodRegistry from zeta-common

### Phase 6: Transformation Trace (4-6 hours)
- TransformationTrace implementation (trace container)
- TraceEntry implementation (individual records)
- TraceExporter implementation (JSON export)
- Automatic trace recording in executor
- Query methods (by source, target, rule)
- Statistics collection (counts, duration)

### Phase 7: Lazy Evaluation and Inheritance (8-10 hours)
- Lazy rule execution
- Rule inheritance graph
- @Extends support
- Inheritance resolution

### Phase 8: Pre/Post Hooks and Guards (3-4 hours)
- Guard evaluation (uses @Guard from zeta-annotations)
- Pre/post execution hooks (uses @PreExecution/@PostExecution from zeta-annotations)

### Phase 9: Parallel Execution (4-6 hours)
- Parallel execution strategy
- Thread-safe cache implementation (uses CacheKey/CacheKeyBuilder from zeta-common)
- Thread-safe trace recording
- Performance optimization

### Phase 10: Documentation and Testing (4-6 hours)
- Comprehensive test suite
- Migration guide from ETL
- Usage examples
- API documentation

### Phase 11: Advanced ETL Features (10-20 hours)

#### Greedy Type Matching (4-6 hours)
- Implement type hierarchy checking using EClass.isSuperTypeOf()
- Update registry to index rules by type hierarchy
- Add appliesTo() logic for kind-of semantics
- Test with inheritance hierarchies

#### Abstract Rule Execution (3-5 hours)
- Prevent direct execution of @Abstract rules
- Build dependency graph for multi-level inheritance
- Implement parent-before-child execution order
- Test inheritance chains

#### Discriminated Equivalence (3-5 hours)
- Implement 3-level discriminated cache (element → rule → discriminator → target)
- Add equivalentDiscriminated() to TransformationContext
- Implement cloning logic with discriminated IDs
- Add ID naming rules: baseId/(discriminator/value)

#### Testing Advanced Features (2-4 hours)
- Test greedy matching with complex hierarchies
- Test abstract rule inheritance chains
- Test discriminated equivalence with multiple discriminators
- Integration tests for all advanced features

**Total Estimated Effort**: 57-84 hours (approximately 1.5-2 weeks)

**Note**: Timeline includes creation of two new shared modules (zeta-common, zeta-annotations), refactoring of validation-core, and transformation trace functionality with JSON export. This upfront investment enables clean code sharing and future extensibility.

## Success Criteria

### Shared Modules
- [ ] `zeta-common` module builds successfully
- [ ] `zeta-annotations` module builds successfully
- [ ] All shared classes moved and working (ModelProvider, CacheKey, CacheKeyBuilder, ExtensionMethodRegistry, ExtensionMethodDescriptor)
- [ ] All annotations defined and documented in zeta-annotations

### validation-core Refactoring
- [ ] validation-core depends on zeta-common and zeta-annotations
- [ ] All validation-core tests pass after refactoring
- [ ] No duplicate classes between modules

### transformation-core
- [ ] `transformation-core` module builds successfully
- [ ] TransformationRegistry discovers and registers rules
- [ ] TransformationExecutor executes eager rules
- [ ] Lazy rules execute on-demand via equivalent()
- [ ] Element resolution cache works correctly
- [ ] Rule inheritance works with @Extends
- [ ] Guards prevent rule execution when conditions fail
- [ ] Pre/post execution hooks execute correctly
- [ ] Parallel execution works for large models (>5000 elements)
- [ ] Extension methods can be registered and invoked
- [ ] Comprehensive test coverage (>80%)
- [ ] Documentation includes migration guide from ETL

### Transformation Trace
- [ ] TransformationTrace automatically collects source→target mappings
- [ ] TraceEntry records source ID, target ID, rule name, discriminator, timestamp
- [ ] Query methods work (getEntriesForSource, getEntriesForTarget, getEntriesForRule)
- [ ] Statistics available (total count, per-rule counts, duration)
- [ ] JSON export via saveToJson(Path) works correctly
- [ ] JSON export via toJson() returns valid JSON string
- [ ] Thread-safe trace recording in parallel execution

### Build & Deployment
- [ ] All OSGi bundles deploy successfully
- [ ] P2 repository includes all new modules (zeta-common, zeta-annotations, transformation-core)

## Alternatives Considered

### 1. Continue Using ETL Scripts
**Pros**: No development effort; proven solution  
**Cons**: Runtime dependency; no type safety; poor IDE support  
**Decision**: Rejected - doesn't solve core problems

### 2. Use Existing Java Transformation Frameworks (ATL/Java, QVT)
**Pros**: Mature solutions; standardized  
**Cons**: Heavy dependencies; complex setup; don't match our architecture  
**Decision**: Rejected - too heavyweight for our needs

### 3. Create ETL-to-Java Code Generator
**Pros**: Automatic migration; maintains ETL syntax  
**Cons**: Complex to implement; still has limitations; maintenance burden  
**Decision**: Rejected - prefer clean Java API

### 4. Extend validation-core for Transformations
**Pros**: Single module; code reuse  
**Cons**: Conflates concerns; different semantics; harder to maintain  
**Decision**: Rejected - separate concerns better

## Migration Path

For existing judo-tatami ETL scripts:

1. **Identify ETL Rules**: Catalog all transformation rules
2. **Create Transformation Classes**: One Java class per ETL module
3. **Convert Rules**: Translate ETL rules to `@TransformRule` methods
4. **Test Incrementally**: Verify each transformation produces same output
5. **Update Build**: Remove ETL dependencies; add transformation-core
6. **Deploy**: Test in staging; rollout to production

**Migration Support**:
- Side-by-side comparison guide (ETL vs Java)
- Common patterns documentation
- Example transformations
- Testing strategies

## Related Changes

None currently - this is a standalone new module.

## Future Enhancements

Potential future additions (out of scope for initial release):

1. **Model Merging**: Support merging multiple source models
2. **Incremental Transformations**: Re-execute only changed elements
3. **Bidirectional Transformations**: Support round-trip transformations
4. **Validation Integration**: Validate targets using validation-core
5. **Transaction Support**: Wrap transformations in transactions
6. **Streaming Transformations**: Handle very large models with streaming
7. **Transformation Composition**: Chain multiple transformations
8. **IDE Tooling**: Eclipse/IntelliJ plugins for transformation development

## References

- Epsilon Transformation Language (ETL): https://eclipse.dev/epsilon/doc/etl/
- validation-core module: `/Users/robson/Project/judo-ng/runtime/judo-zeta/validation-core/`
- ETL Language Specification: [Model Transformation (ETL) - Epsilon](https://eclipse.dev/epsilon/doc/etl/)
- judo-tatami projects (reference ETL usage)

## Appendix: Annotation Comparison

| ETL Feature | Java Annotation | Example |
|-------------|----------------|---------|
| `transform e : Source` | `@TransformationContext(source=Source.class)` | Context declaration |
| `to t : Target` | `@TransformationContext(target=Target.class)` | Target type |
| `rule RuleName` | `@TransformRule(name="RuleName")` | Rule definition |
| `@lazy` | `@Lazy` | Lazy evaluation |
| `@abstract` | `@Abstract` | Abstract rule |
| `@primary` | `@Primary` | Primary rule |
| `@greedy` | `@Greedy` | Type matching |
| `guard : expr` | `@Guard(method="guardMethod")` | Conditional |
| `extends Parent` | `@Extends("ParentRule")` | Inheritance |
| `pre { ... }` | `@PreTransformation` | Pre-hook |
| `post { ... }` | `@PostTransformation` | Post-hook |
| `operation helper()` | `@ExtensionMethod` | Helper method |

---

**Sources:**
- [Model Transformation (ETL) - Epsilon](https://eclipse.dev/epsilon/doc/etl/)
- [Object Language (EOL) - Epsilon](https://eclipse.dev/epsilon/doc/eol/)
- [Overview - Epsilon](https://eclipse.dev/epsilon/doc/)
