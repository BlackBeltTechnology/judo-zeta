# Proposal: Fix Cache Key Mismatch Race Condition

## Why

Parallel transformation exhibits ~25% failure rate with duplicate elements (e.g., +7 extra Cardinality elements). The issue is **NOT covered** by existing proposals:

- `fix-emf-containment-race-condition` → EMF EList corruption (different bug)
- `optimize-unified-locking-performance` → Performance optimization (same locking)
- `fix-rule-registry-regression` → XMI ID lookup (archived, fixed)

### Root Cause: Lock Key Mismatch Between Access Patterns

**Two different access patterns for same target:**

| Pattern | Method | Lock Key | Cache Lookup |
|---------|--------|----------|--------------|
| 1 | `executeParentRule("Model", source)` | `(source, "Model")` | `getByRule(source, "Model")` |
| 2 | `equivalent(source, Model.class)` | `(source, rule.getName())` | `getEquivalent(source, Model.class)` |

**The problem:** `equivalent()` iterates over rules matching `Model.class` in **registration order** and picks the **first matching rule**. If multiple rules produce `Model.class`:

Example:
- Rule "BaseModel" registered first, produces Model.class
- Rule "Model" registered second, produces Model.class (via @Extends or separate)

Then:
- `equivalent(source, Model.class)` → finds "BaseModel" first → lock key: `(source, "BaseModel")`
- `executeParentRule("Model", source)` → explicit name → lock key: `(source, "Model")`
- **Different locks = race condition!**

**Race condition:**
1. Thread A: `equivalent(source, Model.class)` → finds rule "CreateModel" → locks `(source, "CreateModel")`
2. Thread B: `executeParentRule("Model", source)` → locks `(source, "Model")` - **DIFFERENT LOCK!**
3. Both threads execute the same effective rule → duplicate targets

### Evidence

- ~25% failure rate (5/20 runs)
- Run 9: +7 extra elements with Cardinality duplicates
- Multiple element types affected (Model, Cardinality, others)
- Systemic across all @Primary rules

## What Changes

### 1. Unified Cache Key Strategy (Combined Approach)

Both `equivalent()` and `executeParentRule()` must use the **same cache key** for the same logical operation.

**Step 1: Check @Primary rules FIRST**
- In `equivalent()`, before iterating all rules, find the @Primary rule for the target type
- Use the @Primary rule's name for the lock key
- This ensures `equivalent(source, Model.class)` uses the same key as `executeParentRule("Model", source)` when "Model" is @Primary

**Step 2: Fall back to cross-reference cache**
- When `addMapping(source, ruleName, target, isPrimary)` is called, record `(source, targetType) → ruleName`
- For non-@Primary rules, `equivalent()` looks up this mapping to find the canonical rule name
- Ensures consistent lock keys even when @Primary is not defined

### 2. Create Failing Tests First (TDD)

Before implementing the fix, create tests that reproduce the bug:

- **Dual-access pattern test** - One rule accessed via both `executeParentRule()` and `equivalent()`
- **Multiple @Primary rules stress test** - Concurrent access with 100 elements
- **Determinism test** - Verify element counts are consistent across 10 runs

**All tests must FAIL before implementation begins.**

### 3. Verify Tests Pass

After implementation, all Phase 0 tests must PASS.

## Files Modified

1. `CacheKeyMismatchRaceConditionTest.java` - NEW: Failing tests for bug reproduction
2. `TransformationContext.java` - Unify cache key strategy
3. `ElementResolutionCache.java` - Add rule name cross-reference (if Option B)

## Related Proposals

| Proposal | Overlap | Status |
|----------|---------|--------|
| `fix-emf-containment-race-condition` | None (different bug) | Pending |
| `optimize-unified-locking-performance` | Uses same locking infra | Pending |
| `fix-rule-registry-regression` | Fixed XMI ID lookup | Archived |
