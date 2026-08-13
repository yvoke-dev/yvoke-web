# Pinned to an exact version, not the floating :10. This image is the SOLE schema authority — the
# app does not run Flyway (its Flyway dependencies are test-scoped) — so a floating tag lets the
# tool that owns the database change under a rebuild with nothing recording it.
FROM flyway/flyway:10.22.0
COPY docker/db/migration/ /flyway/sql/
