# Design: Add ETL Pattern Integration Tests

**Change ID**: `add-etl-pattern-integration-tests`  
**Status**: Draft

## Overview

This document describes the design for comprehensive integration tests that validate resource alias support against real-world ETL patterns from judo-tatami.

## Key Design Decisions

### Ecore Metamodel Only
All tests use Ecore metamodel elements to simulate transformation scenarios:
- `EClass` → simulates EntityType, Table
- `EAttribute` → simulates Attribute, Column
- `EReference` → simulates Reference, ForeignKey
- `EPackage` → simulates Package, Schema
- `EAnnotation` → simulates Mapping entries, Rule metadata

### Cartesian Product for Multiple @Transform
When a rule has multiple `@Transform` annotations from different aliases, the executor produces a Cartesian product of all source elements:

```java
@TransformRule(name = "ApplyMapping")
@Transform(alias = "asm", type = EClass.class)
@Transform(alias = "mapping", type = EAnnotation.class)
@To(alias = "rdbms", type = EClass.class)
public TransformFunction<Object[], EClass> applyMapping() {
    return (sources, ctx) -> {
        EClass entity = (EClass) sources[0];      // From "asm"
        EAnnotation mapping = (EAnnotation) sources[1]; // From "mapping"
        // Transform using both sources
        EClass table = ctx.create(EClass.class);
        table.setName(mapping.getDetails().get(entity.getName()));
        return table;
    };
}
```

**Execution behavior:**
- If "asm" has elements [A, B, C] and "mapping" has elements [M1, M2]
- Rule fires 6 times: (A,M1), (A,M2), (B,M1), (B,M2), (C,M1), (C,M2)
- Each invocation receives `Object[]` with elements from each alias in order

**Note:** This requires changes to `TransformationExecutor` to support Cartesian product execution.

### Guard with Multiple Sources

For multi-source rules, guards also need access to all source elements:

```java
// Single source guard (backward compatible)
public boolean shouldTransform(EObject source, TransformationContext ctx) {
    EClass entity = (EClass) source;
    return entity.isAbstract() == false;
}

// Multi-source guard (new)
public boolean shouldApplyMapping(Object[] sources, TransformationContext ctx) {
    EClass entity = (EClass) sources[0];
    EAnnotation mapping = (EAnnotation) sources[1];
    // Guard can evaluate conditions on both source elements
    return mapping.getSource().equals(entity.getName());
}
```

The framework detects guard method signature and invokes appropriately:
- `(EObject, TransformationContext)` → single source, backward compatible
- `(Object[], TransformationContext)` → multi-source, receives all elements

## Test Architecture

### Test Fixture Design

Use Ecore metamodel as the test domain since:
- Already available in the project
- Rich enough to model transformation scenarios
- No additional dependencies required

**Simulated Models:**

| Alias | Purpose | Example Elements |
|-------|---------|------------------|
| `source` | Source model | EClass, EAttribute, EReference |
| `target` | Target model | EPackage, EClass (transformed) |
| `mapping` | Mapping rules | EAnnotation with key-value entries |
| `rules` | FK/Junction rules | EAnnotation with rule metadata |

### Test Class Structure

```
ETLPatternIntegrationTest
├── @Nested MultiModelTransformationTests
│   ├── shouldTransformAcrossThreeAliasedModels()
│   └── shouldAccessMappingModelDuringTransformation()
│
├── @Nested CrossModelEquivalenceTests
│   ├── shouldResolveEquivalentFromAliasedTarget()
│   ├── shouldResolveEquivalentChainAcrossModels()
│   └── shouldCacheEquivalentAcrossAliases()
│
├── @Nested GuardWithAliasTests
│   ├── shouldEvaluateGuardQueryingMappingAlias()
│   ├── shouldSkipRuleWhenGuardFailsOnAliasedLookup()
│   └── shouldApplyRuleWhenGuardSucceedsOnAliasedLookup()
│
├── @Nested LazyRuleWithAliasTests
│   ├── shouldInvokeLazyRuleOnEquivalentCall()
│   ├── shouldCreateElementInAliasedTargetLazily()
│   └── shouldCacheLazyResultAcrossInvocations()
│
├── @Nested GreedyRuleWithAliasTests
│   ├── shouldFireGreedyRuleForAllMatchingElements()
│   ├── shouldCreateMultipleOutputsFromAliasedSource()
│   └── shouldRespectGuardInGreedyMode()
│
├── @Nested RuleInheritanceWithAliasTests
│   ├── shouldInheritTransformAliasFromAbstractRule()
│   ├── shouldOverrideAliasInExtendingRule()
│   └── shouldCombineAbstractRuleWithGuardOnAlias()
│
├── @Nested PrePostHookTests
│   ├── shouldRegisterResourceInPreHook()
│   ├── shouldAccessAllAliasesInPostHook()
│   └── shouldApplyMappingsInPostHook()
│
└── @Nested ComplexScenarioTests
    ├── shouldSimulateASM2RDBMSTransformation()
    └── shouldHandleDiscriminatedTransformations()
```

## Test Scenarios

### Scenario 1: Multi-Model Transformation

**Setup:**
```java
// Register 3 models
ctx.registerResource("asm", asmResourceSet);      // Source
ctx.registerResource("rdbms", rdbmsResourceSet);  // Target
ctx.registerResource("mapping", mappingResourceSet); // Lookup

// Populate asm with EClass elements
// Populate mapping with type mapping entries
```

**Transformation:**
```java
@TransformRule(name = "EClass2Table")
@Transform(alias = "asm", type = EClass.class)
@To(alias = "rdbms", type = EClass.class)
public TransformFunction<EClass, EClass> eClass2Table() {
    return (source, ctx) -> {
        // Lookup mapping from "mapping" alias
        Collection<EAnnotation> mappings = ctx.all("mapping", EAnnotation.class);
        EAnnotation typeMapping = mappings.stream()
            .filter(m -> m.getSource().equals(source.getName()))
            .findFirst()
            .orElse(null);
        
        EClass table = ctx.create(EClass.class);
        table.setName(typeMapping != null 
            ? typeMapping.getDetails().get("targetName") 
            : source.getName());
        return table;
    };
}
```

**Assertions:**
- Elements created in "rdbms" alias
- Mapping lookups resolved correctly
- Transformation trace cached

### Scenario 2: Cross-Model Equivalence

**Setup:**
```java
// Parent EClass in source
EClass parent = createEClass("Parent");
// Child EReference in source pointing to parent
EReference ref = createEReference("parentRef", parent);
```

**Transformation:**
```java
@TransformRule(name = "EReference2FK")
@Transform(alias = "asm", type = EReference.class)
@To(alias = "rdbms", type = EClass.class)
public TransformFunction<EReference, EClass> eReference2FK() {
    return (source, ctx) -> {
        // Lookup equivalent of eReferenceType from aliased target
        EClass targetTable = ctx.equivalent(
            source.getEReferenceType(), 
            EClass.class
        );
        
        EClass fk = ctx.create(EClass.class);
        fk.setName(source.getName() + "_fk");
        // Reference the equivalent table
        // fk.referenceKey = targetTable.primaryKey (simulated)
        return fk;
    };
}
```

**Assertions:**
- `equivalent()` returns element from aliased target
- Cross-model reference resolved

### Scenario 3: Guard with Alias Lookup

**Setup:**
```java
// Mapping entries indicating which refs should create FKs
EAnnotation fkRule = createAnnotation("fkRule");
fkRule.getDetails().put("refName", "myRef");
fkRule.getDetails().put("type", "foreignKey");
```

**Transformation:**
```java
@TransformRule(name = "EReference2FK")
@Transform(alias = "asm", type = EReference.class)
@To(alias = "rdbms", type = EClass.class)
@Guard(method = "shouldCreateFK")
public TransformFunction<EReference, EClass> eReference2FK() { ... }

public boolean shouldCreateFK(EObject source, TransformationContext ctx) {
    EReference ref = (EReference) source;
    // Query mapping alias to check if FK should be created
    return ctx.all("rules", EAnnotation.class).stream()
        .anyMatch(rule -> 
            rule.getDetails().get("refName").equals(ref.getName()) &&
            "foreignKey".equals(rule.getDetails().get("type"))
        );
}
```

**Assertions:**
- Guard executes and queries "rules" alias
- Rule skipped when guard returns false
- Rule applies when guard returns true

### Scenario 4: Lazy Rule with Alias

**Setup:**
```java
// EReference that should create junction table on-demand
EReference manyToMany = createEReference("items");
manyToMany.setUpperBound(-1); // many
```

**Transformation:**
```java
@Lazy
@TransformRule(name = "EReference2JunctionTable")
@Transform(alias = "asm", type = EReference.class)
@To(alias = "rdbms", type = EClass.class)
public TransformFunction<EReference, EClass> eReference2JunctionTable() {
    return (source, ctx) -> {
        EClass junction = ctx.create(EClass.class);
        junction.setName(source.getName() + "_junction");
        return junction;
    };
}

// Called from another rule
EClass junction = ctx.equivalent(manyToManyRef, EClass.class);
```

**Assertions:**
- Lazy rule not executed until `equivalent()` called
- Junction table created in aliased target
- Result cached for subsequent calls

### Scenario 5: Greedy Rule with Alias

**Setup:**
```java
// Multiple EClass elements, each should produce annotation
EClass class1 = createEClass("Entity1");
EClass class2 = createEClass("Entity2");
```

**Transformation:**
```java
@Greedy
@TransformRule(name = "CreateDocAnnotation")
@Transform(alias = "asm", type = EClass.class)
@To(alias = "rdbms", type = EAnnotation.class)
public TransformFunction<EClass, EAnnotation> createDocAnnotation() {
    return (source, ctx) -> {
        EAnnotation doc = ctx.create(EAnnotation.class);
        doc.setSource("documentation");
        doc.getDetails().put("class", source.getName());
        return doc;
    };
}
```

**Assertions:**
- Rule fires for each EClass in "asm" alias
- Annotation created for each source element
- All annotations in "rdbms" alias

### Scenario 6: Rule Inheritance with Alias

**Setup:**
```java
// EAttributes with different data types
EAttribute stringAttr = createEAttribute("name", EcorePackage.Literals.ESTRING);
EAttribute intAttr = createEAttribute("count", EcorePackage.Literals.EINT);
```

**Transformation:**
```java
@Abstract
@TransformRule(name = "AddConstraint")
@Transform(alias = "asm", type = EAttribute.class)
@To(alias = "rdbms", type = EAnnotation.class)
public TransformFunction<EAttribute, EAnnotation> addConstraint() {
    return (source, ctx) -> {
        EAnnotation constraint = ctx.create(EAnnotation.class);
        constraint.setSource("constraint");
        return constraint;
    };
}

@Greedy
@TransformRule(name = "AddStringConstraint")
@Transform(alias = "asm", type = EAttribute.class)
@To(alias = "rdbms", type = EAnnotation.class)
@Extends("AddConstraint")
@Guard(method = "isStringType")
public TransformFunction<EAttribute, EAnnotation> addStringConstraint() {
    return (source, ctx) -> {
        EAnnotation constraint = ctx.executeParentRule("AddConstraint", source);
        constraint.getDetails().put("maxLength", "255");
        return constraint;
    };
}
```

**Assertions:**
- Abstract rule not executed directly
- Extended rule inherits alias configuration
- Guard filters to string types only

### Scenario 7: Pre/Post Hooks with Aliases

**Setup:**
```java
// Main source/target models
// Mapping model loaded in pre-hook
```

**Transformation:**
```java
@PreExecution
public void loadMappings(TransformationContext ctx) {
    ResourceSet mappingSet = loadMappingModel();
    ctx.registerResource("mapping", mappingSet);
}

@PostExecution
public void applyNameMappings(TransformationContext ctx) {
    for (EClass table : ctx.all("rdbms", EClass.class)) {
        // Apply SQL name mappings
        ctx.all("mapping", EAnnotation.class).stream()
            .filter(m -> m.getSource().equals(table.getName()))
            .findFirst()
            .ifPresent(m -> {
                // Simulate setting SQL name
                table.setName(m.getDetails().get("sqlName"));
            });
    }
}
```

**Assertions:**
- Pre-hook registers alias before transformation
- Post-hook can access all aliased resources
- Mappings applied after main transformation

## Test Data Setup

### Helper Methods

```java
private EClass createEClass(String name) {
    EClass eClass = EcoreFactory.eINSTANCE.createEClass();
    eClass.setName(name);
    sourceResource.getContents().add(eClass);
    return eClass;
}

private EAttribute createEAttribute(String name, EDataType type) {
    EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
    attr.setName(name);
    attr.setEType(type);
    return attr;
}

private EAnnotation createMappingEntry(String sourceName, String targetName) {
    EAnnotation mapping = EcoreFactory.eINSTANCE.createEAnnotation();
    mapping.setSource(sourceName);
    mapping.getDetails().put("targetName", targetName);
    mappingResource.getContents().add(mapping);
    return mapping;
}
```

### Resource Setup Pattern

```java
@BeforeEach
void setUp() {
    // Source model (asm)
    asmResourceSet = new ResourceSetImpl();
    asmResource = asmResourceSet.createResource(URI.createURI("test://asm.xmi"));
    
    // Target model (rdbms)
    rdbmsResourceSet = new ResourceSetImpl();
    rdbmsResource = rdbmsResourceSet.createResource(URI.createURI("test://rdbms.xmi"));
    
    // Mapping model
    mappingResourceSet = new ResourceSetImpl();
    mappingResource = mappingResourceSet.createResource(URI.createURI("test://mapping.xmi"));
    
    // Context setup
    context = new TransformationContext(modelProvider, asmResourceSet, rdbmsResourceSet, registry);
    context.setTargetPackage(EcorePackage.eINSTANCE);
    context.registerResource("asm", asmResourceSet);
    context.registerResource("rdbms", rdbmsResourceSet);
    context.registerResource("mapping", mappingResourceSet);
}
```

## Validation Strategy

Each test should verify:

1. **Correct Alias Resolution**: Elements come from expected alias
2. **Transformation Trace**: `equivalent()` lookups work across aliases
3. **Element Containment**: Created elements in correct aliased resource
4. **Guard Execution**: Guards can query aliased resources
5. **Hook Integration**: Pre/post hooks interact correctly with aliases
