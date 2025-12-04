# Branch Strategy for judo-zeta

**Date:** 2025-12-04  
**Related:** JNG-6349 (Epsilon to Java validation conversion)

## Overview

This document describes the branching strategy for the judo-zeta project, which separates infrastructure configuration from code implementation to enable clean integration.

## Branch Structure

### 1. `develop` Branch (Infrastructure Only)

**Current Branch** - Contains only CI/CD, build infrastructure, and IDE configurations.

**Contents:**
```
judo-zeta/
├── .github/              # ✅ GitHub Actions CI/CD workflows
│   └── workflows/
│       ├── build.yml
│       ├── bump-version.yml
│       └── [other workflows]
├── .mvn/                 # ✅ Maven wrapper configuration
│   ├── jvm.config
│   ├── extensions.xml
│   └── wrapper/
├── .claude/              # ✅ Claude Code commands (OpenSpec)
├── .agent/               # ✅ Agent workflows (OpenSpec)
├── .opencode/            # ✅ OpenCode commands (OpenSpec)
├── .vscode/              # ✅ VS Code settings
├── .zed/                 # ✅ Zed editor configuration
├── mvnw                  # ✅ Maven wrapper (Unix)
├── mvnw.cmd              # ✅ Maven wrapper (Windows)
├── pom.xml               # ✅ Parent POM (no modules)
├── CLAUDE.md             # ✅ OpenSpec instructions
├── .gitignore            # ✅ Git ignore rules
├── README.md             # ✅ Infrastructure documentation
└── BRANCH_STRATEGY.md    # ✅ This file
```

**Does NOT contain:**
- ❌ Source code modules (validation-core, osgi-itest, p2)
- ❌ Documentation (AGENTS.md, comprehensive README.md)
- ❌ Implementation code

**Purpose:**
- Maintain infrastructure configurations independently
- Enable clean CI/CD updates without code conflicts
- Provide base for future integration

**Commit:** `cfb5e7f` - "chore: Create develop branch with infrastructure only"

### 2. `feature/JNG-6349_Epsiolon2Java` Branch (Complete Implementation)

**Feature Branch** - Contains all source code, documentation, and implementation.

**Contents:**
```
judo-zeta/
├── [All infrastructure from develop] +
├── validation-core/      # ✅ Core validation framework
│   ├── pom.xml
│   └── src/main/java/hu/blackbelt/judo/meta/validation/
│       ├── ModelProvider.java
│       ├── annotation/   # 9 annotation classes
│       ├── core/         # 13 core classes
│       └── util/         # Utility classes
├── p2/                   # ✅ Eclipse P2 update site
│   ├── pom.xml
│   ├── category.xml
│   └── src/assembly/
├── osgi-itest/           # ✅ OSGi integration tests
│   ├── pom.xml
│   └── src/test/java/
├── AGENTS.md             # ✅ Comprehensive developer docs (~900 lines)
├── README.md             # ✅ Complete project README (~650 lines)
├── DOCUMENTATION_UPDATE_SUMMARY.md  # ✅ Documentation update notes
└── p2/CHANGELOG.md       # ✅ Changelog
```

**Purpose:**
- Complete validation framework implementation
- Comprehensive documentation
- All source code and tests

**Status:** Clean working tree (as of this document)

## Integration Workflow

### Current State

```
develop (infrastructure only)
  └── cfb5e7f: "chore: Create develop branch with infrastructure only"

feature/JNG-6349_Epsiolon2Java (complete implementation)
  └── [contains all code + docs + infrastructure]
```

### Future Integration Steps

When ready to create the final PR:

#### Step 1: Switch to Feature Branch
```bash
git checkout feature/JNG-6349_Epsiolon2Java
```

#### Step 2: Merge Develop Branch
```bash
git merge develop
```

**Expected Result:**
- Infrastructure files from `develop` will merge cleanly (they're identical or newer)
- Code and documentation from feature branch remain intact
- Potential conflicts only in files present in both branches (pom.xml, README.md, etc.)

#### Step 3: Resolve Conflicts (if any)

Likely conflicts:
- **pom.xml** - Module declarations
  - Keep: Modules from feature branch (validation-core, p2, osgi-itest)
  - Verify: Infrastructure settings from develop
  
- **README.md** - Content difference
  - Keep: Comprehensive README from feature branch
  - Discard: Minimal infrastructure README from develop

Resolution strategy:
```bash
# For pom.xml - keep feature branch modules
git checkout feature/JNG-6349_Epsiolon2Java -- pom.xml

# For README.md - keep feature branch documentation
git checkout feature/JNG-6349_Epsiolon2Java -- README.md

# Verify all changes
git status
git diff --staged
```

#### Step 4: Test Build
```bash
./mvnw clean install
```

Verify:
- All modules build successfully
- Tests pass
- OSGi integration tests succeed

#### Step 5: Create Pull Request

Target branch: `develop` (or `master`, depending on repository conventions)

PR Title:
```
feat: Implement Java-based validation framework for EMF metamodels [JNG-6349]
```

PR Description:
```markdown
## Summary
Implements a lightweight, annotation-based validation framework for EMF metamodels as an alternative to Epsilon Validation Language (EVL).

## Key Features
- ✅ 8 validation annotations (@Constraint, @Critique, @Guard, @Satisfies, etc.)
- ✅ Parallel validation with ForkJoinPool (3-4x speedup on large models)
- ✅ Dependency resolution via topological sorting
- ✅ Result caching for expensive validations
- ✅ Extension methods for custom helpers
- ✅ OSGi bundle packaging with P2 distribution
- ✅ Comprehensive documentation (AGENTS.md, README.md)

## Modules
- `validation-core` - Core validation framework (24 classes)
- `p2` - Eclipse P2 update site
- `osgi-itest` - Pax Exam integration tests

## Testing
- ✅ OSGi integration tests with Apache Karaf
- ✅ CI/CD pipeline configured (GitHub Actions)
- ✅ Code coverage with JaCoCo

## Documentation
- ✅ Complete AGENTS.md (900+ lines) with usage examples
- ✅ Professional README.md (650+ lines) with installation guides
- ✅ Performance benchmarks and troubleshooting guide

## Related
- JIRA: JNG-6349
- Replaces Epsilon-based validation with pure Java implementation
```

## Branch Maintenance

### Develop Branch Updates

When infrastructure needs updating (CI/CD, build config, IDE settings):

1. Switch to `develop`
2. Make infrastructure changes
3. Commit and push
4. Merge into feature branches as needed

Example:
```bash
git checkout develop
# Make changes to .github/workflows/build.yml
git commit -am "ci: Update GitHub Actions to use Node 20"
git push origin develop

# Update feature branch
git checkout feature/JNG-6349_Epsiolon2Java
git merge develop
git push origin feature/JNG-6349_Epsiolon2Java
```

### Feature Branch Updates

When code or documentation changes:

1. Work on feature branch
2. Commit code/docs
3. Keep infrastructure in sync with develop via periodic merges

## Rationale

### Why Separate Branches?

1. **Clean Separation of Concerns**
   - Infrastructure changes don't require code review
   - Code changes focus on implementation
   - Easier to track what changed where

2. **Flexible Integration**
   - Can update CI/CD independently
   - Can complete code development independently
   - Merge when both are ready

3. **Better PR Review**
   - Final PR shows complete feature
   - Infrastructure already reviewed/tested separately
   - Reviewers focus on code quality, not build config

4. **Conflict Avoidance**
   - Infrastructure changes rarely conflict with code
   - Easier to resolve when they do (clear ownership)

## Branch Protection Rules

When setting up repository:

### Develop Branch
- ✅ Require pull request reviews (1 reviewer)
- ✅ Require status checks (CI/CD must pass)
- ✅ Require branches to be up to date
- ✅ Do not allow force push

### Master Branch
- ✅ Require pull request reviews (2 reviewers)
- ✅ Require status checks (all tests must pass)
- ✅ Require signed commits
- ✅ Do not allow force push
- ✅ Require linear history

## Version Management

### Branch Versions

| Branch | Version Pattern | Example |
|--------|----------------|---------|
| `develop` | `1.0.0-SNAPSHOT` | 1.0.0-SNAPSHOT |
| `feature/*` | `1.0.0-SNAPSHOT` | 1.0.0-SNAPSHOT |
| `master` | `X.Y.Z` | 1.0.0 |

### CI/CD Versioning

GitHub Actions automatically adds branch identifier:
- Develop: `1.0.0.20251204_181032_abc123def_develop`
- Feature: `1.0.0.20251204_181032_abc123def_feature_JNG-6349_Epsiolon2Java`
- Master: `1.0.0`

## Future Branches

### After Initial PR Merge

Once feature branch is merged to develop/master:

```
master (or main)
  └── v1.0.0: "feat: Java-based validation framework"
       ├── develop
       │   └── [infrastructure updates]
       └── feature/next-feature
           └── [next feature development]
```

### Recommended Workflow

1. Create feature branches from `develop`
2. Periodically merge `develop` into feature branches
3. When feature complete, create PR to `develop`
4. After review/approval, merge to `develop`
5. Release from `develop` to `master` with version tag

## Troubleshooting

### Merge Conflicts

**Problem:** Conflicts during `git merge develop`

**Solution:**
```bash
# Identify conflicting files
git status

# For infrastructure files - take develop version
git checkout develop -- .github/workflows/build.yml

# For code files - keep feature branch
git checkout feature/JNG-6349_Epsiolon2Java -- validation-core/

# For mixed files (pom.xml, README.md) - manual resolution required
git mergetool
```

### Lost Changes

**Problem:** Accidentally committed to wrong branch

**Solution:**
```bash
# If committed to develop instead of feature branch
git checkout develop
git log  # Find commit hash

git checkout feature/JNG-6349_Epsiolon2Java
git cherry-pick <commit-hash>

git checkout develop
git reset --hard HEAD~1  # Remove from develop
```

### Branch Out of Sync

**Problem:** Develop branch diverged significantly

**Solution:**
```bash
git checkout feature/JNG-6349_Epsiolon2Java
git fetch origin
git merge origin/develop --no-ff  # Merge with merge commit
# Or
git rebase origin/develop  # Rebase (cleaner history)
```

## Summary

- ✅ **develop** = Infrastructure only (CI/CD, build, IDE configs)
- ✅ **feature/JNG-6349_Epsiolon2Java** = Complete implementation (code + docs + infrastructure)
- ✅ Merge develop → feature when ready for final PR
- ✅ PR contains complete feature with tested infrastructure
- ✅ Clean separation enables independent updates

---

**Last Updated:** 2025-12-04  
**Maintainer:** Róbert Csákány (@robertcsakany)  
**Related Issue:** JNG-6349
