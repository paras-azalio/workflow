# The cr_automation gateway (users, roles, sessions, audit) in front of Kestra. Built from source, JDK only.
# Build context is BST/ (see kestra-local/docker-compose.yml); Dockerfile.dockerignore keeps it to what is copied.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /b
COPY component-wrappers/target/component-wrappers-0.1.0.jar kestra-local/namespace-files/cr_automation/lib/postgresql-42.7.4.jar libs/
COPY gateway/src/main/java src
COPY gateway/src/main/resources classes
RUN javac -d classes -cp "libs/*" $(find src -name '*.java') && jar cf libs/gateway.jar -C classes .

FROM eclipse-temurin:21-jdk
RUN useradd --system --uid 10001 gateway
COPY --from=build /b/libs/ /app/
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/*", "com.nokia.workflow.gateway.GatewayMain"]
