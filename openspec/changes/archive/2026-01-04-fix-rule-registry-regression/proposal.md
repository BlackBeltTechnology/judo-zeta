# Proposal: Fix Rule Registry Regression (Version 131508)

## Why

After the `optimize-rule-registry-performance` change (commit `0cd0bc0`), version 131508 exhibits regressions:

1. **eType: null (28+ occurrences)**: Operations have `eType: null` instead of expected return types
2. **Missing parameters**: Operations have 0 parameters instead of expected count
3. **Zero Metrics**: All transformation metrics show 0

### Root Cause

The XMI ID lookup in `equivalent()` was **inside** the lazy rule loop. When there are **no lazy rules** for the source type, the loop never executes, and:
- XMI ID lookup is **never performed**
- `equivalent()` returns `null`

### Failing Scenario

1. **Eager rule A** transforms `EOperation` → sets `eType` via `ctx.equivalent(returnType, EClassifier.class)`
2. **Eager rule B** transforms `EDataType` (the return type)
3. **NO lazy rule** exists for `EDataType`

In parallel execution, Thread 1 (Rule A) calls `equivalent(returnType)` before Thread 2 (Rule B) completes. Since no lazy rules exist for EDataType, the XMI ID lookup is skipped and `equivalent()` returns null.

## What Changes

### 1. Unified Rule Lookup in equivalent()

Move XMI ID lookup **before** the lazy rule loop to handle targets created by EAGER rules:

- Check XMI ID for ALL matching rules (eager and lazy)
- If not found in cache or XMI index, execute the rule on-demand with proper locking
- Works for both eager and lazy rules

### 2. XMI ID Lookup in executeParentRule()

Add XMI ID lookup to `executeParentRule()` for consistency with `equivalent()`:

- Check cache first (existing behavior)
- Then check XMI ID index (new)
- Execute parent rule on-demand if needed

### 3. Comprehensive Regression Tests

Created 64 tests across 8 phases covering:
- Phase 0.1-0.4: Basic scenarios (missing operations, eType null, metrics, cross-element)
- Phase 0.5: Separate eager rules (bug reproduction)
- Phase 0.6: `equivalent(source, ruleName)` variants
- Phase 0.7: `equivalentDiscriminated()` variants
- Phase 0.8: `executeParentRule()` variants

## Files Modified

1. `TransformationContext.java` - Unified rule lookup + XMI ID in executeParentRule
2. `RuleRegistryRegressionTest.java` - 64 comprehensive regression tests

## Validation

- All 64 regression tests pass
- All 564 total tests pass (0 failures)
- No regressions introduced
