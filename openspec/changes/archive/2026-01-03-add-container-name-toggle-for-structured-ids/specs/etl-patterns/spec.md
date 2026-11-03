# etl-patterns Specification Delta

## ADDED Requirements

### Requirement: Configurable Element Name in Structured IDs

The TransformationContext MUST provide an option to include or exclude the element name prefix from generated structured XMI IDs.

#### Scenario: Default behavior excludes element name

**Given** a TransformationContext with `useStructuredIds = true`
**And** `includeElementNameInStructuredIds` is not explicitly set
**And** a source element named "Customer" with XMI ID "_abc123"
**When** `createTarget(Table.class)` is called in rule "Entity2Table"
**Then** the generated XMI ID is `(source/_abc123)/Entity2Table`
**And** the element name "Customer" is NOT included

#### Scenario: Element name included when enabled

**Given** a TransformationContext with `useStructuredIds = true`
**And** `setIncludeElementNameInStructuredIds(true)` has been called
**And** a source element named "Customer" with XMI ID "_abc123"
**When** `createTarget(Table.class)` is called in rule "Entity2Table"
**Then** the generated XMI ID is `Customer/(source/_abc123)/Entity2Table`
**And** the element name "Customer" IS included as prefix

#### Scenario: Combined with preferred source alias

**Given** a TransformationContext with `useStructuredIds = true`
**And** `registerResource("esm", esmResourceSet)` has been called
**And** `setPreferredSourceAlias("esm")` has been called
**And** `includeElementNameInStructuredIds` is `false` (default)
**And** a source element with XMI ID "_MaJNYeiFEfCKeN0VGO_Tbg"
**When** `createTarget(XMLType.class)` is called in rule "XMLType"
**Then** the generated XMI ID is `(esm/_MaJNYeiFEfCKeN0VGO_Tbg)/XMLType`

#### Scenario: Getter reflects current setting

**Given** a TransformationContext
**When** `isIncludeElementNameInStructuredIds()` is called before any configuration
**Then** `false` is returned (default)

**Given** a TransformationContext
**And** `setIncludeElementNameInStructuredIds(true)` has been called
**When** `isIncludeElementNameInStructuredIds()` is called
**Then** `true` is returned

---
