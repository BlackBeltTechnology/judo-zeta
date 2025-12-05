# Rule Naming Conventions

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Best Practices](rule-naming.md) > Rule Naming

Consistent naming conventions make transformation rules easier to understand and maintain.

## Naming Pattern

Use `Source2Target` format:

```java
@TransformRule(name = "EntityType2Table")           // Good
@TransformRule(name = "Attribute2Column")           // Good
@TransformRule(name = "Namespace2Schema")           // Good
@TransformRule(name = "Reference2ForeignKey")       // Good
```

## Avoid Generic Names

```java
// Bad: Too generic
@TransformRule(name = "Transform")
@TransformRule(name = "Convert")
@TransformRule(name = "Process")

// Good: Specific
@TransformRule(name = "EntityType2Table")
@TransformRule(name = "AbstractEntity2AbstractTable")
```

## Rule Name Constants

Define constants to avoid typos and enable refactoring:

```java
public final class RuleNames {
    public static final String ENTITY_TYPE_2_TABLE = "EntityType2Table";
    public static final String ATTRIBUTE_2_COLUMN = "Attribute2Column";
    public static final String NAMESPACE_2_SCHEMA = "Namespace2Schema";
    public static final String REFERENCE_2_FOREIGN_KEY = "Reference2ForeignKey";
    
    // For inheritance
    public static final String BASE_NAMED_ELEMENT = "BaseNamedElement";
    
    private RuleNames() {}
}
```

Usage:

```java
@TransformRule(name = RuleNames.ENTITY_TYPE_2_TABLE)
public TransformFunction<EntityType, Table> entityType2Table() { ... }

@TransformRule(name = RuleNames.ATTRIBUTE_2_COLUMN)
@Extends(RuleNames.BASE_NAMED_ELEMENT)
public TransformFunction<Attribute, Column> attribute2Column() { ... }
```

## Modifiers in Names

Include modifiers for clarity:

```java
// Abstract rules - prefix with "Base" or "Abstract"
@TransformRule(name = "BaseNamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> baseNamedElement2NamedType() { ... }

// Guarded rules - indicate the condition
@TransformRule(name = "AbstractEntity2AbstractTable")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2AbstractTable() { ... }

@TransformRule(name = "ConcreteEntity2ConcreteTable")
@Guard(method = "isConcrete")
public TransformFunction<EntityType, Table> concreteEntity2ConcreteTable() { ... }

// Lazy rules - can indicate optional nature
@TransformRule(name = "OptionalReference2ForeignKey")
@Lazy
public TransformFunction<Reference, ForeignKey> optionalReference2ForeignKey() { ... }
```

## Method Naming

Match method name to rule name (camelCase):

```java
@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> entityType2Table() { ... }  // Good

@TransformRule(name = "EntityType2Table")
public TransformFunction<EntityType, Table> transform() { ... }  // Bad: mismatch
```

---

**Previous**: [Extension Methods](../user-guide/extension-methods.md) | **Next**: [Rule Organization](rule-organization.md)
