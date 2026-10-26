# /commit — Semantic Commit + Push

Executes the full commit flow.

## Step 1: Verify current branch

Run `git branch --show-current` and verify the current branch is **NOT** `main` or `development`.

If it is, stop immediately:
> "⛔ You are on branch `[name]`. Committing directly to `main` or `development` is not allowed. Create a new branch with `/new-feature` or switch branches manually."

## Step 2: Check repository state

Run in parallel:
- `git status`
- `git diff --staged`

## Step 3: Evaluate staged changes

**If nothing is staged (`git diff --staged` is empty):**

Run `git status` to list modified/new files and ask the user:
> "Nothing is staged. Found the following modified files:
> [file list]
>
> Do you want me to run `git add -A` to stage all of them? (yes/no) Or tell me which specific files to add."

Wait for confirmation before continuing.

**If there are staged files:** continue directly to Step 4.

## Step 4: Analyze the changes

Read the full diff with `git diff --staged` and determine:

1. **Commit type:**
   - `feat` — new functionality
   - `fix` — bug fix
   - `refactor` — refactoring with no behavior change
   - `test` — tests only
   - `chore` — maintenance, config, dependencies
   - `docs` — documentation only
   - `style` — formatting, whitespace (no logic change)
   - `perf` — performance improvement
   - `ci` — CI/CD changes

2. **Optional scope** (which module/area): `protocol`, `server`, `worker`, `queue`, `claude`, `publisher`, `deps`, etc.

3. **Description** in English, concise, imperative mood (e.g. `add fps auto-decision via mpdecimate`, `fix attempt fencing on TASK_RELEASE`)

## Step 5: Propose commit message

```
[type]([scope]): description
```

Show the user a summary:

```
Files in this commit:
  [output of git diff --staged --stat]

Proposed message:
  [type](scope): short description
```

**Wait for explicit user confirmation before continuing:**
- `yes` / `ok` → proceed to Step 6
- `edit [new message]` → use the new message and confirm again
- `cancel` → stop without committing

## Step 6: Commit and push

Run the commit only after receiving explicit confirmation.

Then run `git push origin [current-branch]`.

If the push fails because the branch has no upstream:
`git push --set-upstream origin [current-branch]`

## Step 7: Confirm result

Show the push result and the hash of the created commit.
