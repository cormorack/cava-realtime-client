package com.bakdata.streams_store;

import com.fasterxml.jackson.databind.JsonNode;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.json.JsonSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
//import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.Stores;


import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static net.sourceforge.argparse4j.impl.Arguments.store;

public class App {

    public static void main(String[] args) throws Exception {
        ArgumentParser parser = argParser();
        Properties props = new Properties();
        String hostName = null;
        Integer port = null;
        boolean useRedis = false;

        try {
            Namespace res = parser.parseArgs(args);
            hostName = res.getString("hostname");
            port = res.getInt("port");
            String applicationId = res.getString("applicationId");
            List<String> streamsProps = res.getList("streamsConfig");
            String streamsConfig = res.getString("streamsConfigFile");
            useRedis = res.getBoolean("useRedis");

            if (streamsProps == null && streamsConfig == null) {
                throw new ArgumentParserException("Either --streams-props or --streams.config must be specified.", parser);
            }

            if (streamsConfig != null) {
                try (InputStream propStream = Files.newInputStream(Paths.get(streamsConfig))) {
                    props.load(propStream);
                }
            }

            if (streamsProps != null) {
                for (String prop : streamsProps) {
                    String[] pieces = prop.split("=");
                    if (pieces.length != 2)
                        throw new IllegalArgumentException("Invalid property: " + prop);
                    props.put(pieces[0], pieces[1]);
                }
            }

            props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
            props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, hostName + ":" + port);
            props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
            props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
            props.put("key.deserializer", StringDeserializer.class.getName());
            props.put("value.deserializer", StringDeserializer.class.getName());
            props.put(StreamsConfig.RETRIES_CONFIG, 10);
            props.put(StreamsConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        } catch (ArgumentParserException e) {
            if (args.length == 0) {
                parser.printHelp();
                System.exit(0);
            } else {
                parser.handleError(e);
                System.exit(1);
            }
        }

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);

        final StreamsBuilder builder = new StreamsBuilder();

        buildIt(builder, consumer);

        final Topology topology = builder.build();

        final KafkaStreams streams = new KafkaStreams(topology, props);

        streams.setUncaughtExceptionHandler((Thread thread, Throwable throwable) -> {

            System.out.println("Uncaught Exception in thread " + thread.getName());
            System.out.println("Exception is " + throwable.getMessage());
        });
        /*streams.setUncaughtExceptionHandler(ex -> {
            System.out.println("Kafka-Streams uncaught exception occurred. Stream will be replaced with new thread");
            System.out.println("Exception is " + ex.getMessage());
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });*/

        final RestService restService = new RestService(streams, hostName, port, useRedis);

        restService.start();

        if (useRedis) {
            System.out.println("Using redis cache");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> streams.close()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                restService.stop();
            } catch (Exception e) {}
        }));

        streams.start();
        consumer.close();
    }

    private static StreamsBuilder buildIt(
            StreamsBuilder builder,
            KafkaConsumer consumer) {

        Map<String, List<PartitionInfo>> topics = consumer.listTopics();
        Set<String> topicNames = topics.keySet();

        for (String name : topicNames) {

            if (name.contains("changelog") || name.contains("consumer_offsets")) {
                continue;
            }

            KeyValueBytesStoreSupplier stateStore = Stores.inMemoryKeyValueStore(name);

            final Serializer<JsonNode> jsonNodeSerializer = new JsonSerializer();
            final Deserializer<JsonNode> jsonNodeDeserializer = new JsonDeserializer();
            final Serde<JsonNode> jsonNodeSerde = Serdes.serdeFrom(jsonNodeSerializer,jsonNodeDeserializer);

            KTable<String, JsonNode> table = builder.table(
                    name,
                    Materialized.<String, JsonNode>as(stateStore)
                            .withKeySerde(Serdes.String())
                            .withValueSerde(jsonNodeSerde)
            );
        }
        return builder;
    }

    private static ArgumentParser argParser() {
        ArgumentParser parser = ArgumentParsers
                .newFor("streams-processor").build()
                .defaultHelp(true)
                .description("This Kafka Streams application is used to interactively query values from Kafka topics");

        parser.addArgument("--topic")
                .action(store())
                .required(false)
                .type(String.class)
                .metavar("TOPIC")
                .setDefault("RS03AXPS-SF03A-2A-CTDPFA302-streamed-ctdpf_sbe43_sample__raw")
                .help("process messages from this topic");

        parser.addArgument("--streams-props")
                .nargs("+")
                .required(false)
                .metavar("PROP-NAME=PROP-VALUE")
                .type(String.class)
                .dest("streamsConfig")
                .help("kafka streams related configuration properties like bootstrap.servers etc. " +
                        "These configs take precedence over those passed via --streams.config.");

        parser.addArgument("--streams.config")
                .action(store())
                .required(false)
                .type(String.class)
                .metavar("CONFIG-FILE")
                .dest("streamsConfigFile")
                .help("streams config properties file.");

        parser.addArgument("--application-id")
                .action(store())
                .required(false)
                .type(String.class)
                .metavar("APPLICATION-ID")
                .dest("applicationId")
                .setDefault("streams-processor-default-application-id")
                .help("The id of the streams application to use. Useful for monitoring and resetting the streams application state.");

        parser.addArgument("--hostname")
                .action(store())
                .required(false)
                .type(String.class)
                .metavar("HOSTNAME")
                .setDefault("localhost")
                .help("The host name of this machine / pod / container. Used for inter-processor communication.");

        parser.addArgument("--port")
                .action(store())
                .required(false)
                .type(Integer.class)
                .metavar("PORT")
                .setDefault(8081)
                .help("The TCP Port for the HTTP REST Service");
        parser.addArgument("--useRedis")
                .action(store())
                .required(false)
                .type(Boolean.class)
                .metavar("USE-REDIS")
                .setDefault(false)
                .help("Whether or not to use redis");

        return parser;
    }

}
