# Tasks

## 1. Implementation

- [x] 1.1 Add `globalIdPrefix` field to TransformationContext (volatile String, default null)
- [x] 1.2 Add `setGlobalIdPrefix(String)` method with mutual exclusion validation
- [x] 1.3 Add `getGlobalIdPrefix()` method
- [x] 1.4 Modify `setIncludeElementNameInStructuredIds(true)` to check for globalIdPrefix conflict
- [x] 1.5 Modify `getSourcePath()` to check `globalIdPrefix` first (before `includeElementNameInStructuredIds`)
- [x] 1.6 Normalize empty string to null in setter

## 2. Testing

- [x] 2.1 Add test: global prefix applied to all generated IDs
- [x] 2.2 Add test: global prefix with preferred source alias produces correct format
- [x] 2.3 Add test: exception thrown when globalIdPrefix set and includeElementNameInStructuredIds enabled
- [x] 2.4 Add test: exception thrown when includeElementNameInStructuredIds enabled and globalIdPrefix already set
- [x] 2.5 Add test: null prefix disables the feature
- [x] 2.6 Add test: empty string treated as null (disabled)
- [x] 2.7 Add test: getter returns current value
- [x] 2.8 Add test: global prefix applies to discriminated IDs

## 3. Validation

- [x] 3.1 Run existing StructuredIdTest to verify no regressions
- [x] 3.2 Run full test suite
