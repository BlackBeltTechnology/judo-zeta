# Code Analyzer Skill

## Description

Analyze code structure, patterns, and implementation details using a dedicated subagent to avoid context pollution in the main conversation. The subagent performs deep code exploration and returns comprehensive findings that the main thread can use for decision-making.

## Invocation

- `/analyze <topic>` - Analyze a specific topic, component, or pattern in the codebase
- `/code-analyze <query>` - Alias for /analyze

## When to Use This Skill

Use this skill when you need to:
- Understand how a feature/component is implemented
- Find all usages of a class, method, or pattern
- Investigate a bug or unexpected behavior
- Map dependencies between components
- Understand the call flow for a specific operation
- Search for code patterns across multiple files
- Prepare context for implementing a new feature

## How It Works

This skill spawns an **Explore subagent** that:
1. Searches the codebase using Glob, Grep, and Read tools
2. Analyzes code structure and relationships
3. Returns a structured report with findings
4. Does NOT pollute the main conversation context

## Instructions for Main Thread

When this skill is invoked, you MUST use the Task tool to spawn an Explore subagent. Follow this pattern:

### Step 1: Parse the Query

Extract the analysis topic from the user's request. Examples:
- `/analyze equivalent() method` → topic: "equivalent() method implementation"
- `/analyze transformation execution flow` → topic: "transformation execution flow"
- `/analyze how greedy rules work` → topic: "greedy rule implementation"

### Step 2: Spawn the Subagent

Use the Task tool with these parameters:

```
Tool: Task
subagent_type: Explore
description: "Analyze: <short topic>"
prompt: <detailed prompt - see template below>
```

### Step 3: Report Findings

After the subagent returns, summarize the key findings for the user in a clear, actionable format.

---

## Subagent Prompt Template

Use this template for the subagent prompt, customizing `{TOPIC}` and `{SPECIFIC_QUESTIONS}`:

```
# Code Analysis Task: {TOPIC}

## Project Context

This is the judo-zeta project - a validation and transformation framework for EMF metamodels.

Key modules:
- `validation-core/` - Validation framework with annotations like @Constraint, @Critique
- `transformation-core/` - Model-to-model transformation with @TransformRule, @Lazy, @Greedy
- `openspec/` - Change management specs

Key packages:
- `hu.blackbelt.judo.zeta.transformation.core` - Transformation engine
- `hu.blackbelt.judo.zeta.validation.core` - Validation engine

## Analysis Request

{TOPIC}

## Specific Questions to Answer

{SPECIFIC_QUESTIONS}

## Required Output Format

Provide a comprehensive report with:

### 1. Summary
2-3 sentence overview of what you found.

### 2. Key Files
List the most relevant files with their purposes:
| File | Purpose |
|------|---------|
| `path/to/file.java` | Description |

### 3. Implementation Details
Explain how the code works, including:
- Entry points
- Core logic flow
- Key methods and their responsibilities
- Important data structures

### 4. Code Patterns Found
Document any patterns, conventions, or idioms used.

### 5. Dependencies
List what this code depends on and what depends on it.

### 6. Relevant Code Snippets
Include short, relevant code snippets (max 20 lines each) that illustrate key points.
Use format: `file_path:line_number`

### 7. Recommendations (if applicable)
Any suggestions for the main thread based on findings.

## Instructions

1. Use Glob to find relevant files
2. Use Grep to search for specific patterns
3. Use Read to examine file contents
4. Be thorough - search for related tests, interfaces, and implementations
5. Focus on answering the specific questions
6. Return findings even if partial - partial info is better than none
```

---

## Example Invocations

### Example 1: Analyze a Method

**User:** `/analyze equivalent() method`

**Subagent Prompt:**
```
# Code Analysis Task: equivalent() method implementation

## Project Context
[standard context]

## Analysis Request
Analyze the equivalent() method in the transformation framework - how it works, when it's called, and what it returns.

## Specific Questions to Answer
1. Where is equivalent() defined?
2. What are the different overloads/signatures?
3. How does it resolve source-to-target mappings?
4. How does it handle lazy vs eager rules?
5. What caching mechanism is used?
6. How does it work in parallel execution?

## Required Output Format
[standard format]
```

### Example 2: Analyze a Pattern

**User:** `/analyze how @Greedy annotation affects rule execution`

**Subagent Prompt:**
```
# Code Analysis Task: @Greedy annotation effects on rule execution

## Project Context
[standard context]

## Analysis Request
Understand how the @Greedy annotation modifies transformation rule behavior.

## Specific Questions to Answer
1. Where is @Greedy defined?
2. How does TransformationExecutor check for @Greedy?
3. What's the difference between greedy and non-greedy type matching?
4. How does @Greedy interact with @Lazy?
5. What tests cover @Greedy behavior?

## Required Output Format
[standard format]
```

### Example 3: Investigate a Bug

**User:** `/analyze why null is returned from equivalent() for cross-source-type calls`

**Subagent Prompt:**
```
# Code Analysis Task: null returns from equivalent() for cross-source-type calls

## Project Context
[standard context]

## Analysis Request
Investigate the code path where equivalent() might return null when called across different source types.

## Specific Questions to Answer
1. What are the conditions that cause equivalent() to return null?
2. How does appliesTo() work for different source types?
3. Where is executeLazyRuleImmediately() called?
4. What happens when a rule from TransformationClassA calls equivalent() for a rule in TransformationClassB?
5. Are there any tests for cross-source-type lookups?

## Required Output Format
[standard format]
```

---

## Thoroughness Levels

When spawning the subagent, specify thoroughness based on need:

- **quick**: Basic search, 1-2 key files, surface-level analysis
- **medium**: Moderate exploration, 3-5 files, understand main flow
- **thorough**: Comprehensive analysis, all related files, deep understanding

Include in the prompt: `Thoroughness level: {level}`

---

## Error Handling

If the subagent cannot find relevant code:
1. Report what was searched and not found
2. Suggest alternative search terms
3. Ask user for clarification if needed

---

## Notes

- The subagent has full access to Glob, Grep, Read, and other exploration tools
- Results are returned to the main thread as a single message
- The main thread should summarize findings concisely for the user
- Use this skill proactively when code understanding is needed before making changes
