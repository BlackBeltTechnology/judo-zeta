# Tasks: Optimize Cache Lookup Performance

**Updated:** 2026-01-15 (Based on Validated Benchmark Results)

## Phase 0: Validate Assumptions ✅ COMPLETE

- [x] 0.1 Create `CacheLookupBenchmarkTest.java`
  - Validated: Two-level map is 18.6% FASTER than single-level (assumption INVALID)
  - Validated: String interning is 44% faster (marginal benefit)
  - Validated: Array-based lookup is 7.85x faster (but only saves ~3ms)
  - Validated: computeIfAbsent has 108% overhead vs get()
- [x] 0.2 Identify real bottleneck
  - **CRITICAL FINDING**: Pure map lookup is only 3ms for 40K ops
  - Reported "cache ops" is 2,383ms (793x discrepancy!)
  - Map lookup is NOT the bottleneck

## Phase 1: Investigation (Before Coding) ✅ COMPLETE

- [x] 1.1 Add fine-grained timing to `getOrCreate()`
  - Added timing for: cache lookup, rejection check, addMapping, markRejected
  - Results: Cache lookup 0.122μs, rejection check 0.046μs, **addMapping 1.995μs** (16x slower!)
  - Cache unaccounted reduced to 8.1% (vs 793x discrepancy before)
- [ ] 1.2 Profile with async-profiler (without JaCoCo)
  - Pending - requires external model files
- [x] 1.3 Analyze metrics instrumentation overhead
  - **CRITICAL FINDING**: Metrics add **125.9% overhead** due to System.nanoTime() calls
  - Pure nanoTime() overhead: 36,642 ns/call
  - Significant contributor to reported "cache ops" time
- [x] 1.4 Document findings before proceeding
  - Updated proposal.md with Phase 1 results
  - Decision: Proceed with Phase 2 quick wins (computeIfAbsent optimization)

## Phase 2: Quick Wins (Low Risk) ✅ COMPLETE

- [x] 2.1 Replace computeIfAbsent with get-then-put pattern
  - Implemented in `addMapping()` and `markRejected()` for sequential mode
  - **Result: 50.5% improvement** (1.995 μs → 0.988 μs per addMapping call)
- [x] 2.2 Make metrics recording conditional
  - Already implemented with `TransformationMetrics.isEnabled()` check
  - Production path has zero nanoTime() overhead
- [x] 2.3 Inline rejection check with cache lookup
  - **Skipped** - rejection check is already very fast (0.046 μs)
  - Sentinel approach adds complexity for minimal gain
- [x] 2.4 Run benchmark and verify improvement
  - **All 602 tests pass**
  - addMapping improved from 19ms to 9ms for 10K operations

## Phase 3: Guard Optimization (Medium Risk) ✅ COMPLETE

- [x] 3.1 Analyze guard evaluation overhead
  - Added guard timing to greedy rule execution path
  - **Result**: Guard evaluation is fast (~0.7 μs per evaluation)
  - For 10,000 guards: only 7 ms total (4.8% of greedy execution)
- [x] 3.2 Cache guard results per (source, rule) pair
  - **Already implemented** in ElementResolutionCache (`rejectedKeysSequential`)
  - Also cached at rule level in TransformRuleDescriptor
  - Subsequent calls skip guard evaluation entirely
- [x] 3.3 Optimize common guard patterns
  - **Not needed** - guard evaluation is already fast
  - Simple type checks are ~0.7 μs each
- [x] 3.4 Run benchmark and verify improvement
  - **603 tests pass**
  - Guard timing now visible in performance reports

## Phase 4: Array-Based Cache (DEPRIORITIZED)

**Note**: Benchmark shows array-based lookup saves only ~3ms. Not worth the complexity unless Phase 1 reveals different data.

- [ ] 4.1 (Optional) Implement if Phase 1 shows map lookup is significant
- [ ] 4.2 (Optional) Add rule ordinal assignment to TransformRuleDescriptor
- [ ] 4.3 (Optional) Replace two-level map with source-indexed arrays

## Phase 5: Validation ✅ COMPLETE

- [x] 5.1 Run full transformation-core test suite
  - **603 tests pass** (15 skipped)
- [ ] 5.2 Run PSM2ASM transformation comparison (Optional)
  - Requires external model files
  - Verify output is identical to baseline
  - Verify XMI ID consistency
- [x] 5.3 Run `openspec validate optimize-cache-lookup-performance --strict`
  - **PASSED**: Change 'optimize-cache-lookup-performance' is valid
- [x] 5.4 Document final performance improvement
  - Updated proposal.md with all phase results
  - Key improvement: **50.5% faster addMapping**

## Key Learnings from Benchmarks

1. **Map lookup is NOT the bottleneck** (3ms vs 2,383ms reported)
2. **Two-level map is actually faster** than composite key
3. **computeIfAbsent has significant overhead** (108%)
4. **793x discrepancy** suggests overhead is elsewhere (guards, metrics, etc.)

## Dependencies

- Phase 1 MUST complete before Phases 2-4
- Phase 2 can start after Phase 1 analysis
- Phase 3 depends on Phase 1 findings
- Phase 4 is optional (low expected benefit)

## Parallelization

- Tasks 1.1-1.3 can be done in parallel (investigation)
- Tasks 2.1-2.3 are independent (can parallelize)
- Phase 3 depends on Phase 1 completion
