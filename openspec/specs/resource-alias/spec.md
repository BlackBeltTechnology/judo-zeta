# resource-alias Specification

## Purpose
TBD - created by archiving change add-resource-alias-support. Update Purpose after archive.
## Requirements
### Requirement: Resource Registry

The TransformationContext and ValidationContext MUST provide a registry for associating ResourceSets with string aliases.

#### Scenario: Default aliases are registered automatically

**Given** a new TransformationContext is created  
**When** the context is initialized  
**Then** the "source" alias is registered with the source ResourceSet  
**And** the "target" alias is registered with the target ResourceSet  

#### Scenario: Default alias for ValidationContext

**Given** a new ValidationContext is created  
**When** the context is initialized  
**Then** the "source" alias is registered with the resource set  

#### Scenario: Additional resources can be registered

**Given** a TransformationContext with default aliases  
**When** `registerResource("mapping", mappingResourceSet)` is called  
**Then** the "mapping" alias is available for use  
**And** `getResource("mapping")` returns the mappingResourceSet  

#### Scenario: Aliases can be overwritten

**Given** a TransformationContext with "source" alias registered  
**When** `registerResource("source", differentResourceSet)` is called  
**Then** the "source" alias now points to differentResourceSet  

#### Scenario: Unknown alias throws exception

**Given** a TransformationContext with aliases ["source", "target"]  
**When** `getResource("unknown")` is called  
**Then** an IllegalArgumentException is thrown  
**And** the exception message includes "unknown"  
**And** the exception message includes the available aliases  

---

### Requirement: Query Elements by Alias

The contexts MUST provide a method to query all elements of a type from an aliased resource.

#### Scenario: Query source elements by alias

**Given** a TransformationContext with "source" alias registered  
**And** the source ResourceSet contains 5 EntityType elements  
**When** `all("source", EntityType.class)` is called  
**Then** a collection of 5 EntityType elements is returned  

#### Scenario: Query from additional aliased resource

**Given** a TransformationContext with "mapping" alias registered  
**And** the mapping ResourceSet contains 10 TypeMapping elements  
**When** `all("mapping", TypeMapping.class)` is called  
**Then** a collection of 10 TypeMapping elements is returned  

#### Scenario: Existing getAllSource delegates to all

**Given** a TransformationContext with source elements  
**When** `getAllSource(EntityType.class)` is called  
**Then** the result is identical to `all("source", EntityType.class)`  

#### Scenario: Existing getAllInstances delegates to all

**Given** a ValidationContext with source elements  
**When** `getAllInstances(EntityType.class)` is called  
**Then** the result is identical to `all("source", EntityType.class)`  

---

### Requirement: Create Element Without Containment

The TransformationContext MUST support creating elements without adding them to any resource, matching ETL behavior.

#### Scenario: Create element without containment

**Given** a TransformationContext  
**When** `create(Table.class)` is called  
**Then** a new Table element is created  
**And** the element is NOT added to any resource  
**And** the element has no container (eContainer is null)  

#### Scenario: Rule sets containment explicitly

**Given** an element created via `create(Table.class)`  
**And** a Schema element obtained via `equivalent()`  
**When** `schema.getTables().add(table)` is called  
**Then** the table is now contained by the schema  
**And** the table appears in the schema's resource  

#### Scenario: Existing createTarget adds to resource root

**Given** a TransformationContext  
**When** `createTarget(Table.class)` is called  
**Then** a new Table element is created  
**And** the element IS added to the target Resource root  
**And** this maintains backward compatibility  

---

### Requirement: @Transform Annotation for Source Type Aliases

The framework MUST support @Transform annotations for defining source types with their aliases.

#### Scenario: Single source with default alias

**Given** a method annotated with `@Transform(type = EntityType.class)`  
**When** the rule is registered  
**Then** the source alias defaults to "source"  
**And** elements are collected from the "source" resource  

#### Scenario: Single source with custom alias

**Given** a method annotated with `@Transform(alias = "asm", type = EntityType.class)`  
**When** the executor collects source elements  
**Then** elements are collected from the "asm" aliased resource  

#### Scenario: Multiple sources with different aliases

**Given** a method annotated with:
```java
@Transform(alias = "asm", type = EntityType.class)
@Transform(alias = "mapping", type = TypeMapping.class)
```
**When** the executor collects source elements  
**Then** EntityType elements come from "asm" resource  
**And** TypeMapping elements come from "mapping" resource  

#### Scenario: Backward compatibility with sourceTypes

**Given** a method annotated with `@TransformRule(sourceTypes = {EntityType.class})`  
**When** the rule is registered  
**Then** the source uses default "source" alias  
**And** existing behavior is preserved  

---

### Requirement: @To Annotation for Target Type Aliases

The framework MUST support @To annotations for defining target types with their aliases.

#### Scenario: Single target with default alias

**Given** a method annotated with `@To(type = Table.class)`  
**When** the rule is registered  
**Then** the target alias defaults to "target"  

#### Scenario: Single target with custom alias

**Given** a method annotated with `@To(alias = "rdbms", type = Table.class)`  
**When** elements are created for this target type  
**Then** the rule knows the intended resource is "rdbms"  

#### Scenario: Multiple targets with different aliases

**Given** a method annotated with:
```java
@To(alias = "rdbms", type = Table.class)
@To(alias = "index", type = Index.class)
```
**When** the rule creates elements  
**Then** the rule can create Tables intended for "rdbms"  
**And** the rule can create Indexes intended for "index"  

#### Scenario: Backward compatibility with targetTypes

**Given** a method annotated with `@TransformRule(targetTypes = {Table.class})`  
**When** the rule is registered  
**Then** the target uses default "target" alias  
**And** existing behavior is preserved  

---

### Requirement: Validation Alias Attribute

The @Constraint and @Critique annotations MUST support a resourceAlias attribute.

#### Scenario: Default alias for Constraint

**Given** a @Constraint annotation without resourceAlias attribute  
**When** the validator is registered  
**Then** resourceAlias defaults to "source"  

#### Scenario: Custom alias for Constraint

**Given** a @Constraint with `resourceAlias = "esm"`  
**When** the validation executor collects elements  
**Then** elements are collected from the "esm" aliased resource  

#### Scenario: Default alias for Critique

**Given** a @Critique annotation without resourceAlias attribute  
**When** the validator is registered  
**Then** resourceAlias defaults to "source"  

#### Scenario: Custom alias for Critique

**Given** a @Critique with `resourceAlias = "esm"`  
**When** the validation executor collects elements  
**Then** elements are collected from the "esm" aliased resource  

---

### Requirement: Thread-Safe Registry Access

The resource registry MUST be thread-safe for concurrent access during parallel execution.

#### Scenario: Concurrent reads during parallel transformation

**Given** a parallel transformation is in progress  
**And** multiple threads call `all("mapping", TypeMapping.class)`  
**When** all threads complete  
**Then** no ConcurrentModificationException is thrown  
**And** all threads receive correct results  

#### Scenario: Registration before parallel execution

**Given** resources are registered before transformation starts  
**When** parallel transformation begins  
**Then** all registered aliases are visible to all worker threads  

---

