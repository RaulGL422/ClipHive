# /project-status — Project Status Overview

Generates a summary of the current state of the project.

## Step 1: Git and GitHub state

Run in parallel:
```bash
git branch --show-current
git log --oneline -8
git status --short
git stash list
gh pr list --state open --json number,title,headRefName,createdAt
```

## Step 2: Plan de acción status

Read `docs/DESIGN.md` section 19 (Plan de acción) and section 20 (Decisiones). Report:
- Which phase (Fase 0–9) is currently active, based on the checkbox state in section 19
- Any unchecked items in the active phase
- Any items in section 20.2 (Pendientes) that block the active phase

## Step 3: Pending work in code

Search for pending TODOs and FIXMEs:
```bash
grep -rn "TODO\|FIXME\|HACK\|System\.out\.print" protocol/src server/src worker/src --include="*.java" 2>/dev/null
```

(If a module has no `src/` yet with real code, note it as "no code yet" rather than treating the grep failure as an error.)

## Step 4: Generate report

```
## Project Status — ClipHive — [current date]

### Git
- Current branch: [branch]
- Recent commits: [list]
- Uncommitted changes: [N files] / Clean
- Saved stashes: [N] / None
- Open PRs: [list with number and title] / None

### Plan (docs/DESIGN.md §19)
- Active phase: [Fase N — name]
- Unchecked items in this phase: [list] / None
- Blocking pending decisions (§20.2): [list] / None

### Technical Debt
- TODOs/FIXMEs found: [list with file:line] / None

### Suggested next action
[A single concrete action based on the current state and the active phase]
```
