# Spec Delta: Parallel Transformation - Proxy Unwrapping

**Change ID**: fix-bidirectional-reference-race-condition
**Capability**: parallel-transformation

## ADDED Requirements

### Requirement: Proxy Values Unwrapped Before EMF Operations

The deferred writes infrastructure MUST unwrap proxy values at queue time, not at apply time, to ensure EMF's internal mechanisms receive real EObject instances.

#### Scenario: Reference value unwrapped before queueing

**Given** deferred writes mode is enabled
**And** a transformation rule sets a reference: `target.setRef(proxyValue)`
**When** the operation is queued via `DeferredEObject.handleSet()`
**Then** the proxy value is unwrapped to its delegate before storing in `SetReferenceOp`
**And** the operation contains only real EObject instances
**And** `pendingValues` may still store the proxy for read-after-write consistency

#### Scenario: List element unwrapped before queueing

**Given** deferred writes mode is enabled
**And** a transformation rule adds to a list: `target.getList().add(proxyElement)`
**When** the operation is queued via `DeferredEList.add()`
**Then** the proxy element is unwrapped before storing in `AddToListOp`
**And** EMF never sees proxy instances during list modification

#### Scenario: Bidirectional references work correctly

**Given** EMF model with bidirectional references `A.refToB` ↔ `B.refToA`
**And** deferred writes mode is enabled
**When** a rule sets `a.refToB = b` (where both may be proxies)
**And** the deferred operation is committed
**Then** EMF's inverse handling receives real EObject instances
**And** `b.refToA` is automatically set to real `a` (not proxy)
**And** EMF validation passes with no "opposite features do not refer to each other" errors

---

### Requirement: EMF Inverse Handling Compatibility

Deferred operations MUST be compatible with EMF's `eInverseAdd`/`eInverseRemove` mechanism for bidirectional references.

#### Scenario: Inverse set during commit

**Given** a `SetReferenceOp` for a bidirectional reference feature
**When** `apply()` calls `target.eSet(feature, value)`
**Then** EMF's generated setter invokes `value.eInverseAdd(target, ...)`
**And** both `target` and `value` are real EObject instances (not proxies)
**And** the inverse reference is correctly established

#### Scenario: No EMF validation errors after parallel transformation

**Given** a parallel transformation with bidirectional references
**When** transformation completes and EMF validation runs
**Then** no "opposite features do not refer to each other" errors occur
**And** all bidirectional invariants are satisfied
