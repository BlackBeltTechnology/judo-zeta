# Execution Flow

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Architecture](overview.md) > Execution Flow

Detailed execution flow of the transformation process.

## Transformation Lifecycle

```mermaid
sequenceDiagram
    participant Client
    participant Executor
    participant Registry
    participant Context
    participant Cache
    participant Rule
    
    Client->>Executor: transform(elements)
    
    Note over Executor: Phase 1: Pre-Execution
    Executor->>Registry: invokePreExecutionHooks()
    Registry->>Context: @PreExecution methods
    
    Note over Executor: Phase 2: Eager Execution
    loop For each source element
        Executor->>Registry: getRulesForSource(type)
        Registry-->>Executor: List<TransformRuleDescriptor>
        
        loop For each non-lazy, non-abstract rule
            Executor->>Rule: evaluateGuard()
            alt Guard passes
                Executor->>Rule: apply(source, context)
                Rule->>Context: createTarget(), equivalent()
                Rule-->>Executor: target element
                Executor->>Cache: store(source, rule, target)
            end
        end
    end
    
    Note over Executor: Phase 3: Lazy Resolution
    Note over Context: equivalent() calls trigger lazy rules
    
    Note over Executor: Phase 4: Post-Execution
    Executor->>Registry: invokePostExecutionHooks()
    Registry->>Context: @PostExecution methods
    
    Executor-->>Client: TransformationResult
```

## Phase Details

### Phase 1: Pre-Execution

1. Clear caches from previous runs
2. Invoke all `@PreExecution` methods in registration order
3. Initialize custom attributes

### Phase 2: Eager Execution

For each source element:
1. Find applicable rules (matching source type)
2. Filter out `@Lazy` and `@Abstract` rules
3. For each eligible rule:
   - Evaluate `@Guard` condition
   - If guard passes, execute rule
   - Store result in `ElementResolutionCache`
   - Record entry in `TransformationTrace`

### Phase 3: Lazy Resolution

- Lazy rules execute on-demand via `equivalent()` calls
- Results are cached after first execution
- Subsequent calls return cached results

**Inheritance State Isolation**: When `equivalent()` is called from within a transform function, the framework automatically:
1. Saves the caller's inheritance state (pre-created target, inheritance execution flag)
2. Resets to a clean state for the nested transformation
3. Executes the nested rule with its own independent context
4. Restores the caller's inheritance state after completion

This ensures that `equivalent()` calls from within `@Extends` rules produce independent targets, not accidentally reusing the caller's pre-created target.

### Phase 4: Post-Execution

1. Invoke all `@PostExecution` methods
2. Build `TransformationResult` with trace
3. Return result to caller

## Rule Selection Algorithm

```mermaid
flowchart TD
    A[Source Element] --> B{Find Rules}
    B --> C[Exact Type Match]
    B --> D[Greedy Type Match]
    C --> E{Is Lazy?}
    D --> E
    E -->|Yes| F[Skip - Wait for equivalent]
    E -->|No| G{Is Abstract?}
    G -->|Yes| H[Skip - Called via executeParentRule]
    G -->|No| I{Evaluate Guard}
    I -->|Fails| J[Skip]
    I -->|Passes| K[Execute Rule]
    K --> L[Store in Cache]
```

---

**Previous**: [Overview](overview.md) | **Next**: [Parallel Execution](parallel-execution.md)
