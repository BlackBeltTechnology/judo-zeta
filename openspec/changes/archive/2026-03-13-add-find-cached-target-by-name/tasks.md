## 1. Core Implementation

- [x] 1.1 Add `findByName(String name, Class<T> targetType)` method to `ElementResolutionCache` — linear scan of `ruleCache` values, returns first target where `targetType.isInstance(target) && name.equals(target.eClass().getName())`
- [x] 1.2 Add `findCachedTargetByName(String name, Class<T> targetType)` method to `TransformationContext` — delegates to `resolutionCache.findByName()`, handles null inputs

## 2. Tests — Reproduce Identity-Mismatch Bug

- [x] 2.1 Create `FindCachedTargetByNameTest` with test setup: two source objects of the same EMF type but different Java identity (simulating proxy/copy scenario), one eager TypeRule, one eager AttributeRule that calls `equivalent()` then `findCachedTargetByName()` as fallback
- [x] 2.2 Test: `equivalent()` returns null for different-identity source, confirming the bug scenario
- [x] 2.3 Test: `findCachedTargetByName()` returns the correct target created from the original-identity source

## 3. Tests — ElementResolutionCache.findByName()

- [x] 3.1 Test: exact name match returns cached target
- [x] 3.2 Test: no match returns null
- [x] 3.3 Test: type hierarchy lookup (EDataType found via EClassifier request)
- [x] 3.4 Test: incompatible type returns null (EDataType not found via EClass request)
- [x] 3.5 Test: null name and null targetType inputs return null
- [x] 3.6 Test: works in both sequential mode and parallel mode

## 4. Tests — Regression

- [x] 4.1 Test: existing `equivalent()` behavior unchanged — identity-based lookup still works for same-identity sources
- [x] 4.2 Test: on-demand execution via `equivalent()` still works for lazy rules
- [x] 4.3 Run full test suite to verify no regressions (899 tests, 0 failures)
