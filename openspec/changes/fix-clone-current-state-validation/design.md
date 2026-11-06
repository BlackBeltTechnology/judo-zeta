## Context

The `CLONE_CURRENT_STATE` discrimination strategy requires deterministic element processing order to track state changes across `equivalentDiscriminated()` calls. The `equivalentDiscriminated()` method (lines 2250-2257 in TransformationContext.java) throws `IllegalStateException` when both `CLONE_CURRENT_STATE` and `deferredWritesEnabled` are true.

Currently:
- `validateConfiguration()` only checks `RULE_BY_RULE + parallel + CLONE_CURRENT_STATE`
- But ELEMENT_BY_ELEMENT + parallel also enables `deferredWritesEnabled` (line 1021)
- Users get runtime errors instead of build-time validation

## Goals / Non-Goals

**Goals:**
- Add validation for `CLONE_CURRENT_STATE + ELEMENT_BY_ELEMENT + parallel` combination
- Provide clear error messages explaining the incompatibility
- Ensure all parallel + CLONE_CURRENT_STATE combinations fail at build time

**Non-Goals:**
- Changing CLONE_CURRENT_STATE behavior (it fundamentally requires sequential execution)
- Modifying equivalentDiscriminated() logic

## Decisions

### 1. Unified validation condition

**Decision:** Check `CLONE_CURRENT_STATE + parallel (any execution strategy)` rather than enumerating each strategy.

**Rationale:**
- Simpler, more maintainable code
- Future-proof if new execution strategies are added
- Clearly communicates that CLONE_CURRENT_STATE is incompatible with parallel mode in general

### 2. Error message clarity

**Decision:** Provide specific error messages mentioning the deterministic ordering requirement.

**Rationale:**
- Helps users understand WHY the combination is unsupported
- Guides them toward the solution (use sequential mode)

## Risks / Trade-offs

**Risk: Breaking existing "working" code**
- **Mitigation:** None - these configurations would already fail at runtime. Failing earlier is strictly better.

**Trade-off: More restrictive validation**
- **Pro:** Catches errors at build time
- **Con:** Some edge cases might theoretically work if deferred writes are never triggered
- **Decision:** The risk of subtle bugs outweighs the convenience of allowing risky combinations
