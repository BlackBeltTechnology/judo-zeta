# Design: Activity-Based Greedy Processing

## Architecture Overview

Activity-based processing introduces a two-phase execution model where `@Greedy @Lazy @ActivityBased` rules only process elements that were "activated" via `equivalent()` calls during the transformation.

## Current Architecture

### Eager Phase Execution Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    CURRENT @GREEDY BEHAVIOR                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  TransformationExecutor.transform()                             │
│  ┌──────────────────────────────────────────┐                   │
│  │ for each source in getAllContents():      │                   │
│  │   for each rule matching source type:     │                   │
│  │     if (!rule.isLazy() && rule.appliesTo) │                   │
│  │       executeRule(rule, source)           │ ◄── ALL elements  │
│  └──────────────────────────────────────────┘                   │
│                                                                  │
│  Result: Processes ALL 206 TransferObjectTypes                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### How ETL Differs

```
┌─────────────────────────────────────────────────────────────────┐
│                    EPSILON ETL BEHAVIOR                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  @greedy @lazy rules:                                           │
│  - NOT executed during initial eager phase                      │
│  - Only executed when equivalent() is called                    │
│  - equivalent() creates target on first reference               │
│                                                                  │
│  Result: Only 133 types processed (those referenced)            │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Proposed Architecture

### Two-Phase Execution Model

```
┌─────────────────────────────────────────────────────────────────┐
│                    ACTIVITY-BASED PROCESSING                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  PHASE 1: Standard Eager Execution + Activation Tracking        │
│  ┌──────────────────────────────────────────┐                   │
│  │ for each source in getAllContents():      │                   │
│  │   for each rule matching source type:     │                   │
│  │     if (rule.isActivityBased())           │                   │
│  │       skip  // defer to phase 2           │ ◄── Skip for now  │
│  │     else if (!rule.isLazy())              │                   │
│  │       executeRule(rule, source)           │                   │
│  │                                            │                   │
│  │ During equivalent() calls:                 │                   │
│  │   activationTracker.activate(source, rule)│ ◄── Track         │
│  └──────────────────────────────────────────┘                   │
│                                                                  │
│  PHASE 2: Activity-Based Greedy Execution                       │
│  ┌──────────────────────────────────────────┐                   │
│  │ for each activated (source, rule) pair:   │                   │
│  │   if (!alreadyProcessed(source, rule))    │                   │
│  │     executeRule(rule, source)             │ ◄── Only activated│
│  └──────────────────────────────────────────┘                   │
│                                                                  │
│  Result: Only 133 types processed (matches ETL)                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Component Design

### 1. ActivationTracker

Tracks which source elements have been "activated" for activity-based rules.

```java
public class ActivationTracker {
    // Map: ruleName -> Set of activated source elements
    private final Map<String, Set<EObject>> activations = new ConcurrentHashMap<>();

    /**
     * Record that a source element was activated for a rule.
     */
    public void activate(String ruleName, EObject source) {
        activations.computeIfAbsent(ruleName, k -> ConcurrentHashMap.newKeySet())
                   .add(source);
    }

    /**
     * Get all activated sources for a rule.
     */
    public Set<EObject> getActivated(String ruleName) {
        return activations.getOrDefault(ruleName, Collections.emptySet());
    }

    /**
     * Check if any elements were activated for a rule.
     */
    public boolean hasActivations(String ruleName) {
        Set<EObject> set = activations.get(ruleName);
        return set != null && !set.isEmpty();
    }
}
```

### 2. TransformationContext Changes

Track activations during `equivalent()` calls:

```java
public class TransformationContext {
    private ActivationTracker activationTracker;

    public <T extends EObject> T equivalent(EObject source, Class<T> targetType) {
        // Find matching rule
        TransformRuleDescriptor rule = findRule(source, targetType);

        // Track activation for activity-based rules
        if (rule.isActivityBased()) {
            activationTracker.activate(rule.getName(), source);
        }

        // Existing equivalent() logic...
        return executeEquivalent(rule, source);
    }
}
```

### 3. TransformationExecutor Changes

Two-phase execution for activity-based rules:

```java
public class TransformationExecutor {
    public void transform() {
        // Pre-hooks
        executePreTransformationHooks();

        // Phase 1: Standard eager execution (skip activity-based rules)
        executeEagerRules(skipActivityBased: true);

        // Phase 2: Activity-based greedy execution
        executeActivityBasedRules();

        // Post-hooks
        executePostTransformationHooks();
    }

    private void executeActivityBasedRules() {
        for (TransformRuleDescriptor rule : registry.getAllRules()) {
            if (!rule.isActivityBased()) continue;

            Set<EObject> activated = activationTracker.getActivated(rule.getName());
            for (EObject source : activated) {
                // Check if already processed
                if (context.getElementResolutionCache().getByRule(source, rule.getName()) != null) {
                    continue;
                }

                // Check guard (still applies)
                if (!rule.evaluateGuard(source, context)) continue;

                // Execute rule
                EObject target = rule.execute(source, context);
                if (target != null) {
                    context.getElementResolutionCache().addMapping(
                            source, rule.getName(), target, rule.isPrimary());
                }
            }
        }
    }
}
```

### 4. @ActivityBased Annotation

```java
package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.*;

/**
 * Marks a @Greedy @Lazy rule for activity-based processing.
 *
 * <p>Activity-based rules only process elements that are "activated"
 * via equivalent() calls during transformation. Elements that are never
 * referenced are never processed.</p>
 *
 * <p>This matches Epsilon ETL's implicit @greedy @lazy behavior where
 * only actively referenced elements are transformed.</p>
 *
 * <p><b>Requirements:</b> Must be used with both @Greedy and @Lazy annotations.
 * Using @ActivityBased alone has no effect.</p>
 *
 * @see Greedy
 * @see Lazy
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActivityBased {
}
```

## Execution Order

```
1. Pre-transformation hooks
2. Multi-source rules (Cartesian product)
3. Single-source eager rules (skip @ActivityBased)
4. Activity-based greedy rules (only activated elements)
5. Post-transformation hooks
```

## Thread Safety

- `ActivationTracker` uses `ConcurrentHashMap` for thread-safe activation tracking
- Activation happens during `equivalent()` which can be called from parallel rule execution
- Phase 2 execution can be parallelized across activated elements

## Memory Considerations

- `ActivationTracker` stores references to activated EObjects
- For large models, this could be significant memory usage
- Consider using weak references if memory pressure is a concern

## Edge Cases

### 1. Circular Activations

If rule A's execution calls `equivalent()` which activates rule B, and rule B calls `equivalent()` which activates rule A:

```
RuleA.execute() -> equivalent(x) -> activates RuleB
RuleB.execute() -> equivalent(y) -> activates RuleA
```

**Solution**: Activations are recorded but only unprocessed elements are executed in Phase 2. The cache prevents re-execution.

### 2. Late Activations

If an activity-based rule is activated during Phase 2 execution:

```
Phase 2 starts
ActivityBasedRuleA executes -> calls equivalent() -> activates ActivityBasedRuleB
```

**Solution**: Process activations in a loop until no new activations occur (fixpoint).

### 3. No Activations

If a rule has `@ActivityBased` but no `equivalent()` calls reference it:

**Solution**: Rule produces no targets (matches ETL behavior).

## Alternative Designs Considered

### A. Transformation-Level Setting

```java
TransformationExecutor.builder()
    .activityBasedGreedy(true)  // Apply to all @Greedy @Lazy rules
    .build();
```

**Pros**: Simpler, no annotation on each rule
**Cons**: All-or-nothing, less flexible

### B. Reverse Approach: @EagerGreedy

Make activity-based the default for `@Greedy @Lazy`, add `@EagerGreedy` to opt-in to current behavior:

```java
@Greedy
@Lazy
@EagerGreedy  // Process ALL elements (current behavior)
public TransformFunction<...> rule() { ... }
```

**Pros**: Matches ETL by default
**Cons**: Breaking change for existing transformations

### C. Execution Mode Enum

```java
@Greedy(mode = GreedyMode.ACTIVITY_BASED)
```

**Pros**: Single annotation
**Cons**: Mixing concerns in @Greedy annotation

## Final Design

**Both approaches are supported:**

### Per-Rule: @ActivityBased Annotation

```java
@Greedy
@Lazy
@ActivityBased  // All three annotations required
public TransformFunction<TransferObjectType, ClassType> classType() { ... }
```

### Per-Transformation: ETL Compatibility Mode

```java
TransformationExecutor.builder()
    .registry(registry)
    .context(context)
    .etlCompatibilityMode(true)  // Applies activity-based to ALL @Greedy @Lazy rules
    .build();
```

When `etlCompatibilityMode(true)` is set, the executor treats all `@Greedy @Lazy` rules as if they also had `@ActivityBased`.

### Why Both

1. **Per-rule `@ActivityBased`**: Fine-grained control, explicit intent, works for mixed strategies
2. **`etlCompatibilityMode(true)`**: Simple migration path for ETL ports, single setting enables full ETL behavior
3. **Explicit annotations**: `@ActivityBased` does NOT imply `@Lazy` - keeps annotations orthogonal
