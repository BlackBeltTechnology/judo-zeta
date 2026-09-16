## ADDED Requirements

### Requirement: Public Element ID Resolution API

The `TransformationContext` SHALL expose a public `getElementId(EObject)` method that returns the element's XMI ID, checking pending (deferred) IDs before committed IDs. This enables transformation code to build discriminators using target element IDs reliably.

#### Scenario: Pending ID takes precedence over committed ID

**Given** an element with a committed XMI ID "committed-id" in the resource
**And** the element has a pending ID "pending-id" in `pendingXmiIds`
**When** `ctx.getElementId(element)` is called
**Then** the result SHALL be "pending-id"
**Because** pending IDs represent the current transformation state

#### Scenario: Committed ID returned when no pending ID exists

**Given** an element with a committed XMI ID "committed-id" in the resource
**And** the element has no pending ID in `pendingXmiIds`
**When** `ctx.getElementId(element)` is called
**Then** the result SHALL be "committed-id"

#### Scenario: UUID generated for elements without ID

**Given** an element without any XMI ID
**And** staging is enabled via `ctx.enableStaging()`
**When** `ctx.getElementId(element)` is called
**Then** a UUID SHALL be generated starting with underscore (e.g., "_abc123...")
**And** the UUID SHALL be cached in `pendingXmiIds` for consistency
**And** subsequent calls SHALL return the same UUID

### Requirement: ID Resolution Order

The `getElementId(EObject)` method SHALL resolve IDs in the following order of precedence:

1. **Pending IDs**: Check `pendingXmiIds` map first (highest priority)
2. **Resource URI Fragment**: Check `resource.getURIFragment(element)` excluding path-based fragments
3. **Structural Feature**: Check for "id" structural feature on the element's EClass
4. **Generated UUID**: Generate and cache a new UUID (lowest priority)

#### Scenario: ID resolution follows defined order

**Given** an element with ID sources at multiple levels
**When** `ctx.getElementId(element)` is called
**Then** the method SHALL return the ID from the highest-priority available source
**And** the order of priority SHALL be: pending > resource > feature > generated

### Requirement: Thread-Safe ID Resolution

The `getElementId(EObject)` method SHALL be thread-safe for concurrent access. Multiple threads calling `getElementId()` for the same element SHALL always receive the same result.

#### Scenario: Concurrent getElementId calls return consistent ID

**Given** multiple threads calling `ctx.getElementId(element)` concurrently
**And** the element has no existing ID
**When** all threads complete
**Then** all threads SHALL have received the same generated UUID
**And** no race conditions SHALL cause duplicate or inconsistent IDs

### Requirement: Discriminator Construction with Target Element IDs

Transformation code SHALL be able to use `ctx.getElementId(targetElement)` to build discriminators that include target element IDs, even when those IDs are deferred (not yet committed to XMI resource).

#### Scenario: Building discriminator with deferred target element ID

**Given** a target element created during transformation
**And** the element's ID is stored in `pendingXmiIds` (deferred)
**When** transformation code calls `ctx.getElementId(targetElement)`
**Then** the pending ID SHALL be returned (not null)
**And** the ID can be used to build a discriminator string
**And** `ctx.equivalentDiscriminated(source, type, rule, discriminator)` SHALL work correctly

#### Scenario: Standard XMIResource.getID fails for deferred elements

**Given** a target element with a deferred ID in `pendingXmiIds`
**When** `XMIResource.getID(element)` is called directly
**Then** the result SHALL be null
**Because** deferred IDs are not yet committed to the XMI resource
**Therefore** transformation code MUST use `ctx.getElementId()` instead
