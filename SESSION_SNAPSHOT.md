# Session Snapshot: TransformationContext Race Condition Fix

**Date:** Monday, January 5, 2026
**Current Task:** Fixing race conditions in `TransformationContext` (`equivalent` vs `executeParentRule`) and resolving regression in `ETLPatternIntegrationTest`.

## Context
We identified a race condition where `equivalent()` and `executeParentRule()` were using different lock keys, allowing concurrent execution of the same rule. We implemented a fix using `@Primary` rule prioritization, but initially introduced a regression in `ETLPatternIntegrationTest` by removing the global cache check.

## Changes Made
1.  **`ElementResolutionCache.java`**:
    *   Made `PRIMARY_LOCK_KEY` public to allow shared locking.

2.  **`TransformationContext.java`**:
    *   **Restored Global Cache Check:** Restored `resolutionCache.getEquivalent(source, targetType)` at the beginning of `equivalent()`. This fixes the regression in `ETLPatternIntegrationTest` where previously transformed elements were not being found if they weren't caught by the rule loop.
    *   **Primary Rule Prioritization:** In `equivalent()`, explicitly check for a `@Primary` rule for the target type *before* iterating through all rules. If a primary rule exists and applies, use its name as the canonical lock key and execute it.
        *   This ensures that `equivalent(source, Type)` and `executeParentRule("PrimaryRuleName", source)` use the *same* lock key (`PrimaryRuleName`).
        *   This creates a deterministic execution order and prevents the race condition.
    *   **Variable Renaming:** Renamed conflicting variables (`primaryRule` -> `lockingPrimaryRule`/`loopPrimaryRule`) to fix compilation errors.
    *   **Locking:** Updated locking to use `PRIMARY_LOCK_KEY` for consistent synchronization.

## Current Status
*   **Race Condition Tests:** `CacheKeyMismatchRaceConditionTest` **PASSES**. The race condition is resolved; `equivalent` and `executeParentRule` now coordinate correctly.
*   **Integration Tests:** `ETLPatternIntegrationTest` **PASSES**. The regression is fixed; equivalent lookups work as expected for all ETL patterns.

## Verification
*   `mvn -pl transformation-core -Dtest=ETLPatternIntegrationTest test` -> **SUCCESS**
*   `mvn -pl transformation-core -Dtest=CacheKeyMismatchRaceConditionTest test` -> **SUCCESS**

## Files of Interest
*   `transformation-core/src/main/java/hu/blackbelt/judo/zeta/transformation/core/TransformationContext.java`
*   `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/CacheKeyMismatchRaceConditionTest.java`