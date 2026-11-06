## ADDED Requirements

### Requirement: equivalentCached returns cached results without lazy triggering
The `equivalentCached(source, ruleName)` method SHALL return cached target elements without triggering lazy rule execution. It SHALL return null when no cached result exists.

#### Scenario: Cache hit returns target
- **WHEN** a source element has been transformed by a greedy rule and the result is cached
- **THEN** `equivalentCached(source, ruleName)` SHALL return the cached target

#### Scenario: Cache miss returns null without triggering execution
- **WHEN** a source element has NOT been transformed by a given rule
- **THEN** `equivalentCached(source, ruleName)` SHALL return null
- **AND** the rule SHALL NOT be executed

#### Scenario: Null parameters return null
- **WHEN** `equivalentCached` is called with null source or null ruleName
- **THEN** it SHALL return null

### Requirement: Sequential addToResource applies XMI IDs recursively to children
When `addToResource()` is called in sequential mode, pending XMI IDs SHALL be applied not only to the root element but also recursively to all contained child elements.

#### Scenario: Root element gets XMI ID in sequential mode
- **WHEN** a target element with a pending XMI ID is added to resource in sequential mode
- **THEN** the XMI resource SHALL contain the expected XMI ID for that element

#### Scenario: Contained child elements get XMI IDs in sequential mode
- **WHEN** a target element containing child elements (each with pending XMI IDs) is added to resource in sequential mode
- **THEN** the XMI resource SHALL contain expected XMI IDs for all child elements recursively

### Requirement: Guard rejection is permanent in equivalent lookups
When a guard rejects a source element during the greedy pass, subsequent calls to `equivalent()` and `equivalentDiscriminated()` for the same (source, rule) pair SHALL return null without re-evaluating the guard.

#### Scenario: equivalent returns null for rejected source
- **WHEN** a guard rejects a source element during the greedy pass
- **AND** `equivalent(source, ruleName)` is called later
- **THEN** the method SHALL return null
- **AND** the guard SHALL NOT be re-evaluated

#### Scenario: equivalentDiscriminated returns null for rejected source
- **WHEN** a guard rejects a source element during the greedy pass
- **AND** `equivalentDiscriminated(source, targetType, ruleName, discriminator)` is called later
- **THEN** the method SHALL return null

### Requirement: Same-rule greedy pass lookup returns null
During a greedy pass for rule R, if `equivalent(source, "R")` is called for a source element not yet processed by R, it SHALL return null (ETL semantics: greedy rules only return previously-cached results for same-rule lookups).

#### Scenario: Same-rule equivalent during greedy pass returns null for unprocessed elements
- **WHEN** rule R is executing its greedy pass
- **AND** `equivalent(source, "R")` is called for a source not yet cached or rejected by R
- **THEN** the method SHALL return null
