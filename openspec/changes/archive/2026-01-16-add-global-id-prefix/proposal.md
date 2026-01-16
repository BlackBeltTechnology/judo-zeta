# Change: Add Global ID Prefix Configuration

## Why

ETL prefixes ALL generated XMI IDs with a global context variable (`actorType.name`), resulting in IDs like:
```
GenericUser/(esm/_xxx)/Application
```

ZETA's current `includeElementNameInStructuredIds` option uses the **source element's own name**, which varies per element:
```
SomeAccessName/(esm/_xxx)/Application  ← Different prefix per element!
```

This prevents ETL compatibility when the transformation needs a single global prefix for all IDs.

## What Changes

- Add new configuration option `setGlobalIdPrefix(String prefix)` to TransformationContext
- When set, ALL structured XMI IDs are prefixed with this value
- **Mutually exclusive** with `includeElementNameInStructuredIds` - throws exception if both are enabled
- Must be configured **before any rules execute** (initialization phase only)
- Format: `<globalPrefix>/(alias/sourceId)/RuleName`

**Example**:
```java
context.setGlobalIdPrefix("GenericUser");
// Produces: GenericUser/(esm/_abc123)/Application
```

## Impact

- Affected specs: `etl-patterns` (extends "Configurable Element Name in Structured IDs")
- Affected code:
  - `TransformationContext.java:getSourcePath()` - check for global prefix
  - `TransformationContext.java` - add field and setter/getter with validation

## Non-Goals

- This does NOT change the behavior of `includeElementNameInStructuredIds`
- This does NOT affect discriminated IDs (they append to the base ID)

## Constraints

- `globalIdPrefix` and `includeElementNameInStructuredIds` are mutually exclusive (exception thrown)
- Configuration must occur before transformation starts
- Empty string `""` treated same as `null` (feature disabled)
