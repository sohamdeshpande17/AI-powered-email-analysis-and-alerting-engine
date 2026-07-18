# Circular Analyser — Database (v3)

One PostgreSQL database, one script. `schema.sql` contains the
complete installation in three sections — teardown (drops any previous v3 or
v2 installation), the v3 schema, and the seed data — so running it always
leaves a fresh, fully-seeded database. See `../architecture.md` §5 for the
data model.

## Files

| File | Purpose |
|---|---|
| `schema.sql` | The single install script: teardown + schema + seed. Idempotent. |
| `pgadmin-servers.json` | Pre-registered server entry for the pgAdmin container. |

## Usage

With the compose stack (`../docker-compose.yml`) the script is mounted into
`/docker-entrypoint-initdb.d/` and runs automatically the first time the data
volume is initialised:

```bash
docker compose up -d postgres
```

To re-apply onto a running container (wipes and recreates everything):

```bash
docker cp database/schema.sql circular_analyser_db:/tmp/
docker exec circular_analyser_db psql -U postgres -d circular_analyser -v ON_ERROR_STOP=1 -f /tmp/schema.sql
```

Or against any host-reachable Postgres:

```bash
psql -h localhost -U postgres -d circular_analyser -f database/schema.sql
```

## Schema highlights (v3)

- Single `public` schema — per-source schemas (nse/bse/raw/ai) are gone.
- `raw_circular` (+ documents, NAS paths) and `circular` (AI-summarized,
  PK = `circular_no` with system-UUID fallback).
- `circular_workflow` — one row per lifecycle event (RECEIVED / IN_ACTION /
  CLOSED / COMMENT); only the current status is denormalized on `circular`.
- `circular_history` + trigger = temporal versioning of every circular change.
- Tenancy: every table carries `tenant_id` (default 1 = Meridian
  Capital) plus `created_by/on`, `updated_by/on` audit columns.
- Seeded: roles (lowercase ids), bootstrap admin user, NSE/BSE/EMAIL sources
  with `config_json` poll intervals, teams, reminder intervals, and three
  sample circulars with documents and workflow events.

## Execution Commands

```bash
# Run the script - dev
psql -h localhost -U postgres -d circular_analyser -f database/schema.sql
```

```bash
# Run the script - uat
psql -h <uat-host> -U <uat-user> -d circular_analyser -f database/schema.sql
```

```bash
# Run the script - prod
psql -h <prod-host> -U <prod-user> -d circular_analyser -f database/schema.sql
```

## Notes 

### windows

```bash
PGCLIENTENCODING=UTF8
CREATE EXTENSION pgcrypto — gen_random_uuid() --- no need
```
