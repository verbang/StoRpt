# syntax=docker/dockerfile:1.7
#
# StoRpt single image. ADR-0024 requires the frontend build output, FastAPI,
# JRE and Excel Worker JAR to ship together. HTTPS is terminated by the
# deployment platform or a reverse proxy in front of this image.
#
# The image is reproduced from committed lock files; rebuilds are deterministic
# and do not consult the network for unpinned versions.

# ---------- Stage A: frontend -------------------------------------------------
FROM node:24-alpine AS frontend-builder
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- Stage B: Excel Worker JAR ----------------------------------------
FROM maven:3.9-eclipse-temurin-21-noble AS worker-builder
WORKDIR /build
COPY excel-worker/pom.xml ./
# Pre-fetch dependencies for layer caching; pom.xml changes rarely.
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode -q dependency:go-offline
COPY excel-worker/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode -DskipTests -q package \
    && cp target/excel-worker-*.jar /build/excel-worker.jar

# ---------- Stage C: runtime --------------------------------------------------
FROM python:3.12-slim AS runtime

# JRE for the Worker, plus native libs akshare/pandas/cryptography need.
# openjdk-21-jre-headless registers `java` on PATH via Debian alternatives, so
# WorkerClient's default `java` lookup resolves without an explicit JAVA_HOME.
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        openjdk-21-jre-headless \
        libffi-dev libssl-dev ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*

# Python dependencies from the committed lock file only.
WORKDIR /app
COPY backend/requirements.lock ./requirements.lock
RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r requirements.lock

# Application code and build artefacts.
COPY backend/storpt_api ./storpt_api
COPY deploy/entrypoint.sh ./entrypoint.sh
COPY --from=worker-builder /build/excel-worker.jar ./excel-worker.jar
COPY --from=frontend-builder /build/dist ./static
RUN chmod +x ./entrypoint.sh

# Non-root runtime. The task root lives under /tmp so it is writeable without
# an extra volume; entrypoint ensures ownership at start.
RUN useradd --system --create-home --uid 1001 storpt \
    && mkdir -p /tmp/storpt-tasks \
    && chown -R storpt:storpt /app /tmp/storpt-tasks

USER storpt
ENV STORPT_STATIC_DIR=/app/static \
    STORPT_WORKER_JAR=/app/excel-worker.jar \
    STORPT_TASK_ROOT=/tmp/storpt-tasks \
    PYTHONUNBUFFERED=1

EXPOSE 8000

ENTRYPOINT ["/usr/bin/tini", "--", "/app/entrypoint.sh"]
CMD ["uvicorn", "storpt_api.main:app", "--host", "0.0.0.0", "--port", "8000"]
