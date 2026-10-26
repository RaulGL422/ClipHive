# /new-feature — Create Feature Branch

Creates a properly named working branch from `development` (default) or from the specified base.

## Usage
- `/new-feature add render clip encoder for VAAPI` — free description
- `/new-feature fix websocket heartbeat timeout --from main` — free description from a different base branch

---

## Step 1: Check working state

Run `git status`. If there are uncommitted changes, ask:
> "You have uncommitted changes on the current branch. Do you want me to stash them before switching? (yes/no)"

---

## Step 2: Determine branch name

Infer the change type from the description and generate a branch name:

| Change type | Prefix |
|-------------|--------|
| New feature | `feature/` |
| Bug fix | `fix/` |
| Tests | `test/` |
| Config / maintenance | `chore/` |
| Documentation | `docs/` |

**Name:** kebab-case, ASCII only (no accented characters), max 55 characters.

Propose the name to the user before creating the branch:
```
Branch: [prefix]/[description]
Base:   [base-branch]

Confirm? (yes/no)
```

---

## Step 3: Create the branch

Default base is `development`. If `--from <branch>` was passed, use that.

```bash
git fetch origin [base-branch]
git checkout [base-branch]
git pull origin [base-branch]
git checkout -b [branch-name]
```

## Step 4: Push to remote

```bash
git push --set-upstream origin [branch-name]
```

## Step 5: Confirm

Show the created branch name and remind the user to use `/commit` for changes and `/create-pr` when done.
