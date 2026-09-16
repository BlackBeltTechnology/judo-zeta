# cached-target-name-lookup Specification

## Purpose
TBD

## Requirements

### Requirement: ElementResolutionCache provides name-based target lookup
`ElementResolutionCache` SHALL provide a `findByName(String name, Class<T> targetType)` method that searches all cached target mappings for a target whose `eClass().getName()` equals the given name and is assignable to `targetType`.

#### Scenario: Find cached target by exact name match
- **WHEN** a target with `eClass().getName() == "DateType"` exists in the cache
- **AND** `findByName("DateType", EDataType.class)` is called
- **THEN** the cached target SHALL be returned

#### Scenario: Return null when no matching target exists
- **WHEN** no target with `eClass().getName() == "NonExistent"` exists in the cache
- **AND** `findByName("NonExistent", EDataType.class)` is called
- **THEN** null SHALL be returned

#### Scenario: Filter by target type assignability
- **WHEN** a target with `eClass().getName() == "MyType"` of type `EDataType` exists in the cache
- **AND** `findByName("MyType", EClass.class)` is called (incompatible type)
- **THEN** null SHALL be returned because `EDataType` is not assignable to `EClass`

#### Scenario: Type hierarchy lookup works
- **WHEN** a target of type `EDataType` with name "DateType" exists in the cache
- **AND** `findByName("DateType", EClassifier.class)` is called
- **THEN** the target SHALL be returned because `EDataType` is assignable to `EClassifier`

#### Scenario: Works in both sequential and parallel mode
- **WHEN** the cache is in sequential mode (IdentityHashMap)
- **AND** `findByName(name, type)` is called
- **THEN** the method SHALL work correctly
- **AND** the same behavior SHALL apply in parallel mode (ConcurrentHashMap)

### Requirement: TransformationContext provides findCachedTargetByName convenience method
`TransformationContext` SHALL provide a `findCachedTargetByName(String name, Class<T> targetType)` method that delegates to `ElementResolutionCache.findByName()`.

#### Scenario: Delegate to resolution cache
- **WHEN** `ctx.findCachedTargetByName("DateType", EClassifier.class)` is called
- **THEN** it SHALL delegate to `resolutionCache.findByName("DateType", EClassifier.class)`
- **AND** return the result

#### Scenario: Handle null inputs gracefully
- **WHEN** `ctx.findCachedTargetByName(null, EClassifier.class)` is called
- **THEN** null SHALL be returned without throwing an exception

### Requirement: Identity-mismatch scenario is resolved by name-based lookup
When `ctx.equivalent(source, targetType)` returns null due to the source being a different Java object instance than the originally transformed element, `findCachedTargetByName()` SHALL find the correct target by name.

#### Scenario: Different object identity, same logical type
- **WHEN** TypeRule transforms `DateType@A` to `EDataType(name="DateType")`
- **AND** `ctx.equivalent(DateType@B, EClassifier.class)` returns null (identity mismatch)
- **AND** `ctx.findCachedTargetByName("DateType", EClassifier.class)` is called
- **THEN** the `EDataType` created from `DateType@A` SHALL be returned

#### Scenario: Works during rule execution (before postProcess)
- **WHEN** rule execution is in progress (postProcess has not yet run)
- **AND** the target resource set is empty
- **AND** `findCachedTargetByName("DateType", EClassifier.class)` is called
- **THEN** the target SHALL be found in the resolution cache (not the resource set)
