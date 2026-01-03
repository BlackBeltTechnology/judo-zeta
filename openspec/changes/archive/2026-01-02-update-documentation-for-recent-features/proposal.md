# Proposal: Update Documentation for Recent Features

**Change ID:** update-documentation-for-recent-features
**Status:** Draft
**Created:** 2026-01-02
**Type:** Documentation

## Summary

Update all documentation files (README.md, docs/*, agent-docs/*) to reflect the features and changes implemented in the last month. Several significant features have been added but documentation has not been updated to reflect the new APIs, behaviors, and best practices.

**Critical**: The `agent-docs/` directory contains documentation specifically for LLM agents. These files must contain essential, accurate information to enable agents to write correct transformation and validation code.

## Features Requiring Documentation Updates

### 1. Parallel Transformation Enhancements (December 2025)

**Archived Change:** `2025-12-08-parallelize-transformation-execution`

Documentation updates needed:
- **Parallel threshold changed from 5000 to 1000** (default)
- **Two-phase staging approach** for thread-safe EMF access
- **Fail-fast error handling** with `TransformationException`
- **Deterministic element ordering** in parallel mode
- **Executor reuse** - executors can be reused across transformations
- **Builder pattern** for `TransformationExecutor`
- **New configuration options:** `parallelThreshold()`, `chunkSize()`

### 2. Resource Alias Support (December 2025)

**Archived Change:** `2026-01-01-add-resource-alias-support`

New features to document:
- **`@Transform` annotation** - Specify source types with aliases
- **`@To` annotation** - Specify target types with aliases
- **Multi-model transformations** - Access multiple registered ResourceSets
- **`ctx.registerResource(alias, resourceSet)`** - Register additional models
- **`ctx.all(alias, Class)`** - Get all elements of type from aliased resource
- **`ctx.create(Class)`** - Create element without containment (ETL-like)

### 3. Activity-Based Greedy Processing (January 2026)

**Archived Change:** `2026-01-01-add-activity-based-greedy`

New features to document:
- **`@ActivityBased` annotation** - Optional activity-based processing for `@Greedy @Lazy` rules
- **ETL compatibility mode** - `etlCompatibilityMode(true)` on executor
- **Activation tracking** - Only process elements referenced via `equivalent()`

### 4. Race Condition Fixes (January 2026)

**Archived Changes:**
- `2026-01-02-fix-eager-rule-race-condition`
- `2026-01-02-fix-parallel-parent-rule-race-condition`

Features to document:
- **Atomic `getOrCreate()` in cache** - Thread-safe rule execution
- **Per-key locking** - Fine-grained locks for (source, ruleName) pairs
- **Guard rejection caching** - Prevents redundant guard evaluation
- **Parent rule atomicity** - `executeParentRule()` and `@Extends` chains are now atomic

### 5. Parallel EMF Thread Safety (January 2026)

**Archived Change:** `2026-01-02-fix-parallel-emf-thread-safety`

Documentation updates:
- **Proxy unwrapping** - Automatic proxy cleanup before serialization
- **Deferred writes mode** - Safe element modification during transformation

## Documentation Files to Update

### README.md
- Update parallel threshold from 5000 to 1000
- Add resource alias example
- Add `@ActivityBased` to feature list
- Update `TransformationExecutor` builder examples
- Add `TransformationException` error handling example

### docs/transformation/architecture/parallel-execution.md
- **Critical:** Update threshold from 5000 to 1000
- Add two-phase staging diagram
- Add thread-safety guarantees table
- Add atomic cache operations section
- Add fail-fast error handling section
- Add deterministic ordering explanation

### docs/transformation/user-guide/transformation-rules.md
- Add `@Transform` and `@To` annotation documentation
- Add multi-model transformation example
- Add `ctx.create()` vs `ctx.createTarget()` explanation

### docs/transformation/user-guide/greedy-matching.md
- Add `@ActivityBased` annotation documentation
- Add ETL compatibility mode documentation
- Add activation tracking explanation

### docs/transformation/best-practices/performance.md
- Update parallel threshold guidance
- Add thread-safety guidelines for rules
- Add guidance on executor reuse

### docs/transformation/etl-comparison/overview.md
- Update with resource alias comparison
- Add `@ActivityBased` ETL parity notes

## Agent Documentation Updates (Critical for LLM Agents)

The `agent-docs/` directory is specifically designed for LLM coding agents. Each file must be:
- **Self-contained** - Agents load minimal context
- **Accurate** - Wrong information leads to incorrect code
- **Complete** - Missing info means agents can't use features
- **Concise** - Optimized for token efficiency

### agent-docs/INDEX.md
- Add decision tree entry for `@ActivityBased` usage
- Add entry for thread-safety patterns
- Add entry for error handling with `TransformationException`

### agent-docs/ANNOTATIONS.md
**Missing annotations to add:**
- `@ActivityBased` - Activity-based greedy processing
  - Usage: `@Greedy @Lazy @ActivityBased`
  - Only processes elements activated via `equivalent()`
  - For ETL compatibility

**Update existing:**
- `@Greedy` - Add note about `@ActivityBased` option

### agent-docs/CONTEXT-API.md
**Missing APIs to add:**
- `ElementResolutionCache.getOrCreate()` - Atomic cache operations
  - Thread-safe rule execution
  - Supplier-based lazy execution
  - Per-key locking
- Note about atomic `executeParentRule()` behavior

**Update existing:**
- Resource Management section is good but verify completeness

### agent-docs/EXECUTION.md
**Missing sections to add:**
- **Atomic Cache Operations** - `getOrCreate()` pattern
  - Why it's needed (race conditions)
  - How it works (per-key locking)
  - When supplier executes
- **Parent Rule Atomicity**
  - `executeParentRule()` uses `getOrCreate()` internally
  - `@Extends` chains are atomic
- **TransformationException**
  - Fields: `getFailedElement()`, `getRuleName()`, `getCause()`
  - Error context for debugging
- **Guard Rejection Caching**
  - Guards evaluated under lock
  - Rejections cached to avoid re-evaluation

### agent-docs/QUICK-REF.md
**Missing items to add:**
- `@ActivityBased` in Core Annotations table
- Thread-safe rule writing guidelines (brief)
- Error handling with `TransformationException`

### agent-docs/PATTERNS.md
**Missing patterns to add:**
- **Activity-Based Greedy Pattern**
  ```java
  @Greedy @Lazy @ActivityBased
  ```
  - When to use vs standard `@Greedy @Lazy`
  - ETL compatibility mode alternative
- **Thread-Safe Transformation Pattern**
  - Safe operations list
  - Unsafe operations to avoid
  - Example of safe rule

### agent-docs/TROUBLESHOOTING.md
**Missing items to add:**
- **Race condition symptoms** and how they're now fixed
- **TransformationException handling**
- **Parallel execution issues** diagnosis

## Scope Summary

| File | Updates Required | Priority |
|------|------------------|----------|
| README.md | Threshold, builder pattern, error handling, new annotations | High |
| docs/transformation/architecture/parallel-execution.md | Major rewrite | High |
| docs/transformation/user-guide/transformation-rules.md | @Transform, @To | Medium |
| docs/transformation/user-guide/greedy-matching.md | @ActivityBased | Medium |
| docs/transformation/best-practices/performance.md | Thread-safety | Medium |
| docs/transformation/etl-comparison/overview.md | Resource alias parity | Low |
| **agent-docs/INDEX.md** | Decision tree updates | High |
| **agent-docs/ANNOTATIONS.md** | @ActivityBased annotation | High |
| **agent-docs/CONTEXT-API.md** | getOrCreate(), atomicity | High |
| **agent-docs/EXECUTION.md** | Atomic ops, exceptions, caching | High |
| **agent-docs/QUICK-REF.md** | @ActivityBased, thread-safety | High |
| **agent-docs/PATTERNS.md** | Activity-based, thread-safe patterns | High |
| **agent-docs/TROUBLESHOOTING.md** | Race conditions, exceptions | Medium |

## Success Criteria

1. All documentation accurately reflects current API and behavior
2. Parallel threshold documented as 1000 (not 5000)
3. New annotations (@Transform, @To, @ActivityBased) are documented
4. Resource alias feature is documented with examples
5. Thread-safety guidelines are comprehensive
6. Error handling with TransformationException documented
7. Two-phase staging approach explained
8. **agent-docs/** are accurate and complete for LLM coding agents
9. All agent-docs code examples compile with current API
10. Agent decision tree includes new features

## Approach

1. Read each documentation file
2. Compare with current implementation and archived proposals
3. Update outdated information
4. Add new features documentation
5. Ensure consistency across all files
6. **Verify agent-docs code examples against actual API**
