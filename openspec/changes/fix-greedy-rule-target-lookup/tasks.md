# Tasks: fix-greedy-rule-target-lookup

## Phase 1: Investigation

- [ ] 1.1 Reproduce the issue in Esm2UiExternalModelTest
  - Verify dataElements: 136 vs 208
  - Confirm RelationType.target is null

- [ ] 1.2 Trace equivalent() calls in TransformationContext
  - Add debug logging to see cache lookup flow
  - Check if XMI ID lookup is being triggered

- [ ] 1.3 Verify XMI ID generation timing
  - Check when IDs are set on ClassType targets
  - Verify IDs are available before RelationType equivalent() call

- [ ] 1.4 Identify root cause location
  - Is it in equivalent() implementation?
  - Is it in ElementResolutionCache?
  - Is it in XMI ID index management?

## Phase 2: Implementation

- [ ] 2.1 Fix the identified root cause
  - Implement the fix in TransformationContext or ElementResolutionCache
  - Ensure targets are findable via XMI ID lookup

- [ ] 2.2 Verify fix in sequential mode
  - Run Esm2UiExternalModelTest
  - Confirm dataElements now matches (208)

- [ ] 2.3 Verify fix in parallel mode
  - Run with parallel=true
  - Confirm same results as sequential

## Phase 3: Testing

- [ ] 3.1 Add unit test for greedy-to-greedy target lookup
  - Create transformation with two greedy rules
  - Rule A creates targets, Rule B finds them via equivalent()

- [ ] 3.2 Add regression test for eager rule XMI ID lookup
  - Test equivalent() finds target from eager rule

- [ ] 3.3 Run full transformation test suite
  - Ensure no regressions in existing tests

## Phase 4: Documentation

- [ ] 4.1 Update relevant documentation
  - Document the greedy-to-greedy pattern behavior
  - Note any API guarantees about target visibility

## Dependencies

- Phase 2 depends on Phase 1 completion
- Phase 3 can run in parallel after Phase 2
