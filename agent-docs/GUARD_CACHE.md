Now I have a complete picture. Here's what I found:

## Guard Caching Behavior in Zeta

**Yes, guard evaluation results ARE cached by source instance** - but with an important nuance: it's a **rejection cache**, not a positive result cache.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Guard Evaluation Flow                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  evaluateGuard(source, context)                                         │
│          │                                                              │
│          ▼                                                              │
│  ┌───────────────────┐                                                  │
│  │ rejected.contains │──────▶ TRUE ──────▶ return false (cached)       │
│  │    (source)?      │                   (no re-evaluation)            │
│  └─────────┬─────────┘                                                  │
│            │ FALSE                                                      │
│            ▼                                                             │
│  ┌───────────────────┐                                                  │
│  │ guard.evaluate()  │──────▶ FALSE ────▶ rejected.add(source)         │
│  │  (actual check)   │                   return false                   │
│  └─────────┬─────────┘                                                  │
│            │ TRUE                                                       │
│            ▼                                                             │
│      return true                                                        │
│    (NOT cached!)                                                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Points

| Aspect | Behavior |
|--------|----------|
| **Rejection caching** | ✅ Per `(rule, source)` pair - stored in `TransformRuleDescriptor.rejected` |
| **Positive result caching** | ❌ NOT cached - guard re-evaluates on subsequent calls if it passed before |
| **Scope** | Per-rule - each rule has its own `ConcurrentHashMap.newKeySet()` |
| **Thread-safe** | ✅ Yes - uses `ConcurrentHashMap` |
| **ETL-compatible** | ✅ Matches Epsilon ETL's `TransformationRule.rejected` behavior |

### Why Only Rejection Caching?

This matches **ETL semantics**: once a guard rejects a source element for a rule, it will never pass in the same transformation run. But if a guard passes, the source may still need to be re-evaluated (e.g., for greedy passes or inheritance chains).

### Code Reference

From `TransformRuleDescriptor.java:457-474`:
```java
public boolean evaluateGuard(EObject source, TransformationContext context) {
    // ETL-compatible: Check rejection cache first
    if (rejected.contains(source)) {
        return false;  // Cached rejection
    }

    TransformGuard guard = getGuard();
    if (guard == null) {
        return true;
    }

    boolean result = guard.evaluate(source, context);
    if (!result) {
        // Record rejection for future calls (ETL-compatible caching)
        rejected.add(source);
    }
    return result;
}
```

### What About the Guard Lambda Itself?

The `TransformGuard` lambda returned by the guard method IS cached (per rule, not per source):

```java
// From TransformRuleDescriptor.java:609-635
public TransformGuard getGuard() {
    if (guardMethod == null) return null;
    
    if (cachedGuard == null) {
        // New style: invoke ONCE to get the lambda, then cache it
        cachedGuard = (TransformGuard) guardMethod.invoke(instance);
    }
    return cachedGuard;
}
```

So there are **two levels of caching**:
1. **Lambda caching** - The guard function itself is cached per rule
2. **Rejection caching** - Failed guard evaluations are cached per (rule, source)

So currently there's **no guard-method-level cache**. Here's the current vs proposed architecture:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      CURRENT STATE                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Rule A ───┐                                                               │
│             │     ┌─────────────────────┐                                   │
│             ├────▶│ Rule A's rejected   │                                   │
│             │     │ Set<EObject>        │                                   │
│   Source X ─┤     └─────────────────────┘                                   │
│             │                                                               │
│             │     ┌─────────────────────┐                                   │
│             └────▶│ Rule B's rejected   │                                   │
│   Rule B ───┘     │ Set<EObject>        │                                   │
│                   └─────────────────────┘                                   │
│                                                                             │
│   Problem: Same guard method "isNotAbstract()" called TWICE                │
│   for same source X (once per rule)                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                      PROPOSED: Guard Method Cache                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌───────────────────────────────────────────────────────────────────┐     │
│   │            Guard Method Cache (shared across rules)               │     │
│   │                                                                   │     │
│   │   Key: (guardMethod, source)                                      │     │
│   │   Value: Boolean result                                           │     │
│   │                                                                   │     │
│   │   ┌──────────────────────────┬───────────────────────────────┐   │     │
│   │   │ isNotAbstract + Person1  │ → false                        │   │     │
│   │   ├──────────────────────────┼───────────────────────────────┤   │     │
│   │   │ isNotAbstract + Person2  │ → true                         │   │     │
│   │   ├──────────────────────────┼───────────────────────────────┤   │     │
│   │   │ hasContainer + Order1    │ → true                         │   │     │
│   │   └──────────────────────────┴───────────────────────────────┘   │     │
│   │                                                                   │     │
│   └───────────────────────────────────────────────────────────────────┘     │
│                                                                             │
│   Rule A (uses @Guard(method="isNotAbstract"))                              │
│      └──▶ Check Rule A's rejected → MISS                                    │
│      └──▶ Check Guard Method Cache → HIT (returns cached result)            │
│                                                                             │
│   Rule B (also uses @Guard(method="isNotAbstract"))                         │
│      └──▶ Check Rule B's rejected → MISS                                    │
│      └──▶ Check Guard Method Cache → HIT (same cached result)               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Questions for This Design

| Question | Consideration |
|----------|---------------|
| **Where to store?** | `TransformationContext` (shared across all rules) |
| **Cache key** | `(Method guardMethod, EObject source)` or `(String guardId, EObject)` |
| **Thread-safety** | `ConcurrentHashMap` required |
| **Cache invalidation** | Clear at transformation end (same as rejection cache) |
| **What about multi-source guards?** | Key would be `(Method, EObject[])` - more complex |

### Potential Implementation Location

```java
// In TransformationContext or a new GuardResultCache class
private final Map<Method, Map<EObject, Boolean>> guardResultCache = new ConcurrentHashMap<>();

public Boolean getCachedGuardResult(Method guardMethod, EObject source) {
    return guardResultCache
        .computeIfAbsent(guardMethod, m -> new ConcurrentHashMap<>())
        .get(source);
}

public void cacheGuardResult(Method guardMethod, EObject source, boolean result) {
    guardResultCache
        .computeIfAbsent(guardMethod, m -> new ConcurrentHashMap<>())
        .put(source, result);
}
```

### Tradeoffs

| Pro | Con |
|-----|-----|
| Avoids redundant guard evaluations | Adds complexity |
| Better performance when many rules share guards | Memory overhead for cache |
| ETL-compatible (guards are deterministic) | Must handle guard method identity correctly |

---

**Is this what you're thinking about?** Would you like to:
1. Explore implementing this in the codebase?
2. Discuss edge cases (multi-source guards, inheritance chains)?
3. Check if there are existing tests that would need updates?














Let's explore the edge cases for a guard method cache:

## Edge Case 1: Multi-Source Guards

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Multi-Source Guard Caching Challenge                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   @TransformRule(name = "OrderItemRule")                                    │
│   @Transform(type1 = Order.class, type2 = LineItem.class)                  │
│   @Guard(method = "isValidPair")                                            │
│   public TransformFunction<Order, LineItem, OrderItemTO> rule() {...}       │
│                                                                             │
│   public boolean isValidPair(Order order, LineItem item, Context ctx) {    │
│       return order.getItems().contains(item);  // Depends on COMBINATION   │
│   }                                                                         │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │ Problem: Same Order + different LineItems = different results       │   │
│   │                                                                     │   │
│   │   Order1 + LineItemA → TRUE   (contains)                            │   │
│   │   Order1 + LineItemB → TRUE   (contains)                            │   │
│   │   Order1 + LineItemX → FALSE  (NOT contains)                        │   │
│   │                                                                     │   │
│   │   Can't cache by just (guardMethod, Order1)                         │   │
│   │   Must cache by (guardMethod, [Order1, LineItemA])                  │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Current behavior** (from `TransformRuleDescriptor.java:490-520`):
```java
public boolean evaluateGuard(EObject[] sources, TransformationContext context) {
    // Multi-source guards: NO caching because guard decision depends on
    // the combination of all sources, not just the first element.
    // Same source[0] can pass with one combination and fail with another.
    MultiSourceTransformGuard guard = getMultiSourceGuard();
    return guard.evaluate(sources, context);  // NO CACHE
}
```

**Solution options:**

| Option | Key | Tradeoff |
|--------|-----|----------|
| A: No caching | N/A | Current behavior - safe but slower |
| B: Tuple key | `(Method, EObject[])` | Array equality issues, memory overhead |
| C: Composite key | `(Method, source1, source2, ...)` | Complex, but correct |

---

## Edge Case 2: Inheritance Chains with `@Extends`

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Guard Caching with Rule Inheritance                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   @TransformRule(name = "BaseRule")                                         │
│   @Abstract                                                                 │
│   @Guard(method = "isValid")         ──────────────────────────┐            │
│   public TransformFunction<Entity, TO> base() {...}                   │      │
│                                                                │            │
│   @TransformRule(name = "ChildRule")                                   │    │
│   @Extends("BaseRule")                                          ▼    │    │
│   @Guard(method = "isNotAbstract")  ◀── Additional guard       │    │    │
│   public TransformFunction<Entity, TO> child() {...}                   │    │
│                                                                │            │
│   ┌─────────────────────────────────────────────────────────────────────┐  │
│   │ Execution order:                                                     │  │
│   │                                                                     │  │
│   │   1. BaseRule.guard (isValid)                                       │  │
│   │   2. ChildRule.guard (isNotAbstract)                                │  │
│   │   3. If BOTH pass → execute child rule                              │  │
│   │                                                                     │  │
│   │ Question: If BaseRule.isValid is cached for EntityX,               │  │
│   │           does ChildRule reuse it?                                  │  │
│   └─────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Current behavior** (from `TransformRuleDescriptor.java:727-741`):
```java
// ETL Semantics: ALL parent guards must pass before child executes
for (String parentRuleName : extendsRules) {
    TransformRuleDescriptor parentRule = registry.getRuleByName(parentRuleName);
    if (parentRule != null && !parentRule.evaluateGuard(source, context)) {
        return null;  // Parent guard rejected
    }
}
```

**Guard method cache would help here:**
```
If BaseRule and ChildRule both use @Guard(method="isNotAbstract"):
  → Same guard method, same source → cache HIT
```

---

## Edge Case 3: Guard Method Identity

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Guard Method Identity Problem                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   @TransformationContext                                                    │
│   class TransformationA {                                                   │
│       @Guard(method = "isNotAbstract")                                      │
│       public boolean isNotAbstract(EObject e) {                        │   │
│           return !((EClass) e).isAbstract();                            │   │
│       }                                                                     │
│   }                                                                         │
│                                                                             │
│   @TransformationContext                                                    │
│   class TransformationB {                                                   │
│       @Guard(method = "isNotAbstract")   // Same NAME, different class │   │
│       public boolean isNotAbstract(EObject e) {                        │   │
│           return !((EClass) e).isAbstract();  // Same logic            │   │
│       }                                                                     │
│   }                                                                         │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐  │
│   │ Are these the SAME guard?                                           │  │
│   │                                                                     │  │
│   │   Option A: Key by Method object → Different (different classes)   │  │
│   │   Option B: Key by (Class, methodName) → Different                  │  │
│   │   Option C: Key by methodName only → Same (risky!)                  │  │
│   │                                                                     │  │
│   │   Recommendation: Option A (Method object) - safest                 │  │
│   └─────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Edge Case 4: Context-Dependent Guards

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              Context-Dependent Guard Caching                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   public boolean shouldTransform(EObject source, Context ctx) {            │
│       // Guard result depends on CONTEXT STATE, not just source            │
│       return ctx.getTransformationRegistry().isFeatureEnabled("X");        │
│   }                                                                         │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐  │
│   │ Problem: Same source, different context state = different result    │  │
│   │                                                                     │  │
│   │   Transformation 1: feature X enabled → guard passes               │  │
│   │   Transformation 2: feature X disabled → guard fails               │  │
│   │                                                                     │  │
│   │   BUT: Within SAME transformation, context is stable               │  │
│   │   → Cache is safe within one transformation run                    │  │
│   └─────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│   Solution: Clear guard cache at transformation start (like rejection)     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Summary: Proposed Cache Design

```java
/**
 * Guard result cache - shared across all rules within a transformation.
 * Key: (guardMethod, source) for single-source guards
 * Key: (guardMethod, sourceTuple) for multi-source guards
 */
public class GuardResultCache {
    
    // Single-source: Method × EObject → Boolean
    private final ConcurrentHashMap<Method, ConcurrentHashMap<EObject, Boolean>> singleSourceCache;
    
    // Multi-source: Method × TupleKey → Boolean  
    private final ConcurrentHashMap<Method, ConcurrentHashMap<String, Boolean>> multiSourceCache;
    
    public Boolean getSingleSourceResult(Method guardMethod, EObject source) {
        var methodCache = singleSourceCache.get(guardMethod);
        return methodCache != null ? methodCache.get(source) : null;
    }
    
    public void putSingleSourceResult(Method guardMethod, EObject source, boolean result) {
        singleSourceCache
            .computeIfAbsent(guardMethod, m -> new ConcurrentHashMap<>())
            .put(source, result);
    }
    
    // Clear at transformation start
    public void clear() {
        singleSourceCache.clear();
        multiSourceCache.clear();
    }
}
```

### Cache Lookup Order

```
evaluateGuard(source):
  1. Check rule's rejection cache → if rejected, return false
  2. Check guard method cache → if found, return cached result
  3. Evaluate guard
  4. Store in guard method cache (both true and false)
  5. If false, also store in rule's rejection cache
  6. Return result
```

---

1. **Check existing tests** to see which edge cases are already covered?
2. **Draft implementation** with these edge cases handled?
3. **Explore performance impact** - would this actually help in real transformations?
