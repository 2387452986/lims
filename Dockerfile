# syntax=docker/dockerfile:1.8
FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.16-eclipse-temurin-21 AS backend
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -DskipTests -Dskip.frontend.copy=true dependency:go-offline
COPY src/ src/
COPY --from=frontend /build/frontend/dist frontend/dist
RUN ./mvnw -B -DskipTests package

FROM debian:trixie-slim
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
       ca-certificates chromium fonts-noto-cjk openjdk-21-jre-headless wget \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 lims \
    && useradd --uid 10001 --gid lims --no-create-home --shell /usr/sbin/nologin lims
WORKDIR /app
COPY --from=backend /build/target/lims-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /data/files && chown -R lims:lims /app /data
USER 10001
EXPOSE 8080
ENV CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/tmp/lims-home \
    XDG_CACHE_HOME=/tmp/lims-cache
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-Djava.security.egd=file:/dev/urandom","-jar","/app/app.jar"]
