# validation-core/src/test/java/hu/blackbelt/judo/zeta/validation/integration — agent notes

Whole-stack validation tests: registry + context + executor + extension methods driven together over a real ECore model, as opposed to the per-class unit tests in `../core`.

| File | Purpose |
|---|---|
| `ValidationFrameworkIntegrationTest.java` | Exercises the full `register → build context → ValidationExecutor.validate() → inspect ValidationResult` path against an ECore model. Exports `@Test` methods `completeValidationWorkflowWithECoreModel` (the canonical wiring, the one to copy when adding a metamodel validator), `validationWithExtensionMethods`, `validationWithCaching`, `validationWithHooks`, `validationWithGuards` (asserts a non-abstract `EClass` is guarded out and an abstract one is not), `queryAllInstancesOfType`, `mixedConstraintsAndCritiques` (`ERROR` and `WARNING` results returned from one run). Extends `AbstractValidationTest`, so `resourceSet`/`registry`/`context` are pre-wired and fixtures come from `../TestValidators` and `../TestModelFactory`. Package-private class, JUnit 5, `@DisplayName("Validation Framework Integration Tests")`. Guard-skip results are indistinguishable from passes here — assertions count FAILED results, never "rule did not run". |
