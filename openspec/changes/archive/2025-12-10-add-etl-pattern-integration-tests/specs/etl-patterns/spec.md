# Spec: ETL Pattern Integration Tests

**Capability**: etl-patterns  
**Status**: Draft  
**Parent**: transformation-core

## Purpose

This specification defines the requirements for integration tests that validate resource alias support against real-world ETL patterns found in judo-tatami transformations.

## ADDED Requirements

### Requirement: Cartesian Product for Multiple @Transform

When a rule has multiple `@Transform` annotations, the framework MUST execute the rule for each combination in the Cartesian product of source elements.

#### Scenario: Two sources produce Cartesian product

**Given** a rule with `@Transform(alias = "asm", type = EClass.class)`  
**And** `@Transform(alias = "mapping", type = EAnnotation.class)`  
**And** the "asm" alias contains 3 EClass elements [A, B, C]  
**And** the "mapping" alias contains 2 EAnnotation elements [M1, M2]  
**When** the transformation executes  
**Then** the rule fires 6 times (3 x 2)  
**And** the combinations are: (A,M1), (A,M2), (B,M1), (B,M2), (C,M1), (C,M2)

#### Scenario: Multi-source rule receives Object[] parameter

**Given** a rule with multiple `@Transform` annotations  
**When** the rule executes  
**Then** the TransformFunction receives `Object[]` as source parameter  
**And** `sources[0]` is the element from the first @Transform alias  
**And** `sources[1]` is the element from the second @Transform alias  
**And** the order matches the annotation declaration order

#### Scenario: Single @Transform maintains backward compatibility

**Given** a rule with single `@Transform(type = EClass.class)`  
**When** the transformation executes  
**Then** the TransformFunction receives the single EClass directly  
**And** not wrapped in Object[]

#### Scenario: Empty source from one alias produces no executions

**Given** a rule with two @Transform annotations  
**And** the first alias contains 3 elements  
**And** the second alias contains 0 elements  
**When** the transformation executes  
**Then** the rule fires 0 times (3 x 0 = 0)

#### Scenario: Guard receives all source elements for multi-source rule

**Given** a rule with `@Transform(alias = "asm", type = EClass.class)`  
**And** `@Transform(alias = "mapping", type = EAnnotation.class)`  
**And** a guard method with signature `boolean guard(Object[] sources, TransformationContext ctx)`  
**When** the guard is evaluated for a Cartesian product tuple  
**Then** `sources[0]` contains the EClass from "asm"  
**And** `sources[1]` contains the EAnnotation from "mapping"  
**And** the guard can evaluate conditions on both elements

#### Scenario: Guard with single source maintains backward compatibility

**Given** a rule with single `@Transform(type = EClass.class)`  
**And** a guard method with signature `boolean guard(EObject source, TransformationContext ctx)`  
**When** the guard is evaluated  
**Then** the guard receives the single EClass directly  
**And** not wrapped in Object[]

---

### Requirement: Multi-Model Transformation

The framework MUST support transformations that access multiple aliased ResourceSets during rule execution.

#### Scenario: Transform with three aliased models

**Given** a TransformationContext with aliases "asm", "rdbms", and "mapping"  
**And** the "asm" alias contains EClass elements  
**And** the "mapping" alias contains type mapping entries  
**When** a transformation rule executes  
**Then** the rule can read source elements from "asm"  
**And** the rule can query mapping entries from "mapping"  
**And** the rule can create target elements in "rdbms"

#### Scenario: Query auxiliary model during transformation

**Given** a TransformationContext with aliases "source" and "mapping"  
**And** a transformation rule with `@Transform(alias = "source", type = EClass.class)`  
**When** the rule calls `ctx.all("mapping", EAnnotation.class)`  
**Then** the collection contains only elements from the "mapping" ResourceSet  
**And** elements from "source" are not included

---

### Requirement: Cross-Model Equivalence Resolution

The `equivalent()` method MUST resolve transformations across aliased resources.

#### Scenario: Resolve equivalent from aliased target

**Given** a source element transformed via rule with `@To(alias = "rdbms", type = EClass.class)`  
**When** another rule calls `ctx.equivalent(sourceElement, EClass.class)`  
**Then** the equivalent element from "rdbms" alias is returned  
**And** the element is the same instance created by the first rule

#### Scenario: Equivalent chain across models

**Given** element A transformed to B, and B transformed to C  
**And** each transformation uses different target aliases  
**When** `ctx.equivalent(A, TypeOfC.class)` is called  
**Then** element C is returned via lazy transformation chain

#### Scenario: Equivalent caching works across aliases

**Given** an element transformed to aliased target  
**When** `ctx.equivalent()` is called twice for the same source  
**Then** the same instance is returned both times  
**And** the transformation rule executes only once

---

### Requirement: Guards with Alias Access

Guard methods MUST be able to query aliased resources to determine rule applicability.

#### Scenario: Guard queries mapping alias

**Given** a rule with guard method that calls `ctx.all("rules", EAnnotation.class)`  
**And** the "rules" alias contains matching rule metadata  
**When** the transformation executes  
**Then** the guard method receives the TransformationContext  
**And** the guard can query the "rules" alias  
**And** the guard returns true, allowing rule execution

#### Scenario: Rule skipped when guard fails on alias lookup

**Given** a rule with guard that queries "rules" alias for FK metadata  
**And** no matching metadata exists in "rules" alias  
**When** the transformation executes  
**Then** the guard returns false  
**And** the rule is not executed  
**And** no target element is created

#### Scenario: Multiple rules with discriminating guards

**Given** two rules for the same source type with different guards  
**And** each guard queries different entries from "rules" alias  
**When** a source element matches one guard but not the other  
**Then** only the matching rule executes  
**And** only one target element is created

---

### Requirement: Lazy Rules with Aliases

Lazy rules MUST work correctly with aliased resources.

#### Scenario: Lazy rule deferred until equivalent called

**Given** a lazy rule with `@Transform(alias = "asm", type = EReference.class)`  
**And** `@To(alias = "rdbms", type = EClass.class)`  
**When** the main transformation completes without calling equivalent  
**Then** the lazy rule has not executed  
**And** no element exists for the lazy rule's target

#### Scenario: Lazy rule creates element in aliased target

**Given** a lazy rule targeting "rdbms" alias  
**When** `ctx.equivalent(sourceRef, EClass.class)` is called  
**Then** the lazy rule executes  
**And** the created element is in the "rdbms" ResourceSet  
**And** the element is returned by equivalent()

#### Scenario: Lazy rule result cached across calls

**Given** a lazy rule for EReference elements  
**When** `ctx.equivalent()` is called twice for the same source  
**Then** the lazy rule executes only on the first call  
**And** the cached result is returned on the second call

---

### Requirement: Greedy Rules with Aliases

Greedy rules MUST process all matching elements from aliased sources.

#### Scenario: Greedy rule fires for all matching elements

**Given** a greedy rule with `@Transform(alias = "asm", type = EClass.class)`  
**And** the "asm" alias contains 5 EClass elements  
**When** the transformation executes  
**Then** the rule fires 5 times  
**And** 5 target elements are created

#### Scenario: Greedy rule creates outputs in aliased target

**Given** a greedy rule with `@To(alias = "rdbms", type = EAnnotation.class)`  
**When** the transformation executes  
**Then** all created annotations are in the "rdbms" ResourceSet  
**And** no annotations are added to other aliases

#### Scenario: Greedy rule respects guard with alias query

**Given** a greedy rule with guard querying "rules" alias  
**And** the guard matches only 2 of 5 source elements  
**When** the transformation executes  
**Then** the rule fires only 2 times  
**And** only 2 target elements are created

---

### Requirement: Rule Inheritance with Aliases

Rule inheritance MUST work correctly with alias annotations.

#### Scenario: Abstract rule defines alias, extending rule inherits

**Given** an abstract rule with `@Transform(alias = "asm", type = EAttribute.class)`  
**And** an extending rule without its own @Transform  
**When** the extending rule is registered  
**Then** it inherits the "asm" alias from the abstract rule

#### Scenario: Extending rule overrides alias

**Given** an abstract rule with default alias  
**And** an extending rule with `@Transform(alias = "custom", type = EAttribute.class)`  
**When** the extending rule executes  
**Then** it reads elements from "custom" alias  
**And** not from the abstract rule's alias

#### Scenario: Abstract rule with guard on alias

**Given** an abstract rule base  
**And** an extending rule with guard querying "mapping" alias  
**When** the guard evaluates  
**Then** it has access to the TransformationContext  
**And** it can query the "mapping" alias

---

### Requirement: Pre/Post Hooks with Aliases

Execution hooks MUST be able to register and access aliased resources.

#### Scenario: Pre-hook registers additional alias

**Given** a `@PreExecution` method that calls `ctx.registerResource("mapping", mappingSet)`  
**When** transformation rules execute  
**Then** the "mapping" alias is available  
**And** rules can call `ctx.all("mapping", ...)` successfully

#### Scenario: Post-hook accesses all aliases

**Given** a `@PostExecution` method  
**And** transformation has aliases "source", "target", "mapping"  
**When** the post-hook executes  
**Then** it can call `ctx.all("source", ...)` for source elements  
**And** it can call `ctx.all("target", ...)` for transformed elements  
**And** it can call `ctx.all("mapping", ...)` for mapping entries

#### Scenario: Post-hook applies cross-model mappings

**Given** transformed elements in "rdbms" alias  
**And** name mapping entries in "mapping" alias  
**When** the post-hook iterates "rdbms" elements  
**And** looks up corresponding mapping from "mapping" alias  
**Then** the mappings are applied to the transformed elements

---

### Requirement: Complex Multi-Step Transformations

The framework MUST support complex transformation chains similar to judo-tatami ASM2RDBMS.

#### Scenario: ASM2RDBMS-like transformation

**Given** aliases "asm" (source), "rdbms" (target), "rules" (FK rules)  
**And** EClass elements in "asm"  
**And** EReference elements in "asm"  
**And** FK rule entries in "rules"  
**When** the transformation executes  
**Then** EClass → Table transformation creates tables in "rdbms"  
**And** EReference → ForeignKey transformation queries "rules" for FK type  
**And** Guards discriminate between FK, inversFK, and junction table  
**And** Lazy junction table rule executes on-demand  
**And** All created elements reference each other via equivalent()

#### Scenario: Discriminated transformations

**Given** multiple rules for EReference with different guards  
**And** each guard checks different conditions from "rules" alias  
**And** each rule uses different target type  
**When** the transformation executes  
**Then** each EReference is transformed by exactly one matching rule  
**And** the correct target type is created based on guard evaluation
