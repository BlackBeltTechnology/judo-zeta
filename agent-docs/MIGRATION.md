# ETL to Zeta Migration Reference

Focused reference for migrating ETL transformations to Zeta. For full details, see `docs/transformation/etl-comparison/`.

## Quick Syntax Mapping

| ETL | Zeta |
|-----|------|
| `rule Name transform s : S to t : T { }` | `@TransformRule(name="Name") TransformFunction<S,T>` |
| `@greedy` | `@Greedy` |
| `@lazy` | `@Lazy` |
| `@abstract` | `@Abstract` |
| `@primary` | `@Primary` |
| `extends Parent` | `@Extends("Parent")` |
| `guard: condition` | `@Guard(method="guardMethod")` |
| `s.name` | `s.getName()` |
| `t.name = x` | `t.setName(x)` |
| `s.equivalent()` | `ctx.equivalent(s, TargetType.class)` |
| `s.equivalents()` | `ctx.equivalents(s, TargetType.class)` |
| `pre { }` | `@PreExecution` method |
| `post { }` | `@PostExecution` method |

## Rule Migration Template

**ETL:**
```etl
@greedy
rule EntityToTable
    transform s : PSM!EntityType
    to t : RDBMS!Table {
        guard: not s.`abstract`
        t.name = s.name;
        t.columns.addAll(s.attributes.equivalent());
    }
```

**Zeta:**
```java
public boolean notAbstractGuard(EObject src, TransformationContext ctx) {
    return src instanceof EntityType e && !e.isAbstract();
}

@TransformRule(name = "EntityToTable")
@Greedy
@Guard(method = "notAbstractGuard")
@Transform(type = EntityType.class)
@To(type = Table.class)
public TransformFunction<EntityType, Table> entityToTable() {
    return (s, ctx) -> {
        Table t = ctx.createTarget(Table.class);
        t.setName(s.getName());
        for (Attribute attr : s.getAttributes()) {
            Column col = ctx.equivalent(attr, Column.class);
            if (col != null) t.getColumns().add(col);
        }
        return t;
    };
}
```

## Multi-Output Rule

**ETL:**
```etl
rule Entity2Classes
    transform s : EntityType
    to main : EClass, ref : EClass {
        main.name = s.name;
        ref.name = s.name + "__Ref";
    }
```

**Zeta:**
```java
@TransformRule(name = "Entity2Classes")
@Transform(type = EntityType.class)
@To(type = EClass.class)
public TransformFunction<EntityType, EClass> entity2Classes() {
    return (s, ctx) -> {
        EClass main = ctx.createTarget(EClass.class);
        main.setName(s.getName());
        
        EClass ref = ctx.create(EClass.class);
        ref.setName(s.getName() + "__Ref");
        ctx.registerEquivalent(s, ref, "Reference");
        
        return main;
    };
}

// Access secondary output:
EClass refClass = ctx.equivalent(entity, EClass.class, "Reference");
```

## Rule Inheritance

**ETL:**
```etl
@abstract
rule NamedElement2Named
    transform s : NamedElement
    to t : ENamedElement {
        t.name = s.name;
    }

rule Entity2Class
    transform s : EntityType
    to t : EClass
    extends NamedElement2Named {
        t.abstract = s.`abstract`;
    }
```

**Zeta:**
```java
@TransformRule(name = "NamedElement2Named")
@Abstract
public TransformFunction<NamedElement, ENamedElement> namedElement2Named() {
    return (s, ctx) -> {
        ENamedElement t = ctx.createTarget(ENamedElement.class);
        t.setName(s.getName());
        return t;
    };
}

@TransformRule(name = "Entity2Class")
@Extends("NamedElement2Named")
public TransformFunction<EntityType, EClass> entity2Class() {
    return (s, ctx) -> {
        EClass t = ctx.executeParentRule("NamedElement2Named", s);
        t.setAbstract(s.isAbstract());
        return t;
    };
}
```

## EOL to Java Collections

| EOL | Java |
|-----|------|
| `col.first()` | `col.isEmpty() ? null : col.get(0)` |
| `col.select(x \| cond)` | `col.stream().filter(x -> cond).toList()` |
| `col.collect(x \| expr)` | `col.stream().map(x -> expr).toList()` |
| `col.exists(x \| cond)` | `col.stream().anyMatch(x -> cond)` |
| `col.forAll(x \| cond)` | `col.stream().allMatch(x -> cond)` |
| `col.flatten()` | `col.stream().flatMap(Collection::stream).toList()` |

## Directory Structure

```
src/main/java/.../zeta/
├── <Module>ZetaTransformation.java  # Main class
├── <Module>Helper.java               # EOL operations
├── <Module>RuleNames.java            # Constants
└── rules/
    ├── NamespaceRules.java
    ├── TypeRules.java
    └── ...
```

## Main Transformation Class

```java
public class Psm2AsmZetaTransformation {
    public Map<EObject, List<EObject>> execute() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(NamespaceRules.class);  // Order matters!
        registry.register(TypeRules.class);
        registry.register(EntityRules.class);
        
        TransformationContext ctx = new TransformationContext(...);
        ctx.setTransformationRegistry(registry);
        
        TransformationExecutor executor = TransformationExecutor.builder()
            .registry(registry)
            .context(ctx)
            .build();
        
        executor.transform();
        postProcess(ctx);  // Bidirectional refs, etc.
        
        return buildTrace(ctx);
    }
}
```

## Common Pitfalls

| Issue | Solution |
|-------|----------|
| Forgot `return t;` | Always return created target |
| `ctx.equivalent()` returns null | Element not transformed yet; defer or check null |
| ConcurrentModificationException | Set bidirectional refs in postProcess() |
| Wrong order | Register rules in dependency order |
| Missing container add | `containerPkg.getEClassifiers().add(t);` |

## Post-Processing Pattern

```java
private void postProcess(TransformationContext ctx) {
    // Add roots to resource
    psmUtils.all(Model.class).forEach(m -> {
        EPackage pkg = ctx.equivalent(m, EPackage.class);
        if (pkg != null) targetResource.getContents().add(pkg);
    });
    
    // Set bidirectional references
    psmUtils.all(AssociationEnd.class).forEach(ae -> {
        if (ae.getPartner() != null) {
            EReference ref = ctx.equivalent(ae, EReference.class);
            EReference partner = ctx.equivalent(ae.getPartner(), EReference.class);
            if (ref != null && partner != null && ref.getEOpposite() == null) {
                ref.setEOpposite(partner);
            }
        }
    });
}
```

## Testing Pattern

```java
@ParameterizedTest
@EnumSource(TransformationType.class)
void testTransformation(TransformationType type) {
    PsmModel psm = createTestModel();
    AsmModel asm = AsmModel.buildAsmModel().name("test").build();
    
    if (type == TransformationType.ZETA) {
        Psm2AsmZetaTransformation.builder()
            .psmModel(psm).asmModel(asm).build().execute();
    } else {
        Psm2AsmEtlTransformation.builder()
            .psmModel(psm).asmModel(asm).build().execute();
    }
    
    assertNotNull(asm.getResource());
}
```

## Equivalence Verification

```java
@Test
void testEtlZetaEquivalence() {
    PsmModel psm1 = createModel(), psm2 = createModel();
    AsmModel etl = runEtl(psm1), zeta = runZeta(psm2);
    
    ModelComparator.assertEquivalent(
        etl.getResource(), 
        zeta.getResource()
    );
}
```
