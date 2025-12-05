# Service Transformations

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Service Transformations

Examples of service layer transformations (TransferObjects, Operations).

## TransferObjectType Transformation

```java
@TransformationContext(source = TransferObjectType.class, target = TransferDTO.class)
public class TransferObjectTransformations {
    
    @TransformRule(name = "TransferObjectType2TransferDTO")
    public TransformFunction<TransferObjectType, TransferDTO> transferObjectType2TransferDTO() {
        return (to, ctx) -> {
            TransferDTO dto = ctx.createTarget(TransferDTO.class);
            dto.setName(to.getName());
            
            // Transform attributes
            for (TransferAttribute attr : to.getAttributes()) {
                TransferField field = ctx.equivalent(attr, TransferField.class);
                dto.getFields().add(field);
            }
            
            // Transform relations
            for (TransferRelation rel : to.getRelations()) {
                TransferReference ref = ctx.equivalent(rel, TransferReference.class);
                dto.getReferences().add(ref);
            }
            
            // Handle mapping
            if (to.getMapping() != null) {
                dto.setMappedEntity(
                    ctx.equivalent(to.getMapping().getTarget(), Table.class)
                );
            }
            
            return dto;
        };
    }
}
```

## Operation Transformation

```java
@TransformRule(name = "Operation2Procedure")
public TransformFunction<Operation, Procedure> operation2Procedure() {
    return (op, ctx) -> {
        Procedure proc = ctx.createTarget(Procedure.class);
        proc.setName(op.getName());
        proc.setReturnType(mapReturnType(op.getReturnType()));
        
        // Transform parameters
        for (Parameter param : op.getParameters()) {
            ProcedureParam procParam = ctx.equivalent(param, ProcedureParam.class);
            proc.getParameters().add(procParam);
        }
        
        // Transform faults/exceptions
        for (Fault fault : op.getFaults()) {
            ProcedureException exc = ctx.equivalent(fault, ProcedureException.class);
            proc.getExceptions().add(exc);
        }
        
        return proc;
    };
}
```

## Guarded Service Operations

```java
// Only transform bound operations
@TransformRule(name = "BoundOperation2BoundProcedure")
@Guard(method = "isBound")
public TransformFunction<Operation, Procedure> boundOperation2Procedure() {
    return (op, ctx) -> {
        Procedure proc = ctx.createTarget(Procedure.class);
        proc.setName(op.getName());
        proc.setBound(true);
        proc.setInputType(ctx.equivalent(op.getInput(), TransferDTO.class));
        return proc;
    };
}

// Only transform static operations
@TransformRule(name = "StaticOperation2StaticProcedure")
@Guard(method = "isStatic")
public TransformFunction<Operation, Procedure> staticOperation2Procedure() {
    return (op, ctx) -> {
        Procedure proc = ctx.createTarget(Procedure.class);
        proc.setName(op.getName());
        proc.setStatic(true);
        return proc;
    };
}

private boolean isBound(EObject element, TransformationContext ctx) {
    return ((Operation) element).getInput() != null;
}

private boolean isStatic(EObject element, TransformationContext ctx) {
    return ((Operation) element).isStatic();
}
```

---

**Previous**: [Namespace Transformations](namespace-transformations.md) | **Next**: [Lazy Rule Examples](lazy-rule-examples.md)
