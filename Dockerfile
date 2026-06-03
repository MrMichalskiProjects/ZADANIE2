# syntax=docker/dockerfile:1.7


# Stage 1: build aplikacji (Maven Wrapper + JDK)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# kopiujemy wszystko co opisuje jak budowac apke
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:resolve-plugins

# kopiujemy kod i kompilujemy
COPY src ./src
RUN ./mvnw -B -q -DskipTests package \
 && cp target/apka1.jar /tmp/app.jar # /tmp jest konwencjonalne


# Stage 2: zbieramy biblioteki do odpalenia jaby na scratch
FROM eclipse-temurin:21-jdk-alpine AS jre

# szyjemy na miare jdk do tego co potrzebujemy
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.base,java.net.http,jdk.httpserver,java.logging,jdk.crypto.ec,jdk.crypto.cryptoki,java.naming,jdk.unsupported \
      --strip-debug --no-header-files --no-man-pages --compress=2 \
      --output /opt/jre

# Zbieramy biblioteki  konieczne do uruchomienia java na scratch
# Loader musl ma nazwe zalezna od architektury (ld-musl-x86_64 / ld-musl-aarch64),
# dlatego uzywamy globa zamiast sztywnej nazwy - dzieki temu obraz buduje sie
# zarowno dla linux/amd64 jak i linux/arm64.
RUN mkdir -p /rootfs/lib /rootfs/usr/lib /rootfs/tmp /rootfs/app \
 && cp /lib/ld-musl-*.so.1          /rootfs/lib/ \
 && cp /usr/lib/libz.so.1           /rootfs/usr/lib/ \
 && cp /usr/lib/libstdc++.so.6      /rootfs/usr/lib/ \
 && cp /usr/lib/libgcc_s.so.1       /rootfs/usr/lib/ \
 && chmod 1777 /rootfs/tmp


# Stage 3: scartch z bibliotekami, jdk i apka
FROM scratch

LABEL org.opencontainers.image.authors="Mateusz Michalski" 

COPY --from=jre /rootfs/    /
COPY --from=jre /opt/jre    /opt/jre
COPY --from=build /tmp/app.jar /app/apka1.jar

ENV PORT=8080 \
    PATH=/opt/jre/bin

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/opt/jre/bin/java", "-jar", "/app/apka1.jar", "healthcheck"]

ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/apka1.jar"]
