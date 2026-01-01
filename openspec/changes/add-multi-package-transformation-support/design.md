# Design: Multi-Package Transformation Support

## Overview

This document captures the architectural decisions for supporting multiple target EPackages in TransformationContext and enabling package-to-package transformations.

### Two-Layer Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Metamodel Layer (EMF Infrastructure - always Ecore)        │
│                                                            │
│  EPackage ──contains──> EClass ──has──> EAttribute         │
│     │                      │                               │
│     └── getEFactoryInstance().create() ──> EObject         │
└────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────┐
│ Model Layer (Domain Instances - what transformations see)  │
│                                                            │
│  Namespace ──contains──> SourceClass ──has──> Attribute    │
│      ↓ transform                ↓ transform                │
│  Module ──contains──> Entity ──has──> Field                │
└────────────────────────────────────────────────────────────┘
```

**Why EPackage is needed:**
- All metamodels are defined in Ecore (EMF's meta-metamodel)
- Every domain type (`Namespace`, `Module`, etc.) is an `EClass` inside an `EPackage`
- Element creation requires: `ePackage.getEFactoryInstance().create(eClass)`
- Multiple EPackages exist when target metamodel is split (e.g., `UiPackage`, `DataPackage`)

**Why EPackage is invisible to transformations:**
- Transformation rules work with domain types (`Namespace` → `Module`)
- `createTarget(Module.class)` abstracts away which EPackage contains `Module`
- The EPackage registry is infrastructure, not transformation logic

## Current State

The current `TransformationContext` uses a single `targetPackage` field:

```java
private EPackage targetPackage;

public void setTargetPackage(EPackage targetPackage) {
    this.targetPackage = targetPackage;
}

public <T extends EObject> T createTarget(Class<T> targetType) {
    if (targetPackage == null) {
        throw new IllegalStateException("Target package not set. Call setTargetPackage() first.");
    }
    String typeName = targetType.getSimpleName();
    EClass eClass = (EClass) targetPackage.getEClassifier(typeName);
    if (eClass == null) {
        throw new IllegalArgumentException("EClass not found in target package: " + typeName);
    }
    // ... create and return instance
}
```

## Proposed Architecture

### Design Principles

1. **Minimal Changes**: Keep the implementation simple and focused
2. **Hierarchical Support**: Automatically traverse EMF EPackage hierarchies (sub-packages)
3. **Backward Compatibility**: Existing `setTargetPackage()` usage must work unchanged
4. **Thread Safety**: Support parallel transformations
5. **Clear Errors**: Provide helpful error messages for resolution failures

### Data Structure

Replace single package with a thread-safe list:

```java
// Before:
private EPackage targetPackage;

// After:
private final List<EPackage> targetPackages = new CopyOnWriteArrayList<>();
```

**Why `CopyOnWriteArrayList`?**
- Thread-safe without explicit synchronization
- Optimized for read-heavy, write-rare patterns (packages registered once at startup)
- Iteration is safe during concurrent modifications
- Matches pattern used elsewhere in the codebase (e.g., `resourceRegistry`)

### Package Resolution Algorithm

```java
private EPackage resolvePackageForType(Class<?> targetType) {
    String typeName = targetType.getSimpleName();

    if (targetPackages.isEmpty()) {
        throw new IllegalStateException(
            "No target package registered. Call setTargetPackage() or registerTargetPackage() first.");
    }

    // Fast path: single package
    if (targetPackages.size() == 1) {
        EPackage pkg = targetPackages.get(0);
        if (pkg.getEClassifier(typeName) instanceof EClass) {
            return pkg;
        }
        throw new IllegalArgumentException("EClass not found in target package: " + typeName);
    }

    // Multi-package: find matching package(s)
    List<EPackage> matchingPackages = targetPackages.stream()
        .filter(pkg -> pkg.getEClassifier(typeName) instanceof EClass)
        .collect(Collectors.toList());

    if (matchingPackages.isEmpty()) {
        throw new IllegalArgumentException(
            "EClass '" + typeName + "' not found in any registered target package. " +
            "Registered packages: " + targetPackages.stream()
                .map(EPackage::getNsURI)
                .collect(Collectors.toList()));
    }

    if (matchingPackages.size() > 1) {
        throw new IllegalArgumentException(
            "EClass '" + typeName + "' found in multiple packages: " +
            matchingPackages.stream().map(EPackage::getNsURI).collect(Collectors.toList()) +
            ". Use createTarget(Class, EPackage) to specify which package to use.");
    }

    return matchingPackages.get(0);
}
```

### API Methods

```java
/**
 * Set the target package (clears any previously registered packages).
 * Sub-packages are NOT included (backward compatible).
 */
public void setTargetPackage(EPackage targetPackage) {
    this.targetPackages.clear();
    if (targetPackage != null) {
        this.targetPackages.add(targetPackage);
    }
}

/**
 * Register an additional target package.
 * Sub-packages are NOT included (consistent with setTargetPackage).
 * Use registerTargetPackage(pkg, true) to include sub-packages.
 */
public void registerTargetPackage(EPackage targetPackage) {
    if (targetPackage == null) {
        throw new IllegalArgumentException("Target package cannot be null");
    }
    this.targetPackages.add(targetPackage);
}

/**
 * Register an additional target package with control over sub-package inclusion.
 * @param targetPackage the package to register
 * @param includeSubpackages if true, recursively include all sub-packages
 */
public void registerTargetPackage(EPackage targetPackage, boolean includeSubpackages) {
    if (targetPackage == null) {
        throw new IllegalArgumentException("Target package cannot be null");
    }
    if (includeSubpackages) {
        addPackageWithSubpackages(targetPackage);
    } else {
        this.targetPackages.add(targetPackage);
    }
}

/**
 * Recursively add a package and all its sub-packages.
 */
private void addPackageWithSubpackages(EPackage pkg) {
    this.targetPackages.add(pkg);
    for (EPackage subPkg : pkg.getESubpackages()) {
        addPackageWithSubpackages(subPkg);
    }
}

/**
 * Get all registered target packages.
 */
public List<EPackage> getTargetPackages() {
    return Collections.unmodifiableList(new ArrayList<>(targetPackages));
}

/**
 * Create a target element with automatic package resolution.
 */
public <T extends EObject> T createTarget(Class<T> targetType) {
    EPackage pkg = resolvePackageForType(targetType);
    return createTargetInPackage(targetType, pkg);
}

/**
 * Create a target element in a specific package.
 */
public <T extends EObject> T createTarget(Class<T> targetType, EPackage pkg) {
    if (pkg == null) {
        throw new IllegalArgumentException("Package cannot be null");
    }
    return createTargetInPackage(targetType, pkg);
}

@SuppressWarnings("unchecked")
private <T extends EObject> T createTargetInPackage(Class<T> targetType, EPackage pkg) {
    String typeName = targetType.getSimpleName();
    EClass eClass = (EClass) pkg.getEClassifier(typeName);
    if (eClass == null) {
        throw new IllegalArgumentException("EClass not found in package " + pkg.getNsURI() + ": " + typeName);
    }

    EObject instance = pkg.getEFactoryInstance().create(eClass);

    // Existing staging/resource logic...
    if (stagingEnabled.get()) {
        long sequence = creationSequence.getAndIncrement();
        elementOrder.put(instance, sequence);
        stagedElements.offer(new StagedElement(instance, true, sequence));
    } else {
        if (!targetResourceSet.getResources().isEmpty()) {
            Resource targetResource = targetResourceSet.getResources().get(0);
            targetResource.getContents().add(instance);
        }
    }

    return (T) instance;
}
```

### Impact on `create()` Method

The `create()` method (ETL-style element creation without containment) also needs updating:

```java
public <T extends EObject> T create(Class<T> type) {
    EPackage pkg = resolvePackageForType(type);
    return createWithoutContainment(type, pkg);
}

public <T extends EObject> T create(Class<T> type, EPackage pkg) {
    if (pkg == null) {
        throw new IllegalArgumentException("Package cannot be null");
    }
    return createWithoutContainment(type, pkg);
}

private <T extends EObject> T createWithoutContainment(Class<T> type, EPackage pkg) {
    String typeName = type.getSimpleName();
    EClass eClass = (EClass) pkg.getEClassifier(typeName);
    if (eClass == null) {
        throw new IllegalArgumentException("EClass not found in package " + pkg.getNsURI() + ": " + typeName);
    }

    EObject instance = pkg.getEFactoryInstance().create(eClass);

    // Track for ordering but do NOT add to any resource
    if (stagingEnabled.get()) {
        long sequence = creationSequence.getAndIncrement();
        elementOrder.put(instance, sequence);
        stagedElements.offer(new StagedElement(instance, false, sequence));
    }

    return (T) instance;
}
```

## Thread Safety Analysis

| Operation | Thread Safety | Mechanism |
|-----------|---------------|-----------|
| `registerTargetPackage()` | Safe | `CopyOnWriteArrayList.add()` |
| `setTargetPackage()` | Safe | `CopyOnWriteArrayList.clear()` + `add()` |
| `createTarget()` | Safe | Iteration over COW list; per-thread staging |
| `resolvePackageForType()` | Safe | Read-only iteration |

**Note**: Package registration typically happens during transformation setup (single-threaded), while `createTarget()` is called during parallel transformation. This read-heavy pattern is ideal for `CopyOnWriteArrayList`.

## Hierarchical Package Support

### EMF Package Hierarchy

EMF EPackages can contain sub-packages via `getESubpackages()`. This creates a tree structure:

```
RootPackage (nsURI: http://example/root)
├── SubPackage1 (nsURI: http://example/root/sub1)
│   ├── DeepPackage (nsURI: http://example/root/sub1/deep)
│   └── AnotherDeep (nsURI: http://example/root/sub1/another)
└── SubPackage2 (nsURI: http://example/root/sub2)
```

### Design Decision: Flatten on Registration

When a package is registered, we recursively add all sub-packages to the flat list:

```java
// When user calls:
ctx.registerTargetPackage(RootPackage.eINSTANCE);

// Internally, targetPackages list contains:
// [RootPackage, SubPackage1, DeepPackage, AnotherDeep, SubPackage2]
```

**Rationale:**
1. **Simplicity**: Resolution algorithm remains a simple linear search
2. **Performance**: No recursive traversal during type lookup
3. **Predictability**: All packages are treated equally after registration
4. **EMF Alignment**: Matches how EMF resource loading discovers packages

### Why Not Recursive Resolution?

An alternative approach would be to keep only root packages and recursively search during resolution:

```java
// Alternative (NOT USED):
private EPackage findInPackageHierarchy(EPackage pkg, String typeName) {
    if (pkg.getEClassifier(typeName) instanceof EClass) {
        return pkg;
    }
    for (EPackage subPkg : pkg.getESubpackages()) {
        EPackage found = findInPackageHierarchy(subPkg, typeName);
        if (found != null) return found;
    }
    return null;
}
```

**Rejected because:**
1. More complex resolution logic
2. Repeated tree traversal on every `createTarget()` call
3. Harder to debug (which sub-package was searched?)
4. Ambiguity detection becomes more complex

### Opt-Out of Sub-Package Inclusion

For cases where sub-packages should NOT be included:

```java
// Register only the root package, not its children
ctx.registerTargetPackage(RootPackage.eINSTANCE, false);
```

Use cases:
- Testing with limited scope
- Avoiding ambiguity from sub-packages
- Performance optimization for large hierarchies

### Impact on Error Messages

When a type is not found, the error lists all packages (including sub-packages):

```
EClass 'UnknownType' not found in any registered target package.
Registered packages: [
  http://example/root,
  http://example/root/sub1,
  http://example/root/sub1/deep,
  http://example/root/sub1/another,
  http://example/root/sub2
]
```

This helps diagnose whether the expected package hierarchy was registered.

## Package Transformation via Standard Rules

### Packages as Model Elements

In metamodels, packages are regular types like any other:

| Source Metamodel | Target Metamodel | Transformation |
|------------------|------------------|----------------|
| `Namespace` | `Module` | Standard rule |
| `Package` | `Package` | Standard rule |
| `Project` | `Application` | Standard rule |

These are **not** EMF EPackages - they are domain-specific types that represent containment/organization in the model.

### How Package Transformation Works

```java
// 1. Define transformation rule for package types
@TransformRule(name = "Namespace2Module")
@Transform(type = Namespace.class)
@To(type = Module.class)
public TransformFunction<Namespace, Module> namespace2Module() {
    return (namespace, ctx) -> {
        Module module = ctx.createTarget(Module.class);
        module.setName(namespace.getName());
        // Handle parent relationship...
        return module;
    };
}

// 2. Other rules use equivalent() to resolve package references
@TransformRule(name = "Class2Entity")
@Transform(type = SourceClass.class)
@To(type = Entity.class)
public TransformFunction<SourceClass, Entity> class2Entity() {
    return (sourceClass, ctx) -> {
        Entity entity = ctx.createTarget(Entity.class);

        // Resolve the equivalent target package - triggers lazy rule if needed
        Module targetModule = ctx.equivalent(sourceClass.getNamespace(), Module.class);
        targetModule.getEntities().add(entity);

        return entity;
    };
}
```

### EPackage vs Package Summary

| Aspect | EPackage (EMF) | Package (Metamodel) |
|--------|----------------|---------------------|
| Purpose | Factory for `createTarget()` | Model organization |
| Registration | `registerTargetPackage()` | N/A (it's a model element) |
| Transformation | N/A | Via standard rules |
| Resolution | Automatic type lookup | `equivalent()` |
| Example | `UiPackage.eINSTANCE` | `sourceModel.getNamespace()` |

### Why This Design

1. **Consistency**: Packages are transformed like any other type - no special handling
2. **Flexibility**: Different source/target package structures are supported
3. **Lazy evaluation**: Package rules execute on-demand via `equivalent()`
4. **No coupling**: EPackage registry is independent of metamodel package concepts

## Alternative Designs Considered

### 1. ModelPackage Abstraction Layer

The original design specification proposed a `ModelPackage` interface to abstract away from EMF:

```java
public interface ModelPackage {
    String getId();
    boolean containsType(String typeName);
    <T> T createInstance(Class<T> type);
}
```

**Decision: Not implemented**

Reasons:
- Adds complexity without immediate benefit
- All current use cases are EMF-based
- Can be added later if non-EMF support is needed
- YAGNI (You Aren't Gonna Need It)

### 2. PackageResolver Strategy Pattern

The original design proposed a strategy pattern for package resolution:

```java
public interface PackageResolver {
    Optional<ModelPackage> resolve(PackageResolutionContext context);
}
```

**Decision: Not implemented in v1**

Reasons:
- Simple type-name-based resolution covers 95% of use cases
- Adds significant complexity
- Can be added as a future enhancement if needed
- Explicit `createTarget(Class, EPackage)` handles edge cases

### 3. @TargetPackage Annotation

The original design proposed annotations on rules:

```java
@TransformRule(name = "Actor")
@TargetPackage("http://blackbelt.hu/judo/meta/ui/data")
public TransformFunction<ActorType, ClassType> actor() { ... }
```

**Decision: Not implemented in v1**

Reasons:
- Requires changes to annotation processing and registry
- Automatic resolution handles most cases
- Explicit `createTarget(Class, EPackage)` handles special cases
- Can be added as a future enhancement

## Migration Path

### Existing Code (No Changes Required)

```java
// This continues to work exactly as before:
ctx.setTargetPackage(MyPackage.eINSTANCE);
MyType t = ctx.createTarget(MyType.class);
```

### New Multi-Package Code

```java
// Register multiple packages:
ctx.registerTargetPackage(UiPackage.eINSTANCE);
ctx.registerTargetPackage(DataPackage.eINSTANCE);

// Automatic resolution:
Application app = ctx.createTarget(Application.class);  // From UiPackage
ClassType ct = ctx.createTarget(ClassType.class);       // From DataPackage

// Or explicit when needed:
ClassType ct2 = ctx.createTarget(ClassType.class, DataPackage.eINSTANCE);
```

### Mixed Usage

```java
// Can mix setTargetPackage with registerTargetPackage:
ctx.setTargetPackage(UiPackage.eINSTANCE);           // Primary package
ctx.registerTargetPackage(DataPackage.eINSTANCE);    // Additional package
```

## Future Enhancements

If more sophisticated resolution is needed, these can be added later:

1. **@TargetPackage annotation** - specify package per rule
2. **PackageResolver interface** - custom resolution strategies
3. **Builder pattern** - fluent configuration API
4. **Source-based resolution** - map source package to target package

These can be implemented without breaking the API introduced here.
