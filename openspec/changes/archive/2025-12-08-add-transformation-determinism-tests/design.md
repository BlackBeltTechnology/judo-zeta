# Design: Add Transformation Determinism Tests

**Change ID**: `add-transformation-determinism-tests`

## Overview

This document describes the testing approach for verifying deterministic transformation output between sequential and parallel execution modes.

## Design Principles

1. **Comparison-Based Testing**: Run same transformation twice, compare outputs
2. **Multiple Verification Methods**: Use both XMI bytes and EMF structural comparison
3. **Repeated Execution**: Run tests many times to catch intermittent ordering issues
4. **Realistic Scenarios**: Test with containment, references, and varied element counts

## Architecture

### Test Structure

```
transformation-core/src/test/java/
└── hu/blackbelt/judo/zeta/transformation/core/
    ├── ConcurrencyStressTest.java          # Existing - thread safety
    └── TransformationDeterminismTest.java  # NEW - output equivalence
```

### Test Class Design

```java
class TransformationDeterminismTest {

    @Nested
    @DisplayName("Sequential vs Parallel Equivalence")
    class SequentialParallelEquivalence {
        // Tests comparing seq and parallel output
    }
    
    @Nested
    @DisplayName("Parallel Run Consistency")
    class ParallelRunConsistency {
        // Tests comparing multiple parallel runs
    }
    
    @Nested
    @DisplayName("XMI Serialization Determinism")
    class XmiSerializationDeterminism {
        // Tests comparing serialized XMI bytes
    }
    
    @Nested
    @DisplayName("Containment Ordering")
    class ContainmentOrdering {
        // Tests for nested element ordering
    }
}
```

## Comparison Methods

### Method 1: XMI Byte Comparison

Serialize both models to XMI and compare the resulting bytes:

```java
private byte[] serializeToXmi(Resource resource) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Map<String, Object> options = new HashMap<>();
    options.put(XMIResource.OPTION_ENCODING, "UTF-8");
    options.put(XMIResource.OPTION_FORMATTED, Boolean.TRUE);
    resource.save(baos, options);
    return baos.toByteArray();
}

@Test
void sequentialAndParallelProduceSameXmi() {
    byte[] sequentialXmi = runTransformationAndSerialize(parallel=false);
    byte[] parallelXmi = runTransformationAndSerialize(parallel=true);
    
    assertArrayEquals(sequentialXmi, parallelXmi,
        "XMI output should be byte-identical");
}
```

**Pros**: Strongest guarantee - proves file output is identical
**Cons**: Sensitive to XMI serialization options

### Method 2: EMF Structural Comparison

Use `EcoreUtil.equals()` for element comparison:

```java
@Test
void sequentialAndParallelProduceEqualModels() {
    Resource seqResource = runTransformation(parallel=false);
    Resource parResource = runTransformation(parallel=true);
    
    assertEquals(seqResource.getContents().size(), 
                 parResource.getContents().size());
    
    for (int i = 0; i < seqResource.getContents().size(); i++) {
        assertTrue(EcoreUtil.equals(
            seqResource.getContents().get(i),
            parResource.getContents().get(i)
        ), "Element at index " + i + " should be equal");
    }
}
```

**Pros**: Semantic comparison, less sensitive to serialization
**Cons**: May miss ordering differences within collections

### Method 3: Element Order Verification

Explicitly verify element ordering matches:

```java
@Test
void elementOrderingIsDeterministic() {
    Resource res1 = runTransformation(parallel=true);
    Resource res2 = runTransformation(parallel=true);
    
    List<String> names1 = res1.getContents().stream()
        .map(e -> ((ENamedElement)e).getName())
        .collect(Collectors.toList());
    
    List<String> names2 = res2.getContents().stream()
        .map(e -> ((ENamedElement)e).getName())
        .collect(Collectors.toList());
    
    assertEquals(names1, names2, "Element order should be identical");
}
```

## Test Scenarios

### Scenario 1: Flat Transformation

Transform N source elements to N target elements with no containment:

```java
@RepeatedTest(50)
void flatTransformationIsDeterministic() {
    // Create 100 source EClasses
    // Transform to target EClasses (no containment)
    // Compare sequential vs parallel
}
```

### Scenario 2: Parent-Child Containment

Transform elements with single-level containment:

```java
@RepeatedTest(50)
void parentChildContainmentIsDeterministic() {
    // Create packages with classes
    // Transform maintaining containment
    // Verify child ordering within parents
}
```

### Scenario 3: Deep Nesting

Transform elements with 3+ levels of containment:

```java
@RepeatedTest(50)
void deepNestingIsDeterministic() {
    // Package -> Class -> Attribute -> Annotation
    // Verify ordering at each level
}
```

### Scenario 4: Mixed Rule Types

Combine primary transformation rules with lazy rules:

```java
@RepeatedTest(50)
void mixedRuleTypesAreDeterministic() {
    // Primary rules create main elements
    // Lazy rules create referenced elements on demand
    // Verify all elements ordered correctly
}
```

### Scenario 5: Large Model

Test with 10,000+ elements to exercise parallel execution:

```java
@Test
@Timeout(120)
void largeModelTransformationIsDeterministic() {
    // Create 10,000 source elements
    // Force parallel execution (above threshold)
    // Compare with sequential execution
}
```

## Test Infrastructure

### Helper Methods

```java
abstract class AbstractDeterminismTest {
    
    protected Resource createSourceModel(int elementCount) {
        // Create source model with specified elements
    }
    
    protected Resource runTransformation(
        Resource source, 
        boolean parallel
    ) {
        // Execute transformation in specified mode
    }
    
    protected byte[] serializeToXmi(Resource resource) {
        // Serialize resource to XMI bytes
    }
    
    protected void assertModelsEqual(
        Resource expected, 
        Resource actual
    ) {
        // Deep structural comparison
    }
    
    protected void assertOrderingEqual(
        Resource res1, 
        Resource res2
    ) {
        // Verify element ordering matches
    }
}
```

### Test Transformation Rules

Create simple test transformation rules for determinism testing:

```java
@TransformationRule(sourceType = EClass.class)
public class TestClassRule {
    
    @Primary
    public EClass transform(EClass source, TransformationContext ctx) {
        EClass target = ctx.createTarget(EClass.class);
        target.setName(source.getName());
        
        // Transform attributes (creates child elements)
        for (EAttribute attr : source.getEAttributes()) {
            EAttribute targetAttr = ctx.equivalent(attr, EAttribute.class);
            target.getEStructuralFeatures().add(targetAttr);
        }
        
        return target;
    }
}
```

## Verification Strategy

### Run Count

Tests should be repeated multiple times to catch intermittent issues:
- Simple tests: `@RepeatedTest(50)`
- Complex tests: `@RepeatedTest(20)`
- Performance tests: Single run with timeout

### Failure Diagnostics

On failure, provide detailed diagnostics:

```java
private void assertXmiEqual(byte[] expected, byte[] actual) {
    if (!Arrays.equals(expected, actual)) {
        // Write both to temp files for manual inspection
        Files.write(Path.of("expected.xmi"), expected);
        Files.write(Path.of("actual.xmi"), actual);
        
        // Find first difference
        int diffIndex = findFirstDifference(expected, actual);
        fail("XMI differs at byte " + diffIndex + 
             ". Files written to expected.xmi and actual.xmi");
    }
}
```

## Integration with Build

Tests will run as part of normal Maven build:
- `mvn test` - Runs all tests including determinism tests
- `mvn verify` - Full build with integration tests

### Timeout Configuration

Configure appropriate timeouts for repeated tests:

```java
@RepeatedTest(50)
@Timeout(value = 5, unit = TimeUnit.SECONDS)
void quickDeterminismTest() { ... }

@Test
@Timeout(value = 120, unit = TimeUnit.SECONDS)
void largeModelDeterminismTest() { ... }
```

## Success Metrics

1. **100% Pass Rate**: All tests pass over 50+ consecutive runs
2. **No Flakiness**: Zero intermittent failures in CI
3. **Coverage**: Tests cover all ordering scenarios (flat, nested, deep)
4. **Performance**: Total test time < 60 seconds

## References

- Parallel transformation spec: `openspec/specs/parallel-transformation/spec.md`
- Requirement: "Deterministic Element Ordering"
- Existing tests: `ConcurrencyStressTest.java`
