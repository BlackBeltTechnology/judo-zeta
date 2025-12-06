# TransformationResult API

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > TransformationResult

Reference for TransformationResult returned by executor.

## Overview

`TransformationResult` is returned by `TransformationExecutor.transform()` and contains the transformation outcome.

## Methods

### getTargetResourceSet()

Returns the target ResourceSet containing all created elements.

```java
ResourceSet targetResourceSet = result.getTargetResourceSet();
```

### getTrace()

Returns the transformation trace for debugging.

```java
TransformationTrace trace = result.getTrace();
```

### getContext()

Returns the TransformationContext used.

```java
TransformationContext context = result.getContext();
```

## Usage Example

```java
TransformationExecutor executor = new TransformationExecutor(registry, context, true);
TransformationResult result = executor.transform(sourceElements);

// Access target model
ResourceSet targetModel = result.getTargetResourceSet();
for (Resource resource : targetModel.getResources()) {
    resource.save(Collections.emptyMap());
}

// Access trace
TransformationTrace trace = result.getTrace();
System.out.println("Transformed " + trace.getEntryCount() + " elements");

// Export trace for debugging
trace.saveToJson(new File("trace.json"));
```

## Accessing Target Elements

```java
TransformationResult result = executor.transform(sourceElements);

// Via ResourceSet
ResourceSet target = result.getTargetResourceSet();
List<Table> tables = new ArrayList<>();
for (Resource r : target.getResources()) {
    r.getAllContents().forEachRemaining(obj -> {
        if (obj instanceof Table) {
            tables.add((Table) obj);
        }
    });
}

// Via context
Collection<Table> tables = result.getContext().getAllTarget(Table.class);
```

---

**Previous**: [TransformationContext](transformation-context.md) | **Next**: [TransformationTrace](transformation-trace.md)
