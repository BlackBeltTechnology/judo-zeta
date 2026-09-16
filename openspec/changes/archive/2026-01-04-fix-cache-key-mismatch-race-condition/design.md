# Design: Fix Cache Key Mismatch Race Condition

## Problem Analysis

### Current Cache Structure

```
ElementResolutionCache
├── ruleCache: Map<EObject, Map<String, EObject>>
│   └── source → ruleName → target
│   └── Accessed by: getByRule(source, ruleName)
│
├── typeCache: Map<EObject, Map<String, List<EObject>>>
│   └── source → targetTypeName → [targets]
│   └── Accessed by: getEquivalent(source, targetType)
│
└── primaryCache: Map<EObject, Map<String, EObject>>
    └── source → targetTypeName → primaryTarget
    └── Accessed by: getEquivalent(source, targetType)
```

### The Original Problem

When multiple rules produce `Model.class` (e.g., "BaseModel" registered first, "Model" @Primary registered second):

```
equivalent(source, Model.class):
  → Iterates rules in registration order
  → Finds "BaseModel" first
  → Uses lock key = (source, "BaseModel")

executeParentRule("Model", source):
  → Uses explicit rule name
  → Uses lock key = (source, "Model")

RESULT: Different lock keys = race condition = duplicates!
```

## Chosen Approach: Semantic Lock Key Assignment

**Principle:** The lock key should reflect the caller's intent:
- `equivalent(source, Type.class)` = "Give me THE equivalent for this type" → use @Primary
- `executeParentRule("RuleName", source)` = "Execute THIS specific rule" → use explicit name

### Semantics

| Method | Lock Key | Rationale |
|--------|----------|-----------|
| `equivalent(source, TargetType.class)` | @Primary rule name | Type-based lookup should consistently use @Primary |
| `executeParentRule("RuleName", source)` | Explicit rule name | Caller explicitly requested this specific rule |
| `equivalentDiscriminated(source, type, "RuleName", disc)` | Explicit rule name | Caller explicitly requested this specific rule |

### Implementation

#### 1. TransformationRegistry: Add @Primary Lookup

```java
// New field
private final ConcurrentHashMap<Class<?>, TransformRuleDescriptor> primaryRuleByTargetTypeCache =
        new ConcurrentHashMap<>();

// In registerRule() - populate cache for @Primary rules
if (isPrimary) {
    primaryRuleByTargetTypeCache.putIfAbsent(targetType, descriptor);
}

// New method
public TransformRuleDescriptor getPrimaryRuleForTargetType(Class<?> targetType) {
    return primaryRuleByTargetTypeCache.get(targetType);
}
```

#### 2. equivalent(): Use @Primary for Lock Key

```java
// In equivalent() - use @Primary rule's name for lock key
String canonicalRuleName = rule.getName();
TransformRuleDescriptor primaryRule = transformationRegistry.getPrimaryRuleForTargetType(targetType);
if (primaryRule != null && primaryRule.appliesTo(source)) {
    canonicalRuleName = primaryRule.getName();
}
RuleCacheKey key = new RuleCacheKey(source, canonicalRuleName);
```

#### 3. executeParentRule(): Use Explicit Name (No Change)

```java
// executeParentRule() uses the explicit rule name - no normalization
// The caller explicitly requested this specific rule
RuleCacheKey key = new RuleCacheKey(source, parentRuleName);
```

#### 4. equivalentDiscriminated(): Use Explicit Name with Proper Locking

```java
// equivalentDiscriminated() uses explicit rule name (same as executeParentRule)
// But now uses RuleCacheKey and ruleLocks for thread-safe execution
RuleCacheKey key = new RuleCacheKey(source, ruleName);
ReentrantLock lock = ruleLocks.computeIfAbsent(key, k -> new ReentrantLock());
```

### Lock Key Summary

```
equivalent(source, Model.class):
  → @Primary rule for Model.class is "ModelA"
  → lock key = (source, "ModelA")

executeParentRule("ModelA", source):
  → Explicit name "ModelA"
  → lock key = (source, "ModelA")
  → SAME LOCK as equivalent() ✓

executeParentRule("ModelB", source):
  → Explicit name "ModelB"
  → lock key = (source, "ModelB")
  → DIFFERENT LOCK (intentional - different rule requested)

equivalentDiscriminated(source, Model.class, "ModelA", "disc"):
  → Explicit name "ModelA"
  → lock key = (source, "ModelA")
  → SAME LOCK as executeParentRule("ModelA") ✓
```

### Why This Design

1. **Consistent type-based lookups**: All `equivalent(source, Model.class)` calls use the same lock key regardless of rule iteration order

2. **Explicit rule execution preserved**: When caller explicitly requests a rule by name, they get that rule (not silently redirected to @Primary)

3. **No semantic change for single-rule cases**: If only one rule produces a type, behavior is unchanged

4. **Backward compatible**: Existing code using explicit rule names continues to work

## Test Strategy

1. **Test 0.2.1-0.2.2**: `equivalent()` + `executeParentRule()` with same @Primary → share lock, no duplicates

2. **Test 0.2.3**: `equivalent()` + `executeParentRule("ModelB")` + `executeParentRule("ModelC")`:
   - `equivalent()` uses @Primary ("ModelA") - one execution
   - `executeParentRule("ModelB")` - one execution (different rule)
   - `executeParentRule("ModelC")` - one execution (different rule)
   - Total: 3 executions (intentional - 3 different rules requested)

3. **Test 0.5.1-0.5.2**: `equivalentDiscriminated()` + `executeParentRule()` same rule name → share lock
