## Context

The `CLONE_CURRENT_STATE` strategy implements ETL-compatible `equivalentDiscriminated()` semantics where:
1. First caller receives the **original object** (can mutate it)
2. Subsequent callers receive **clones of the current state** (inherit previous mutations)

In sequential mode, this works because calls happen in order. In parallel mode:
- Multiple threads may call `equivalentDiscriminated()` simultaneously for the same `(source, ruleName)`
- First caller's mutations may not be visible when subsequent callers clone
- Results are non-deterministic between runs

Current implementation throws `IllegalStateException` when `CLONE_CURRENT_STATE + deferredWritesEnabled` (line 2251-2257 in TransformationContext.java).

## Goals / Non-Goals

**Goals:**
- Enable `CLONE_CURRENT_STATE + parallel` with deterministic, ETL-compatible results
- Minimal performance impact on existing code paths
- Clean separation between sequential and parallel implementations

**Non-Goals:**
- Changing CLONE_CURRENT_STATE semantics in sequential mode
- Supporting async mutations (mutations must happen during rule execution)
- Optimizing for pathological cases (thousands of parallel discriminators on same source)

## Decisions

### 1. Coordination Mechanism: Phaser with Version Tracking

**Decision:** Use `java.util.concurrent.Pher` to coordinate mutation phases, tracking versions per `(source, ruleName)`.

**Rationale:**
- Phaser is designed for dynamic party registration (unknown number of callers upfront)
- Unlike CyclicBarrier, Phaser supports multiple phases and can be reused
- Unlike CountDownLatch, Phaser parties can arrive and deregister
- Version tracking ensures each phase has a known completion point

**Alternatives considered:**
- **Simple locks**: Would serialize all calls, losing parallelism
- **StampedLock**: Optimistic reads don't help here (need wait-for-completion)
- **CompletableFuture**: Overkill, adds unnecessary async complexity

### 2. Phase Boundary: Rule Execution Completion

**Decision:** Phase completes when the **rule finishes executing**, not when mutation is detected.

**Rationale:**
- Simpler implementation (no proxy mutation detection)
- Matches ETL semantics (mutations happen during rule body)
- Works with any mutation pattern (direct field access, reflective, etc.)

**Implementation:**
```java
try {
    EObject result = rule.execute(source, context);
    return result;
} finally {
    if (result instanceof PhaseAwareEObject proxy) {
        proxy.signalPhaseComplete();
    }
}
```

### 3. First Caller Handling: Shared Original with Proxy

**Decision:** First caller gets the actual original (not a clone), wrapped in `PhaseAwareEObject` for tracking.

**Rationale:**
- Maintains ETL semantics (first caller gets original, can mutate)
- Proxy enables phase completion signaling without API changes
- Shared reference means all callers see same object (mutations visible)

**Data structure:**
```java
class VersionedOriginal {
    EObject original;           // Shared mutable object (only first caller gets this)
    AtomicLong version;         // Current phase version
    Phaser phaser;              // Coordinates phases
}
```

**Note:** Only ONE first caller exists per `(source, ruleName)` due to atomic `putIfAbsent` in `OriginalTracker`. No need for `firstCallers` counter.

### 4. Subsequent Caller Handling: Wait-and-Clone

**Decision:** Subsequent callers wait for previous phase to complete, then clone from current state.

**Rationale:**
- Ensures clones see all mutations from previous phase
- Deterministic ordering (version N completes before version N+1 starts)
- Timeout prevents deadlock if phase never completes

**Flow:**
```
1. Caller N registers with Phaser (increments party count)
2. Caller N awaits phase N-1 completion (with timeout)
3. Caller N clones from original (has all mutations up to phase N-1)
4. Caller N arrives at phase N
5. Caller N continues execution
6. Rule completion signals phase N complete
```

### 5. Timeout Configuration

**Decision:** Use 1-second timeout with warning log, but allow continuation.

**Rationale:**
- Prevents indefinite hangs if phase never completes
- 1 second is sufficient for normal rule execution
- Warning allows debugging without blocking production
- Fallback: clone from current state (best-effort)

### 6. Memory Management

**Decision:** Clear `VersionedOriginalRegistry` during executor reset, same as `OriginalTracker`.

**Rationale:**
- Consistent with existing cleanup patterns
- No premature cleanup (registry lives for transformation duration)
- No memory leaks (reset clears all entries)

### 7. XMI ID Handling

**Decision:** XMI IDs are handled correctly by the phase coordination mechanism.

**Rationale:**
- First caller sets original's XMI ID to their discriminator
- Phaser ensures subsequent callers clone AFTER first caller completes
- Clone inherits original's final XMI ID (with first caller's discriminator)
- Subsequent caller then overwrites clone's XMI ID with their own discriminator
- Each discriminator ends up with correct, unique XMI ID

**Flow:**
```
First caller (disc="rel1"):
  1. setElementId(original, "base::rel1")
  2. [mutates original]
  3. signalPhaseComplete()

Subsequent caller (disc="rel2"):
  1. [waits for phase 0]
  2. clone = EcoreUtil.copy(original)  // Clone has ID "base::rel1"
  3. setElementId(clone, "base::rel2") // Overwrites clone's ID
  4. return clone
```

**Key invariant:** Only ONE first caller exists per `(source, ruleName)`, enforced by atomic `putIfAbsent` in `OriginalTracker`.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         COMPONENT ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  TransformationContext                                          │   │
│  │                                                                 │   │
│  │  + versionedOriginals: VersionedOriginalRegistry               │   │
│  │  + originalTracker: OriginalTracker (existing)                 │   │
│  │  + activePhaseAwareObjects: ThreadLocal<Set<PhaseAwareEObject>>│   │
│  │  + handleCloneCurrentStateParallel()                           │   │
│  │  + signalAllPhaseAwareObjects()                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│           │                                  │                          │
│           ▼                                  ▼                          │
│  ┌──────────────────────┐      ┌──────────────────────────────────┐    │
│  │ VersionedOriginal    │      │ PhaseAwareEObject (proxy)        │    │
│  │ Registry             │      │                                  │    │
│  │                      │      │ - Wraps original for first       │    │
│  │ - getOrCreate()      │      │   callers only                   │    │
│  │ - ConcurrentHashMap  │      │ - Delegates all EObject ops      │    │
│  │ - clear()            │      │ - Tracks phase for signaling     │    │
│  └──────────────────────┘      └──────────────────────────────────┘    │
│           │                                  │                          │
│           ▼                                  ▼                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  VersionedOriginal (inner class)                                 │   │
│  │                                                                  │   │
│  │  - original: EObject (shared, only first caller gets this)      │   │
│  │  - version: AtomicLong (current phase)                          │   │
│  │  - phaser: Phaser (coordinates phase completion)                │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ThreadLocal Tracking Flow:                                            │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  1. Rule execution starts                                        │   │
│  │  2. equivalentDiscriminated() creates PhaseAwareEObject         │   │
│  │  3. Proxy added to activePhaseAwareObjects (ThreadLocal)        │   │
│  │  4. Rule continues (may create more proxies)                    │   │
│  │  5. Rule completes                                               │   │
│  │  6. signalAllPhaseAwareObjects() called in finally block        │   │
│  │  7. All proxies signal their phases complete                    │   │
│  │  8. Set cleared for next rule execution                         │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Integration Points:                                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  equivalentDiscriminated()                                       │   │
│  │    → detect CLONE_CURRENT_STATE + deferredWritesEnabled          │   │
│  │    → call handleCloneCurrentStateParallel()                     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  TransformationExecutor.executeRuleForSource()                  │   │
│  │    → finally block calls context.signalAllPhaseAwareObjects()   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Risks / Trade-offs

**Risk: Phaser starvation if rule hangs**
- **Mitigation**: 1-second timeout with warning, fallback to current state

**Risk: Memory overhead from tracking entries**
- **Mitigation**: Entries cleared on reset, same lifecycle as existing registries

**Risk: Complexity increase in critical path**
- **Mitigation**: Only affects CLONE_CURRENT_STATE path; sequential mode unchanged

**Risk: Timeout value too short/long**
- **Mitigation**: Configurable via system property, documented with rationale

**Trade-off: Determinism vs performance**
- Phaser coordination adds ~1-2ms per discriminated call
- Enables correct results (worth the cost for CLONE_CURRENT_STATE users)

**Trade-off: API purity vs practicality**
- PhaseAwareEObject is a proxy (adds complexity)
- Avoids API breaking changes (no new methods on user code)

## Compatibility Analysis

### @Extends Inheritance Compatibility

**Challenge:** With `@Extends`, a child rule executes parent rules before its own transform function. If a parent calls `equivalentDiscriminated()`, the PhaseAwareEObject must not be signaled until the entire inheritance chain completes.

```
ChildRule.execute(source, context):
  // Pre-created target already set
  executeParentRulesInChain():
    ParentRule.execute(source, context):
      equivalentDiscriminated() → PhaseAwareEObject #1
      // Parent completes, but CHILD is still running!
  // Child's transform function
  equivalentDiscriminated() → PhaseAwareEObject #2
  // Child completes
  // NOW signalPhaseComplete() should be called for BOTH #1 and #2
```

**Solution: ThreadLocal Tracking**

Use a ThreadLocal Set to track all PhaseAwareEObject instances created during a rule execution:

```java
// In TransformationContext:
private final ThreadLocal<Set<PhaseAwareEObject>> activePhaseAwareObjects =
    ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

// In handleCloneCurrentStateParallel() when returning PhaseAwareEObject:
activePhaseAwareObjects.get().add(proxy);

// In executeRuleForSource() finally block:
void signalAllPhaseAwareObjects() {
    Set<PhaseAwareEObject> active = activePhaseAwareObjects.get();
    for (PhaseAwareEObject proxy : active) {
        proxy.signalPhaseComplete();
    }
    active.clear();
}
```

**This ensures:**
- All PhaseAwareEObject instances from this rule execution are signaled
- Works with @Extends inheritance (signaled after entire chain completes)
- Works with nested equivalentDiscriminated() calls

### RULE_BY_RULE Parallel Compatibility

**Analysis:** RULE_BY_RULE + parallel has barriers between rules, which naturally aligns with phase completion:

```
Rule A (parallel chunks):
  Chunk 1: executeRuleForSource(A, E1) → signals PhaseAwareObjects
  Chunk 2: executeRuleForSource(A, E2) → signals PhaseAwareObjects
  ...
  BARRIER: commitDeferredOperationsIncremental()
  [All chunks complete, all phases signaled]

Rule B (parallel chunks):
  Chunk 1: executeRuleForSource(B, E1) → can see Rule A's results
  ...
```

**Compatibility:** ✓ Fully compatible. The per-rule barrier ensures all phases from Rule A complete before Rule B starts.

### Recursive equivalent() Call Compatibility

**Existing mechanism (lines 1618-1623 in TransformationContext.java):**
```java
Set<RuleCacheKey> inProgress = inProgressRules.get();
if (inProgress.contains(key)) {
    // Recursive call detected - return null to break the cycle
    return null;
}
```

**For equivalentDiscriminated():** Need similar detection for `(source, ruleName, discriminator)` tuples.

**Solution:** Extend existing `inProgressRules` or add new ThreadLocal for discriminated calls:

```java
private final ThreadLocal<Set<DiscriminatedKey>> inProgressDiscriminated =
    ThreadLocal.withInitial(HashSet::new);

// In handleCloneCurrentStateParallel():
DiscriminatedKey key = new DiscriminatedKey(source, ruleName, discriminator);
if (inProgressDiscriminated.get().contains(key)) {
    // Recursive call - return cached result or null
    return resolutionCache.getEquivalentDiscriminated(source, targetType, ruleName, discriminator);
}
```

### Nested equivalentDiscriminated() Call Compatibility

**Scenario:**
```
RuleA.execute():
  equivalentDiscriminated(S1, "LazyB", "disc1") → PhaseAware(B1)
    → LazyB.execute():
      → equivalentDiscriminated(S2, "LazyC", "disc2") → PhaseAware(C1)
        → LazyC.execute()
      → B1 continues with C1
    → B1 completes, signals PhaseAware(C1) only (nested)
  → A continues with B1
```

**With ThreadLocal tracking:**
- LazyC execution adds C1 to activePhaseAwareObjects
- LazyC completes, signals C1, clears from set
- LazyB execution adds B1 to activePhaseAwareObjects
- LazyB completes, signals B1, clears from set
- RuleA completes (no PhaseAware objects in set, already cleared)

**This works correctly because:** ThreadLocal is per-thread, and nested calls complete in LIFO order.

## Compatibility Matrix

| Feature | RULE_BY_RULE Parallel | CLONE_CURRENT_STATE | Notes |
|---------|----------------------|---------------------|-------|
| ETL rule ordering | ✓ | ✓ | Barriers ensure deterministic order |
| @Extends inheritance | ✓ | ✓ | ThreadLocal tracking signals after full chain |
| Recursive equivalent() | ✓ | ✓ | Existing inProgressRules detection |
| Nested equivalentDiscriminated() | ✓ | ✓ | ThreadLocal + LIFO completion order |
| Multiple discriminators per rule | ✓ | ✓ | Set tracks all instances |
| Cross-rule equivalent() | ✓ | ✓ | Cache ensures idempotency |

## Open Questions

1. **Should timeout be configurable?**
   - Current design: 1 second hard-coded
   - Consider: System property `judo.zeta.cloneCurrentState.timeoutSeconds`

2. **Should we support async mutations?**
   - Current design: No, mutations must happen during rule execution
   - Future: Could add explicit `completePhase()` API if needed

3. **Should PhaseAwareEObject be public API?**
   - Current design: Internal class, users interact via existing API
   - Consider: Expose if advanced use cases emerge

4. **Should recursive equivalentDiscriminated() return null or cached?**
   - Current design for equivalent(): returns null
   - Consider: Return cached result if available, null only if not cached
