# Annotations Reference

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > Annotations

Complete reference for all transformation annotations.

## @TransformationContext

Marks a class as containing transformation rules.

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations { ... }
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `source` | `Class<?>` | Yes | Primary source element type |
| `target` | `Class<?>` | Yes | Primary target element type |

## @TransformRule

Defines a transformation rule.

```java
@TransformRule(name = "EntityType2Table", description = "Transforms entity to table")
public TransformFunction<EntityType, Table> entityType2Table() { ... }
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `name` | `String` | Yes | - | Unique rule identifier |
| `description` | `String` | No | `""` | Human-readable description |

## @Lazy

Rule executes on-demand via `equivalent()` calls, not during the eager transformation phase.

```java
@TransformRule(name = "Reference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> reference2ForeignKey() { ... }
```

No parameters.

**Epsilon ETL Comparison**: Both frameworks use `@lazy`/`@Lazy` identically - rules are excluded from the main transformation pass and only execute when explicitly requested via `equivalent()`.

## @Abstract

Rule only executes via `executeParentRule()`.

```java
@TransformRule(name = "BaseNamedElement")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement() { ... }
```

No parameters.

## @Primary

Rule's result takes precedence in `equivalent()`.

```java
@TransformRule(name = "Entity2MainTable")
@Primary
public TransformFunction<EntityType, Table> entity2MainTable() { ... }
```

No parameters.

## @Greedy

Matches source type AND all subtypes (kind-of semantics vs type-of semantics).

```java
@TransformRule(name = "NamedElement2NamedType")
@Greedy
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() { ... }
```

No parameters.

**Epsilon ETL Comparison**: Both frameworks use `@greedy`/`@Greedy` for the same purpose - enabling kind-of type matching instead of exact type-of matching. This allows a rule declared for `NamedElement` to also match `EntityType`, `ActorType`, and other subtypes.

> **Note**: `@Greedy` relates **only to type matching** in inheritance hierarchies. It does NOT affect whether elements are processed eagerly or lazily - that is controlled by `@Lazy`. A rule can be both `@Greedy` (match subtypes) and `@Lazy` (on-demand execution).

## @ActivityBased

Enables activity-based processing for `@Greedy @Lazy` rules. Only elements explicitly referenced via `equivalent()` calls are processed.

```java
@TransformRule(name = "ClassType")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<ClassType, TransferObjectType> classType() { ... }
```

No parameters.

**Must be used with**: `@Greedy` and `@Lazy` (a warning is logged if used without both).

### How It Works

Activity-based processing uses a two-phase execution model:

1. **Phase 1 (Eager)**: Non-activity-based rules execute. When they call `equivalent()` for an activity-based rule, the element is recorded as "activated" but execution is deferred.

2. **Phase 2 (Activity-Based)**: After Phase 1 completes, activity-based rules execute only for activated elements. A fixpoint loop handles late activations (when one activity-based rule activates another).

### Example

```java
// Eager rule that activates specific ClassTypes
@TransformRule(name = "EntityType")
public TransformFunction<EntityType, Table> entityType() {
    return (source, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        // Only referenced ClassTypes will be processed
        for (ClassType ct : source.getReferencedClassTypes()) {
            ctx.equivalent(ct, "ClassType");  // Records activation
        }
        return table;
    };
}

// Activity-based rule - only processes activated elements
@TransformRule(name = "ClassType")
@Greedy
@Lazy
@ActivityBased
public TransformFunction<ClassType, TransferObjectType> classType() {
    return (source, ctx) -> {
        // Only executes for ClassTypes referenced above
        return ctx.createTarget(TransferObjectType.class);
    };
}
```

### ETL Compatibility Mode

For migrating from Epsilon ETL, use `etlCompatibilityMode(true)` on the executor to treat ALL `@Greedy @Lazy` rules as activity-based without adding `@ActivityBased` annotations:

```java
TransformationExecutor executor = TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // All @Greedy @Lazy rules become activity-based
    .build();
```

**Epsilon ETL Comparison**: In ETL, `@greedy @lazy` rules implicitly use activity-based semantics - they only process elements referenced via `equivalent()`. Zeta makes this behavior explicit with `@ActivityBased` or opt-in via `etlCompatibilityMode`.

## @Extends

Inherits from parent rule(s).

```java
@TransformRule(name = "EntityType2Table")
@Extends("BaseNamedElement")
public TransformFunction<EntityType, Table> entityType2Table() { ... }
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `value` | `String` | Yes | Parent rule name |

## @Guard

Conditional execution based on guard method.

```java
@TransformRule(name = "AbstractEntity2Table")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2Table() { ... }

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | `String` | Yes | Guard method name |

**Guard method signatures**:
- `boolean methodName(EObject element)`
- `boolean methodName(EObject element, TransformationContext ctx)`

## @PreExecution

Method runs before transformation starts.

```java
@PreExecution
public void setup(TransformationContext ctx) { ... }
```

No parameters.

## @PostExecution

Method runs after transformation completes.

```java
@PostExecution
public void cleanup(TransformationContext ctx) { ... }
```

No parameters.

## Annotation Combinations

| Combination | Valid | Notes |
|-------------|-------|-------|
| @Lazy + @Guard | Yes | Guard evaluated when equivalent() called |
| @Lazy + @Primary | Yes | Primary among lazy rules |
| @Abstract + @Extends | Yes | Abstract rule can extend another |
| @Greedy + @Guard | Yes | Guard checked for each matched subtype |
| @Greedy + @Lazy + @ActivityBased | Yes | Activity-based processing (recommended) |
| @Greedy + @Lazy | Yes | Standard lazy matching; use etlCompatibilityMode for activity-based behavior |
| @ActivityBased (alone) | No | Requires @Greedy and @Lazy |
| @Abstract + @Lazy | No | Abstract rules are never lazy |
| @Primary + @Abstract | No | Abstract rules don't produce direct results |

---

**Next**: [TransformationContext API](transformation-context.md)
