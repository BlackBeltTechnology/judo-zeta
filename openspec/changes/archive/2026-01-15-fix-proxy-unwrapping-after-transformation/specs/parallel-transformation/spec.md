# Spec Delta: Parallel Transformation - Proxy Unwrapping

**Capability**: parallel-transformation
**Change**: fix-proxy-unwrapping-after-transformation

## ADDED Requirements

### Requirement: Automatic Proxy Unwrapping After Commit

After the commit phase completes in parallel transformation, all deferred proxy objects MUST be replaced with their underlying EMF delegates throughout the model.

#### Scenario: Containment reference proxies are unwrapped

**Given** parallel transformation has completed
**And** elements were added to containment references (e.g., `getEOperations()`)
**When** the commit phase finishes
**Then** all containment references contain real EMF objects (e.g., `EOperationImpl`)
**And** no proxy objects (`$ProxyN`) remain in containment collections
**And** iterating `getEAllOperations()` returns castable implementation types

#### Scenario: Non-containment reference proxies are unwrapped

**Given** parallel transformation has completed
**And** proxy objects were set as non-containment references
**When** the commit phase finishes
**Then** all non-containment references point to real EMF objects
**And** no proxy objects remain in reference values
**And** casting to implementation types succeeds

#### Scenario: Nested containment proxies are unwrapped

**Given** parallel transformation has completed
**And** proxies exist in deeply nested containment hierarchies
**When** the commit phase finishes
**Then** the model is recursively traversed
**And** all nested proxy references are replaced with delegates
**And** no proxies remain at any nesting level

#### Scenario: Model is usable by EMF internal operations

**Given** parallel transformation has completed
**And** the commit phase has finished
**When** EMF internal operations access the model (e.g., `EClassImpl.getEAllOperations()`)
**Then** all elements are real EMF objects
**And** no ClassCastException occurs
**And** EMF reflection APIs work correctly

---

### Requirement: Proxy Unwrap Metrics

Proxy unwrapping duration MUST be tracked in transformation metrics when metrics are enabled.

#### Scenario: Unwrap duration is measured

**Given** transformation metrics are enabled
**And** parallel transformation completes
**When** proxy unwrapping occurs
**Then** the unwrap duration is recorded in metrics
**And** the count of unwrapped proxies is available
