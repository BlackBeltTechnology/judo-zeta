# Proposal: Add Transformation Documentation

**Status: Applied**

## Why

The Judo Zeta project now has two major frameworks:
1. **Validation Framework** (validation-core) - Comprehensive documentation exists in `docs/`
2. **Transformation Framework** (transformation-core) - Only has README.md with basic examples

The validation documentation is extensive with user guides, best practices, EVL comparison, examples, architecture docs, and API reference. The transformation framework needs the same level of documentation for end users to effectively migrate from Epsilon ETL and use the new Java-based transformation framework.

Additionally, the current `docs/` structure mixes general concepts with validation-specific content, making it unclear that a transformation framework also exists.

## What Changes

### 1. Restructure Documentation Directory

Move validation-specific docs to `docs/validation/` subdirectory:
- `docs/validation/getting-started.md`
- `docs/validation/user-guide/` (all existing user guide files)
- `docs/validation/best-practices/` (all existing best practice files)
- `docs/validation/evl-comparison/` (all existing EVL comparison files)
- `docs/validation/examples/` (all existing example files)
- `docs/validation/architecture/` (all existing architecture files)
- `docs/validation/reference/` (all existing reference files)

### 2. Create Transformation Documentation

Create parallel structure in `docs/transformation/`:
- `docs/transformation/getting-started.md` - Quick start guide
- `docs/transformation/user-guide/` - Core concepts, rules, context, etc.
- `docs/transformation/best-practices/` - Production patterns
- `docs/transformation/etl-comparison/` - ETL syntax mapping, migration guide, feature parity
- `docs/transformation/examples/` - Real-world examples from judo-tatami
- `docs/transformation/architecture/` - System architecture, execution flow, parallel execution
- `docs/transformation/reference/` - Annotations, TransformationContext API, troubleshooting

### 3. Update Main Documentation Index

Update `docs/index.md` to serve as hub for both frameworks:
- Overview of Judo Zeta (validation + transformation)
- Links to validation documentation
- Links to transformation documentation
- Quick reference for choosing which framework to use

### 4. Create Shared Documentation

Create `docs/shared/` for content common to both frameworks:
- `docs/shared/installation.md` - Maven, P2, OSGi installation
- `docs/shared/extension-methods.md` - Common extension method patterns
- `docs/shared/caching.md` - Caching strategies (shared annotations)

## Scope

- Documentation only - no code changes
- Focus on end-user documentation (not API javadocs)
- Mirror the quality and detail level of existing validation documentation
- Include real-world examples from judo-tatami-esm2psm and similar modules

## Out of Scope

- API javadoc generation (separate concern)
- Video tutorials or interactive documentation
- Internationalization of documentation
