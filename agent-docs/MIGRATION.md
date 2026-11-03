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

## Critical Migration Patterns

### Pattern 1: Helper Method → @Lazy Rule

**DO NOT use helper methods to create elements that are ETL rules.** Convert them to @Lazy rules.

**WRONG - Helper Method:**
```java
// WRONG! This should be a @Lazy rule
private Button createBackButton(TransferObjectView source, TransformationContext ctx) {
    Button button = ctx.createTarget(Button.class);
    button.setName(fqName(source) + "::Back");
    return button;
}

// Called from another rule:
Button backButton = createBackButton(source, ctx);
```

**CORRECT - @Lazy Rule:**
```java
@TransformRule(name = "TransferObjectViewBackButton")
@Lazy
public TransformFunction<TransferObjectView, Button> transferObjectViewBackButton() {
    return (source, ctx) -> {
        Button target = ctx.createTarget(Button.class);
        target.setName(fqName(source) + "::Back");
        return target;
    };
}

// Called via equivalent():
Button backButton = ctx.equivalent(source, "TransferObjectViewBackButton");
```

**Why This Matters:**
- **Caching**: `ctx.equivalent()` caches results - helper methods don't
- **XMI IDs**: @Lazy rules generate consistent XMI IDs based on (source, ruleName)
- **ETL Semantics**: ETL uses `s.equivalent("RuleName")` - Zeta must match

### Pattern 2: Rule-Named equivalent() Calls

**When ETL uses `s.equivalent("RuleName")`, Zeta must use `ctx.equivalent(source, "RuleName")`.**

**ETL:**
```etl
t.icon = s.equivalent("TransferObjectViewBackButtonIcon");
t.actionDefinition = s.equivalent("TransferObjectViewBackActionDefinition");
```

**WRONG - Type-based lookup:**
```java
// WRONG! This does type-based lookup, not rule-named
Icon icon = ctx.equivalent(source, Icon.class);
```

**CORRECT - Rule-named lookup:**
```java
// CORRECT! Uses rule name like ETL
Icon icon = ctx.equivalent(source, "TransferObjectViewBackButtonIcon");
BackActionDefinition actionDef = ctx.equivalent(source, "TransferObjectViewBackActionDefinition");
```

### Pattern 3: equivalentDiscriminated with null → equivalent

**When discriminator is null, use `ctx.equivalent(source, ruleName)` instead.**

**WRONG:**
```java
// WRONG! When discriminator is null, this falls back to type-based lookup
BackActionDefinition actionDef = ctx.equivalentDiscriminated(source,
    BackActionDefinition.class, "RuleName", null);
```

**CORRECT:**
```java
// CORRECT! Use rule-named equivalent when discriminator is null
BackActionDefinition actionDef = ctx.equivalent(source, "RuleName");
```

### Pattern 4: @Lazy Rules Have NO Guards

**ETL @lazy rules do NOT have guards - they are invoked on-demand.** Zeta @Lazy rules must follow the same pattern.

**WRONG:**
```java
@TransformRule(name = "TransferObjectViewBackActionDefinition")
@Lazy
@Guard(method = "viewGuard")  // WRONG! @Lazy rules should not have guards
public TransformFunction<TransferObjectView, BackActionDefinition> rule() { ... }
```

**CORRECT:**
```java
@TransformRule(name = "TransferObjectViewBackActionDefinition")
@Lazy  // No @Guard - invoked on-demand via ctx.equivalent()
public TransformFunction<TransferObjectView, BackActionDefinition> rule() { ... }
```

### Pattern 5: No Fallback Creation

**If `ctx.equivalent()` returns null, DO NOT create the element inline.** This indicates a missing rule or guard issue.

**WRONG:**
```java
Icon icon = ctx.equivalent(source, "VisualElementIcon");
if (icon == null) {
    // WRONG! Don't create fallback - investigate why it's null
    icon = ctx.createTarget(Icon.class);
    icon.setIconName(source.getIconName());
}
target.setIcon(icon);
```

**CORRECT:**
```java
// If equivalent returns null, don't set the icon - investigate why it's null
Icon icon = ctx.equivalent(source, "VisualElementIcon");
target.setIcon(icon);  // May be null, that's OK
```

### Pattern 6: Use Constants for Rule Names

**When calling `ctx.equivalent()` with a rule name, ALWAYS use a constant.** The same constant MUST be used in both `@TransformRule(name = ...)` and `ctx.equivalent()` calls.

**WRONG - String literals:**
```java
@TransformRule(name = "TransferObjectViewBackButton")  // String literal
@Lazy
public TransformFunction<TransferObjectView, Button> rule() { ... }

// Different string, prone to typos
Button backButton = ctx.equivalent(source, "TransferObjectViewBackButon");  // Typo!
```

**CORRECT - Use constants:**
```java
// In RuleNames.java
public static final String TRANSFER_OBJECT_VIEW_BACK_BUTTON = "TransferObjectViewBackButton";

// In TransformationClass.java
@TransformRule(name = TRANSFER_OBJECT_VIEW_BACK_BUTTON)  // Uses constant
@Lazy
public TransformFunction<TransferObjectView, Button> rule() { ... }

// In another file - compile-time checked
Button backButton = ctx.equivalent(source, TRANSFER_OBJECT_VIEW_BACK_BUTTON);
```

### Pattern 7: Split Composite Elements into Separate Rules

**When ETL has separate rules for related elements (Button, Icon, ActionDefinition), Zeta must have separate @Lazy rules too.**

**ETL:**
```etl
@lazy
rule TransferObjectViewBackButtonIcon
    transform s : ESM!TransferObjectView to t : UI!Icon { ... }

@lazy
rule TransferObjectViewBackButton
    transform s : ESM!TransferObjectView to t : UI!Button {
    t.icon = s.equivalent("TransferObjectViewBackButtonIcon");
}
```

**ZETA:**
```java
@TransformRule(name = "TransferObjectViewBackButtonIcon")
@Lazy
public TransformFunction<TransferObjectView, Icon> backButtonIcon() {
    return (source, ctx) -> {
        Icon target = ctx.createTarget(Icon.class);
        target.setIconName("arrow-left");
        return target;
    };
}

@TransformRule(name = "TransferObjectViewBackButton")
@Lazy
public TransformFunction<TransferObjectView, Button> backButton() {
    return (source, ctx) -> {
        Button target = ctx.createTarget(Button.class);
        // Get icon via equivalent - NOT inline creation
        Icon icon = ctx.equivalent(source, "TransferObjectViewBackButtonIcon");
        target.setIcon(icon);
        return target;
    };
}
```

### Pattern 8: Container Rules Use equivalent() for Children

**Container rules should get children via `ctx.equivalent()`, not by creating them inline.**

**WRONG:**
```java
@TransformRule(name = "TransferObjectViewButtonGroup")
@Lazy
public TransformFunction<TransferObjectView, ButtonGroup> buttonGroup() {
    return (source, ctx) -> {
        ButtonGroup target = ctx.createTarget(ButtonGroup.class);
        // WRONG! Don't create buttons inline
        Button backButton = ctx.createTarget(Button.class);
        backButton.setName(fqName(source) + "::Back");
        target.getButtons().add(backButton);
        return target;
    };
}
```

**CORRECT:**
```java
@TransformRule(name = "TransferObjectViewButtonGroup")
@Lazy
public TransformFunction<TransferObjectView, ButtonGroup> buttonGroup() {
    return (source, ctx) -> {
        ButtonGroup target = ctx.createTarget(ButtonGroup.class);
        // Get buttons via equivalent - triggers @Lazy rules
        Button backButton = ctx.equivalent(source, "TransferObjectViewBackButton");
        if (backButton != null) target.getButtons().add(backButton);
        Button refreshButton = ctx.equivalent(source, "TransferObjectViewRefreshButton");
        if (refreshButton != null) target.getButtons().add(refreshButton);
        return target;
    };
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
| Helper method instead of @Lazy | Convert helper to @Lazy rule, call via equivalent() |
| Type-based equivalent() | Use rule-named equivalent() to match ETL |
| Guard on @Lazy rule | Remove guard - @Lazy rules are invoked on-demand |
| Fallback creation | Don't create if equivalent() returns null |

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
