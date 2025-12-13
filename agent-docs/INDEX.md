# Zeta Transformation Agent Documentation

Quick-reference documentation for coding agents. Each file is self-contained for minimal context loading.

## Files

| File | When to Read | Size |
|------|--------------|------|
| `QUICK-REF.md` | Always start here - covers 90% of tasks | Small |
| `ANNOTATIONS.md` | Need annotation details | Small |
| `CONTEXT-API.md` | Working with TransformationContext | Medium |
| `EXECUTION.md` | Understanding execution flow, parallel, staging | Medium |
| `PATTERNS.md` | Complex patterns: inheritance, multi-source, lazy | Medium |
| `TROUBLESHOOTING.md` | Debugging issues | Small |

## Decision Tree

```
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

Task: Debug transformation issue
  → Read: TROUBLESHOOTING.md

Task: Understand execution order
  → Read: EXECUTION.md

Task: Work with resources/aliases
  → Read: CONTEXT-API.md (Resource Management)
```
