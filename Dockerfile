# syntax=docker/dockerfile:1
#
# ClipHive server image (docs/DEPLOY.md, "Docker" section).
# Multi-arch: builds natively on a Raspberry Pi (arm64) or on x86_64.
# Releases are published as ghcr.io/raulgl422/cliphive-server by
# .github/workflows/server.yml; this builds the same image from the sources:
#
#   docker compose up -d --build
#
# Only the server is packaged here — the worker (transcription + render)
# runs on a machine with a GPU, never on the server host.

# ── Build stage ──────────────────────────────────────────────
# Base images pinned to an exact Temurin release (multi-arch tags) so a
# rebuild gets the same JDK; bump both FROM lines together.
# The jar doesn't depend on the CPU, so a cross-build (amd64 runner making the
# arm64 image) compiles it natively and only emulates the runtime stage.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
WORKDIR /src

# Set by the release workflow from the server-vX.Y.Z tag; empty keeps the pom's.
ARG CLIPHIVE_VERSION=

# The Maven Wrapper downloads Maven itself and needs curl (or wget) plus
# unzip; the slim JDK image ships neither.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY protocol protocol
COPY server server
# The root pom lists the worker module, so Maven needs its pom to load the
# reactor — but with -pl server -am it never compiles it.
COPY worker/pom.xml worker/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && if [ -n "$CLIPHIVE_VERSION" ]; then \
        ./mvnw -B -q org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
            -DnewVersion="$CLIPHIVE_VERSION" -DgenerateBackupPoms=false -DprocessAllModules; \
    fi \
    && ./mvnw -B -pl server -am package -DskipTests \
    && cp server/target/server-*.jar /app.jar

# ── Runtime stage ────────────────────────────────────────────
FROM eclipse-temurin:25.0.4.1_1-jre-noble

LABEL org.opencontainers.image.title="ClipHive server" \
      org.opencontainers.image.source="https://github.com/RaulGL422/ClipHive" \
      org.opencontainers.image.licenses="Apache-2.0"

ARG TARGETARCH

# ffmpeg/ffprobe: audio extraction, chunking and clipping on the server.
# unzip: needed by the Deno installer.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Deno — yt-dlp needs a JS runtime to get past YouTube's bot checks.
RUN curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh -s -- -y

RUN useradd -m -u 1001 -s /bin/bash cliphive \
    && mkdir -p /opt/tools /data \
    && chown cliphive:cliphive /opt/tools /data

# yt-dlp standalone binary, owned by the app user so `yt-dlp -U` can
# replace it in place without root.
RUN case "$TARGETARCH" in \
        arm64) YTDLP=yt-dlp_linux_aarch64 ;; \
        *)     YTDLP=yt-dlp_linux ;; \
    esac \
    && curl -fsSL -o /opt/tools/yt-dlp \
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/${YTDLP}" \
    && chmod +x /opt/tools/yt-dlp \
    && chown cliphive:cliphive /opt/tools/yt-dlp

USER cliphive
ENV HOME=/home/cliphive \
    PATH=/opt/tools:/home/cliphive/.local/bin:$PATH \
    CLIPHIVE_DATA_DIR=/data

# Claude Code (native installer, no Node.js needed) — used by CliLlmClient.
# Auth comes from CLAUDE_CODE_OAUTH_TOKEN in .env.
RUN curl -fsSL https://claude.ai/install.sh | bash \
    && claude --version

WORKDIR /home/cliphive
COPY --from=build --chown=cliphive:cliphive /app.jar /home/cliphive/server.jar

VOLUME ["/data", "/home/cliphive/.claude"]

# /login is public and served by the admin connector: a 200 means the app is up
# and answering (docs/DESIGN.md §16.6).
HEALTHCHECK --interval=1m --timeout=10s --start-period=3m --retries=3 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${CLIPHIVE_ADMIN_PORT:-8080}/login" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /home/cliphive/server.jar"]
