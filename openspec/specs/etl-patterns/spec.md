# etl-patterns Specification

## Purpose
TBD - created by archiving change add-etl-pattern-integration-tests. Update Purpose after archive.
## Requirements
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

### Requirement: Idempotent Caching (ETL Compound ID Bug - Not Replicated)

The `equivalent()` method MUST be idempotent: calling it multiple times with the same (source, ruleName) pair MUST return the same target, regardless of which rule initiated the call.

**Context**: ETL includes the calling context in its cache key, leading to compound XMI IDs and non-idempotent behavior. This is a **bug** that Zeta intentionally does not replicate.

#### Scenario: Same source same target regardless of caller

**Given** a source element `op` of type EOperation
**And** two rules A and B both call `ctx.equivalent(op, "TargetRule")`
**When** the transformation executes
**Then** TargetRule executes exactly once (first call wins)
**And** both callers receive the same target instance (identity equality)
**And** the target has a simple XMI ID like `(source/op)/TargetRule`
**And** no compound IDs like `((A)/op)_((B)/op)` are generated

#### Scenario: Cache key excludes calling context

**Given** a source element transformed by rule R
**When** rule A calls `ctx.equivalent(source, "R")`
**And** rule B also calls `ctx.equivalent(source, "R")`
**Then** the cache key for both is `(source, "R")` only
**And** calling rule (A vs B) does NOT affect the cache lookup

#### Scenario: XMI ID format is simple (not compound)

**Given** structured IDs are enabled
**When** multiple rules call `equivalent()` for the same (source, ruleName)
**Then** the target XMI ID is simple format: `(source_id)/RuleName`
**And** the ID does NOT contain compound patterns like `_((`, `)_(`, or nested parentheses beyond one level

#### Scenario: ETL compound ID bug NOT replicated

**Given** ETL produces compound IDs for multi-context equivalent() calls
**When** Zeta executes the same transformation
**Then** Zeta produces simple IDs
**And** XMI comparison will show differences (expected, not a bug)
**And** the Zeta output is semantically correct (idempotent)

---

### Requirement: Idempotent Rule Caching (Correct Behavior)

The Zeta Transformation Framework SHALL implement idempotent rule caching where the same (source, ruleName) pair always returns the same target instance. This is the correct behavior that ensures deterministic, thread-safe transformations.

**Note**: ETL's compound XMI ID generation for `@lazy @greedy` rules from different calling contexts is a **bug** that violates idempotency. Zeta intentionally does NOT replicate this buggy behavior.

#### Scenario: Same source returns same target regardless of calling context

**Given** a rule "CreateOperationBody" for source type Operation
**And** Rule A invokes `ctx.equivalent(operation, "CreateOperationBody")` from context X
**And** Rule B invokes `ctx.equivalent(operation, "CreateOperationBody")` from context Y
**When** both rules execute for the same Operation instance
**Then** both rules receive the SAME cached target instance
**And** the target has a simple XMI ID like `(esm/_abc123)/CreateOperationBody`
**And** NO compound ID is generated
**Because** idempotent caching ensures deterministic results

#### Scenario: Cache key ensures idempotency

**Given** a transformation with multiple rules calling `equivalent()` for the same (source, ruleName) pair
**When** the cache lookup is performed
**Then** the cache key is computed from (source identity, ruleName) only
**And** the calling rule's context is NOT part of the cache key
**And** all callers receive the same cached target
**Because** context-independent caching is required for idempotency

#### Scenario: ETL compound ID bug is not replicated

**Given** ETL would generate compound ID format `((A)/RuleName)_((B)/CallerRule)` (buggy behavior)
**When** the same scenario executes in Zeta
**Then** Zeta generates simple ID format `(A)/RuleName`
**And** the caller context `B` is NOT included in the ID
**Because** ETL's compound ID generation violates idempotency and is a known bug

#### Scenario: Expected XMI ID differences from ETL

**Given** a transformation that triggers compound ID generation in ETL
**When** the same transformation runs in Zeta
**Then** XMI ID differences occur due to ETL bugs:
  - 2 OperationBody IDs (ETL compound bug, Zeta simple correct)
  - 8 random fault UUIDs (ETL uses non-deterministic EcoreUtil.generateUUID())
  - 8 compound fault parameter IDs (related to random UUIDs)
  - 2 additional compound IDs (other ETL context-dependent bugs)
**And** these differences represent Zeta's CORRECT behavior vs ETL's BUGGY behavior
**And** no attempt is made to replicate ETL's bugs

---

### Requirement: Context-Independent Rule Caching

The rule execution cache SHALL use only the source element identity and rule name as cache key. The calling context (which rule invoked the lookup) SHALL NOT affect caching behavior.

**Rationale**: This design ensures:
1. **Idempotency**: Same input always produces same output
2. **Determinism**: Results are consistent across runs
3. **Thread-safety**: Parallel execution produces identical results to sequential
4. **Predictability**: No hidden context-dependent behavior

#### Scenario: Multiple callers get same cached result

**Given** Rule A, Rule B, and Rule C all call `ctx.equivalent(source, "TargetRule")`
**And** all three rules use the same source element
**When** TargetRule executes for the first caller (e.g., Rule A)
**Then** a target is created and cached
**And** Rule B receives the same cached target (not a new instance)
**And** Rule C receives the same cached target (not a new instance)
**And** TargetRule executes exactly once, not three times

#### Scenario: Idempotent transformation guarantees

**Given** the design goal of idempotent transformations
**When** the same (source, ruleName) pair is requested multiple times
**Then** the same target is returned every time
**And** this behavior is consistent regardless of parallel or sequential execution
**And** this is the CORRECT behavior (ETL's context-dependent caching is a bug)

---

