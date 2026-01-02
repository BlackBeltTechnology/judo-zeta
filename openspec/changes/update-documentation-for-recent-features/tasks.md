# Tasks: Update Documentation for Recent Features

## Phase 1: README.md Updates

- [ ] Update parallel threshold from 5000 to 1000
- [ ] Update TransformationExecutor builder example with new options
- [ ] Add TransformationException error handling example
- [ ] Add resource alias feature mention
- [ ] Add @ActivityBased annotation mention
- [ ] Verify all code examples compile with current API

## Phase 2: Agent Documentation (Critical - LLM Agents)

### agent-docs/INDEX.md
- [ ] Add decision tree entry for @ActivityBased usage
- [ ] Add entry for thread-safety patterns
- [ ] Add entry for error handling with TransformationException
- [ ] Verify all file references are correct

### agent-docs/ANNOTATIONS.md
- [ ] Add @ActivityBased annotation section
  - Usage with @Greedy @Lazy
  - Purpose: ETL-compatible activation-based processing
  - Example code
- [ ] Update @Greedy section to reference @ActivityBased option
- [ ] Verify all annotation examples compile

### agent-docs/CONTEXT-API.md
- [ ] Add ElementResolutionCache.getOrCreate() documentation
  - Signature and parameters
  - Thread-safety guarantees
  - When supplier executes
- [ ] Add note about executeParentRule() atomicity
- [ ] Verify Resource Management section is complete
- [ ] Verify all code examples are correct

### agent-docs/EXECUTION.md
- [ ] Add "Atomic Cache Operations" section
  - getOrCreate() pattern explanation
  - Why needed (race conditions)
  - Per-key locking mechanism
- [ ] Add "Parent Rule Atomicity" section
  - executeParentRule() uses getOrCreate()
  - @Extends chains are atomic
- [ ] Add "TransformationException" section
  - getFailedElement()
  - getRuleName()
  - getCause()
  - Example try-catch block
- [ ] Add "Guard Rejection Caching" section
  - Guards evaluated under lock
  - Rejections cached
- [ ] Verify threshold is 1000 (not 5000)

### agent-docs/QUICK-REF.md
- [ ] Add @ActivityBased to Core Annotations table
- [ ] Add brief thread-safe rule guidelines
- [ ] Add TransformationException handling example
- [ ] Verify all examples compile with current API

### agent-docs/PATTERNS.md
- [ ] Add "Activity-Based Greedy Pattern" section
  - @Greedy @Lazy @ActivityBased usage
  - Comparison with standard @Greedy @Lazy
  - ETL compatibility mode alternative
  - Complete example
- [ ] Add "Thread-Safe Transformation Pattern" section
  - Safe operations list
  - Unsafe operations to avoid
  - Example of safe rule code
- [ ] Verify all existing patterns still compile

### agent-docs/TROUBLESHOOTING.md
- [ ] Add "Race Conditions" section
  - Symptoms (duplicate elements)
  - How getOrCreate() fixes it
  - Verification steps
- [ ] Add "TransformationException" handling section
- [ ] Add "Parallel Execution Issues" diagnosis

## Phase 3: Parallel Execution Documentation

- [ ] Rewrite docs/transformation/architecture/parallel-execution.md
  - Update threshold to 1000
  - Add two-phase staging approach diagram
  - Add thread-safety guarantees table
  - Add atomic cache operations (getOrCreate)
  - Add fail-fast error handling section
  - Add deterministic ordering explanation
  - Add executor reuse documentation

## Phase 4: User Guide Updates

- [ ] Update docs/transformation/user-guide/transformation-rules.md
  - Add @Transform annotation documentation
  - Add @To annotation documentation
  - Add multi-model transformation example
  - Add ctx.create() vs ctx.createTarget() explanation
  - Add ctx.registerResource() documentation

- [ ] Update docs/transformation/user-guide/greedy-matching.md
  - Add @ActivityBased annotation documentation
  - Add ETL compatibility mode (etlCompatibilityMode)
  - Add activation tracking explanation
  - Update examples with new options

## Phase 5: Best Practices and Reference

- [ ] Update docs/transformation/best-practices/performance.md
  - Update parallel threshold guidance
  - Add thread-safety guidelines for transformation rules
  - Add safe vs unsafe operations table
  - Add executor reuse guidance

- [ ] Update docs/transformation/etl-comparison/overview.md
  - Add resource alias comparison (Model!Type vs @Transform)
  - Add @ActivityBased ETL parity notes
  - Update feature parity table

## Phase 6: Validation

- [ ] Verify all documentation links work
- [ ] Verify all code examples compile
- [ ] Ensure consistency of terminology across docs
- [ ] Check that all new features are documented
- [ ] Test agent-docs examples against actual API
- [ ] Verify no outdated threshold values (should be 1000)

## Dependencies

- Phase 1 can run independently
- **Phase 2 (agent-docs) is highest priority** - affects LLM code generation
- Phase 3-5 can run in parallel after Phase 2
- Phase 6 must run last

## Notes for Agent Documentation

When updating agent-docs, remember:
1. **Self-contained** - Each file should work standalone
2. **Accurate** - Wrong info = wrong generated code
3. **Complete** - Missing features = unused features
4. **Concise** - Optimized for token efficiency
5. **Verifiable** - All examples must compile
