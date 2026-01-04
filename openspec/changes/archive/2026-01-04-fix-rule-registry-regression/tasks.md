# Tasks: Fix Rule Registry Regression

## Phase 0: Reproduce the Problem

- [x] **0.1** Create test to reproduce missing operations
  - Created `RuleRegistryRegressionTest.MissingOperationsTests`
  - Tests greedy rules for supertype executing for all subtypes
  - Tests multiple greedy rules all executing for same element
  - **Result: Tests PASS**

- [x] **0.2** Create test to reproduce eType: null issue
  - Created `RuleRegistryRegressionTest.ETypeNullTests`
  - Tests eager rule calling equivalent() for lazy rule result
  - **Result: Tests PASS**

- [x] **0.3** Create test to verify metrics are non-zero
  - Created `RuleRegistryRegressionTest.ZeroMetricsTests`
  - Tests getEagerRulesForType() and getLazyRulesForType() return non-empty
  - **Result: Tests PASS**

- [x] **0.4** Create test for cross-element dependencies
  - Created `RuleRegistryRegressionTest.CrossElementDependencyTests`
  - Tests parallel execution with cross-element equivalent() calls
  - Repeated test 10x to catch intermittent race conditions
  - Tests with 21 elements to stress test parallelism
  - **Result: All 12 tests PASS**

- [x] **0.5** Create test for separate eager rules (NO LAZY FALLBACK)
  - Created `RuleRegistryRegressionTest.SeparateEagerRulesTests`
  - Tests EOperation eager rule calling equivalent() for EDataType
  - EDataType has only an eager rule (no lazy rule)
  - **Result: Initially FAILED (confirmed the bug), then PASSED after fix**

## Phase 0.5 Findings: ROOT CAUSE CONFIRMED

The bug was reproduced with `SeparateEagerRulesTests`:

**Scenario:**
1. Eager rule A transforms `EOperation` → calls `equivalent(returnType)`
2. Eager rule B transforms `EDataType` (the return type)
3. NO lazy rule for `EDataType` exists

**What happened:**
1. Thread 1 calls `equivalent(returnType, EClassifier.class)`
2. Cache miss (Thread 2 not done yet)
3. `equivalent()` looks for LAZY rules for EDataType - **NONE EXIST**
4. XMI ID lookup was **inside** the lazy rule loop - **NEVER EXECUTED**
5. Returns null → **eType: null!**

**Root cause:** XMI ID lookup and on-demand rule execution were only done inside the lazy rule loop. When no lazy rules exist, these paths were never taken.

## Phase 1: Implement Fix

- [x] **1.1** Move XMI ID lookup before lazy rule loop
  - Added unified rule lookup path for ALL rules (eager and lazy)
  - XMI ID lookup now happens for all matching rules
  - If XMI ID not found, execute rule on-demand (like lazy rules)
  - Fixed in `TransformationContext.equivalent()`

## Phase 2: Validation

- [x] **2.1** Run regression tests
  - All 30 regression tests pass (including Phase 0.5)

- [x] **2.2** Run full test suite
  - 530 tests run, 0 failures, 11 skipped
  - No regressions introduced

## Summary

**Bug:** `equivalent()` returned null when called for sources that are only transformed by EAGER rules (no lazy rules exist).

**Root Cause:** XMI ID lookup and on-demand rule execution were inside the lazy rule loop. When no lazy rules exist for a source type, the loop never executes, so:
- XMI ID lookup is skipped (can't find existing targets)
- On-demand execution is skipped (can't trigger eager rules)

**Fix:** Added a unified rule lookup path before the lazy rule loop that:
1. Checks XMI ID for ALL matching rules (eager and lazy)
2. If not found, executes the rule on-demand with proper locking
3. Works for both eager and lazy rules

**Test file:** `RuleRegistryRegressionTest.java` (30 tests total)
**Modified file:** `TransformationContext.java` (equivalent method)
