# Design: Add JUnit Tests for Validation Framework

**Change ID**: `add-validation-junit-tests`

## Overview

This document describes the architectural approach and design decisions for implementing a comprehensive JUnit test suite for the validation-core module.

## Design Principles

1. **Test Isolation**: Each test should be independent and not rely on execution order
2. **Readability**: Tests should serve as documentation for framework usage
3. **Maintainability**: Tests should be easy to update when framework changes
4. **Performance**: Test suite should execute quickly (target < 30 seconds total)
5. **Coverage**: Focus on public API contracts, not implementation details

## Architecture

### Test Structure

```
validation-core/
├── src/main/java/
│   └── hu/blackbelt/judo/meta/validation/
│       ├── annotation/      # Validation annotations
│       ├── core/            # Core framework classes
│       └── util/            # Utility classes
└── src/test/java/
    └── hu/blackbelt/judo/meta/validation/
        ├── AbstractValidationTest.java         # Base test class
        ├── TestModelFactory.java                # ECore test model factory
        ├── annotation/
        │   └── AnnotationProcessingTest.java   # Annotation tests
        ├── core/
        │   ├── ValidationRegistryTest.java
        │   ├── ValidationExecutorTest.java
        │   ├── ValidationContextTest.java
        │   ├── ValidatorDescriptorTest.java
        │   ├── ValidationResultTest.java
        │   ├── CacheKeyBuilderTest.java
        │   └── ExtensionMethodRegistryTest.java
        ├── util/
        │   └── EolStyleCollectionsTest.java     # If needed
        └── integration/
            └── ValidationFrameworkIntegrationTest.java
```

### Test Base Class Design

The `AbstractValidationTest` provides common setup for all tests:

```java
public abstract class AbstractValidationTest {
    
    protected ResourceSet resourceSet;
    protected Resource testResource;
    protected ValidationRegistry registry;
    protected ExtensionMethodRegistry extensionRegistry;
    protected ValidationContext context;
    
    @BeforeEach
    void setUp() {
        // Initialize EMF resource set
        resourceSet = new ResourceSetImpl();
        testResource = resourceSet.createResource(
            URI.createURI("test://test-model")
        );
        
        // Initialize validation components
        registry = new ValidationRegistry();
        extensionRegistry = new ExtensionMethodRegistry();
        context = new ValidationContext(
            new TestModelProvider(),
            resourceSet,
            extensionRegistry
        );
        context.setValidationRegistry(registry);
    }
    
    @AfterEach
    void tearDown() {
        // Clean up resources
        if (testResource != null) {
            testResource.getContents().clear();
        }
        resourceSet = null;
        context = null;
    }
    
    protected <T extends EObject> T addToModel(T element) {
        testResource.getContents().add(element);
        return element;
    }
}
```

### Test Model Factory Design

**IMPORTANT**: We use the actual ECore metamodel (EClass, EPackage, EAttribute, etc.) that is already available in the project. The ECore metamodel is the base model that describes EMF models, including ESM.

The `TestModelFactory` provides convenient methods for creating test ECore metamodel elements:

```java
public class TestModelFactory {
    
    private static final EcoreFactory ECORE = EcoreFactory.eINSTANCE;
    private static final EcorePackage ECORE_PKG = EcorePackage.eINSTANCE;
    
    /**
     * Create an EClass instance (represents a class in a metamodel).
     */
    public static EClass createEClass(String name) {
        EClass eClass = ECORE.createEClass();
        eClass.setName(name);
        return eClass;
    }
    
    /**
     * Create an EAttribute instance (represents an attribute/property).
     */
    public static EAttribute createEAttribute(
        String name, 
        EDataType type
    ) {
        EAttribute attr = ECORE.createEAttribute();
        attr.setName(name);
        attr.setEType(type);
        return attr;
    }
    
    /**
     * Create an EPackage instance (represents a namespace/package).
     */
    public static EPackage createEPackage(
        String name, 
        String nsURI
    ) {
        EPackage pkg = ECORE.createEPackage();
        pkg.setName(name);
        pkg.setNsURI(nsURI);
        pkg.setNsPrefix(name);
        return pkg;
    }
    
    /**
     * Create an EReference instance (represents a reference to another class).
     */
    public static EReference createEReference(
        String name,
        EClass type
    ) {
        EReference ref = ECORE.createEReference();
        ref.setName(name);
        ref.setEType(type);
        return ref;
    }
    
    /**
     * Create a simple test model with package, class, and attribute.
     */
    public static EPackage createSimpleTestModel() {
        EPackage pkg = createEPackage("testmodel", "http://test.model");
        EClass personClass = createEClass("Person");
        EAttribute nameAttr = createEAttribute("name", ECORE_PKG.getEString());
        
        personClass.getEStructuralFeatures().add(nameAttr);
        pkg.getEClassifiers().add(personClass);
        
        return pkg;
    }
}
```

### Test Validator Design

Sample validator classes for testing purposes:

```java
@ValidationContext(EClass.class)
public class TestEClassValidator {
    
    @Constraint(
        name = "EClassMustHaveName",
        message = "EClass must have a name"
    )
    public ValidationRule eClassMustHaveName() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            return eClass.getName() != null && !eClass.getName().isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail(
                    "EClassMustHaveName",
                    "EClass must have a name",
                    Severity.ERROR,
                    element
                );
        };
    }
    
    @Critique(
        name = "EClassShouldStartWithCapital",
        message = "EClass name should start with capital letter"
    )
    public ValidationRule eClassShouldStartWithCapital() {
        return (element, ctx) -> {
            EClass eClass = (EClass) element;
            if (eClass.getName() == null) {
                return ValidationResult.pass();
            }
            return Character.isUpperCase(eClass.getName().charAt(0))
                ? ValidationResult.pass()
                : ValidationResult.warn(
                    "EClassShouldStartWithCapital",
                    "EClass '" + eClass.getName() + 
                    "' should start with capital letter"
                );
        };
    }
    
    @Constraint(
        name = "EClassAbstractMustNotHaveDirectInstances",
        message = "Abstract EClass must not have direct instances"
    )
    @Guard(method = "isAbstract")
    public ValidationRule abstractEClassMustNotHaveInstances() {
        return (element, ctx) -> {
            // Only executed if guard passes (isAbstract returns true)
            return ValidationResult.pass();
        };
    }
    
    public boolean isAbstract(EObject element, ValidationContext ctx) {
        return ((EClass) element).isAbstract();
    }
}
```

## Design Decisions

### 1. JUnit 5 vs JUnit 4

**Decision**: Use JUnit 5 (Jupiter)

**Rationale**:
- Modern testing framework with better features
- Nested test classes for better organization
- Better parameterized test support
- Display names for readable test reports
- Reference implementation uses JUnit 5

**Trade-offs**:
- Requires Java 8+ (already required by project)
- Different annotations than JUnit 4 (migration cost negligible for new tests)

### 2. Test Organization Strategy

**Decision**: Use nested test classes within single test files

**Rationale**:
- Groups related tests logically (e.g., ValidationRegistryTest contains nested classes for different aspects)
- Reduces number of test files
- Follows pattern from reference implementation
- Easier to navigate related tests

**Example**:
```java
class ValidationRegistryTest {
    
    @Nested
    @DisplayName("Registration Tests")
    class RegistrationTests {
        // Tests for registration functionality
    }
    
    @Nested
    @DisplayName("Lookup Tests")
    class LookupTests {
        // Tests for validator lookup
    }
}
```

### 3. Test Data: Real Metamodel vs Simple ECore

**Decision**: Use ECore metamodel elements (EClass, EPackage, EAttribute, EReference, etc.)

**Rationale**:
- ECore metamodel is already available in the project
- ECore is the foundation for all EMF models (including ESM)
- Tests demonstrate real-world usage with actual metamodel elements
- No additional metamodel dependencies needed
- Well-documented and understood by EMF developers
- Tests are self-contained and focused on validation framework

**Why ECore Metamodel**:
- ECore metamodel describes the structure of metamodels themselves
- Contains familiar elements: EClass (class), EAttribute (property), EPackage (package), EReference (relationship)
- These elements are EObjects, perfect for testing validation framework
- Allows testing complex scenarios (inheritance, references, containment)

### 4. Caching Verification Strategy

**Decision**: Use counting mechanism in test validators

**Rationale**:
- Direct way to verify cache hits vs misses
- Similar to reference implementation approach
- No need for mocking or instrumentation

**Example**:
```java
@ValidationContext(EObject.class)
public class CountingValidator {
    public static final AtomicInteger callCount = new AtomicInteger(0);
    
    @Constraint(name = "CountingConstraint", message = "Test")
    public ValidationRule countingConstraint() {
        return (element, ctx) -> {
            callCount.incrementAndGet();
            return ValidationResult.pass();
        };
    }
}
```

### 5. Parallel Execution Testing

**Decision**: Test both parallel and sequential execution paths

**Rationale**:
- Framework supports both modes
- Different code paths need verification
- Need to ensure no race conditions
- Sequential mode is simpler for most tests
- Parallel mode tested specifically where relevant

**Approach**:
- Most tests use sequential mode (simpler, faster)
- Dedicated tests for parallel execution
- Use deterministic test data to avoid flakiness
- Verify behavior, not timing

### 6. Extension Method Testing

**Decision**: Create dedicated test extension classes

**Rationale**:
- Extension methods are a key framework feature
- Need to test caching separately from non-cached
- Need to verify parameter passing
- Test both success and error cases

**Example**:
```java
@ExtensionMethod(EClass.class)
public class TestEClassExtensions {
    
    @Cached
    public String getFullyQualifiedName(EClass self) {
        // Implementation with caching
    }
    
    public int getAttributeCount(EClass self) {
        // Implementation without caching
    }
}
```

### 7. Assertion Library

**Decision**: Use standard JUnit 5 assertions

**Rationale**:
- No additional dependency
- Sufficient for validation testing needs
- Consistent with reference implementation
- Team is likely familiar with standard assertions

**Alternative Considered**: AssertJ
- Provides more fluent API
- Rejected to minimize dependencies
- Can be reconsidered if tests become hard to read

## Testing Patterns

### Pattern 1: Test Setup with Builder

```java
@Test
void shouldValidateElement() {
    // Given
    EClass element = TestModelFactory.createEClass("TestClass");
    addToModel(element);
    registry.register(TestEClassValidator.class);
    
    // When
    ValidationExecutor executor = new ValidationExecutor(
        registry, context, false
    );
    List<ValidationResult> results = executor.validate(
        Collections.singletonList(element)
    );
    
    // Then
    assertTrue(results.isEmpty());
}
```

### Pattern 2: Verify Constraint Failure with Message

```java
@Test
void shouldFailWhenNameMissing() {
    // Given
    EClass element = TestModelFactory.createEClass(null); // No name
    addToModel(element);
    registry.register(TestEClassValidator.class);
    
    // When
    ValidationExecutor executor = new ValidationExecutor(
        registry, context, false
    );
    List<ValidationResult> results = executor.validate(
        Collections.singletonList(element)
    );
    
    // Then
    assertFalse(results.isEmpty());
    ValidationResult failure = results.get(0);
    assertEquals("EClassMustHaveName", failure.getConstraintName());
    assertEquals(Severity.ERROR, failure.getSeverity());
    // IMPORTANT: Verify exact message
    assertEquals("EClass must have a name", failure.getMessage());
}
```

### Pattern 3: Verify Caching

```java
@Test
void shouldCacheResults() {
    // Given
    CountingValidator.callCount.set(0);
    registry.register(CountingValidator.class);
    EClass element = TestModelFactory.createEClass("Test");
    addToModel(element);
    
    // When - call twice
    context.satisfies(element, "CountingConstraint");
    context.satisfies(element, "CountingConstraint");
    
    // Then - should only execute once
    assertEquals(1, CountingValidator.callCount.get());
}
```

## Integration with Build

### Maven Configuration

Add to `validation-core/pom.xml`:

```xml
<dependencies>
    <!-- Test Dependencies -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
    
    <dependency>
        <groupId>org.eclipse.emf</groupId>
        <artifactId>org.eclipse.emf.ecore</artifactId>
        <version>2.38.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>${surefire-version}</version>
            <configuration>
                <includes>
                    <include>**/*Test.java</include>
                </includes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### CI Integration

Tests will run automatically as part of:
- `mvn test` - Run tests only
- `mvn verify` - Run tests and integration tests
- `mvn install` - Full build including tests
- GitHub Actions workflows (existing build.yml)

## Performance Considerations

1. **Test Execution Time**:
   - Target: < 30 seconds for full suite
   - Use simple test data
   - Avoid expensive operations in setup
   - No external dependencies (files, network)

2. **Resource Management**:
   - Clear resources in @AfterEach
   - Use small test models
   - Reuse resource sets where possible

3. **Parallel Test Execution**:
   - Tests are thread-safe (no shared state)
   - Can enable Maven parallel execution if needed

## Migration Path

Since this is adding tests to existing code, no migration is needed. However:

1. Tests should be added incrementally (task by task)
2. Each task delivers working, passing tests
3. Coverage can be measured and improved over time
4. Existing code does not need modification

## Future Enhancements

Potential improvements after initial implementation:

1. **Code Coverage Reports**: Add JaCoCo for coverage metrics
2. **Mutation Testing**: Add PIT for test quality verification
3. **Performance Benchmarks**: Add JMH benchmarks for critical paths
4. **Property-based Testing**: Add QuickCheck-style tests for edge cases
5. **Test Fixtures**: Create reusable test model fixtures

## References

- JUnit 5 User Guide: https://junit.org/junit5/docs/current/user-guide/
- EMF Testing Patterns: https://www.eclipse.org/modeling/emf/
- Reference Implementation: `/tmp/judo-meta-esm/model-test/`
