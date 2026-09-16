# thread-safety Specification

## Purpose
TBD - created by archiving change unify-rule-execution-locking. Update Purpose after archive.
## Requirements
### Requirement: Rule execution MUST use unified locking across all API methods

All methods that trigger rule execution (`equivalent()`, `executeParentRule()`, etc.) SHALL use the same locking mechanism to prevent race conditions when the same (source, ruleName) pair is accessed concurrently through different API methods. **The implementation SHOULD minimize overhead on the fast path (cache hit) while maintaining thread safety.**

#### Scenario: Fast path avoids object allocation on cache hit

**Given** a transformation calling `executeParentRule()` for a previously executed rule
**When** the result is already cached in `resolutionCache`
**Then** the method returns immediately without creating `RuleCacheKey` object
**And** no lock acquisition occurs
**Because** cache lookup uses source and ruleName directly

#### Scenario: Unified locking uses consistent lock acquisition

**Given** Thread A calls `equivalent(source, TargetType.class)`
**And** Thread B calls `executeParentRule("RuleName", source)` for the same source
**When** both methods need to acquire a lock
**Then** both use `lock.lock()` (not `tryLock(timeout)`)
**And** both use the same `ruleLocks` ConcurrentHashMap
**Because** consistent lock semantics prevent subtle timing differences

#### Scenario: RuleCacheKey uses identity-based comparison

**Given** two `RuleCacheKey` instances created for the same source EObject
**When** comparing keys for lock lookup
**Then** identity comparison (`source == that.source`) is used instead of `equals()`
**And** `System.identityHashCode(source)` is used instead of `source.hashCode()`
**Because** EMF identity is based on object reference, not structural equality

