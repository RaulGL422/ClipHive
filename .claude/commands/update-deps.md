# /update-deps — Review and Update Dependencies

Reviews the aggregator and all three module `pom.xml` files for outdated dependencies and proposes safe updates.

## Step 1: Read the pom files

Read the root `pom.xml` and `protocol/pom.xml`, `server/pom.xml`, `worker/pom.xml`. Extract all dependencies and plugin versions.

## Step 2: Run the Maven versions plugin

From the repository root (this checks all modules in one pass):
```bash
./mvnw versions:display-dependency-updates -q 2>&1 | grep -E "\->" | head -60
./mvnw versions:display-plugin-updates -q 2>&1 | grep -E "\->" | head -40
```

If the command fails, analyze the versions manually against knowledge of the latest stable version of each dependency.

## Step 3: Classify the updates

For each dependency/plugin with an available update, classify it as:

**SAFE (patch / minor with no known breaking changes):**
- Patch version change (x.y.Z → x.y.Z+1)
- Minor in mature libraries with a good compatibility track record

**REQUIRES REVIEW (minor with possible changes):**
- Spring Boot (once adopted) — check the release notes for the minor bump
- Any library whose API `server`/`worker` code calls directly with non-trivial usage

**MAJOR / NOT RECOMMENDED now:**
- Java version changes (stay on 21 unless the user explicitly decides otherwise)
- Spring Boot / OpenJFX major versions — these are still open decisions per `docs/DESIGN.md` §20.2, don't pin a major version without flagging it

## Step 4: Present report

```
## Dependency Updates — [date]

### Safe Updates (I apply automatically if you confirm)
| Module | Dependency | Current version | New version | Type |
|--------|------------|-----------------|-------------|------|
| server | ... | ... | ... | patch |

### Require Review (I explain what changes before updating)
| Module | Dependency | Current version | New version | Risk |
|--------|------------|-----------------|-------------|------|

### Not Recommended Now
[dependencies with major bump or reasons not to update]

---
Apply the safe updates? (yes/no)
Explain what changes in a specific dependency? (name)
```

## Step 5: Apply confirmed updates

If the user confirms, edit the relevant `pom.xml`(s), updating only the approved versions.

Then verify it compiles:
```bash
./mvnw compile -q
```

If there is a compilation error after updating, revert the specific change and report which dependency caused the problem.

## Step 6: Reminder

When done, suggest running `/commit` with type `chore(deps): update dependencies`.
