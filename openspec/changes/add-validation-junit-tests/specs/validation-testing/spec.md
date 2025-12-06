# Validation Testing Capability

**Capability**: `validation-testing`  
**Owner**: validation-core module  
**Status**: New

## Overview

This specification defines the testing requirements for the validation framework in the validation-core module.

## ADDED Requirements

### Requirement: Validation Component Tests

**ID**: `VT-001`  
**Priority**: High

The system MUST provide unit tests for all core validation components.

#### Scenario: ValidationRegistry is tested

**Given** a ValidationRegistry instance  
**When** validators are registered  
**Then** the registry should correctly:
- Find validators by EObject type
- Find validators by name
- Retrieve validators for supertypes
- Invoke pre and post-validation hooks

#### Scenario: ValidationExecutor is tested

**Given** a ValidationExecutor instance  
**When** elements are validated  
**Then** the executor should correctly:
- Execute validators sequentially when configured
- Execute validators in parallel when configured
- Filter out passing results
- Invoke registry hooks before and after execution

#### Scenario: ValidationContext is tested

**Given** a ValidationContext instance  
**When** context operations are performed  
**Then** the context should correctly:
- Track current element
- Manage custom attributes
- Evaluate satisfies() predicates
- Cache satisfies results
- Query all instances of a type

#### Scenario: ValidatorDescriptor is tested

**Given** a ValidatorDescriptor instance  
**When** validation is performed  
**Then** the descriptor should correctly:
- Check if validator applies to element type
- Lazily create validation rules
- Evaluate guards before validation
- Skip validation when guard fails

#### Scenario: ValidationResult is tested

**Given** validation results  
**When** results are created and compared  
**Then** the results should correctly:
- Represent passing and failing states
- Include constraint name, message, and severity
- Support equality comparison
- Distinguish errors from warnings
- Verify exact error messages match expected values

---

### Requirement: Caching Mechanism Tests

**ID**: `VT-002`  
**Priority**: High

The system MUST provide tests verifying caching behavior.

#### Scenario: Satisfies cache works correctly

**Given** a ValidationContext with a registered validator  
**When** satisfies() is called multiple times for the same element and constraint  
**Then** the validation should execute only once (subsequent calls use cache)

#### Scenario: Satisfies cache can be cleared

**Given** a cached satisfies result  
**When** the cache is cleared  
**Then** the next satisfies() call should execute validation again

#### Scenario: Extension method cache works for @Cached methods

**Given** an extension method annotated with @Cached  
**When** the method is invoked multiple times with same parameters  
**Then** the method should execute only once (subsequent calls use cache)

#### Scenario: Extension method cache does not cache non-@Cached methods

**Given** an extension method NOT annotated with @Cached  
**When** the method is invoked multiple times  
**Then** the method should execute every time

---

### Requirement: Extension Method Tests

**ID**: `VT-003`  
**Priority**: High

The system MUST provide tests for the extension method registry.

#### Scenario: Extension methods are registered

**Given** a class annotated with @ExtensionMethod  
**When** the class is registered  
**Then** all public methods should be available as extensions

#### Scenario: Extension methods can be invoked

**Given** a registered extension method  
**When** the method is invoked on a target object  
**Then** the method should execute with correct parameters and return result

#### Scenario: Missing extension methods throw exception

**Given** an extension method that doesn't exist  
**When** attempting to invoke the method  
**Then** an IllegalArgumentException should be thrown

#### Scenario: Classes without @ExtensionMethod annotation are rejected

**Given** a class NOT annotated with @ExtensionMethod  
**When** attempting to register the class  
**Then** an IllegalArgumentException should be thrown

---

### Requirement: Annotation Processing Tests

**ID**: `VT-004`  
**Priority**: High

The system MUST provide tests verifying annotation processing.

#### Scenario: @Constraint annotation is processed

**Given** a validator method annotated with @Constraint  
**When** the validator class is registered  
**Then** the constraint should be registered with ERROR severity  
**And** validation failures should return the exact message specified in the annotation

#### Scenario: @Critique annotation is processed

**Given** a validator method annotated with @Critique  
**When** the validator class is registered  
**Then** the critique should be registered with WARNING severity  
**And** validation failures should return the exact message specified in the annotation

#### Scenario: @Guard annotation is processed

**Given** a validator method with @Guard annotation  
**When** validation is executed and guard returns false  
**Then** the validator method should NOT be executed

#### Scenario: @Satisfies annotation is processed

**Given** a validator method with @Satisfies annotation  
**When** validation is executed  
**Then** the validator should only run if dependencies are satisfied

#### Scenario: @PreValidation hook is invoked

**Given** a method annotated with @PreValidation  
**When** validation executor runs  
**Then** the hook method should be invoked before any validation

#### Scenario: @PostValidation hook is invoked

**Given** a method annotated with @PostValidation  
**When** validation executor completes  
**Then** the hook method should be invoked after all validation

---

### Requirement: Message Verification Tests

**ID**: `VT-008`  
**Priority**: High

The system MUST verify that validation error messages are correct and match expectations.

#### Scenario: Error messages match constraint annotations

**Given** a validator with @Constraint annotation specifying a message  
**When** validation fails  
**Then** the ValidationResult message should exactly match the annotation message

#### Scenario: Warning messages match critique annotations

**Given** a validator with @Critique annotation specifying a message  
**When** validation produces a warning  
**Then** the ValidationResult message should exactly match the annotation message

#### Scenario: Dynamic messages include element information

**Given** a validator that generates dynamic messages with element details  
**When** validation fails  
**Then** the message should include the relevant element information (e.g., element name)

#### Scenario: Messages are verified in all test cases

**Given** any validation test  
**When** testing constraint or critique behavior  
**Then** the test should assert both:
- The constraint/critique name
- The exact error/warning message

---

### Requirement: Cache Key Builder Tests

**ID**: `VT-005`  
**Priority**: Medium

The system MUST provide tests for cache key generation.

#### Scenario: Cache keys are generated from EObjects

**Given** an EObject instance  
**When** a cache key is built  
**Then** the key should be unique and deterministic

#### Scenario: Cache keys are generated from primitives

**Given** primitive values (strings, numbers, booleans)  
**When** a cache key is built  
**Then** the key should correctly represent all values

#### Scenario: Same inputs produce equal keys

**Given** identical input values  
**When** cache keys are built separately  
**Then** the keys should be equal (same hashCode and equals)

#### Scenario: Different inputs produce different keys

**Given** different input values  
**When** cache keys are built  
**Then** the keys should NOT be equal

---

### Requirement: Integration Testing

**ID**: `VT-006`  
**Priority**: Medium

The system MUST provide integration tests demonstrating complete workflows.

#### Scenario: End-to-end validation workflow

**Given** a complete validation setup with:
- ECore model elements
- Registered validators with constraints and critiques
- Registered extension methods
- Pre and post-validation hooks  
**When** validation is executed on model elements  
**Then** the validation should:
- Execute all applicable validators
- Return correct results (pass/fail)
- Execute hooks in correct order
- Cache results appropriately

---

### Requirement: Test Infrastructure

**ID**: `VT-007`  
**Priority**: High

The system MUST provide test infrastructure for easy test creation.

#### Scenario: Abstract base test class is available

**Given** a need to write validation tests  
**When** extending AbstractValidationTest  
**Then** common setup should be available:
- Initialized ResourceSet
- Initialized ValidationRegistry
- Initialized ValidationContext
- Initialized ExtensionMethodRegistry

#### Scenario: Test model factory is available

**Given** a need to create test ECore elements  
**When** using TestModelFactory  
**Then** factory methods should be available for:
- Creating EClass instances
- Creating EPackage instances
- Creating EAttribute instances
- Creating other common ECore elements

#### Scenario: Test validators are available

**Given** a need to test validation execution  
**When** using provided test validator classes  
**Then** validators should be available with:
- Passing validators
- Failing validators
- Validators with guards
- Validators with dependencies
- Validators with counters for cache testing

---

## Dependencies

- JUnit 5 (Jupiter) test framework
- EMF ECore for test model creation
- Existing validation-core implementation

## Non-Functional Requirements

### Performance

- **NFR-001**: Test suite MUST complete in under 30 seconds
- **NFR-002**: Tests MUST NOT require external resources (files, network, databases)

### Reliability

- **NFR-003**: Tests MUST be deterministic (no flaky tests)
- **NFR-004**: Tests MUST be independent (can run in any order)

### Maintainability

- **NFR-005**: Tests MUST serve as documentation for framework usage
- **NFR-006**: Test code MUST follow same quality standards as production code

## Testing Strategy

All requirements will be verified through:
- Automated unit tests in validation-core/src/test/java
- Executed as part of Maven build (`mvn test`)
- Integrated into CI/CD pipeline
- Minimum 80% code coverage target
