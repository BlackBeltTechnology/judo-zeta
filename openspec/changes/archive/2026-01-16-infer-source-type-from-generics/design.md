# Design: Automatic Source Type Inference from Generic Parameters

## Context

The ZETA transformation framework evaluates rules against input elements using a two-level filtering system:
1. **Type-based filtering** (registration time): `rulesBySourceType` index groups rules by their declared source type
2. **Runtime filtering** (`appliesTo()`): Checks if a rule matches an element based on greedy/non-greedy semantics

Currently, source type is determined by priority:
1. `@Transform(type = X.class)` annotation (explicit)
2. `sourceTypes` attribute in `@TransformRule` (backward compat)
3. Default from `@TransformationContext(source = X.class)` (class-level default)

The problem: When rules don't specify explicit source types, they inherit the class-level default (often `EObject` or a broad type), causing them to be evaluated against ALL elements.

## Goals

- **Automatic optimization**: Infer source type from `TransformFunction<S, T>` generic parameter
- **Zero annotation changes**: Existing rules benefit without modification
- **Behavioral equivalence**: Inferred filtering MUST produce identical results to current `appliesTo()` checks
- **Handle greedy semantics**: Correctly filter by supertypes for `@Greedy` rules

## Non-Goals

- Changing the `@TransformRule` annotation (no new attributes)
- Changing `appliesTo()` semantics
- Changing execution order

## Technical Analysis

### Current Source Type Extraction

In `TransformationRegistry.registerRule()` (lines 174-216):

```java
// Priority: @Transform annotations > sourceTypes attribute > default from @TransformationContext
if (!transforms.isEmpty()) {
    sourceType = transforms.get(0).getType();
} else if (sourceTypes.length > 0) {
    sourceType = sourceTypes[0];
    // Convert sourceTypes to TransformDefinitions with default alias
    for (Class<? extends EObject> st : sourceTypes) {
        transforms.add(new TransformDefinition("source", st));
    }
} else {
    sourceType = defaultSourceType;  // ← FALLS BACK TO BROAD TYPE
    transforms.add(new TransformDefinition("source", defaultSourceType));
}
```

### Existing Target Type Extraction

The target type is already extracted from generics (lines 497-547):

```java
private Class<? extends EObject> extractTargetTypeFromReturnType(Method ruleMethod) {
    Type returnType = ruleMethod.getGenericReturnType();

    if (returnType instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) returnType;
        Type[] typeArgs = parameterizedType.getActualTypeArguments();

        // TransformFunction has 2 type parameters: <SourceType, TargetType>
        // We want the second one (index 1)
        if (typeArgs.length >= 2) {
            Type targetTypeArg = typeArgs[1];
            // ... extract class from type argument
        }
    }
    return null;
}
```

### Proposed: Source Type Extraction

Add parallel method for source type (first generic parameter):

```java
@SuppressWarnings("unchecked")
private Class<? extends EObject> extractSourceTypeFromReturnType(Method ruleMethod) {
    Type returnType = ruleMethod.getGenericReturnType();

    if (returnType instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) returnType;
        Type[] typeArgs = parameterizedType.getActualTypeArguments();

        // TransformFunction has 2 type parameters: <SourceType, TargetType>
        // We want the first one (index 0)
        if (typeArgs.length >= 1) {
            Type sourceTypeArg = typeArgs[0];

            // Handle direct class reference
            if (sourceTypeArg instanceof Class) {
                Class<?> sourceClass = (Class<?>) sourceTypeArg;
                if (EObject.class.isAssignableFrom(sourceClass)) {
                    return (Class<? extends EObject>) sourceClass;
                }
            }

            // Handle parameterized types
            if (sourceTypeArg instanceof ParameterizedType) {
                Type rawType = ((ParameterizedType) sourceTypeArg).getRawType();
                if (rawType instanceof Class && EObject.class.isAssignableFrom((Class<?>) rawType)) {
                    return (Class<? extends EObject>) rawType;
                }
            }
        }
    }

    return null;
}
```

### Integration Point

Modify `registerRule()` to use inferred source type as fallback:

```java
// Priority: @Transform annotations > sourceTypes attribute > GENERIC INFERENCE > default
if (!transforms.isEmpty()) {
    sourceType = transforms.get(0).getType();
} else if (sourceTypes.length > 0) {
    sourceType = sourceTypes[0];
    for (Class<? extends EObject> st : sourceTypes) {
        transforms.add(new TransformDefinition("source", st));
    }
} else {
    // NEW: Try to infer from generic parameter
    Class<? extends EObject> inferredSourceType = extractSourceTypeFromReturnType(ruleMethod);
    if (inferredSourceType != null && !inferredSourceType.equals(EObject.class)) {
        sourceType = inferredSourceType;
        transforms.add(new TransformDefinition("source", inferredSourceType));
        log.debug("Inferred source type from method signature: {} for rule: {}",
                inferredSourceType.getSimpleName(), name);
    } else {
        // Fall back to default
        sourceType = defaultSourceType;
        transforms.add(new TransformDefinition("source", defaultSourceType));
    }
}
```

## Critical: Greedy Rule Semantics

For **@Greedy** rules, the type-based filtering MUST include all subtypes:

```java
// In computeRulesForSource():
for (Map.Entry<Class<? extends EObject>, List<TransformRuleDescriptor>> entry : rulesBySourceType.entrySet()) {
    Class<? extends EObject> ruleSourceType = entry.getKey();
    // For greedy rules: if rule's sourceType is supertype of element's type, include
    if (ruleSourceType.isAssignableFrom(sourceType) && !ruleSourceType.equals(sourceType)) {
        result.addAll(entry.getValue());
    }
}
```

This already handles greedy semantics correctly. The key insight:
- Rule registered with `sourceType = NamedElement` (supertype)
- Element has runtime type `EntityType` (subtype)
- `NamedElement.isAssignableFrom(EntityType)` = true
- Rule is included in `getRulesForSource(EntityType)`
- Runtime `appliesTo()` with `@Greedy`: `NamedElement.isInstance(entityTypeInstance)` = true

## Validation Strategy

### Test 1: Behavioral Equivalence

```java
@Test
void inferredTypeFilteringMatchesAppliesToBehavior() {
    // Create elements of various types
    List<EObject> elements = createMixedTypeElements();

    // For each rule
    for (TransformRuleDescriptor rule : registry.getAllRules()) {
        // Count elements that pass appliesTo() (current behavior)
        long appliesToCount = elements.stream()
            .filter(rule::appliesTo)
            .count();

        // Count elements that pass type filter (new behavior)
        long filteredCount = elements.stream()
            .filter(e -> registry.getRulesForSource(e.getClass()).contains(rule))
            .count();

        // MUST be identical
        assertEquals(appliesToCount, filteredCount,
            "Type filtering must match appliesTo() for rule: " + rule.getName());
    }
}
```

### Test 2: Generic Type Extraction

```java
@Test
void extractsSourceTypeFromGenericParameter() {
    // Rule: TransformFunction<EntityType, EClass>
    registry.register(TypedTransformation.class);

    TransformRuleDescriptor rule = registry.getRuleByName("TypedRule");

    // Source type should be inferred from generic parameter
    assertEquals(EntityType.class, rule.getSourceType());
}

@TransformationContext(source = EObject.class, target = EObject.class)
public static class TypedTransformation {
    @TransformRule(name = "TypedRule")
    public TransformFunction<EntityType, EClass> typedRule() {
        return (source, ctx) -> ctx.createTarget(EClass.class);
    }
}
```

### Test 3: Greedy Subtype Matching

```java
@Test
void greedyRuleWithInferredSupertypeMatchesSubtypes() {
    // Rule declared for NamedElement (supertype)
    registry.register(GreedySuperTypeTransformation.class);

    // Element is EntityType (subtype of NamedElement)
    EntityType entity = createEntityType("TestEntity");

    // Should be included in filtered rules
    List<TransformRuleDescriptor> rules = registry.getEagerRulesForType(entity.getClass());
    assertTrue(rules.stream().anyMatch(r -> r.getName().equals("GreedyNamedElement")));
}
```

### Test 4: Explicit Type Takes Priority

```java
@Test
void explicitTransformAnnotationTakesPriority() {
    registry.register(ExplicitTypeTransformation.class);

    TransformRuleDescriptor rule = registry.getRuleByName("ExplicitRule");

    // @Transform type should override inferred type
    assertEquals(SpecificType.class, rule.getSourceType());
}

@TransformationContext(source = EObject.class, target = EObject.class)
public static class ExplicitTypeTransformation {
    @TransformRule(name = "ExplicitRule")
    @Transform(type = SpecificType.class)  // Explicit takes priority
    public TransformFunction<EObject, EClass> explicitRule() {  // Generic says EObject
        return (source, ctx) -> ctx.createTarget(EClass.class);
    }
}
```

## Performance Validation

```java
@Test
void guardEvaluationsReducedWithTypeFiltering() {
    // Setup: Many types, many rules
    AtomicLong guardEvaluations = new AtomicLong();

    // Track guard evaluations
    TransformationMetrics.enable();

    // Execute transformation
    executor.transform();

    // Verify reduction
    long actualEvaluations = TransformationMetrics.getGuardEvaluationCount();
    long maxExpected = elements.size() * 2;  // Allow some overhead

    assertTrue(actualEvaluations < maxExpected,
        "Guard evaluations should be ~N (matched rules) not N*R (all rules). " +
        "Actual: " + actualEvaluations + ", Max expected: " + maxExpected);
}
```

## Risks and Mitigations

### Risk: Type Erasure at Runtime

**Concern**: Generic types are erased at runtime.

**Mitigation**: We extract at registration time using reflection on the Method object:
```java
Type returnType = ruleMethod.getGenericReturnType();  // Preserves generic info
```

Method signatures retain generic type information even after erasure.

### Risk: Wildcard or Bounded Types

**Concern**: Rules might use `TransformFunction<? extends NamedElement, T>`.

**Mitigation**: Handle bounded types:
```java
if (sourceTypeArg instanceof WildcardType) {
    Type[] upperBounds = ((WildcardType) sourceTypeArg).getUpperBounds();
    if (upperBounds.length > 0 && upperBounds[0] instanceof Class) {
        return (Class<? extends EObject>) upperBounds[0];
    }
}
```

### Risk: Breaking Existing Behavior

**Concern**: Inferred type might exclude elements that currently pass.

**Mitigation**:
1. Comprehensive tests verify behavioral equivalence
2. Fall back to default if inference fails
3. Explicit annotations always take priority

## Open Questions

None - the approach follows the existing pattern for target type extraction.

## Decision Summary

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| Extraction point | First generic parameter of `TransformFunction<S, T>` | Direct, explicit source type |
| Priority | After explicit annotations, before default | Backward compatible |
| Fallback | Class-level `@TransformationContext` default | Maintains current behavior |
| Validation | Behavioral equivalence tests | Proves no semantic changes |
