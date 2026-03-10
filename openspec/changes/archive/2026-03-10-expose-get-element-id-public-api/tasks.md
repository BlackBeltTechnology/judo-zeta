# Tasks: Expose getElementId() as Public API

## Phase 1: TDD Test Creation (RED)

- [x] **Task 1.1**: Create test file `GetElementIdPublicApiTest.java`
  - Location: `transformation-core/src/test/java/.../GetElementIdPublicApiTest.java`
  - Tests cover: pending ID, committed ID, generated ID, null handling
  - Tests verify: thread safety, discriminator construction use case
  - **Status**: COMPLETED - 16 tests created

## Phase 2: Implementation (GREEN)

- [x] **Task 2.1**: Change `getElementId()` visibility from private to public
  - File: `TransformationContext.java:2995`
  - Change: `private String getElementId(EObject element)` → `public String getElementId(EObject element)`
  - **Status**: COMPLETED

- [x] **Task 2.2**: Update Javadoc for public API
  - File: `TransformationContext.java:2971-2994`
  - Added comprehensive documentation explaining:
    - ID resolution order (pending → resource → feature → generated)
    - Thread safety guarantees
    - Use case for discriminator construction
    - Comparison to XMIResource.getID() behavior
  - **Status**: COMPLETED

## Phase 3: Verification (REFACTOR)

- [x] **Task 3.1**: Run TDD tests
  - Command: `mvn test -pl transformation-core -Dtest=GetElementIdPublicApiTest`
  - **Result**: All 16 tests pass (GREEN phase)

- [x] **Task 3.2**: Run full test suite
  - Command: `mvn test -pl transformation-core`
  - **Result**: 765 tests pass, 0 failures, 4 skipped (no regression)

- [x] **Task 3.3**: Verify parallel execution tests
  - Thread safety tests included in GetElementIdPublicApiTest
  - **Result**: Thread safety confirmed

## Phase 4: Documentation

- [x] **Task 4.1**: Javadoc updated with comprehensive documentation
  - No separate AGENTS.md update needed (API documented in source)

## Dependencies

```
Task 1.1 → Task 2.1 → Task 2.2 → Task 3.1 → Task 3.2 → Task 3.3
                                                   ↘
                                                    Task 4.1
```

## Validation Checkpoints

| Checkpoint | Command | Result |
|------------|---------|--------|
| Tests compile | `mvn test-compile -pl transformation-core` | ✅ SUCCESS |
| TDD tests pass | `mvn test -pl transformation-core -Dtest=GetElementIdPublicApiTest` | ✅ 16 tests pass |
| No regression | `mvn test -pl transformation-core` | ✅ 765 tests pass |
| OpenSpec valid | `openspec validate expose-get-element-id-public-api --strict` | ✅ Valid |

## Implementation Summary

**Change Applied:** 2026-01-17

The `getElementId(EObject)` method in `TransformationContext` has been changed from `private` to `public`, enabling transformation code to reliably get element IDs regardless of whether they are pending (deferred) or committed.

### Files Modified

1. **TransformationContext.java**
   - Line 2995: `private` → `public`
   - Lines 2971-2994: Updated Javadoc

2. **GetElementIdPublicApiTest.java** (NEW)
   - 16 test cases covering all scenarios
