# Proposal: Add Guard Scope Control for Greedy/Lazy Rule Interaction

**Change ID**: `add-guard-scope-control`
**Status**: Proposed
**Created**: 2026-01-01
**Type**: Bug Fix / ETL Compatibility

## Why

When a `@Greedy` rule with a guard blocks a source element during the eager phase, subsequent `equivalent()` calls should NOT invoke `@Lazy` rules for the same source→target type mapping. Currently, ZETA invokes `@Lazy` rules regardless of whether a sibling `@Greedy` rule's guard rejected the element.

This causes extra elements to be created, breaking ETL compatibility:
- **RelationType count**: ETL produces 245, ZETA produces 517 (+272 extra)
- **Root cause**: `@Lazy` rules create elements for sources that `@Greedy` guards already rejected

### Current Behavior (Incorrect)

```
Source: RelationFeature
Target: RelationType

Rules:
1. RelationType (@Greedy, guard: isExposedRelation) - rejects non-exposed
2. CloneRelationType (@Lazy @Greedy, no guard) - creates for any

Execution:
1. Eager phase: RelationType guard returns false for non-exposed → skipped
2. Later: equivalent(nonExposedRelation, RelationType.class) called
3. ZETA finds CloneRelationType (no guard), executes it
4. Result: Extra RelationTypes created for non-exposed relations
```

### ETL Behavior (Per-Rule Rejection)

```
Execution:
1. Eager phase: RelationType guard returns false → added to RelationType.rejected
2. Later: equivalent(nonExposedRelation, RelationType.class) called
3. RelationType.appliesTo() → rejected.contains(source) → false (skip)
4. CloneRelationType.appliesTo() → own rejected set empty, no guard → true
5. CloneRelationType executes (if not activity-based)
```

**Key insight**: Per-rule rejection alone doesn't explain the element count difference.
The real difference is likely **activity-based semantics** for @Lazy @Greedy rules.

## What Changes

### 1. Per-Rule Guard Rejection Tracking (ETL-Compatible)

Add rejection tracking **per rule** in `TransformRuleDescriptor`:
- Each rule maintains its own `rejected` collection
- Key: `sourceElement`
- Similar to ETL's `TransformationRule.rejected`

```java
public class TransformRuleDescriptor {
    // Each rule tracks its own rejected elements
    private final Set<EObject> rejected = ConcurrentHashMap.newKeySet();

    public boolean wasRejected(EObject source) {
        return rejected.contains(source);
    }

    public void recordRejection(EObject source) {
        rejected.add(source);
    }
}
```

### 2. Record Rejections in appliesTo/evaluateGuard

When a rule's guard returns `false`, record the rejection for that source in the rule's rejected set:

```java
public boolean evaluateGuard(EObject source, TransformationContext ctx) {
    if (rejected.contains(source)) {
        return false;  // Early exit - already rejected
    }
    boolean result = /* evaluate guard */;
    if (!result) {
        rejected.add(source);  // Cache rejection
    }
    return result;
}
```

### 3. Check Rejection Before Guard Evaluation

In `equivalent()` and eager phase, check rejection cache first to avoid redundant guard evaluation.

### 4. Clear Rejections on Executor Reset

Reset each rule's rejected set when executor is reused.

## Behavior (ETL-Compatible)

| Scenario | Behavior |
|----------|----------|
| Guard fails for source X | X added to THIS rule's rejected set |
| Same rule, same source again | Returns false immediately (cached) |
| Different rule, same source | Evaluated independently (own rejected set) |
| Executor reset | All rules' rejected sets cleared |

## Impact Analysis

### What This Change Does
- **Optimization**: Avoids redundant guard evaluation for the same source element
- **ETL Compatibility**: Matches ETL's per-rule rejection caching behavior
- **Thread Safety**: Uses `ConcurrentHashMap.newKeySet()` for parallel transformations

### What This Change Does NOT Do
- Does NOT propagate rejection across rules
- Does NOT block @Lazy rules when @Greedy rejects

### Real Root Cause of Element Count Difference

With per-rule rejection (ETL-compatible), the 245 vs 517 difference is likely caused by:

| Likely Cause | Solution |
|--------------|----------|
| **Activity-based semantics** | Enable `etlCompatibilityMode(true)` for @Lazy @Greedy rules |
| **Caching issues** | @Lazy rules via `executeParentRule()` need manual cache management |
| **Rule selection** | ETL might stop at first applicable rule |

**Recommendation**: First enable `etlCompatibilityMode(true)` and verify if element counts improve before implementing per-rule rejection.

### Files to Modify
1. `TransformRuleDescriptor.java` - Add `rejected` set and methods
2. `TransformationExecutor.java` - Clear rejected sets on reset
3. `TransformationRegistry.java` - Provide access to rule rejected sets for clearing

## Success Criteria

- [ ] Per-rule guard rejection tracking is thread-safe
- [ ] Same-rule guard evaluation is cached (no redundant evaluations)
- [ ] Different rules evaluate independently (per-rule isolation)
- [ ] Executor reset clears all rules' rejected sets
- [ ] All existing tests pass
- [ ] Performance improvement for models with many rejected elements

## Related Investigation: @Lazy Rule Caching

A separate investigation uncovered a related caching issue that may contribute to element count discrepancies:

### Problem: @Lazy Rules and executeParentRule() Caching

@Lazy rules in Zeta don't automatically cache their results when invoked via `ctx.executeParentRule()`. Each call would create a new target object, causing duplicates.

**Affected transformations (judo-tatami-client):**
- `TypeRules.java`: Auto-generated types (BooleanType, IntegerType, LongType, StringType) and operation enumerations
- `NamespaceRules.java`: All generated/extension package rules

**Workaround applied:**
Each @Lazy rule now manually manages caching:
1. Checks cache first via `ctx.getElementResolutionCache().getByRule(source, ruleName)`
2. Returns cached value if exists
3. Creates new target and adds to cache via `ctx.getElementResolutionCache().addMapping(...)`

### Interaction with useStructuredIds

Zeta's `useStructuredIds` mode (default: `true`) provides XMI ID-based lookup as a secondary cache mechanism:

```java
if (useStructuredIds) {
    String structuredId = generateStructuredId(source, rule.getName());
    T existingByXmiId = findByXmiId(structuredId, targetType);
    if (existingByXmiId != null) {
        // Found by XMI ID - cache it and return
        resolutionCache.addMapping(source, rule.getName(), existingByXmiId, rule.isPrimary());
        return existingByXmiId;
    }
}
```

This means:
- With `useStructuredIds=true`, already-created elements can be found by XMI ID even if not in the resolution cache
- Guard rejection tracking should happen **before** XMI ID lookup to prevent finding elements that shouldn't exist

### Module Configuration Considerations

For modules experiencing element count mismatches, verify:

| Setting | Expected | Purpose |
|---------|----------|---------|
| `useStructuredIds` | `true` | Enables ETL-compatible XMI ID-based element lookup |
| `etlCompatibilityMode` | `true` | Enables activity-based processing for @Greedy @Lazy rules |
| `propagateGreedyGuardRejections` | `true` | NEW: Prevents @Lazy from creating rejected elements |

**Migration checklist for affected modules:**
1. Enable `etlCompatibilityMode(true)` if using @Greedy @Lazy rules
2. Enable `propagateGreedyGuardRejections(true)` (default) for guard propagation
3. Review @Lazy rules that use `executeParentRule()` for caching issues
4. Verify element counts match ETL baseline

## ETL Research Findings

Investigation of [ETL's TransformationRule.java](https://github.com/eclipse/epsilon/blob/main/plugins/org.eclipse.epsilon.etl.engine/src/org/eclipse/epsilon/etl/dom/TransformationRule.java) reveals:

### ETL Rejection Mechanism

```java
protected Collection<Object> rejected = new HashSet<>();

public boolean appliesTo(Object source, ...) {
    if (rejected.contains(source)) return false;  // Early exit
    // ... type check ...
    if (guard != null) {
        guardSatisfied = guard.execute(...);
    }
    if (!applies) {
        rejected.add(source);  // Cache rejection
    }
    return applies;
}
```

### Key Finding: Rejection is PER-RULE

| Aspect | ETL Behavior | Proposed Zeta Behavior |
|--------|--------------|------------------------|
| Rejection scope | Per-rule (`rejected` is instance variable) | Per (source, targetType) |
| Cross-rule effect | RuleA rejection doesn't affect RuleB | RuleA rejection blocks all @Lazy for same targetType |

### Implication

In ETL, each rule has its own `rejected` collection. When `equivalent()` iterates through rules:
1. @Greedy rule's `appliesTo()` returns `false` (source in its `rejected` set)
2. @Lazy rule's `appliesTo()` is checked independently (its own `rejected` set)
3. If @Lazy has no guard and type matches, it executes

**This suggests ETL's 245 vs Zeta's 517 might have a different root cause** - possibly related to:
- Rule priority/selection when multiple rules match
- @Lazy @Greedy interaction with activity-based semantics
- The `@primary` annotation affecting rule selection

### Recommendation

Before implementing, we should verify:
1. Does ETL have a "first applicable rule wins" semantic for `equivalent()`?
2. Does `@primary` affect which rule executes when multiple match?
3. Is the element count difference due to caching issues rather than guard propagation?

**Alternative approach**: Match ETL's per-rule rejection (simpler, more compatible) instead of per-targetType rejection.

## References

- ETL `relationFeature.etl`: RelationType and CloneRelationType rules
- [Epsilon ETL Documentation](https://eclipse.dev/epsilon/doc/etl/)
- [ETL TransformationRule.java](https://github.com/eclipse/epsilon/blob/main/plugins/org.eclipse.epsilon.etl.engine/src/org/eclipse/epsilon/etl/dom/TransformationRule.java)
- judo-tatami-client TypeRules.java and NamespaceRules.java caching investigation
