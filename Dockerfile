# Single deployable artifact: the React app is built, folded into the jar's static resources, and
# served by Spring from the same origin as the API.
#
# Why one service rather than two: free tiers cap how many services you can run, a same-origin
# deployment needs no production CORS configuration to drift out of sync, and a portfolio link is
# one URL. This works with no extra code because the frontend uses tab state rather than a
# client-side router, so there is no SPA fallback route to add.

# ---------- stage 1: build the frontend ----------
FROM node:22-alpine AS frontend
WORKDIR /build

# Dependencies first, so a source-only change does not re-run npm ci.
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci

# The frontend's prebuild step syncs the benchmark numbers out of docs/, so that file has to be
# present before `npm run build` runs.
COPY docs/benchmark-data.json ./docs/benchmark-data.json
COPY frontend/ ./frontend/
RUN cd frontend && npm run build

# ---------- stage 2: build the jar ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build

# Warm the dependency cache in its own layer. Tolerate failure: go-offline is a build-speed
# optimisation, and some plugin metadata resists it. The real build below fetches anything missed.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
COPY --from=frontend /build/frontend/dist ./frontend/dist

# -Pwith-frontend copies frontend/dist into the jar's static resources.
# Tests are skipped here on purpose: they run in CI and locally, and re-running 203 tests
# (including a 100,000-word index build) on every deploy wastes free-tier build minutes.
RUN mvn -B -q -Pwith-frontend -DskipTests package

# ---------- stage 3: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Wildcard matches the repackaged jar only; spring-boot-maven-plugin leaves the unpackaged one as
# *.jar.original, which this does not match.
COPY --from=backend /build/target/fuzzy-search-engine-*.jar /app/app.jar

# MaxRAMPercentage rather than a fixed -Xmx, so the JVM respects whatever the container is
# actually given. SerialGC because the parallel collectors' bookkeeping is a poor trade in a
# small single-core container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
