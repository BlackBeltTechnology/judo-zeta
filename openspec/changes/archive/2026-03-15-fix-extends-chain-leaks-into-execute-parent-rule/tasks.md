## 1. Write Failing Tests (TDD — Red Phase)

- [x] 1.1 Create `ExtendsPrecreatedTargetLeakTest.java` in `transformation-core/src/test/java/hu/blackbelt/judo/zeta/transformation/core/` with a transformation that reproduces the bug: `@Extends` rule calling `executeParentRule` for a principal whose type is a subtype of the outer pre-created target type
- [x] 1.2 Add test `testNonLazyExecuteParentRuleInsideExtendsChainGetsFreshTarget` — asserts that the returned target from the inner rule is a NEW instance (not the pre-created ActorType target from the outer chain)
- [x] 1.3 Verify tests FAIL before the fix (`mvn test -pl transformation-core -Dtest=ExtendsPrecreatedTargetLeakTest`)

## 2. Apply the Fix

- [x] 2.1 In `TransformationContext.java`, locate the `executeParentRule` method; find the condition `wasInInheritance && previousPreCreated != null && parentRule.isLazy()` and remove `&& parentRule.isLazy()` so all non-lazy (and lazy) `target=null` calls clear the inheritance state

## 3. Verify Fix

- [x] 3.1 Run failing tests again and confirm they now PASS (`mvn test -pl transformation-core -Dtest=ExtendsPrecreatedTargetLeakTest`)
- [x] 3.2 Run full test suite (`mvn test -pl transformation-core`) and confirm all existing tests still pass
