# Queryable Kafka Topics with Kafka Streams

This application is derived from a blog post on [Medium](https://medium.com/bakdata/queryable-kafka-topics-with-kafka-streams-8d2cca9de33f).

It is an implementation of a Kafka Streams application that provides a key-value query interface to the messages of key-partitioned Kafka topics.

## Quick Start

Compile the project using Maven: 
```
mvn package
```

Start one or more instances of the Kafka Streams application:

```
./streams-processor                         \
    --streams-props                         \
        bootstrap.servers=localhost:9092    \
        num.standby.replicas=1              \
    --application-id my-streams-processor   \
    --hostname localhost
```
You can build a Docker image with: 

```
docker build -f resources/docker/Dockerfile -t kafka-kv .
```
and run it with:

```
docker run -p 8081:8081 kafka-kv --streams-props bootstrap.servers=kafka:29092 num.standby.replicas=1 --application-id my-streams-processor --hostname localhost
```