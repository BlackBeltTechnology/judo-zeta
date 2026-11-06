# Change: Infer Source Type from Generic Parameters

## Why

The ZETA transformation framework currently evaluates **all registered rules against all input elements**, resulting in a 96% rejection rate (77,485 wasted guard evaluations out of 80,695 total). Guard evaluation is expensive because it often involves EMF model traversal.

The first generic parameter `S` in `TransformFunction<S, T>` already declares the source type that a rule can process. By extracting this at registration time and using it for type-based filtering, we can:
1. **Eliminate unnecessary guard evaluations** - Rules are only considered for elements matching their declared source type
2. **Maintain backward compatibility** - No annotation changes required; existing rules work automatically
3. **Achieve ~8x reduction in guard evaluations** - From ~80,695 to ~10,000 for the RackInspect benchmark

## What Changes

- **ADDED**: Extract source type from `TransformFunction<S, T>` generic parameter during rule registration
- **ADDED**: Use extracted source type as the rule's `sourceType` when no explicit `@Transform` or `sourceTypes` attribute is specified
- **MODIFIED**: Type-based rule filtering in `TransformationRegistry` now works automatically without requiring explicit type declarations
- **ADDED**: Handle greedy rules correctly - when source type is a supertype, all subtypes remain eligible
- **ADDED**: Validation that inferred source type matches existing behavior (no behavioral changes)

## Impact

### Affected Specs
- `rule-execution` - New capability for automatic source type inference

### Affected Code
- `TransformationRegistry.java:150-302` - Rule registration, source type extraction
- `TransformationRegistry.java:360-413` - Type-based filtering caches
- `TransformRuleDescriptor.java:401-418` - `appliesTo()` method (unchanged behavior)

### Performance Impact
Based on RackInspect benchmark (22,370 PSM elements):
| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Guard evaluations | 80,695 | ~10,000 | ~8x fewer |
| Time in guards | 3,587ms | ~450ms | ~8x faster |
| Total transformation | 4,598ms | ~1,500ms | ~3x faster |

### Backward Compatibility
- **100% backward compatible** - No annotation changes required
- Rules with explicit `@Transform` or `sourceTypes` attributes continue to use those values (higher priority)
- Rules without explicit source types now benefit from automatic inference
- No behavioral changes - only optimization of which rules are evaluated

### Risk Assessment
- **Low risk**: The inferred type MUST match what `appliesTo()` would return; comprehensive tests ensure behavioral equivalence
- **Validation**: JUnit tests prove that filtering produces identical results to current behavior

## Implementation Approach: Test-Driven Development (TDD)

**IMPORTANT**: This change MUST follow TDD methodology:

1. **Write tests FIRST** - Create comprehensive JUnit tests that define expected behavior BEFORE writing any implementation code
2. **Tests initially fail** - All tests should fail (red) because the feature doesn't exist yet
3. **Implement to pass tests** - Write minimal implementation code to make tests pass (green)
4. **Refactor** - Clean up implementation while keeping tests green

### Required Test Coverage (write before implementation)

| Test Category | Purpose | Priority |
|---------------|---------|----------|
| Generic type extraction | Verify `extractSourceTypeFromReturnType()` correctly extracts first generic parameter | P0 |
| Priority order | Verify `@Transform` > `sourceTypes` > inferred > default | P0 |
| Behavioral equivalence | Verify type filtering matches `appliesTo()` for ALL element/rule combinations | P0 |
| Greedy subtype matching | Verify `@Greedy` rules with inferred supertype match subtypes | P0 |
| Non-greedy exact matching | Verify non-greedy rules only match exact type | P0 |
| Edge cases | Wildcards, bounded types, raw `EObject` fallback | P1 |
| Performance validation | Verify guard evaluation count reduction | P2 |

### TDD Benefits for This Change

- **Proves behavioral equivalence**: Tests document and verify that no semantic changes occur
- **Safe refactoring**: Implementation can be optimized with confidence
- **Living documentation**: Tests serve as executable specification
- **Regression prevention**: Future changes cannot break verified behavior
