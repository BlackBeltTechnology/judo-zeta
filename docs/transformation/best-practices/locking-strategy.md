# Locking Strategy

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Locking Strategy

This document details the locking strategy used in the Zeta Transformation Framework to ensure thread safety and high performance during parallel execution.

## Core Strategy: Per-Key Locking

Zeta uses **Per-Key Locking** to manage concurrent access to the element resolution cache. This means that for every unique pair of `(source element, rule name)`, there is a dedicated `ReentrantLock`.

```java
// Conceptual implementation
ReentrantLock lock = ruleLocks.computeIfAbsent(
    new CacheKey(source, ruleName), 
    k -> new ReentrantLock()
);
```

### Why Per-Key Locking?

Previous versions (and other frameworks) sometimes use **Lock Striping**, where a fixed array of locks (e.g., 1024) guards all cache entries based on hash codes. 

**We explicitly rejected Lock Striping** because it causes "artificial" deadlocks in our specific use case:

1.  **Nested Locking**: Transformation rules frequently call `equivalent()` (acquiring a lock) from within a rule that is already executing (holding a lock).
2.  **Hash Collisions**: In Lock Striping, two completely unrelated `(source, rule)` pairs can hash to the same lock stripe.
3.  **Deadlock Scenario**:
    *   Thread A executes Rule 1 (Stripe X) and needs Rule 2 (Stripe Y).
    *   Thread B executes Rule 3 (Stripe Y) and needs Rule 4 (Stripe X).
    *   **Deadlock**: Thread A waits for Y, Thread B waits for X.

With **Per-Key Locking**, Lock X and Lock Y are guaranteed to be distinct unless they refer to the *exact same* (source, rule) pair. Since a rule cannot depend on itself (circular dependency), collision-based deadlocks are eliminated.

### Memory Overhead

One concern with Per-Key Locking is memory usage. However, even for a massive model with 1 million active `(source, rule)` pairs during a transformation, the overhead of 1 million `ReentrantLock` objects is approximately 30-40 MB. This is a negligible cost for the stability and correctness guarantees it provides on modern hardware.

## Deadlock Prevention Mechanisms

### 1. Fine-Grained Locks (Per-Key)
As described above, this minimizes the probability of contention and eliminates hash-collision deadlocks.

### 2. Timeout-Based Acquisition
The framework avoids indefinite blocking. All internal lock acquisitions use a timeout (currently 30 seconds).

```java
if (!lock.tryLock(30, TimeUnit.SECONDS)) {
    throw new RuntimeException("Potential deadlock detected...");
}
```

This ensures that if a *true* circular dependency exists (e.g., Rule A depends on Rule B which depends on Rule A), the system will fail fast with a descriptive error rather than hanging forever.

### 3. No "Source-Level" Locking
We purposely avoid locking an entire source element (e.g., "lock(source)" for all rules). Different rules for the same source element (e.g., `Entity2Table` and `Entity2Class`) can and should execute in parallel. This maximizes concurrency.

## Best Practices for Developers

1.  **Avoid Shared Mutable State**: Do not use global static variables or shared collections without synchronization in your rules.
2.  **Rely on `ctx.equivalent()`**: Always use the context methods for lookups. They handle the locking automatically.
3.  **Trust the Framework**: Do not introduce your own `synchronized` blocks around rule logic unless absolutely necessary, as this can introduce new deadlock paths.
