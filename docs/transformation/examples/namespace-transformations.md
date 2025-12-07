# Namespace Transformations

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Namespace Transformations

Examples of namespace/package hierarchy transformations.

## Namespace to Schema

```java
@TransformationContext(source = Namespace.class, target = Schema.class)
public class NamespaceTransformations {
    
    @TransformRule(name = "Namespace2Schema")
    public TransformFunction<Namespace, Schema> namespace2Schema() {
        return (ns, ctx) -> {
            Schema schema = ctx.createTarget(Schema.class);
            schema.setName(ns.getName());
            
            // Build qualified name from hierarchy
            schema.setQualifiedName(buildQualifiedName(ns));
            
            // Transform nested namespaces
            for (Namespace child : ns.getSubNamespaces()) {
                Schema childSchema = ctx.equivalent(child, Schema.class);
                schema.getSubSchemas().add(childSchema);
            }
            
            return schema;
        };
    }
    
    private String buildQualifiedName(Namespace ns) {
        List<String> parts = new ArrayList<>();
        Namespace current = ns;
        while (current != null) {
            parts.add(0, current.getName());
            current = current.getParent();
        }
        return String.join("::", parts);
    }
}
```

## Hierarchical Package Structure

```java
@TransformRule(name = "Package2Module")
public TransformFunction<Package, Module> package2Module() {
    return (pkg, ctx) -> {
        Module module = ctx.createTarget(Module.class);
        module.setName(pkg.getName());
        
        // Transform all classes in package
        for (ClassType cls : pkg.getClasses()) {
            TypeDefinition typeDef = ctx.equivalent(cls, TypeDefinition.class);
            module.getTypes().add(typeDef);
        }
        
        // Handle parent package reference
        if (pkg.getParentPackage() != null) {
            Module parentModule = ctx.equivalent(pkg.getParentPackage(), Module.class);
            module.setParentModule(parentModule);
        }
        
        return module;
    };
}
```

## Root Namespace Detection

```java
@TransformRule(name = "RootNamespace2RootSchema")
@Guard(method = "isRootNamespace")
public TransformFunction<Namespace, Schema> rootNamespace2RootSchema() {
    return (ns, ctx) -> {
        Schema schema = ctx.createTarget(Schema.class);
        schema.setName(ns.getName());
        schema.setRoot(true);
        return schema;
    };
}

private boolean isRootNamespace(EObject element, TransformationContext ctx) {
    return ((Namespace) element).getParent() == null;
}
```

---

**Previous**: [Entity Transformations](entity-transformations.md) | **Next**: [Service Transformations](service-transformations.md)
