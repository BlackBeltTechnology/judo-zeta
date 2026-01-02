# Proposal: Fix Parallel Transformation EMF Thread-Safety

**Change ID**: `fix-parallel-emf-thread-safety`  
**Status**: Proposed  
**Created**: 2025-12-14  
**Type**: Bug Fix / Enhancement

## Summary

The current parallel transformation implementation has thread-safety issues with EMF model modifications. While `Resource.getContents().add()` is correctly staged, **attribute/reference modifications during rule execution are not protected**, leading to potential `ConcurrentModificationException` or data corruption when multiple threads modify EMF objects concurrently.

This proposal analyzes two solution approaches and recommends the optimal path forward.

## Problem Statement

### Current Architecture Gap

The existing staging mechanism only protects one type of EMF write:

```java
// PROTECTED: Adding to Resource contents
if (stagingEnabled.get()) {
    stagedElements.offer(new StagedElement(instance, true, sequence));
} else {
    targetResource.getContents().add(instance);  // Deferred
}
```

But transformation rules perform many other EMF writes that are **NOT protected**:

```java
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);  // OK - staged
    table.setName(source.getName());              // UNSAFE - direct EMF write
    table.setSchema(schema);                      // UNSAFE - reference write
    table.getColumns().add(column);               // UNSAFE - EList modification
    table.getAnnotations().addAll(annotations);   // UNSAFE - bulk EList modification
    return table;
};
```

### Failure Scenarios

1. **Shared Target Modification**: Two threads transform related elements that reference the same target
2. **Bidirectional References**: Setting a reference triggers automatic inverse update
3. **Containment Hierarchies**: Adding a child to a parent modifies both objects
4. **EList Concurrent Modification**: Multiple threads adding to the same EList

### Observed Symptoms

- `ConcurrentModificationException` during parallel transformation
- Missing or duplicate elements in target model
- Corrupted bidirectional references
- Non-deterministic transformation results

## Solution Analysis

### Option A: Deferred EMF Writes with Record/Replay Pattern

**Concept**: Capture all EMF modifications as immutable "operations" during parallel phase, then replay them sequentially during commit.

#### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PARALLEL PHASE                                    │
├─────────────────────────────────────────────────────────────────────┤
│  Thread 1                    Thread 2                    Thread N   │
│  ┌──────────────────┐       ┌──────────────────┐       ┌─────────┐ │
│  │ TransformRule    │       │ TransformRule    │       │   ...   │ │
│  │                  │       │                  │       │         │ │
│  │ table.setName()──┼──┐    │ col.setType()────┼──┐    │         │ │
│  │ table.add(col)───┼──┤    │ col.setTable()───┼──┤    │         │ │
│  └──────────────────┘  │    └──────────────────┘  │    └─────────┘ │
│                        │                          │                 │
│                        ▼                          ▼                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │         ConcurrentLinkedQueue<EMFOperation>                  │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐            │   │
│  │  │SetAttribute │ │SetReference │ │AddToList    │  ...       │   │
│  │  │target=table │ │target=col   │ │target=table │            │   │
│  │  │attr=name    │ │ref=type     │ │list=columns │            │   │
│  │  │value="T1"   │ │value=String │ │element=col  │            │   │
│  │  │seq=1        │ │seq=2        │ │seq=3        │            │   │
│  │  └─────────────┘ └─────────────┘ └─────────────┘            │   │
│  └─────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────┤
│                    COMMIT PHASE (Single-Threaded)                   │
├─────────────────────────────────────────────────────────────────────┤
│  Sort operations by sequence number                                 │
│  For each operation:                                                │
│    operation.apply()  // Actually modifies EMF model                │
└─────────────────────────────────────────────────────────────────────┘
```

#### Implementation Approach

**EMF Operation Recording:**

```java
public sealed interface EMFOperation permits 
    SetAttributeOp, SetReferenceOp, AddToListOp, 
    RemoveFromListOp, AddAllToListOp, ClearListOp {
    
    long sequence();
    void apply();
}

public record SetAttributeOp(
    EObject target, 
    EStructuralFeature feature, 
    Object value,
    long sequence
) implements EMFOperation {
    @Override
    public void apply() {
        target.eSet(feature, value);
    }
}

public record AddToListOp(
    EObject target,
    EStructuralFeature feature,
    Object element,
    int index,  // -1 for append
    long sequence
) implements EMFOperation {
    @Override
    public void apply() {
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) target.eGet(feature);
        if (index < 0) {
            list.add(element);
        } else {
            list.add(index, element);
        }
    }
}
```

**Proxy-Based Interception:**

```java
public class DeferredEObject implements InvocationHandler {
    private final EObject delegate;
    private final OperationQueue queue;
    private final AtomicLong sequence;
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        
        // Intercept setters
        if (name.startsWith("set") && args.length == 1) {
            EStructuralFeature feature = findFeature(name);
            queue.offer(new SetAttributeOp(delegate, feature, args[0], sequence.getAndIncrement()));
            return null;
        }
        
        // Intercept list accessors - return wrapped list
        if (name.startsWith("get") && EList.class.isAssignableFrom(method.getReturnType())) {
            EList<?> realList = (EList<?>) method.invoke(delegate, args);
            return new DeferredEList(realList, delegate, findFeature(name), queue, sequence);
        }
        
        // Pass through reads
        return method.invoke(delegate, args);
    }
}
```

#### Pros

| Benefit | Impact |
|---------|--------|
| Complete thread isolation | High - No shared mutable state during parallel phase |
| Deterministic ordering | High - Sequence numbers ensure reproducible results |
| No EMF dependency changes | Medium - Works with standard EMF |
| Debugging friendly | Medium - Operations can be logged/traced |
| Rollback possible | Low - Can discard operations on error |

#### Cons

| Drawback | Impact | Mitigation |
|----------|--------|------------|
| Memory overhead | High - All operations stored | Batch commit at thresholds |
| Proxy complexity | High - Must wrap all EObjects and ELists | Code generation for proxies |
| Performance overhead | Medium - Reflection/proxy invocation | Cache method lookups |
| Read-after-write inconsistency | Medium - Writes not visible until commit | Track pending values per object |
| Complex implementation | High - Many edge cases | Extensive testing |

#### Read-After-Write Problem

```java
// In parallel phase:
table.setName("Orders");
String name = table.getName();  // Returns null or old value!
```

**Solution**: Track pending writes per object:

```java
public class DeferredEObject {
    private final ConcurrentHashMap<EStructuralFeature, Object> pendingValues = new ConcurrentHashMap<>();
    
    public Object invoke(...) {
        // On set: record pending value
        if (isSetter(method)) {
            pendingValues.put(feature, args[0]);
            queue.offer(new SetAttributeOp(...));
            return null;
        }
        
        // On get: check pending values first
        if (isGetter(method)) {
            Object pending = pendingValues.get(feature);
            if (pending != null) return pending;
            return method.invoke(delegate, args);
        }
    }
}
```

---

### Option B: Synchronized EMF Access with Fine-Grained Locking

**Concept**: Wrap EMF operations with synchronization to serialize concurrent access to the same objects.

#### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PARALLEL PHASE                                    │
├─────────────────────────────────────────────────────────────────────┤
│  Thread 1                    Thread 2                               │
│  ┌──────────────────┐       ┌──────────────────┐                   │
│  │ table.setName()  │       │ col.setType()    │                   │
│  │       │          │       │       │          │                   │
│  │       ▼          │       │       ▼          │                   │
│  │ synchronized(    │       │ synchronized(    │                   │
│  │   table) {       │       │   col) {         │  ← Different locks│
│  │   eSet(...)      │       │   eSet(...)      │    = parallel OK  │
│  │ }                │       │ }                │                   │
│  └──────────────────┘       └──────────────────┘                   │
│                                                                     │
│  Thread 3                    Thread 4                               │
│  ┌──────────────────┐       ┌──────────────────┐                   │
│  │ table.add(col)   │       │ table.add(idx)   │                   │
│  │       │          │       │       │          │                   │
│  │       ▼          │       │       ▼          │                   │
│  │ synchronized(    │       │ synchronized(    │                   │
│  │   table) {       │       │   table) {       │  ← Same lock      │
│  │   wait...        │◄──────│   eList.add(...) │    = serialized   │
│  │   eList.add(...) │       │ }                │                   │
│  │ }                │       └──────────────────┘                   │
│  └──────────────────┘                                               │
└─────────────────────────────────────────────────────────────────────┘
```

#### Implementation Approach

**Synchronized Wrapper:**

```java
public class SynchronizedEMFAccess {
    
    /**
     * Set an attribute with synchronization on the target object.
     */
    public static void setFeature(EObject target, EStructuralFeature feature, Object value) {
        synchronized (target) {
            target.eSet(feature, value);
        }
    }
    
    /**
     * Add to a list with synchronization on the container.
     */
    public static <T> void addToList(EObject container, EStructuralFeature feature, T element) {
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.add(element);
        }
    }
    
    /**
     * Add all to a list with synchronization.
     */
    public static <T> void addAllToList(EObject container, EStructuralFeature feature, Collection<T> elements) {
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.addAll(elements);
        }
    }
}
```

**Integration with TransformationContext:**

```java
public class TransformationContext {
    
    /**
     * Thread-safe attribute setter.
     */
    public void set(EObject target, String featureName, Object value) {
        EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
        if (feature == null) {
            throw new IllegalArgumentException("Unknown feature: " + featureName);
        }
        synchronized (target) {
            target.eSet(feature, value);
        }
    }
    
    /**
     * Thread-safe list adder.
     */
    public <T> void add(EObject container, String featureName, T element) {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        synchronized (container) {
            @SuppressWarnings("unchecked")
            EList<T> list = (EList<T>) container.eGet(feature);
            list.add(element);
        }
    }
}
```

**Usage in Transformation Rules:**

```java
// Current (UNSAFE):
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    table.setName(source.getName());          // Direct EMF call
    table.getColumns().add(column);           // Direct EList access
    return table;
};

// Option B (SAFE):
return (source, ctx) -> {
    Table table = ctx.createTarget(Table.class);
    ctx.set(table, "name", source.getName()); // Synchronized
    ctx.add(table, "columns", column);        // Synchronized
    return table;
};
```

#### Pros

| Benefit | Impact |
|---------|--------|
| Simple implementation | High - Standard Java synchronization |
| No memory overhead | High - No operation recording |
| Immediate visibility | High - Writes visible immediately |
| Familiar pattern | Medium - Developers understand locks |
| Incremental adoption | Medium - Can mix with existing code |

#### Cons

| Drawback | Impact | Mitigation |
|----------|--------|------------|
| Lock contention | High - Hot objects become bottlenecks | Identify and isolate hot paths |
| Deadlock risk | High - Nested locks on related objects | Lock ordering protocol |
| API change required | Medium - Rules must use ctx.set() | Deprecation period, migration guide |
| Non-deterministic order | Medium - Depends on lock acquisition | Accept or add ordering layer |
| Reduced parallelism | Medium - Serialization at lock points | Fine-grained locks, short critical sections |

#### Deadlock Scenario

```java
// Thread 1:
synchronized (table) {
    table.setSchema(schema);  // Needs lock on schema for bidirectional
    synchronized (schema) {   // DEADLOCK if Thread 2 holds schema
        schema.getTables().add(table);
    }
}

// Thread 2:
synchronized (schema) {
    schema.setName("public");
    synchronized (table) {   // DEADLOCK if Thread 1 holds table
        table.setSchema(schema);
    }
}
```

**Deadlock Prevention - Lock Ordering:**

```java
public static void setMutualReference(EObject a, EObject b, String aFeature, String bFeature) {
    // Always lock in consistent order based on identity hash
    EObject first = System.identityHashCode(a) < System.identityHashCode(b) ? a : b;
    EObject second = first == a ? b : a;
    
    synchronized (first) {
        synchronized (second) {
            a.eSet(a.eClass().getEStructuralFeature(aFeature), b);
            // EMF handles inverse automatically
        }
    }
}
```

---

## Comparative Analysis

### Performance Comparison

| Scenario | Option A (Deferred) | Option B (Synchronized) |
|----------|---------------------|-------------------------|
| Independent objects | Excellent - No contention | Excellent - No contention |
| Few shared objects | Good - Operations queued | Good - Brief lock holds |
| Many shared objects | Good - Still parallel | Poor - Lock contention |
| Deep containment | Moderate - Many operations | Poor - Nested locks risk |
| Memory usage | Higher - Operation storage | Lower - No storage |
| Commit phase | Sequential replay | N/A - Already committed |

### Complexity Comparison

| Aspect | Option A (Deferred) | Option B (Synchronized) |
|--------|---------------------|-------------------------|
| Core implementation | High - Proxies, operations, replay | Low - Synchronized wrappers |
| Edge cases | Many - R/W, collections, bidirectional | Few - Mainly deadlock |
| Testing effort | High - Many operation types | Medium - Lock scenarios |
| Debugging | Easy - Trace operations | Hard - Lock contention |
| API impact | Low - Transparent proxies | Medium - New methods |

### Risk Comparison

| Risk | Option A | Option B |
|------|----------|----------|
| Correctness bugs | Medium - Complex logic | Low - Simple locking |
| Performance issues | Low - Predictable | Medium - Contention |
| Deadlocks | None | Medium - Preventable |
| Memory exhaustion | Medium - Large transforms | None |
| Maintenance burden | High | Low |

---

## Recommendation

### Primary Recommendation: Hybrid Approach

Implement **Option A (Deferred Writes)** for the core mechanism, with **Option B (Synchronized)** as a fallback for specific scenarios.

#### Rationale

1. **Complete Thread Isolation**: Option A provides true isolation - no race conditions possible during parallel phase

2. **Deterministic Results**: Sequence-ordered replay ensures identical results across runs

3. **Performance Scalability**: No lock contention means linear speedup with cores

4. **Debugging/Tracing**: Operation log enables powerful debugging capabilities

5. **Rollback Capability**: Can abort transformation without corrupting model

#### Implementation Strategy

**Phase 1: Deferred Write Infrastructure**
- Implement EMFOperation hierarchy
- Implement OperationQueue with sequencing
- Implement basic proxy factory for common cases

**Phase 2: Transparent Proxy Integration**
- Modify `createTarget()` to return proxied EObjects
- Implement DeferredEList for collection operations
- Handle read-after-write with pending value tracking

**Phase 3: Commit Phase**
- Sort operations by sequence
- Apply operations sequentially
- Handle errors with partial rollback

**Phase 4: Fallback Mechanism**
- Provide synchronized access methods for edge cases
- Document when to use fallback
- Migration utilities for existing code

---

## Alternative: Minimal Synchronized Approach

If Option A is deemed too complex for initial implementation, a **minimal Option B** can provide immediate relief:

### Minimal Implementation

1. **Document thread-safety requirements** for transformation rules
2. **Provide synchronized helper methods** in TransformationContext
3. **Identify and fix specific hot spots** causing failures
4. **Accept reduced parallelism** for problem scenarios

### When to Choose Minimal Approach

- Time constraints prevent full Option A implementation
- Existing transformations rarely share target objects
- Performance under lock contention is acceptable
- Team prefers simpler, well-understood solution

---

## Success Criteria

- [ ] No `ConcurrentModificationException` in parallel transformation
- [ ] Deterministic transformation results (identical across runs)
- [ ] Performance within 20% of current parallel implementation
- [ ] Memory overhead < 50% increase for large models
- [ ] All existing tests pass
- [ ] Stress tests with 100+ threads pass reliably
- [ ] Clear migration path for existing transformation code

## Implementation Estimate

| Component | Option A | Option B |
|-----------|----------|----------|
| Core infrastructure | 3-4 days | 1 day |
| Proxy implementation | 2-3 days | N/A |
| Testing | 2-3 days | 1-2 days |
| Documentation | 1 day | 0.5 days |
| **Total** | **8-11 days** | **2.5-3.5 days** |

---

## Real-World Impact Analysis: judo-tatami-base

This section analyzes the actual transformation codebase at `/Users/robson/Project/judo-ng/runtime/judo-tatami-base/` to determine the practical impact of each option.

### Codebase Overview

| Category | Count | Examples |
|----------|-------|----------|
| Main Zeta Transformations | 6 | Asm2RdbmsZetaTransformation, Psm2AsmZetaTransformation |
| Incremental Subtransformations | 8 | BeforeIncrementalZetaTransformation, DbCheckupZetaTransformation |
| Rule Classes | 13 | Asm2RdbmsRules, TypeRules, NamespaceRules |
| **Total Files with EMF Modifications** | **27** | |

### EMF Modification Patterns Found

**Pattern 1: Direct Setter Chains (294+ occurrences)**
```java
// From Asm2RdbmsZetaTransformation.java:209-210
rdbmsModelElement.setVersion(modelVersion);
rdbmsModelElement.setName(rootPackage.getName());

// From Asm2RdbmsZetaTransformation.java:240-242
table.setSqlName(tableSqlName(eClass));
table.setName(asmUtils.getClassifierFQName(eClass));
table.setUuid("(asm/" + getId(eClass) + ")/Table");

// From Asm2RdbmsZetaTransformation.java:278-280
idField.setName(asmUtils.getClassifierFQName(eClass) + "#_id");
idField.setUuid("(asm/" + getId(eClass) + ")/TableIdField");
idField.setSqlName("ID");
```

**Pattern 2: Collection Additions (High Frequency)**
```java
// From Asm2RdbmsZetaTransformation.java:212
rdbmsModel.getResource().getContents().add(rdbmsModelElement);

// From Asm2RdbmsZetaTransformation.java:248
model.getRdbmsTables().add(table);

// From Asm2RdbmsZetaTransformation.java:283, 297, 310, etc.
table.getFields().add(idField);
table.getFields().add(typeField);
table.getFields().add(versionField);

// From Asm2RdbmsZetaTransformation.java:258
table.getParents().add(parentTable);

// From Asm2RdbmsZetaTransformation.java:447, 450
index.getFields().add(field);
table.getIndexes().add(index);
```

**Pattern 3: Reference Setting**
```java
// From Asm2RdbmsZetaTransformation.java:219
rdbmsModelElement.setConfiguration(configuration);

// From Asm2RdbmsZetaTransformation.java:284
table.setPrimaryKey(idField);

// From Asm2RdbmsZetaTransformation.java:516
fk.setReferenceKey(targetTable.getPrimaryKey());
```

**Pattern 4: Complex Multi-Step Creation**
```java
// Typical pattern from Asm2RdbmsZetaTransformation.java:276-285
private void createTableIdField(EClass eClass, RdbmsTable table) {
    RdbmsIdentifierField idField = rdbmsFactory.createRdbmsIdentifierField();
    setId(idField, "(asm/" + getId(eClass) + ")/TableIdField");
    idField.setName(asmUtils.getClassifierFQName(eClass) + "#_id");
    idField.setUuid("(asm/" + getId(eClass) + ")/TableIdField");
    idField.setSqlName("ID");
    fillType(idField, "java.util.UUID", null);
    table.getFields().add(idField);
    table.setPrimaryKey(idField);
    addTrace(eClass, ECLASS_TO_TABLE_ID_FIELD, idField);
}
```

### Transformation Architecture Patterns

The codebase uses **THREE distinct patterns**:

**1. Manual Orchestration (Asm2RdbmsZetaTransformation) - Does NOT use TransformationContext**
```java
public Map<EObject, List<EObject>> execute() {
    transformRootPackages();      // Phase 1
    transformEntityClasses();     // Phase 2
    transformAttributes();        // Phase 3
    transformReferences();        // Phase 4
    postProcess();                // Phase 5
    return buildTraceResult();
}

private void transformEntityClass(EClass eClass) {
    RdbmsTable table = rdbmsFactory.createRdbmsTable();  // Direct factory
    table.setSqlName(tableSqlName(eClass));              // Direct setter
    model.getRdbmsTables().add(table);                   // Direct list add
    addTrace(eClass, ECLASS_TO_RDBMS_TABLE, table);
}
```

**2. Rule-Based with TransformFunction (Psm2AsmZetaTransformation)**
```java
@TransformRule(name = "EntityType2EClass")
@Transform(type = EntityType.class)
@To(type = EClass.class)
public TransformFunction<EntityType, EClass> entityType2EClass() {
    return (source, ctx) -> {
        EClass target = ctx.createTarget(EClass.class);
        target.setName(source.getName());               // Direct setter
        target.setAbstract(source.isAbstract());        // Direct setter
        return target;
    };
}
```

**3. Hybrid Pattern**
```java
// Uses both rule annotations and manual execution
```

---

## Option A: Feasibility for Real Projects

### Why Option A Works Transparently

**Key Insight**: All transformations use standard EMF patterns that can be intercepted:

| Pattern | Interceptable? | Mechanism |
|---------|----------------|-----------|
| `obj.setXxx(value)` | Yes | Dynamic proxy intercepts setter |
| `obj.getList().add(x)` | Yes | Return wrapped EList from getter |
| `factory.createXxx()` | Yes | Wrap factory or post-creation wrap |
| `obj.eSet(feature, value)` | Yes | Proxy intercepts eSet |

**Zero Code Changes Required in Transformations**:

```java
// CURRENT CODE (unchanged)
RdbmsTable table = rdbmsFactory.createRdbmsTable();
table.setSqlName(tableSqlName(eClass));
table.setName(asmUtils.getClassifierFQName(eClass));
model.getRdbmsTables().add(table);

// WITH OPTION A (same code, different behavior)
RdbmsTable table = rdbmsFactory.createRdbmsTable();  // Returns proxy
table.setSqlName(tableSqlName(eClass));              // Operation recorded
table.setName(asmUtils.getClassifierFQName(eClass)); // Operation recorded
model.getRdbmsTables().add(table);                   // Operation recorded
// All operations applied atomically during commit phase
```

### Integration Points for Option A

Only **2-3 files** need modification in judo-zeta framework:

| File | Change |
|------|--------|
| `TransformationContext.createTarget()` | Return proxied EObject |
| `TransformationExecutor.transformWithStaging()` | Add operation replay phase |
| (Optional) Factory wrapper | Intercept direct factory usage |

**Zero changes to judo-tatami-base transformations.**

### Handling Direct Factory Usage

The main challenge is that `Asm2RdbmsZetaTransformation` uses direct factory:
```java
RdbmsIdentifierField idField = rdbmsFactory.createRdbmsIdentifierField();
```

**Solutions**:

1. **Post-creation wrapping**: Wrap objects when added to traced collections
2. **Factory injection**: Provide wrapped factory via transformation context
3. **Aspect-oriented**: Intercept factory calls (complex)

Recommended: **Solution 1** - wrap when objects enter the transformation trace.

---

## Option B: Impact on Real Projects

### Required Code Changes for judo-tatami-base

**Every EMF modification must change**:

```java
// BEFORE (current code)
table.setName(asmUtils.getClassifierFQName(eClass));
table.getFields().add(idField);
table.setPrimaryKey(idField);

// AFTER (Option B - requires ctx parameter everywhere)
ctx.set(table, "name", asmUtils.getClassifierFQName(eClass));
ctx.add(table, "fields", idField);
ctx.set(table, "primaryKey", idField);
```

### Estimated Changes Required

| File | EMF Modifications | Lines to Change |
|------|-------------------|-----------------|
| Asm2RdbmsZetaTransformation.java | 150+ | ~300 |
| Psm2AsmZetaTransformation.java | 80+ | ~160 |
| Rdbms2LiquibaseZetaTransformation.java | 60+ | ~120 |
| Asm2KeycloakZetaTransformation.java | 40+ | ~80 |
| Psm2MeasureZetaTransformation.java | 30+ | ~60 |
| 8 Incremental subtransformations | 20+ each | ~320 |
| 13 Rule classes | 10+ each | ~260 |
| **Total** | **500+** | **~1,300** |

### Breaking Changes with Option B

1. **TransformationContext dependency everywhere**
   - Manual orchestration (Asm2RdbmsZetaTransformation) doesn't use ctx
   - Would require major refactoring to pass ctx to all methods

2. **Factory usage breaks**
   - Direct `rdbmsFactory.createXxx()` bypasses ctx
   - All factory calls need replacement

3. **Method signatures change**
   ```java
   // Before
   private void createTableIdField(EClass eClass, RdbmsTable table)
   
   // After (Option B)
   private void createTableIdField(EClass eClass, RdbmsTable table, TransformationContext ctx)
   ```

### Migration Effort for Option B

```
Option B Migration for judo-tatami-base:
├── Analysis & Planning:           2 days
├── Refactor Asm2RdbmsZeta:        2-3 days (largest, most complex)
├── Refactor Psm2AsmZeta:          1-2 days
├── Refactor other 4 main classes: 2 days
├── Refactor 8 incremental:        2 days
├── Refactor 13 rule classes:      2 days
├── Testing & Debugging:           3-4 days
├── Code Review:                   1-2 days
└── Total:                         15-18 days
```

---

## Side-by-Side Comparison for Real Projects

| Criterion | Option A (Deferred) | Option B (Synchronized) |
|-----------|---------------------|-------------------------|
| **Files to modify in tatami-base** | 0 | 27 |
| **Lines to change in tatami-base** | 0 | ~1,300 |
| **Migration effort (tatami-base)** | 0 days | 15-18 days |
| **Framework implementation** | 8-11 days | 2.5-3.5 days |
| **Total effort** | 8-11 days | 17.5-21.5 days |
| **Risk of regression bugs** | Low | High |
| **Works with manual orchestration** | Yes | Requires major refactor |
| **Works with rule-based approach** | Yes | Yes (with changes) |
| **Future transformation changes** | None | Must use ctx.set/add |

---

## Recommendation for Projects Like judo-tatami-base

### Choose Option A When:

- Existing transformations use direct EMF API (setters, list.add())
- Manual orchestration pattern is used (like Asm2RdbmsZetaTransformation)
- Zero migration effort is required for existing code
- Multiple transformation modules exist
- Team wants transparent thread-safety without code changes

### Choose Option B When:

- Starting a completely new project from scratch
- All transformations already use TransformationContext for everything
- Simpler framework implementation is preferred
- Willing to refactor all existing transformations

---

## Final Recommendation

**For judo-tatami-base and similar real-world projects: Option A is strongly recommended**

| Factor | Option A | Option B |
|--------|----------|----------|
| Framework implementation | 8-11 days | 2.5-3.5 days |
| tatami-base migration | **0 days** | **15-18 days** |
| **Total effort** | **8-11 days** | **17.5-21.5 days** |
| Regression risk | Low | High |
| Ongoing maintenance | Framework only | All transformations |

**Key advantages of Option A**:
1. **Zero migration cost** - existing transformations work unchanged
2. **50% less total effort** - framework work vs. framework + migration
3. **Lower risk** - bugs are localized to framework, not spread across 27 files
4. **Better maintainability** - transformation authors don't need to think about thread-safety
5. **Works with all patterns** - manual orchestration, rule-based, and hybrid

## References

- Current parallel implementation: `TransformationExecutor.java`
- Current staging: `TransformationContext.java`
- EMF Thread-Safety: [EMF FAQ](https://wiki.eclipse.org/EMF/FAQ#Is_EMF_thread-safe.3F)
- Previous parallel proposal: `openspec/changes/archive/2025-12-08-parallelize-transformation-execution/`
- Real transformation example: `judo-tatami-base/.../Asm2RdbmsZetaTransformation.java` (959 lines, 150+ EMF modifications)

---

# Appendix: ESM-to-PSM Parallel Execution Analysis

This appendix documents concrete findings from testing parallel execution in the ESM-to-PSM transformation.

## Current State

| Mode | Status | Performance | Notes |
|------|--------|-------------|-------|
| Sequential | ✅ Working | 2,265 ms | 10.72x faster than ETL |
| Parallel | ❌ Broken | N/A | Element duplication, NPE |

## Identified Issues

### Issue 1: Element Duplication in Parallel Mode

**Symptom:** Parallel execution creates 4 extra elements (22,138 vs 22,134 expected).

**Root Cause:** Multiple threads executing the same transformation rule for the same source element, each creating a target element before the cache is updated.

**Affected Areas:**
- `@Greedy` rules that create elements based on related source elements
- Rules with `@Lazy` annotation that are triggered from multiple call sites
- Rules that use `ctx.executeParentRule()` concurrently

**Evidence:**
```
Element count mismatch between ETL and Zeta ==> expected: <22134> but was: <22138>
```

### Issue 2: NullPointerException - preparedResult is null

**Symptom:** `Cannot invoke "org.eclipse.emf.ecore.InternalEObject.eDirectResource()" because "this.preparedResult" is null`

**Root Cause:** A thread attempts to access an element that another thread is still creating. The element exists in the cache but its internal EMF state is not yet fully initialized.

**Affected Areas:**
- Element creation in `TransformationContext.create()` or `createTarget()`
- EMF resource attachment during parallel containment operations
- XMI ID assignment during element creation

### Issue 3: EMF Collection Concurrent Modification

**Symptom:** `ArrayIndexOutOfBoundsException` or `ConcurrentModificationException` in EMF EList operations.

**Root Cause:** EMF ELists are not thread-safe. Multiple threads adding elements to the same containment reference causes corruption.

**Current Mitigation:** `TransformationHelper.synchronizedAdd()` and related methods provide synchronized access.

**Status:** ✅ Fixed in transformation rules, but Zeta framework may have internal unsynchronized operations.

---

## Required Fixes

### 1. Zeta Framework: Thread-Safe Element Resolution Cache

**Location:** `hu.blackbelt.judo.zeta.transformation.core.ElementResolutionCache`

**Requirements:**

1.1. **Atomic get-or-create operation:**
```java
/**
 * Atomically get existing element or create new one.
 * Prevents duplicate creation when multiple threads request the same element.
 */
<T> T getOrCreate(EObject source, String ruleName, Supplier<T> creator);
```

1.2. **Thread-safe cache operations:**
- Use `ConcurrentHashMap` for all internal maps
- Use `computeIfAbsent()` for atomic insertion
- Ensure visibility of cached elements across threads

1.3. **Cache key must include rule name:**
- Key: `(sourceElement, ruleName)` tuple
- Prevents cache pollution between rules producing different target types

**Example Implementation:**
```java
public class ElementResolutionCache {
    private final ConcurrentHashMap<CacheKey, EObject> cache = new ConcurrentHashMap<>();

    public <T extends EObject> T getOrCreate(EObject source, String ruleName, Supplier<T> creator) {
        CacheKey key = new CacheKey(source, ruleName);
        return (T) cache.computeIfAbsent(key, k -> {
            T result = creator.get();
            // Ensure element is fully initialized before returning
            return result;
        });
    }

    private static class CacheKey {
        final EObject source;
        final String ruleName;
        // equals() and hashCode() based on both fields
    }
}
```

### 2. Zeta Framework: Thread-Safe Rule Execution

**Location:** `hu.blackbelt.judo.zeta.transformation.core.TransformationExecutor`

**Requirements:**

2.1. **Rule execution locking per source element:**
```java
/**
 * Execute rule with per-element locking to prevent duplicate execution.
 */
<T> T executeRule(String ruleName, EObject source, Function<EObject, T> ruleFunction);
```

2.2. **Lock granularity:**
- Lock per `(sourceElement, ruleName)` pair, not globally
- Use `ReentrantLock` or `StampedLock` for each source element
- Release lock after element is fully created and cached

2.3. **Prevent re-entrant duplicate creation:**
- If thread A is creating element for source S, thread B requesting same element should wait
- After thread A completes, thread B gets cached result

**Example Implementation:**
```java
public class TransformationExecutor {
    private final ConcurrentHashMap<CacheKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T executeRule(String ruleName, EObject source, Function<EObject, T> ruleFunction) {
        CacheKey key = new CacheKey(source, ruleName);

        // Check cache first (fast path)
        T cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Acquire lock for this specific (source, rule) pair
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check after acquiring lock
            cached = cache.get(key);
            if (cached != null) {
                return cached;
            }

            // Execute rule and cache result
            T result = ruleFunction.apply(source);
            cache.put(key, result);
            return result;
        } finally {
            lock.unlock();
        }
    }
}
```

### 3. Zeta Framework: Thread-Safe EMF Operations

**Location:** `hu.blackbelt.judo.zeta.transformation.core.TransformationContext`

**Requirements:**

3.1. **Synchronized element creation:**
```java
/**
 * Create element with guaranteed resource attachment.
 * Element must be fully initialized before returning.
 */
<T extends EObject> T create(Class<T> type);
```

3.2. **Atomic containment operations:**
- Adding element to containment reference must be atomic
- Parent's resource must be set before child operations
- XMI ID must be set after resource attachment

3.3. **Memory barriers:**
- Use `volatile` or synchronized blocks to ensure visibility
- Ensure `eResource()` is non-null before element is cached

**Example Implementation:**
```java
public <T extends EObject> T create(Class<T> type) {
    T element = factory.create(type);

    // Ensure element is in a consistent state
    synchronized (element) {
        // Initialize any required internal state
        initializeElement(element);
    }

    return element;
}

public void addToContainment(EObject parent, EStructuralFeature feature, EObject child) {
    synchronized (parent) {
        EList<EObject> list = (EList<EObject>) parent.eGet(feature);
        synchronized (list) {
            list.add(child);
        }
    }

    // Set XMI ID after containment (element now has resource)
    setXmiId(child);
}
```

### 4. Transformation Rules: Thread-Safe Helpers

**Location:** `hu.blackbelt.judo.tatami.esm2psm.zeta.extensions.TransformationHelper`

**Current State:** ✅ Already implemented synchronized helpers

**Requirements (already met):**

4.1. `synchronizedAdd(EList, element)` - Thread-safe list addition
4.2. `addToNamespace(Namespace, element)` - Synchronized namespace operations
4.3. `addToPackage(Package, element)` - Synchronized package operations

**Additional Requirements:**

4.4. **Synchronized XMI ID operations:**
```java
public static void setXmiId(EObject element, String id) {
    Resource resource = element.eResource();
    if (resource instanceof XMLResource) {
        synchronized (resource) {
            ((XMLResource) resource).setID(element, id);
        }
    }
}
```

### 5. Transformation Rules: Idempotent Rule Design

**Location:** All rule classes in `hu.blackbelt.judo.tatami.esm2psm.zeta.rules`

**Requirements:**

5.1. **Rules must be idempotent:**
- Calling a rule twice for the same source must return the same target
- Rules must check cache before creating new elements
- Use `ctx.getOrCreate()` pattern instead of direct creation

5.2. **Pattern for idempotent rules:**
```java
@TransformRule(name = "CreateEntityType")
public TransformFunction<EntityType, hu.blackbelt.judo.meta.psm.data.EntityType> createEntityType() {
    return (source, ctx) -> {
        // Framework should handle caching - rule just creates
        // If rule is called, it means cache miss, so create new element
        var target = ctx.create(hu.blackbelt.judo.meta.psm.data.EntityType.class);
        // ... set properties ...
        return target;
    };
}
```

5.3. **Avoid side effects in guards:**
- Guard methods must be pure functions
- Guards must not modify state or create elements
- Guards must be thread-safe (no shared mutable state)

### 6. Post-Processor: Thread-Safe Inheritance Copying

**Location:** `hu.blackbelt.judo.tatami.esm2psm.zeta.Esm2PsmPostProcessor`

**Requirements:**

6.1. **Sequential post-processing is acceptable:**
- Post-processing runs after main transformation
- Currently takes ~40ms, optimization not critical
- Can remain sequential for simplicity

6.2. **If parallelized, needs:**
- Synchronized access to target TO's collections
- Copy operations must be atomic
- Avoid modifying element while another thread reads it

---

## Testing Requirements

### Test 1: Element Count Equivalence

```java
@Test
void parallelModeProducesSameElementCount() {
    // Run sequential
    PsmModel sequentialResult = runTransformation(parallel=false);
    int sequentialCount = countElements(sequentialResult);

    // Run parallel
    PsmModel parallelResult = runTransformation(parallel=true);
    int parallelCount = countElements(parallelResult);

    assertEquals(sequentialCount, parallelCount);
}
```

### Test 2: Structural Equivalence

```java
@Test
void parallelModeProducesStructurallyEquivalentModel() {
    PsmModel sequentialResult = runTransformation(parallel=false);
    PsmModel parallelResult = runTransformation(parallel=true);

    ComparisonResult result = ModelComparator.compare(
        sequentialResult, parallelResult, ComparisonMode.STRUCTURAL);

    assertTrue(result.isEquivalent());
}
```

### Test 3: Deterministic Output

```java
@Test
void parallelModeIsDeterministic() {
    // Run parallel multiple times
    List<PsmModel> results = IntStream.range(0, 10)
        .mapToObj(i -> runTransformation(parallel=true))
        .collect(toList());

    // All results should be structurally equivalent
    PsmModel reference = results.get(0);
    for (PsmModel result : results) {
        assertTrue(ModelComparator.compare(reference, result).isEquivalent());
    }
}
```

### Test 4: No Duplicate Elements

```java
@Test
void parallelModeCreatesNoDuplicates() {
    PsmModel result = runTransformation(parallel=true);

    // Check for duplicate named elements in same namespace
    Map<String, List<NamedElement>> byName = new HashMap<>();
    result.getResource().getAllContents().forEachRemaining(obj -> {
        if (obj instanceof NamedElement) {
            NamedElement ne = (NamedElement) obj;
            String key = getFullPath(ne);
            byName.computeIfAbsent(key, k -> new ArrayList<>()).add(ne);
        }
    });

    byName.forEach((path, elements) -> {
        assertEquals(1, elements.size(), "Duplicate element at: " + path);
    });
}
```

### Test 5: Stress Test

```java
@Test
void parallelModeHandlesHighConcurrency() {
    // Use large model (10,000+ elements)
    EsmModel largeModel = RealisticModelGenerator.scaled(10000);

    // Run with high parallelism
    PsmModel result = runTransformation(largeModel, parallel=true, threads=16);

    // Verify correctness
    assertNoExceptions();
    assertCorrectElementCount(result);
}
```

---

## Implementation Priority

| Priority | Component | Effort | Impact |
|----------|-----------|--------|--------|
| 1 | Thread-safe cache (get-or-create) | Medium | High - Prevents duplicates |
| 2 | Per-element rule locking | Medium | High - Prevents race conditions |
| 3 | Synchronized EMF operations | Low | Medium - Prevents NPE |
| 4 | Transformation rule updates | Low | Low - Mostly done |
| 5 | Post-processor parallelization | Low | Low - Already fast |

## Success Criteria

1. **Correctness:** Parallel mode produces 100% structurally equivalent output to sequential mode
2. **No duplicates:** Element count matches exactly between modes
3. **No exceptions:** No NPE, ArrayIndexOutOfBounds, or ConcurrentModificationException
4. **Deterministic:** Multiple parallel runs produce identical results
5. **Performance:** Parallel mode is at least 2x faster than sequential on multi-core systems

---

## Current Workarounds

### Workaround 1: Sequential Mode (Current)

```java
executeEsm2PsmTransformation(esm2PsmParameter()
    .esmModel(esmModel)
    .psmModel(psmModel)
    .parallel(false)  // Use sequential mode
    .transformationMode(TransformationMode.ZETA));
```

**Pros:** Works correctly, 10x faster than ETL
**Cons:** Doesn't utilize multiple cores

### Workaround 2: ctx.create() instead of ctx.createTarget()

**Location:** `TransformationHelper.createForNamespaceElement()`

```java
// Use create() to avoid cache pollution from type hierarchy
T target = ctx.create(targetType);  // NOT ctx.createTarget()
```

**Status:** ✅ Implemented - Prevents MappedActorType/MappedTransferObjectType cache pollution

### Workaround 3: Synchronized Collection Operations

**Location:** `TransformationHelper`

```java
public static void addToNamespace(Namespace ns, NamespaceElement element) {
    synchronized (ns.getElements()) {
        ns.getElements().add(element);
    }
}
```

**Status:** ✅ Implemented - Prevents EMF collection corruption
