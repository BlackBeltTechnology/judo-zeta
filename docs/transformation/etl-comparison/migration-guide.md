# ETL to Zeta Migration Guide

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [ETL Comparison](overview.md) > Migration Guide

This guide provides a comprehensive, step-by-step process for migrating transformation logic from Epsilon ETL to Zeta Java-based transformations.

## Prerequisites

- Familiarity with the existing ETL transformation
- Java development environment (JDK 17+)
- Understanding of the source and target metamodels
- IDE with Java support (IntelliJ IDEA, Eclipse, VS Code)

## Migration Process Overview

```
1. Analyze ETL Structure
       ↓
2. Create Zeta Directory Structure
       ↓
3. Define Rule Name Constants
       ↓
4. Migrate Rules by Category
       ↓
5. Implement Post-Processing
       ↓
6. Set Up Main Transformation Class
       ↓
7. Set Up Dual-Engine Testing
       ↓
8. Verify Output Equivalence
```

---

## Step 1: Analyze ETL Structure

### 1.1 Inventory ETL Files

List all ETL files in your transformation:

```
src/main/epsilon/transformations/
├── mainTransformation.etl      # Entry point
├── modules/
│   ├── namespace.etl
│   ├── type.etl
│   ├── data.etl
│   └── ...
└── utils/
    └── helpers.eol
```

### 1.2 Categorize Rules

For each ETL file, create an inventory table:

| Category | ETL Marker | Count | Complexity |
|----------|------------|-------|------------|
| Greedy rules | `@greedy` | | Simple |
| Guarded rules | `guard:` | | Medium |
| Lazy rules | `@lazy` | | On-demand |
| Multi-output rules | Multiple `to` targets | | Complex |
| Primary rules | `@primary` | | Standard |
| Abstract rules | `@abstract` | | Base patterns |

### 1.3 Identify Dependencies

Map rule dependencies:
- Which rules must execute before others?
- Which rules reference equivalents from other rules?
- Which elements need post-processing for bidirectional references?

### 1.4 Document Helper Functions

List all EOL helper operations that need Java equivalents:

```
helpers.eol:
├── isInteger(NumericType) : Boolean
├── getIntegerClassName(NumericType) : String
├── formatTableName(String) : String
└── ...
```

---

## Step 2: Create Zeta Directory Structure

### 2.1 Create Directory Layout

```bash
mkdir -p src/main/java/hu/blackbelt/judo/tatami/<module>/zeta/rules
```

### 2.2 Standard File Structure

```
src/main/java/hu/blackbelt/judo/tatami/<module>/zeta/
├── <Module>ZetaTransformation.java    # Main transformation class
├── <Module>Helper.java                 # Utility methods (from EOL)
├── <Module>RuleNames.java              # Rule name constants
└── rules/
    ├── NamespaceRules.java             # Package/namespace rules
    ├── TypeRules.java                  # Type transformation rules
    ├── DataRules.java                  # Data/entity rules
    ├── TransferObjectRules.java        # DTO rules
    ├── OperationRules.java             # Operation/method rules
    └── ActorRules.java                 # Actor/security rules
```

### 2.3 Example: PSM to ASM Structure

```
src/main/java/hu/blackbelt/judo/tatami/psm2asm/zeta/
├── Psm2AsmZetaTransformation.java
├── Psm2AsmHelper.java
├── Psm2AsmRuleNames.java
└── rules/
    ├── NamespaceRules.java
    ├── PrimitiveTypeRules.java
    ├── EnumerationRules.java
    ├── EntityRules.java
    ├── AttributeRules.java
    ├── ReferenceRules.java
    ├── TransferObjectRules.java
    └── OperationRules.java
```

---

## Step 3: Define Rule Name Constants

Extract all rule names from ETL files into a constants class for type-safe references:

```java
package hu.blackbelt.judo.tatami.psm2asm.zeta;

/**
 * Rule name constants for PSM to ASM transformation.
 * These names are used for traceability and rule identification.
 */
public final class Psm2AsmRuleNames {
    private Psm2AsmRuleNames() {}

    // Namespace rules
    public static final String MODEL_TO_PACKAGE = "ModelToPackage";
    public static final String PACKAGE_TO_PACKAGE = "PackageToPackage";

    // Primitive type rules
    public static final String CREATE_STRING_TYPE = "CreateStringType";
    public static final String CREATE_INTEGER_TYPE = "CreateIntegerType";
    public static final String CREATE_DECIMAL_TYPE = "CreateDecimalType";
    public static final String CREATE_BOOLEAN_TYPE = "CreateBooleanType";
    public static final String CREATE_DATE_TYPE = "CreateDateType";
    public static final String CREATE_TIMESTAMP_TYPE = "CreateTimestampType";

    // Enumeration rules
    public static final String CREATE_ENUMERATION = "CreateEnumeration";
    public static final String CREATE_ENUMERATION_LITERAL = "CreateEnumerationLiteral";

    // Entity rules
    public static final String CREATE_ENTITY_CLASS = "CreateEntityClass";
    public static final String CREATE_ENTITY_REFERENCE_CLASS = "CreateEntityReferenceClass";
    
    // Attribute rules
    public static final String CREATE_ATTRIBUTE = "CreateAttribute";
    public static final String CREATE_IDENTIFIER_ATTRIBUTE = "CreateIdentifierAttribute";
    
    // Reference rules
    public static final String CREATE_REFERENCE = "CreateReference";
    public static final String CREATE_CONTAINMENT_REFERENCE = "CreateContainmentReference";
    
    // Transfer object rules
    public static final String CREATE_TRANSFER_OBJECT = "CreateTransferObject";
    public static final String CREATE_TRANSFER_ATTRIBUTE = "CreateTransferAttribute";
    
    // Operation rules
    public static final String CREATE_OPERATION = "CreateOperation";
    public static final String CREATE_PARAMETER = "CreateParameter";
}
```

---

## Step 4: Migrate Rules by Category

### 4.1 Start with Independent Rules

Begin with rules that have no dependencies on other rules:

**ETL:**
```etl
@greedy
rule CreateStringType
    transform s : JUDOPSM!StringType
    to t : ASM!EDataType {
        t.setId("(psm/" + s.getId() + ")/StringType");
        t.name = s.name;
        t.instanceClassName = "java.lang.String";
        s.eContainer.asmEquivalent().eClassifiers.add(t);
    }
```

**Zeta:**
```java
@TransformRule(name = CREATE_STRING_TYPE)
@Greedy
@Transform(type = StringType.class)
@To(type = EDataType.class)
public TransformFunction<StringType, EDataType> createStringType() {
    return (s, ctx) -> {
        EDataType t = ctx.createTarget(EDataType.class);
        setId(t, "(psm/" + getId(s) + ")/StringType");
        t.setName(s.getName());
        t.setInstanceClassName("java.lang.String");

        EPackage containerPkg = ctx.equivalent(s.eContainer(), EPackage.class);
        if (containerPkg != null) {
            containerPkg.getEClassifiers().add(t);
        }
        return t;
    };
}
```

### 4.2 Migrate Guarded Rules

Create guard methods for conditional rules:

**ETL:**
```etl
@greedy
rule CreateIntegerType
    transform s : JUDOPSM!NumericType
    to t : ASM!EDataType {
        guard: s.isInteger()
        t.setId("(psm/" + s.getId() + ")/IntegerType");
        t.name = s.name;
        t.instanceClassName = getIntegerClassName(s);
    }
```

**Zeta:**
```java
// Guard method - must match signature: (EObject, TransformationContext) -> boolean
public boolean isIntegerGuard(EObject source, TransformationContext ctx) {
    return source instanceof NumericType 
        && Psm2AsmHelper.isInteger((NumericType) source);
}

@TransformRule(name = CREATE_INTEGER_TYPE)
@Greedy
@Guard(method = "isIntegerGuard")
@Transform(type = NumericType.class)
@To(type = EDataType.class)
public TransformFunction<NumericType, EDataType> createIntegerType() {
    return (s, ctx) -> {
        EDataType t = ctx.createTarget(EDataType.class);
        setId(t, "(psm/" + getId(s) + ")/IntegerType");
        t.setName(s.getName());
        t.setInstanceClassName(Psm2AsmHelper.getIntegerClassName(s));
        
        EPackage containerPkg = ctx.equivalent(s.eContainer(), EPackage.class);
        if (containerPkg != null) {
            containerPkg.getEClassifiers().add(t);
        }
        return t;
    };
}
```

### 4.3 Migrate Multi-Output Rules

Use `registerEquivalent` for additional outputs:

**ETL:**
```etl
rule CreateEntityClass
    transform s : JUDOPSM!EntityType
    to entity : ASM!EClass, ref : ASM!EClass {
        entity.name = s.name;
        entity.abstract = s.`abstract`;
        
        ref.name = s.name + "__Reference";
        ref.abstract = s.`abstract`;
    }
```

**Zeta:**
```java
@TransformRule(name = CREATE_ENTITY_CLASS)
@Transform(type = EntityType.class)
@To(type = EClass.class)
public TransformFunction<EntityType, EClass> createEntityClass() {
    return (s, ctx) -> {
        // Primary output
        EClass entityClass = ctx.createTarget(EClass.class);
        setId(entityClass, "(psm/" + getId(s) + ")/EntityClass");
        entityClass.setName(s.getName());
        entityClass.setAbstract(s.isAbstract());

        // Secondary output - register with qualifier
        EClass refClass = ctx.create(EClass.class);
        setId(refClass, "(psm/" + getId(s) + ")/ReferenceClass");
        refClass.setName(s.getName() + "__Reference");
        refClass.setAbstract(s.isAbstract());
        ctx.registerEquivalent(s, refClass, "Reference");

        // Add to container
        EPackage containerPkg = ctx.equivalent(s.eContainer(), EPackage.class);
        if (containerPkg != null) {
            containerPkg.getEClassifiers().add(entityClass);
            containerPkg.getEClassifiers().add(refClass);
        }

        return entityClass;  // Return primary target
    };
}

// Accessing the secondary output later:
EClass refClass = ctx.equivalent(entity, EClass.class, "Reference");
```

### 4.4 Convert Lazy Rules to Helper Methods

Lazy rules in ETL are called on-demand. In Zeta, convert them to helper methods:

**ETL:**
```etl
@lazy
rule CreateDocumentationAnnotation
    transform s : JUDOPSM!NamedElement
    to t : ASM!EAnnotation {
        guard: s.documentation.isDefined()
        t.source = getAnnotationUri("documentation");
        t.details.put("value", s.documentation);
    }
```

**Zeta (Helper method):**
```java
// In Helper class - called explicitly where needed
public static EAnnotation createDocumentationAnnotation(
        NamedElement source, TransformationContext ctx) {
    if (source.getDocumentation() == null || source.getDocumentation().isEmpty()) {
        return null;
    }
    EAnnotation annotation = ctx.create(EAnnotation.class);
    annotation.setSource(getAnnotationUri("documentation"));
    annotation.getDetails().put("value", source.getDocumentation());
    return annotation;
}

// Usage in a rule:
EAnnotation docAnnotation = Psm2AsmHelper.createDocumentationAnnotation(s, ctx);
if (docAnnotation != null) {
    t.getEAnnotations().add(docAnnotation);
}
```

**Alternative: Use @Lazy annotation for on-demand rule execution:**
```java
@TransformRule(name = "CreateDocumentationAnnotation")
@Lazy
@Guard(method = "hasDocumentation")
@Transform(type = NamedElement.class)
@To(type = EAnnotation.class)
public TransformFunction<NamedElement, EAnnotation> createDocumentationAnnotation() {
    return (s, ctx) -> {
        EAnnotation annotation = ctx.createTarget(EAnnotation.class);
        annotation.setSource(getAnnotationUri("documentation"));
        annotation.getDetails().put("value", s.getDocumentation());
        return annotation;
    };
}

private boolean hasDocumentation(EObject source, TransformationContext ctx) {
    return source instanceof NamedElement ne 
        && ne.getDocumentation() != null 
        && !ne.getDocumentation().isEmpty();
}
```

### 4.5 Migrate Abstract Rules with Inheritance

**ETL:**
```etl
@abstract
rule NamedElement2NamedType
    transform s : JUDOPSM!NamedElement
    to t : ASM!ENamedElement {
        t.name = s.name;
    }

rule EntityType2EClass
    transform s : JUDOPSM!EntityType
    to t : ASM!EClass
    extends NamedElement2NamedType {
        t.abstract = s.`abstract`;
        t.interface = false;
    }
```

**Zeta:**
```java
@TransformRule(name = "NamedElement2NamedType")
@Abstract
@Transform(type = NamedElement.class)
@To(type = ENamedElement.class)
public TransformFunction<NamedElement, ENamedElement> namedElement2NamedType() {
    return (s, ctx) -> {
        ENamedElement t = ctx.createTarget(ENamedElement.class);
        t.setName(s.getName());
        return t;
    };
}

@TransformRule(name = "EntityType2EClass")
@Extends("NamedElement2NamedType")
@Transform(type = EntityType.class)
@To(type = EClass.class)
public TransformFunction<EntityType, EClass> entityType2EClass() {
    return (s, ctx) -> {
        // Execute parent rule first - this creates the target and sets name
        EClass t = ctx.executeParentRule("NamedElement2NamedType", s);
        // Add child-specific properties
        t.setAbstract(s.isAbstract());
        t.setInterface(false);
        return t;
    };
}
```

---

## Step 5: Implement Post-Processing

### 5.1 Identify Post-Processing Needs

Post-processing is required for:
- Adding root elements to resources
- Setting bidirectional references (EOpposite)
- Cross-referencing that requires all elements to exist
- Model enrichment (annotations, validation markers)
- Cleanup and finalization

### 5.2 Create Post-Process Method

```java
private void postProcess(TransformationContext context) {
    // 1. Add root packages to resource
    addRootPackagesToResource(context);
    
    // 2. Set bidirectional references
    setBidirectionalReferences(context);
    
    // 3. Model enrichment
    enrichModel(context);
}

private void addRootPackagesToResource(TransformationContext context) {
    psmUtils.all(Model.class).forEach(model -> {
        EPackage rootPkg = context.equivalent(model, EPackage.class);
        if (rootPkg != null && !targetResource.getContents().contains(rootPkg)) {
            targetResource.getContents().add(rootPkg);
        }
    });
}

private void setBidirectionalReferences(TransformationContext context) {
    // Set EOpposite for association ends
    psmUtils.all(AssociationEnd.class).forEach(assocEnd -> {
        if (assocEnd.getPartner() != null) {
            EReference ref = context.equivalent(assocEnd, EReference.class);
            EReference partnerRef = context.equivalent(
                assocEnd.getPartner(), EReference.class);
            if (ref != null && partnerRef != null && ref.getEOpposite() == null) {
                ref.setEOpposite(partnerRef);
                // Note: EMF automatically sets the inverse
            }
        }
    });
}

private void enrichModel(TransformationContext context) {
    AsmUtils asmUtils = new AsmUtils(targetResourceSet);
    asmUtils.enrichWithAnnotations();
    asmUtils.validateModel();
}
```

### 5.3 Post-Processing Best Practices

1. **Order Matters**: Execute post-processing steps in dependency order
2. **Null Safety**: Always check for null equivalents
3. **Idempotency**: Post-processing should be safe to run multiple times
4. **Logging**: Add trace logging for debugging

```java
private void postProcess(TransformationContext context) {
    log.debug("Starting post-processing");
    
    long startTime = System.currentTimeMillis();
    
    // Phase 1: Resource population
    addRootPackagesToResource(context);
    log.debug("Root packages added in {}ms", System.currentTimeMillis() - startTime);
    
    // Phase 2: Cross-references
    startTime = System.currentTimeMillis();
    setBidirectionalReferences(context);
    log.debug("Bidirectional references set in {}ms", System.currentTimeMillis() - startTime);
    
    // Phase 3: Enrichment
    startTime = System.currentTimeMillis();
    enrichModel(context);
    log.debug("Model enriched in {}ms", System.currentTimeMillis() - startTime);
    
    log.debug("Post-processing complete");
}
```

---

## Step 6: Set Up Main Transformation Class

### 6.1 Basic Structure

```java
@Slf4j
public class Psm2AsmZetaTransformation {
    
    private final PsmModel psmModel;
    private final AsmModel asmModel;
    private final PsmUtils psmUtils;
    private final Resource targetResource;
    private final ResourceSet targetResourceSet;
    
    @Builder
    public Psm2AsmZetaTransformation(
            PsmModel psmModel, 
            AsmModel asmModel,
            boolean parallel,
            boolean createTrace) {
        this.psmModel = requireNonNull(psmModel, "psmModel");
        this.asmModel = requireNonNull(asmModel, "asmModel");
        this.psmUtils = new PsmUtils(psmModel.getResourceSet());
        this.targetResource = asmModel.getResource();
        this.targetResourceSet = asmModel.getResourceSet();
    }
    
    public Map<EObject, List<EObject>> execute() {
        log.info("Starting PSM to ASM transformation");
        long startTime = System.currentTimeMillis();
        
        // 1. Create registry and register rules
        TransformationRegistry registry = createRegistry();
        
        // 2. Create context
        TransformationContext context = createContext(registry);
        
        // 3. Execute transformation
        TransformationExecutor executor = TransformationExecutor.builder()
            .registry(registry)
            .context(context)
            .parallel(parallel)
            .build();
        
        TransformationResult result = executor.transform();
        
        // 4. Post-processing
        postProcess(context);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Transformation complete in {}ms", duration);
        
        return buildTraceResult(context);
    }
    
    private TransformationRegistry createRegistry() {
        TransformationRegistry registry = new TransformationRegistry();
        
        // Register in dependency order
        registry.register(NamespaceRules.class);
        registry.register(PrimitiveTypeRules.class);
        registry.register(EnumerationRules.class);
        registry.register(EntityRules.class);
        registry.register(AttributeRules.class);
        registry.register(ReferenceRules.class);
        registry.register(TransferObjectRules.class);
        registry.register(OperationRules.class);
        
        return registry;
    }
    
    private TransformationContext createContext(TransformationRegistry registry) {
        TransformationContext context = new TransformationContext(
            psmUtils::all,
            psmModel.getResourceSet(),
            targetResourceSet,
            null  // Extension registry if needed
        );
        context.setTransformationRegistry(registry);
        return context;
    }
    
    private Map<EObject, List<EObject>> buildTraceResult(TransformationContext context) {
        Map<EObject, List<EObject>> result = new HashMap<>();
        // Build trace from context's element resolution cache
        context.getElementResolutionCache().getAllMappings().forEach((source, targets) -> {
            result.put(source, new ArrayList<>(targets));
        });
        return result;
    }
}
```

### 6.2 Rule Registration Order

Rules should be registered in dependency order - packages before types, types before entities, etc.:

```java
private TransformationRegistry createRegistry() {
    TransformationRegistry registry = new TransformationRegistry();
    
    // Phase 1: Structural containers (packages)
    registry.register(NamespaceRules.class);
    
    // Phase 2: Types (primitives, enumerations)
    registry.register(PrimitiveTypeRules.class);
    registry.register(EnumerationRules.class);
    
    // Phase 3: Entities and their features
    registry.register(EntityRules.class);
    registry.register(AttributeRules.class);
    registry.register(ReferenceRules.class);
    
    // Phase 4: Transfer objects
    registry.register(TransferObjectRules.class);
    
    // Phase 5: Operations
    registry.register(OperationRules.class);
    
    // Phase 6: Actors and security
    registry.register(ActorRules.class);
    
    return registry;
}
```

---

## Step 7: Migrate Helper Functions

### 7.1 Convert EOL Operations to Java

**ETL/EOL:**
```eol
operation JUDOPSM!NumericType isInteger() : Boolean {
    return self.scale <= 0;
}

operation getIntegerClassName(numericType : JUDOPSM!NumericType) : String {
    if (numericType.precision <= 9 and numericType.precision > 0) {
        return "java.lang.Integer";
    } else if (numericType.precision <= 19) {
        return "java.lang.Long";
    } else {
        return "java.math.BigDecimal";
    }
}

operation JUDOPSM!NamedElement getFQName() : String {
    var fqn = self.name;
    var container = self.eContainer;
    while (container.isDefined() and container.isKindOf(JUDOPSM!Package)) {
        fqn = container.name + "::" + fqn;
        container = container.eContainer;
    }
    return fqn;
}
```

**Zeta (Helper class):**
```java
package hu.blackbelt.judo.tatami.psm2asm.zeta;

import hu.blackbelt.judo.meta.psm.type.NumericType;
import hu.blackbelt.judo.meta.psm.namespace.NamedElement;
import hu.blackbelt.judo.meta.psm.namespace.Package;
import org.eclipse.emf.ecore.EObject;

public final class Psm2AsmHelper {
    
    private Psm2AsmHelper() {}
    
    /**
     * Check if a numeric type represents an integer (no decimal places).
     */
    public static boolean isInteger(NumericType numericType) {
        return numericType.getScale() <= 0;
    }
    
    /**
     * Determine the appropriate Java class name for an integer type.
     */
    public static String getIntegerClassName(NumericType numericType) {
        int precision = numericType.getPrecision();
        if (precision <= 9 && precision > 0) {
            return "java.lang.Integer";
        } else if (precision <= 19) {
            return "java.lang.Long";
        } else {
            return "java.math.BigDecimal";
        }
    }
    
    /**
     * Get the fully qualified name of a named element.
     */
    public static String getFQName(NamedElement element) {
        StringBuilder fqn = new StringBuilder(element.getName());
        EObject container = element.eContainer();
        
        while (container instanceof Package pkg) {
            fqn.insert(0, pkg.getName() + "::");
            container = container.eContainer();
        }
        
        return fqn.toString();
    }
    
    /**
     * Check if a type is a decimal (has decimal places).
     */
    public static boolean isDecimal(NumericType numericType) {
        return numericType.getScale() > 0;
    }
    
    /**
     * Get the Java class name for a decimal type.
     */
    public static String getDecimalClassName(NumericType numericType) {
        return "java.math.BigDecimal";
    }
}
```

### 7.2 XMI ID Handling

**Helper methods for ID management:**
```java
public final class IdHelper {
    
    private static final Map<EObject, String> idCache = new ConcurrentHashMap<>();
    
    /**
     * Get the XMI ID of an EMF object.
     */
    public static String getId(EObject obj) {
        // Check cache first
        String cached = idCache.get(obj);
        if (cached != null) {
            return cached;
        }
        
        // Get from resource
        if (obj.eResource() != null) {
            String id = obj.eResource().getURIFragment(obj);
            if (id != null && !id.startsWith("/")) {
                idCache.put(obj, id);
                return id;
            }
        }
        
        return null;
    }
    
    /**
     * Set the XMI ID of an EMF object.
     */
    public static void setId(EObject obj, String id) {
        idCache.put(obj, id);
        if (obj.eResource() instanceof XMIResource xmiResource) {
            xmiResource.setID(obj, id);
        }
    }
    
    /**
     * Clear the ID cache (call between transformations).
     */
    public static void clearCache() {
        idCache.clear();
    }
}
```

---

## Common Pitfalls and Solutions

### Pitfall 1: Recursive Update Issues

**Problem:** Setting bidirectional references during rule execution causes recursive updates or `ConcurrentModificationException`.

**Solution:** Move bidirectional reference setup to post-processing:

```java
// DON'T do this in a rule:
@TransformRule(name = "CreateReference")
public TransformFunction<AssociationEnd, EReference> createReference() {
    return (s, ctx) -> {
        EReference ref = ctx.createTarget(EReference.class);
        // This can cause issues:
        EReference partnerRef = ctx.equivalent(s.getPartner(), EReference.class);
        if (partnerRef != null) {
            ref.setEOpposite(partnerRef);  // DANGER: recursive updates
        }
        return ref;
    };
}

// DO this in postProcess():
private void postProcess(TransformationContext context) {
    psmUtils.all(AssociationEnd.class).forEach(assocEnd -> {
        if (assocEnd.getPartner() != null) {
            EReference ref = context.equivalent(assocEnd, EReference.class);
            EReference partnerRef = context.equivalent(
                assocEnd.getPartner(), EReference.class);
            if (ref != null && partnerRef != null && ref.getEOpposite() == null) {
                ref.setEOpposite(partnerRef);
            }
        }
    });
}
```

### Pitfall 2: Order-Dependent Transformations

**Problem:** ETL automatically resolves dependencies; Zeta requires explicit ordering.

**Solution:** Register rule classes in dependency order:

```java
// Packages must exist before types
registry.register(NamespaceRules.class);  // Phase 1: creates packages

// Types must exist before entities use them
registry.register(PrimitiveTypeRules.class);  // Phase 2
registry.register(EnumerationRules.class);    // Phase 2

// Entities can now reference types
registry.register(EntityRules.class);  // Phase 3
```

### Pitfall 3: Missing Container Addition

**Problem:** Elements created but not added to containers - they exist but aren't part of the model.

**Solution:** Always add to container within rules:

```java
@TransformRule(name = "CreateEnumLiteral")
public TransformFunction<EnumerationMember, EEnumLiteral> createEnumLiteral() {
    return (s, ctx) -> {
        EEnumLiteral literal = ctx.createTarget(EEnumLiteral.class);
        literal.setName(s.getName());
        literal.setValue(s.getOrdinal());
        
        // IMPORTANT: Add to container
        EEnum containerEnum = ctx.equivalent(s.eContainer(), EEnum.class);
        if (containerEnum != null) {
            containerEnum.getELiterals().add(literal);
        } else {
            log.warn("Container enum not found for literal: {}", s.getName());
        }
        
        return literal;
    };
}
```

### Pitfall 4: Null Equivalents

**Problem:** `ctx.equivalent()` returns null when element hasn't been transformed yet.

**Solution:** Check for null or defer to post-processing:

```java
@TransformRule(name = "CreateReference")
public TransformFunction<Reference, EReference> createReference() {
    return (s, ctx) -> {
        EReference ref = ctx.createTarget(EReference.class);
        ref.setName(s.getName());
        
        // Target type might not be transformed yet
        EClass targetClass = ctx.equivalent(s.getTarget(), EClass.class);
        if (targetClass != null) {
            ref.setEType(targetClass);
        } else {
            // Will be set in post-processing
            log.trace("Target class not yet available for: {}", s.getName());
            deferredTypeReferences.add(new DeferredReference(s, ref));
        }
        
        return ref;
    };
}
```

### Pitfall 5: Incorrect Property Access

**Problem:** ETL uses `s.name`; Java requires `s.getName()`.

**Solution:** Use proper Java accessors:

```java
// ETL: t.name = s.name
// Zeta:
t.setName(s.getName());

// ETL: t.abstract = s.`abstract`  (backticks for reserved words)
// Zeta:
t.setAbstract(s.isAbstract());

// ETL: t.interface = false
// Zeta:
t.setInterface(false);
```

### Pitfall 6: Collection Iteration with Modification

**Problem:** Modifying collections while iterating.

**Solution:** Collect to list first or use streams:

```java
// DON'T:
for (EClass cls : pkg.getEClassifiers()) {
    pkg.getEClassifiers().add(newClass);  // ConcurrentModificationException!
}

// DO:
List<EClassifier> toAdd = new ArrayList<>();
for (EClass cls : pkg.getEClassifiers()) {
    toAdd.add(createRelatedClass(cls));
}
pkg.getEClassifiers().addAll(toAdd);

// Or use streams:
List<EClass> newClasses = pkg.getEClassifiers().stream()
    .filter(c -> c instanceof EClass)
    .map(c -> createRelatedClass((EClass) c))
    .toList();
pkg.getEClassifiers().addAll(newClasses);
```

### Pitfall 7: Forgetting to Return Target

**Problem:** ETL implicitly returns the target; Zeta requires explicit return.

**Solution:** Always return the created target:

```java
@TransformRule(name = "CreateEntity")
public TransformFunction<EntityType, EClass> createEntity() {
    return (s, ctx) -> {
        EClass t = ctx.createTarget(EClass.class);
        t.setName(s.getName());
        t.setAbstract(s.isAbstract());
        
        // ... more setup ...
        
        return t;  // DON'T FORGET THIS!
    };
}
```

---

## Migration Checklist

Use this checklist to track your migration progress:

### Analysis Phase
- [ ] Inventoried all ETL files and counted rules
- [ ] Categorized rules by type (greedy, lazy, guarded, etc.)
- [ ] Documented rule dependencies
- [ ] Identified helper functions needing conversion

### Structure Phase
- [ ] Created Zeta directory structure
- [ ] Created rule name constants class
- [ ] Created helper class skeleton

### Migration Phase
- [ ] Migrated namespace/package rules
- [ ] Migrated primitive type rules
- [ ] Migrated enumeration rules
- [ ] Migrated entity/class rules
- [ ] Migrated attribute rules
- [ ] Migrated reference rules
- [ ] Migrated transfer object rules
- [ ] Migrated operation rules
- [ ] Migrated actor/security rules
- [ ] Converted lazy rules to helpers or @Lazy methods
- [ ] Migrated all EOL helper operations

### Integration Phase
- [ ] Implemented main transformation class
- [ ] Implemented post-processing
- [ ] Set correct rule registration order
- [ ] Added proper logging

### Testing Phase
- [ ] Created TransformationType enum for parameterized tests
- [ ] Set up parameterized tests with @EnumSource
- [ ] Verified ETL-Zeta output equivalence
- [ ] Added performance comparison tests
- [ ] Fixed any differences found

### Documentation Phase
- [ ] Documented any module-specific patterns
- [ ] Added inline code comments where needed
- [ ] Updated README with transformation usage

---

## Next Steps

After completing migration:

1. **Run Equivalence Tests** - See [Dual-Engine Testing](dual-engine-testing.md)
2. **Performance Benchmark** - Compare ETL vs Zeta execution times
3. **Code Review** - Have the migration reviewed by team
4. **Deploy** - Update build configuration to use Zeta engine

---

**Previous**: [Syntax Mapping](syntax-mapping.md) | **Next**: [Feature Parity](feature-parity.md)
