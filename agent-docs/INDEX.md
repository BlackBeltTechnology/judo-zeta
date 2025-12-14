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

Task: Debug transformation issue
  → Read: TROUBLESHOOTING.md

Task: Understand execution order
  → Read: EXECUTION.md

Task: Work with resources/aliases
  → Read: CONTEXT-API.md (Resource Management)

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
