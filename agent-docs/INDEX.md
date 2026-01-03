# Zeta Agent Documentation

Quick-reference documentation for coding agents. Each file is self-contained for minimal context loading.

## Transformation Files

| File | When to Read | Size |
|------|--------------|------|
| `QUICK-REF.md` | Always start here - covers 90% of tasks | Small |
| `ANNOTATIONS.md` | Need annotation details | Small |
| `CONTEXT-API.md` | Working with TransformationContext | Medium |
| `EXECUTION.md` | Understanding execution flow, parallel, staging | Medium |
| `PATTERNS.md` | Complex patterns: inheritance, multi-source, lazy | Medium |
| `MIGRATION.md` | Migrating ETL to Zeta transformations | Medium |
| `TROUBLESHOOTING.md` | Debugging issues | Small |

## Validation Files

| File | When to Read | Size |
|------|--------------|------|
| `MIGRATION-EVL.md` | Migrating EVL to Zeta validations | Medium |

## Decision Tree

```
=== TRANSFORMATION ===

Task: Write new transformation rule
  → Read: QUICK-REF.md

Task: Add guard condition
  → Read: QUICK-REF.md (Guards section)

Task: Multi-source Cartesian product
  → Read: PATTERNS.md (Multi-Source section)

Task: Rule inheritance (@Extends, @Abstract)
  → Read: PATTERNS.md (Inheritance section)

Task: Lazy evaluation
  → Read: PATTERNS.md (Lazy Rules section)

Task: Migrate ETL rule to Zeta
  → Read: MIGRATION.md

Task: Convert EOL operations to Java
  → Read: MIGRATION.md (EOL to Java Collections)

Task: Set up dual-engine transformation testing
  → Read: MIGRATION.md (Testing Pattern)

Task: Convert helper method to @Lazy rule
  → Read: MIGRATION.md (Pattern 1: Helper Method → @Lazy Rule)

Task: Use rule-named equivalent() like ETL
  → Read: MIGRATION.md (Pattern 2: Rule-Named equivalent() Calls)

Task: Fix @Lazy rule with guard (ETL mismatch)
  → Read: MIGRATION.md (Pattern 4: @Lazy Rules Have NO Guards)

Task: Avoid fallback creation when equivalent() returns null
  → Read: MIGRATION.md (Pattern 5: No Fallback Creation)

Task: Use constants for rule names
  → Read: MIGRATION.md (Pattern 6: Use Constants for Rule Names)

Task: Debug transformation issue
  → Read: TROUBLESHOOTING.md

Task: Understand execution order
  → Read: EXECUTION.md

Task: Work with resources/aliases
  → Read: CONTEXT-API.md (Resource Management)

Task: ETL-compatible @Greedy @Lazy processing
  → Read: PATTERNS.md (Activity-Based Greedy)

Task: Handle TransformationException
  → Read: EXECUTION.md (Error Handling)

Task: Write thread-safe transformation rules
  → Read: PATTERNS.md (Thread-Safe Pattern)

Task: Fix duplicate elements in parallel mode
  → Read: TROUBLESHOOTING.md (Race Conditions)

Task: Fix NPE "preparedResult is null" in parallel mode
  → Read: TROUBLESHOOTING.md (Direct Factory Usage Issues)

Task: Fix "Duplicate key" IllegalStateException
  → Read: TROUBLESHOOTING.md (Duplicate Key Errors)

Task: Migrate direct factory usage to ctx.createTarget()
  → Read: PATTERNS.md (Migration from Direct EMF Factory Pattern)

Task: Make legacy transformation parallel-safe
  → Read: PATTERNS.md (Synchronized Helper Pattern)

Task: Understand safe vs unsafe parallel patterns
  → Read: QUICK-REF.md (Thread-Safe Rule Guidelines)

Task: Profile transformation performance
  → Read: QUICK-REF.md (Performance Profiling)

Task: Identify slow rules or bottlenecks
  → Read: QUICK-REF.md (Performance Profiling)

=== VALIDATION ===

Task: Migrate EVL constraint to Zeta
  → Read: MIGRATION-EVL.md

Task: Convert EVL critique to Zeta
  → Read: MIGRATION-EVL.md (Critique Pattern)

Task: Add @Satisfies dependency
  → Read: MIGRATION-EVL.md (Satisfies Dependency)

Task: Set up dual-engine validation testing
  → Read: MIGRATION-EVL.md (Dual-Engine Testing Pattern)

Task: Performance test validation
  → Read: MIGRATION-EVL.md (Performance Test Pattern)
```
