# judo-zeta — module agent doctrine

## Module purpose

`judo-zeta` is the estate's **annotated-Java engine for EMF model validation and
model-to-model transformation** — the Java-native replacement for the Epsilon
scripting layer (EVL for constraints, ETL for transformations) that the JUDO
toolchain was originally built on. It knows nothing about JUDO's metamodels: it
is a standalone framework over plain EMF, and every metamodel plugs *itself* in.

**How a metamodel plugs in.** The seam is `hu.blackbelt.judo.zeta.common.ModelProvider`,
a small interface a metamodel implements to expose traversal — `getAllContents(ResourceSet, Class<T>)`
plus optional `getName` / type-name hooks used for message interpolation.
`models/judo-meta-asm` supplies `AsmModelProvider`, which delegates
`getAllContents` straight to `AsmUtils.all(type)`; `judo-meta-psm`, `judo-meta-ui`,
`judo-meta-rdbms`, `judo-meta-liquibase`, `judo-meta-script` and `judo-meta-keycloak`
each supply the same shape. That one interface is what lets one engine drive
every metamodel without the engine depending on any of them.

**What the validation framework contributes.** Rules stop being `.evl` script text
and become annotated Java on ordinary classes: `@ValidationContext` marks the
holder, `@Constraint` declares an error-severity rule, `@Critique` a
warning-severity one, `@Guard` short-circuits a rule, `@Satisfies` declares a
dependency on another rule so ordering is derived rather than hand-written,
`@Cached` memoises an expensive computation, `@ExtensionMethod` adds a helper
callable from rule bodies, and `@PreValidation` / `@PostValidation` hook the run.
Per metamodel a `ValidationRegistry` is built and the rule-bearing classes are
`register(...)`ed into it — that registry, plus the metamodel's `ModelProvider`,
is handed to a `ValidationExecutor` which walks the elements and returns
`ValidationResult`s carrying a `Severity`. `models/judo-meta-asm`'s
`AsmValidator.validateAsm(...)` is the canonical wiring to copy. The payoff over
Epsilon is measurable: the executor switches to a work-stealing `ForkJoinPool`
once the element count reaches `PARALLEL_THRESHOLD` (5000), chunking at 100, and
`@Cached` + `@Satisfies` let it skip recomputation and derive ordering instead of
re-evaluating scripts.

**What the transformation framework contributes.** The same idea for
model-to-model work: `@TransformRule` classes with `@Transform` / `@To` mappings,
`@Lazy`, `@Abstract`, `@Primary`, `@Greedy`, `@ActivityBased`, `@Extends`,
`@Guard`, `@Detached`, `@PreExecution` / `@PostExecution`, executed by
`TransformationExecutor` against a `TransformationRegistry`. It offers two
execution strategies — `ELEMENT_BY_ELEMENT` (default) and `RULE_BY_RULE` (the
ETL-compatible order, so a migrated ETL script keeps behaving) — resolves
already-transformed targets through `ElementResolutionCache`, records source→target
links in `TransformationTrace`, and defers EMF containment writes through the
`deferred/` operation queue so parallel rule bodies stay safe.

**Who consumes it.** The `judo-tatami*` transformation repositories run their
Zeta route through these executors (`judo-tatami-core` carries the
`ETL` / `ZETA` / `BOTH` mode switch; `judo-tatami-jsl`'s `Jsl2PsmZetaTransformation`
and `Jsl2UiZetaTransformation` are the Zeta-side entry points), and the
`models/judo-meta-*` repositories run their Java validation rules through
`ValidationExecutor`. This module is therefore load-bearing for both layers and
changes here ripple estate-wide.

**Repo:** BlackBeltTechnology/judo-zeta | **License:** EPL-2.0 | **Java:** 21 |
**Build:** Maven 3.9.4+ (wrapper: `./mvnw`)

## Reactor map

The root `pom.xml` declares its `<modules>` inside the `modules` profile, active
whenever `skipModules` is not `true`; `-DskipModules=true` builds the parent
alone. Declared order is build order — `zeta-common` and `zeta-annotations` come
first because both engines depend on them.

| Module | Artifact / packaging | What it contributes |
|---|---|---|
| `zeta-common` | `hu.blackbelt.judo.zeta.common`, `bundle` (OSGi) | The metamodel-neutral seam both engines share: `ModelProvider` (the interface each metamodel implements to expose traversal and naming), `ExtensionMethodRegistry` + `ExtensionMethodDescriptor` (helper methods callable from rule bodies), and `CacheKey` / `CacheKeyBuilder` backing `@Cached`. Deliberately dependency-light so nothing forces a metamodel dependency onto the engines. |
| `zeta-annotations` | `hu.blackbelt.judo.zeta.annotations`, `bundle` (OSGi) | The declarative vocabulary, and nothing else — 22 annotation types covering validation (`@ValidationContext`, `@Constraint`, `@Critique`, `@Satisfies`, `@Cached`), transformation (`@TransformationContext`, `@TransformRule`, `@Transform`/`@Transforms`, `@To`/`@Tos`, `@Lazy`, `@Abstract`, `@Primary`, `@Greedy`, `@ActivityBased`, `@Extends`, `@Detached`), the shared `@Guard` and `@ExtensionMethod`, and the `@PreExecution`/`@PostExecution` lifecycle hooks. Split out so a rule-authoring module compiles against the vocabulary without pulling in an engine. |
| `validation-core` | `hu.blackbelt.judo.zeta.validation-core`, `bundle` (OSGi) | The validation engine: `ValidationRegistry` discovers and orders annotated rules (walking superclasses and interfaces), `ValidationExecutor` runs them — sequentially, or on a work-stealing `ForkJoinPool` once the element count reaches 5000 — and `ValidationContext`, `ValidationResult`, `Severity`, `ValidationRule`, `ValidatorDescriptor`, `ValidationRuleBuilder` and `Guard` form the surface a metamodel binds to. `EolStyleCollections` keeps EVL-shaped collection idioms working so migrated rules read the same. |
| `transformation-core` | `hu.blackbelt.judo.zeta.transformation-core`, `bundle` (OSGi) | The transformation engine: `TransformationExecutor` + `TransformationRegistry` + `TransformationContext` drive `TransformRuleDescriptor` / `TransformDefinition` / `ToDefinition` rule metadata, with `RuleInheritanceGraph` resolving `@Extends`, `DiscriminatorResolver` and `EquivalentDiscriminatedStrategy` picking among candidate rules, `ActivationTracker` and `OriginalTracker` tracking what already fired, `ElementResolutionCache` answering "what did this source become", `TransformationTrace` recording it, and `TransformationMetrics` / `TransformationResult` / `TransformationException` reporting the run. The `deferred/` package (`OperationQueue`, `EMFOperation`, `DeferredEObject`, `DeferredEList`, `ContainmentOp`, `ContainmentDeferringProxy`) queues EMF containment writes so parallel creation stays thread-safe. |
| `p2` | `hu.blackbelt.judo.zeta.p2`, `jar` | Eclipse P2 update site packaging. `category.xml` groups the bundles into installable features (`hu.blackbelt.judo.zeta.common.feature` and siblings, each with a `.source` twin) and `src/assembly/assembly.xml` drives `maven-assembly-plugin` to produce the site archive — the delivery shape for IDE/Eclipse-side consumers. No engine code. |
| `osgi-itest` | `hu.blackbelt.judo.zeta.osgi.itest`, `jar` | The check the unit tests structurally cannot make: `ZetaLoadITest` boots a real Apache Karaf container through Pax Exam (features supplied by `KarafFeatureProvider`, container config under `src/test/resources/etc/`) and asserts the bundles resolve, activate and register their services there. |

## Repository layout

```
judo-zeta/
├── zeta-common/              # ModelProvider seam, extension-method + cache-key support
├── zeta-annotations/         # The annotation vocabulary (both frameworks)
├── validation-core/          # Core validation framework (OSGi bundle)
├── transformation-core/      # Core transformation framework
├── p2/                       # Eclipse P2 update site packaging
├── osgi-itest/               # Pax Exam integration tests
├── agent-docs/               # Detailed docs for coding agents (READ THESE)
├── .github/                  # CI/CD workflows
└── .mvn/                     # Maven wrapper config
```

The root `pom.xml` is the parent POM carrying the module list, dependency
management and every profile below; `validation-core/pom.xml` and
`transformation-core/pom.xml` carry the OSGi bundle configuration for the two
engines; `.mvn/jvm.config` supplies JVM args (memory, `--add-opens`),
`.mvn/extensions.xml` the Maven extensions, and `.github/workflows/build.yml`
the CI pipeline.

## Detailed documentation (agent-docs/)

**Start with `agent-docs/INDEX.md`** — it has a decision tree for which file to read.

| File | When to Read |
|------|--------------|
| `agent-docs/QUICK-REF.md` | **Always start here** — covers 90% of transformation tasks |
| `agent-docs/ANNOTATIONS.md` | Need annotation details for transformation rules |
| `agent-docs/CONTEXT-API.md` | Working with `TransformationContext` methods |
| `agent-docs/EXECUTION.md` | Execution flow, parallel mode, staging, deferred writes |
| `agent-docs/GUARD_CACHE.md` | `@Guard` evaluation and `@Cached` memoisation semantics |
| `agent-docs/PATTERNS.md` | Complex patterns: inheritance, multi-source, lazy, thread-safety |
| `agent-docs/MIGRATION.md` | Migrating ETL → Zeta transformations |
| `agent-docs/MIGRATION-EVL.md` | Migrating EVL → Zeta validations |
| `agent-docs/TROUBLESHOOTING.md` | Debugging transformation/validation issues |

## Validation framework summary

**Annotations:** `@ValidationContext`, `@Constraint` (error), `@Critique` (warning), `@Guard`, `@Satisfies` (dependencies), `@Cached`, `@ExtensionMethod`, `@PreValidation`/`@PostValidation`

**Engine:** `ValidationExecutor` auto-parallelizes when elements ≥ 5000 (configurable), chunk size 100, ForkJoinPool with work-stealing.

**Key classes:** `ValidationRegistry`, `ValidationExecutor`, `ValidationContext`, `ValidationResult`, `ValidationRule` (functional interface)

→ See `agent-docs/MIGRATION-EVL.md` for validation patterns and examples.

## Transformation framework summary

**Annotations:** `@TransformationContext`, `@TransformRule`, `@Transform`/`@To`, `@Lazy`, `@Abstract`, `@Primary`, `@Greedy`, `@ActivityBased`, `@Extends`, `@Guard`, `@Detached`, `@PreExecution`/`@PostExecution`

**Execution strategies:** `ELEMENT_BY_ELEMENT` (default) vs `RULE_BY_RULE` (ETL-compatible)

**Key classes:** `TransformationExecutor`, `TransformationContext`, `TransformationRegistry`, `ElementResolutionCache`, `TransformationTrace`, `ExecutionStrategy`

**Thread-safety:** Two-phase staging (parallel create → sequential commit). Safe: `ctx.createTarget()`, `ctx.equivalent()`, reading source. Unsafe: modifying source, modifying other rules' targets, shared mutable state.

→ See `agent-docs/QUICK-REF.md` for transformation patterns and examples.

## Build commands

```bash
./mvnw clean install              # Full build with tests
./mvnw clean install -DskipTests  # Skip tests
./mvnw clean verify               # Build with code coverage
./mvnw test                       # Unit tests only
./mvnw verify -Dit.test=*ITest    # Integration tests only
```

**Maven profiles:** `modules` (supplies the six-module list; active unless `-DskipModules=true`), `release-judong` (internal Nexus), `release-central` (Maven Central), `sign-artifacts` (GPG), `release-dummy` (test to /tmp), `generate-github-asciidoc-diagrams` (documentation diagrams), `update-source-code-license` (license headers)

**Deploy:** `./mvnw clean deploy -Prelease-judong`

## Testing

**Unit tests:** Standard JUnit 5 in each module's `src/test/`.

**OSGi integration tests:** `osgi-itest/` uses Pax Exam + Karaf. Verifies bundle activation, service registration, EMF integration.

**Coverage:** JaCoCo reports at `target/site/jacoco/index.html` after `./mvnw clean verify`.

## Technology stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 21 | Target language |
| Maven | 3.9.4+ | Build system |
| Eclipse EMF | 2.38.0+ | Metamodel foundation |
| OSGi | 7.0.0 | Modularity framework |
| SLF4J + Logback | 2.0.16 / 1.5.12 | Logging |
| Lombok | 1.18.34 | Annotation processing |
| JUnit Jupiter | 5.11.3 | Testing |
| Apache Karaf | 4.4.7 | OSGi runtime (integration tests) |
| Pax Exam | 4.13.5 | OSGi integration testing |

## Git & versioning

- **Branches:** `develop` (main), `master` (stable), `feature/*`, `hotfix/*`, `release/*`, `increment/*`
- **Commits:** Conventional commits — `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- **Versioning:** `1.0.0-SNAPSHOT` (dev), `1.0.0.{timestamp}_{hash}_{branch}` (CI), `1.0.0` (release)

## Coding principles

1. Read the codebase before making changes. Never speculate about code you haven't opened.
2. Check in before major changes — verify the plan first.
3. Give high-level explanations of what you changed at each step.
4. Keep changes simple and minimal. Every change should impact as little code as possible.
5. Maintain architecture documentation.
6. Use clear variable names, Java naming conventions, Javadoc on public APIs.
7. Write modular, reusable code — smaller is better.

<!-- dox-doctrine -->
## Documentation Update Protocol (WRITE discipline)

Per-directory `AGENTS.md` files form a tree. Each directory `AGENTS.md` is the
per-file record for the files in that directory. This module-root `AGENTS.md`
holds doctrine + architecture pointers only — never a per-file index.

**Keep the root lean.** This file loads into every agent turn — every byte costs
tokens on every turn. A verbose root file buries the rules the model must follow
(signal dilution) and measurably degrades adherence; a lean file keeps doctrine
salient. Default assumption: your update does NOT belong in the root — route it
by the table below.

**Route every doc update by kind:**

| Kind of update | Goes in |
|---|---|
| New file in a directory, or its per-file detail / change history | Nearest directory `AGENTS.md`. Add a `` | `<basename>` | <purpose> | `` row, path-alphabetical. |
| Data flow, protocol, architecture rationale | `docs/architecture.md` or a `docs/<topic>.md` |
| End-user / developer setup | `README.md` |
| Cross-cutting rule every agent needs every turn (rare) | this module-root `AGENTS.md` |

**Read before editing (chain walk).** Before editing a file, read the nearest
`AGENTS.md` chain root→leaf so you know the file's recorded purpose, contracts,
and change history. Do not edit blind.

**Update after editing (closeout pass).** After changing a file, update its row
in the nearest directory `AGENTS.md`: find the file's row, update its purpose in
place; if absent, add it in path-alphabetical order. New directory → scaffold
its `AGENTS.md`. One row per file. The purpose carries a one-line summary, key
exported symbols, contracts/invariants, and `See change: <id>` history.

**Row style (caveman).** Short declarative fragments. Drop articles. Subject →
verb → object, present tense. One fact per row. Prefer concrete tokens (paths,
symbols, env vars) over prose. Keep identifiers verbatim.

**Size rule — split an over-large directory `AGENTS.md` file-based.** pi
auto-injects a directory `AGENTS.md` on every turn when cwd sits at/below it, so
an over-large directory `AGENTS.md` is not supported. Split it file-based: a row
exceeding the length threshold promotes to a per-file `<File>.AGENTS.md`
sidecar carrying that file's full detail (including every `See change:`). The
sidecar is pull-only — its name is not `AGENTS.md`, so pi never auto-injects it
— yet it stays search-indexed (`agents` doc_type). The directory `AGENTS.md`
keeps a one-line summary plus a `→ see `<File>.AGENTS.md`` pointer. Rows within
the threshold stay verbatim (lossless).

## Finding docs (READ discipline)

`kb_*` tools are faster and cheaper than raw search — they return a one-line
purpose + key exports per file, not raw bytes. **This fires on the ACTION, not
the intent** — before you `grep`/`rg` for a symbol, `cat`/read a file to learn
what it does, or chase an import, the kb call goes first. It fires **even
mid-task when you already know the file**; knowing the file does not exempt you.
When your reflex is the left column, run the right column instead:

| You're about to… | Do this FIRST instead |
|---|---|
| `grep -rn "SymbolName" src/` — find where a fn / type / const lives | `kb_search --doc-type agents "SymbolName"` — tree indexes key exports per file |
| `grep -rn "feature\|topic" src/` — how does X work / where's X handled | `kb_search "feature topic"` |
| `cat` / read a file just to learn its purpose before editing | `kb agents <path>` — one-line purpose + exports + change history |
| chase imports / callers across files | `kb_neighbors <path\|heading>` |
| read one doc section in full | `kb_get <path> <section>` |

**Fall-through (explicit):** if the kb call returns nothing relevant, `rg` /
source read is allowed — then add the missing directory `AGENTS.md` row per the
WRITE discipline. kb does NOT replace grep; it goes first.
