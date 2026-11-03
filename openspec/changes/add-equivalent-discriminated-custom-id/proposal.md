## Why

`equivalentDiscriminated()` always computes the clone's XMI ID via `generateDiscriminatedId(baseId, discriminator)`. When the consumer transformation needs a clone with a specific XMI ID (e.g., to match ETL-produced IDs for inherited operation faults), there is no way to override this. The consumer would need to call `setElementId` after the fact, which is fragile — the clone is already cached and indexed under the generated ID, and other rules could read it between creation and override. Adding a first-class `customId` parameter eliminates this window and keeps ID assignment atomic with clone creation.

This closes ~6 XMI ID diffs in the Northwind `FullPipelineComparisonTest` for inherited operation fault parameter types.

## What Changes

- Add a new 5-argument overload: `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)`
- When `customId` is non-null, it replaces the generated discriminated ID in all code paths (CLONE_PRISTINE, CLONE_CURRENT_STATE, DISCRIMINATOR_ONLY, XMI ID lookup)
- The existing 4-argument overload delegates to the new one with `customId=null` (no behavioral change for existing callers)
- Extract a private `resolveDiscriminatedId()` helper to centralize ID computation across the 5 assignment points

## Capabilities

### New Capabilities
- `equivalent-discriminated-custom-id`: Support for explicit XMI ID override on clones produced by `equivalentDiscriminated()`

### Modified Capabilities
None — this is a purely additive API extension. Existing behavior is unchanged when `customId` is null.

## Impact

- **Affected code**: `TransformationContext.equivalentDiscriminated()` — new overload + internal refactoring of ID computation
- **APIs**: New public method signature; existing signature unchanged
- **Dependencies**: None — uses existing `setElementId`/`setElementIdInternal` infrastructure
- **Consumers**: `judo-tatami` / `judo-tatami-client` ESM-to-UI transformations can pass explicit IDs for inherited operation fault clones
