# Spec Delta: Parallel Transformation Performance

**Capability**: parallel-transformation

## ADDED Requirements

### Requirement: Rule Lookup Caching

The TransformationRegistry MUST cache `getRulesForSource()` results for each source type to avoid repeated computation.

#### Scenario: Same type lookup returns cached result

**Given** a transformation with 10,000 source elements
**And** 50 elements share the same Java class type
**When** `getRulesForSource()` is called for each element
**Then** the rule lookup computation happens only once per unique type
**And** subsequent calls return the cached result in O(1) time
**And** cache uses ConcurrentHashMap for thread-safe access

#### Scenario: Cache hit rate is tracked in metrics

**Given** TransformationMetrics is enabled
**And** transformation processes elements of 100 unique types
**When** transformation completes
**Then** metrics show cache hit count and miss count
**And** cache hit rate is calculated as hits / (hits + misses)

---

### Requirement: Pre-Partitioned Rule Lists

The TransformationRegistry MUST pre-compute partitioned lists of rules by their execution phase.

#### Scenario: Eager greedy rules are pre-computed

**Given** a registry with 50 registered transformation rules
**And** 20 rules are @Greedy, non-@Lazy, non-@Abstract
**When** `getEagerGreedyRules()` is called
**Then** a pre-computed list of 20 rules is returned
**And** no filtering computation occurs on each call
**And** the list preserves registration order

#### Scenario: Pre-partitioning happens lazily after first rule registration

**Given** a newly created TransformationRegistry
**When** rules are registered
**Then** pre-partitioned lists are not computed during registration
**And** lists are computed lazily on first access
**And** lists are cached after computation

#### Scenario: Partitioned lists are immutable

**Given** pre-partitioned rule lists are computed
**When** additional rules are registered after computation
**Then** the cached lists are invalidated
**And** new lists are computed on next access

---

### Requirement: Performance Regression Tests

The transformation framework MUST include performance regression tests that verify optimization effectiveness.

#### Scenario: Large model transformation time is bounded

**Given** a test model with 50,000 source elements
**And** baseline metrics are recorded for the current version
**When** transformation is executed with optimizations enabled
**Then** total transformation time does not exceed baseline + 10%
**And** `getRulesForSource` time is significantly reduced from baseline

#### Scenario: Memory overhead is bounded

**Given** a transformation with 100 unique source types
**And** 200 registered transformation rules
**When** caches are populated
**Then** additional memory usage is bounded (< 1MB)
**And** no memory leaks occur during cache access
