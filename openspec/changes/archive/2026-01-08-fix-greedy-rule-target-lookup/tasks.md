# Tasks: fix-greedy-rule-target-lookup

## Phase 1: Analysis (Same-Source-Type)

- [x] 1.1 Identify root cause: lazy rule activation only returns null
- [x] 1.2 Confirm fix approach: execute lazy rules immediately for explicit calls

## Phase 2: Implementation (Same-Source-Type)

- [x] 2.1 Modify `equivalent(EObject source, String ruleName)` to execute lazy rules immediately
- [x] 2.2 Add helper method `executeLazyRuleImmediately()`
- [x] 2.3 Run transformation-core tests to verify no regressions

## Phase 3: Testing (Same-Source-Type)

- [x] 3.1 Add unit test for cross-rule target lookup pattern
  - Created `GreedyLazyCrossRuleLookupTest.java` with:
    - Sequential mode test
    - Parallel mode test
    - Stress test (repeated 3x)
    - Edge case tests (non-existent rule, caching)
- [x] 3.2 Run Esm2UiExternalModelTest to verify 208 dataElements
  - **Resolved**: Issue was stale judo-zeta version in judo-tatami-esm2ui
  - After deploying latest 1.0.0-SNAPSHOT, cross-source-type lookups work correctly
- [x] 3.3 Run full test suite for regressions
  - All transformation-core tests pass (BUILD SUCCESS)

## Phase 4: Validation (Same-Source-Type)

- [x] 4.1 Verify sequential mode produces correct results
  - Test: `testCrossRuleLookupSequential()` - 10 ClassTypes, 10 RelationTypes with targets, 0 null targets
- [x] 4.2 Verify parallel mode produces identical results
  - Test: `testCrossRuleLookupParallel()` - 50 ClassTypes, 50 RelationTypes with targets, 0 null targets

---

## Phase 5: Cross-Source-Type Validation (JNG-6349)

**Context**: Bug report JNG-6349 identified that `ctx.equivalent()` returned null for cross-source-type lookups. Investigation revealed the issue was a stale judo-zeta version in the test environment.

- [x] 5.1 Verify existing fix is deployed to judo-tatami-esm2ui
  - **Result**: Deployed latest judo-zeta 1.0.0-SNAPSHOT
  - `executeLazyRuleImmediately()` fix handles cross-source-type scenario correctly
- [x] 5.2 Verify cross-source-type lookups work
  - **Result**: Error is gone after version update
  - The existing fix (Phases 1-4) already covers cross-source-type scenarios
- [x] 5.3 Add unit tests for all spec scenarios
  - Created `CrossSourceTypeLookupTest.java` with 10 tests covering:
    - Cross-source-type equivalent() calls (5 tests)
    - 3-rule chain scenarios (2 tests)
    - XMI ID lookup scenarios (3 tests)
  - All 596 transformation-core tests pass (BUILD SUCCESS)

## Conclusion

The fix implemented in Phases 1-4 (`executeLazyRuleImmediately()`) correctly handles **both**:
1. Same-source-type lookups (tested in `GreedyLazyCrossRuleLookupTest.java`)
2. Cross-source-type lookups (verified in judo-tatami-esm2ui after version update)

No additional implementation was needed. The bug report JNG-6349 was caused by using a stale version of judo-zeta that didn't include the fix.

## Dependencies

- Phases 1-4: Completed (core fix)
- Phase 5: Completed (cross-source-type validation)

## Status: Ready for Archive

All tasks complete. Proposal can be archived after final verification.
