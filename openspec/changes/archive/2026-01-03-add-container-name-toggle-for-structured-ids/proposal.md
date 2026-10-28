# Proposal: Add Element Name Toggle for Structured IDs

**Change ID:** add-container-name-toggle-for-structured-ids
**Status:** Applied
**Created:** 2026-01-03
**Updated:** 2026-01-03

## Problem Statement

When using structured XMI IDs, the framework includes the source element's name as a prefix in the generated ID. This prefix is not always desired and can cause compatibility issues with existing tooling.

### Current Behavior

```
xml/(source/_MaJNYeiFEfCKeN0VGO_Tbg)/XMLType
 │      │                            │
 │      │                            └── rule name
 │      └── (alias/sourceId)
 └── element name (always included when available)
```

### Required Behavior

The default should exclude the element name for cleaner IDs:

```
(esm/_MaJNYeiFEfCKeN0VGO_Tbg)/XMLType
    │                         │
    └── (alias/sourceId)      └── rule name
```

### Use Case

The Tatami project uses Zeta transformations and requires ID formats compatible with their existing tooling. Their expected format excludes the element name prefix.

## Proposed Solution

Add a configuration option `setIncludeElementNameInStructuredIds(boolean)` to `TransformationContext` that controls whether the element name is included in generated structured IDs.

### API

```java
// Include element name in structured IDs (default: false)
ctx.setIncludeElementNameInStructuredIds(true);

// Default behavior (no configuration needed)
ctx.registerResource("esm", esmResourceSet);
ctx.setPreferredSourceAlias("esm");
// Result: (esm/_id)/RuleName

// Opt-in to include element name
ctx.setIncludeElementNameInStructuredIds(true);
// Result: ElementName/(esm/_id)/RuleName
```

### Default Behavior

- `includeElementNameInStructuredIds = false` (default) - cleaner IDs without element name prefix
- Opt-in to include element name when traceability is desired

## Implementation Scope

This is a minimal, focused change:

1. Add boolean field `includeElementNameInStructuredIds` with default `false`
2. Add setter/getter methods
3. Modify `getSourcePath()` to respect the flag
4. Add tests
5. Update documentation

## Success Criteria

1. New API method `setIncludeElementNameInStructuredIds(boolean)` available
2. Default (`false`) excludes element name from structured IDs
3. Setting to `true` includes element name for traceability
4. All existing tests pass (may need updates for new default)
5. Documentation updated

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Breaking existing IDs | Projects relying on element name prefix need to set `true` explicitly |
| ID collisions without element name | Alias + source ID should be unique; element name was redundant |
