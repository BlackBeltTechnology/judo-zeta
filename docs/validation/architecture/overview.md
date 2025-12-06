# System Architecture Overview

**Navigation:** [Documentation Home](../README.md) > [Architecture](./README.md) > Overview

---

## Table of Contents

- [Introduction](#introduction)
- [Component Architecture](#component-architecture)
- [Core Components](#core-components)
- [Key Interfaces and Classes](#key-interfaces-and-classes)
- [Package Structure](#package-structure)
- [Module Dependencies](#module-dependencies)
- [Design Principles](#design-principles)
- [Core Concepts and Relationships](#core-concepts-and-relationships)
- [Execution Flow](#execution-flow)
- [Thread Safety and Concurrency](#thread-safety-and-concurrency)
- [Extension Points](#extension-points)

---

## Introduction

Judo Zeta is a **lightweight, standalone validation framework for EMF metamodels**. It provides a modern, annotation-based alternative to Epsilon Validation Language (EVL) with parallel execution, dependency resolution, and comprehensive caching support.

The framework is designed with the following goals:

- **Simplicity:** Write validation rules as plain Java methods with annotations
- **Performance:** Automatic parallel execution for large models (5000+ elements)
- **Type Safety:** Compile-time validation rule checking via Java's type system
- **Flexibility:** Support for guards, dependencies, caching, and extension methods
- **Modularity:** Clean separation between core framework and metamodel-specific implementations

---

## Component Architecture

The following diagram illustrates the high-level architecture and component relationships:

```mermaid
graph TB
    subgraph "Client Layer"
        CLIENT[Validation Client]
        MODEL[EMF Model]
    end

    subgraph "Framework Layer"
        EXECUTOR[ValidationExecutor]
        REGISTRY[ValidationRegistry]
        CONTEXT[ValidationContext]
    end

    subgraph "Rule Layer"
        VALIDATOR[Validator Classes<br/>@ValidationContext]
        DESCRIPTOR[ValidatorDescriptor]
        RULE[ValidationRule]
        GUARD[Guard]
    end

    subgraph "Extension Layer"
        EXT_REGISTRY[ExtensionMethodRegistry]
        EXT_DESCRIPTOR[ExtensionMethodDescriptor]
    end

    subgraph "Support Layer"
        RESULT[ValidationResult]
        CACHE[CacheKey/Cache]
        PROVIDER[ModelProvider]
    end

    CLIENT -->|1. Register Rules| REGISTRY
    CLIENT -->|2. Execute Validation| EXECUTOR
    EXECUTOR -->|Uses| REGISTRY
    EXECUTOR -->|Creates| CONTEXT
    EXECUTOR -->|Returns| RESULT

    REGISTRY -->|Scans & Registers| VALIDATOR
    REGISTRY -->|Creates| DESCRIPTOR
    DESCRIPTOR -->|Wraps| RULE
    DESCRIPTOR -->|May Have| GUARD

    CONTEXT -->|Accesses| PROVIDER
    CONTEXT -->|Invokes| EXT_REGISTRY
    CONTEXT -->|Uses| CACHE
    CONTEXT -->|Checks Dependencies| REGISTRY

    RULE -->|Validates| MODEL
    RULE -->|Returns| RESULT

    EXT_REGISTRY -->|Manages| EXT_DESCRIPTOR

    style EXECUTOR fill:#e1f5ff
    style REGISTRY fill:#e1f5ff
    style CONTEXT fill:#e1f5ff
    style VALIDATOR fill:#fff4e1
    style RESULT fill:#f0f0f0
```

### Component Interaction Flow

```mermaid
sequenceDiagram
    participant Client
    participant ValidationRegistry
    participant ValidationExecutor
    participant ValidationContext
    participant ValidatorDescriptor
    participant ValidationRule
    participant EObject

    Client->>ValidationRegistry: register(ValidatorClass)
    ValidationRegistry->>ValidationRegistry: Scan annotations
    ValidationRegistry->>ValidatorDescriptor: Create descriptors
    
    Client->>ValidationExecutor: validate(elements)
    ValidationExecutor->>ValidationContext: Invoke pre-hooks
    
    loop For each element
        ValidationExecutor->>ValidationRegistry: getValidatorsFor(type)
        ValidationRegistry-->>ValidationExecutor: List<ValidatorDescriptor>
        
        loop For each validator
            ValidationExecutor->>ValidatorDescriptor: appliesTo(element)
            ValidatorDescriptor-->>ValidationExecutor: true/false
            
            alt Applies to element
                ValidationExecutor->>ValidatorDescriptor: validate(element, context)
                ValidatorDescriptor->>ValidationContext: Check @Satisfies dependencies
                ValidationContext-->>ValidatorDescriptor: Dependencies satisfied
                ValidatorDescriptor->>ValidatorDescriptor: Evaluate guard
                ValidatorDescriptor->>ValidationRule: validate(element, context)
                ValidationRule->>EObject: Check constraints
                ValidationRule-->>ValidatorDescriptor: ValidationResult
                ValidatorDescriptor-->>ValidationExecutor: ValidationResult
            end
        end
    end
    
    ValidationExecutor->>ValidationContext: Invoke post-hooks
    ValidationExecutor-->>Client: List<ValidationResult>
```

---

## Core Components

### 1. ValidationRegistry

**Purpose:** Central registry for discovering, registering, and managing validation rules.

**Responsibilities:**
- Scan classes annotated with `@ValidationContext`
- Register constraint and critique methods as validation rules
- Create and manage `ValidatorDescriptor` instances
- Provide validators by element type (including type hierarchy)
- Manage pre/post-validation lifecycle hooks

**Key Features:**
- Type-based rule lookup with inheritance support
- Interface-aware validation (checks all implemented interfaces)
- Named validator lookup for dependency resolution
- Thread-safe registration

### 2. ValidationExecutor

**Purpose:** Parallel execution engine for running validation rules against model elements.

**Responsibilities:**
- Orchestrate validation execution (sequential or parallel)
- Determine optimal execution strategy based on element count
- Partition work into chunks for parallel processing
- Invoke lifecycle hooks (pre/post-validation)
- Filter and return only failed validation results

**Key Features:**
- Automatic parallel execution for 5000+ elements
- Work-stealing thread pool (ForkJoinPool)
- Configurable chunk size (default: 100 elements per chunk)
- Validator cache pre-computation for performance
- Lazy executor initialization

### 3. ValidationContext

**Purpose:** Runtime context providing access to model, cache, and extension methods during validation.

**Responsibilities:**
- Track current element being validated (thread-local)
- Provide model traversal via `ModelProvider`
- Manage `@Satisfies` dependency cache
- Invoke extension methods with caching
- Store custom attributes for hooks

**Key Features:**
- Thread-safe current element tracking (ThreadLocal)
- Circular dependency detection for `@Satisfies`
- Three-state cache (EVALUATING, SATISFIED, NOT_SATISFIED)
- Extension method invocation with result caching
- Custom attribute storage for inter-hook communication

### 4. ValidatorDescriptor

**Purpose:** Metadata wrapper for a single validation rule.

**Responsibilities:**
- Store rule metadata (name, message, severity, context type)
- Cache `ValidationRule` and `Guard` instances
- Check if rule applies to an element type
- Validate elements with guard and dependency checking
- Interpolate message placeholders

**Key Features:**
- Lazy initialization of rule and guard instances
- Reflection-based method invocation
- Guard evaluation before rule execution
- `@Satisfies` dependency enforcement
- Message interpolation (`{element.name}`)

### 5. ValidationRule (Functional Interface)

**Purpose:** Functional interface representing a single validation rule.

**Signature:**
```java
@FunctionalInterface
public interface ValidationRule {
    ValidationResult validate(EObject element, ValidationContext ctx);
}
```

**Usage:**
- Enables lambda-based rule definition
- Receives element and context
- Returns `ValidationResult` (pass/fail)
- Compose-able via functional programming

### 6. ValidationResult (Immutable Value Object)

**Purpose:** Immutable result representing validation outcome.

**Properties:**
- `boolean passed` - Whether validation passed
- `String constraintName` - Name of the constraint
- `String message` - Error/warning message
- `Severity severity` - ERROR or WARNING
- `EObject context` - Element that failed validation

**Factory Methods:**
- `ValidationResult.pass()` - Create passing result
- `ValidationResult.fail(message)` - Create error result
- `ValidationResult.warn(message)` - Create warning result
- `ValidationResult.fail(name, message, severity, element)` - Full metadata

---

## Key Interfaces and Classes

### Interface Hierarchy

```mermaid
classDiagram
    class ModelProvider {
        <<interface>>
        +getAllContents(ResourceSet, Class) Collection
        +getName(EObject) String
        +getTypeName(EObject) String
    }

    class ValidationRule {
        <<interface>>
        +validate(EObject, ValidationContext) ValidationResult
        +builder()$ ValidationRuleBuilder
    }

    class Guard {
        <<interface>>
        +evaluate(EObject, ValidationContext) boolean
    }

    class ValidationRegistry {
        -Map~Class, List~ValidatorDescriptor~~ validators
        -Map~String, ValidatorDescriptor~ descriptorsByName
        -List~Method~ preValidationHooks
        -List~Method~ postValidationHooks
        +register(Class) void
        +getValidatorsFor(Class) Collection
        +getValidatorByName(String) ValidatorDescriptor
        +invokePreValidationHooks(ValidationContext) void
        +invokePostValidationHooks(ValidationContext) void
    }

    class ValidationExecutor {
        -ValidationRegistry registry
        -ValidationContext context
        -boolean parallel
        -ExecutorService executor
        +validate(Collection) List~ValidationResult~
        -validateSequential(Collection) List~ValidationResult~
        -validateParallel(Collection) List~ValidationResult~
        -validateChunk(List, Map) List~ValidationResult~
        +shutdown() void
    }

    class ValidationContext {
        -ModelProvider modelProvider
        -ResourceSet resourceSet
        -ExtensionMethodRegistry extensionRegistry
        -Map~CacheKey, SatisfiesState~ satisfiesCache
        -ThreadLocal~EObject~ currentElement
        +satisfies(String) boolean
        +satisfies(EObject, String) boolean
        +allSatisfy(Collection, String) boolean
        +getAllInstances(Class) Collection
        +call(EObject, String, Object...) Object
        +setAttribute(String, Object) void
        +getAttribute(String) Object
    }

    class ValidatorDescriptor {
        -Object instance
        -Method ruleMethod
        -String name
        -String message
        -Severity severity
        -Class contextType
        -Method guardMethod
        -List~String~ satisfiesDependencies
        +getRule() ValidationRule
        +getGuard() Guard
        +appliesTo(EObject) boolean
        +validate(EObject, ValidationContext) ValidationResult
    }

    class ValidationResult {
        <<immutable>>
        -boolean passed
        -String constraintName
        -String message
        -Severity severity
        -EObject context
        +pass()$ ValidationResult
        +fail(String)$ ValidationResult
        +warn(String)$ ValidationResult
        +isPassed() boolean
        +isFailed() boolean
    }

    class ExtensionMethodRegistry {
        -Map~Class, List~ExtensionMethodDescriptor~~ extensions
        -Map~CacheKey, Object~ cache
        +register(Class) void
        +invoke(EObject, String, Object...) Object
        +clearCache() void
    }

    class Severity {
        <<enumeration>>
        ERROR
        WARNING
    }

    ValidationExecutor --> ValidationRegistry
    ValidationExecutor --> ValidationContext
    ValidationRegistry --> ValidatorDescriptor
    ValidatorDescriptor --> ValidationRule
    ValidatorDescriptor --> Guard
    ValidatorDescriptor --> Severity
    ValidationRule --> ValidationResult
    ValidationContext --> ModelProvider
    ValidationContext --> ExtensionMethodRegistry
    ValidationResult --> Severity
```

### Annotation System

```mermaid
classDiagram
    class ValidationContext {
        <<annotation>>
        +Class~EObject~ value
    }

    class Constraint {
        <<annotation>>
        +String name
        +String message
    }

    class Critique {
        <<annotation>>
        +String name
        +String message
    }

    class Guard {
        <<annotation>>
        +String method
    }

    class Satisfies {
        <<annotation>>
        +String[] constraints
    }

    class Cached {
        <<annotation>>
    }

    class ExtensionMethod {
        <<annotation>>
        +Class~EObject~ elementType
    }

    class PreValidation {
        <<annotation>>
    }

    class PostValidation {
        <<annotation>>
    }

    note for ValidationContext "Marks class as containing\nvalidation rules for a type"
    note for Constraint "Defines ERROR-level rule"
    note for Critique "Defines WARNING-level rule"
    note for Guard "Conditional execution guard"
    note for Satisfies "Declares rule dependencies"
    note for Cached "Enables result caching"
    note for ExtensionMethod "Registers helper method"
    note for PreValidation "Pre-validation lifecycle hook"
    note for PostValidation "Post-validation lifecycle hook"
```

---

## Package Structure

```
hu.blackbelt.judo.zeta.validation
├── ModelProvider.java                      # Core metamodel integration interface
│
├── annotation/                             # Annotation definitions
│   ├── Constraint.java                    # @Constraint - Error-level rule
│   ├── Critique.java                      # @Critique - Warning-level rule
│   ├── Guard.java                         # @Guard - Conditional execution
│   ├── Satisfies.java                     # @Satisfies - Rule dependencies
│   ├── Cached.java                        # @Cached - Result caching
│   ├── ExtensionMethod.java               # @ExtensionMethod - Helper methods
│   ├── PreValidation.java                 # @PreValidation - Pre-hook
│   ├── PostValidation.java                # @PostValidation - Post-hook
│   └── ValidationContext.java             # @ValidationContext - Rule container
│
├── core/                                   # Core validation engine
│   ├── ValidationRegistry.java            # Rule registration and discovery
│   ├── ValidationExecutor.java            # Parallel execution engine
│   ├── ValidationContext.java             # Runtime execution context
│   ├── ValidationResult.java              # Immutable result value object
│   ├── ValidationRule.java                # Functional interface for rules
│   ├── ValidationRuleBuilder.java         # Fluent builder API
│   ├── ValidatorDescriptor.java           # Rule metadata wrapper
│   ├── ExtensionMethodRegistry.java       # Extension method management
│   ├── ExtensionMethodDescriptor.java     # Extension method metadata
│   ├── CacheKey.java                      # Immutable cache key
│   ├── CacheKeyBuilder.java               # Cache key construction
│   ├── Guard.java                         # Guard functional interface
│   └── Severity.java                      # ERROR/WARNING enumeration
│
└── util/                                   # Utility classes
    └── EolStyleCollections.java           # Epsilon-like collection utilities
```

### Package Responsibilities

| Package | Responsibility | Public API |
|---------|---------------|------------|
| `validation` | Core integration interface | `ModelProvider` |
| `validation.annotation` | Validation DSL annotations | All 8 annotations |
| `validation.core` | Validation engine implementation | All core classes |
| `validation.util` | Utility classes | `EolStyleCollections` |

---

## Module Dependencies

The project consists of three modules with the following dependency structure:

```mermaid
graph TD
    subgraph "Production Modules"
        CORE[validation-core<br/>OSGi Bundle]
        P2[p2<br/>P2 Repository]
    end

    subgraph "Test Modules"
        ITEST[osgi-itest<br/>Integration Tests]
    end

    subgraph "External Dependencies"
        EMF[Eclipse EMF<br/>2.38.0 / 2.41.0]
        OSGI[OSGi Core<br/>7.0.0]
        SLF4J[SLF4J<br/>2.0.16]
        LOMBOK[Lombok<br/>1.18.34]
        KARAF[Apache Karaf<br/>4.4.7]
        PAXEXAM[Pax Exam<br/>4.13.5]
    end

    P2 -->|Packages| CORE
    ITEST -->|Tests| CORE
    ITEST -->|Uses| KARAF
    ITEST -->|Uses| PAXEXAM

    CORE -->|Depends| EMF
    CORE -->|Depends| OSGI
    CORE -->|Depends| SLF4J
    CORE -->|Depends| LOMBOK

    style CORE fill:#e1f5ff
    style P2 fill:#f0f0f0
    style ITEST fill:#fff4e1
```

### Module Details

**validation-core** (`hu.blackbelt.judo.zeta.validation-core`)
- Type: OSGi Bundle
- Purpose: Reusable validation framework
- Key Dependencies: EMF Ecore, SLF4J, Lombok
- Exports: All public packages

**p2** (`hu.blackbelt.judo.zeta.p2`)
- Type: P2 Repository
- Purpose: Eclipse P2 update site packaging
- Packages: validation-core as Eclipse feature

**osgi-itest** (`hu.blackbelt.judo.zeta.osgi.itest`)
- Type: Integration Test Suite
- Purpose: OSGi/Karaf integration testing
- Framework: Pax Exam 4.13.5
- Runtime: Apache Karaf 4.4.7

### External Dependencies

| Dependency | Version | Scope | Purpose |
|------------|---------|-------|---------|
| Eclipse EMF Ecore | 2.38.0 / 2.41.0 | compile | Metamodel foundation |
| OSGi Core | 7.0.0 | provided | OSGi framework APIs |
| SLF4J API | 2.0.16 | compile | Logging facade |
| Logback | 1.5.12 | test | Logging implementation |
| Lombok | 1.18.34 | provided | Annotation processing |
| JUnit Jupiter | 5.11.3 | test | Unit testing |
| Apache Karaf | 4.4.7 | test | OSGi runtime |
| Pax Exam | 4.13.5 | test | OSGi integration testing |

---

## Design Principles

### 1. Annotation-Driven Configuration

The framework uses annotations as a declarative DSL for defining validation rules, eliminating the need for external configuration files or domain-specific languages like EVL.

**Benefits:**
- Compile-time validation of rule definitions
- IDE support (autocomplete, refactoring, navigation)
- Type-safe rule implementation
- No external file parsing or interpretation

**Example:**
```java
@ValidationContext(EntityType.class)
public class EntityTypeValidations {
    @Constraint(name = "EntityMustHaveName", message = "Entity must have name")
    public ValidationRule entityMustHaveName() {
        return (element, ctx) -> { /* ... */ };
    }
}
```

### 2. Functional Programming

The framework embraces functional programming principles through the use of functional interfaces and lambda expressions.

**Key Functional Interfaces:**
- `ValidationRule` - Rule logic as function
- `Guard` - Conditional predicate
- Immutable results and cache keys

**Benefits:**
- Concise rule definitions
- Composable validation logic
- Stateless rule execution
- Easy testing and reasoning

**Example:**
```java
ValidationRule rule = (element, ctx) -> {
    return condition(element) 
        ? ValidationResult.pass() 
        : ValidationResult.fail("Error");
};
```

### 3. Immutability

Core value objects are immutable to ensure thread safety and prevent unexpected side effects.

**Immutable Classes:**
- `ValidationResult` - Validation outcome
- `CacheKey` - Cache key for results
- All annotation metadata

**Benefits:**
- Thread-safe sharing across parallel workers
- No defensive copying needed
- Clear value semantics
- Safer caching strategies

### 4. Separation of Concerns

The architecture cleanly separates different concerns:

| Concern | Component | Responsibility |
|---------|-----------|---------------|
| Rule Discovery | `ValidationRegistry` | Scan and register rules |
| Rule Execution | `ValidationExecutor` | Execute rules in parallel |
| Runtime Context | `ValidationContext` | Provide access to model and cache |
| Rule Metadata | `ValidatorDescriptor` | Store rule metadata |
| Rule Logic | `ValidationRule` | Implement validation logic |
| Model Access | `ModelProvider` | Abstract model traversal |

### 5. Type Safety

The framework leverages Java's type system for compile-time safety:

- Generic type parameters preserve element types
- Reflection used only for discovery, not execution
- Metamodel classes are strongly typed EMF classes
- No string-based element access in rules

### 6. Performance by Default

Performance optimizations are built-in and automatic:

- **Parallel Execution:** Automatic for 5000+ elements
- **Lazy Initialization:** Executors created only when needed
- **Result Caching:** `@Cached` annotation for expensive operations
- **Pre-computation:** Validator lookups cached before parallel execution
- **Efficient Chunking:** Work divided evenly across CPU cores

### 7. Extensibility

The framework provides multiple extension points:

- **ModelProvider:** Integrate any EMF metamodel
- **Extension Methods:** Add reusable helper functions
- **Pre/Post Hooks:** Lifecycle callbacks for setup/teardown
- **Guard Functions:** Custom conditional logic
- **Custom Attributes:** Inter-hook communication via context

---

## Core Concepts and Relationships

### Validation Rule Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Registered: @ValidationContext scanned
    Registered --> Discovered: Registry finds rule for element type
    Discovered --> Guarded: Check @Guard condition
    Guarded --> Skipped: Guard returns false
    Guarded --> DependencyCheck: Guard returns true
    DependencyCheck --> Skipped: @Satisfies dependency failed
    DependencyCheck --> Executed: All dependencies satisfied
    Executed --> Passed: ValidationResult.pass()
    Executed --> Failed: ValidationResult.fail()
    Passed --> [*]
    Failed --> [*]
    Skipped --> [*]
```

### Dependency Resolution

The framework resolves `@Satisfies` dependencies at runtime through a cache-based approach:

```mermaid
graph TD
    START[Rule needs to execute] --> CHECK{Check @Satisfies}
    CHECK -->|No dependencies| GUARD[Evaluate Guard]
    CHECK -->|Has dependencies| CACHE{Check Cache}
    
    CACHE -->|SATISFIED| GUARD
    CACHE -->|NOT_SATISFIED| SKIP[Skip Rule]
    CACHE -->|EVALUATING| CIRCULAR[Assume Satisfied<br/>Break Circular Dependency]
    CACHE -->|Not Cached| MARK[Mark as EVALUATING]
    
    MARK --> EVAL[Evaluate Dependency Constraint]
    EVAL --> STORE{Store Result}
    STORE -->|Passed| CACHE_SAT[Cache SATISFIED]
    STORE -->|Failed| CACHE_NOT[Cache NOT_SATISFIED]
    
    CACHE_SAT --> GUARD
    CACHE_NOT --> SKIP
    CIRCULAR --> GUARD
    
    GUARD -->|Guard False| SKIP
    GUARD -->|Guard True| EXECUTE[Execute Rule]
    
    EXECUTE --> RESULT[Return ValidationResult]
    SKIP --> RESULT
```

### Caching Strategy

The framework supports multiple caching levels:

**1. Satisfies Cache (Automatic)**
- Caches constraint satisfaction results
- Three states: EVALUATING, SATISFIED, NOT_SATISFIED
- Prevents re-evaluation of the same constraint
- Clears after validation run

**2. Extension Method Cache (Annotation-based)**
- Enabled via `@Cached` annotation
- Cache key: `(element, methodName, args)`
- Stores expensive computation results
- Clears after validation run

**3. Validator Cache (Performance Optimization)**
- Pre-computes validator lookups by type
- Used in parallel execution
- Reduces registry access during validation

### Thread Safety Model

```mermaid
graph LR
    subgraph "Thread-Safe (Concurrent)"
        REGISTRY[ValidationRegistry<br/>ConcurrentHashMap]
        SATISFIES_CACHE[Satisfies Cache<br/>ConcurrentHashMap]
        EXT_CACHE[Extension Cache<br/>ConcurrentHashMap]
        ATTRS[Context Attributes<br/>ConcurrentHashMap]
    end

    subgraph "Thread-Local"
        CURRENT_ELEM[Current Element<br/>ThreadLocal]
    end

    subgraph "Immutable"
        RESULT[ValidationResult]
        CACHE_KEY[CacheKey]
        DESCRIPTOR[ValidatorDescriptor<br/>metadata]
    end

    subgraph "Partition-Isolated"
        CHUNK1[Chunk 1]
        CHUNK2[Chunk 2]
        CHUNK3[Chunk N]
    end

    style REGISTRY fill:#c8e6c9
    style SATISFIES_CACHE fill:#c8e6c9
    style EXT_CACHE fill:#c8e6c9
    style ATTRS fill:#c8e6c9
    style CURRENT_ELEM fill:#fff9c4
    style RESULT fill:#e1f5fe
    style CACHE_KEY fill:#e1f5fe
    style DESCRIPTOR fill:#e1f5fe
    style CHUNK1 fill:#f0f0f0
    style CHUNK2 fill:#f0f0f0
    style CHUNK3 fill:#f0f0f0
```

**Thread Safety Guarantees:**
- **Registry:** Thread-safe registration and lookup
- **Context:** ThreadLocal current element prevents races
- **Caches:** ConcurrentHashMap for parallel access
- **Results:** Immutable value objects
- **Chunks:** No shared state between parallel workers

---

## Execution Flow

### Sequential Validation Flow

```mermaid
sequenceDiagram
    participant Client
    participant Executor as ValidationExecutor
    participant Registry as ValidationRegistry
    participant Context as ValidationContext
    participant Descriptor as ValidatorDescriptor
    participant Rule as ValidationRule

    Client->>Executor: validate(elements)
    Executor->>Registry: invokePreValidationHooks(context)
    
    loop For each element
        Executor->>Context: setCurrentElement(element)
        Executor->>Registry: getValidatorsFor(element.class)
        Registry-->>Executor: List<ValidatorDescriptor>
        
        loop For each validator
            Executor->>Descriptor: appliesTo(element)?
            alt Applies
                Executor->>Descriptor: validate(element, context)
                Descriptor->>Context: Check @Satisfies dependencies
                alt Dependencies satisfied
                    Descriptor->>Descriptor: Evaluate guard
                    alt Guard passes
                        Descriptor->>Rule: validate(element, context)
                        Rule-->>Descriptor: ValidationResult
                    end
                end
                Descriptor-->>Executor: ValidationResult
            end
        end
        
        Executor->>Context: clearCurrentElement()
    end
    
    Executor->>Registry: invokePostValidationHooks(context)
    Executor-->>Client: List<ValidationResult> (failures only)
```

### Parallel Validation Flow

```mermaid
graph TD
    START[validate elements] --> COUNT{Element count >= 5000?}
    COUNT -->|No| SEQUENTIAL[Sequential Validation]
    COUNT -->|Yes| PRECOMPUTE[Pre-compute Validator Cache]
    
    PRECOMPUTE --> PARTITION[Partition into Chunks<br/>Size: max 100 elements]
    PARTITION --> SCHEDULE[Schedule Chunks on WorkStealingPool]
    
    SCHEDULE --> WORKER1[Worker Thread 1<br/>Process Chunk 1]
    SCHEDULE --> WORKER2[Worker Thread 2<br/>Process Chunk 2]
    SCHEDULE --> WORKERN[Worker Thread N<br/>Process Chunk N]
    
    WORKER1 --> VALIDATE1[Sequential Validation<br/>of Chunk Elements]
    WORKER2 --> VALIDATE2[Sequential Validation<br/>of Chunk Elements]
    WORKERN --> VALIDATEN[Sequential Validation<br/>of Chunk Elements]
    
    VALIDATE1 --> RESULTS1[Chunk Results 1]
    VALIDATE2 --> RESULTS2[Chunk Results 2]
    VALIDATEN --> RESULTSN[Chunk Results N]
    
    RESULTS1 --> MERGE[Merge All Results]
    RESULTS2 --> MERGE
    RESULTSN --> MERGE
    
    SEQUENTIAL --> FILTER[Filter Failures]
    MERGE --> FILTER
    FILTER --> RETURN[Return List<ValidationResult>]
```

### Extension Method Invocation

```mermaid
sequenceDiagram
    participant Rule as ValidationRule
    participant Context as ValidationContext
    participant ExtRegistry as ExtensionMethodRegistry
    participant Cache as Extension Cache
    participant Descriptor as ExtensionMethodDescriptor

    Rule->>Context: call(element, "methodName", args)
    Context->>ExtRegistry: invoke(element, methodName, args)
    ExtRegistry->>ExtRegistry: findMethod(type, name, args)
    
    alt Method has @Cached
        ExtRegistry->>Cache: get(cacheKey)
        alt Cache hit
            Cache-->>ExtRegistry: Cached result
            ExtRegistry-->>Context: Result
        else Cache miss
            ExtRegistry->>Descriptor: invoke(element, args)
            Descriptor-->>ExtRegistry: Result
            ExtRegistry->>Cache: put(cacheKey, result)
            ExtRegistry-->>Context: Result
        end
    else Method not cached
        ExtRegistry->>Descriptor: invoke(element, args)
        Descriptor-->>ExtRegistry: Result
        ExtRegistry-->>Context: Result
    end
    
    Context-->>Rule: Result
```

---

## Thread Safety and Concurrency

### Parallel Execution Architecture

The framework uses a **work-stealing thread pool** (ForkJoinPool) for parallel validation:

```
┌─────────────────────────────────────────────────────────┐
│              ValidationExecutor                          │
│                                                          │
│  Elements (10,000)                                       │
│      ↓                                                   │
│  Partition into Chunks (100 each)                        │
│      ↓                                                   │
│  ┌──────────┬──────────┬──────────┬─────────────┐       │
│  │ Chunk 1  │ Chunk 2  │ Chunk 3  │  ... 100   │       │
│  │ (100)    │ (100)    │ (100)    │  (100)      │       │
│  └──────────┴──────────┴──────────┴─────────────┘       │
│      ↓           ↓           ↓            ↓              │
│  ┌────────────────────────────────────────────────┐     │
│  │      ForkJoinPool (Work-Stealing)              │     │
│  │                                                 │     │
│  │  [Thread 1] → Process Chunk 1                  │     │
│  │  [Thread 2] → Process Chunk 2                  │     │
│  │  [Thread 3] → Process Chunk 3                  │     │
│  │  [Thread 4] → Steal work if idle               │     │
│  │  ...                                            │     │
│  │  [Thread N] → Process Chunk N                  │     │
│  └────────────────────────────────────────────────┘     │
│      ↓                                                   │
│  CompletableFuture.join() - Wait for all                │
│      ↓                                                   │
│  Merge Results                                           │
└─────────────────────────────────────────────────────────┘
```

### Thread-Local State Management

```java
// Each thread maintains its own current element
private final ThreadLocal<EObject> currentElement = new ThreadLocal<>();

// Set before validating chunk
context.setCurrentElement(element);

// Access in validation rules
EObject current = context.getCurrentElement();

// Clear after chunk completes (prevent memory leak)
context.clearCurrentElement();
```

### Concurrent Data Structures

| Structure | Type | Thread Safety | Purpose |
|-----------|------|---------------|---------|
| `validators` | HashMap | Read-only after registration | Validator lookup |
| `satisfiesCache` | ConcurrentHashMap | Concurrent reads/writes | Dependency cache |
| `extensionCache` | ConcurrentHashMap | Concurrent reads/writes | Extension result cache |
| `attributes` | ConcurrentHashMap | Concurrent reads/writes | Custom attributes |
| `currentElement` | ThreadLocal | Thread-isolated | Current element |

---

## Extension Points

The framework provides several extension points for customization:

### 1. ModelProvider Interface

Integrate any EMF metamodel by implementing `ModelProvider`:

```java
public interface ModelProvider {
    <T extends EObject> Collection<T> getAllContents(ResourceSet rs, Class<T> type);
    default String getName(EObject element) { /* ... */ }
    default String getTypeName(EObject element) { /* ... */ }
}
```

**Use Cases:**
- Metamodel-specific model traversal
- Custom naming conventions
- Type name resolution for error messages

### 2. Extension Methods

Add reusable helper functions via `@ExtensionMethod`:

```java
@ValidationContext(EntityType.class)
public class EntityExtensions {
    @ExtensionMethod(elementType = EntityType.class)
    public List<Attribute> getAllAttributes(EntityType entity) {
        // Recursive attribute collection
    }
}
```

**Use Cases:**
- Reusable computation logic
- Model navigation helpers
- Complex queries

### 3. Pre/Post Validation Hooks

Lifecycle callbacks for setup and teardown:

```java
@PreValidation
public void setUp(ValidationContext ctx) {
    // Initialize caches, collect statistics
}

@PostValidation
public void tearDown(ValidationContext ctx) {
    // Cleanup, logging, metrics
}
```

**Use Cases:**
- Cache initialization
- Performance metrics collection
- Logging and debugging
- Resource cleanup

### 4. Custom Attributes

Share data between hooks via context attributes:

```java
@PreValidation
public void setUp(ValidationContext ctx) {
    ctx.setAttribute("startTime", System.currentTimeMillis());
}

@PostValidation
public void tearDown(ValidationContext ctx) {
    long startTime = ctx.getAttribute("startTime");
    long duration = System.currentTimeMillis() - startTime;
    log.info("Validation took {} ms", duration);
}
```

### 5. Guard Functions

Custom conditional logic for rule execution:

```java
@Guard(method = "isNotAbstract")
@Constraint(name = "MustHaveTable", message = "Concrete entity must have table")
public ValidationRule mustHaveTable() { /* ... */ }

private boolean isNotAbstract(EObject element, ValidationContext ctx) {
    return !((EntityType) element).isAbstract();
}
```

**Use Cases:**
- Conditional validation based on element state
- Context-aware rule activation
- Cross-cutting concerns

---

## Summary

The Judo Zeta validation framework provides a modern, performant, and type-safe approach to EMF model validation through:

- **Annotation-driven DSL** for declarative rule definition
- **Parallel execution engine** with automatic work partitioning
- **Functional programming** for composable validation logic
- **Comprehensive caching** for performance optimization
- **Thread-safe architecture** for concurrent validation
- **Extensible design** for metamodel integration

The architecture balances simplicity, performance, and flexibility, making it suitable for both small and large-scale EMF metamodel validation scenarios.

---

**Navigation:** [Documentation Home](../README.md) > [Architecture](./README.md) > Overview
