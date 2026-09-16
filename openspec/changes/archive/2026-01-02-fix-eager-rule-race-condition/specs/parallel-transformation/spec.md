# Spec Delta: parallel-transformation

## ADDED Requirements

### Requirement: Proxy Unwrapping Before Resource Addition

Proxy objects created by deferred writes MUST be unwrapped before being added to the target EMF Resource.

#### Scenario: Staged elements are unwrapped before commit

**Given** deferred writes mode is enabled
**And** transformation creates proxied target elements
**When** `commitStagedElements()` is called
**Then** all proxied elements are unwrapped to real EObjects
**And** the target resource contains only real EObjects
**And** no `DeferredEObject.ProxyMarker` instances exist in resource

#### Scenario: Model serialization succeeds after parallel transformation

**Given** parallel transformation with deferred writes enabled
**And** transformation completes with staged elements
**When** the target model is serialized to XMI
**Then** serialization completes without ClassCastException
**And** all containment features serialize correctly
**And** output XMI is valid and well-formed

### Requirement: Proxy Reference Cleanup

Reference values in the transformed model MUST NOT contain proxy objects after transformation completes.

#### Scenario: Post-commit cleanup unwraps remaining proxies

**Given** parallel transformation has completed
**And** some reference values may contain proxy objects
**When** `unwrapAllProxiesInModel()` is called
**Then** all EReference values are scanned for proxies
**And** proxy references are replaced with unwrapped real objects
**And** no proxy instances remain in the model graph
