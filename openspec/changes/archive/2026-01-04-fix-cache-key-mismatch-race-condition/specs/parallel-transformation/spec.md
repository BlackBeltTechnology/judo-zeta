# Parallel Transformation Specification Delta

## ADDED Requirements

### Requirement: Unified Lock Key for Same Logical Target

When multiple code paths access the same transformation target (e.g., `equivalent()` and `executeParentRule()`), they MUST use the same lock key to prevent race conditions.

#### Scenario: equivalent() and executeParentRule() use same lock

**Given** a @Primary rule "CreateModel" that produces Model.class
**And** Thread A calls `equivalent(source, Model.class)`
**And** Thread B calls `executeParentRule("CreateModel", source)`
**When** both threads attempt to acquire locks
**Then** both use lock key `(source, "CreateModel")`
**And** only one thread executes the rule
**And** the other thread receives the cached result

#### Scenario: First execution establishes canonical rule name

**Given** a source element with no cached Model target
**And** multiple rules produce Model.class ("CreateModel", "ModelFromX")
**When** the first thread calls `equivalent(source, Model.class)`
**And** rule "CreateModel" is selected (e.g., @Primary)
**Then** `typeToRuleMapping[(source, "Model")]` is set to "CreateModel"
**And** subsequent calls to `equivalent(source, Model.class)` use lock key `(source, "CreateModel")`

### Requirement: Type-to-Rule Cross-Reference Cache

The cache MUST maintain a mapping from (source, targetType) to the canonical rule name used for that transformation.

#### Scenario: Cross-reference populated on first cache

**Given** no prior transformation for source element
**When** `addMapping(source, "CreateModel", target, isPrimary)` is called
**And** target.eClass().getName() is "Model"
**Then** `typeToRuleMapping[(source, "Model")]` is set to "CreateModel"
**And** this mapping is immutable for the duration of the transformation

#### Scenario: Cross-reference used for lock key normalization

**Given** `typeToRuleMapping[(source, "Model")]` = "CreateModel"
**When** `equivalent(source, Model.class)` is called
**Then** lock key is `(source, "CreateModel")` (not the iterated rule's name)
**And** cache lookup uses `getByRule(source, "CreateModel")`

### Requirement: Deterministic Parallel Results

Parallel transformation MUST produce identical results regardless of thread interleaving.

#### Scenario: No duplicate elements in parallel mode

**Given** a transformation with @Primary rules
**And** parallel execution enabled
**When** multiple threads access same source via different patterns
**Then** exactly one target is created per (source, targetType) pair
**And** element counts match sequential mode

#### Scenario: Consistent results across runs

**Given** a transformation executed 20 times in parallel mode
**When** comparing element counts between runs
**Then** all runs produce identical element counts
**And** 0 failures due to race conditions
