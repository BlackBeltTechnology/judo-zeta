# Dual-Engine Testing Framework

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Dual-Engine Testing

This document describes the testing framework for verifying ETL and Zeta transformation equivalence, enabling safe migration and ongoing validation.

## Overview

The dual-engine testing framework enables:
- Running tests with both ETL and Zeta engines
- Comparing output models for structural equivalence
- Performance benchmarking between engines
- Gradual migration with confidence

## Core Components

### TransformationType Enum

Create an enum to parameterize tests for both engines:

```java
package hu.blackbelt.judo.tatami.psm2asm;

/**
 * Transformation engine type for parameterized testing.
 */
public enum TransformationType {
    /** Use Epsilon ETL transformation engine. */
    ETL,

    /** Use Zeta Java transformation engine. */
    ZETA
}
```

**Location**: `src/test/java/hu/blackbelt/judo/tatami/<module>/TransformationType.java`

### ModelComparator

Order-independent EMF model comparison utility for validating that both engines produce equivalent output:

```java
package hu.blackbelt.judo.tatami.test.util;

import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import java.util.*;

/**
 * Compares EMF models for structural equivalence.
 */
public class ModelComparator {
    
    public enum ComparisonMode {
        /** All attributes and references must match exactly */
        STRICT,
        /** Structure must match, annotation differences tolerated */
        STRUCTURAL,
        /** Major elements must match, minor differences allowed */
        LENIENT
    }
    
    /**
     * Assert two resources are equivalent.
     */
    public static void assertEquivalent(Resource expected, Resource actual) {
        assertEquivalent(expected, actual, ComparisonMode.STRUCTURAL);
    }
    
    /**
     * Assert two resources are equivalent with specified comparison mode.
     */
    public static void assertEquivalent(
            Resource expected, 
            Resource actual,
            ComparisonMode mode) {
        ComparisonResult result = compare(
            expected.getContents().get(0),
            actual.getContents().get(0),
            mode
        );
        
        if (!result.isEquivalent()) {
            throw new AssertionError(
                "Models are not equivalent:\n" + result.getDetailedReport()
            );
        }
    }
    
    /**
     * Compare two root elements and return detailed result.
     */
    public static ComparisonResult compare(
            EObject expected, 
            EObject actual,
            ComparisonMode mode) {
        ComparisonContext ctx = new ComparisonContext(mode);
        compareRecursive(expected, actual, "", ctx);
        return ctx.getResult();
    }
    
    private static void compareRecursive(
            EObject expected, 
            EObject actual, 
            String path,
            ComparisonContext ctx) {
        // Compare EClass
        if (!expected.eClass().equals(actual.eClass())) {
            ctx.addDifference(new TypeMismatch(path, 
                expected.eClass().getName(), 
                actual.eClass().getName()));
            return;
        }
        
        // Compare attributes
        for (EAttribute attr : expected.eClass().getEAllAttributes()) {
            Object expectedValue = expected.eGet(attr);
            Object actualValue = actual.eGet(attr);
            
            if (!Objects.equals(expectedValue, actualValue)) {
                ctx.addDifference(new ValueMismatch(
                    path + "." + attr.getName(),
                    String.valueOf(expectedValue),
                    String.valueOf(actualValue)
                ));
            }
        }
        
        // Compare references
        for (EReference ref : expected.eClass().getEAllContainments()) {
            Object expectedRef = expected.eGet(ref);
            Object actualRef = actual.eGet(ref);
            
            if (ref.isMany()) {
                compareList(
                    (List<EObject>) expectedRef, 
                    (List<EObject>) actualRef,
                    path + "." + ref.getName(),
                    ctx
                );
            } else {
                if (expectedRef != null && actualRef != null) {
                    compareRecursive(
                        (EObject) expectedRef, 
                        (EObject) actualRef,
                        path + "." + ref.getName(),
                        ctx
                    );
                } else if (expectedRef != null || actualRef != null) {
                    ctx.addDifference(new ValueMismatch(
                        path + "." + ref.getName(),
                        expectedRef != null ? "present" : "null",
                        actualRef != null ? "present" : "null"
                    ));
                }
            }
        }
    }
    
    private static void compareList(
            List<EObject> expected, 
            List<EObject> actual,
            String path,
            ComparisonContext ctx) {
        // Build maps by identifying feature (usually name or ID)
        Map<String, EObject> expectedMap = buildIdentityMap(expected);
        Map<String, EObject> actualMap = buildIdentityMap(actual);
        
        // Find missing elements
        for (String key : expectedMap.keySet()) {
            if (!actualMap.containsKey(key)) {
                ctx.addDifference(new MissingElement(path, key));
            }
        }
        
        // Find extra elements
        for (String key : actualMap.keySet()) {
            if (!expectedMap.containsKey(key)) {
                ctx.addDifference(new ExtraElement(path, key));
            }
        }
        
        // Compare matching elements
        for (String key : expectedMap.keySet()) {
            if (actualMap.containsKey(key)) {
                compareRecursive(
                    expectedMap.get(key),
                    actualMap.get(key),
                    path + "[" + key + "]",
                    ctx
                );
            }
        }
    }
    
    private static Map<String, EObject> buildIdentityMap(List<EObject> elements) {
        Map<String, EObject> map = new LinkedHashMap<>();
        int index = 0;
        for (EObject element : elements) {
            String key = getIdentity(element, index++);
            map.put(key, element);
        }
        return map;
    }
    
    private static String getIdentity(EObject element, int index) {
        // Try to find name attribute
        EStructuralFeature nameFeature = element.eClass()
            .getEStructuralFeature("name");
        if (nameFeature != null) {
            Object name = element.eGet(nameFeature);
            if (name != null) {
                return element.eClass().getName() + ":" + name;
            }
        }
        // Fall back to type + index
        return element.eClass().getName() + "#" + index;
    }
}
```

### ComparisonResult

```java
public class ComparisonResult {
    private final List<Difference> differences = new ArrayList<>();
    private final int maxDifferences;
    
    public ComparisonResult(int maxDifferences) {
        this.maxDifferences = maxDifferences;
    }
    
    public boolean isEquivalent() {
        return differences.isEmpty();
    }
    
    public int getDifferenceCount() {
        return differences.size();
    }
    
    public List<Difference> getDifferenceList() {
        return Collections.unmodifiableList(differences);
    }
    
    public String getDetailedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(differences.size()).append(" differences:\n");
        
        int shown = Math.min(differences.size(), maxDifferences);
        for (int i = 0; i < shown; i++) {
            sb.append("  ").append(i + 1).append(". ");
            sb.append(differences.get(i).describe()).append("\n");
        }
        
        if (differences.size() > maxDifferences) {
            sb.append("  ... and ")
              .append(differences.size() - maxDifferences)
              .append(" more differences\n");
        }
        
        return sb.toString();
    }
    
    void addDifference(Difference diff) {
        differences.add(diff);
    }
}
```

### Difference Types

```java
public sealed interface Difference permits 
        MissingElement, ExtraElement, ValueMismatch, TypeMismatch {
    String describe();
}

public record MissingElement(String path, String elementKey) implements Difference {
    @Override
    public String describe() {
        return "Missing element at " + path + ": " + elementKey;
    }
}

public record ExtraElement(String path, String elementKey) implements Difference {
    @Override
    public String describe() {
        return "Unexpected element at " + path + ": " + elementKey;
    }
}

public record ValueMismatch(String path, String expected, String actual) 
        implements Difference {
    @Override
    public String describe() {
        return "Value mismatch at " + path + ": expected '" + expected 
            + "' but was '" + actual + "'";
    }
}

public record TypeMismatch(String path, String expectedType, String actualType) 
        implements Difference {
    @Override
    public String describe() {
        return "Type mismatch at " + path + ": expected " + expectedType 
            + " but was " + actualType;
    }
}
```

---

## Parameterized Test Patterns

### Basic Parameterized Test

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class Psm2AsmTransformationTest {

    @ParameterizedTest(name = "{0}: Basic entity transformation")
    @EnumSource(TransformationType.class)
    void testBasicEntityTransformation(TransformationType type) throws Exception {
        // Setup source model
        PsmModel psmModel = createTestModel();
        AsmModel asmModel = AsmModel.buildAsmModel()
            .name("test")
            .build();

        // Execute transformation based on type
        if (type == TransformationType.ZETA) {
            Psm2AsmZetaTransformation.builder()
                .psmModel(psmModel)
                .asmModel(asmModel)
                .build()
                .execute();
        } else {
            Psm2AsmEtlTransformation.builder()
                .psmModel(psmModel)
                .asmModel(asmModel)
                .build()
                .execute();
        }

        // Verify results
        assertNotNull(asmModel.getResource());
        assertFalse(asmModel.getResource().getContents().isEmpty());
        
        EPackage rootPkg = (EPackage) asmModel.getResource().getContents().get(0);
        assertEquals("TestModel", rootPkg.getName());
    }
}
```

### Using Work Classes

For projects with Work-based transformation orchestration:

```java
@ParameterizedTest(name = "{0}: Transformation via Work class")
@EnumSource(TransformationType.class)
void testWithWorkClass(TransformationType type) throws Exception {
    // Create transformation context
    TransformationContext context = new TransformationContext("TestModel");
    context.put(psmModel);
    
    // Configure work parameters
    context.put(Psm2AsmWork.Psm2AsmWorkParameter.psm2AsmWorkParameter()
            .transformationMode(type == TransformationType.ZETA
                ? TransformationMode.ZETA
                : TransformationMode.ETL)
            .createTrace(true)
            .build());

    // Execute work
    Psm2AsmWork work = new Psm2AsmWork(context);
    work.execute();

    // Get result
    AsmModel result = context.getByClass(AsmModel.class)
            .orElseThrow(() -> new IllegalStateException("ASM Model not found"));

    // Verify
    verifyResult(result);
}
```

### ETL-Zeta Equivalence Test

The most important test - verify both engines produce identical output:

```java
@Test
void testEtlZetaEquivalence() throws Exception {
    // Setup - create identical source models
    PsmModel psmModelForEtl = createTestModel();
    PsmModel psmModelForZeta = createTestModel();  // Fresh copy
    
    AsmModel etlResult = AsmModel.buildAsmModel().name("etl").build();
    AsmModel zetaResult = AsmModel.buildAsmModel().name("zeta").build();

    // Execute ETL transformation
    Psm2AsmEtlTransformation.builder()
        .psmModel(psmModelForEtl)
        .asmModel(etlResult)
        .build()
        .execute();

    // Execute Zeta transformation
    Psm2AsmZetaTransformation.builder()
        .psmModel(psmModelForZeta)
        .asmModel(zetaResult)
        .build()
        .execute();

    // Compare results
    if (!etlResult.getResource().getContents().isEmpty() &&
        !zetaResult.getResource().getContents().isEmpty()) {

        EObject etlRoot = etlResult.getResource().getContents().get(0);
        EObject zetaRoot = zetaResult.getResource().getContents().get(0);

        ComparisonResult result = ModelComparator.compare(
            etlRoot, zetaRoot, ComparisonMode.STRUCTURAL);

        assertTrue(result.isEquivalent(),
            "ETL and Zeta outputs differ:\n" + result.getDetailedReport());
    }
}
```

---

## Comparison Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `STRICT` | All attributes and references must match exactly | Final validation, release testing |
| `STRUCTURAL` | Element structure must match, annotation differences tolerated | Development, iterative migration |
| `LENIENT` | Major structural elements must match, minor differences allowed | Initial migration, exploratory testing |

### When to Use Each Mode

**STRICT** - Use for:
- Final release validation
- Regression testing
- Production deployment verification

**STRUCTURAL** - Use for:
- Active development
- Iterating on migration
- When annotations differ but structure is correct

**LENIENT** - Use for:
- Initial migration attempts
- Exploring transformation differences
- When you expect minor variations

---

## Configuration Properties

Control comparison behavior via system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `judo.test.comparison.enabled` | `true` | Enable/disable comparison |
| `judo.test.comparison.mode` | `STRUCTURAL` | Comparison strictness |
| `judo.test.comparison.maxDifferences` | `50` | Max differences to report |
| `judo.test.comparison.reportFile` | `null` | Output file for diff report |

### Setting Properties

**Via command line:**
```bash
mvn test -Djudo.test.comparison.mode=STRICT
```

**Via surefire plugin:**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <judo.test.comparison.mode>STRUCTURAL</judo.test.comparison.mode>
            <judo.test.comparison.maxDifferences>100</judo.test.comparison.maxDifferences>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

---

## Performance Testing

### RealisticPerformanceTest Pattern

```java
@Slf4j
@Tag("performance")
public class Psm2AsmPerformanceTest {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASUREMENT_ITERATIONS = 5;

    @Test
    void testSmallModelPerformance() throws Exception {
        runPerformanceTest(10, "Small (10 entities)");
    }
    
    @Test
    void testMediumModelPerformance() throws Exception {
        runPerformanceTest(50, "Medium (50 entities)");
    }
    
    @Test
    void testLargeModelPerformance() throws Exception {
        runPerformanceTest(200, "Large (200 entities)");
    }
    
    @Test
    void testRealisticModelPerformance() throws Exception {
        runPerformanceTest(70, "Realistic (~70 entities)");
    }

    private void runPerformanceTest(int entityCount, String testName) throws Exception {
        log.info("Running performance test: {}", testName);
        
        // Generate model
        PsmModel psmModel = RealisticPsmModelGenerator.generate(entityCount);
        
        // Warmup both engines
        log.info("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            executeEtl(createFreshModel(psmModel));
            executeZeta(createFreshModel(psmModel));
        }
        
        // Measure ETL
        List<Long> etlTimes = new ArrayList<>();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            executeEtl(createFreshModel(psmModel));
            etlTimes.add(System.nanoTime() - start);
        }
        
        // Measure Zeta
        List<Long> zetaTimes = new ArrayList<>();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            executeZeta(createFreshModel(psmModel));
            zetaTimes.add(System.nanoTime() - start);
        }
        
        // Calculate statistics
        double etlAvg = etlTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double zetaAvg = zetaTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double speedup = etlAvg / zetaAvg;
        
        // Report
        log.info("=== {} Performance Results ===", testName);
        log.info("ETL average:  {:.2f}ms", etlAvg);
        log.info("Zeta average: {:.2f}ms", zetaAvg);
        log.info("Speedup: {:.2f}x", speedup);
        
        // Verify equivalence
        AsmModel etlResult = executeEtl(createFreshModel(psmModel));
        AsmModel zetaResult = executeZeta(createFreshModel(psmModel));
        ModelComparator.assertEquivalent(
            etlResult.getResource(), 
            zetaResult.getResource()
        );
    }
    
    private AsmModel executeEtl(PsmModel psmModel) throws Exception {
        AsmModel asmModel = AsmModel.buildAsmModel().name("etl").build();
        Psm2AsmEtlTransformation.builder()
            .psmModel(psmModel)
            .asmModel(asmModel)
            .build()
            .execute();
        return asmModel;
    }
    
    private AsmModel executeZeta(PsmModel psmModel) throws Exception {
        AsmModel asmModel = AsmModel.buildAsmModel().name("zeta").build();
        Psm2AsmZetaTransformation.builder()
            .psmModel(psmModel)
            .asmModel(asmModel)
            .build()
            .execute();
        return asmModel;
    }
}
```

### Running Performance Tests

```bash
# Run with performance profile
mvn test -pl judo-tatami-psm2asm \
    -Dtest=Psm2AsmPerformanceTest \
    -Pperformance

# Run all performance tests
mvn test -Pperformance -Dgroups=performance
```

### Performance Test Maven Profile

```xml
<profile>
    <id>performance</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <groups>performance</groups>
                    <systemPropertyVariables>
                        <judo.test.performance>true</judo.test.performance>
                    </systemPropertyVariables>
                    <argLine>-Xmx2g</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

---

## Test Organization

Recommended test directory structure:

```
src/test/java/hu/blackbelt/judo/tatami/<module>/
├── TransformationType.java           # Enum for parameterized tests
├── <Module>TransformationTest.java   # Main transformation tests
├── <Module>WorkTest.java             # Work class tests
├── <Module>EquivalenceTest.java      # ETL-Zeta equivalence tests
├── rules/                            # Rule-specific tests
│   ├── NamespaceRulesTest.java
│   ├── TypeRulesTest.java
│   └── ...
└── perf/
    ├── <Module>PerformanceTest.java  # Performance benchmarks
    └── RealisticModelGenerator.java  # Test model generator
```

---

## Best Practices

### 1. Use Fresh Models for Each Engine

Always create fresh source models for each engine to avoid state contamination:

```java
// GOOD: Fresh models for each engine
PsmModel psmModelForEtl = createTestModel();
AsmModel etlResult = runEtl(psmModelForEtl);

PsmModel psmModelForZeta = createTestModel();  // New instance
AsmModel zetaResult = runZeta(psmModelForZeta);

// BAD: Reusing the same source model
PsmModel psmModel = createTestModel();
AsmModel etlResult = runEtl(psmModel);
AsmModel zetaResult = runZeta(psmModel);  // May have side effects!
```

### 2. Clear Caches Between Runs

```java
@BeforeEach
void setup() {
    // Clear any static caches
    Psm2AsmHelper.clearCaches();
    IdHelper.clearCache();
}
```

### 3. Use Meaningful Test Names

```java
@ParameterizedTest(name = "{0}: Entity with single inheritance")
@EnumSource(TransformationType.class)
void testEntityWithSingleInheritance(TransformationType type) { ... }

@ParameterizedTest(name = "{0}: Entity with multiple interfaces")
@EnumSource(TransformationType.class)
void testEntityWithMultipleInterfaces(TransformationType type) { ... }
```

### 4. Report Differences Clearly

```java
ComparisonResult result = ModelComparator.compare(etlRoot, zetaRoot);

if (!result.isEquivalent()) {
    StringBuilder sb = new StringBuilder();
    sb.append("Found ").append(result.getDifferenceCount()).append(" differences:\n\n");
    
    for (Difference diff : result.getDifferenceList()) {
        sb.append("  - ").append(diff.describe()).append("\n");
    }
    
    // Also save to file for detailed analysis
    Files.writeString(
        Path.of("target/comparison-report.txt"), 
        sb.toString()
    );
    
    fail(sb.toString());
}
```

### 5. Test Edge Cases Separately

```java
@ParameterizedTest
@EnumSource(TransformationType.class)
void testEmptyModel(TransformationType type) { ... }

@ParameterizedTest
@EnumSource(TransformationType.class)
void testModelWithOnlyPackages(TransformationType type) { ... }

@ParameterizedTest
@EnumSource(TransformationType.class)
void testCircularReferences(TransformationType type) { ... }

@ParameterizedTest
@EnumSource(TransformationType.class)
void testDeepInheritanceHierarchy(TransformationType type) { ... }
```

---

## Troubleshooting

### Tests Pass for ETL but Fail for Zeta

1. **Check rule registration order** - Dependencies may not be met
2. **Verify post-processing handles all cross-references** - Bidirectional refs, etc.
3. **Compare generated XMI IDs** - IDs may be formatted differently
4. **Check for null handling differences** - ETL may handle nulls differently

### Comparison Reports False Positives

1. **Use STRUCTURAL mode** during development
2. **Verify element ordering** doesn't affect logic
3. **Check for annotation differences** - Tolerated in STRUCTURAL mode
4. **Verify ID format matches** - May need normalization

### Performance Tests Show Large Variance

1. **Increase warmup iterations** - JIT compilation needs time
2. **Run in isolation** - No other tests running
3. **Use consistent model sizes** - Ensure fair comparison
4. **Check for GC pauses** - Increase heap if needed

### Memory Issues During Testing

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-Xmx2g -XX:+UseG1GC</argLine>
    </configuration>
</plugin>
```

---

## Difference Types Reference

The ModelComparator reports these difference types:

| Type | Description | Example |
|------|-------------|---------|
| `MissingElement` | Element in expected but not actual | `Missing at .classifiers: EClass:Customer` |
| `ExtraElement` | Element in actual but not expected | `Unexpected at .classifiers: EClass:ExtraClass` |
| `ValueMismatch` | Attribute values differ | `.name: expected 'Customer' but was 'customer'` |
| `TypeMismatch` | Element types differ | `Expected EClass but was EDataType` |

---

**Previous**: [Migration Guide](migration-guide.md) | **Next**: [Feature Parity](feature-parity.md)
