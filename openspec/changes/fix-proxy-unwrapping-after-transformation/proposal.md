# Proposal: Fix Proxy Unwrapping After Transformation

**Change ID**: fix-proxy-unwrapping-after-transformation
**Status**: Draft
**Created**: 2026-01-05

## Problem Statement

After parallel transformation completes, deferred containment proxies (`$Proxy26` objects) remain in the model instead of being replaced with the real `EObject` implementations (e.g., `EOperationImpl`).

### Error Details

```
java.lang.ClassCastException: class jdk.proxy2.$Proxy26 cannot be cast to
class org.eclipse.emf.ecore.impl.EOperationImpl
```

### Complete Call Stack

```
Psm2AsmExternalModelTest.testExternalModel()
    │
    ▼
Psm2AsmWork.executeZetaTransformation()
    │
    ├── TransformationExecutor.transform() ← Zeta creates proxies
    │
    ▼
Psm2AsmZetaTransformation.postProcess()
    │
    ▼
AsmUtils.enrichWithAnnotations()
    │
    ▼
AsmUtils.getAllOperationNames()
    │
    ▼
EClassImpl.getEAllOperations()          ← EMF internal code
    │
    ▼
════════════════ CRASH ════════════════
```

### EMF Internal Code (EClassImpl.java:976)

```java
public EList<EOperation> getEAllOperations() {
    if (eAllOperations == null) {
        // ...
        for (EOperation operation : eOperations) {
            // EMF internally casts to EOperationImpl for performance
            EOperationImpl impl = (EOperationImpl) operation;  // ← CRASH!
            // ...
        }
    }
    return eAllOperations;
}
```

## Root Cause Analysis

### What Zeta Does (Deferred Containment)

During transformation, Zeta creates JDK dynamic proxies instead of real objects:

```java
// During transformation, Zeta creates proxies instead of real objects
EOperation operation = ctx.createTarget(EOperation.class);
// Returns: jdk.proxy2.$Proxy26 implementing EOperation interface

// Proxy is added to EClass.eOperations
eClass.getEOperations().add(operation);  // Adds proxy, not real object
```

### Why EMF Fails

EMF's internal code casts elements to `*Impl` classes:

```java
// EMF expects concrete implementation classes, not interface proxies
EOperationImpl impl = (EOperationImpl) operation;  // ClassCastException!
```

The proxy implements `EOperation` (interface) but **NOT** `EOperationImpl` (class).

### Object Type Comparison

| Aspect          | Expected by EMF                          | Actual (with Proxy) |
|-----------------|------------------------------------------|---------------------|
| Type            | EOperationImpl                           | jdk.proxy2.$Proxy26 |
| Implements      | EOperation                               | EOperation ✓        |
| Extends         | EOperationImpl                           | ❌ No               |
| Class hierarchy | EOperationImpl → ETypedElementImpl → ... | Proxy → Object      |

**Fundamental incompatibility**: JDK proxies implement interfaces but **cannot extend** EMF's implementation classes.

### Affected EMF Methods

EMF internally casts to implementation classes in several places:

| EMF Class    | Method                      | Expected Type          | Fails with Proxy |
|--------------|-----------------------------|------------------------|------------------|
| EClassImpl   | getEAllOperations()         | EOperationImpl         | ✓ Fails          |
| EClassImpl   | getEAllStructuralFeatures() | EStructuralFeatureImpl | Likely fails     |
| EClassImpl   | getEAllSuperTypes()         | EClassImpl             | Likely fails     |
| EPackageImpl | getEClassifiers()           | EClassifierImpl        | Likely fails     |

### Transformation Timeline

```
PHASE 1: Rule Execution (parallel)
─────────────────────────────────────────────────────────────────────────────
    ctx.createTarget(EOperation.class)
        │
        ▼
    Zeta creates: Proxy26 wrapping real EOperationImpl
        │
        ▼
    eClass.getEOperations().add(proxy)  ← Proxy stored in model

PHASE 2: After transform() completes
─────────────────────────────────────────────────────────────────────────────
    TransformationExecutor.transform() returns
        │
        ▼
    ════════════════════════════════════════════════════════
    HERE: Zeta should unwrap all proxies to real objects
          Replace Proxy26 with actual EOperationImpl
    ════════════════════════════════════════════════════════
        │
        ▼
    BUT: Proxies remain in the model (BUG!)

PHASE 3: postProcess()
─────────────────────────────────────────────────────────────────────────────
    AsmUtils.enrichWithAnnotations()
        │
        ▼
    EClassImpl.getEAllOperations()
        │
        ▼
    (EOperationImpl) proxy  ← CRASH!
```

### Current Code Analysis

The `TransformationExecutor.transformWithStaging()` method performs:
1. `commitDeferredOperations()` - replays deferred EMF writes
2. `commitStagedElements()` - adds staged elements to Resource

But it does **NOT** call `unwrapAllProxiesInModel()` after commit. This method exists in `TransformationContext` and correctly unwraps all proxy references, but it's never invoked by the executor.

## Proposed Solution

### Option A: Unwrap Proxies After Transform (Recommended)

Call `unwrapAllProxiesInModel()` after the commit phase in `transformWithStaging()`.

This is the minimal fix - the method already exists and works correctly.

```java
// In TransformationExecutor.transformWithStaging()
context.commitDeferredOperations();
context.commitStagedElements();

// Add this call:
context.unwrapAllProxiesInModel();
```

### Option B: Don't Use Proxies for Target Objects

Create real EMF objects instead of proxies, track deferred operations separately:

```java
public <T extends EObject> T createTarget(Class<T> type) {
    // Create real EMF object, not proxy
    T real = factory.create(type);

    // Track for deferred operations separately
    deferredOperations.register(real);

    return real;  // Return real object, not proxy
}
```

**Pros**: No unwrap phase needed
**Cons**: Significant refactoring, changes parallel phase semantics

### Option C: Use Proxy Only for Collection Operations

Wrap collections for deferred add operations, not the objects themselves:

```java
public <T extends EObject> T createTarget(Class<T> type) {
    T real = factory.create(type);  // Real object

    // Wrap collections for deferred add operations
    wrapCollections(real);

    return real;
}
```

**Pros**: Objects are always real
**Cons**: Complex collection wrapping logic

### Why Option A (Unwrap After Commit)

| Approach | Pros | Cons |
|----------|------|------|
| **Option A: Unwrap after commit** (chosen) | Minimal change, method exists, tested | Extra pass over model |
| Option B: Real objects, track separately | No unwrap needed | Major refactoring |
| Option C: Wrap collections only | Objects always real | Complex wrapping logic |

1. **Simplest fix**: Single method call after commit
2. **Complete**: Catches all proxies regardless of how they were stored
3. **Safe**: Parallel phase is complete, no thread safety concerns
4. **Existing method**: `unwrapAllProxiesInModel()` is already tested and works

## Implementation Changes

1. **TransformationExecutor.transformWithStaging()**: Add call to `context.unwrapAllProxiesInModel()` after `commitStagedElements()`
2. Add metrics tracking for unwrap phase

## Test Case

```java
@Test
void testProxiesUnwrappedAfterTransform() {
    // Setup transformation
    TransformationExecutor executor = ...;
    executor.transform();

    // After transform, verify no proxies remain
    for (EObject obj : targetResourceSet.getAllContents()) {
        assertFalse(Proxy.isProxyClass(obj.getClass()),
            "Proxy not unwrapped: " + obj);

        // Check all contained elements
        for (EObject child : obj.eContents()) {
            assertFalse(Proxy.isProxyClass(child.getClass()),
                "Child proxy not unwrapped: " + child);
        }
    }
}
```

## Success Criteria

1. No proxy objects (`$ProxyN`) remain in model after parallel transformation
2. `EClassImpl.getEAllOperations()` returns `EOperationImpl` objects
3. `EClassImpl.getEAllStructuralFeatures()` returns `EStructuralFeatureImpl` objects
4. All EMF internal iterators work correctly
5. All existing tests pass
6. PSM2ASM transformation succeeds without ClassCastException

## Summary

| Issue           | Cause                        | Fix Required                           |
|-----------------|------------------------------|----------------------------------------|
| Proxy in model  | createTarget() returns proxy | Return real object or unwrap after     |
| EMF cast fails  | EMF casts to *Impl classes   | Proxies can't extend impl classes      |
| No unwrap phase | Missing proxy resolution     | Add unwrapAllProxies() after transform |

The fundamental issue: Zeta's deferred containment uses JDK proxies which implement interfaces but cannot extend EMF's implementation classes. EMF internally relies on concrete implementation types, making proxy-based approaches incompatible without an unwrap phase.

## Related Issues

This is issue #4 from the SNAPSHOT issues summary:
```
| #   | Error                                  | Location                       | Root Cause                            |
|-----|----------------------------------------|--------------------------------|---------------------------------------|
| 4   | Proxy cannot be cast to EOperationImpl | EClassImpl.getEAllOperations() | Proxies not unwrapped after transform |
```

## Related Specs

- `parallel-transformation` - Thread-safe parallel execution requirements
