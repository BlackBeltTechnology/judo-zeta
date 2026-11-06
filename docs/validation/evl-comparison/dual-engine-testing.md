# Dual-Engine Testing Framework for Validation

**Navigation**: [Documentation Hub](../../index.md) > [Validation](../index.md) > [EVL Comparison](overview.md) > Dual-Engine Testing

This document describes the testing framework for verifying EVL and Zeta validation equivalence, enabling safe migration and ongoing validation of both engines.

## Overview

The dual-engine testing framework enables:
- Running validation tests with both EVL and Java/Zeta engines
- Comparing validation results for consistency
- Performance benchmarking between engines
- Gradual migration with confidence

## Core Components

### ValidationEngine Enum

Create an enum to parameterize tests for both validation engines:

```java
package hu.blackbelt.judo.meta.psm;

/**
 * Validation engine type for parameterized testing.
 */
public enum ValidationEngine {
    /** Use Epsilon EVL validation engine. */
    EVL("EVL"),
    
    /** Use Java/Zeta validation engine. */
    JAVA("Java");

    private final String displayName;

    ValidationEngine(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
```

### ValidationTestCase Class

Define reusable test cases that work with both engines:

```java
/**
 * Test case definition that includes model setup and expected constraint violations.
 */
public class ValidationTestCase {
    final String name;
    final Consumer<PsmModel> modelSetup;
    final Set<String> expectedConstraints;
    final boolean expectPass;

    public ValidationTestCase(String name, Consumer<PsmModel> modelSetup, 
                              Set<String> expectedConstraints) {
        this.name = name;
        this.modelSetup = modelSetup;
        this.expectedConstraints = expectedConstraints;
        this.expectPass = expectedConstraints.isEmpty();
    }

    /**
     * Create a test case that expects validation to pass.
     */
    public static ValidationTestCase passing(String name, Consumer<PsmModel> modelSetup) {
        return new ValidationTestCase(name, modelSetup, Collections.emptySet());
    }

    /**
     * Create a test case that expects specific constraints to fail.
     */
    public static ValidationTestCase failing(String name, Consumer<PsmModel> modelSetup, 
                                             String... expectedConstraints) {
        return new ValidationTestCase(name, modelSetup, 
            new HashSet<>(Arrays.asList(expectedConstraints)));
    }

    @Override
    public String toString() {
        return name;
    }
}
```

---

## Parameterized Test Patterns

### Basic Parameterized Test with @MethodSource

```java
@Slf4j
class PsmValidationParameterizedTest {

    private static final String MODEL_URI = "urn:psm.judo-meta-psm";
    private PsmModel psmModel;

    @BeforeEach
    void setUp() {
        psmModel = PsmModel.buildPsmModel()
                .uri(URI.createURI(MODEL_URI))
                .build();
    }

    /**
     * Run EVL validation and return constraint names that failed.
     */
    private Set<String> runEvlValidation() {
        Set<String> violations = new HashSet<>();
        try (BufferedSlf4jLogger bufferedLog = new BufferedSlf4jLogger(log)) {
            PsmEpsilonValidator.validatePsm(
                bufferedLog,
                psmModel,
                PsmEpsilonValidator.calculatePsmValidationScriptURI(),
                Collections.emptyList(),
                Collections.emptyList()
            );
        } catch (EvlScriptExecutionException ex) {
            // Parse constraint names from error messages
            for (String error : ex.getUnexpectedErrors()) {
                if (error.contains("|")) {
                    violations.add(error.split("\\|")[0].trim());
                }
            }
            for (String warning : ex.getUnexpectedWarnings()) {
                if (warning.contains("|")) {
                    violations.add(warning.split("\\|")[0].trim());
                }
            }
        } catch (Exception ex) {
            log.error("EVL validation failed", ex);
        }
        return violations;
    }

    /**
     * Run Java/Zeta validation and return constraint names that failed.
     */
    private Set<String> runJavaValidation() {
        List<ValidationResult> results = PsmValidator.validate(log, psmModel);
        return results.stream()
                .filter(r -> r.getSeverity() == Severity.ERROR 
                          || r.getSeverity() == Severity.WARNING)
                .map(ValidationResult::getConstraintName)
                .collect(Collectors.toSet());
    }

    /**
     * Provides test cases for both validation engines.
     */
    static Stream<Arguments> validationTestCases() {
        List<ValidationTestCase> testCases = Arrays.asList(
            // Valid model - should pass with no constraint violations
            ValidationTestCase.passing("ValidModel", psmModel -> {
                StringType string = newStringTypeBuilder()
                    .withName("String").withMaxLength(255).build();
                EntityType entity = newEntityTypeBuilder()
                    .withName("Customer").build();
                Model model = newModelBuilder()
                    .withName("M")
                    .withElements(ImmutableList.of(string, entity))
                    .build();
                psmModel.addContent(model);
            }),

            // Empty element name - should fail ElementNameNotEmpty
            ValidationTestCase.failing("EmptyElementName", psmModel -> {
                StringType string = newStringTypeBuilder()
                    .withName("").withMaxLength(255).build();
                Model m = newModelBuilder()
                    .withName("M").withElements(string).build();
                psmModel.addContent(m);
            }, "ElementNameNotEmpty"),

            // Duplicate names - should fail NamedElementIsUniqueInItsContainer
            ValidationTestCase.failing("DuplicateElementNames", psmModel -> {
                StringType string1 = newStringTypeBuilder()
                    .withName("DuplicateName").withMaxLength(255).build();
                StringType string2 = newStringTypeBuilder()
                    .withName("DuplicateName").withMaxLength(100).build();
                Model m = newModelBuilder()
                    .withName("M")
                    .withElements(ImmutableList.of(string1, string2))
                    .build();
                psmModel.addContent(m);
            }, "NamedElementIsUniqueInItsContainer"),

            // Invalid max length - should fail ValidMaxLength
            ValidationTestCase.failing("InvalidMaxLength", psmModel -> {
                StringType string = newStringTypeBuilder()
                    .withName("InvalidString").withMaxLength(0).build();
                Model m = newModelBuilder()
                    .withName("M").withElements(string).build();
                psmModel.addContent(m);
            }, "ValidMaxLength"),

            // Scale >= precision - should fail ScaleIsLowerThanPrecision
            ValidationTestCase.failing("InvalidScale", psmModel -> {
                NumericType numeric = newNumericTypeBuilder()
                    .withName("InvalidNumeric")
                    .withPrecision(5).withScale(5).build();
                Model m = newModelBuilder()
                    .withName("M").withElements(numeric).build();
                psmModel.addContent(m);
            }, "ScaleIsLowerThanPrecision"),

            // Invalid cardinality - should fail CardinalityUpperIsAtLeastOne
            ValidationTestCase.failing("InvalidCardinalityUpper", psmModel -> {
                EntityType target = newEntityTypeBuilder()
                    .withName("Target").build();
                EntityType source = newEntityTypeBuilder()
                    .withName("Source")
                    .withRelations(newAssociationEndBuilder()
                        .withName("relation")
                        .withTarget(target)
                        .withCardinality(newCardinalityBuilder()
                            .withLower(0).withUpper(-2).build())
                        .build())
                    .build();
                Model m = newModelBuilder()
                    .withName("M")
                    .withElements(ImmutableList.of(source, target))
                    .build();
                psmModel.addContent(m);
            }, "CardinalityUpperIsAtLeastOne")
        );

        // Generate test arguments for each combination of test case and engine
        return testCases.stream()
                .flatMap(tc -> Stream.of(
                        Arguments.of(tc, ValidationEngine.EVL),
                        Arguments.of(tc, ValidationEngine.JAVA)
                ));
    }

    @ParameterizedTest(name = "{0} with {1} validation")
    @MethodSource("validationTestCases")
    @DisplayName("Parameterized validation test")
    void testValidation(ValidationTestCase testCase, ValidationEngine engine) {
        log.info("Running test '{}' with {} validation", testCase.name, engine);

        // Setup the model
        testCase.modelSetup.accept(psmModel);

        // Run the appropriate validation engine
        Set<String> violations;
        if (engine == ValidationEngine.EVL) {
            violations = runEvlValidation();
        } else {
            violations = runJavaValidation();
        }

        log.info("  Violations found: {}", violations);
        log.info("  Expected constraints: {}", testCase.expectedConstraints);

        if (testCase.expectPass) {
            assertTrue(violations.isEmpty(),
                    String.format("Expected no violations but got: %s", violations));
        } else {
            for (String expectedConstraint : testCase.expectedConstraints) {
                assertTrue(violations.contains(expectedConstraint),
                        String.format("Expected '%s' to be violated. Found: %s",
                                expectedConstraint, violations));
            }
        }
    }
}
```

---

## Consistency Testing

### EVL-Java Consistency Test

Verify that both engines produce identical results for the same model:

```java
/**
 * Tests that both validation engines produce the same results for identical models.
 */
static Stream<Arguments> consistencyTestCases() {
    return validationTestCases()
            .filter(args -> args.get()[1] == ValidationEngine.EVL)
            .map(args -> Arguments.of(args.get()[0]));
}

@ParameterizedTest(name = "Consistency: {0}")
@MethodSource("consistencyTestCases")
@DisplayName("EVL and Java validation consistency")
void testValidationConsistency(ValidationTestCase testCase) {
    log.info("Testing consistency for '{}'", testCase.name);

    // Setup model for EVL
    testCase.modelSetup.accept(psmModel);
    Set<String> evlViolations = runEvlValidation();

    // Reset and setup model for Java
    psmModel = PsmModel.buildPsmModel()
            .uri(URI.createURI(MODEL_URI))
            .build();
    testCase.modelSetup.accept(psmModel);
    Set<String> javaViolations = runJavaValidation();

    log.info("  EVL violations: {}", evlViolations);
    log.info("  Java violations: {}", javaViolations);

    // Check consistency for expected constraints
    for (String expectedConstraint : testCase.expectedConstraints) {
        boolean evlHas = evlViolations.contains(expectedConstraint);
        boolean javaHas = javaViolations.contains(expectedConstraint);

        assertEquals(evlHas, javaHas,
                String.format("Constraint '%s' inconsistency: EVL=%s, Java=%s",
                        expectedConstraint, evlHas, javaHas));
    }
}
```

### Full Model Comparison

For complete validation result comparison:

```java
@Test
void testFullValidationEquivalence() throws Exception {
    // Generate a complex model
    generateComplexTestModel(psmModel);

    // Run both validations
    Set<String> evlViolations = runEvlValidation();
    
    // Reset model
    psmModel = PsmModel.buildPsmModel()
            .uri(URI.createURI(MODEL_URI))
            .build();
    generateComplexTestModel(psmModel);
    
    Set<String> javaViolations = runJavaValidation();

    // Compare all violations
    Set<String> evlOnly = new HashSet<>(evlViolations);
    evlOnly.removeAll(javaViolations);

    Set<String> javaOnly = new HashSet<>(javaViolations);
    javaOnly.removeAll(evlViolations);

    if (!evlOnly.isEmpty() || !javaOnly.isEmpty()) {
        log.error("Validation inconsistency detected!");
        log.error("  EVL only: {}", evlOnly);
        log.error("  Java only: {}", javaOnly);
        fail("Validation engines produced different results");
    }

    log.info("Both engines found {} violations", evlViolations.size());
}
```

---

## Performance Testing

### Comprehensive Performance Test

```java
@Slf4j
class PsmValidationPerformanceTest {

    private static final int ENTITY_COUNT = 100;
    private static final int ATTRIBUTES_PER_ENTITY = 5;
    private static final int RELATIONS_PER_ENTITY = 3;
    private static final int WARMUP_ITERATIONS = 3;
    private static final int TEST_ITERATIONS = 5;

    private PsmModel psmModel;

    @BeforeEach
    void setUp() {
        psmModel = PsmModel.buildPsmModel()
                .uri(URI.createURI("urn:psm.judo-meta-psm"))
                .build();
    }

    /**
     * Generate a model with approximately 1000 elements.
     * 
     * Creates:
     * - 100 EntityTypes
     * - 500 Attributes (5 per entity)
     * - 300 Relations (3 per entity)
     * - 2 primitive types
     * - 10 packages
     */
    private void generateLargeModel() {
        StringType stringType = newStringTypeBuilder()
                .withName("String").withMaxLength(255).build();
        NumericType integerType = newNumericTypeBuilder()
                .withName("Integer").withPrecision(10).withScale(0).build();

        List<EntityType> entities = new ArrayList<>();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            List<Attribute> attributes = new ArrayList<>();
            for (int j = 0; j < ATTRIBUTES_PER_ENTITY; j++) {
                Attribute attr = newAttributeBuilder()
                        .withName("attr_" + i + "_" + j)
                        .withDataType(j % 2 == 0 ? stringType : integerType)
                        .withRequired(j == 0)
                        .build();
                attributes.add(attr);
            }

            EntityType entity = newEntityTypeBuilder()
                    .withName("Entity" + i)
                    .withAttributes(attributes)
                    .build();
            entities.add(entity);
        }

        // Create relations between entities
        for (int i = 0; i < ENTITY_COUNT; i++) {
            EntityType source = entities.get(i);
            List<AssociationEnd> relations = new ArrayList<>();

            for (int j = 0; j < RELATIONS_PER_ENTITY; j++) {
                int targetIdx = (i + j + 1) % ENTITY_COUNT;
                EntityType target = entities.get(targetIdx);

                AssociationEnd relation = newAssociationEndBuilder()
                        .withName("rel_" + i + "_" + j)
                        .withTarget(target)
                        .withCardinality(newCardinalityBuilder()
                                .withLower(0)
                                .withUpper(j == 0 ? 1 : -1)
                                .build())
                        .build();
                relations.add(relation);
            }
            source.getRelations().addAll(relations);
        }

        // Organize into packages
        List<Package> packages = new ArrayList<>();
        int entitiesPerPackage = ENTITY_COUNT / 10;
        for (int i = 0; i < 10; i++) {
            List<EntityType> packageEntities = entities.subList(
                    i * entitiesPerPackage,
                    (i + 1) * entitiesPerPackage
            );
            Package pkg = newPackageBuilder()
                    .withName("package" + i)
                    .withElements(new ArrayList<>(packageEntities))
                    .build();
            packages.add(pkg);
        }

        Model model = newModelBuilder()
                .withName("PerformanceTestModel")
                .withElements(ImmutableList.of(stringType, integerType))
                .withPackages(packages)
                .build();

        psmModel.addContent(model);

        log.info("Generated model with ~{} elements", 
            ENTITY_COUNT + ENTITY_COUNT * ATTRIBUTES_PER_ENTITY 
            + ENTITY_COUNT * RELATIONS_PER_ENTITY + 12);
    }

    @Test
    void testPerformanceComparison() throws Exception {
        log.info("=== Validation Performance Test ===");

        generateLargeModel();
        assertTrue(psmModel.isValid(), "Generated model should be valid");

        // Warmup phase
        log.info("\n--- Warmup Phase ({} iterations) ---", WARMUP_ITERATIONS);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runJavaValidation();
            runEvlValidation();
        }

        // Test phase
        log.info("\n--- Test Phase ({} iterations) ---", TEST_ITERATIONS);

        List<Long> javaTimings = new ArrayList<>();
        List<Long> evlTimings = new ArrayList<>();

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            log.info("\nIteration {}", i + 1);

            // Java validation
            long javaStart = System.nanoTime();
            List<ValidationResult> javaResults = runJavaValidation();
            long javaTime = (System.nanoTime() - javaStart) / 1_000_000;
            javaTimings.add(javaTime);
            log.info("  Java: {} ms ({} results)", javaTime, javaResults.size());

            // EVL validation
            long evlStart = System.nanoTime();
            runEvlValidation();
            long evlTime = (System.nanoTime() - evlStart) / 1_000_000;
            evlTimings.add(evlTime);
            log.info("  EVL:  {} ms", evlTime);
        }

        // Calculate statistics
        double javaAvg = javaTimings.stream()
            .mapToLong(Long::longValue).average().orElse(0);
        double evlAvg = evlTimings.stream()
            .mapToLong(Long::longValue).average().orElse(0);
        long javaMin = javaTimings.stream()
            .mapToLong(Long::longValue).min().orElse(0);
        long javaMax = javaTimings.stream()
            .mapToLong(Long::longValue).max().orElse(0);
        long evlMin = evlTimings.stream()
            .mapToLong(Long::longValue).min().orElse(0);
        long evlMax = evlTimings.stream()
            .mapToLong(Long::longValue).max().orElse(0);
        double speedup = evlAvg / javaAvg;

        // Print formatted results
        log.info("\n=== Performance Results ===");
        log.info("+----------------------+------------+------------+------------+");
        log.info("| Validation Engine    | Avg (ms)   | Min (ms)   | Max (ms)   |");
        log.info("+----------------------+------------+------------+------------+");
        log.info(String.format("| Java/Zeta            | %10.2f | %10d | %10d |", 
            javaAvg, javaMin, javaMax));
        log.info(String.format("| EVL                  | %10.2f | %10d | %10d |", 
            evlAvg, evlMin, evlMax));
        log.info("+----------------------+------------+------------+------------+");
        log.info(String.format("| Speedup              | %10.2fx |            |            |", 
            speedup));
        log.info("+----------------------+------------+------------+------------+");
    }

    private List<ValidationResult> runJavaValidation() {
        return PsmValidator.validate(log, psmModel);
    }

    private void runEvlValidation() throws Exception {
        try (BufferedSlf4jLogger bufferedLog = new BufferedSlf4jLogger(log)) {
            PsmEpsilonValidator.validatePsm(
                    bufferedLog,
                    psmModel,
                    PsmEpsilonValidator.calculatePsmValidationScriptURI(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }
    }
}
```

### Running Performance Tests

```bash
# Run with performance tag
mvn test -Dtest=PsmValidationPerformanceTest -Dgroups=performance

# Run with increased heap for large models
mvn test -Dtest=PsmValidationPerformanceTest -DargLine="-Xmx2g"
```

---

## Test Organization

Recommended test directory structure:

```
src/test/java/hu/blackbelt/judo/meta/<module>/
├── ValidationEngine.java              # Enum for parameterized tests
├── ValidationTestCase.java            # Reusable test case class
├── <Module>ValidationTest.java        # Basic validation tests
├── <Module>ValidationParameterizedTest.java  # Dual-engine tests
├── <Module>ValidationConsistencyTest.java    # Consistency tests
├── <Module>ValidationPerformanceTest.java    # Performance benchmarks
└── <Module>JavaValidationTest.java    # Java-only validation tests
```

---

## Best Practices

### 1. Use Fresh Models for Each Engine

Always create fresh models to avoid state contamination:

```java
// Run EVL validation
testCase.modelSetup.accept(psmModel);
Set<String> evlViolations = runEvlValidation();

// Reset model for Java validation
psmModel = PsmModel.buildPsmModel()
        .uri(URI.createURI(MODEL_URI))
        .build();
testCase.modelSetup.accept(psmModel);
Set<String> javaViolations = runJavaValidation();
```

### 2. Test Both Passing and Failing Cases

```java
List<ValidationTestCase> testCases = Arrays.asList(
    // Passing case
    ValidationTestCase.passing("ValidModel", psmModel -> {
        // Setup valid model
    }),

    // Failing case with expected constraint
    ValidationTestCase.failing("InvalidModel", psmModel -> {
        // Setup invalid model
    }, "ExpectedConstraintName")
);
```

### 3. Use Meaningful Test Names

```java
@ParameterizedTest(name = "{0} with {1} validation")
@MethodSource("validationTestCases")
void testValidation(ValidationTestCase testCase, ValidationEngine engine) {
    // Test displays as: "EmptyElementName with EVL validation"
}
```

### 4. Clear Logging for Debugging

```java
log.info("Running test '{}' with {} validation", testCase.name, engine);
log.info("  Violations found: {}", violations);
log.info("  Expected constraints: {}", testCase.expectedConstraints);
```

### 5. Handle Expected Errors vs Warnings

```java
private void runEvlValidation(Collection<String> expectedErrors, 
                              Collection<String> expectedWarnings) throws Exception {
    try (BufferedSlf4jLogger bufferedLog = new BufferedSlf4jLogger(log)) {
        PsmEpsilonValidator.validatePsm(bufferedLog, psmModel,
                validationScriptURI,
                expectedErrors,
                expectedWarnings);
    } catch (EvlScriptExecutionException ex) {
        log.error("EVL failed");
        log.error("  expected errors: {}", expectedErrors);
        log.error("  unexpected errors: {}", ex.getUnexpectedErrors());
        log.error("  errors not found: {}", ex.getErrorsNotFound());
        log.error("  expected warnings: {}", expectedWarnings);
        log.error("  unexpected warnings: {}", ex.getUnexpectedWarnings());
        log.error("  warnings not found: {}", ex.getWarningsNotFound());
        throw ex;
    }
}
```

---

## Configuration Properties

Control test behavior via system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `validation.test.engine` | `both` | Which engine(s) to test: `evl`, `java`, `both` |
| `validation.test.iterations` | `5` | Number of test iterations for performance |
| `validation.test.warmup` | `3` | Warmup iterations before measurement |
| `validation.test.parallel` | `true` | Enable parallel validation in Java engine |

### Maven Configuration

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <validation.test.engine>both</validation.test.engine>
            <validation.test.iterations>5</validation.test.iterations>
        </systemPropertyVariables>
        <argLine>-Xmx1g</argLine>
    </configuration>
</plugin>
```

---

## Troubleshooting

### Tests Pass for EVL but Fail for Java

1. **Check constraint name spelling** - Must match exactly
2. **Verify guard conditions** - Java guards may behave differently
3. **Check satisfies dependencies** - Ordering may differ
4. **Compare error message format** - EVL uses `ConstraintName|message` format

### Tests Pass for Java but Fail for EVL

1. **Check EVL file location** - Ensure `.evl` files are accessible
2. **Verify metamodel registration** - EMF packages must be registered
3. **Check EOL helpers** - All helper operations must be available

### Performance Tests Show Large Variance

1. **Increase warmup iterations** - JIT needs time to optimize
2. **Run in isolation** - Other tests may affect results
3. **Check GC pauses** - Increase heap size if needed
4. **Ensure consistent model size** - Use the same generator

### Constraint Name Parsing Fails

EVL error format is `ConstraintName|message`. Parse correctly:

```java
for (String error : ex.getUnexpectedErrors()) {
    if (error.contains("|")) {
        String constraintName = error.split("\\|")[0].trim();
        violations.add(constraintName);
    }
}
```

---

## Related Documentation

- [EVL to Zeta Migration Guide](migration-guide.md) - Step-by-step migration
- [Syntax Mapping](syntax-mapping.md) - EVL to Zeta syntax reference
- [Feature Parity](feature-parity.md) - What's supported
- [Validation Rules](../user-guide/validation-rules.md) - Writing rules
- [Best Practices](../best-practices/performance.md) - Performance optimization

---

**Previous**: [Migration Guide](migration-guide.md) | **Next**: [Feature Parity](feature-parity.md)
