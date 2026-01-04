# Documentation Specification Delta

## MODIFIED Requirements

### Requirement: Performance metrics MUST cover all major transformation phases

When transformation metrics are enabled, the timing breakdown SHALL account for at least 70% of total transformation time, covering:
- Rule execution (greedy and lazy)
- Element collection and model iteration
- Staging commit phase
- Cache operations

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
