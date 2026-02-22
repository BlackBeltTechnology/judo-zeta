# Tasks: Fix Greedy Annotation Semantics

## Status: REJECTED

**This proposal has been rejected after analysis revealed the current implementation is correct.**

See proposal.md for full analysis.

## Analysis Performed (2026-01-17)

- [x] Reviewed current `appliesTo()` implementation in `TransformRuleDescriptor`
- [x] Reviewed `@Greedy` annotation Javadoc in `zeta-annotations`
- [x] Reviewed `ETLSemanticsTest` test cases
- [x] Verified all 740 tests pass with current implementation
- [x] Analyzed ETL terminology ("type-of" vs "kind-of")

## Findings

1. **Current implementation is CORRECT:**
   - Non-greedy (default) = exact type match only
   - @Greedy = matches type AND all subtypes

2. **Proposal was based on misinterpreted terminology:**
   - Proposal claimed "type-of" means subtype matching (WRONG)
   - Proposal claimed "kind-of" means exact matching (WRONG)
   - Standard OOP: "type-of" = exact, "kind-of" = is-a relationship

3. **Evidence:**
   - `@Greedy` Javadoc correctly documents the behavior
   - `ETLSemanticsTest` tests verify correct behavior
   - All 740 tests pass

## Recommendation

Archive this proposal as REJECTED. No code changes needed.

## Optional Follow-up

Minor comment improvement in `appliesTo()` could clarify the terminology,
but this is not a semantic change and can be done separately if desired.
