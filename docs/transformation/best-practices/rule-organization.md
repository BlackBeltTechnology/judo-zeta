# Rule Organization

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Rule Organization

Organize transformation rules for maintainability and clarity.

## One Context Class Per Source Type

```java
// Good: Focused responsibility
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations { ... }

@TransformationContext(source = Attribute.class, target = Column.class)
public class AttributeTransformations { ... }

@TransformationContext(source = Namespace.class, target = Schema.class)
public class NamespaceTransformations { ... }
```

## Package Structure

```
com.example.transformation/
├── entity/
│   ├── EntityTypeTransformations.java
│   ├── AttributeTransformations.java
│   └── ReferenceTransformations.java
├── namespace/
│   └── NamespaceTransformations.java
├── operation/
│   └── OperationTransformations.java
├── extensions/
│   ├── EntityTypeExtensions.java
│   └── NamespaceExtensions.java
└── constants/
    └── RuleNames.java
```

## Splitting Large Transformations

When a context class grows too large, split by concern:

```java
// Instead of one large class:
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeTransformations {
    // 20+ rules...
}

// Split into focused classes:
@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeBasicTransformations {
    // Basic entity → table transformation
}

@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeInheritanceTransformations {
    // Inheritance-related transformations
}

@TransformationContext(source = EntityType.class, target = Table.class)
public class EntityTypeOperationTransformations {
    // Operation-related transformations
}
```

## Registration Order

Register transformation classes in dependency order:

```java
TransformationRegistry registry = new TransformationRegistry();

// 1. Base/abstract rules first
registry.register(BaseTransformations.class);

// 2. Then specific rules
registry.register(NamespaceTransformations.class);
registry.register(EntityTypeTransformations.class);
registry.register(AttributeTransformations.class);

// 3. Finally, dependent rules
registry.register(ReferenceTransformations.class);
registry.register(OperationTransformations.class);
```

## Module Organization for Large Projects

```
transformation-core/           # Framework
transformation-esm2psm/        # ESM → PSM transformation
  └── src/main/java/
      └── hu.blackbelt.judo.tatami.esm2psm/
          ├── Esm2PsmTransformations.java     # Entry point
          ├── namespace/
          ├── entity/
          ├── service/
          └── extensions/
transformation-psm2asm/        # PSM → ASM transformation
```

---

**Previous**: [Rule Naming](rule-naming.md) | **Next**: [Performance](performance.md)
