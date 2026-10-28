## MODIFIED Requirements

### Requirement: Thread-Safe Element Resolution Cache

The element resolution cache MUST provide fast lookup performance with minimal allocation overhead while maintaining thread safety in parallel mode.

#### Scenario: Fast-path cache lookup avoids allocation

**Given** a transformation running in sequential mode
**And** a source element with cached mappings
**When** `getOrCreate(source, ruleName, ...)` is called
**Then** the cache lookup completes without allocating new objects
**And** the lookup requires at most 2 map operations (outer map + inner map)
**And** no `computeIfAbsent` is invoked on the fast path

#### Scenario: Cache hit returns immediately

**Given** a source element with an existing mapping for rule "RuleA"
**When** `getOrCreate(source, "RuleA", ...)` is called
**Then** the cached target is returned immediately
**And** the `ruleExecutor` supplier is NOT invoked
**And** no rejection cache lookup is performed

#### Scenario: Rejection check is inlined with cache lookup (sequential mode)

**Given** a transformation running in sequential mode
**And** a source element was previously rejected by rule "RuleA"
**When** `getOrCreate(source, "RuleA", ...)` is called
**Then** the rejection is detected in a single map lookup
**And** `null` is returned immediately
**And** no separate rejection cache is consulted

---

## ADDED Requirements

### Requirement: Cache Lookup Performance Target

The cache lookup operation MUST achieve sub-millisecond average latency per operation in sequential mode.

#### Scenario: High-throughput cache operations

**Given** a transformation with 40,000+ `equivalent()` calls
**And** sequential execution mode enabled
**When** the transformation completes
**Then** total cache operation time is less than 15% of transformation time
**And** average lookup time is less than 0.01ms per operation

#### Scenario: Cache hit rate maintained

**Given** a transformation with repeated lookups for same (source, ruleName)
**When** the transformation completes
**Then** cache hit rate is at least 90%
**And** each cache hit avoids rule re-execution

### Requirement: Pre-Allocation Support for Large Models

The cache MUST support optional pre-allocation of inner maps for known source elements to eliminate allocation overhead during transformation.

#### Scenario: Pre-allocation reduces allocation overhead

**Given** a transformation with known source elements
**And** `preAllocate(sources)` is called before transformation
**When** the transformation executes
**Then** no new inner map allocations occur for pre-allocated sources
**And** cache operations are faster due to pre-sized maps

#### Scenario: Pre-allocation is optional

**Given** a transformation without pre-allocation
**When** the transformation executes
**Then** inner maps are allocated on demand (backward compatible)
**And** the transformation produces correct results
