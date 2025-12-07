# Transformation Framework Capability

**Capability**: `transformation-framework`  
**Owner**: transformation-core module  
**Status**: New

## Overview

This specification defines the requirements for the annotation-based transformation framework in the transformation-core module, providing Java equivalents for Epsilon Transformation Language (ETL) capabilities.

## ADDED Requirements

### Requirement: Transformation Module Structure

**ID**: `TF-001`  
**Priority**: High

The system MUST provide a transformation-core module with proper structure and packaging.

#### Scenario: Module builds successfully

**Given** the transformation-core module is added to the project  
**When** Maven build is executed  
**Then** the module should:
- Compile all Java sources without errors
- Generate OSGi bundle with proper manifest
- Export correct packages (annotation, core, util)
- Include all required dependencies (EMF, SLF4J)
- Pass all unit tests

#### Scenario: OSGi bundle activates in Karaf

**Given** the transformation-core OSGi bundle  
**When** the bundle is deployed to Apache Karaf  
**Then** the bundle should:
- Activate successfully without errors
- Export all public API packages
- Import required dependencies
- Be version 1.0.0-SNAPSHOT

#### Scenario: P2 repository includes transformation framework

**Given** the P2 repository build process  
**When** P2 site is generated  
**Then** the transformation-core bundle should be:
- Included in the P2 repository
- Installable in Eclipse IDE
- Listed in available features

---

### Requirement: Core Annotations

**ID**: `TF-002`  
**Priority**: High

The system MUST provide comprehensive annotations for defining transformations.

#### Scenario: @TransformationContext marks transformation classes

**Given** a Java class containing transformation rules  
**When** the class is annotated with @TransformationContext(source=EntityType.class, target=Table.class)  
**Then** the annotation should:
- Specify source element type (EntityType)
- Specify target element type (Table)
- Be discoverable via reflection at runtime
- Have complete JavaDoc documentation

#### Scenario: @TransformRule defines transformation rules

**Given** a method returning TransformFunction  
**When** the method is annotated with @TransformRule(name="EntityType2Table")  
**Then** the annotation should:
- Specify a unique rule name
- Allow optional description
- Be discoverable by the registry
- Have return type TransformFunction<S, T>

#### Scenario: @Lazy marks rules for lazy evaluation

**Given** a transformation rule method  
**When** the method is annotated with @Lazy  
**Then** the rule should:
- NOT execute during eager transformation phase
- Only execute when equivalent() is called
- Cache results after first execution
- Be available for on-demand transformation

#### Scenario: @Abstract marks rules for inheritance only

**Given** a transformation rule method  
**When** the method is annotated with @Abstract  
**Then** the rule should:
- NOT execute directly during transformation
- Only execute when called by child rules
- Be available via executeParentRule()
- Support inheritance with @Extends

#### Scenario: @Primary marks rules with priority

**Given** multiple rules transforming same source to target type  
**When** one rule is annotated with @Primary  
**Then** the primary rule result should:
- Be returned first by equivalent()
- Precede other rule results in equivalents()
- Be stored in primary cache
- Take precedence in element resolution

#### Scenario: @Greedy enables broader type matching

**Given** a transformation rule with source type Animal  
**When** the rule is annotated with @Greedy  
**Then** the rule should:
- Match Animal instances
- Match Dog instances (subtype of Animal)
- Match Cat instances (subtype of Animal)
- Use kind-of relationship instead of exact type match

#### Scenario: @Guard enables conditional execution

**Given** a transformation rule with @Guard(method="isNotAbstract")  
**When** transformation is executed  
**Then** the system should:
- Invoke the guard method with source element and context
- Execute the rule only if guard returns true
- Skip the rule if guard returns false
- Support guards with custom parameters

#### Scenario: @Extends enables rule inheritance

**Given** a transformation rule annotated with @Extends("BaseEntity2Table")  
**When** the child rule executes  
**Then** the system should:
- Allow calling executeParentRule("BaseEntity2Table", source)
- Execute parent rule logic first
- Return parent result for child to extend
- Support multiple parent rules

---

### Requirement: Transformation Registry

**ID**: `TF-003`  
**Priority**: High

The system MUST provide a registry for discovering and managing transformation rules.

#### Scenario: Rules are discovered via reflection

**Given** a transformation context class with @TransformRule methods  
**When** the class is registered via registry.register(MyTransformations.class)  
**Then** the registry should:
- Discover all @TransformRule annotated methods
- Create TransformRuleDescriptor for each rule
- Index rules by source type
- Index rules by name

#### Scenario: Rules can be retrieved by source type

**Given** a registry with registered transformation rules  
**When** getRulesForSource(EntityType.class) is called  
**Then** the registry should return:
- All rules with source type EntityType
- Rules with source type compatible with EntityType (if @Greedy)
- Rules in correct execution order
- Empty collection if no rules found

#### Scenario: Rules can be retrieved by name

**Given** a registry with a rule named "EntityType2Table"  
**When** getRuleByName("EntityType2Table") is called  
**Then** the registry should:
- Return the matching TransformRuleDescriptor
- Return null if rule not found
- Support unique rule names only

#### Scenario: Pre-transformation hooks are discovered

**Given** a transformation context class with @PreTransformation method  
**When** the class is registered  
**Then** the registry should:
- Discover all @PreTransformation methods
- Store hook methods in execution order
- Invoke hooks before transformation starts
- Pass TransformationContext to hooks

#### Scenario: Post-transformation hooks are discovered

**Given** a transformation context class with @PostTransformation method  
**When** the class is registered  
**Then** the registry should:
- Discover all @PostTransformation methods
- Store hook methods in execution order
- Invoke hooks after transformation completes
- Pass TransformationContext to hooks

---

### Requirement: Transformation Executor

**ID**: `TF-004`  
**Priority**: High

The system MUST provide an executor for running transformations with sequential and parallel execution.

#### Scenario: Sequential transformation executes correctly

**Given** a collection of source elements (<5000 elements)  
**When** transform() is called with sequential execution  
**Then** the executor should:
- Invoke all pre-transformation hooks
- Execute all eager (non-lazy) rules for each element
- Skip abstract rules
- Evaluate guards before executing rules
- Store source→target mappings in cache
- Invoke all post-transformation hooks
- Return TransformationResult

#### Scenario: Parallel transformation executes for large models

**Given** a collection of source elements (≥5000 elements)  
**When** transform() is called with parallel execution enabled  
**Then** the executor should:
- Partition elements into chunks (100 elements per chunk)
- Execute chunks in parallel using CompletableFuture
- Use thread-safe element resolution cache
- Produce same results as sequential execution
- Achieve ~3-4x speedup on 8-core CPU

#### Scenario: Abstract rules are skipped during execution

**Given** a rule annotated with @Abstract  
**When** eager transformation phase executes  
**Then** the executor should:
- Skip the abstract rule
- NOT create target elements for abstract rule
- Allow rule to be called via executeParentRule()

#### Scenario: Guards prevent rule execution

**Given** a rule with @Guard that returns false  
**When** transformation executes  
**Then** the executor should:
- Evaluate the guard method
- Skip rule execution if guard returns false
- Continue with other rules
- Log guard evaluation (DEBUG level)

---

### Requirement: Element Resolution

**ID**: `TF-005`  
**Priority**: High

The system MUST provide element resolution matching ETL equivalent() semantics.

#### Scenario: equivalent() returns transformed element

**Given** a source element transformed to target element  
**When** equivalent(source, TargetClass.class) is called  
**Then** the system should:
- Look up source in resolution cache
- Return the target element if found
- Execute lazy rules if not in cache
- Return null if no transformation exists

#### Scenario: equivalent() returns primary result first

**Given** a source element transformed by multiple rules (one @Primary)  
**When** equivalent(source, TargetClass.class) is called  
**Then** the system should:
- Return the target from @Primary rule first
- Ignore other rule results
- Provide consistent result across calls

#### Scenario: equivalents() returns all transformed elements

**Given** a source element transformed to multiple target elements  
**When** equivalents(source, TargetClass.class) is called  
**Then** the system should:
- Return all target elements for source
- Include @Primary results first in list
- Return empty list if no transformations exist
- Execute lazy rules if needed

#### Scenario: Element resolution triggers lazy rules

**Given** a lazy rule transforming Source to Target  
**When** equivalent(sourceElement, Target.class) is called  
**Then** the system should:
- Check cache first
- Execute lazy rule if not cached
- Store result in cache
- Return the transformed element

#### Scenario: Element resolution cache is thread-safe

**Given** parallel transformation execution  
**When** multiple threads call equivalent() simultaneously  
**Then** the cache should:
- Handle concurrent access safely
- Prevent race conditions
- Return consistent results
- Not throw ConcurrentModificationException

---

### Requirement: Lazy Evaluation

**ID**: `TF-006`  
**Priority**: High

The system MUST support lazy evaluation of transformation rules.

#### Scenario: Lazy rules do not execute eagerly

**Given** a transformation rule annotated with @Lazy  
**When** eager transformation phase executes  
**Then** the lazy rule should:
- NOT execute automatically
- NOT create target elements
- NOT store mappings in cache
- Be available for later execution

#### Scenario: Lazy rules execute on-demand

**Given** a lazy rule transforming Source to Target  
**When** equivalent(source, Target.class) is called  
**Then** the system should:
- Detect no cached result exists
- Find applicable lazy rules
- Execute lazy rule transformation
- Store result in cache
- Return transformed element

#### Scenario: Lazy rule results are cached

**Given** a lazy rule that executed once  
**When** equivalent() is called again with same source  
**Then** the system should:
- Return cached result
- NOT re-execute the lazy rule
- Provide instant response
- Ensure consistency

---

### Requirement: Rule Inheritance

**ID**: `TF-007`  
**Priority**: Medium

The system MUST support rule inheritance via @Extends annotation.

#### Scenario: Child rule can execute parent rule

**Given** a child rule extending "ParentRule"  
**When** executeParentRule("ParentRule", source) is called  
**Then** the system should:
- Look up parent rule by name
- Execute parent transformation
- Return parent result
- Allow child to extend result

#### Scenario: Rule inheritance graph is topologically sorted

**Given** rules with inheritance relationships (A → B → C)  
**When** rules are registered  
**Then** the system should:
- Build dependency graph
- Topologically sort rules
- Detect cycles and throw exception
- Ensure parent rules are defined before children

#### Scenario: Multiple inheritance is supported

**Given** a rule extending multiple parent rules  
**When** the child rule executes  
**Then** the system should:
- Allow executing multiple parent rules
- Combine parent results
- Support composition patterns

---

### Requirement: Guard Conditions

**ID**: `TF-008`  
**Priority**: High

The system MUST support guard conditions for conditional rule execution.

#### Scenario: Guard method is invoked before rule execution

**Given** a rule with @Guard(method="isValid")  
**When** the rule is about to execute  
**Then** the system should:
- Find the guard method by name
- Invoke guard with (source, context) parameters
- Check boolean return value
- Execute rule only if guard returns true

#### Scenario: Guard method receives source element and context

**Given** a guard method `boolean isNotAbstract(EntityType entity, TransformationContext ctx)`  
**When** the guard is evaluated  
**Then** the system should:
- Pass the current source element as first parameter
- Pass the transformation context as second parameter
- Support both signatures: (EObject) and (EObject, TransformationContext)

#### Scenario: Missing guard method throws exception

**Given** a rule with @Guard(method="nonExistentMethod")  
**When** attempting to execute the rule  
**Then** the system should:
- Throw RuntimeException with clear message
- Indicate which guard method is missing
- Fail fast during registration (not execution)

---

### Requirement: Pre/Post Transformation Hooks

**ID**: `TF-009`  
**Priority**: Medium

The system MUST support pre and post-transformation lifecycle hooks.

#### Scenario: Pre-transformation hooks execute before rules

**Given** a transformation context with @PreTransformation method  
**When** transform() is called  
**Then** the system should:
- Invoke all pre-transformation hooks first
- Execute hooks in registration order
- Pass TransformationContext to hooks
- Allow hooks to initialize data structures

#### Scenario: Post-transformation hooks execute after rules

**Given** a transformation context with @PostTransformation method  
**When** transformation completes  
**Then** the system should:
- Invoke all post-transformation hooks last
- Execute hooks in registration order
- Pass TransformationContext to hooks
- Allow hooks to cleanup or save results

#### Scenario: Hooks can access transformation context

**Given** a pre-transformation hook  
**When** the hook executes  
**Then** the hook should:
- Receive TransformationContext parameter
- Access source and target ResourceSets
- Set custom attributes for rules to use
- Query source model elements

#### Scenario: Multiple hooks execute in order

**Given** multiple transformation classes each with pre/post hooks  
**When** classes are registered in specific order  
**Then** hooks should:
- Execute in the same order as registration
- Complete before next hook starts
- Not interfere with each other

---

### Requirement: Parallel Execution

**ID**: `TF-010`  
**Priority**: Medium

The system MUST support parallel transformation execution for large models.

#### Scenario: Parallel execution activates above threshold

**Given** a collection of 5000 source elements  
**When** transform() is called with parallel=true  
**Then** the executor should:
- Detect element count ≥ 5000
- Partition elements into chunks
- Execute chunks in parallel
- Merge results

#### Scenario: Sequential execution used below threshold

**Given** a collection of 4999 source elements  
**When** transform() is called with parallel=true  
**Then** the executor should:
- Detect element count < 5000
- Use sequential execution
- Avoid parallelization overhead
- Return same results as parallel would

#### Scenario: Parallel execution is thread-safe

**Given** parallel transformation execution  
**When** multiple threads transform elements simultaneously  
**Then** the system should:
- Prevent race conditions in cache
- Ensure thread-safe element creation
- Produce deterministic results
- Not throw threading exceptions

#### Scenario: Parallel execution produces same results as sequential

**Given** the same source model  
**When** transformed sequentially and in parallel  
**Then** both executions should:
- Produce identical target models
- Create same number of target elements
- Have same element properties
- Have same element relationships

---

### Requirement: Transformation Context Operations

**ID**: `TF-011`  
**Priority**: High

The system MUST provide comprehensive transformation context operations.

#### Scenario: Context can create target elements

**Given** a transformation rule  
**When** ctx.create(Table.class) is called  
**Then** the context should:
- Look up EClass for Table
- Create instance using EFactory
- Add to target ResourceSet
- Return typed Table instance

#### Scenario: Context can query all source elements by type

**Given** a transformation context with source model  
**When** ctx.getAllSource(EntityType.class) is called  
**Then** the context should:
- Iterate over source ResourceSet
- Filter elements by EntityType class
- Return list of all matching elements
- Support generic type safety

#### Scenario: Context can query all target elements by type

**Given** a transformation context with target model  
**When** ctx.getAllTarget(Table.class) is called  
**Then** the context should:
- Iterate over target ResourceSet
- Filter elements by Table class
- Return list of all created elements
- Support generic type safety

#### Scenario: Context supports custom attributes

**Given** a transformation context  
**When** ctx.setAttribute("statistics", statsMap) is called  
**Then** the context should:
- Store the attribute by key
- Allow retrieval via getAttribute("statistics")
- Support any Object type
- Persist across rule executions

#### Scenario: Context can save target models

**Given** a transformation context with target models  
**When** ctx.saveTargetModels() is called  
**Then** the context should:
- Save all target Resources
- Use appropriate file format (XMI)
- Handle IOException appropriately
- Preserve all target elements

---

### Requirement: Extension Method Support

**ID**: `TF-012`  
**Priority**: Medium

The system MUST support extension methods as helper functions.

#### Scenario: Extension methods are registered

**Given** a class annotated with @ExtensionMethod(elementType=EntityType.class)  
**When** the class is registered  
**Then** the extension registry should:
- Discover all public methods
- Store method metadata
- Make methods callable via context
- Support method overloading

#### Scenario: Extension methods can be invoked from context

**Given** a registered extension method "getAllAttributes"  
**When** ctx.callExtension("getAllAttributes", entityType) is called  
**Then** the system should:
- Find matching method by name
- Invoke with provided parameters
- Return the method result
- Support generic return types

#### Scenario: Extension method results can be cached

**Given** an extension method annotated with @Cached  
**When** the method is invoked multiple times with same parameters  
**Then** the system should:
- Execute method on first call
- Cache the result
- Return cached result on subsequent calls
- Key cache by parameters

#### Scenario: Non-cached extension methods execute every time

**Given** an extension method NOT annotated with @Cached  
**When** the method is invoked multiple times  
**Then** the system should:
- Execute method every time
- NOT cache results
- Return fresh results each call
- Support side-effecting operations

---

### Requirement: Documentation Integration

**ID**: `TF-013`  
**Priority**: Medium

The system MUST provide comprehensive documentation for the transformation framework.

#### Scenario: All annotations have JavaDoc

**Given** all transformation annotations  
**When** documentation is generated  
**Then** each annotation should have:
- Clear description of purpose
- Usage examples
- Parameter descriptions
- Related annotations referenced

#### Scenario: Migration guide from ETL exists

**Given** the transformation framework documentation  
**When** developers need to migrate from ETL  
**Then** the documentation should provide:
- Side-by-side ETL vs Java comparison
- Feature mapping table
- Common transformation patterns
- Migration checklist
- Troubleshooting guide

#### Scenario: Usage examples are comprehensive

**Given** the transformation framework README  
**When** developers learn the framework  
**Then** the documentation should include:
- Quick start guide with simple example
- Complete transformation example
- Lazy rule example
- Rule inheritance example
- Parallel execution example
- Extension method example

---

### Requirement: Greedy Type Matching

**ID**: `TF-014`  
**Priority**: High

The system MUST support greedy type matching that matches source element types AND all subtypes.

#### Scenario: Non-greedy rule matches only exact type

**Given** a transformation rule without @Greedy annotation  
**And** the rule transforms Animal to AnimalDTO  
**When** transformation executes on a Dog instance (Dog extends Animal)  
**Then** the rule should:
- NOT match the Dog instance
- Skip transformation for Dog
- Only match exact Animal instances

#### Scenario: Greedy rule matches type and all subtypes

**Given** a transformation rule with @Greedy annotation  
**And** the rule transforms Animal to AnimalDTO  
**When** transformation executes on instances of Animal, Dog, and Cat  
**Then** the rule should:
- Match the Animal instance
- Match the Dog instance (Dog extends Animal)
- Match the Cat instance (Cat extends Animal)
- Execute transformation for all three instances

#### Scenario: Greedy matching with EMF EClass hierarchy

**Given** a greedy rule for ESM::NamedElement  
**And** EntityType, ActorType, and Operation all extend NamedElement  
**When** transformation executes  
**Then** the rule should:
- Use EClass.isSuperTypeOf() for type checking
- Match all NamedElement subtypes
- Correctly handle multi-level inheritance

#### Scenario: Multiple greedy rules for different levels

**Given** a greedy rule for Animal transforming to AnimalDTO  
**And** a greedy rule for Mammal transforming to MammalDTO (Mammal extends Animal)  
**When** transformation executes on a Dog instance (Dog extends Mammal)  
**Then** the system should:
- Both rules match the Dog instance
- Execute both transformations
- Create both AnimalDTO and MammalDTO targets
- Store both in element resolution cache

#### Scenario: Greedy and non-greedy rules coexist

**Given** a non-greedy rule for Animal  
**And** a greedy rule for Mammal  
**When** transformation executes on Dog, Cat, and Bird instances  
**Then** the system should:
- Non-greedy Animal rule matches only Animal instances
- Greedy Mammal rule matches Dog and Cat (both Mammals)
- Bird is not Mammal, so greedy rule doesn't match
- Each rule executes only for appropriate instances

#### Scenario: Registry indexes greedy rules by base type

**Given** multiple greedy rules registered  
**When** getRulesForSource(Dog.class) is called  
**Then** the registry should:
- Return exact Dog rules
- Return greedy rules for Animal (Dog extends Animal)
- Return greedy rules for Mammal (Dog extends Mammal)
- NOT return greedy rules for Cat or unrelated types

---

### Requirement: Abstract Rule Templates

**ID**: `TF-015`  
**Priority**: High

The system MUST support abstract rules that provide reusable transformation logic via inheritance but never execute directly.

#### Scenario: Abstract rule is skipped during eager execution

**Given** a rule annotated with @Abstract  
**When** eager transformation phase executes  
**Then** the abstract rule should:
- NOT execute automatically
- NOT create target elements
- NOT store mappings in cache
- Be available for child rules to call

#### Scenario: Child rule executes parent rule explicitly

**Given** an abstract rule "CreateNamedElement"  
**And** a child rule "CreateOperation" extending "CreateNamedElement"  
**When** the child rule calls ctx.executeParentRule("CreateNamedElement", source)  
**Then** the system should:
- Execute the parent rule logic
- Create target element in parent rule
- Return parent result to child
- Allow child to add additional logic

#### Scenario: Parent logic executes before child logic

**Given** a parent abstract rule that sets element name  
**And** a child rule that sets element binding  
**When** the child rule executes  
**Then** the execution order should be:
1. Child calls executeParentRule()
2. Parent rule creates element and sets name
3. Parent returns element to child
4. Child adds binding to element
5. Child returns complete element

#### Scenario: Multi-level inheritance chain

**Given** abstract rule "CreateNamedElement" (sets name)  
**And** abstract rule "CreateTypedElement" extends "CreateNamedElement" (sets type)  
**And** concrete rule "CreateOperation" extends "CreateTypedElement" (sets binding)  
**When** "CreateOperation" executes  
**Then** the execution chain should be:
1. CreateOperation calls executeParentRule("CreateTypedElement")
2. CreateTypedElement calls executeParentRule("CreateNamedElement")
3. CreateNamedElement creates element and sets name
4. CreateTypedElement adds type
5. CreateOperation adds binding
6. All logic from all three rules is applied

#### Scenario: Cycle detection in inheritance graph

**Given** rule A extends rule B  
**And** rule B extends rule C  
**And** rule C extends rule A (cycle!)  
**When** the transformation registry validates the graph  
**Then** the system should:
- Detect the cycle during registration
- Throw IllegalStateException with clear message
- Indicate which rules form the cycle
- Prevent execution

#### Scenario: Abstract rule with no children triggers warning

**Given** an abstract rule "BaseTransform"  
**And** no child rules extend "BaseTransform"  
**When** the transformation registry validates the graph  
**Then** the system should:
- Log warning about unused abstract rule
- Continue registration (not an error)
- Allow rule to be used if children added later

#### Scenario: Missing parent rule throws exception

**Given** a child rule extending "NonExistentParent"  
**When** the transformation registry validates the graph  
**Then** the system should:
- Throw IllegalStateException
- Indicate the missing parent rule name
- Indicate which child rule references it
- Fail fast during registration

#### Scenario: Abstract rule can have different generic types

**Given** abstract rule CreateNamedElement<NamedElement, NamedElement>  
**And** child rule CreateOperation<Operation, BoundOperation>  
**When** CreateOperation calls parent rule  
**Then** the system should:
- Allow different source and target types
- Perform safe casting
- Return correctly typed result
- Maintain type safety throughout chain

---

### Requirement: Discriminated Equivalence

**ID**: `TF-016`  
**Priority**: High

The system MUST support discriminated equivalence allowing multiple transformations of the same source element with different discriminators and unique IDs.

#### Scenario: Multiple discriminated transformations of same source

**Given** a source Relation element  
**When** ctx.equivalentDiscriminated(relation, Operation.class, "RelationOp", "create") is called  
**And** ctx.equivalentDiscriminated(relation, Operation.class, "RelationOp", "update") is called  
**And** ctx.equivalentDiscriminated(relation, Operation.class, "RelationOp", "delete") is called  
**Then** the system should:
- Create three separate Operation instances
- Each instance is a clone of the base transformation
- Each has a different discriminated ID
- All three are cached independently

#### Scenario: Discriminated ID naming convention

**Given** a source element with ID "relation-customer-orders"  
**When** equivalentDiscriminated() is called with discriminator "create"  
**Then** the discriminated element should:
- Have ID: "relation-customer-orders/(discriminator/create)"
- Follow pattern: baseId/(discriminator/value)
- Be unique in target model
- Allow easy identification of discriminator from ID

#### Scenario: Discriminated cache is independent per discriminator

**Given** relation element transformed with discriminator "create"  
**When** equivalentDiscriminated() is called again with "create"  
**Then** the system should:
- Return cached instance
- NOT create new instance
- NOT re-execute transformation
- Ensure consistency

**And** when equivalentDiscriminated() is called with discriminator "update"  
**Then** the system should:
- NOT find cached instance (different discriminator)
- Create new cloned instance
- Cache separately from "create" instance
- Return new instance

#### Scenario: Cloning preserves all properties

**Given** a base transformation creating an Operation with name and parameters  
**When** equivalentDiscriminated() clones the element  
**Then** the clone should:
- Have same name as original
- Have same parameters as original
- Have same all other properties
- Only differ in ID (discriminated)

#### Scenario: Discriminated elements are added to target model

**Given** a discriminated transformation creating an Operation  
**When** the clone is created  
**Then** the system should:
- Add clone to target ResourceSet
- Make it part of target model
- Allow references to discriminated element
- Include in target model serialization

#### Scenario: Base transformation is created if not exists

**Given** a source element not yet transformed  
**When** equivalentDiscriminated() is called  
**Then** the system should:
- Check if base transformation exists
- Execute transformation rule if needed
- Create base element
- Then clone base element for discriminator
- Cache both base and discriminated versions

#### Scenario: Three-level cache structure

**Given** the discriminated cache implementation  
**Then** the cache structure should be:
- Level 1: Map by source element
- Level 2: Map by rule name
- Level 3: Map by discriminator value
- Result: target element instance

**Example**:
```
cache.get(relationElement)
     .get("RelationOperation")
     .get("create") → Operation instance
```

#### Scenario: ID management with EMF XMI resources

**Given** an element in an XMI resource  
**When** setElementId() is called with discriminated ID  
**Then** the system should:
- Set XMI resource ID if resource is XMIResource
- Set model element "id" attribute if exists
- Handle both simultaneously if both available
- Skip gracefully if neither available

#### Scenario: ID fallback strategy

**Given** an element without explicit ID  
**When** getElementId() is called  
**Then** the system should try in order:
1. EMF resource URI fragment
2. Model element "id" attribute
3. Generate UUID as last resort

**And** all strategies should produce valid discriminated IDs

#### Scenario: Real-world relation to operations example

**Given** a Relation between Customer and Order entities  
**When** processing the relation for UI operations  
**Then** the transformation should create:
- createOrder operation (discriminator: "create")
- updateOrder operation (discriminator: "update")
- deleteOrder operation (discriminator: "delete")
- listOrders operation (discriminator: "list")

**And** each operation should:
- Have unique ID with discriminator
- Be independently cached
- Be independently retrievable
- Share base properties but differ in specifics

---

## MODIFIED Requirements

None - all requirements are new.

## REMOVED Requirements

None - this is a new capability.

---

## Non-Functional Requirements

### Performance

- **NFR-001**: Transformation MUST execute at least as fast as ETL for equivalent transformations
- **NFR-002**: Parallel execution MUST achieve 3-4x speedup on 8-core CPU for models >5000 elements
- **NFR-003**: Element resolution cache lookups MUST be O(1) average case
- **NFR-004**: Transformation overhead MUST NOT exceed 20% compared to manual Java code

### Scalability

- **NFR-005**: Framework MUST handle models with up to 100,000 source elements
- **NFR-006**: Memory usage MUST NOT exceed 2KB per source element (excluding model size)
- **NFR-007**: Element resolution cache MUST support concurrent access

### Reliability

- **NFR-008**: Parallel execution MUST produce deterministic results
- **NFR-009**: Cycle detection in rule inheritance MUST prevent infinite loops
- **NFR-010**: All public APIs MUST validate inputs and throw clear exceptions

### Maintainability

- **NFR-011**: Code coverage MUST exceed 80%
- **NFR-012**: All public APIs MUST have comprehensive JavaDoc
- **NFR-013**: Framework design MUST mirror validation-core patterns for consistency

### Compatibility

- **NFR-014**: Framework MUST work in OSGi environments (Karaf, Equinox)
- **NFR-015**: Framework MUST work in standalone Java applications
- **NFR-016**: Framework MUST be compatible with Java 21+
- **NFR-017**: Framework MUST integrate with EMF ResourceSet API

### Usability

- **NFR-018**: API MUST be intuitive for developers familiar with ETL
- **NFR-019**: Error messages MUST clearly indicate the problem and location
- **NFR-020**: Migration from ETL MUST be straightforward with clear documentation

---

## Dependencies

### Technical Dependencies
- EMF ECore 2.38.0+ (metamodel foundation)
- SLF4J 2.0.16+ (logging facade)
- JUnit 5 (testing)
- Lombok 1.18.34+ (annotation processing)

### Module Dependencies
- validation-core (reference implementation patterns)
- Parent POM configuration

### External Dependencies
- None - self-contained module

---

## Testing Strategy

All requirements will be verified through:

1. **Unit Tests**: Test individual components in isolation
   - TransformationRegistry tests
   - TransformationExecutor tests
   - ElementResolutionCache tests
   - TransformRuleDescriptor tests
   - RuleInheritanceGraph tests

2. **Integration Tests**: Test complete transformation workflows
   - End-to-end transformation tests
   - Lazy evaluation integration tests
   - Rule inheritance integration tests
   - Parallel execution integration tests

3. **Performance Tests**: Verify non-functional requirements
   - Parallel vs sequential execution benchmarks
   - Large model scalability tests
   - Cache performance tests

4. **Compatibility Tests**: Verify deployment scenarios
   - OSGi bundle tests (Pax Exam)
   - Standalone application tests
   - P2 repository installation tests

---

## Acceptance Criteria

The transformation framework is considered complete when:

- [ ] All functional requirements (TF-001 through TF-013) are implemented
- [ ] All scenarios pass with automated tests
- [ ] Non-functional requirements are met and verified
- [ ] Code coverage exceeds 80%
- [ ] Documentation is complete (README, migration guide, JavaDoc)
- [ ] OSGi bundle activates successfully in Karaf
- [ ] P2 repository includes and deploys transformation-core
- [ ] CI/CD pipeline builds and deploys successfully
- [ ] At least one real-world transformation migrated from ETL as proof-of-concept

---

## Future Enhancements

Potential future additions (out of scope for initial release):

1. **Model Merging**: Support merging multiple source models into single target
2. **Incremental Transformations**: Re-execute only changed elements
3. **Bidirectional Transformations**: Support round-trip transformations
4. **Validation Integration**: Validate targets using validation-core
5. **Transformation Composition**: Chain multiple transformations
6. **Debug Tooling**: Visualize transformation trace and execution flow
7. **Performance Profiling**: Built-in metrics collection and reporting

---

## References

- Epsilon Transformation Language (ETL): https://eclipse.dev/epsilon/doc/etl/
- validation-core module: `/Users/robson/Project/judo-ng/runtime/judo-zeta/validation-core/`
- EMF Resource Framework: https://www.eclipse.org/modeling/emf/
- Java Concurrency: Java Concurrency in Practice (Brian Goetz)
