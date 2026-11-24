# Trailscape-Bau-Dienst („trailscape-worlds", docs/indoor/serverbau.md).
# Bauen aus dem Wurzelverzeichnis des Repos (das Werkzeug braucht :core):
#   git submodule update --init third_party/brouter
#   docker build -f server/worlds/Dockerfile -t trailscape-worlds .

# --- 1. Werkzeug bauen (JDK, Gradle-Wrapper, ohne Android-SDK) ---------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY brouter brouter
COPY third_party/brouter third_party/brouter
COPY core core
COPY tool/indoor-dev tool/indoor-dev
RUN ./gradlew --no-daemon -Ptrailscape.toolOnly=true :indoor-dev:installDist \
 && test -x tool/indoor-dev/build/install/indoor-dev/bin/indoor-dev

# --- 2. Laufzeit: JRE + Node (HTTP-Schicht) ----------------------------------
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin trailscape \
 && mkdir -p /data && chown trailscape:trailscape /data
COPY --from=build /src/tool/indoor-dev/build/install/indoor-dev /opt/indoor-dev
WORKDIR /app
COPY server/worlds/worlds.mjs ./
# Die Daten (Aufträge, Pakete, Geodaten-Zwischenspeicher) liegen nur unter /data.
VOLUME /data
ENV DATA_DIR=/data \
    PORT=8081 \
    BUILDER_CMD=/opt/indoor-dev/bin/indoor-dev \
    JAVA_OPTS="-Xmx3g -XX:+ExitOnOutOfMemoryError"
USER trailscape
EXPOSE 8081
CMD ["node", "worlds.mjs"]
