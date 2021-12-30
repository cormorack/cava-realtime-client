package com.bakdata.streams_store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.kafka.common.serialization.Serdes;
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
import org.json.JSONObject;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
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
    public RestService(final KafkaStreams streams, final String hostName, final int port, boolean useCache) {
        this.streams = streams;
        this.hostInfo = new HostInfo(hostName, port);
        this.inMemoryCache = new InMemoryCache();
        redisClient = RedisClient.getInstance(6379);
        useRedis = useCache;
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
     * @param key
     * @param uriInfo
     * @return
     * @throws InterruptedException
     */
    @GET
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "OOI Instrument Data", description = "Gets instrument data by key")
    @ApiResponse(content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "200", description = "Ok")
    @ApiResponse(responseCode = "400", description = "Bad Request")
    @ApiResponse(responseCode = "404", description = "Error")
    @ApiResponse(responseCode = "500", description = "Internal Server Error")
    @ApiResponse(responseCode = "503", description = "Service Unavailable")
    @Tag(name = "GetDataByKey")
    public KeyValueBean valueByKey(
            @PathParam("key")
            @Parameter(description = "The data key", required = true)
            final String key,
            @Context UriInfo uriInfo) throws InterruptedException {

        String keyStore = key + raw;

        boolean hasCache = useRedis ? redisClient.get(key) != null : inMemoryCache.get(key) != null;

        String cacheValue = useRedis ? redisClient.get(key) : (String) inMemoryCache.get(key);

        final StreamsMetadata metadata = streams.metadataForKey(keyStore, key, Serdes.String().serializer());

        if (metadata == null) {
            if (hasCache) {
                return new KeyValueBean(key, cacheValue);
            } else {
                throw new NotFoundException();
            }
        }

        if (metadata.hostInfo().host() != "unavailable" &&
                 metadata.hostInfo().port() != -1 &&
                 !metadata.hostInfo().equals(hostInfo)) {

             return fetchValue(metadata.hostInfo(), uriInfo.getPath(), new GenericType<KeyValueBean>() {});
        }

        final ReadOnlyKeyValueStore<String, String> store = waitUntilStoreIsQueryable(keyStore, QueryableStoreTypes.keyValueStore(), streams);

        if (store == null) {
            if (hasCache) {
                return new KeyValueBean(key, cacheValue);
            } else {
                throw new NotFoundException();
            }
        }

        final String value = store.get(key);

        if (value == null) {
            throw new NotFoundException();
        }

        String valueString = toJson(value);

        if (useRedis) {
            redisClient.remove(key, valueString);
            redisClient.add(key, valueString);
        }
        else {
            inMemoryCache.remove(key);
            inMemoryCache.add(key, valueString);
        }
        return new KeyValueBean(key, valueString);
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
                System.out.println("The Store not yet ready for querying!");
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
    public String status() {

        return "App is up!";
    }

    /**
     *
     * @param valueString
     * @return java.lang.String
     */
    private String toJson(String valueString) {

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = null;

        try {
            actualObj = mapper.readTree(valueString);
        } catch (IOException e) {
            e.printStackTrace();
            return valueString;
        }

        JsonNode dataNode = actualObj.get("data");
        if (dataNode.isNull()) {
            return valueString;
        }

        JSONObject json = new JSONObject();
        processNode(dataNode, json, 0);
        return json.toString();
    }

    /**
     *
     * @param jsonNode
     * @param json
     * @param depth
     */
    private void processNode(JsonNode jsonNode, JSONObject json, int depth) {

        /*if (jsonNode.isValueNode()) {
          System.out.println("I'm a valueNode " + splitLast( jsonNode.asText()));
        }*/
        if (jsonNode.isArray()) {
            for (JsonNode arrayItem : jsonNode) {
                appendNodeToJson(arrayItem, json, depth, true);
            }
        }
        else if (jsonNode.isObject()) {
            appendNodeToJson(jsonNode, json, depth, false);
        }
    }

    /**
     *
     * @param node
     * @param json
     * @param depth
     * @param isArrayItem
     */
    private void appendNodeToJson(JsonNode node, JSONObject json, int depth, boolean isArrayItem) {

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        boolean isFirst = true;

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> jsonField = fields.next();
            addFieldNameToJson(json, jsonField.getKey(), jsonField.getValue().toString(), depth, isArrayItem && isFirst);
            processNode(jsonField.getValue(), json, depth + 1);
            isFirst = false;
        }
    }

    /**
     *
     * @param json
     * @param fieldName
     * @param fieldValue
     * @param depth
     * @param isFirstInArray
     */
    private void addFieldNameToJson(JSONObject json, String fieldName, String fieldValue, int depth, boolean isFirstInArray) {

        if (!fieldName.contains("timestamp") &&
                !fieldName.contains("string") &&
                !fieldName.contains("coefficient") &&
                !fieldName.contains("volt")) {

            json.put(fieldName, getLastValue(fieldValue));
        }
    }

    /**
     *
     * @param value
     * @return java.lang.String
     */
    private String getLastValue(String value) {

        String newVal = value;
        int index = value.lastIndexOf(",");
        if (index != -1) {
            newVal = value.substring(index + 1);
        }
        return filterCharacters(newVal);
    }

    /**
     *
     * @param value
     * @return java.lang.String
     */
    private String filterCharacters(String value) {

        String newValue = value.replaceAll("\\]", "")
                .replaceAll("\\[", "")
                .replaceAll("\"", "");
        return newValue;
    }

}



