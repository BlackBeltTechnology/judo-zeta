# Tasks: Verify Idempotent Caching Correctness

## Phase 1: Add Tests Verifying Correct Behavior

- [ ] 1.1 Create `IdempotentCachingTest.java` verifying Zeta's correct behavior
  - Same source element accessed from different calling contexts
  - Verify Zeta returns same cached target (correct idempotent behavior)
  - Document that ETL's compound ID behavior is a bug
- [ ] 1.2 Add test for multi-context rule invocation
  - Rule A calls equivalent(op, "CreateOperationBody") from context X
  - Rule B calls equivalent(op, "CreateOperationBody") from context Y
  - Verify both get the SAME target (correct behavior)
  - Assert single target instance created
- [ ] 1.3 Add test verifying idempotent cache key design
  - Confirm cache key is (source, ruleName) only
  - Verify calling context does NOT affect cache lookup
  - Document this as intentional design for idempotency
- [ ] 1.4 Add test verifying uniform ID generation
  - Verify all IDs are simple format: `(esm/X)/RuleName`
  - Verify NO compound IDs like `((esm/A)/X)_((esm/B)/Y)` are generated
  - Document this as correct behavior (ETL compound IDs are a bug)

## Phase 2: Document ETL Bug and Correct Behavior

- [ ] 2.1 Update `docs/transformation/etl-comparison/feature-parity.md`
  - Add section "Known ETL Bugs - Compound XMI IDs"
  - Document that ETL compound ID behavior violates idempotency
  - Explain why Zeta's behavior is correct
  - List expected XMI ID differences (not bugs, correct behavior)
- [ ] 2.2 Add inline documentation to `ElementResolutionCache.java`
  - Document that cache key (source, ruleName) ensures idempotency
  - Note that excluding calling context is intentional design
  - Reference the parallel-transformation spec requirements
- [ ] 2.3 Add inline documentation to `TransformationContext.equivalent()`
  - Document idempotent behavior: same (source, ruleName) = same target
  - Note that ETL's context-dependent behavior is a bug we don't replicate

## Phase 3: Update Specs

- [ ] 3.1 Update `etl-patterns` spec
  - Document ETL compound ID bug
  - Clarify Zeta's correct idempotent behavior
  - Include scenarios showing expected behavior
- [ ] 3.2 Review `greedy-target-lookup` spec
  - Verify it correctly documents idempotent caching
  - Add note that calling context does not affect lookup

## Phase 4: Validation

- [ ] 4.1 Run all transformation-core tests
- [ ] 4.2 Verify new tests pass (confirming correct behavior)
- [ ] 4.3 Run `openspec validate verify-idempotent-caching-correctness --strict`

## Dependencies

- None - this verifies existing correct behavior

## Parallelization

- Tasks 1.1-1.4 can be done in parallel (independent tests)
- Tasks 2.1-2.3 can be done in parallel (independent documentation)
- Phase 3 depends on Phase 1 completion (tests inform spec wording)
- Phase 4 depends on all previous phases
