# TransformationTrace API

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Reference](annotations.md) > TransformationTrace

Reference for transformation trace and JSON export.

## Overview

`TransformationTrace` records all source-to-target mappings created during transformation.

## Methods

### getEntries()

Returns all trace entries.

```java
List<TraceEntry> entries = trace.getEntries();
```

### getEntryCount()

Returns the number of entries.

```java
int count = trace.getEntryCount();
```

### toJson()

Exports trace to JSON string.

```java
String json = trace.toJson();
```

### saveToJson()

Saves trace to JSON file.

```java
trace.saveToJson(new File("trace.json"));
```

## TraceEntry Structure

```java
public class TraceEntry {
    String ruleName;        // Rule that created this mapping
    ElementInfo source;     // Source element info
    ElementInfo target;     // Target element info
    boolean primary;        // Whether this is the primary result
}

public class ElementInfo {
    String type;            // EClass name
    String id;              // Element ID
    String name;            // Element name (if available)
}
```

## JSON Format

```json
{
  "traceEntries": [
    {
      "ruleName": "EntityType2Table",
      "source": {
        "type": "EntityType",
        "id": "entity-customer",
        "name": "Customer"
      },
      "target": {
        "type": "Table",
        "id": "table-customer",
        "name": "Customer"
      },
      "primary": true
    },
    {
      "ruleName": "Attribute2Column",
      "source": {
        "type": "Attribute",
        "id": "attr-name",
        "name": "name"
      },
      "target": {
        "type": "Column",
        "id": "col-name",
        "name": "name"
      },
      "primary": true
    }
  ],
  "entryCount": 2,
  "timestamp": 1699123456789
}
```

## Debugging with Trace

```java
TransformationTrace trace = result.getTrace();

// Find transformations for specific source
trace.getEntries().stream()
    .filter(e -> e.getSource().getId().equals("entity-customer"))
    .forEach(e -> System.out.println(e.getRuleName() + " -> " + e.getTarget().getId()));

// Find all transformations by rule
Map<String, Long> ruleStats = trace.getEntries().stream()
    .collect(Collectors.groupingBy(TraceEntry::getRuleName, Collectors.counting()));
ruleStats.forEach((rule, count) -> System.out.println(rule + ": " + count));

// Export for external analysis
trace.saveToJson(new File("transformation-trace-" + System.currentTimeMillis() + ".json"));
```

---

**Previous**: [TransformationResult](transformation-result.md) | **Next**: [Troubleshooting](troubleshooting.md)
