## ADDED Requirements

### Requirement: Global ID Prefix Configuration

The TransformationContext MUST provide an option to set a global prefix that is prepended to ALL generated structured XMI IDs. This option is mutually exclusive with `includeElementNameInStructuredIds`.

#### Scenario: Global prefix applied to all generated IDs

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setGlobalIdPrefix("GenericUser")` has been called
**And** a source element with XMI ID "_abc123"
**When** `createTarget(Table.class)` is called in rule "Application"
**Then** the generated XMI ID is `GenericUser/(source/_abc123)/Application`
**And** the prefix "GenericUser" is applied to ALL elements, not just this one

#### Scenario: Global prefix with preferred source alias

**Given** a TransformationContext with `useStructuredIds = true`
**And** `registerResource("esm", esmResourceSet)` has been called
**And** `setPreferredSourceAlias("esm")` has been called
**And** `setGlobalIdPrefix("GenericUser")` has been called
**And** a source element with XMI ID "_MaJNYeiFEfCKeN0VGO_Tbg"
**When** `createTarget(XMLType.class)` is called in rule "XMLType"
**Then** the generated XMI ID is `GenericUser/(esm/_MaJNYeiFEfCKeN0VGO_Tbg)/XMLType`

#### Scenario: Exception when globalIdPrefix set with includeElementNameInStructuredIds enabled

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setIncludeElementNameInStructuredIds(true)` has been called
**When** `setGlobalIdPrefix("GenericUser")` is called
**Then** an IllegalStateException is thrown
**And** the exception message indicates the options are mutually exclusive

#### Scenario: Exception when includeElementNameInStructuredIds enabled with globalIdPrefix set

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setGlobalIdPrefix("GenericUser")` has been called
**When** `setIncludeElementNameInStructuredIds(true)` is called
**Then** an IllegalStateException is thrown
**And** the exception message indicates the options are mutually exclusive

#### Scenario: Null prefix disables global prefix

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setGlobalIdPrefix(null)` has been called
**And** a source element with XMI ID "_abc123"
**When** `createTarget(Table.class)` is called in rule "Entity2Table"
**Then** the generated XMI ID is `(source/_abc123)/Entity2Table`
**And** no prefix is prepended

#### Scenario: Empty string treated as null

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setGlobalIdPrefix("")` has been called
**When** `getGlobalIdPrefix()` is called
**Then** `null` is returned
**And** the behavior is identical to calling `setGlobalIdPrefix(null)`

#### Scenario: Getter returns current global prefix value

**Given** a TransformationContext
**When** `getGlobalIdPrefix()` is called before any configuration
**Then** `null` is returned (default)

**Given** a TransformationContext
**And** `setGlobalIdPrefix("GenericUser")` has been called
**When** `getGlobalIdPrefix()` is called
**Then** `"GenericUser"` is returned

#### Scenario: Global prefix applies to discriminated IDs

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setGlobalIdPrefix("GenericUser")` has been called
**And** a source element with XMI ID "_abc123"
**When** a discriminated target is created with discriminator "relation1"
**Then** the generated XMI ID is `GenericUser/(source/_abc123)/RuleName/(discriminator/relation1)`
**And** the global prefix is part of the base ID before the discriminator suffix

#### Scenario: No conflict when includeElementNameInStructuredIds is false

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setIncludeElementNameInStructuredIds(false)` has been called (or default)
**When** `setGlobalIdPrefix("GenericUser")` is called
**Then** no exception is thrown
**And** `getGlobalIdPrefix()` returns `"GenericUser"`
