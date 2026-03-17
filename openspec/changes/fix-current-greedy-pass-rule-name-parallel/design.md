## Context

The `currentGreedyPassRuleName` field in `TransformationContext` is used by `equivalent()` to detect "same-rule lookups" during greedy rule execution. This is critical for ETL-compatible semantics where `equivalent()` for a non-lazy greedy rule should only return previously-cached results, not trigger lazy execution.

Currently:
- Sequential rule-by-rule (`transformRuleByRule()` line 972) correctly sets `context.setCurrentGreedyPassRuleName(rule.getName())`
- Parallel rule-by-rule (`transformRuleByRuleParallel()` lines 1025-1063) does NOT set this field

The `currentGreedyPassRuleName` field is `volatile`, making it safe for concurrent reads. Since rule-by-rule parallel processes rules sequentially (only the inner element loop is parallelized), setting this before each rule's parallel phase is safe — all parallel threads read the same value.

## Goals / Non-Goals

**Goals:**
- Fix `transformRuleByRuleParallel()` to set `currentGreedyPassRuleName` before processing each rule
- Ensure proper cleanup via `clearCurrentGreedyPassRuleName()` in finally block
- Maintain thread-safety (volatile field is safe for the concurrent-read pattern)

**Non-Goals:**
- Changing the `volatile` field to `ThreadLocal` (architecturally significant, out of scope)
- Modifying `equivalent()` behavior (only fixing missing state tracking)

## Decisions

### 1. Location of `setCurrentGreedyPassRuleName()` call

**Decision:** Place the call at the start of each rule iteration, before the parallel chunking.

**Rationale:**
- Matches the sequential version's placement (line 972)
- Must be set before any thread can call `equivalent()` during rule execution
- The outer loop is sequential, so only one rule's name is active at a time

### 2. Thread-safety approach

**Decision:** Use existing `volatile` field without additional synchronization.

**Rationale:**
- The field is already `volatile` (line 501 in TransformationContext.java)
- Rule-by-rule parallel has sequential outer loop: Rule A completes entirely before Rule B starts
- During Rule A's parallel phase, all threads read the same value (Rule A's name)
- No write conflicts occur because only the main thread sets the value

### 3. Finally block placement

**Decision:** Wrap the entire rule processing loop in try-finally, clearing the rule name after completion.

**Rationale:**
- Matches the sequential version's pattern (lines 972-994)
- Ensures cleanup even if exceptions occur
- Prevents stale state from affecting subsequent rules

## Risks / Trade-offs

**Risk: Volatile field may not be ideal for true parallel execution**
- **Mitigation:** Acceptable for current architecture where outer loop is sequential. Document this constraint. Future refactoring to `ThreadLocal` should track this limitation.

**Risk: Missing test coverage for same-rule lookup in parallel mode**
- **Mitigation:** Add test case verifying that lazy rule invocation from within a greedy rule during parallel rule-by-rule respects ETL semantics.

**Trade-off: Fix is minimal and localized**
- **Pro:** Low risk, quick to implement, doesn't change architecture
- **Con:** Doesn't address the architectural question of whether `volatile` is the right choice long-term
