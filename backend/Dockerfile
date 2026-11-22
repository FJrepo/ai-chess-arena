# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
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

FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699 AS stockfish-base

# Keep the selected asset, checksum and labels together for each target architecture.
FROM stockfish-base AS stockfish-amd64
ARG STOCKFISH_TAR=stockfish-linux-x86-64-universal.tar.gz
ARG STOCKFISH_SHA256=9defc0d4e55d49c65a6d042f3e571a39fcea499ade6dbe741b53b8c65e03611f

FROM stockfish-base AS stockfish-arm64
ARG STOCKFISH_TAR=stockfish-linux-arm64-universal.tar.gz
ARG STOCKFISH_SHA256=fe26cfd1d9db4c8af3d21e24d9ff34cacb31c1f940085a7583da11796f2bac01

FROM stockfish-${TARGETARCH} AS runtime
ARG STOCKFISH_VERSION=sf_19
WORKDIR /app

LABEL org.opencontainers.image.licenses="MIT, GPL-3.0-or-later" \
      dev.aichessarena.stockfish.version="${STOCKFISH_VERSION}" \
      dev.aichessarena.stockfish.source="https://github.com/official-stockfish/Stockfish/tree/${STOCKFISH_VERSION}" \
      dev.aichessarena.stockfish.asset="${STOCKFISH_TAR}" \
      dev.aichessarena.stockfish.sha256="${STOCKFISH_SHA256}"

# Install dependencies for downloading and running Stockfish
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    ca-certificates \
    tar \
    gzip \
    && rm -rf /var/lib/apt/lists/*

# Download and install the pinned Stockfish 19 universal binary with checksum verification.
RUN set -eux; \
    wget -O stockfish.tar.gz "https://github.com/official-stockfish/Stockfish/releases/download/${STOCKFISH_VERSION}/${STOCKFISH_TAR}"; \
    echo "${STOCKFISH_SHA256}  stockfish.tar.gz" | sha256sum -c -; \
    mkdir stockfish_temp; \
    tar -C stockfish_temp -xzf stockfish.tar.gz; \
    binary="$(find stockfish_temp -type f -name "${STOCKFISH_TAR%.tar.gz}")"; \
    test -n "$binary" && test -f "$binary"; \
    mv "$binary" /usr/local/bin/stockfish; \
    chmod +x /usr/local/bin/stockfish; \
    rm -rf stockfish.tar.gz stockfish_temp

# Create an unprivileged runtime user.
RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --create-home --home-dir /home/app --shell /usr/sbin/nologin app

RUN install -d -m 0700 -o app -g app /app/.local/secrets
RUN mkdir -p /usr/share/licenses/stockfish
COPY licenses/stockfish/ /usr/share/licenses/stockfish/
COPY --from=build --chown=app:app /app/target/quarkus-app/ ./quarkus-app/
USER app
EXPOSE 8080
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
