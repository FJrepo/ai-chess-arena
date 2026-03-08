# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'for attempt in 1 2 3; do \
      mvn dependency:go-offline -B -Dmaven.wagon.http.retryHandler.count=5 && exit 0; \
      echo "go-offline failed on attempt ${attempt}, retrying..." >&2; \
      sleep $((attempt * 5)); \
    done; \
    exit 1'
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'for attempt in 1 2 3; do \
      mvn package -DskipTests -B -Dmaven.wagon.http.retryHandler.count=5 && exit 0; \
      echo "package failed on attempt ${attempt}, retrying..." >&2; \
      sleep $((attempt * 5)); \
    done; \
    exit 1'

FROM eclipse-temurin:21-jre@sha256:34a58218d838035428163eb35abb629944c5906d1bedcfef8bc8864cce11dfe5
WORKDIR /app

LABEL org.opencontainers.image.licenses="MIT, GPL-3.0-only" \
      dev.aichessarena.stockfish.version="sf_18" \
      dev.aichessarena.stockfish.source="https://github.com/official-stockfish/Stockfish/tree/sf_18" \
      dev.aichessarena.stockfish.asset="stockfish-ubuntu-x86-64-avx2.tar"

# Install dependencies for downloading and running Stockfish
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    ca-certificates \
    tar \
    && rm -rf /var/lib/apt/lists/*

# Download and install pinned Stockfish 18 binary (x86-64-avx2) with checksum verification.
ARG STOCKFISH_VERSION=sf_18
ARG STOCKFISH_TAR=stockfish-ubuntu-x86-64-avx2.tar
ARG STOCKFISH_SHA256=536c0c2c0cf06450df0bfb5e876ef0d3119950703a8f143627f990c7b5417964
RUN set -eux; \
    wget -O stockfish.tar "https://github.com/official-stockfish/Stockfish/releases/download/${STOCKFISH_VERSION}/${STOCKFISH_TAR}"; \
    echo "${STOCKFISH_SHA256}  stockfish.tar" | sha256sum -c -; \
    mkdir stockfish_temp; \
    tar -C stockfish_temp -xf stockfish.tar; \
    mv "stockfish_temp/stockfish/${STOCKFISH_TAR%.tar}" /usr/local/bin/stockfish; \
    chmod +x /usr/local/bin/stockfish; \
    rm -rf stockfish.tar stockfish_temp

# Create an unprivileged runtime user.
RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --create-home --home-dir /home/app --shell /usr/sbin/nologin app

RUN mkdir -p /usr/share/licenses/stockfish
COPY licenses/stockfish/ /usr/share/licenses/stockfish/
COPY --from=build --chown=app:app /app/target/quarkus-app/ ./quarkus-app/
USER app
EXPOSE 8080
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
