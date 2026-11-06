## ADDED Requirements

### Requirement: equivalentDiscriminated supports explicit customId for clone XMI ID

`TransformationContext` SHALL provide a 5-argument overload of `equivalentDiscriminated` that accepts an optional `customId` parameter. When `customId` is non-null, it SHALL be used as the clone's (or original's) XMI ID instead of the generated discriminated ID.

The existing 4-argument overload SHALL delegate to the 5-argument overload with `customId=null`, preserving full backward compatibility.

#### Scenario: customId overrides generated discriminated ID (CLONE_PRISTINE)

- **WHEN** a caller invokes `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)` with `customId = "my/custom/id"` and the strategy is `CLONE_PRISTINE`
- **THEN** the returned clone's XMI ID SHALL be `"my/custom/id"`
- **AND** it SHALL NOT be `generateDiscriminatedId(generateStructuredId(source, ruleName), discriminator)`

#### Scenario: customId overrides generated discriminated ID (CLONE_CURRENT_STATE first caller)

- **WHEN** a caller invokes `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)` with `customId = "my/custom/id"` and the strategy is `CLONE_CURRENT_STATE` and this is the first call for `(source, ruleName)`
- **THEN** the returned original's XMI ID SHALL be `"my/custom/id"`

#### Scenario: customId overrides generated discriminated ID (CLONE_CURRENT_STATE subsequent caller)

- **WHEN** a subsequent caller invokes `equivalentDiscriminated(source, targetType, ruleName, discriminator2, customId2)` with `customId2 = "other/custom/id"` and the strategy is `CLONE_CURRENT_STATE`
- **THEN** the returned clone's XMI ID SHALL be `"other/custom/id"`
- **AND** the first caller's original SHALL retain its own custom ID

#### Scenario: null customId preserves existing behavior

- **WHEN** a caller invokes the 4-argument overload `equivalentDiscriminated(source, targetType, ruleName, discriminator)` or the 5-argument overload with `customId=null`
- **THEN** the clone's XMI ID SHALL be computed via `generateDiscriminatedId(baseId, discriminator)` as before
- **AND** no behavioral change SHALL occur compared to the current implementation

### Requirement: XMI ID lookup uses customId when provided

When `useStructuredIds` is enabled and `customId` is non-null, the XMI ID lookup path at the start of `equivalentDiscriminated` SHALL search for the custom ID instead of the generated discriminated ID.

#### Scenario: Existing element found by customId

- **WHEN** `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)` is called with `customId = "existing/id"`
- **AND** an element with XMI ID `"existing/id"` already exists in the target resource
- **THEN** the existing element SHALL be returned (cached and reused)
- **AND** no new clone SHALL be created

#### Scenario: No existing element found by customId triggers normal clone

- **WHEN** `equivalentDiscriminated(source, targetType, ruleName, discriminator, customId)` is called with `customId = "new/id"`
- **AND** no element with XMI ID `"new/id"` exists in the target resource
- **THEN** a new clone SHALL be created with XMI ID `"new/id"`

### Requirement: Discriminated ID computation centralized in resolveDiscriminatedId helper

All discriminated ID computation within `equivalentDiscriminated` SHALL be routed through a single private helper method to ensure consistent behavior across all code paths (CLONE_PRISTINE, CLONE_CURRENT_STATE first/subsequent, DISCRIMINATOR_ONLY, XMI lookup).

#### Scenario: All strategy paths produce consistent IDs

- **WHEN** `customId` is provided
- **THEN** all code paths (CLONE_PRISTINE clone, CLONE_CURRENT_STATE first caller, CLONE_CURRENT_STATE subsequent caller, DISCRIMINATOR_ONLY) SHALL use `customId` as the element's XMI ID
- **AND** no path SHALL fall through to `generateDiscriminatedId`

#### Scenario: null original handled safely in legacy mode

- **WHEN** `useStructuredIds` is false and `original` is null at the point of ID computation
- **THEN** the helper SHALL NOT throw a NullPointerException
- **AND** it SHALL fall back to a safe default (e.g., use `generateStructuredId` or return null)
