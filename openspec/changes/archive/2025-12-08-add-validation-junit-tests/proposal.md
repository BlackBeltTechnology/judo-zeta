# Proposal: Add JUnit Tests for Validation Framework

**Change ID**: `add-validation-junit-tests`  
**Status**: Draft  
**Created**: 2025-12-04  
**Type**: Enhancement

## Summary

Add comprehensive JUnit test suite for the `validation-core` module to ensure the validation framework components work correctly. The tests will verify registry, executor, context, caching, extension methods, and validation rules using ECore metamodel elements.

## Motivation

Currently, the `validation-core` module lacks automated tests, making it difficult to:
- Verify correctness of the validation framework components
- Catch regressions when making changes
- Demonstrate usage patterns to developers
- Ensure proper integration with EMF/ECore models
- Validate caching mechanisms and parallel execution

The reference implementation in `/tmp/judo-meta-esm/model-test` demonstrates comprehensive testing patterns that should be replicated for this project.

## Goals

1. **Core Component Testing**: Test `ValidationRegistry`, `ValidationExecutor`, `ValidationContext`, `ValidatorDescriptor`, and `ValidationResult`
2. **Caching and Performance**: Verify caching mechanisms work correctly (satisfies cache, extension method cache)
3. **Extension Methods**: Test `ExtensionMethodRegistry` and extension method invocation
4. **Annotation Processing**: Verify all validation annotations work correctly (`@Constraint`, `@Critique`, `@Guard`, `@Satisfies`, `@Cached`, etc.)
5. **EMF Integration**: Test integration with ECore metamodels and elements
6. **Parallel Execution**: Verify parallel validation execution works correctly
7. **Hooks**: Test pre- and post-validation hooks

## Non-Goals

- Performance benchmarking (beyond basic verification)
- Integration tests with real metamodels beyond simple test cases
- UI or visualization testing
- Testing of external validator implementations

## Scope

### In Scope

- Unit tests for all public APIs in `validation-core`
- Test fixtures using simple ECore model elements
- Verification of caching behavior
- Verification of parallel vs sequential execution
- Testing of annotation-based validation rules
- Testing of extension method registry
- Maven dependencies for JUnit 5 and test utilities

### Out of Scope

- OSGi integration tests (covered by `osgi-itest` module)
- Generator or code generation testing
- Performance optimization

## Dependencies

- Reference tests in `/tmp/judo-meta-esm/model-test/` for patterns and structure
- JUnit 5 (Jupiter) for test framework
- EMF ECore for test model creation
- Existing validation-core implementation

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Test fixtures may be complex to create | Medium | Use simple ECore elements; reference examples exist in judo-meta-esm |
| Tests may be tightly coupled to implementation | Medium | Focus on public API contracts; use interface-based testing |
| Parallel execution tests may be flaky | Low | Use deterministic test data; verify behavior rather than timing |

## Clarifications

Based on feedback, the following decisions have been made:

1. **Test Location**: Tests will be placed in `validation-core/src/test/java/` following standard Maven conventions

2. **Test Metamodel**: Tests will use the actual **ECore metamodel** (EClass, EPackage, EAttribute, EReference, etc.) which is already available in the project. The ECore metamodel is the foundation for all EMF models and provides realistic test scenarios.

3. **Message Verification**: Tests MUST verify both constraint names AND exact error messages to ensure proper error reporting to users

## Alternatives Considered

1. **Manual Testing Only**
   - Pros: No test maintenance burden
   - Cons: High risk of regressions, no documentation of expected behavior
   - Decision: Rejected - automated tests are essential

2. **Integration Tests Only**
   - Pros: Tests real-world usage
   - Cons: Slower, harder to debug, doesn't test components in isolation
   - Decision: Rejected - need both unit and integration tests

3. **Use Different Test Framework (JUnit 4, TestNG)**
   - Pros: More familiar to some developers
   - Cons: JUnit 5 is modern standard, reference project uses it
   - Decision: Use JUnit 5 for consistency

## Success Criteria

- [ ] All validation-core components have corresponding test classes
- [ ] Test coverage includes happy paths, error cases, and edge cases
- [ ] Tests execute successfully in Maven build
- [ ] Tests serve as usage documentation for the framework
- [ ] No flaky tests (all tests pass consistently)
- [ ] Build time increase is acceptable (< 30 seconds for all tests)

## Related Changes

None - this is a standalone enhancement to add missing test coverage.

## References

- Reference implementation: `/tmp/judo-meta-esm/model-test/`
- Validation framework code: `validation-core/src/main/java/`
- EMF ECore documentation: https://www.eclipse.org/modeling/emf/
