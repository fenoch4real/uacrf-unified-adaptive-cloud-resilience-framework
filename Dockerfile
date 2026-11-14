ARG BUILD_REVISION=unknown

FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS build
WORKDIR /workspace
ADD --checksum=sha256:a46cc51bc74fa23fd267c7a0b9132b146dcf526da60d31aa5174e565632e9e0e \
    https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.tar.gz \
    /tmp/apache-maven-3.10.0-bin.tar.gz
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar \
    /tmp/guava-33.7.2-jre.jar
RUN mkdir -p /opt/maven \
    && tar -xzf /tmp/apache-maven-3.10.0-bin.tar.gz --strip-components=1 -C /opt/maven \
    && rm /opt/maven/lib/guava-33.7.1-jre.jar \
    && mv /tmp/guava-33.7.2-jre.jar /opt/maven/lib/guava-33.7.2-jre.jar \
    && rm /tmp/apache-maven-3.10.0-bin.tar.gz
COPY . .
RUN /opt/maven/bin/mvn --batch-mode --no-transfer-progress \
    -pl services/api-edge,services/experience-api,services/identity,services/estate-catalog,services/connections,services/evidence,web \
    -am -Dmaven.test.skip=true package \
    && mkdir -p /out \
    && cp services/api-edge/target/api-edge-*.jar /out/api-edge.jar \
    && cp services/experience-api/target/experience-api-*.jar /out/experience-api.jar \
    && cp services/identity/target/identity-service-*.jar /out/identity-service.jar \
    && cp services/estate-catalog/target/estate-catalog-service-*.jar /out/estate-catalog-service.jar \
    && cp services/connections/target/connections-service-*.jar /out/connections-service.jar \
    && cp services/evidence/target/evidence-service-*.jar /out/evidence-service.jar \
    && cp web/target/web-*.jar /out/web.jar \
    && rm -rf /root/.m2

FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699 AS runtime-base
WORKDIR /app
ARG BUILD_REVISION
LABEL org.opencontainers.image.source="https://github.com/fenoch4real/uacrf-unified-adaptive-cloud-resilience-framework" \
      org.opencontainers.image.revision="${BUILD_REVISION}"
USER 10001:10001

FROM runtime-base AS api-edge
COPY --from=build --chown=10001:10001 /out/api-edge.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS experience-api
COPY --from=build --chown=10001:10001 /out/experience-api.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS identity
COPY --from=build --chown=10001:10001 /out/identity-service.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS estate-catalog
COPY --from=build --chown=10001:10001 /out/estate-catalog-service.jar /app/app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS connections
COPY --from=build --chown=10001:10001 /out/connections-service.jar /app/app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS evidence
COPY --from=build --chown=10001:10001 /out/evidence-service.jar /app/app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

FROM runtime-base AS web
COPY --from=build --chown=10001:10001 /out/web.jar /app/app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
