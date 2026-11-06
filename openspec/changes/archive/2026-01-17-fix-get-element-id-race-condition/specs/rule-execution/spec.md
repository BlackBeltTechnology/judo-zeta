## ADDED Requirements

### Requirement: createTarget with Explicit ID Parameter

The `TransformationContext` SHALL provide a `createTarget(Class<T> targetType, String customId)` overload that creates a target element with the specified ID already set. This prevents race conditions where other rules might read an initial ID before a custom ID is set.

#### Scenario: Create target with custom ID prevents race condition

**Given** a rule that needs to create a target with a custom ID
**And** the rule will call other rules that may read the target's ID
**When** the rule calls `ctx.createTarget(EPackage.class, "custom/id")`
**Then** the target element SHALL have ID "custom/id" immediately
**And** any rule called via `equivalent()` SHALL see "custom/id" when calling `getElementId()`
**And** no race condition SHALL occur

#### Scenario: createTarget with null customId uses auto-generated ID

**Given** a rule calls `ctx.createTarget(EPackage.class, null)`
**When** the target is created
**Then** the target SHALL receive an auto-generated structured ID
**And** the behavior SHALL be identical to `createTarget(EPackage.class)`

### Requirement: ID Immutability After External Read

When a rule OTHER than the creating rule reads an element's ID via `getElementId()`, the ID SHALL become immutable. Any subsequent call to `setElementId()` for that element SHALL throw an `IllegalStateException`.

#### Scenario: setElementId after external read throws exception

**Given** Rule A creates a target via `createTarget()`
**And** Rule A calls `equivalent()` which triggers Rule B
**And** Rule B calls `getElementId(targetA)` to read the ID
**When** Rule A calls `setElementId(targetA, "custom/id")` after Rule B completes
**Then** an `IllegalStateException` SHALL be thrown
**And** the exception message SHALL indicate that the ID was already read by another rule
**And** the exception message SHALL suggest using `createTarget(type, customId)` instead

#### Scenario: setElementId before external read succeeds

**Given** Rule A creates a target via `createTarget()`
**And** Rule A calls `setElementId(target, "custom/id")` immediately
**When** Rule A later calls `equivalent()` which triggers Rule B
**And** Rule B calls `getElementId(target)`
**Then** Rule B SHALL receive "custom/id"
**And** no exception SHALL be thrown

#### Scenario: Same rule can change ID multiple times

**Given** Rule A creates a target via `createTarget()`
**And** Rule A has not called `equivalent()` or any other method that triggers another rule
**When** Rule A calls `setElementId(target, "id1")` then `setElementId(target, "id2")`
**Then** both calls SHALL succeed
**And** the final ID SHALL be "id2"
**Because** no external rule has read the ID yet

### Requirement: External Read Tracking

The `TransformationContext` SHALL track which elements have had their IDs read by rules other than the creating rule. This tracking is used to enforce ID immutability after external read.

#### Scenario: External read is tracked per element

**Given** Rule A creates targets T1 and T2
**And** Rule B reads the ID of T1 only
**When** Rule A calls `setElementId(T1, "new-id")`
**Then** an `IllegalStateException` SHALL be thrown for T1
**When** Rule A calls `setElementId(T2, "new-id")`
**Then** the call SHALL succeed for T2
**Because** T2's ID was not read externally

#### Scenario: External read tracking is thread-safe

**Given** multiple rules executing in parallel
**When** they read and modify element IDs concurrently
**Then** the external read tracking SHALL be thread-safe
**And** no race conditions SHALL occur in the tracking mechanism itself

### Requirement: Clear Error Messages for ID Race Condition

When `setElementId()` throws an `IllegalStateException` due to external read, the error message SHALL clearly explain the problem and provide guidance on how to fix it.

#### Scenario: Error message provides actionable guidance

**Given** `setElementId()` is called after external read
**When** the exception is thrown
**Then** the message SHALL include:
  - The element that was affected
  - Which rule read the ID
  - The suggestion to use `createTarget(type, customId)` instead
  - Example of the correct pattern
