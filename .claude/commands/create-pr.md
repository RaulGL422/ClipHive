# /create-pr — Create Pull Request

Creates a PR from the current branch toward `development` (default) or the specified base.

## Usage
- `/create-pr` — creates PR toward `development`
- `/create-pr main` — creates PR toward `main`

## Step 1: Verify preconditions

Run `git branch --show-current`.

If the branch is `main` or `development`, stop:
> "You cannot create a PR from `main` or `development`."

The base branch defaults to `development`. If an argument was passed (e.g. `main`), use it as the base.

Verify there are commits ahead of the base branch:
```bash
git log [base-branch]...HEAD --oneline
```

If there are no commits, stop:
> "No changes to create a PR from. Make commits first with `/commit`."

## Step 2: Review the changes

Run in parallel:
- `git log [base-branch]...HEAD --oneline`
- `git diff [base-branch]...HEAD --stat`
- `git diff [base-branch]...HEAD`

## Step 3: Verify gh authentication

```bash
gh auth status
```

If not authenticated, stop:
> "GitHub CLI (`gh`) is not authenticated. Run `gh auth login` in your terminal and try again."

## Step 4: Generate PR title and description

Based on the commits and diff, generate:

**Title** (max 70 characters): clear and descriptive, in English.

**Description** with these sections:
- **What does this PR do?** — 2–3 bullet points
- **Main changes** — list of files and what changed
- **How to test** — steps to verify it works
- **Checklist** — `[ ]` tests added/updated, `[ ]` `docs/DESIGN.md` updated if a design decision changed, `[ ]` multiplatform rules respected (Path, ProcessBuilder argument lists — see CLAUDE.md), `[ ]` no secrets introduced

## Step 5: Confirm and create the PR

Show the user the generated title and description and ask:
> "Create the PR with this title and description? (yes/edit/cancel)"

Wait for confirmation before continuing.

If confirmed, push the branch and create the PR:
```bash
git push --set-upstream origin [current-branch]
gh pr create --title "[title]" --body "[description]" --base [base-branch]
```

## Step 6: Launch automatic review

After creating the PR, automatically run `/review-pr [number]` for an immediate review.

Show the PR URL at the end.
