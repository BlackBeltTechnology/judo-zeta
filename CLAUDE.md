# Important: Project Documentation

**Always read `AGENTS.md` first** - it contains comprehensive documentation about:
- Project structure and all modules
- Zeta metamodel architecture
- Technology stack (EMF, Ecore, Tycho, Epsilon, Handlebars)
- Build commands and Maven profiles
- Code generation flow
- Development environment requirements

This is essential context for understanding and working with this codebase.

## LSP Usage (Preferred for Java)

**IMPORTANT: For Java class operations ALWAYS use LSP instead of Grep/Glob.**

LSP (Language Server Protocol) provides type-aware code intelligence. It is the preferred method for navigating and understanding Java code.

### Available LSP Operations

| Operation | Use Case | Example |
|-----------|----------|---------|
| `documentSymbol` | List all symbols (classes, methods, fields) in a file | Find all methods in a class |
| `workspaceSymbol` | Search for symbols across the entire workspace | Find a class by name |
| `goToDefinition` | Jump to where a symbol is defined | Find where a method/class is implemented |
| `findReferences` | Find all usages of a symbol | Find all callers of a method |
| `goToImplementation` | Find implementations of interface/abstract method | Find concrete implementations |
| `hover` | Get type info and documentation for a symbol | Check method signature and Javadoc |
| `prepareCallHierarchy` | Get call hierarchy item at position | Prepare for incoming/outgoing calls |
| `incomingCalls` | Find all methods that call this method | Trace who calls this function |
| `outgoingCalls` | Find all methods called by this method | Trace what this function calls |

### When to Use LSP vs Other Tools

| Task | Use LSP | Don't Use |
|------|---------|-----------|
| Find method definition | `goToDefinition` | Grep for method name |
| Find all usages of a method | `findReferences` | Grep for method name |
| List class members | `documentSymbol` | Read entire file |
| Find implementations | `goToImplementation` | Grep for class name |
| Understand call flow | `incomingCalls`/`outgoingCalls` | Manual file reading |
| Search for a class | `workspaceSymbol` | Glob for filename |

### LSP Usage Examples

```
# Find all symbols in a Java file
LSP operation=documentSymbol filePath=src/main/java/MyClass.java line=1 character=1

# Find where a method is defined (cursor on method call)
LSP operation=goToDefinition filePath=src/main/java/MyClass.java line=25 character=15

# Find all references to a method
LSP operation=findReferences filePath=src/main/java/MyClass.java line=25 character=15

# Find who calls this method
LSP operation=incomingCalls filePath=src/main/java/MyClass.java line=25 character=15
```

### Fallback to Grep/Glob

Use Grep/Glob only when:
- LSP server is not available or not indexed
- Searching for string literals, comments, or non-code content
- Searching across non-Java files (XML, properties, etc.)

---
