# Judo Zeta - Develop Branch (Infrastructure)

This is the **develop** branch containing only CI/CD, IDE settings, and build infrastructure files.

## Branch Structure

This branch contains:
- ✅ `.github/` - GitHub Actions CI/CD workflows
- ✅ `.mvn/` - Maven wrapper configuration
- ✅ `.claude/`, `.agent/`, `.opencode/`, `.zed/`, `.vscode/` - IDE and AI assistant configurations
- ✅ `mvnw`, `mvnw.cmd` - Maven wrapper scripts
- ✅ `pom.xml` - Parent POM (infrastructure only)
- ✅ `CLAUDE.md` - OpenSpec instructions
- ✅ `.gitignore` - Git ignore rules

This branch does NOT contain:
- ❌ Source code modules (validation-core, osgi-itest, p2)
- ❌ Documentation (AGENTS.md, comprehensive README)
- ❌ Implementation details

## Purpose

This branch serves as the base for:
1. **CI/CD Configuration** - GitHub Actions workflows, build scripts
2. **Development Environment** - IDE settings, AI assistant configs
3. **Build Infrastructure** - Maven wrapper, parent POM

## Integration Strategy

The main feature branch (`feature/JNG-6349_Epsiolon2Java`) contains:
- All source code (validation-core, osgi-itest, p2)
- Complete documentation (AGENTS.md, README.md)
- Implementation details

**Workflow:**
1. This `develop` branch maintains infrastructure
2. Feature branch maintains code and documentation
3. Later: Merge `develop` into feature branch
4. Create PR with complete implementation

## Files in This Branch

```
judo-zeta/
├── .github/          # CI/CD workflows
├── .mvn/             # Maven wrapper config
├── .claude/          # Claude Code commands
├── .agent/           # Agent workflows
├── .opencode/        # OpenCode commands
├── .vscode/          # VS Code settings
├── .zed/             # Zed editor config
├── mvnw              # Maven wrapper (Unix)
├── mvnw.cmd          # Maven wrapper (Windows)
├── pom.xml           # Parent POM
├── CLAUDE.md         # OpenSpec instructions
├── .gitignore        # Git ignore rules
└── README.md         # This file
```

## Next Steps

When ready to integrate:

```bash
# Switch to feature branch
git checkout feature/JNG-6349_Epsiolon2Java

# Merge develop branch
git merge develop

# Resolve any conflicts
# Create PR with complete implementation
```

## Build

This branch alone cannot be built (no modules). It provides the infrastructure for the complete project.

---

**Repository:** BlackBeltTechnology/judo-zeta  
**License:** Eclipse Public License 2.0 (EPL-2.0)
