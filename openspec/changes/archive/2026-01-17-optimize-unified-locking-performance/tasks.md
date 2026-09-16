# Tasks: Optimize Unified Locking Performance

## 1. Optimize Fast Path

- [x] **1.1** Move `resolutionCache.getByRule()` check BEFORE `RuleCacheKey` creation
- [x] **1.2** Only create `RuleCacheKey` when cache miss requires locking
- [x] **1.3** Replace `tryLock(30, TimeUnit.SECONDS)` with `lock.lock()` (matches `equivalent()`)

## 2. Optimize RuleCacheKey (Option B)

- [x] **2.1** Change `RuleCacheKey.equals()` to use identity comparison (`source == that.source`)
- [x] **2.2** Change `RuleCacheKey.hashCode()` to use `System.identityHashCode(source)`
- [x] **2.3** Pre-compute hash in constructor to avoid repeated computation

## 3. Reduce Cache Lookup Duplication

- [x] **3.1** Remove redundant `executingLazyRules.get(key)` check after lock (rely on `resolutionCache`)
- [x] **3.2** Ensure `resolutionCache` is the authoritative cache checked under lock

## 4. Validation

- [x] **4.1** Verify `DualLockingRaceConditionTest` still passes - **CONFIRMED: 0/20 duplicate executions**
- [ ] **4.2** Run performance benchmark to verify improvement - **Pending user validation**
- [x] **4.3** Run all transformation-core tests (500+) - **PASSED: 500 tests, 0 failures**
- [ ] **4.4** Target: Performance within 15% of original baseline - **Pending user validation**

## Dependencies

- Task 1.x can be done independently
- Task 2.x requires updating both `executeParentRule()` and `equivalent()` for consistency
- Task 3.x depends on 1.x completion
- Task 4.x is final validation
