## 1. Test (TDD - write failing test first)

- [x] 1.1 Create `ExtendsCreateTargetIdPreservationTest.java` with the following test scenarios:

  **Scenario A: Parent rule's createTarget(Class, source, "BaseRule") correctly overwrites child's ID (ETL semantics)**
  - Set up: Abstract parent rule "BaseRule" that calls `ctx.createTarget(EPackage.class, source, "BaseRule")`
  - Set up: Concrete child rule "ConcreteRule" with `@Extends("BaseRule")`
  - Assert: Target element's XMI ID ends with `/BaseRule` (parent's custom ID wins in ETL)

  **Scenario B: Parent rule creating additional element after getting pre-created target must NOT collide**
  - Set up: Abstract parent "BaseWithSecond" that gets pre-created target via `createTarget(EPackage.class, source, "BaseWithSecond")` then creates second element via `createTarget(EAnnotation.class)`
  - Set up: Concrete child "ConcreteWithSecond" with `@Extends("BaseWithSecond")`
  - Assert: Second element's auto-generated ID is different from pre-created target's overwritten ID

  **Scenario C: Normal (non-inheritance) `createTarget(Class, String)` still applies custom ID**
  - Set up: A non-abstract rule that calls `ctx.createTarget(EPackage.class, "my-custom-id")`
  - Assert: Target element has XMI ID "my-custom-id"

- [x] 1.2 Run test — verify Scenario B **FAILS** (confirming the collision bug exists), Scenarios A and C pass

## 2. Implementation

- [x] 2.1 Fix `TransformationContext.createTargetInPackage()` pre-created target early return:
  - When returning the pre-created target (line 1424), increment `ruleInstanceCounters` for the current rule
  - This ensures subsequent `createTarget()` calls in the same parent rule generate unique suffixed IDs
  - The parent's `createTarget(Class, source, "ParentRule")` still correctly overwrites the child's ID via `setElementIdInternal` (ETL semantics preserved)

- [x] 2.2 Run test from 1.1 — verify all three scenarios **PASS**

## 3. Regression verification

- [x] 3.1 Run full `transformation-core` test suite: 1026 tests, 0 failures
- [x] 3.2 Run full judo-zeta build: BUILD SUCCESS
- [x] 3.3 Verify no new test failures compared to baseline

## 4. Spec update

- [x] 4.1 Update `openspec/specs/extends-execute-parent-isolation/spec.md` with new requirement:

  **Requirement: Pre-created target return increments ruleInstanceCounters during @Extends chain**
  - WHEN `createTargetInPackage()` returns the pre-created target early during `@Extends` inheritance
  - THEN the `ruleInstanceCounters` SHALL be incremented for the current (source, rule) key
  - SO THAT subsequent `createTarget()` calls generate unique suffixed IDs
  - AND parent rule's explicit `createTarget(Class, source, suffix)` correctly overwrites child's ID (ETL semantics)
