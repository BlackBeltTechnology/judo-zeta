# Fix Orphan Elements from Discriminated Lazy Rules

## Status
IMPLEMENTED

## Selected Approach
**Option A: Defer `addToResource()` for Discriminated Calls** - Implemented

## Summary

When a `@Lazy` rule calls `ctx.addToResource(element)` and is later accessed via `equivalentDiscriminated()`, **both** the original element **and** a clone are added to `Resource.contents`. This creates orphan elements at the resource root that have no container and serve no purpose.

## Problem Statement

### Current Behavior (Bug)

When `equivalentDiscriminated(source, targetType, ruleName, discriminator)` is called:

1. The lazy rule executes, which typically calls `ctx.addToResource(original)` to add the element to the resource
2. The method then **clones** the original for the discriminated version
3. The clone is **also** added to `Resource.contents`
4. Result: **TWO** elements in the resource - the original (orphan, no container) and the clone (may be contained)

### Real-World Impact

In the JUDO framework's PSM-to-UI transformation:

```
Source: OperationParameterType → Target: ActionDefinition

- Button.createActionDefinition() calls equivalentDiscriminated() with discriminator "button/..."
  → Creates original ActionDef + clone1 (contained by Button)

- Action.createActionDefinition() calls equivalentDiscriminated() with discriminator "action/..."
  → Cache miss (different discriminator) → Creates clone2 (orphan at root)

Result per source: 1 original + 2 clones = 3 ActionDefinitions
                   2 orphans (original + Action's clone) at Resource.contents
```

With 1000s of UI components, this causes:
- Memory bloat from orphan elements
- XMI output cluttered with unreferenced elements
- Validation warnings about elements without containers
- Confusion when debugging transformation output

### Root Cause Analysis

The issue stems from two design decisions:

1. **`@Lazy` rules calling `addToResource()`**: Rules add their created elements to the resource as a side effect
2. **`equivalentDiscriminated()` always clones**: Even if the original was just created, it clones and adds another element

The combination means: lazy rule execution + discriminated access = 2 elements.

### EMF Containment Complication

EMF's `EPackage.eClassifiers` (and similar references) have `isResolveProxies=true`, which means:
- Elements **don't auto-remove** from `Resource.contents` when contained
- Even "properly contained" elements appear at root
- This masks the true orphan problem until serialization

## Proposed Solution

### Option A: Defer `addToResource()` for Discriminated Calls (Recommended)

When a lazy rule is triggered via `equivalentDiscriminated()`:
1. Don't add the original to the resource during rule execution
2. Only add the discriminated clone to the resource
3. The original serves as a template and is garbage collected

Implementation:
- Add a thread-local flag `isDiscriminatedExecution` in TransformationContext
- Check this flag in `addToResource()` to skip adding when true
- Set flag before executing lazy rule in `equivalentDiscriminated()`

### Option B: Remove Original After Cloning

After cloning in `equivalentDiscriminated()`:
1. Remove the original from `Resource.contents` if present
2. Only the clone remains

Implementation:
- After `EcoreUtil.copy()`, check if original is in resource
- If so, remove it: `targetResource.getContents().remove(original)`

### Option C: Use @Detached Annotation for Template Rules

Rules intended as templates for discriminated access should use `@Detached`:
- Detached rules don't auto-add to resource
- Caller explicitly adds the clone to appropriate container

This is a documentation/convention change, not a framework change.

## Test Coverage

### OrphanElementDiscriminatorMismatchTest.java (Phase 1)
Tests that verify Option A implementation:
1. Verify EMF proxy-resolving containment behavior
2. Same-discriminator creates exactly 1 element (fixed from 2)
3. Different-discriminators create exactly 2 elements (fixed from 3)
4. Verify orphan accumulation with multiple sources (10 orphans, not 20)

### Esm2UiDiscriminatorMismatchTest.java (Phase 2 - TDD)
Reproduces the esm2ui discriminator mismatch pattern:

1. **differentDiscriminatorsFromSharedHelperCreateOrphan**: Simulates esm2ui pattern where:
   - Shared helper `getActionDefinitionForOperationForm()` called with different context discriminators
   - Button uses "tot/..." discriminator (TransferObjectTable context)
   - Action uses "access/..." discriminator (AccessTable context)
   - Result: 2 ActionDefs (1 contained, 1 orphan) - confirms discriminator mismatch causes orphans

2. **sameDiscriminatorFromSharedHelperNoOrphans**: Demonstrates correct pattern:
   - Same discriminator used by both Button and Action
   - Result: 1 ActionDef (no orphans) - confirms aligned discriminators eliminate orphans

3. **multipleSourcesAccumulateOrphans**: Shows orphan accumulation:
   - 5 sources × 2 clones = 10 ActionDefs
   - 5 orphans (Action's clones)
   - Confirms orphan accumulation at scale

## Impact Assessment

### Breaking Changes
- Option A: None (lazy rule behavior unchanged for non-discriminated calls)
- Option B: Rules relying on original being in resource would break
- Option C: Requires annotation changes to existing rules

### Performance
- Option A: Slight improvement (no unnecessary resource additions)
- Option B: Slight overhead (remove operation after clone)
- Option C: No change

### Migration
- Option A: Transparent (existing code works)
- Option B: Review rules that expect original in resource
- Option C: Add @Detached to template rules

## Recommendation

**Option A** is recommended because:
1. No breaking changes
2. Addresses root cause
3. Slight performance improvement
4. Semantic clarity (discriminated access = clone, not original + clone)

## Post-Implementation Status

**Option A is implemented** and prevents originals from being added during `equivalentDiscriminated()` calls.

However, **orphans still occur** when the same source is accessed via `equivalentDiscriminated()` with **different discriminators**:

```
JUnit Test: OrphanDiscriminatorMismatchTest.testOperationFormCreatesActionDefinitions

  ETL Results:
    - Root elements: 1
    - ActionDefinitions: 7 (all properly contained)

  ZETA Results (with inDiscriminatedExecution fix):
    - Root elements: 3 (2 orphans)
    - ActionDefinitions: 9 (2 orphans at root)
    - Orphan types: ParameterlessCallOperationActionDefinition
```

### Remaining Issue: Discriminator Mismatch

The fix prevents **duplicate originals** but not **duplicate clones**. When:
1. Button transformation calls `equivalentDiscriminated(opForm, "button/xyz")` → Clone A (contained)
2. Action transformation calls `equivalentDiscriminated(opForm, "action/xyz")` → Clone B (orphan)

**Solution Required**: Align discriminators in esm2ui transformation so that Button and Action use the SAME discriminator for the same logical ActionDefinition.

See `judo-tatami-client/agent-docs/ZETA_ORPHAN_TEST_SPECIFICATION.md` for full analysis.

## References

- Phase 1 Test: `transformation-core/src/test/java/.../OrphanElementDiscriminatorMismatchTest.java`
- Phase 2 Test: `transformation-core/src/test/java/.../Esm2UiDiscriminatorMismatchTest.java`
- esm2ui proof test: `judo-tatami-esm2ui/src/test/java/.../OrphanDiscriminatorMismatchTest.java`
- EMF proxy-resolving containment: https://eclipsesource.com/blogs/2015/05/26/emf-dos-and-donts-11/
