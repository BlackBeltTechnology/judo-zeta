# Testing Transformations

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Testing

Strategies for testing transformation rules effectively.

## Unit Testing Rules

```java
class EntityTypeTransformationsTest {
    
    private TransformationRegistry registry;
    private TransformationContext context;
    
    @BeforeEach
    void setUp() {
        registry = new TransformationRegistry();
        registry.register(EntityTypeTransformations.class);
        
        ResourceSet sourceResourceSet = new ResourceSetImpl();
        ResourceSet targetResourceSet = new ResourceSetImpl();
        
        context = new TransformationContext(
            new TestModelProvider(),
            sourceResourceSet,
            targetResourceSet,
            new ExtensionMethodRegistry()
        );
        context.setTransformationRegistry(registry);
        context.setTargetPackage(TargetPackage.eINSTANCE);
    }
    
    @Test
    void entityType2Table_mapsNameCorrectly() {
        // Given
        EntityType entity = SourceFactory.eINSTANCE.createEntityType();
        entity.setName("Customer");
        
        // When
        TransformationExecutor executor = new TransformationExecutor(registry, context, false);
        TransformationResult result = executor.transform(List.of(entity));
        
        // Then
        Table table = context.equivalent(entity, Table.class);
        assertThat(table.getName()).isEqualTo("Customer");
    }
    
    @Test
    void entityType2Table_transformsAttributes() {
        // Given
        EntityType entity = SourceFactory.eINSTANCE.createEntityType();
        entity.setName("Customer");
        
        Attribute nameAttr = SourceFactory.eINSTANCE.createAttribute();
        nameAttr.setName("name");
        entity.getAttributes().add(nameAttr);
        
        // When
        TransformationExecutor executor = new TransformationExecutor(registry, context, false);
        executor.transform(List.of(entity));
        
        // Then
        Table table = context.equivalent(entity, Table.class);
        assertThat(table.getColumns()).hasSize(1);
        assertThat(table.getColumns().get(0).getName()).isEqualTo("name");
    }
}
```

## Integration Testing

```java
class Esm2PsmTransformationIntegrationTest {
    
    @Test
    void transformsCompleteModel() throws Exception {
        // Given: Load source model
        ResourceSet sourceResourceSet = loadModel("test-models/complete-model.esm");
        ResourceSet targetResourceSet = new ResourceSetImpl();
        
        // When: Execute full transformation
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(AllTransformations.class);
        
        TransformationContext context = createContext(sourceResourceSet, targetResourceSet);
        TransformationExecutor executor = new TransformationExecutor(registry, context, true);
        
        Collection<EObject> sourceElements = getAllContents(sourceResourceSet);
        TransformationResult result = executor.transform(sourceElements);
        
        // Then: Verify target model
        assertThat(result.getTrace().getEntryCount()).isGreaterThan(0);
        
        Collection<Table> tables = getAllOfType(targetResourceSet, Table.class);
        assertThat(tables).isNotEmpty();
        
        // Verify specific transformations
        Table customerTable = findByName(tables, "Customer");
        assertThat(customerTable).isNotNull();
        assertThat(customerTable.getColumns()).hasSizeGreaterThan(0);
    }
}
```

## Verifying Trace Output

```java
@Test
void traceContainsAllTransformations() {
    // When
    TransformationResult result = executor.transform(sourceElements);
    TransformationTrace trace = result.getTrace();
    
    // Then
    assertThat(trace.getEntryCount()).isEqualTo(expectedCount);
    
    // Verify specific entries
    List<TraceEntry> entityEntries = trace.getEntries().stream()
        .filter(e -> e.getRuleName().equals("EntityType2Table"))
        .toList();
    assertThat(entityEntries).hasSize(3);
    
    // Export for debugging
    String json = trace.toJson();
    assertThat(json).contains("EntityType2Table");
}
```

## Test Fixtures and Model Builders

```java
class TestModelBuilder {
    
    public static EntityType createEntity(String name) {
        EntityType entity = SourceFactory.eINSTANCE.createEntityType();
        entity.setName(name);
        return entity;
    }
    
    public static EntityType createEntityWithAttributes(String name, String... attrNames) {
        EntityType entity = createEntity(name);
        for (String attrName : attrNames) {
            Attribute attr = SourceFactory.eINSTANCE.createAttribute();
            attr.setName(attrName);
            entity.getAttributes().add(attr);
        }
        return entity;
    }
    
    public static Namespace createNamespace(String name, EntityType... entities) {
        Namespace ns = SourceFactory.eINSTANCE.createNamespace();
        ns.setName(name);
        for (EntityType entity : entities) {
            ns.getElements().add(entity);
        }
        return ns;
    }
}

// Usage in tests
@Test
void test() {
    EntityType customer = TestModelBuilder.createEntityWithAttributes(
        "Customer", "name", "email", "phone"
    );
    // ...
}
```

## Testing Guards

```java
@Test
void abstractEntity_usesAbstractRule() {
    // Given
    EntityType abstractEntity = TestModelBuilder.createEntity("AbstractEntity");
    abstractEntity.setAbstract(true);
    
    // When
    executor.transform(List.of(abstractEntity));
    
    // Then
    Table table = context.equivalent(abstractEntity, Table.class);
    assertThat(table.isAbstract()).isTrue();
}

@Test
void concreteEntity_usesConcreteRule() {
    // Given
    EntityType concreteEntity = TestModelBuilder.createEntity("ConcreteEntity");
    concreteEntity.setAbstract(false);
    
    // When
    executor.transform(List.of(concreteEntity));
    
    // Then
    Table table = context.equivalent(concreteEntity, Table.class);
    assertThat(table.isAbstract()).isFalse();
}
```

## Testing Lazy Rules

```java
@Test
void lazyRule_executesOnDemand() {
    // Given
    Reference ref = TestModelBuilder.createReference("customer");
    EntityType entity = TestModelBuilder.createEntity("Order");
    entity.getReferences().add(ref);
    
    // When: Transform entity (lazy rule not yet executed)
    executor.transform(List.of(entity));
    
    // Then: Lazy rule executes when equivalent() called
    ForeignKey fk = context.equivalent(ref, ForeignKey.class);
    assertThat(fk).isNotNull();
    assertThat(fk.getName()).isEqualTo("fk_customer");
}
```

---

**Previous**: [Error Handling](error-handling.md) | **Next**: [ETL Comparison](../etl-comparison/overview.md)
