# Tasks: Fix Greedy Annotation Semantics

## Phase 1: Analysis and Design

- [ ] 1.1 Verify current test coverage for rule matching with inheritance
- [ ] 1.2 Identify all tests that rely on current (incorrect) behavior
- [ ] 1.3 Design migration strategy with deprecation period

## Phase 2: Implementation

- [ ] 2.1 Fix `appliesTo()` in `TransformRuleDescriptor` for non-greedy rules
- [ ] 2.2 Fix `appliesTo()` in `TransformRuleDescriptor` for greedy rules
- [ ] 2.3 Update `@Greedy` annotation Javadoc
- [ ] 2.4 Add deprecation warning for migration path

## Phase 3: Testing

- [ ] 3.1 Add tests verifying ETL-compatible type-of matching
- [ ] 3.2 Add tests verifying kind-of (exact) matching for greedy
- [ ] 3.3 Update existing tests that rely on old behavior
- [ ] 3.4 Run full transformation-core test suite

## Phase 4: Documentation

- [ ] 4.1 Update migration guide
- [ ] 4.2 Document new semantics in user guide
- [ ] 4.3 Add breaking change notice

## Dependencies

- None - can be done independently

## Parallelization

- Tasks 1.1-1.3 can be done in parallel
- Tasks 2.1-2.4 depend on Phase 1 completion
- Tasks 3.1-3.4 depend on Phase 2 completion
