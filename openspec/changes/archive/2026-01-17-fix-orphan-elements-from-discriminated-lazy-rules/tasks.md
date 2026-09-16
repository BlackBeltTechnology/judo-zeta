# Implementation Tasks

## Phase 1: Option A - Prevent Duplicate Originals (COMPLETED)

### Analysis
- [x] Create test reproducing orphan element problem
- [x] Verify EMF containment behavior with proxy-resolving references
- [x] Identify root cause in `equivalentDiscriminated()` flow
- [x] Document current behavior in tests

### Design
- [x] Evaluate Option A: Defer addToResource for discriminated calls
- [x] Evaluate Option B: Remove original after cloning
- [x] Evaluate Option C: Use @Detached annotation convention
- [x] Select implementation approach → **Option A selected**

### Implementation
- [x] Add `isDiscriminatedExecution` thread-local flag to TransformationContext
- [x] Modify `addToResource()` to check flag and skip when in discriminated execution
- [x] Set flag in `equivalentDiscriminated()` before lazy rule execution
- [x] Clear flag after lazy rule execution (in finally block)
- [x] Handle nested discriminated calls correctly (via wasInDiscriminated save/restore)

### Testing
- [x] Update `OrphanElementDiscriminatorMismatchTest` to verify fix
- [x] Add test: same discriminator creates exactly 1 element
- [x] Add test: different discriminators create exactly 2 elements (clones only)
- [x] Add test: 10 sources with different discriminators = 10 orphans (not 20)
- [x] Run full test suite to verify no regressions (746 tests pass)

### Result
**Option A prevents duplicate originals** - Before: 3 elements (1 original + 2 clones), After: 2 elements (2 clones only)

---

## Phase 2: Discriminator Mismatch Problem (REMAINING ISSUE)

### Problem Statement
When different callers use **different discriminators** for the same logical element:
- Button: `equivalentDiscriminated(source, "button/xyz")` → Clone A (contained)
- Action: `equivalentDiscriminated(source, "action/xyz")` → Clone B (orphan)

Clone B is orphaned because Action only holds a reference (not containment).

### Real-World Evidence
```
esm2ui test: OrphanDiscriminatorMismatchTest.testOperationFormCreatesActionDefinitions
  - ETL: 1 root element, 7 ActionDefinitions (all contained)
  - ZETA: 3 root elements, 9 ActionDefinitions (2 orphans)
  - Orphan types: ParameterlessCallOperationActionDefinition
```

### Proposed Solutions

#### Approach 1: Discriminator Alignment (esm2ui side)
- [ ] Analyze helper methods that create ActionDefinitions
- [ ] Ensure Button and Action transformations use SAME discriminator
- **Pros:** No framework changes
- **Cons:** Complex refactoring, risk of breaking XMI IDs

#### Approach 2: Shared Instance Registry (ZETA framework)
- [ ] Add `equivalentShared(source, type, rule, discriminator, sharedKey)` API
- [ ] Share instances across discriminators when sharedKey matches
- **Pros:** Clean API, transparent to callers
- **Cons:** New framework feature needed

#### Approach 3: Containment-Aware Lookup (ZETA framework)
- [ ] Before creating new clone, check if contained instance exists for same source+rule
- [ ] Return existing contained instance instead of creating orphan
- **Pros:** Automatic sharing, no transformation code changes
- **Cons:** May break cases where separate instances are intentional

### Testing
- [x] Add test demonstrating discriminator mismatch creates orphans
- [x] Add test with shared helper method called from multiple contexts (`Esm2UiDiscriminatorMismatchTest.java`)
- [x] Add test verifying containment vs reference behavior (`Esm2UiDiscriminatorMismatchTest.java`)
- [x] Add test demonstrating aligned discriminators eliminate orphans

---

## Documentation Phase
- [ ] Update AGENTS.md with guidance on discriminated lazy rules
- [ ] Add examples showing correct discriminator usage patterns
- [ ] Document the discriminator mismatch problem and solutions
