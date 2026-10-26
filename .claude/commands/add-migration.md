# /add-migration — Create a Flyway Migration

Creates a new Flyway migration file for the `server` module's PostgreSQL schema, following `docs/DESIGN.md` section 14 (Persistencia).

## Usage
- `/add-migration add index on clip.status`
- `/add-migration create scheduled_post table`

## Step 1: Determine the next version number

```bash
ls server/src/main/resources/db/migration/ 2>/dev/null | sort -V | tail -5
```

If the directory doesn't exist yet, this is `V1`. Otherwise, the next version is the highest existing `V{n}` + 1.

If no files exist, create `server/src/main/resources/db/migration/` first.

## Step 2: Name the file

```
V{n}__{description_in_snake_case}.sql
```

Example: `V5__add_index_clip_status.sql`

## Step 3: Write the migration

Base the schema on `docs/DESIGN.md` section 14.2 (table purposes) if this migration creates a new table from that list. Follow standard PostgreSQL conventions:
- Explicit column types, `NOT NULL` where the design requires it
- Foreign keys with `ON DELETE` behavior that matches the retention rules in `docs/DESIGN.md` section 15
- Indexes for columns used in `SELECT ... FOR UPDATE SKIP LOCKED` queries (task assignment, section 6.3) or frequent filters (status columns)

## Step 4: Critical rules

- **Never modify a migration file that may already have run** — always create a new one, even to fix a typo.
- Hibernate must stay on `ddl-auto=validate` — Flyway owns the schema, not Hibernate.
- Don't encode secrets or real data in the migration — schema only.

## Step 5: Confirm

Show the created file and remind the user that `./mvnw -pl server spring-boot:run` applies it automatically on startup.
