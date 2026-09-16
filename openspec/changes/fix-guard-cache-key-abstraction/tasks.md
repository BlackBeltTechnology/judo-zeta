## 1. Add Guard Method Cache to TransformationContext

- [x] 1.1 Add `ConcurrentHashMap<Method, ConcurrentHashMap<EObject, Boolean>>` field `guardResultCache` to `TransformationContext`
- [x] 1.2 Add `getGuardResult(Method guardMethod, EObject source)` method returning `Boolean` (null if not cached)
- [x] 1.3 Add `putGuardResult(Method guardMethod, EObject source, boolean result)` method

## 2. Modify TransformRuleDescriptor.evaluateGuard()

- [x] 2.1 Update single-source `evaluateGuard(EObject, TransformationContext)` to check `context.getGuardResult(guardMethod, source)` before invoking guard, and store result via `context.putGuardResult()` after invocation
- [x] 2.2 Update multi-source `evaluateGuard(EObject[], TransformationContext)` single-source fallback path to use guard method cache
- [x] 2.3 Remove `rejected` field (`Set<EObject>`) from `TransformRuleDescriptor`
- [x] 2.4 Remove `wasRejected()`, `recordRejection()`, `clearRejected()` methods

## 3. Clean Up TransformationRegistry

- [x] 3.1 Remove `clearAllRejectedSets()` method from `TransformationRegistry`
- [x] 3.2 Remove call to `clearAllRejectedSets()` from executor reset path

## 4. Update Tests

- [x] 4.1 Update `GuardRejectionCacheTest` to verify guard-method-level caching: shared guards across rules get cache hits
- [x] 4.2 Verify `GuardRejectionCacheTest.testDifferentRulesHaveIndependentRejectedSets` — update to test that rules sharing the same guard method share cache results
- [x] 4.3 Verify `GuardRejectionCacheTest.testConcurrentRejectionRecordingIsThreadSafe` passes with new cache
- [x] 4.4 Verify `GuardRejectionCacheTest.testExecutorResetClearsAllRulesRejectedSets` — update to verify fresh context = fresh guard cache
- [x] 4.5 Run full test suite (`mvn test -pl transformation-core`) and verify all tests pass

## 5. Update Metrics

- [x] 5.1 Add guard cache hit/miss counters to `TransformationMetrics` (optional but useful for benchmarking the improvement)
