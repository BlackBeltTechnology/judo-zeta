# Changelog

All notable changes to the Judo Zeta Validation Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Annotation-based validation framework for EMF metamodels
- `@ValidationContext` annotation for defining validation rule containers
- `@Constraint` annotation for error-level validation rules
- `@Critique` annotation for warning-level validation rules
- `@Guard` annotation for conditional rule execution
- `@Satisfies` annotation for rule dependency declaration
- `@Cached` annotation for expensive validation result caching
- `@ExtensionMethod` annotation for helper method registration
- `@PreValidation` and `@PostValidation` lifecycle hooks
- Parallel validation execution for large models (5000+ elements)
- Automatic dependency resolution via topological sorting
- ForkJoinPool-based work-stealing parallelization
- Configurable parallel threshold and chunk size
- Cache key system with element-based, string-based, and object-based keys
- ValidationRegistry for rule discovery and registration
- ValidationExecutor with fluent builder API
- ValidationContext with extension method support
- ValidationResult with severity levels (ERROR, WARNING)
- Epsilon-style collection utilities for validation rules
- OSGi bundle packaging with proper manifest
- Eclipse P2 update site distribution
- Pax Exam integration tests with Apache Karaf
- GitHub Actions CI/CD pipeline
- Maven Central and Nexus deployment support
- Comprehensive JavaDoc documentation
- AGENTS.md developer documentation
- README.adoc with usage examples

### Changed
- N/A (initial release)

### Deprecated
- N/A

### Removed
- N/A

### Fixed
- N/A

### Security
- N/A

## Version History

### [1.0.0-SNAPSHOT] - In Development
- Initial development version
- Core validation framework implementation
- P2 repository setup
- CI/CD pipeline configuration
