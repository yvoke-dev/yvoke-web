# Custom Postgres image with pgvector (0.8.2) + ParadeDB pg_search (0.24.0)
FROM pgvector/pgvector:pg16@sha256:7d400e340efb42f4d8c9c12c6427adb253f726881a9985d2a471bf0eed824dff

ARG TARGETARCH
ARG PG_SEARCH_VERSION=0.24.0
ARG DEB=postgresql-16-pg-search_${PG_SEARCH_VERSION}-1PARADEDB-bookworm_${TARGETARCH}.deb

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    curl -fL "https://github.com/paradedb/paradedb/releases/download/v${PG_SEARCH_VERSION}/${DEB}" \
        -o /tmp/pg_search.deb; \
    apt-get install -y --no-install-recommends /tmp/pg_search.deb; \
    rm -f /tmp/pg_search.deb; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*
