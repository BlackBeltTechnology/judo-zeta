## Why

When a consuming transformation (e.g., PSM→ASM) calls `ctx.equivalent(source, EClassifier.class)` and the source object is a different Java instance than what the TypeRules originally processed (proxy/copy in extension packages), the identity-based cache miss causes `equivalent()` to return null. The fallback `resolveTypeByName()` also fails because it searches the target resource set, which is empty until `postProcess()`. This results in 23+ EAttributes with `eType = null`.

## What Changes

- Add `findCachedTargetByName(String name, Class<T> targetType)` method to `TransformationContext` that searches all cached transformation targets by EMF name, bypassing identity-based lookup
- Add corresponding `findByName()` method to `ElementResolutionCache` for efficient name-based search across all cached mappings
- Add tests reproducing the identity-mismatch + empty-resource-set scenario
- Add regression tests ensuring existing `equivalent()` behavior is preserved

## Capabilities

### New Capabilities
- `cached-target-name-lookup`: Name-based lookup of cached transformation targets as fallback when identity-based `equivalent()` fails due to proxy/copy source objects

### Modified Capabilities

## Impact

- `TransformationContext.java`: New public method `findCachedTargetByName()`
- `ElementResolutionCache.java`: New public method `findByName()`
- New test class for the name-based lookup feature
- No breaking changes — purely additive API
