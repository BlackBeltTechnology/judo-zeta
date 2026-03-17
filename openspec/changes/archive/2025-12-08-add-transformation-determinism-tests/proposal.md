# Proposal: Add Transformation Determinism Tests

**Change ID**: `add-transformation-determinism-tests`  
**Status**: Draft  
**Created**: 2025-12-08  
**Type**: Enhancement

## Summary

Add JUnit tests to the `transformation-core` module that verify sequential and parallel transformation execution produce identical results. These tests ensure the deterministic ordering guarantees documented in the parallel-transformation spec are actually working.

## Motivation

The parallel transformation implementation includes mechanisms for deterministic element ordering:
- `AtomicLong creationSequence` for unique sequence numbers
- Sorting during `commitStagedElements()` 
- Recursive ordering of contained elements

However, there are currently no tests that verify the actual determinism guarantee: that running the same transformation in sequential vs parallel mode produces byte-for-byte identical XMI output.

The existing `ConcurrencyStressTest` verifies thread-safety (no exceptions, no data loss) but does NOT verify determinism (identical output).

## Goals

1. **Output Equivalence**: Verify sequential and parallel transformations produce identical target models
2. **XMI Serialization**: Verify XMI serialization produces identical byte output
3. **Multiple Runs**: Verify multiple parallel runs produce identical results
4. **Edge Cases**: Test determinism with nested containment, multiple rule types, and varied element counts

## Non-Goals

- Performance benchmarking (covered elsewhere)
- Thread-safety testing (covered by `ConcurrencyStressTest`)
- New determinism features (implementation already exists)

## Scope

### In Scope

- New test class `TransformationDeterminismTest.java`
- Tests comparing sequential vs parallel output
- Tests comparing multiple parallel runs
- XMI serialization comparison
- EMF structural comparison using `EcoreUtil.equals()`

### Out of Scope

- Changes to transformation implementation
- Changes to existing tests
- OSGi integration tests

## Approach

### Comparison Methods

1. **XMI Byte Comparison**: Serialize both models to XMI and compare bytes
2. **EMF Structural Comparison**: Use `EcoreUtil.equals()` for element-by-element comparison
3. **Element Order Verification**: Verify elements appear in same order in both resources

### Test Scenarios

1. Simple flat transformation (no containment)
2. Transformation with parent-child containment
3. Transformation with deep nesting (3+ levels)
4. Mixed rule types (primary + lazy rules)
5. Large model transformation (10,000+ elements)

## Dependencies

- Existing parallel transformation implementation
- Existing test infrastructure (`ConcurrencyStressTest` patterns)
- EMF ECore for test models

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Non-deterministic test environment | Medium | Use fixed thread pool, multiple iterations |
| XMI serialization differences | Low | Normalize whitespace, use same XMI options |
| Test flakiness | Medium | Run tests repeatedly (50+ iterations) |

## Success Criteria

- [ ] All determinism tests pass consistently (100% over 50 runs)
- [ ] Tests verify XMI output is byte-identical
- [ ] Tests verify element ordering is deterministic
- [ ] Tests cover nested containment scenarios
- [ ] Tests execute in < 60 seconds total

## Related Changes

- **parallel-transformation spec**: This change validates the "Deterministic Element Ordering" requirement

## References

- Parallel transformation spec: `openspec/specs/parallel-transformation/spec.md`
- Existing concurrency tests: `ConcurrencyStressTest.java`
- EMF comparison: `EcoreUtil.equals()`
