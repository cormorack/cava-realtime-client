package com.bakdata.streams_store;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.stream.Collectors;

@Path("messages")
public class RestService {

    private final KafkaStreams streams;
    private final String storeName;
    private HostInfo hostInfo;
    private Server jettyServer;
    private final Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();

    /**
     *
     * @param streams
     * @param storeName
     * @param hostName
     * @param port
     */
    public RestService(final KafkaStreams streams, final String storeName, final String hostName, final int port) {
        this.streams = streams;
        this.storeName = storeName;
        this.hostInfo = new HostInfo(hostName, port);
    }

    /**
     *
     * @throws Exception
     */
    public void start() throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        jettyServer = new Server(hostInfo.port());
        jettyServer.setHandler(context);

        ResourceConfig rc = new ResourceConfig();
        rc.register(this);
        rc.register(JacksonFeature.class);

        ServletContainer sc = new ServletContainer(rc);
        ServletHolder holder = new ServletHolder(sc);
        context.addServlet(holder, "/*");

        jettyServer.start();
    }

    /**
     *
     * @throws Exception
     */
    public void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
    }

    /**
     *
     * @param key
     * @param uriInfo
     * @return
     * @throws InterruptedException
     */
    @GET
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public KeyValueBean valueByKey(@PathParam("key") final String key, @Context UriInfo uriInfo) throws InterruptedException {

        //System.out.println("key is " + key);

        final StreamsMetadata metadata = streams.metadataForKey(storeName, key, Serdes.String().serializer());

        //System.out.println("metadata is " + metadata.toString());

        if (metadata == null) {
            throw new NotFoundException();
        }

         if (metadata.hostInfo().host().toString() != "unavailable" &&
                 metadata.hostInfo().port() != -1 &&
                 !metadata.hostInfo().equals(hostInfo)) {
             //System.out.println("metadata host is " + metadata.hostInfo() + "and hostInfo is " + hostInfo + "so, calling fetchValue ... ");
             return fetchValue(metadata.hostInfo(), uriInfo.getPath(), new GenericType<KeyValueBean>() {});
         }

        final ReadOnlyKeyValueStore<String, String> store = waitUntilStoreIsQueryable(storeName, QueryableStoreTypes.keyValueStore(), streams);

        if (store == null) {
            throw new NotFoundException();
        }

        final String value = store.get(key);

        if (value == null) {
            throw new NotFoundException();
        }
        //System.out.println("value is " + value);

        return new KeyValueBean(key, value);
    }

    /**
     *  Wait until the store of type T is queryable.  When it is, return a reference to the store
     * @param storeName
     * @param queryableStoreType
     * @param streams
     * @param <T>
     * @return
     * @throws InterruptedException
     */
    private static <T> T waitUntilStoreIsQueryable(final String storeName,
                                                  final QueryableStoreType<T> queryableStoreType,
                                                  final KafkaStreams streams) throws InterruptedException {
        while (true) {
            try {
                return streams.store(storeName, queryableStoreType);
            } catch (InvalidStateStoreException ignored) {
                // store not yet ready for querying
                Thread.sleep(100);
            }
        }
    }

    /**
     *
     * @param host
     * @param path
     * @param responseType
     * @param <T>
     * @return
     */
    private <T> T fetchValue(final HostInfo host, final String path, GenericType<T> responseType) {
        return client.target(String.format("http://%s:%d/%s", host.host(), host.port(), path))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(responseType);
    }

    /**
     *
     */
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public class ProcessorMetadata {
        private String host;
        private int port;
        private List<Integer> topicPartitions;
    }

    /**
     *
     * @return
     */
    @GET()
    @Path("/processors")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProcessorMetadata> processors() {
        return streams.allMetadataForStore(storeName)
                .stream()
                .map(metadata -> new ProcessorMetadata(
                        metadata.host(),
                        metadata.port(),
                        metadata.topicPartitions().stream()
                                .map(TopicPartition::partition)
                                .collect(Collectors.toList()))
                )
                .collect(Collectors.toList());
    }
}

