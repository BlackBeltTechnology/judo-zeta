# Judo Zeta Framework

## Project Overview

**Repo:** BlackBeltTechnology/judo-zeta | **License:** EPL-2.0 | **Java:** 21 | **Build:** Maven 3.9.4+ (wrapper: `./mvnw`)

Lightweight, standalone **validation + transformation framework for EMF metamodels**. Annotation-based alternative to Epsilon EVL/ETL with parallel execution, dependency resolution, and caching.

## Coding Principles

1. Read the codebase before making changes. Never speculate about code you haven't opened.
2. Check in before major changes — verify the plan first.
3. Give high-level explanations of what you changed at each step.
4. Keep changes simple and minimal. Every change should impact as little code as possible.
5. Maintain architecture documentation.
6. Use clear variable names, Java naming conventions, Javadoc on public APIs.
7. Write modular, reusable code — smaller is better.

## Project Structure

```
judo-zeta/
├── validation-core/          # Core validation framework (OSGi bundle)
├── transformation-core/      # Core transformation framework
├── p2/                       # Eclipse P2 update site packaging
├── osgi-itest/               # Pax Exam integration tests
├── agent-docs/               # Detailed docs for coding agents (READ THESE)
├── .github/                  # CI/CD workflows
└── .mvn/                     # Maven wrapper config
```

## Core Modules

| Module | Artifact ID | Purpose |
|--------|-------------|---------|
| **validation-core** | `hu.blackbelt.judo.zeta.validation-core` | Annotation-based EMF validation engine |
| **transformation-core** | `hu.blackbelt.judo.zeta.transformation-core` | Annotation-based EMF model-to-model transformation |
| **p2** | `hu.blackbelt.judo.zeta.p2` | Eclipse P2 update site |
| **osgi-itest** | `hu.blackbelt.judo.zeta.osgi.itest` | Pax Exam OSGi/Karaf integration tests |

## Detailed Documentation (agent-docs/)

**Start with `agent-docs/INDEX.md`** — it has a decision tree for which file to read.

| File | When to Read |
|------|--------------|
| `agent-docs/QUICK-REF.md` | **Always start here** — covers 90% of transformation tasks |
| `agent-docs/ANNOTATIONS.md` | Need annotation details for transformation rules |
| `agent-docs/CONTEXT-API.md` | Working with `TransformationContext` methods |
| `agent-docs/EXECUTION.md` | Execution flow, parallel mode, staging, deferred writes |
| `agent-docs/PATTERNS.md` | Complex patterns: inheritance, multi-source, lazy, thread-safety |
| `agent-docs/MIGRATION.md` | Migrating ETL → Zeta transformations |
| `agent-docs/MIGRATION-EVL.md` | Migrating EVL → Zeta validations |
| `agent-docs/TROUBLESHOOTING.md` | Debugging transformation/validation issues |

## Validation Framework Summary

**Annotations:** `@ValidationContext`, `@Constraint` (error), `@Critique` (warning), `@Guard`, `@Satisfies` (dependencies), `@Cached`, `@ExtensionMethod`, `@PreValidation`/`@PostValidation`

**Engine:** `ValidationExecutor` auto-parallelizes when elements ≥ 5000 (configurable), chunk size 100, ForkJoinPool with work-stealing.

**Key classes:** `ValidationRegistry`, `ValidationExecutor`, `ValidationContext`, `ValidationResult`, `ValidationRule` (functional interface)

→ See `agent-docs/MIGRATION-EVL.md` for validation patterns and examples.

## Transformation Framework Summary

**Annotations:** `@TransformationContext`, `@TransformRule`, `@Transform`/`@To`, `@Lazy`, `@Abstract`, `@Primary`, `@Greedy`, `@ActivityBased`, `@Extends`, `@Guard`, `@Detached`, `@PreExecution`/`@PostExecution`

**Execution strategies:** `ELEMENT_BY_ELEMENT` (default) vs `RULE_BY_RULE` (ETL-compatible)

**Key classes:** `TransformationExecutor`, `TransformationContext`, `TransformationRegistry`, `ElementResolutionCache`, `TransformationTrace`, `ExecutionStrategy`

**Thread-safety:** Two-phase staging (parallel create → sequential commit). Safe: `ctx.createTarget()`, `ctx.equivalent()`, reading source. Unsafe: modifying source, modifying other rules' targets, shared mutable state.

→ See `agent-docs/QUICK-REF.md` for transformation patterns and examples.

## Technology Stack

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

## Build Commands

```bash
./mvnw clean install              # Full build with tests
./mvnw clean install -DskipTests  # Skip tests
./mvnw clean verify               # Build with code coverage
./mvnw test                       # Unit tests only
./mvnw verify -Dit.test=*ITest    # Integration tests only
```

**Maven profiles:** `release-judong` (internal Nexus), `release-central` (Maven Central), `sign-artifacts` (GPG), `release-dummy` (test to /tmp)

**Deploy:** `./mvnw clean deploy -Prelease-judong`

## Key Configuration Files

| File | Purpose |
|------|---------|
| `/pom.xml` | Parent POM — modules, dependency management, profiles |
| `/validation-core/pom.xml` | Validation bundle config |
| `/transformation-core/pom.xml` | Transformation bundle config |
| `/.mvn/jvm.config` | JVM args (memory, add-opens) |
| `/.mvn/extensions.xml` | Maven extensions |
| `/.github/workflows/build.yml` | CI/CD pipeline |

## Testing

**Unit tests:** Standard JUnit 5 in each module's `src/test/`.

**OSGi integration tests:** `osgi-itest/` uses Pax Exam + Karaf. Verifies bundle activation, service registration, EMF integration.

**Coverage:** JaCoCo reports at `target/site/jacoco/index.html` after `./mvnw clean verify`.

## Git & Versioning

- **Branches:** `develop` (main), `master` (stable), `feature/*`, `hotfix/*`, `release/*`, `increment/*`
- **Commits:** Conventional commits — `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- **Versioning:** `1.0.0-SNAPSHOT` (dev), `1.0.0.{timestamp}_{hash}_{branch}` (CI), `1.0.0` (release)


