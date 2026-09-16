# Documentation Specification Delta

## MODIFIED Requirements

### Requirement: Performance metrics MUST cover all major transformation phases

When transformation metrics are enabled, the timing breakdown SHALL account for at least 90% of total transformation time, covering:
- Rule execution (greedy and lazy)
- Element collection and model iteration
- Staging commit phase
- Cache operations
- Rule matching and loop overhead
- Parallel execution overhead

#### Scenario: TransformationExecutor reports element collection time

**Given** transformation metrics are enabled
**And** a transformation is executed with source elements
**When** the metrics report is generated
**Then** the "Model iteration" metric includes time spent collecting source elements
**And** the "Model iteration" metric includes list conversion and partitioning time

#### Scenario: TransformationExecutor reports staging commit time

**Given** transformation metrics are enabled
**And** a transformation uses staging mode
**When** `commitStagedElements()` is called
**Then** the "Staging commit" metric reflects the actual commit duration

#### Scenario: Total transformation time is tracked

**Given** transformation metrics are enabled
**When** `transform()` method completes
**Then** the total transformation time is recorded
**And** the report shows ACCOUNTED vs UNACCOUNTED breakdown

#### Scenario: Rule loop overhead is tracked

**Given** transformation metrics are enabled
**And** a transformation processes source elements
**When** `executeEagerRulesFor()` is called for each element
**Then** the "Rule matching" metric reflects `getRulesForSource()` lookup time
**And** the "Rule loop" metric reflects iteration through rules
**And** the "Cache getOrCreate" metric reflects cache operation overhead

#### Scenario: Parallel execution overhead is tracked

**Given** transformation metrics are enabled
**And** a transformation runs in parallel mode
**When** `transformParallel()` is executed
**Then** the "Chunk processing" metric reflects total time in transformChunk
**And** the "Future creation" metric reflects CompletableFuture setup time
**And** the "Parallel wait" metric reflects time waiting for tasks to complete
