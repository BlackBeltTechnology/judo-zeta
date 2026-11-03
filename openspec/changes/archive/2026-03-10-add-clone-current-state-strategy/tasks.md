## 1. New Types

- [x] 1.1 Create `EquivalentDiscriminatedStrategy` enum with `CLONE_PRISTINE` and `CLONE_CURRENT_STATE` values in `transformation-core`
- [x] 1.2 Create `OriginalTracker` class with `checkAndRegisterFirstCall()`, `FirstCallResult` record, and `clear()` method

## 2. TransformationContext Integration

- [x] 2.1 Add `equivalentDiscriminatedStrategy` field (default `CLONE_PRISTINE`), getter, and setter to `TransformationContext`
- [x] 2.2 Add `OriginalTracker` instance field to `TransformationContext`, initialize in constructor, call `clear()` on reset
- [x] 2.3 Add fail-fast validation: throw `IllegalStateException` when `CLONE_CURRENT_STATE` + `deferredWritesEnabled` in `equivalentDiscriminated()`
- [x] 2.4 Suppress `inDiscriminatedExecution` flag when `CLONE_CURRENT_STATE` is active (line ~2218 condition change)
- [x] 2.5 Add `CLONE_CURRENT_STATE` branch in clone section (after line 2267): first call returns original with discriminated ID, subsequent calls clone from current state via `EcoreUtil.copy(original)`
- [x] 2.6 Ensure first-caller path handles resource addition, XMI ID assignment, and caching correctly (mirror existing DISCRIMINATOR_ONLY logic for original-return pattern)

## 3. Tests

Create `EquivalentDiscriminatedStrategyTest.java` with shared test setup (EcorePackage-based, @Lazy TestRule + @Lazy @Detached TestDetachedRule).

### Critical (must-pass for release)

- [x] 3.1 Test 1: First caller receives original object (`assertSame`)
- [x] 3.2 Test 2: Second caller receives clone (different identity, name = "base")
- [x] 3.3 Test 3: Mutations by first caller propagate to subsequent clones (name = "base::s1" on clone)
- [x] 3.4 Test 4: Clones copy from original only — resultC gets "base::s1" not "base::s1::s2"
- [x] 3.5 Test 8: Fail-fast when deferred writes enabled (`IllegalStateException`)
- [x] 3.6 Test 9: CLONE_PRISTINE behavior unchanged — clone gets "base" not "base::suffix"

### High priority

- [x] 3.7 Test 5: Same discriminator returns cached result (`assertSame`)
- [x] 3.8 Test 6: First caller's XMI ID contains "/(discriminator/discA)"
- [x] 3.9 Test 7: Clone XMI IDs are unique per discriminator
- [x] 3.10 Test 10: Different source objects are independent
- [x] 3.11 Test 13: Multiple single-valued properties (name + isAbstract) propagate
- [x] 3.12 Test 14: List property (eStructuralFeatures) mutations propagate as deep copy

### Medium priority

- [x] 3.13 Test 11: @Detached clones not added to Resource
- [x] 3.14 Test 12: Non-detached clones ARE added to Resource

### Regression

- [x] 3.15 Verify existing `EquivalentDiscriminatedRaceTest` still passes (no regression in CLONE_PRISTINE path)
