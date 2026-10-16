# Build Andromedia core API
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -pl core -am package -DskipTests

# Runtime image
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd --system andromedia \
  && useradd --system --gid andromedia --create-home --home-dir /home/andromedia andromedia

WORKDIR /app

COPY --from=build /src/core/target/andromedia-core-0.1.0-SNAPSHOT.jar /app/app.jar

RUN mkdir -p /home/andromedia/.andromedia \
  && chown -R andromedia:andromedia /app /home/andromedia

USER andromedia
ENV HOME=/home/andromedia
ENV JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8083/api/v1/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
