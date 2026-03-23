## 1. Test (TDD - write failing tests first)

- [x] 1.1 Create `EquivalentDiscriminatedCustomIdTest.java` with the following test scenarios:

  **Scenario A: CLONE_PRISTINE — customId overrides generated discriminated ID**
  - Set up: Source element, @Lazy rule, call `equivalentDiscriminated(source, type, rule, disc, "my/custom/id")`
  - Assert: Returned clone's XMI ID is `"my/custom/id"`, not the generated discriminated ID

  **Scenario B: CLONE_PRISTINE — null customId preserves existing behavior**
  - Set up: Same as A but with `customId=null` (or 4-arg overload)
  - Assert: Returned clone's XMI ID matches `generateDiscriminatedId(baseId, disc)` format

  **Scenario C: CLONE_CURRENT_STATE — first caller gets original with customId**
  - Set up: Strategy = CLONE_CURRENT_STATE, call with `customId = "first/caller/id"`
  - Assert: Returned object is the original (not a clone), XMI ID is `"first/caller/id"`

  **Scenario D: CLONE_CURRENT_STATE — subsequent caller gets clone with customId**
  - Set up: After scenario C, second call with different discriminator and `customId = "second/caller/id"`
  - Assert: Returned object is a clone, XMI ID is `"second/caller/id"`, original retains `"first/caller/id"`

  **Scenario E: XMI ID lookup finds existing element by customId**
  - Set up: Pre-create an element with XMI ID `"existing/id"` in target resource
  - Call `equivalentDiscriminated(source, type, rule, disc, "existing/id")`
  - Assert: Returns the pre-existing element, no new clone created

  **Scenario F: 4-arg overload delegates to 5-arg with null customId**
  - Set up: Call 4-arg overload
  - Assert: Behavior identical to 5-arg with `customId=null`

- [x] 1.2 Run tests — verify scenarios A, C, D, E fail (confirming the new parameter is needed); B and F pass (backward compat)

## 2. Implementation

- [x] 2.1 Add `resolveDiscriminatedId()` private helper to `TransformationContext`:
  - Parameters: `String customId, EObject source, String ruleName, String discriminator, EObject original`
  - When `customId != null` → return `customId`
  - When `useStructuredIds` → return `generateDiscriminatedId(generateStructuredId(source, ruleName), discriminator)`
  - Otherwise → return `getElementId(original) + "/(discriminator/" + discriminator + ")"` with null-safe guard on `original`

- [x] 2.2 Add the 5-arg overload `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)`:
  - Move the method body from the current 4-arg implementation into the 5-arg version
  - Replace all 5 discriminated ID computation points with calls to `resolveDiscriminatedId()`
  - For path [1] (XMI lookup): when `customId != null`, use `customId` as the lookup ID directly
  - For CLONE_CURRENT_STATE: skip `originalTracker.registerBaseId()` when `customId != null`

- [x] 2.3 Convert the 4-arg overload to delegate: `return equivalentDiscriminated(source, targetType, ruleName, discriminator, null);`

- [x] 2.4 Run tests from 1.1 — verify all scenarios pass

## 3. Regression verification

- [x] 3.1 Run full `transformation-core` test suite — all existing tests pass
- [x] 3.2 Run full judo-zeta build (`./mvnw clean install`)
- [x] 3.3 Verify no new test failures compared to baseline

## 4. Spec update

- [x] 4.1 Create `openspec/specs/equivalent-discriminated-custom-id/spec.md` from delta spec (sync or archive)
