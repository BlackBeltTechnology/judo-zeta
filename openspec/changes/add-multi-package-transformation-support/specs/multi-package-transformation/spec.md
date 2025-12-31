# Spec: Multi-Package Transformation Support

**Capability**: multi-package-transformation
**Status**: Proposed
**Parent**: parallel-transformation

## Purpose

This specification defines the requirements for:

1. **Multiple target EPackages**: Supporting registration and resolution of multiple EMF EPackages for element creation via `createTarget()`
2. **Package transformation**: Transforming package-like types (e.g., `Namespace`, `Module`) from source to target models using standard transformation rules

### Architecture Context

```
Metamodel Layer (Ecore)          Model Layer (Domain)
─────────────────────────        ────────────────────
EPackage                         Namespace, Module (domain types)
  └─ EClass                        └─ transformed via rules
      └─ creates EObject               └─ resolved via equivalent()
```

**Why EPackage is required:**
- All metamodels are Ecore-based; every domain type is an `EClass` in an `EPackage`
- `createTarget()` needs the EFactory from EPackage to instantiate elements
- Multiple EPackages when target metamodel is split across packages

**Why EPackage is infrastructure:**
- Transformations work with domain types (`Namespace` → `Module`), not EPackages
- `createTarget(Module.class)` automatically finds the right EPackage
- Domain package types are transformed via standard rules like any other type

## ADDED Requirements

### Requirement: Multiple Target Package Registration

The TransformationContext MUST support registration of multiple target EPackages.

#### Scenario: Register single package via setTargetPackage

**Given** an empty TransformationContext
**When** `setTargetPackage(UiPackage.eINSTANCE)` is called
**Then** only UiPackage is registered (sub-packages NOT included)
**And** `createTarget(Application.class)` succeeds for types in UiPackage
**And** types in sub-packages are NOT resolvable (backward compatible)

#### Scenario: Register additional package via registerTargetPackage

**Given** a TransformationContext with UiPackage already set via setTargetPackage
**When** `registerTargetPackage(DataPackage.eINSTANCE)` is called
**Then** both UiPackage and DataPackage are registered (sub-packages NOT included)
**And** `createTarget()` can create elements from either package

#### Scenario: setTargetPackage clears previously registered packages

**Given** a TransformationContext with UiPackage and DataPackage registered
**When** `setTargetPackage(NewPackage.eINSTANCE)` is called
**Then** only NewPackage is registered
**And** types from UiPackage and DataPackage are no longer resolvable

#### Scenario: Register null package throws exception

**Given** a TransformationContext
**When** `registerTargetPackage(null)` is called
**Then** an IllegalArgumentException is thrown
**And** the message indicates that the package cannot be null

---

### Requirement: Automatic Package Resolution

The `createTarget()` method MUST automatically resolve the correct package for the requested type.

#### Scenario: Single package resolution

**Given** a TransformationContext with only UiPackage registered
**When** `createTarget(Application.class)` is called
**Then** the Application element is created from UiPackage
**And** no explicit package specification is required

#### Scenario: Multi-package automatic resolution

**Given** a TransformationContext with UiPackage and DataPackage registered
**And** Application is defined only in UiPackage
**And** ClassType is defined only in DataPackage
**When** `createTarget(Application.class)` is called
**Then** Application is created from UiPackage
**When** `createTarget(ClassType.class)` is called
**Then** ClassType is created from DataPackage

#### Scenario: Type not found in any package

**Given** a TransformationContext with UiPackage registered
**When** `createTarget(UnknownType.class)` is called
**Then** an IllegalArgumentException is thrown
**And** the message lists the registered packages

#### Scenario: No packages registered

**Given** a TransformationContext with no packages registered
**When** `createTarget(Application.class)` is called
**Then** an IllegalStateException is thrown
**And** the message indicates to call setTargetPackage or registerTargetPackage

---

### Requirement: Ambiguous Type Resolution Handling

When a type exists in multiple registered packages, the framework MUST reject ambiguous resolution with a clear error.

#### Scenario: Type found in multiple packages

**Given** a TransformationContext with Package1 and Package2 registered
**And** both packages define an EClass named "Element"
**When** `createTarget(Element.class)` is called
**Then** an IllegalArgumentException is thrown
**And** the message indicates which packages contain the type
**And** the message suggests using `createTarget(Class, EPackage)`

#### Scenario: Explicit package resolves ambiguity

**Given** a TransformationContext with Package1 and Package2 registered
**And** both packages define an EClass named "Element"
**When** `createTarget(Element.class, Package1.eINSTANCE)` is called
**Then** Element is created from Package1
**And** no error is thrown

---

### Requirement: Explicit Package Specification

The `createTarget()` method MUST support explicit package specification.

#### Scenario: Create element in specific package

**Given** a TransformationContext with UiPackage and DataPackage registered
**When** `createTarget(ClassType.class, DataPackage.eINSTANCE)` is called
**Then** ClassType is created from DataPackage
**And** no automatic resolution is performed

#### Scenario: Explicit package not containing type

**Given** a TransformationContext with UiPackage and DataPackage registered
**And** ClassType is only defined in DataPackage
**When** `createTarget(ClassType.class, UiPackage.eINSTANCE)` is called
**Then** an IllegalArgumentException is thrown
**And** the message indicates the type was not found in the specified package

#### Scenario: Explicit null package throws exception

**Given** a TransformationContext
**When** `createTarget(Application.class, null)` is called
**Then** an IllegalArgumentException is thrown

---

### Requirement: Thread-Safe Package Registry

The package registry MUST be thread-safe for use during parallel transformations.

#### Scenario: Concurrent createTarget with multiple packages

**Given** a TransformationContext with UiPackage and DataPackage registered
**And** parallel transformation is enabled
**When** multiple threads call createTarget for different types concurrently
**Then** all elements are created correctly
**And** no ConcurrentModificationException is thrown
**And** each type is resolved from the correct package

#### Scenario: Package registration before parallel phase

**Given** a TransformationContext
**When** packages are registered before parallel transformation starts
**And** parallel transformation creates elements
**Then** all registered packages are visible to all worker threads
**And** type resolution works correctly for all packages

---

### Requirement: ETL-Style create() with Multiple Packages

The `create()` method (element creation without containment) MUST support multiple packages.

#### Scenario: create() resolves package automatically

**Given** a TransformationContext with UiPackage and DataPackage registered
**When** `create(ClassType.class)` is called
**Then** ClassType is created from DataPackage
**And** the element is not added to any resource

#### Scenario: create() with explicit package

**Given** a TransformationContext with UiPackage and DataPackage registered
**When** `create(ClassType.class, DataPackage.eINSTANCE)` is called
**Then** ClassType is created from DataPackage
**And** the element is not added to any resource

---

### Requirement: Backward Compatibility

Existing single-package transformations MUST work unchanged.

#### Scenario: Existing setTargetPackage usage

**Given** existing code that calls `ctx.setTargetPackage(MyPackage.eINSTANCE)`
**And** then calls `ctx.createTarget(MyType.class)`
**When** the code executes with the new implementation
**Then** the behavior is identical to before
**And** no code changes are required

#### Scenario: Existing create usage

**Given** existing code that calls `ctx.setTargetPackage(MyPackage.eINSTANCE)`
**And** then calls `ctx.create(MyType.class)`
**When** the code executes with the new implementation
**Then** the behavior is identical to before

#### Scenario: All existing tests pass

**Given** the existing test suite for TransformationContext
**When** tests are executed with the new implementation
**Then** all tests pass without modification

---

### Requirement: getTargetPackages Accessor

The TransformationContext MUST provide access to registered packages.

#### Scenario: Get all registered packages

**Given** a TransformationContext with UiPackage and DataPackage registered
**When** `getTargetPackages()` is called
**Then** a list containing both packages is returned
**And** the list is unmodifiable

#### Scenario: Get packages returns empty list when none registered

**Given** a TransformationContext with no packages registered
**When** `getTargetPackages()` is called
**Then** an empty list is returned

---

### Requirement: Hierarchical Package Support (Opt-In)

The framework MUST support opt-in inclusion of sub-packages for backward compatibility.

#### Scenario: Sub-packages NOT included by default

**Given** an EPackage RootPackage with sub-packages SubPackage1 and SubPackage2
**When** `registerTargetPackage(RootPackage.eINSTANCE)` is called
**Then** only RootPackage is registered
**And** types from SubPackage1 and SubPackage2 are NOT resolvable

#### Scenario: Opt-in sub-package inclusion

**Given** an EPackage RootPackage with sub-packages SubPackage1 and SubPackage2
**And** SubPackage1 has a nested sub-package DeepPackage
**When** `registerTargetPackage(RootPackage.eINSTANCE, true)` is called
**Then** RootPackage is registered
**And** SubPackage1 is registered
**And** SubPackage2 is registered
**And** DeepPackage is registered
**And** types from all four packages can be created

#### Scenario: Type in sub-package resolved after opt-in

**Given** a TransformationContext with `registerTargetPackage(RootPackage.eINSTANCE, true)` called
**And** RootPackage has SubPackage which contains EClass "SubType"
**When** `createTarget(SubType.class)` is called
**Then** SubType is created from SubPackage

#### Scenario: Type in deeply nested package resolved after opt-in

**Given** a TransformationContext with `registerTargetPackage(RootPackage.eINSTANCE, true)` called
**And** RootPackage → SubPackage → DeepPackage hierarchy
**And** DeepPackage contains EClass "DeepType"
**When** `createTarget(DeepType.class)` is called
**Then** DeepType is created from DeepPackage

#### Scenario: setTargetPackage does NOT include sub-packages (backward compat)

**Given** an EPackage RootPackage with sub-packages
**When** `setTargetPackage(RootPackage.eINSTANCE)` is called
**Then** only RootPackage is registered
**And** types from sub-packages are NOT resolvable
**And** previous registrations are cleared

#### Scenario: Ambiguity between parent and sub-package with opt-in

**Given** RootPackage defines EClass "Element"
**And** SubPackage (child of RootPackage) also defines EClass "Element"
**When** `registerTargetPackage(RootPackage.eINSTANCE, true)` is called
**And** `createTarget(Element.class)` is called
**Then** an IllegalArgumentException is thrown
**And** the message indicates the type exists in multiple packages

#### Scenario: getTargetPackages returns only registered packages

**Given** a TransformationContext with `registerTargetPackage(RootPackage.eINSTANCE)` called
**When** `getTargetPackages()` is called
**Then** the list contains only RootPackage (not sub-packages)

#### Scenario: getTargetPackages includes sub-packages with opt-in

**Given** a TransformationContext with `registerTargetPackage(RootPackage.eINSTANCE, true)` called
**When** `getTargetPackages()` is called
**Then** the list contains RootPackage and all sub-packages

---

### Requirement: Package Transformation via Standard Rules

Metamodel packages (e.g., `Namespace`, `Module`, `Package`) MUST be transformable using standard transformation rules, with no special handling.

#### Scenario: Transform source package to target package

**Given** a transformation rule for `Namespace` → `Module`
**And** a source model containing a `Namespace` element
**When** the transformation executes
**Then** a `Module` element is created in the target model
**And** the mapping is recorded in the resolution cache

#### Scenario: Resolve package via equivalent()

**Given** a transformation rule `Namespace` → `Module` has been executed
**And** another rule transforms `SourceClass` → `Entity`
**When** the rule calls `ctx.equivalent(sourceClass.getNamespace(), Module.class)`
**Then** the previously transformed `Module` is returned
**And** no duplicate transformation occurs

#### Scenario: Lazy package transformation via equivalent()

**Given** a transformation rule for `Namespace` → `Module` exists
**And** a source `SourceClass` has a reference to a `Namespace`
**And** the `Namespace` has not been transformed yet
**When** a rule calls `ctx.equivalent(sourceClass.getNamespace(), Module.class)`
**Then** the `Namespace` → `Module` rule executes lazily
**And** the resulting `Module` is returned
**And** subsequent `equivalent()` calls return the cached result

#### Scenario: Hierarchical package transformation

**Given** a source model with `Namespace` containing sub-`Namespace` elements
**And** a transformation rule for `Namespace` → `Module`
**When** the transformation executes
**Then** each `Namespace` is transformed to a `Module`
**And** parent-child relationships are preserved via `equivalent()` resolution

#### Scenario: Package transformation with different structures

**Given** source model has `Project` → `Package` hierarchy (2 levels)
**And** target model has `Application` → `Module` → `SubModule` hierarchy (3 levels)
**When** transformation rules map the structures
**Then** the transformation handles the structural difference
**And** `equivalent()` correctly resolves cross-level references

#### Scenario: EPackage and metamodel package are independent

**Given** a TransformationContext with `UiPackage.eINSTANCE` registered (EMF EPackage)
**And** a source model containing `Namespace` elements (metamodel package)
**When** transforming `Namespace` → `Module`
**Then** `createTarget(Module.class)` uses the registered EMF EPackage
**And** the `Namespace` transformation uses standard rules
**And** these are completely independent mechanisms

---

## Error Messages

### Error: No packages registered

```
IllegalStateException: No target package registered.
Call setTargetPackage() or registerTargetPackage() first.
```

### Error: Type not found in any package

```
IllegalArgumentException: EClass 'UnknownType' not found in any registered target package.
Registered packages: [http://blackbelt.hu/judo/meta/ui, http://blackbelt.hu/judo/meta/ui/data]
```

### Error: Ambiguous type

```
IllegalArgumentException: EClass 'Element' found in multiple packages:
[http://package1, http://package2].
Use createTarget(Class, EPackage) to specify which package to use.
```

### Error: Type not in specified package

```
IllegalArgumentException: EClass 'ClassType' not found in package http://blackbelt.hu/judo/meta/ui
```

---

## Configuration

No new configuration settings are required. Multi-package support is enabled automatically when multiple packages are registered.

---

## Compatibility

### Backward Compatibility

- `setTargetPackage(EPackage)` continues to work as before
- Existing single-package transformations require no changes
- All existing tests pass without modification

### API Additions

| Method | Description |
|--------|-------------|
| `registerTargetPackage(EPackage)` | Register a target package (sub-packages NOT included) |
| `registerTargetPackage(EPackage, boolean)` | Register with opt-in sub-package inclusion |
| `createTarget(Class, EPackage)` | Create element in specific package |
| `create(Class, EPackage)` | Create element without containment in specific package |
| `getTargetPackages()` | Get all registered packages |

### Breaking Changes

**None.** The design ensures full backward compatibility:
- `setTargetPackage()` behavior unchanged (single package, no sub-packages)
- `registerTargetPackage()` adds single package (consistent with `setTargetPackage`)
- Sub-packages only included with explicit `registerTargetPackage(pkg, true)`
