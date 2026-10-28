## Context

`TransformationContext.equivalentDiscriminated()` is the framework method that produces discriminated clones of transformation targets. It supports three strategies (CLONE_PRISTINE, CLONE_CURRENT_STATE, DISCRIMINATOR_ONLY) and generates XMI IDs via `generateDiscriminatedId(baseId, discriminator)`. The method is ~540 lines with 5 distinct code paths that each compute and assign the discriminated ID independently.

Consumer transformations (judo-tatami ESM-to-UI) need to produce clones with specific XMI IDs that match ETL output — particularly for inherited operation fault parameter types where the ID must be based on the child ClassType's ID rather than the standard `generateStructuredId(source, ruleName)` base.

## Goals / Non-Goals

**Goals:**
- Enable callers to specify an explicit XMI ID for discriminated clones
- Maintain full backward compatibility — existing 4-arg callers see zero change
- Keep ID assignment atomic with clone creation (no window for stale reads)
- Support all three cloning strategies (CLONE_PRISTINE, CLONE_CURRENT_STATE, DISCRIMINATOR_ONLY)

**Non-Goals:**
- Changing cache key semantics — cache still uses `(source, ruleName, discriminator)`
- Supporting custom IDs for non-discriminated `equivalent()` calls
- Resolving the DEFERRED clone-timing problem (dataElement/targetDataElement) — that's a separate architectural change

## Decisions

### Decision 1: New 5-arg overload (not builder/options object)

**Choice:** Add `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)`.

**Alternatives considered:**
- *Options/builder object*: Cleaner for future extensibility but over-engineered for a single optional parameter. Would also change the call pattern for all callers.
- *Post-hoc `setElementId`*: Works today but the clone is briefly visible with a "wrong" ID between return and override. Also requires consumers to understand the internal ID lifecycle.

**Rationale:** A 5th parameter is minimal, explicit, and the internal delegation (`4-arg → 5-arg with null`) is trivial. If we need more options later, we can introduce a builder then.

### Decision 2: Extract `resolveDiscriminatedId()` private helper

**Choice:** Centralize the 5 ID-computation points into one helper method.

```java
private String resolveDiscriminatedId(
    String customId, EObject source, String ruleName,
    String discriminator, EObject original
) {
    if (customId != null) return customId;
    if (useStructuredIds) {
        return generateDiscriminatedId(
            generateStructuredId(source, ruleName), discriminator);
    }
    return getElementId(original) + "/(discriminator/" + discriminator + ")";
}
```

**Rationale:** Reduces duplication and ensures the `customId` override is applied consistently across CLONE_PRISTINE, CLONE_CURRENT_STATE (first + subsequent), DISCRIMINATOR_ONLY, and XMI lookup paths.

**Note:** The helper has a `null`-safe `original` parameter — path [1] (XMI lookup) and CLONE_CURRENT_STATE first-caller path don't have `original` available at ID-computation time. For those paths, `original` is null and the legacy fallback branch is unreachable (structured IDs are always enabled in practice).

### Decision 3: XMI lookup path uses customId directly

**Choice:** When `customId != null` and `useStructuredIds`, look up `findByXmiId(customId, targetType)` instead of computing the discriminated ID.

**Rationale:** Ensures idempotency — if a clone was already created with the custom ID (e.g., in a prior transformation run), the lookup finds it without re-creating.

### Decision 4: CLONE_CURRENT_STATE baseId registration skipped when customId provided

**Choice:** When `customId != null`, skip `originalTracker.registerBaseId()` and `originalTracker.getBaseId()`. Each caller provides its own ID.

**Rationale:** `baseId` storage enables subsequent callers to derive their discriminated IDs from the first caller's base. With `customId`, each call is self-contained — the ID is explicitly specified, not derived.

## Risks / Trade-offs

- **[Misuse: same customId for different discriminators]** → A caller could pass the same `customId` for two different discriminator values, causing ID collision. Mitigated by existing collision detection in `setElementIdInternal()` which logs `XMI ID COLLISION DETECTED`.
- **[Testing complexity]** → Need to test customId across all 3 strategies. Mitigated by focused unit tests per strategy + the existing dual-comparison test infrastructure in the consumer.
- **[resolveDiscriminatedId null-original edge case]** → The legacy (non-structured-ID) path calls `getElementId(original)` which NPEs if `original` is null. In practice, `useStructuredIds` is always true for Zeta transformations. Add a null-check guard for safety.
