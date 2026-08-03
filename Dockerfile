# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
COPY profiles ./profiles
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

# Create non-root user for security
RUN groupadd -r dede && useradd -r -g dede dede

WORKDIR /app

# Copy artifacts
COPY --from=build /app/target/dede-java-*-exec.jar app.jar
COPY --from=build /app/profiles ./profiles

# Create log directory
RUN mkdir -p /var/log/dede && chown -R dede:dede /var/log/dede /app

# Switch to non-root user
USER dede

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Environment defaults
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport"

# Run application.
# The trailing "--" is required: in `sh -c "script" arg0 arg1...`, the first
# appended arg becomes $0 (the script's own conventional name slot), not part of
# "$@" (which expands from $1 onward). Without this placeholder, `docker run
# image --help` silently drops --help entirely (args.length == 0), which starts
# the app as a REST/GraphQL/Web UI server instead of running one-shot and exiting.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
