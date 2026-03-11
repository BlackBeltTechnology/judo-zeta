## Context

ETL's `equivalentDiscriminated` (in `id.eol`) has mutation-propagation semantics: the first caller for a given `(source, ruleName)` gets the original object directly, subsequent callers get `ecoreUtil.copy(f)` of the **current state** of that original. ZETA's implementation always clones from the pristine original via `DeferredEObject.unwrapWithPendingValues(original)` + `EcoreUtil.copy()`.

This difference causes ~80 diffs in action name suffixes in the rackinspect model. Action names are used as map keys, parsed via `split("::")`, and generate i18n keys — so exact suffix counts matter.

Current code path (TransformationContext.java lines 2369-2374):
```java
EObject unwrappedOriginal = DeferredEObject.unwrapWithPendingValues(original);
T clone = (T) EcoreUtil.copy(unwrappedOriginal);
```

## Goals / Non-Goals

**Goals:**
- Support ETL-compatible clone-from-current-state semantics in `equivalentDiscriminated()`
- Maintain backward compatibility — default behavior unchanged (`CLONE_PRISTINE`)
- Fail fast when `CLONE_CURRENT_STATE` is used with parallel execution (non-deterministic mutation order)
- Correctly handle the `inDiscriminatedExecution` flag so the original is added to resource when first caller gets it directly

**Non-Goals:**
- Per-rule strategy annotation (future enhancement if needed)
- Supporting `CLONE_CURRENT_STATE` in parallel execution mode
- Changes to discriminator resolution or cache modes — the new strategy is orthogonal
- Changes to the consumer side (judo-tatami-client) — that's a separate change

## Decisions

### Decision 1: Strategy enum on TransformationContext (global setting)

**Choice**: A global `EquivalentDiscriminatedStrategy` field on `TransformationContext` with getter/setter.

**Alternatives considered**:
- Per-rule `@DiscriminatedStrategy` annotation — More granular but requires annotation processing changes and reading rule metadata during clone. Overkill since all current use cases would use the same strategy per transformation.
- Per-call parameter — Would require changing the `equivalentDiscriminated()` signature, breaking all callers.

**Rationale**: Simplest approach. A transformation either uses ETL semantics or it doesn't. Per-rule can be added later without breaking changes.

### Decision 2: OriginalTracker keyed on (source, ruleName)

**Choice**: Track first-call state using `(System.identityHashCode(source), ruleName)` as the key.

**Alternatives considered**:
- Key on output object (ETL's `__originalMap.containsKey(f)`) — Would require different lookup pattern; the resolution cache already tracks source→original mappings, so keying on source is more natural in ZETA.

**Rationale**: `(source, ruleName)` uniquely identifies the original in ZETA (confirmed by `resolutionCache.getByRule(source, ruleName)` at line 2128). Functionally equivalent to ETL's output-keyed approach.

### Decision 3: Fail-fast for parallel + CLONE_CURRENT_STATE

**Choice**: Throw `IllegalStateException` in `equivalentDiscriminated()` when `CLONE_CURRENT_STATE` is active and `deferredWritesEnabled` is true.

**Alternatives considered**:
- Synchronized cloning from current state in parallel mode — Complex, non-deterministic suffix order, `DeferredEObject` list operations may not be captured correctly.
- Silently falling back to `CLONE_PRISTINE` — Hidden behavior change, debugging nightmare.

**Rationale**: ETL is sequential. Mutation propagation requires deterministic ordering. Parallel execution makes ordering non-deterministic. Better to fail clearly than produce subtly wrong output.

### Decision 4: Suppress `inDiscriminatedExecution` for CLONE_CURRENT_STATE

**Choice**: When `CLONE_CURRENT_STATE` is active, do NOT set `inDiscriminatedExecution = true` during rule execution. This allows the original to be added to the resource via `addToResource()`.

**Rationale**: In `CLONE_CURRENT_STATE` mode, the first caller gets the original directly (like DISCRIMINATOR_ONLY mode). The original IS the final element — it needs to be in the resource. Suppressing `addToResource` would orphan it. This matches ETL's behavior where the original is always in the resource.

### Decision 5: Clone section branching location

**Choice**: Branch after line 2267 (after `effectiveDiscriminator == null` check), before the existing DISCRIMINATOR_ONLY and SOURCE_BASED paths.

The flow becomes:
```
effectiveDiscriminator == null? → return original
CLONE_CURRENT_STATE? → first-call/clone-current logic (new)
DISCRIMINATOR_ONLY cache? → existing path (return original with disc ID)
SOURCE_BASED cache? → existing path (clone pristine)
```

**Rationale**: The `CLONE_CURRENT_STATE` strategy operates in SOURCE_BASED cache mode. Placing it before the DISCRIMINATOR_ONLY check keeps the paths cleanly separated. The DISCRIMINATOR_ONLY path already has its own semantics that don't need the strategy enum.

## Risks / Trade-offs

**[Risk: Mutation timing in sequential mode]** → Even in sequential mode, the first caller must fully mutate the original before returning control flow to the next caller. In ZETA's executor, rules for the same source may interleave. → **Mitigation**: The callers (e.g., `createOperationFormTableRowCallAction`) mutate the action name synchronously within the same method call before returning. No interleaving within a single `equivalentDiscriminated` + mutate sequence.

**[Risk: OriginalTracker memory]** → The tracker holds entries for the lifetime of the transformation. → **Mitigation**: Cleared when `TransformationContext` is reset. Entry count is bounded by (source count × rule count), which is already bounded by the resolution cache size.

**[Risk: DISCRIMINATOR_ONLY + CLONE_CURRENT_STATE interaction]** → These are logically independent dimensions but share some overlapping semantics (both can return original directly). → **Mitigation**: DISCRIMINATOR_ONLY path is checked first and returns early, never reaching the CLONE_CURRENT_STATE branch. They don't interact in practice.

**[Trade-off: Sequential-only restriction]** → Forces sequential execution for transformations using this strategy. → **Acceptable**: ETL is sequential. The esm2ui transformation that needs this is already configured. Performance impact is limited to the specific transformation, not framework-wide.
