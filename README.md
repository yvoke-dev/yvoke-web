# Yvoke

This is Yvoke, built using Java, Spring Boot, and PostgreSQL.

## Prerequisites

- **Java**: Java 25
- **Docker**: Desktop or CLI with Compose support
- **Node**: Node 22+ — required, not optional. The browser-side JavaScript has its own test tier
  bound to the Maven `test` phase, so without `node` on `PATH` every `./mvnw test`, `package`,
  `verify` and `install` fails. There is nothing to install: the tier has **zero dependencies**
  (no `npm install`, no `node_modules`, no lockfile). To build without it, pass `-DskipJsTests=true`.

## Testing

| command | covers |
| --- | --- |
| `./mvnw test` | Java unit tests **+ the JS tier** (fast, no Docker) |
| `./mvnw verify -Pit-tests` | unit + integration (Testcontainers); emits the JaCoCo aggregate and enforces the coverage ratchet |
| `./mvnw verify -Pe2e-tests` | Playwright browser e2e; first run downloads Chromium |
| `npm test` | the JS tier alone |
| `npm run test:coverage` | the JS tier with its per-file coverage table and floors |

The JS tier (`src/test/js/`) covers the pure string→string logic extracted out of `thread.js` and
the admin templates into `static/js/chat/` and `static/js/admin/`. It also carries a **syntax gate**
that parses every app-owned `.js` file *and* every inline `<script>` in every Thymeleaf template —
Spotless is Java-only, so without it a JavaScript syntax error can otherwise reach production.

## Local Development Setup

### 1. Build the Application

Build the production package jar:
```bash
mvn clean package
```

### 2. Start Infrastructure

Start the app and database services using Docker Compose:
```bash
docker compose up -d --build
```

Note: The database container maps host port `5433` to container port `5432` to avoid conflicts with other local database instances.

### 3. Verify Health

Once started, verify the services are responsive:
- **Application Health**: `curl http://localhost:8080/actuator/health`
- **Application Controller Health**: `curl http://localhost:8080/health`
- **Postgres Database Extensions**:
  ```bash
  docker compose exec postgres psql -U postgres -d postgres -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_search;"
  ```

## Project Module Layout

For detailed information on the vertical domain packaging and module layout, please refer to the single source of truth: `.antigravity/steering/structure.md`.

## Spec-Driven Workflow

This repository uses the Antigravity Spec-Driven Development (ASDD) flow.

- `.antigravity/steering/`: durable repository guidance for product, structure, technology, and AI behavior.

All active project-planning files (implementation plan, tasks, and walkthroughs) are managed natively in Antigravity's local memory directory (`<appDataDir>/brain/<conversation-id>/`). No specification, design, or checklist files are written to the workspace root or the `.antigravity/` folder to keep the codebase clean.
