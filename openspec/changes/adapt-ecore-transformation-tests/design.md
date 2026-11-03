# Design: Adapt Ecore Transformation Test Patterns

## Context

The judo-zeta framework currently lacks comprehensive test coverage for complex transformation scenarios that are common in production use. The judo-tatami projects have well-established test patterns for:
- Inheritance hierarchies (abstract classes, multi-level)
- Relation cardinality variations (single/collection, containment/association)
- Entity projections (transfer objects with subset features)
- Realistic synthetic model generation

This design adapts these patterns to use Ecore's `EcorePackage.eINSTANCE` as both source and target metamodel, creating self-transformations that test the framework's capabilities thoroughly.

### Current State

Existing tests in judo-zeta use:
- Simple EClass/EAttribute creation
- Basic parallel execution tests
- Guard rejection scenarios
- XMI ID generation tests

Missing coverage:
- Complex inheritance chains
- Bidirectional references
- Large-scale realistic models
- Performance benchmarking

## Goals / Non-Goals

**Goals:**
- Create `RealisticEcoreModelGenerator` for configurable synthetic models
- Add inheritance transformation tests (abstract, multi-level, feature inheritance)
- Add relation cardinality tests (single/collection, containment/association, bidirectional)
- Add projection/transformation tests (entity→TO with subset features)
- Add performance benchmarks using realistic models
- Provide reference implementations for common transformation patterns

**Non-Goals:**
- Modifying production transformation code
- Creating new framework features
- Testing non-Ecore metamodels
- Full tatami feature parity (only patterns relevant to zeta)

## Decisions

### D1: Use EcorePackage as Source/Target

**Decision**: Use `EcorePackage.eINSTANCE` (EClass, EAttribute, EReference, EOperation) as both source and target metamodel.

**Rationale**:
- Zero external dependencies (EMF is already required)
- Self-transformation is a valid use case (copy/modify patterns)
- All framework features work with Ecore elements
- Simplifies test setup (no custom metamodel registration)

**Alternatives Considered**:
- Custom test metamodel → Rejected: adds complexity, maintenance burden
- Reusing tatami metamodels (ESM, PSM) → Rejected: would require tatami dependencies

### D2: Realistic Model Generator Design

**Decision**: Create `RealisticEcoreModelGenerator` following tatami's `RealisticPsmModelGenerator` patterns.

**Key Features**:
```java
public class RealisticEcoreModelGenerator {

    @Builder
    public static class Config {
        private int packageCount = 3;
        private int classesPerPackage = 20;
        private int attributesPerClass = 5;
        private int referencesPerClass = 3;
        private int operationsPerClass = 2;
        private int inheritanceDepth = 3;  // Max levels
        private double abstractRatio = 0.1;  // 10% abstract
        private long seed = System.currentTimeMillis();
    }

    public ResourceSet generate(Config config);
}
```

**Rationale**:
- Configurable generation supports various test scenarios
- Reproducible with fixed seed
- Production-like ratios ensure realistic testing

### D3: Test Class Hierarchy

**Decision**: Create abstract base class `AbstractEcoreTransformationTest` for common setup.

```java
abstract class AbstractEcoreTransformationTest {
    protected ResourceSet sourceResourceSet;
    protected ResourceSet targetResourceSet;
    protected TransformationContext context;
    protected TransformationRegistry registry;

    @BeforeEach
    void setUp() {
        // Common initialization
    }

    protected EClass createEClass(String name) { ... }
    protected EAttribute createEAttribute(String name, EDataType type) { ... }
    protected EReference createEReference(String name, EClass target) { ... }
}
```

**Rationale**:
- Reduces boilerplate in individual tests
- Consistent setup across test classes
- Easy to extend for specialized tests

### D4: Model Generation Patterns

**Decision**: Generate models with realistic characteristics based on tatami analysis.

**Patterns to Generate**:
1. **Package hierarchy** - 2-5 packages with cross-references
2. **Class hierarchy** - Inheritance trees with configurable depth
3. **Feature distribution**:
   - 4-6 attributes per class (mix of String, Integer, Boolean, Date)
   - 2-4 references per class (mix of containment/association)
   - 0-2 operations per class
4. **Abstract classes** - ~10% of classes abstract
5. **Bidirectional references** - ~30% of references have opposites

### D5: Performance Test Strategy

**Decision**: Use JUnit 5 `@Tag("performance")` with configurable model sizes.

**Test Levels**:
| Level | Classes | Expected Time | CI Inclusion |
|-------|---------|---------------|--------------|
| Small | 20 | <1s | Always |
| Medium | 100 | <10s | Nightly |
| Large | 500 | <60s | Manual |

**Rationale**:
- Tiered approach balances coverage with CI time
- Tagged tests can be filtered in CI pipelines
- Manual testing available for stress testing

## Risks / Trade-offs

| Risk | Impact | Mitigation |
|------|--------|------------|
| Large model generation time | Slow test execution | Use `@Tag("slow")`, keep unit tests small (<50 classes) |
| Memory pressure from large models | OOM in CI | Limit max size to 500 classes, use ResourceSet.clear() in @AfterEach |
| Generator complexity | Maintenance burden | Start simple, add complexity incrementally with test coverage |
| Ecore-specific patterns | Less applicable to custom metamodels | Document patterns as reference implementations, not just tests |
| Flaky parallel tests | Non-deterministic failures | Use fixed seeds, avoid shared mutable state, thorough assertions |

## Migration Plan

This is test-only, no production migration needed.

**Implementation Order**:
1. **Phase 1**: `RealisticEcoreModelGenerator` + basic tests
2. **Phase 2**: `EcoreInheritanceTest` (inheritance patterns)
3. **Phase 3**: `EcoreRelationCardinalityTest` (reference patterns)
4. **Phase 4**: `EcoreProjectionTest` (transfer object patterns)
5. **Phase 5**: `EcorePerformanceBenchmarkTest` (performance tests)

**Validation**: Each phase can be validated independently through test execution.

## Open Questions

| Question | Status | Resolution |
|----------|--------|------------|
| Should we add `ModelComparator` utility? | Resolved | Yes, create `EcoreModelComparator` for structural comparison. Start with element count and type matching. |
| Include performance tests in standard CI? | Resolved | No, use `@Tag("performance")` to exclude from standard runs. |
| Create shared test base class? | Resolved | Yes, `AbstractEcoreTransformationTest` with common setup/helpers. |
| Support for EOperation generation? | Open | Start with attributes/references, add operations in follow-up if needed. |
| Bidirectional reference handling? | Resolved | Generator will create opposite references for ~30% of references. |
