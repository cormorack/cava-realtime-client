package com.bakdata.streams_store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.jaxrs2.integration.OpenApiServlet;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
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
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.*;

@OpenAPIDefinition(
        info = @Info(
                title = "Realtime Client API",
                version = "0.1",
                description = "API for OOI Live Data"
        )
)
@Path("")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class RestService {

    private final KafkaStreams streams;
    private final String raw = "__raw";
    private final String dash = "-";
    private final String streamMetadata = "metaData";
    private HostInfo hostInfo;
    private Server jettyServer;
    private final Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
    private InMemoryCache inMemoryCache;
    private static RedisClient redisClient;
    private boolean useRedis = false;

    /**
     *
     * @param streams
     * @param hostName
     * @param port
     */
    public RestService(final KafkaStreams streams, final String hostName, final int port, boolean useRemoteCache, URI rUri) {
        this.streams = streams;
        this.hostInfo = new HostInfo(hostName, port);

        if (!useRemoteCache) {
            this.inMemoryCache = new InMemoryCache();
        }
        if (useRemoteCache) {
            redisClient = RedisClient.getInstance(rUri);
        }
        useRedis = useRemoteCache;
    }

    /**
     *
     * @throws Exception
     */
    public void start() throws Exception {

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/feed");

        jettyServer = new Server(hostInfo.port());
        jettyServer.setHandler(context);

        ResourceConfig rc = new ResourceConfig();
        rc.register(this);
        rc.register(JacksonFeature.class);
        rc.register(CorsFilter.class, 1);

        ServletContainer sc = new ServletContainer(rc);
        ServletHolder holder = new ServletHolder(sc);
        context.addServlet(holder, "/*");

        // Setup API resources to be intercepted by Jersey
        ServletHolder jersey = context.addServlet(ServletContainer.class, "/api/*");
        jersey.setInitOrder(1);
        jersey.setInitParameter("jersey.config.server.provider.packages", "com.bakdata.streams_store; io.swagger.v3.jaxrs2.integration.resources");

        // Expose API definition independently into yaml/json
        ServletHolder openApi = context.addServlet(OpenApiServlet.class, "/openapi/*");
        openApi.setInitOrder(2);
        openApi.setInitParameter("openApi.configuration.resourcePackages", "com.bakdata.streams_store");

        // Setup Swagger-UI static resources
        String resourceBasePath;
        resourceBasePath = ServiceLoader.class.getResource("/webapp").toExternalForm();
        ServletHolder holderPwd = new ServletHolder("static-home", DefaultServlet.class);
        holderPwd.setInitParameter("resourceBase", resourceBasePath);
        holderPwd.setInitParameter("dirAllowed","true");
        holderPwd.setInitParameter("pathInfoOnly","true");
        holderPwd.setInitOrder(3);
        context.addServlet(holderPwd,"/docs/*");

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
        redisClient.destroyInstance();
    }

    /**
     * Gets data from a Kafka Streams Store by key
     * @param ref
     * @param uriInfo
     * @return
     * @throws InterruptedException
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "OOI Instrument Data", description = "Gets instrument data by reference designator")
    @ApiResponse(content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "200", description = "Ok")
    @ApiResponse(responseCode = "400", description = "Bad Request")
    @ApiResponse(responseCode = "404", description = "Error")
    @ApiResponse(responseCode = "500", description = "Internal Server Error")
    @ApiResponse(responseCode = "503", description = "Service Unavailable")
    @Tag(name = "GetDataByRef")
    public JsonNode valueByKey(
            @QueryParam("ref")
            @Parameter(description = "The reference designator", required = true)
            final String ref,
            @Context UriInfo uriInfo) throws InterruptedException {

        boolean hasCache = useRedis ? redisClient.get(ref) != null : inMemoryCache.get(ref) != null;

        JsonNode inMemoryCacheValue = (JsonNode) inMemoryCache.get(ref);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode streamNode = mapper.createObjectNode();

        ObjectNode notFoundNode = mapper.createObjectNode();
        notFoundNode.put("message", "No data is available");

        String[] parts = ref.split(dash);
        if (parts.length < 4) {
            return notFoundNode;
        }

        final String instrumentString = parts[0] + dash + parts[1] + dash + parts[2] + dash + parts[3];

        Collection<StreamsMetadata> metas = streams.allMetadata();
        Set<String> topics = new HashSet<>();
        Set<String> matches = new HashSet<>();

        if (!metas.isEmpty()) {

            for (StreamsMetadata smeta : metas) {
                // only use stores that have some active partitions
                if (smeta.topicPartitions().size() > 0) {
                    topics.addAll(smeta.stateStoreNames());
                }
            }
            for (String topic : topics) {
                if (topic.contains(instrumentString)) {
                    matches.add(topic);
                }
            }
        }

        if (metas.isEmpty()) {

            if (useRedis && redisClient.get(streamMetadata) != null) {
                matches = (Set<String>) new ArrayList<String>(Arrays.asList(redisClient.get("metadata").split(",")));
            }
            else if (!useRedis && inMemoryCache.get(streamMetadata) != null) {
                matches = (Set<String>) inMemoryCache.get(streamMetadata);
            }
            else {
                return notFoundNode;
            }
        }

        if (useRedis) {
            redisClient.remove(streamMetadata, matches.toString());
            redisClient.add(streamMetadata, matches.toString());
        }
        else {
            inMemoryCache.remove(streamMetadata);
            inMemoryCache.add(streamMetadata, matches);
        }

        boolean b = matches.stream().allMatch( s -> instrumentString.contains(s) );
        if (b) {
            return notFoundNode;
        }

        for (String match : matches) {

            String[] theseParts = match.split(dash);

            if (theseParts.length < 5) {
                streamNode.set(instrumentString, notFoundNode);
                continue;
            }

            String thisStreamString = theseParts[4] + dash + theseParts[5].replace(raw, "");
            String thisKey = match.replace(raw, "");

            ReadOnlyKeyValueStore<String, JsonNode> store = waitUntilStoreIsQueryable(match, QueryableStoreTypes.keyValueStore(), streams);

            if (store == null) {
                if (hasCache) {
                    if (useRedis) {
                        return fillNode(redisClient.get(ref), notFoundNode);
                    }
                    else {
                        return inMemoryCacheValue;
                    }
                }
                else {
                    streamNode.set(thisStreamString, notFoundNode);
                    continue;
                }
            }

            JsonNode value = store.get(thisKey);

            if (value == null) {
                streamNode.set(thisStreamString, notFoundNode);
                continue;
            }

            JsonNode jsonNode = composeJson(value);

            if (jsonNode == null) {
                streamNode.set(thisStreamString, notFoundNode);
                continue;
            }
            streamNode.set(thisStreamString, jsonNode);
        }

        ObjectNode instrumentNode = mapper.createObjectNode();
        instrumentNode.set(instrumentString, streamNode);

        if (useRedis) {
            String valueString = instrumentNode.toString();
            redisClient.remove(ref, valueString);
            redisClient.add(ref, valueString);
        }
        else {
            inMemoryCache.remove(ref);
            inMemoryCache.add(ref, instrumentNode);
        }
        return instrumentNode;
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
    @Path("/{key}/processors")
    @Produces(MediaType.APPLICATION_JSON)
    @Hidden
    public List<ProcessorMetadata> processors(@PathParam("key") final String key) {
        return streams.allMetadataForStore(key + raw)
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

    @GET()
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Hidden
    public HashMap status() {

        HashMap<String, String> statusMap = new HashMap<String, String>();
        statusMap.put("status", "running");
        statusMap.put("message", "Live Data Service is up.");
        return statusMap;
    }

    /**
     * Creates a JsonNode and attempts to fill it with a String value
     * @param cacheValue String value
     * @param notFoundNode ObjectNode with not found message
     * @return
     */
    private JsonNode fillNode(String cacheValue, ObjectNode notFoundNode) {

        ObjectNode node = null;
        try {
            node = (ObjectNode) new ObjectMapper().readTree(cacheValue);
        } catch (JsonProcessingException e) {
            System.out.println(e.getMessage());
            return notFoundNode;
        }
        return node;
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
                System.out.println("The Store is not yet ready for querying!");
                Thread.sleep(30000);
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
     * @param col
     * @param str
     * @return
     */
    private boolean containsSubString (List<String> col, String str) {

        for (String s: col) {
            if (str.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @param jsonNode
     * @return
     */
    private JsonNode composeJson(JsonNode jsonNode) {

        JsonNode dataNode = jsonNode.get("data");

        if (dataNode.isNull()) {
            return null;
        }

        List<String> exclusions = Arrays.asList("timestamp", "string", "coefficient", "volt");

        Iterator<Map.Entry<String, JsonNode>> it = dataNode.fields();

        while (it.hasNext()) {

            Map.Entry<String, JsonNode> nextKey = it.next();

            if (containsSubString(exclusions, nextKey.getKey())) {
                it.remove();
                ((ObjectNode)dataNode).remove(nextKey.getKey());
            }

            if (!containsSubString(exclusions, nextKey.getKey())) {

                if (nextKey.getValue().isArray()) {

                    int size = nextKey.getValue().size();
                    String thisKey = nextKey.getKey();
                    ((ObjectNode) dataNode).set(thisKey, nextKey.getValue().get(size -1));
                }
            }
        }
        return dataNode;
    }
}



