## 1. Core Infrastructure

- [ ] 1.1 Create `VersionedOriginalRegistry` class with `VersionedOriginal` inner class
- [ ] 1.2 Create `PhaseAwareEObject` proxy class implementing EObject delegation
- [ ] 1.3 Add `versionedOriginals` field to `TransformationContext`
- [ ] 1.4 Add `activePhaseAwareObjects` ThreadLocal field to `TransformationContext`
- [ ] 1.5 Implement `signalAllPhaseAwareObjects()` method in `TransformationContext`
- [ ] 1.6 Add `clear()` calls to `TransformationContext.reset()` for both registries

## 2. equivalentDiscriminated() Integration

- [ ] 2.1 Modify `equivalentDiscriminated()` to detect `CLONE_CURRENT_STATE + deferredWritesEnabled`
- [ ] 2.2 Create `handleCloneCurrentStateParallel()` method in `TransformationContext`
- [ ] 2.3 Implement first caller path: return original wrapped in `PhaseAwareEObject`, add to ThreadLocal set
- [ ] 2.4 Implement subsequent caller path: wait for phase, clone, cache, return
- [ ] 2.5 Add recursive call detection for `(source, ruleName, discriminator)` tuples
- [ ] 2.6 Remove or modify the `IllegalStateException` throw at line 2251-2257

## 3. Rule Execution Integration

- [ ] 3.1 Modify `TransformationExecutor.executeRuleForSource()` to add finally block
- [ ] 3.2 In finally: call `context.signalAllPhaseAwareObjects()`
- [ ] 3.3 Add logging for phase transitions, timeout events, and signaling
- [ ] 3.4 Verify @Extends inheritance chain completes before signaling

## 4. Test Coverage

- [ ] 4.1 Test: Parallel CLONE_CURRENT_STATE race - only one caller wins first-call
- [ ] 4.2 Test: Parallel CLONE_CURRENT_STATE - subsequent caller waits for first caller
- [ ] 4.3 Test: Mutation propagation from first to subsequent caller in parallel
- [ ] 4.4 Test: XMI ID correctness - first caller's ID on original, subsequent's ID on clone
- [ ] 4.5 Test: Timeout scenario when phase never completes
- [ ] 4.6 Test: Sequential mode unchanged (no proxy, no version tracking)
- [ ] 4.7 Test: Multiple discriminators across multiple phases
- [ ] 4.8 Test: Registry cleared on reset
- [ ] 4.9 Test: Integration with discrimination cache

### Compatibility Tests

- [ ] 4.10 Test: @Extends inheritance - PhaseAwareObject signaled after entire chain completes
- [ ] 4.11 Test: Multiple @Extends levels - all PhaseAwareObjects signaled at end
- [ ] 4.12 Test: RULE_BY_RULE parallel - phase signaling aligns with rule barriers
- [ ] 4.13 Test: RULE_BY_RULE - cross-rule equivalentDiscriminated sees previous results
- [ ] 4.14 Test: Recursive equivalentDiscriminated - returns cached or null (no new proxy)
- [ ] 4.15 Test: Nested lazy rule execution - LIFO signaling order
- [ ] 4.16 Test: Multiple discriminators in same rule - all signaled on completion
- [ ] 4.17 Test: ThreadLocal set uses identity comparison

## 5. Documentation

- [ ] 5.1 Update QUICK-REF.md with CLONE_CURRENT_STATE + parallel support
- [ ] 5.2 Update ANNOTATIONS.md if needed
- [ ] 5.3 Add Javadoc to `VersionedOriginalRegistry` and `PhaseAwareEObject`
- [ ] 5.4 Update error messages to reflect new capability

## 6. Validation

- [ ] 6.1 Run existing CLONE_CURRENT_STATE tests (should still pass)
- [ ] 6.2 Run parallel transformation tests (should still pass)
- [ ] 6.3 Run full test suite to verify no regressions
- [ ] 6.4 Performance test: measure overhead of Phaser coordination
