# Getting Started with Judo Zeta Transformation

**Navigation**: [Documentation Hub](../index.md) > [Transformation](index.md) > Getting Started

This guide will help you install the Judo Zeta Transformation Framework and write your first model transformation in under 15 minutes.

## Prerequisites

- Java 21 JDK (Zulu, Temurin, or Oracle)
- Maven 3.9.4+ or your build tool of choice
- Source and target EMF metamodels (e.g., Ecore models)
- Basic understanding of Java and EMF

## Installation

### Maven Dependencies

Add the following dependencies to your `pom.xml`:

```xml
<dependencies>
    <!-- Core transformation framework -->
    <dependency>
        <groupId>hu.blackbelt.judo.zeta</groupId>
        <artifactId>hu.blackbelt.judo.zeta.transformation-core</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    
    <!-- Shared annotations -->
    <dependency>
        <groupId>hu.blackbelt.judo.zeta</groupId>
        <artifactId>hu.blackbelt.judo.zeta.annotations</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    
    <!-- Common utilities -->
    <dependency>
        <groupId>hu.blackbelt.judo.zeta</groupId>
        <artifactId>hu.blackbelt.judo.zeta.common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### Eclipse P2 Update Site

For Eclipse IDE plugin installation:

1. Open **Help → Install New Software**
2. Click **Add...** and enter:
   - **Name**: `Judo Zeta Transformation`
   - **Location**: `https://nexus.judo.technology/repository/p2-judong/judo-zeta/develop/`
3. Select **Judo Zeta Transformation Framework**
4. Click **Next**, accept licenses, and **Finish**
5. Restart Eclipse

### OSGi Bundle (Apache Karaf)

```bash
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.common/1.0.0-SNAPSHOT
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.annotations/1.0.0-SNAPSHOT
karaf@root()> bundle:install -s mvn:hu.blackbelt.judo.zeta/hu.blackbelt.judo.zeta.transformation-core/1.0.0-SNAPSHOT
```

## Your First Transformation

Let's create a simple transformation that converts `EntityType` elements from a source model to `Table` elements in a target model.

### Step 1: Create a Transformation Class

```java
package com.example.transformation;

import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.transformation.core.*;
import com.example.source.EntityType;  // Your source metamodel
import com.example.source.Attribute;
import com.example.target.Table;       // Your target metamodel
import com.example.target.Column;

@TransformationContext(source = EntityType.class, target = Table.class)
public class Entity2TableTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            // 1. Create target element
            Table table = ctx.createTarget(Table.class);
            
            // 2. Map properties
            table.setName(entity.getName());
            
            // 3. Transform related elements
            for (Attribute attr : entity.getAttributes()) {
                Column column = ctx.equivalent(attr, Column.class);
                table.getColumns().add(column);
            }
            
            return table;
        };
    }
    
    @TransformRule(name = "Attribute2Column")
    public TransformFunction<Attribute, Column> attribute2Column() {
        return (attr, ctx) -> {
            Column column = ctx.createTarget(Column.class);
            column.setName(attr.getName());
            column.setType(mapType(attr.getType()));
            return column;
        };
    }
    
    private String mapType(String sourceType) {
        return switch (sourceType) {
            case "String" -> "VARCHAR(255)";
            case "Integer" -> "INT";
            case "Boolean" -> "BOOLEAN";
            default -> "TEXT";
        };
    }
}
```

**What's happening here?**

1. **`@TransformationContext`** - Declares the source and target types for this transformation class
2. **`@TransformRule`** - Defines a transformation rule with a unique name
3. **`TransformFunction<S, T>`** - Functional interface that transforms source (S) to target (T)
4. **`ctx.createTarget()`** - Creates a new target element in the target model
5. **`ctx.equivalent()`** - Gets or creates the transformed equivalent of a source element

### Step 2: Set Up and Execute the Transformation

```java
import hu.blackbelt.judo.zeta.transformation.core.*;
import hu.blackbelt.judo.zeta.common.*;
import org.eclipse.emf.ecore.resource.ResourceSet;
import java.util.Collection;

public class TransformationExample {
    
    public static void main(String[] args) {
        // 1. Load source model
        ResourceSet sourceResourceSet = loadSourceModel();
        
        // 2. Create empty target ResourceSet
        ResourceSet targetResourceSet = createTargetResourceSet();
        
        // 3. Create registry and register transformation class
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(Entity2TableTransformations.class);
        
        // 4. Create supporting components
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new SimpleModelProvider();
        
        // 5. Create transformation context
        TransformationContext context = new TransformationContext(
            modelProvider,
            sourceResourceSet,
            targetResourceSet,
            extensionRegistry
        );
        context.setTransformationRegistry(registry);
        // Note: For generated metamodels, setTargetPackage is optional.
        // The framework auto-discovers EPackages from generated Java classes.
        
        // 6. Create executor and run transformation
        TransformationExecutor executor = new TransformationExecutor(
            registry, 
            context, 
            true  // Enable parallel execution for large models
        );
        
        Collection<EObject> sourceElements = modelProvider.getAllContents(
            sourceResourceSet, 
            EntityType.class
        );
        
        TransformationResult result = executor.transform(sourceElements);
        
        // 7. Access results
        System.out.println("Transformation complete!");
        System.out.println("Elements transformed: " + result.getTrace().getEntryCount());
        
        // 8. Save trace for debugging
        result.getTrace().saveToJson(new File("transformation-trace.json"));
        
        // 9. Save target model
        saveTargetModel(targetResourceSet);
    }
}
```

### Step 3: Run and See Results

When you run this with a source model containing entities and attributes:

```
Transformation complete!
Elements transformed: 15
```

The transformation trace JSON shows what was transformed:

```json
{
  "traceEntries": [
    {
      "ruleName": "EntityType2Table",
      "source": {"type": "EntityType", "id": "customer", "name": "Customer"},
      "target": {"type": "Table", "id": "table-customer", "name": "Customer"},
      "primary": true
    },
    {
      "ruleName": "Attribute2Column",
      "source": {"type": "Attribute", "id": "customer-name", "name": "name"},
      "target": {"type": "Column", "id": "col-name", "name": "name"},
      "primary": true
    }
  ],
  "entryCount": 15,
  "timestamp": 1699123456789
}
```

## Adding Lazy Rules

Some transformations should only execute when needed. Use `@Lazy`:

```java
@TransformationContext(source = EntityType.class, target = Table.class)
public class Entity2TableTransformations {
    
    @TransformRule(name = "EntityType2Table")
    public TransformFunction<EntityType, Table> entityType2Table() {
        return (entity, ctx) -> {
            Table table = ctx.createTarget(Table.class);
            table.setName(entity.getName());
            
            // This triggers the lazy Reference2ForeignKey rule
            if (entity.getSuperType() != null) {
                Table superTable = ctx.equivalent(entity.getSuperType(), Table.class);
                table.setParentTable(superTable);
            }
            
            return table;
        };
    }
    
    // Only executes when ctx.equivalent() is called for a Reference
    @TransformRule(name = "Reference2ForeignKey")
    @Lazy
    public TransformFunction<Reference, ForeignKey> reference2ForeignKey() {
        return (ref, ctx) -> {
            ForeignKey fk = ctx.createTarget(ForeignKey.class);
            fk.setName("fk_" + ref.getName());
            fk.setReferencedTable(ctx.equivalent(ref.getTarget(), Table.class));
            return fk;
        };
    }
}
```

## Adding Guards for Conditional Transformation

Only transform elements that meet certain conditions:

```java
@TransformRule(name = "AbstractEntity2AbstractTable")
@Guard(method = "isAbstract")
public TransformFunction<EntityType, Table> abstractEntity2AbstractTable() {
    return (entity, ctx) -> {
        Table table = ctx.createTarget(Table.class);
        table.setName(entity.getName());
        table.setAbstract(true);
        return table;
    };
}

private boolean isAbstract(EObject element, TransformationContext ctx) {
    return ((EntityType) element).isAbstract();
}
```

## Rule Inheritance with @Extends

Reuse transformation logic with inheritance:

```java
@TransformRule(name = "NamedElement2NamedType")
@Abstract
public TransformFunction<NamedElement, NamedType> namedElement2NamedType() {
    return (source, ctx) -> {
        NamedType target = ctx.createTarget(NamedType.class);
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        return target;
    };
}

@TransformRule(name = "EntityType2Table")
@Extends("NamedElement2NamedType")
public TransformFunction<EntityType, Table> entityType2Table() {
    return (entity, ctx) -> {
        // Execute parent rule first
        Table table = ctx.executeParentRule("NamedElement2NamedType", entity);
        
        // Add entity-specific transformations
        table.setSchema(ctx.equivalent(entity.getNamespace(), Schema.class));
        
        return table;
    };
}
```

## Quick Reference Card

| Task | Annotation/Method | Example |
|------|-------------------|---------|
| Define transformation class | `@TransformationContext` | `@TransformationContext(source = A.class, target = B.class)` |
| Define transformation rule | `@TransformRule` | `@TransformRule(name = "A2B")` |
| Lazy evaluation | `@Lazy` | `@Lazy` on rule method |
| Abstract (inheritance only) | `@Abstract` | `@Abstract` on parent rule |
| Rule inheritance | `@Extends` | `@Extends("ParentRule")` |
| Match subtypes | `@Greedy` | `@Greedy` matches type and subtypes |
| Primary result | `@Primary` | `@Primary` for preferred transformation |
| Conditional | `@Guard` | `@Guard(method = "guardMethod")` |
| Create target | `ctx.createTarget()` | `ctx.createTarget(Table.class)` |
| Get equivalent | `ctx.equivalent()` | `ctx.equivalent(source, Target.class)` |
| Get all equivalents | `ctx.equivalents()` | `ctx.equivalents(source, Target.class)` |
| Call parent rule | `ctx.executeParentRule()` | `ctx.executeParentRule("Parent", source)` |

## Next Steps

Now that you have a basic transformation working, explore:

- **[Core Concepts](user-guide/core-concepts.md)** - Deep dive into transformation concepts
- **[Element Resolution](user-guide/element-resolution.md)** - Understanding equivalent() semantics
- **[Rule Inheritance](user-guide/rule-inheritance.md)** - Code reuse with @Abstract and @Extends
- **[Examples](examples/simple-transformations.md)** - More real-world examples
- **[ETL Comparison](etl-comparison/overview.md)** - Coming from Epsilon ETL?

## Common Setup Patterns

### OSGi Declarative Services

```java
import org.osgi.service.component.annotations.*;

@Component(immediate = true)
public class ModelTransformer {
    
    @Reference
    private ModelProvider modelProvider;
    
    @Activate
    public void activate() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(Entity2TableTransformations.class);
        
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        
        TransformationContext context = new TransformationContext(
            modelProvider,
            sourceResourceSet,
            targetResourceSet,
            extensionRegistry
        );
        context.setTransformationRegistry(registry);
        
        TransformationExecutor executor = new TransformationExecutor(registry, context, true);
        TransformationResult result = executor.transform(sourceElements);
        
        // Process results...
    }
}
```

### Multiple Transformation Classes

```java
TransformationRegistry registry = new TransformationRegistry();
registry.register(Entity2TableTransformations.class);
registry.register(Namespace2SchemaTransformations.class);
registry.register(Operation2ProcedureTransformations.class);
registry.register(Reference2ForeignKeyTransformations.class);
```

### Package Resolution

For **generated metamodels**, no package registration is needed:

```java
// EPackage is auto-discovered from the Java class
Table table = ctx.createTarget(Table.class);
```

For **dynamic EMF** (runtime-created packages), register and specify explicitly:

```java
context.registerTargetPackage(dynamicPackage);
EObject obj = ctx.createTarget(dynamicType, dynamicPackage);
```

### Parallel Execution for Large Models

```java
// Parallel execution is automatic when element count >= 5000
TransformationExecutor executor = new TransformationExecutor(
    registry, 
    context, 
    true  // Enable parallel execution
);

// Elements are partitioned into chunks of 100 and processed in parallel
TransformationResult result = executor.transform(largeElementCollection);
```

## Troubleshooting

**Problem**: Rule not found for source element

**Solution**: Make sure:
1. Class has `@TransformationContext` annotation with correct source type
2. Class is registered with `registry.register(MyClass.class)`
3. Rule method has `@TransformRule` annotation
4. Rule returns `TransformFunction<SourceType, TargetType>`

**Problem**: `equivalent()` returns null

**Solution**: Check if:
1. The source element has a matching rule defined
2. The rule is not `@Lazy` (or call equivalent to trigger it)
3. The target type matches what the rule returns

**Problem**: Circular inheritance detected

**Solution**: Check your `@Extends` annotations for cycles:
```
Rule A extends B
Rule B extends C
Rule C extends A  // This creates a cycle!
```

---

**Previous**: [Transformation Hub](index.md) | **Next**: [Core Concepts](user-guide/core-concepts.md)
