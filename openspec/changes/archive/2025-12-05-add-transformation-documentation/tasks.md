# Tasks: Add Transformation Documentation

## Phase 1: Directory Restructure

- [x] Create `docs/validation/` directory structure
- [x] Move `docs/getting-started.md` to `docs/validation/getting-started.md`
- [x] Move `docs/user-guide/` to `docs/validation/user-guide/`
- [x] Move `docs/best-practices/` to `docs/validation/best-practices/`
- [x] Move `docs/evl-comparison/` to `docs/validation/evl-comparison/`
- [x] Move `docs/examples/` to `docs/validation/examples/`
- [x] Move `docs/architecture/` to `docs/validation/architecture/`
- [x] Move `docs/reference/` to `docs/validation/reference/`
- [x] Create `docs/validation/index.md` as validation hub
- [x] Update all internal links in validation docs

## Phase 2: Transformation Getting Started

- [x] Create `docs/transformation/` directory structure
- [x] Create `docs/transformation/getting-started.md` with:
  - [x] Prerequisites section
  - [x] Maven/P2/OSGi installation
  - [x] First transformation example (step-by-step)
  - [x] Quick reference card
  - [x] Next steps links

## Phase 3: Transformation User Guide

- [x] Create `docs/transformation/user-guide/core-concepts.md`:
  - [x] Overview and key benefits
  - [x] Core components diagram (Mermaid)
  - [x] @TransformationContext explanation
  - [x] @TransformRule explanation
  - [x] TransformFunction interface
  - [x] TransformationResult and trace
  - [x] TransformationContext API
  - [x] TransformationRegistry
  - [x] TransformationExecutor
  - [x] Annotation summary table

- [x] Create `docs/transformation/user-guide/transformation-rules.md`:
  - [x] Basic rule structure
  - [x] Return types and TransformFunction
  - [x] Creating target elements
  - [x] Accessing source element properties
  - [x] Setting target element properties
  - [x] Handling null values
  - [x] Error handling in rules

- [x] Create `docs/transformation/user-guide/element-resolution.md`:
  - [x] equivalent() semantics
  - [x] equivalents() for multiple targets
  - [x] Primary rule resolution
  - [x] Lazy rule execution
  - [x] Cache behavior

- [x] Create `docs/transformation/user-guide/guards-and-conditions.md`:
  - [x] @Guard annotation usage
  - [x] Guard method signatures
  - [x] Conditional transformations
  - [x] Combining guards with other annotations

- [x] Create `docs/transformation/user-guide/lazy-evaluation.md`:
  - [x] @Lazy annotation purpose
  - [x] When to use lazy rules
  - [x] On-demand execution via equivalent()
  - [x] Caching of lazy results

- [x] Create `docs/transformation/user-guide/rule-inheritance.md`:
  - [x] @Abstract annotation
  - [x] @Extends annotation
  - [x] executeParentRule() usage
  - [x] Multi-level inheritance chains
  - [x] Cycle detection

- [x] Create `docs/transformation/user-guide/greedy-matching.md`:
  - [x] @Greedy annotation purpose
  - [x] Type hierarchy matching
  - [x] Use cases for greedy rules
  - [x] Combining with other rules

- [x] Create `docs/transformation/user-guide/discriminated-equivalence.md`:
  - [x] equivalentDiscriminated() semantics
  - [x] Use cases (multiple outputs from one source)
  - [x] ID generation and naming
  - [x] Cache structure for discriminated elements

- [x] Create `docs/transformation/user-guide/lifecycle-hooks.md`:
  - [x] @PreExecution hooks
  - [x] @PostExecution hooks
  - [x] Hook execution order
  - [x] Common hook patterns

- [x] Create `docs/transformation/user-guide/extension-methods.md`:
  - [x] @ExtensionMethod annotation
  - [x] @Cached annotation
  - [x] Helper method patterns
  - [x] Sharing extensions between transformations

## Phase 4: Transformation Best Practices

- [x] Create `docs/transformation/best-practices/rule-naming.md`:
  - [x] Naming conventions (Source2Target)
  - [x] Rule name constants pattern
  - [x] Avoiding name collisions

- [x] Create `docs/transformation/best-practices/rule-organization.md`:
  - [x] One context class per source type
  - [x] Module organization
  - [x] Splitting large transformations

- [x] Create `docs/transformation/best-practices/performance.md`:
  - [x] Parallel execution thresholds
  - [x] Caching expensive operations
  - [x] Avoiding repeated equivalent() calls
  - [x] Profiling transformations

- [x] Create `docs/transformation/best-practices/error-handling.md`:
  - [x] Handling missing source elements
  - [x] Dealing with unresolved proxies
  - [x] Logging and diagnostics
  - [x] Transformation trace for debugging

- [x] Create `docs/transformation/best-practices/testing.md`:
  - [x] Unit testing transformation rules
  - [x] Integration testing full transformations
  - [x] Verifying trace output
  - [x] Test fixtures and model builders

## Phase 5: ETL Comparison Documentation

- [x] Create `docs/transformation/etl-comparison/overview.md`:
  - [x] ETL vs Zeta philosophy
  - [x] Key differences (declarative vs programmatic)
  - [x] When to use which
  - [x] Benefits of Java-based approach

- [x] Create `docs/transformation/etl-comparison/syntax-mapping.md`:
  - [x] Rule declaration mapping
  - [x] Type specification mapping
  - [x] Guard mapping
  - [x] Lazy/abstract/primary/greedy mapping
  - [x] extends mapping
  - [x] equivalent() mapping
  - [x] pre/post blocks mapping
  - [x] EOL operations mapping
  - [x] Complete syntax reference table

- [x] Create `docs/transformation/etl-comparison/migration-guide.md`:
  - [x] Step-by-step migration process
  - [x] Converting .etl files to Java classes
  - [x] Handling ETL-specific constructs
  - [x] Testing migrated transformations
  - [x] Common migration pitfalls

- [x] Create `docs/transformation/etl-comparison/feature-parity.md`:
  - [x] Feature matrix (ETL vs Zeta)
  - [x] Supported features
  - [x] Unsupported features with alternatives
  - [x] Future roadmap items

## Phase 6: Transformation Examples

- [x] Create `docs/transformation/examples/simple-transformations.md`:
  - [x] Basic rule examples
  - [x] Single source to single target
  - [x] Property mapping examples

- [x] Create `docs/transformation/examples/entity-transformations.md`:
  - [x] EntityType to Table transformation
  - [x] Handling inheritance
  - [x] Attribute to Column mapping
  - [x] Reference from judo-tatami-esm2psm

- [x] Create `docs/transformation/examples/namespace-transformations.md`:
  - [x] Namespace/Package transformations
  - [x] Hierarchical structure handling
  - [x] From judo-tatami namespace modules

- [x] Create `docs/transformation/examples/service-transformations.md`:
  - [x] TransferObjectType transformations
  - [x] Operation transformations
  - [x] Complex service layer examples

- [x] Create `docs/transformation/examples/lazy-rule-examples.md`:
  - [x] On-demand transformation patterns
  - [x] Lazy reference resolution
  - [x] Performance optimization with lazy rules

- [x] Create `docs/transformation/examples/discriminated-examples.md`:
  - [x] Multiple operations from relations
  - [x] CRUD operation generation
  - [x] Real-world discriminator patterns

- [x] Create `docs/transformation/examples/inheritance-examples.md`:
  - [x] Abstract base rules
  - [x] Inheritance chains
  - [x] Code reuse patterns

## Phase 7: Transformation Architecture

- [x] Create `docs/transformation/architecture/overview.md`:
  - [x] System architecture diagram (Mermaid)
  - [x] Component descriptions
  - [x] Data flow overview

- [x] Create `docs/transformation/architecture/execution-flow.md`:
  - [x] Transformation lifecycle diagram
  - [x] Registration phase
  - [x] Discovery phase
  - [x] Execution phases (eager and lazy)
  - [x] Result collection

- [x] Create `docs/transformation/architecture/parallel-execution.md`:
  - [x] Parallelization strategy
  - [x] Threshold configuration
  - [x] Chunk size tuning
  - [x] Thread safety considerations
  - [x] Performance characteristics

- [x] Create `docs/transformation/architecture/element-resolution-cache.md`:
  - [x] Cache structure diagram
  - [x] Source to target mapping
  - [x] Discriminated cache structure
  - [x] Thread safety implementation

- [x] Create `docs/transformation/architecture/rule-inheritance-graph.md`:
  - [x] Dependency graph construction
  - [x] Topological sorting
  - [x] Cycle detection algorithm
  - [x] Execution order determination

## Phase 8: Transformation Reference

- [x] Create `docs/transformation/reference/annotations.md`:
  - [x] @TransformationContext parameters
  - [x] @TransformRule parameters
  - [x] @Lazy usage
  - [x] @Abstract usage
  - [x] @Primary usage
  - [x] @Greedy usage
  - [x] @Extends parameters
  - [x] @Guard parameters
  - [x] @PreExecution usage
  - [x] @PostExecution usage

- [x] Create `docs/transformation/reference/transformation-context.md`:
  - [x] createTarget() method
  - [x] equivalent() method
  - [x] equivalents() method
  - [x] equivalentDiscriminated() method
  - [x] executeParentRule() method
  - [x] getAllSource() method
  - [x] getAllTarget() method
  - [x] setAttribute()/getAttribute() methods

- [x] Create `docs/transformation/reference/transformation-result.md`:
  - [x] Result object structure
  - [x] Accessing target ResourceSet
  - [x] Accessing transformation trace
  - [x] Statistics and metrics

- [x] Create `docs/transformation/reference/transformation-trace.md`:
  - [x] Trace structure
  - [x] JSON export format
  - [x] TraceEntry fields
  - [x] Using trace for debugging

- [x] Create `docs/transformation/reference/troubleshooting.md`:
  - [x] Common errors and solutions
  - [x] Rule not found
  - [x] Circular inheritance
  - [x] Missing equivalent
  - [x] Parallel execution issues
  - [x] At least 10 common issues

## Phase 9: Main Index Update

- [x] Create `docs/transformation/index.md` as transformation hub
- [x] Update `docs/index.md` to cover both frameworks:
  - [x] Welcome and overview
  - [x] Framework comparison (validation vs transformation)
  - [x] Quick navigation to validation docs
  - [x] Quick navigation to transformation docs
  - [x] Shared concepts section
- [x] Update README.md to reference new documentation structure

## Phase 10: Validation and Review

- [x] Verify all internal links work
- [x] Verify code examples compile
- [x] Check Mermaid diagrams render correctly
- [x] Review documentation for consistency
- [x] Update openspec documentation spec
