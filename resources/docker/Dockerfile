ARG MAVEN_IMAGE_VERSION=3.5-jdk-8-slim
ARG OPENJDK_IMAGE_VERSION=8-jre-slim
FROM maven:$MAVEN_IMAGE_VERSION

WORKDIR /app

# Restore maven dependencies in a separate build step
ADD pom.xml .
RUN mvn clean verify --fail-never

ADD src src
RUN mvn package

FROM openjdk:$OPENJDK_IMAGE_VERSION

WORKDIR /app

COPY --from=0 /app/target target
ADD streams-processor .

EXPOSE 8081
#CMD "./streams-processor"
ENTRYPOINT ["./streams-processor" ]
