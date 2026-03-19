# Spec Delta: documentation

## MODIFIED Requirements

### Requirement: Parallel Transformation Documentation

Documentation MUST accurately describe the parallel transformation feature including current threshold values, thread-safety guarantees, and configuration options.

#### Scenario: Documentation reflects current parallel threshold

**Given** the parallel transformation feature
**When** a user reads the documentation
**Then** they see the correct threshold of 1000 elements (not 5000)
**And** they see the two-phase staging approach explained
**And** they see thread-safety guarantees documented
**And** they see fail-fast error handling with TransformationException

### Requirement: Resource Alias Documentation

Documentation MUST describe the resource alias feature including annotations, API methods, and usage examples.

#### Scenario: Documentation covers resource alias annotations

**Given** the resource alias feature is implemented
**When** a user reads the documentation
**Then** they find @Transform annotation documentation with alias parameter
**And** they find @To annotation documentation with alias parameter
**And** they find examples of multi-model transformations
**And** they find ctx.registerResource() and ctx.all() API documentation

### Requirement: Activity-Based Greedy Documentation

Documentation MUST describe the activity-based greedy processing feature for ETL compatibility.

#### Scenario: Documentation covers activity-based processing

**Given** the activity-based greedy feature is implemented
**When** a user reads the documentation
**Then** they find @ActivityBased annotation documentation
**And** they find etlCompatibilityMode() configuration documentation
**And** they understand when to use activity-based vs eager processing

### Requirement: Thread-Safety Guidelines Documentation

Documentation MUST provide clear guidelines for writing thread-safe transformation rules.

#### Scenario: Documentation provides thread-safety guidance

**Given** parallel transformation is the default mode
**When** a user reads the documentation
**Then** they find a list of safe operations in transformation rules
**And** they find a list of unsafe operations to avoid
**And** they understand atomic cache operations (getOrCreate)

### Requirement: Agent Documentation Accuracy

The agent-docs directory MUST contain accurate, complete documentation for LLM coding agents to generate correct transformation and validation code.

#### Scenario: Agent docs contain all current annotations

**Given** an LLM agent needs to write a transformation rule
**When** the agent reads agent-docs/ANNOTATIONS.md
**Then** it finds documentation for all transformation annotations including @ActivityBased
**And** all code examples compile with the current API
**And** the documentation is self-contained

#### Scenario: Agent docs contain current execution details

**Given** an LLM agent needs to understand transformation execution
**When** the agent reads agent-docs/EXECUTION.md
**Then** it finds the correct parallel threshold (1000)
**And** it finds atomic cache operations (getOrCreate) documentation
**And** it finds TransformationException handling documentation
**And** it finds parent rule atomicity documentation

#### Scenario: Agent docs contain thread-safe patterns

**Given** an LLM agent needs to write thread-safe transformation code
**When** the agent reads agent-docs/PATTERNS.md
**Then** it finds activity-based greedy pattern documentation
**And** it finds thread-safe transformation pattern with safe/unsafe operations
**And** all example code compiles correctly

#### Scenario: Agent decision tree is complete

**Given** an LLM agent needs to decide which documentation to read
**When** the agent reads agent-docs/INDEX.md
**Then** it finds decision tree entries for all new features
**And** it can determine the correct file for @ActivityBased usage
**And** it can determine the correct file for thread-safety patterns
