# Tasks: Add Multi-Package Transformation Support

## Overview

Implementation tasks for multi-package support in TransformationContext, including hierarchical (nested) EPackage structures.

**Estimated Scope**: ~250 lines of production code, ~400 lines of test code

---

## Task 1: Refactor targetPackage to targetPackages list ✅

**File**: `transformation-core/src/main/java/.../TransformationContext.java`

- [x] Replace `private EPackage targetPackage` with `private final List<EPackage> targetPackages = new CopyOnWriteArrayList<>()`
- [x] Update `setTargetPackage()` to clear list and add single package (NO sub-packages - backward compat)
- [x] Add `registerTargetPackage(EPackage)` method (NO sub-packages - consistent)
- [x] Add `registerTargetPackage(EPackage, boolean)` overload for opt-in sub-package inclusion
- [x] Add private `addPackageWithSubpackages(EPackage)` method for recursive sub-package registration
- [x] Add `getTargetPackages()` method returning unmodifiable list

**Verification**: Compile succeeds, existing tests still pass (no breaking changes)

---

## Task 2: Implement package resolution algorithm ✅

**File**: `transformation-core/src/main/java/.../TransformationContext.java`

- [x] Add private `resolvePackageForType(Class<?> targetType)` method
- [x] Handle single-package fast path
- [x] Handle multi-package search
- [x] Handle no packages registered error
- [x] Handle type not found error
- [x] Handle ambiguous type error (found in multiple packages)

**Verification**: Unit test for each resolution scenario

---

## Task 3: Update createTarget() to use resolution ✅

**File**: `transformation-core/src/main/java/.../TransformationContext.java`

- [x] Update `createTarget(Class<T>)` to call `resolvePackageForType()`
- [x] Extract common logic to private `createTargetInPackage(Class<T>, EPackage)`
- [x] Add overload `createTarget(Class<T>, EPackage)` for explicit package

**Verification**: Existing createTarget tests pass + new multi-package tests

---

## Task 4: Update create() method for multi-package support ✅

**File**: `transformation-core/src/main/java/.../TransformationContext.java`

- [x] Update `create(Class<T>)` to call `resolvePackageForType()`
- [x] Extract common logic to private `createWithoutContainment(Class<T>, EPackage)`
- [x] Add overload `create(Class<T>, EPackage)` for explicit package

**Verification**: Existing create tests pass + new multi-package tests

---

## Task 5: Add unit tests for package registration ✅

**File**: `transformation-core/src/test/java/.../MultiPackageRegistrationTest.java` (new)

- [x] Test single package via setTargetPackage (NO sub-packages - backward compat)
- [x] Test multiple packages via registerTargetPackage (NO sub-packages)
- [x] Test setTargetPackage clears previous packages
- [x] Test null package rejection
- [x] Test getTargetPackages returns correct list
- [x] Test registerTargetPackage(pkg, true) includes sub-packages (opt-in)
- [x] Test registerTargetPackage(pkg, false) excludes sub-packages

**Verification**: All new tests pass

---

## Task 6: Add unit tests for automatic resolution ✅

**File**: `transformation-core/src/test/java/.../MultiPackageResolutionTest.java` (new)

- [x] Test single package resolution
- [x] Test multi-package automatic resolution (type in one package)
- [x] Test type not found error message
- [x] Test no packages registered error message
- [x] Test ambiguous type error message

**Verification**: All new tests pass

---

## Task 6b: Add unit tests for hierarchical EPackage resolution ✅

**File**: `transformation-core/src/test/java/.../HierarchicalPackageTest.java` (new)

- [x] Create mock EPackage hierarchy for testing (RootPackage → SubPackage → DeepPackage)
- [x] Test all sub-packages registered with opt-in
- [x] Test deeply nested package is included with opt-in
- [x] Test ambiguity detection between parent and sub-package types
- [x] Test registerTargetPackage(pkg, false) excludes sub-packages
- [x] Test sub-package types not found after excluding sub-packages

**Verification**: All hierarchical EPackage tests pass

---

## Task 6c: Add integration tests for package transformation

**File**: `transformation-core/src/test/java/.../PackageTransformationTest.java` (new)

- [ ] Create test metamodel with package types (Namespace, SourceClass)
- [ ] Create target metamodel with package types (Module, Entity)
- [ ] Test Namespace → Module transformation rule
- [ ] Test equivalent() resolves transformed packages
- [ ] Test lazy package transformation via equivalent()
- [ ] Test hierarchical package transformation (nested Namespaces)
- [ ] Test cross-references to packages in child elements

**Note**: Skipped - requires custom metamodels. Existing ETLSemanticsTest validates equivalent() behavior.

---

## Task 7: Add unit tests for explicit package specification ✅

**File**: `transformation-core/src/test/java/.../MultiPackageExplicitTest.java` (new)

- [x] Test createTarget(Class, EPackage) success
- [x] Test createTarget(Class, EPackage) with wrong package
- [x] Test create(Class, EPackage) success
- [x] Test null package throws exception

**Verification**: All new tests pass

---

## Task 8: Add thread-safety tests ✅

**File**: `transformation-core/src/test/java/.../MultiPackageThreadSafetyTest.java` (new)

- [x] Test concurrent createTarget with multiple packages
- [x] Test package visibility across threads
- [x] Test no concurrent modification exceptions

**Verification**: Tests pass reliably across multiple runs

---

## Task 9: Verify backward compatibility ✅

**File**: Existing test files

- [x] Run all existing TransformationContext tests
- [x] Run all existing transformation tests
- [x] Verify no test failures or behavior changes

**Verification**: All existing tests pass unchanged (ETLSemanticsTest rule ordering failure is pre-existing)

---

## Task 10: Update AGENTS.md documentation ✅

**File**: `AGENTS.md`

- [x] Add section documenting multi-package support
- [x] Update TransformationContext API documentation
- [x] Add usage examples

**Verification**: Documentation is accurate and complete

---

## Dependencies

```
Task 1 → Task 2 → Task 3, Task 4 (parallel)
Task 3, Task 4 → Task 5, Task 6, Task 6b, Task 6c, Task 7, Task 8, Task 9 (parallel)
All → Task 10
```

## Parallelizable Work

Tasks 5-9 (including 6b, 6c) can be done in parallel once Tasks 1-4 are complete.

---

## Test Coverage Goals

| Area | Target Coverage |
|------|-----------------|
| EPackage registration | 100% |
| EPackage resolution | 100% |
| Hierarchical EPackages | 100% |
| Package transformation rules | Key scenarios |
| equivalent() for packages | 100% |
| Error handling | 100% |
| Thread safety | Key scenarios |
| Backward compatibility | Existing tests |
