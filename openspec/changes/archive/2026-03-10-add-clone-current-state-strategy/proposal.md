## Why

ETL's `equivalentDiscriminated` (defined in `id.eol`) clones from the **current (mutated) state** of the original object, while ZETA always clones from the **pristine (unmodified) original**. This causes ~80 diffs in action name suffixes across the rackinspect model. Downstream code depends on the exact suffix accumulation pattern because action names are used as map keys, parsed via `split("::")`, and generate i18n keys and URL paths.

## What Changes

- Add `EquivalentDiscriminatedStrategy` enum with two modes: `CLONE_PRISTINE` (current default) and `CLONE_CURRENT_STATE` (ETL-compatible)
- Add `OriginalTracker` helper class to track first-call semantics per `(source, ruleName)` pair
- Modify `equivalentDiscriminated()` clone section in `TransformationContext` to branch on strategy:
  - `CLONE_PRISTINE`: existing behavior (always clone from pristine original)
  - `CLONE_CURRENT_STATE`: first caller gets original object directly (no clone), subsequent callers get clones of current (mutated) state
- **BREAKING**: When `CLONE_CURRENT_STATE` is active, `inDiscriminatedExecution` must NOT suppress `addToResource` for the original (first caller's element must be in the resource)
- Add fail-fast validation: `CLONE_CURRENT_STATE` + `deferredWritesEnabled` (parallel mode) throws `IllegalStateException` — ETL semantics require sequential execution for deterministic mutation ordering
- Add unit tests for the new strategy

## Capabilities

### New Capabilities
- `clone-current-state`: ETL-compatible cloning strategy for `equivalentDiscriminated()` where the first caller receives the original object and subsequent callers receive clones of its current (possibly mutated) state

### Modified Capabilities
- `rule-execution`: New requirements for `equivalentDiscriminated` behavior when `CLONE_CURRENT_STATE` strategy is active (first-call-gets-original semantics, `inDiscriminatedExecution` flag interaction, parallel mode restriction)

## Impact

- **TransformationContext.java**: Core clone logic in `equivalentDiscriminated()` (~lines 2217-2445) gains strategy branching
- **New files**: `EquivalentDiscriminatedStrategy.java`, `OriginalTracker.java` in `transformation-core`
- **Downstream consumers** (judo-tatami-client): Can enable `CLONE_CURRENT_STATE` to match ETL behavior and revert workarounds in `TabularReferenceFieldActionUtils.java`
- **No breaking API changes**: Default strategy remains `CLONE_PRISTINE`, existing behavior unchanged
- **Orthogonal to cache modes**: Works with both `SOURCE_BASED` and `DISCRIMINATOR_ONLY` cache modes

## Test Plan

14 unit tests using generic EMF EClass/EObject types from EcorePackage (no domain-specific types needed).

### Test Setup (shared)

```java
@Lazy @TransformRule(name = "TestRule")
TransformFunction<EClass, EClass> testRule = (source, ctx) -> {
    EClass target = ctx.createTarget(EClass.class);
    target.setName("base");
    return target;
};

@Lazy @Detached @TransformRule(name = "TestDetachedRule")
TransformFunction<EClass, EClass> testDetachedRule = (source, ctx) -> {
    EClass target = ctx.createTarget(EClass.class);
    target.setName("detached-base");
    return target;
};
```

### Tests

| # | Test | Strategy | Assert | Priority |
|---|------|----------|--------|----------|
| 1 | First caller gets original | CLONE_CURRENT_STATE | `assertSame(result, original)` | **CRITICAL** |
| 2 | Second caller gets clone | CLONE_CURRENT_STATE | `assertNotSame(resultA, resultB)`, name = "base" | **CRITICAL** |
| 3 | Mutation propagation (1→2) | CLONE_CURRENT_STATE | resultA mutated to "base::s1", resultB.getName() = "base::s1" | **CRITICAL** |
| 4 | Clones copy from original only | CLONE_CURRENT_STATE | resultC cloned from original (not resultB), name = "base::s1" not "base::s1::s2" | **CRITICAL** |
| 5 | Same discriminator = cache hit | CLONE_CURRENT_STATE | `assertSame(resultA, resultA2)` | HIGH |
| 6 | First caller XMI ID | CLONE_CURRENT_STATE | ID contains "/(discriminator/discA)" | HIGH |
| 7 | Clone XMI ID unique | CLONE_CURRENT_STATE | resultA and resultB have different discriminated IDs | HIGH |
| 8 | Fail-fast on deferred writes | CLONE_CURRENT_STATE + parallel | Throws `IllegalStateException` | **CRITICAL** |
| 9 | CLONE_PRISTINE unchanged | CLONE_PRISTINE | resultB.getName() = "base" (no mutation from resultA) | **CRITICAL** |
| 10 | Different sources independent | CLONE_CURRENT_STATE | sourceY's original unaffected by sourceX mutations | HIGH |
| 11 | @Detached not in Resource | CLONE_CURRENT_STATE | resource.getContents() excludes detached results | MEDIUM |
| 12 | Non-detached in Resource | CLONE_CURRENT_STATE | resource.getContents() contains both resultA and resultB | MEDIUM |
| 13 | Multi-property propagation | CLONE_CURRENT_STATE | name + isAbstract mutations both propagate to clone | HIGH |
| 14 | List mutation propagation | CLONE_CURRENT_STATE | eStructuralFeatures added to original appear in clone (deep copy) | HIGH |

**Must-pass for release**: Tests 1, 2, 3, 4, 8, 9 (6 tests).
