## Why

Several features introduced in recent commits (8bff98e, 0a9fe21) lack test coverage. Specifically: `equivalentCached()` public API, sequential-mode recursive XMI ID assignment in `addToResource()`, guard rejection integration in `equivalent()`/`equivalentDiscriminated()`, and `currentGreedyPassRuleName` tracking. Without regression tests, these behaviors can silently break.

## What Changes

- Add tests for `equivalentCached()` cache-only lookup (no lazy triggering)
- Add tests for sequential `addToResource()` recursive XMI ID propagation to child elements
- Add integration tests for `isRejected()` checks inside `equivalent()` and `equivalentDiscriminated()`
- Add tests for `currentGreedyPassRuleName` same-rule lookup returning null during greedy pass

## Capabilities

### New Capabilities
- `test-coverage-gaps`: Tests for untested features from commits 8bff98e and 0a9fe21

### Modified Capabilities

## Impact

- `transformation-core/src/test/` — new test classes only
- No production code changes
