# Proposal: Add Multi-Package Transformation Support

**Change ID**: `add-multi-package-transformation-support`
**Status**: Proposed
**Created**: 2025-12-22
**Type**: Feature

## Summary

Add support for registering and resolving multiple target EPackages in TransformationContext, and enable transformation of package structures from source to target models. Currently, `createTarget()` only supports a single target package via `setTargetPackage(EPackage)`. This change enables:

1. Multi-package target metamodels (e.g., UI model with UiPackage and DataPackage)
2. Package-to-package transformations using standard transformation rules
3. Hierarchical package structures in both source and target models

## Motivation

### Multiple Target Packages for Element Creation

The current `TransformationContext.createTarget()` method only supports a single target package. This limitation prevents proper transformation of models where:

1. **Multiple target packages**: The target metamodel has multiple packages (e.g., UI model has `UiPackage` and `DataPackage`)
2. **Type-based routing**: Different target types need to be created from different packages
3. **Complex metamodels**: Real-world transformations like ESM2UI require creating elements from multiple packages

### Metamodel vs Model Packages

**Metamodel layer (EMF infrastructure):**
- All metamodels are defined in Ecore as `EPackage` containing `EClass` definitions
- `createTarget()` uses `EPackage.getEFactoryInstance().create()` to instantiate elements
- This is infrastructure - not visible in transformation logic

**Model layer (domain types):**
- Domain packages (`Namespace`, `Module`, `Package`) are regular EClasses defined in EPackages
- They are model elements that get transformed like any other type
- Their relationships (containment, references) are transformed via `equivalent()`

```
┌─────────────────────────────────────────────────────────┐
│ Metamodel Layer (EMF Infrastructure)                    │
│ ┌─────────────────┐  ┌─────────────────┐               │
│ │ SourcePackage   │  │ TargetPackage   │  (EPackage)   │
│ │ ├─ Namespace    │  │ ├─ Module       │  (EClass)     │
│ │ ├─ SourceClass  │  │ ├─ Entity       │               │
│ │ └─ Attribute    │  │ └─ Field        │               │
│ └─────────────────┘  └─────────────────┘               │
└─────────────────────────────────────────────────────────┘
                           ↓ instantiates
┌─────────────────────────────────────────────────────────┐
│ Model Layer (Domain Instances)                          │
│ ┌─────────────────┐  ┌─────────────────┐               │
│ │ myNamespace     │→→│ myModule        │  (EObject)    │
│ │ ├─ myClass      │→→│ ├─ myEntity     │               │
│ │ └─ myAttr       │→→│ └─ myField      │               │
│ └─────────────────┘  └─────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

EPackage is required for element creation because all types are EClasses. But from a transformation perspective, `Namespace`, `Module`, etc. are just domain types that get transformed via standard rules.

**Current limitation example:**
```java
// Only one package can be set:
ctx.setTargetPackage(UiPackage.eINSTANCE);
Application app = ctx.createTarget(Application.class); // Works (from UiPackage)
ClassType ct = ctx.createTarget(ClassType.class);     // FAILS - ClassType is in DataPackage!
```

## Goals

1. **Multi-Package Registry**: Support registration of multiple EPackages as target packages for element creation
2. **Package Transformation Rules**: Enable transformation of source packages to target packages using standard rules
3. **Hierarchical Package Support**: Support nested package structures in both source and target models
4. **Automatic Type Resolution**: Automatically find the correct EPackage for a given target type
5. **Explicit Package Selection**: Allow explicit EPackage specification per `createTarget()` call
6. **Thread Safety**: Maintain thread-safe operation for parallel transformations
7. **Backward Compatibility**: Existing single-package usage continues to work unchanged

## Non-Goals

1. **Special treatment of EPackage**: EPackage is an EMF implementation detail; packages in metamodels are regular types
2. **Complex Resolution Strategies**: No source-based resolution or annotation-based routing (can be added later if needed)

## Scope

### In Scope

**Element Creation (EPackage registry):**
- `TransformationContext.registerTargetPackage(EPackage)` - register additional target EPackage for element creation
- `TransformationContext.registerTargetPackage(EPackage, boolean)` - register with option to include sub-packages
- `TransformationContext.createTarget(Class, EPackage)` - create element in specific EPackage
- Automatic EPackage resolution when multiple packages are registered
- Recursive registration through EPackage hierarchies (`getESubpackages()`)
- Thread-safe package registry using `CopyOnWriteArrayList`
- Clear error messages when type cannot be resolved

**Package Transformation (standard rules):**
- Transformation rules for package types (e.g., `Namespace` → `Module`)
- Use `equivalent()` to resolve transformed packages
- Hierarchical package relationships via containment references

### Out of Scope

- Custom package resolver strategies (future enhancement)
- `@TargetPackage` annotation on rules (future enhancement)
- Builder pattern for package configuration (future enhancement)
- IDE tooling for package completion

## Proposed Solution

### API Changes

**New methods in TransformationContext:**
```java
/**
 * Register an additional target package.
 * Sub-packages are NOT included (for backward compatibility).
 * Use registerTargetPackage(pkg, true) to include sub-packages.
 */
public void registerTargetPackage(EPackage targetPackage);

/**
 * Register an additional target package with control over sub-package inclusion.
 * @param targetPackage the package to register
 * @param includeSubpackages if true, recursively include all sub-packages
 */
public void registerTargetPackage(EPackage targetPackage, boolean includeSubpackages);

/**
 * Create a target element in a specific package.
 * @param targetType the target type
 * @param pkg the specific package to use
 */
public <T extends EObject> T createTarget(Class<T> targetType, EPackage pkg);
```

**Modified methods:**
```java
/**
 * Create a target element.
 * Package is resolved automatically (including sub-packages):
 * 1. If only one package registered - search it and its sub-packages
 * 2. If multiple packages - search all packages and their sub-packages
 * 3. If type found in multiple packages - throw error (ambiguous)
 * 4. If type not found - throw error with helpful message
 */
public <T extends EObject> T createTarget(Class<T> targetType);
```

### Internal Changes

```java
// Replace single targetPackage with list:
private final List<EPackage> targetPackages = new CopyOnWriteArrayList<>();

// setTargetPackage() clears list and adds single package (NO sub-packages - backward compat)
public void setTargetPackage(EPackage targetPackage) {
    this.targetPackages.clear();
    if (targetPackage != null) {
        this.targetPackages.add(targetPackage);
    }
}

// registerTargetPackage() adds single package (NO sub-packages - consistent)
public void registerTargetPackage(EPackage pkg) {
    this.targetPackages.add(pkg);
}

// registerTargetPackage(pkg, true) adds package WITH sub-packages (opt-in)
public void registerTargetPackage(EPackage pkg, boolean includeSubpackages) {
    if (includeSubpackages) {
        addPackageWithSubpackages(pkg);
    } else {
        this.targetPackages.add(pkg);
    }
}

// Recursively add package and all sub-packages
private void addPackageWithSubpackages(EPackage pkg) {
    this.targetPackages.add(pkg);
    for (EPackage subPkg : pkg.getESubpackages()) {
        addPackageWithSubpackages(subPkg);
    }
}
```

### Usage Example

```java
TransformationContext ctx = new TransformationContext(...);

// Register multiple independent packages (sub-packages NOT included by default)
ctx.registerTargetPackage(UiPackage.eINSTANCE);
ctx.registerTargetPackage(DataPackage.eINSTANCE);

// Now both work automatically
Application app = ctx.createTarget(Application.class);   // Resolved from UiPackage
ClassType ct = ctx.createTarget(ClassType.class);        // Resolved from DataPackage

// Or explicitly specify package
ClassType ct2 = ctx.createTarget(ClassType.class, DataPackage.eINSTANCE);

// Opt-in: Register root package WITH sub-packages
ctx.registerTargetPackage(RootPackage.eINSTANCE, true);
// Now types from RootPackage AND all its sub-packages are available
```

### Hierarchical EPackage Example

```java
// Given EMF EPackage structure (for element creation):
// RootPackage
// ├── SubPackage1
// │   └── DeepPackage
// └── SubPackage2

// Option 1: Register each package explicitly (no sub-packages)
ctx.registerTargetPackage(RootPackage.eINSTANCE);
ctx.registerTargetPackage(SubPackage1.eINSTANCE);
ctx.registerTargetPackage(DeepPackage.eINSTANCE);
ctx.registerTargetPackage(SubPackage2.eINSTANCE);

// Option 2: Opt-in to include all sub-packages
ctx.registerTargetPackage(RootPackage.eINSTANCE, true);

// All these work automatically:
ctx.createTarget(RootType.class);      // From RootPackage
ctx.createTarget(Sub1Type.class);      // From SubPackage1
ctx.createTarget(DeepType.class);      // From DeepPackage
ctx.createTarget(Sub2Type.class);      // From SubPackage2
```

### Package Transformation Example

Packages in source/target metamodels are regular types transformed via standard rules:

```java
// Source metamodel has Namespace (a package concept)
// Target metamodel has Module (a different package concept)

// Transformation rule: Namespace → Module
@TransformRule(name = "Namespace2Module")
@Transform(type = Namespace.class)
@To(type = Module.class)
public TransformFunction<Namespace, Module> namespace2Module() {
    return (namespace, ctx) -> {
        Module module = ctx.createTarget(Module.class);
        module.setName(namespace.getName());

        // Transform parent package relationship
        if (namespace.getParent() != null) {
            Module parentModule = ctx.equivalent(namespace.getParent(), Module.class);
            parentModule.getSubModules().add(module);
        }

        return module;
    };
}

// Transformation rule: Class → Entity (with package reference)
@TransformRule(name = "Class2Entity")
@Transform(type = SourceClass.class)
@To(type = Entity.class)
public TransformFunction<SourceClass, Entity> class2Entity() {
    return (sourceClass, ctx) -> {
        Entity entity = ctx.createTarget(Entity.class);
        entity.setName(sourceClass.getName());

        // Resolve equivalent package using standard equivalent() call
        Module targetModule = ctx.equivalent(sourceClass.getNamespace(), Module.class);
        targetModule.getEntities().add(entity);

        return entity;
    };
}
```

### Multi-Level Package Hierarchy Transformation

```java
// Source: Project → Package → SubPackage (3 levels)
// Target: Application → Module → SubModule (3 levels)

@TransformRule(name = "Package2Module")
@Transform(type = Package.class)
@To(type = Module.class)
public TransformFunction<Package, Module> package2Module() {
    return (pkg, ctx) -> {
        Module module = ctx.createTarget(Module.class);
        module.setName(pkg.getName());

        // Handle containment - could be Project or parent Package
        EObject container = pkg.eContainer();
        if (container instanceof Project) {
            Application app = ctx.equivalent((Project) container, Application.class);
            app.getModules().add(module);
        } else if (container instanceof Package) {
            Module parentModule = ctx.equivalent((Package) container, Module.class);
            parentModule.getSubModules().add(module);
        }

        return module;
    };
}
```

### Error Handling

```java
// When type not found in any registered package:
IllegalArgumentException: EClass 'UnknownType' not found in any registered target package.
Registered packages: [http://blackbelt.hu/judo/meta/ui, http://blackbelt.hu/judo/meta/ui/data]

// When type found in multiple packages (ambiguous):
IllegalArgumentException: EClass 'Element' found in multiple packages:
[http://package1, http://package2]. Use createTarget(Class, EPackage) to specify.

// When no packages registered:
IllegalStateException: No target package registered. Call setTargetPackage() or registerTargetPackage() first.
```

## Benefits

1. **Enables Multi-Package Transformations**: Real-world transformations like ESM2UI can now work
2. **Hierarchical Package Support**: Automatically traverses EMF EPackage hierarchies (sub-packages)
3. **Simple API**: Just `registerTargetPackage()` and automatic resolution
4. **Full Backward Compatibility**: Single-package usage unchanged
5. **Type Safety**: Compile-time type checking preserved
6. **Thread Safety**: `CopyOnWriteArrayList` for parallel transformation support

## Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Ambiguous type resolution | Medium | Low | Clear error message with available packages |
| Performance overhead | Low | Low | CopyOnWriteArrayList optimized for read-heavy access |
| Breaking existing code | High | Very Low | setTargetPackage() behavior unchanged |

## Dependencies

- transformation-core module only (no new modules)

## References

- Current TransformationContext: `transformation-core/src/main/java/.../TransformationContext.java`
- ETL-patterns spec: `openspec/specs/etl-patterns/spec.md`
- Parallel transformation spec: `openspec/specs/parallel-transformation/spec.md`
