# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 -m worksystem \
 && mkdir -p /app/uploads /app/releases \
 && chown -R worksystem:worksystem /app
COPY --from=build /workspace/target/*.jar /app/app.jar
USER worksystem
ENV JAVA_OPTS="" \
    SERVER_PORT=8080 \
    PROFILE_IMAGE_DIR=/app/uploads/profile-images \
    TRIP_RECEIPT_DIR=/app/uploads/trip-receipts \
    SIGNATURE_IMAGE_DIR=/app/uploads/signatures \
    NOTICE_IMAGE_DIR=/app/uploads/notice-images \
    APP_RELEASE_DIR=/app/releases
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
