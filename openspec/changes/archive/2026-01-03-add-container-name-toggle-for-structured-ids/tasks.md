# Tasks: Add Element Name Toggle for Structured IDs

## Implementation Tasks

- [x] **1. Add configuration field and accessors**
  - Add `private volatile boolean includeElementNameInStructuredIds = false;`
  - Add `setIncludeElementNameInStructuredIds(boolean)` method
  - Add `isIncludeElementNameInStructuredIds()` method
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java`

- [x] **2. Modify getSourcePath() to respect the flag**
  - Check `includeElementNameInStructuredIds` before adding element name
  - When `false` (default), return only `(alias/sourceId)` format
  - When `true`, include element name prefix
  - *Location:* `transformation-core/src/main/java/.../TransformationContext.java` (lines 2186-2205)

- [x] **3. Add unit tests**
  - Test default behavior (element name excluded)
  - Test `setIncludeElementNameInStructuredIds(true)` includes element name
  - Test combined with `setPreferredSourceAlias()`
  - *Location:* `transformation-core/src/test/java/.../StructuredIdTest.java`

- [x] **4. Update existing tests for new default**
  - Review tests that assert on structured ID format
  - Update expected values or set `includeElementNameInStructuredIds(true)` where needed
  - *Dependency:* 1, 2

- [x] **5. Update documentation**
  - Add API documentation in `docs/transformation/reference/transformation-context.md`
  - Add example in `agent-docs/CONTEXT-API.md`
  - *Dependency:* 1, 2, 3, 4

## Verification Criteria

1. Default behavior produces IDs without element name prefix: `(alias/_id)/RuleName`
2. `ctx.setIncludeElementNameInStructuredIds(true)` produces: `ElementName/(alias/_id)/RuleName`
3. All existing tests pass (with updates as needed)
4. New tests cover the feature
