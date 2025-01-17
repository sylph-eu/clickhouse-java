package com.clickhouse.client.api;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseClientBuilder;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseNodeSelector;
import com.clickhouse.client.ClickHouseProtocol;
import com.clickhouse.client.ClickHouseRequest;
import com.clickhouse.client.ClickHouseResponse;
import com.clickhouse.client.api.insert.DataSerializationException;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.insert.POJOSerializer;
import com.clickhouse.client.api.insert.SerializerNotFoundException;
import com.clickhouse.client.api.internal.SerializerUtils;
import com.clickhouse.client.api.internal.SettingsConverter;
import com.clickhouse.client.api.internal.TableSchemaParser;
import com.clickhouse.client.api.internal.ValidationUtils;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataStreamFactory;
import com.clickhouse.data.ClickHouseFormat;
import com.clickhouse.data.ClickHousePipedOutputStream;
import com.clickhouse.data.format.BinaryStreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.time.temporal.ChronoUnit.SECONDS;

public class Client {
    public static final int TIMEOUT = 30_000;
    private Set<String> endpoints;
    private Map<String, String> configuration;
    private List<ClickHouseNode> serverNodes = new ArrayList<>();
    private Map<Class<?>, List<POJOSerializer>> serializers;//Order is important to preserve for RowBinary
    private Map<Class<?>, Map<String, Method>> getterMethods;
    private Map<Class<?>, Boolean> hasDefaults;
    private static final Logger LOG = LoggerFactory.getLogger(Client.class);
    private ExecutorService queryExecutor;

    private Map<String, OperationStatistics.ClientStatistics> globalClientStats = new ConcurrentHashMap<>();

    private Client(Set<String> endpoints, Map<String,String> configuration) {
        this.endpoints = endpoints;
        this.configuration = configuration;
        this.endpoints.forEach(endpoint -> {
            this.serverNodes.add(ClickHouseNode.of(endpoint, this.configuration));
        });
        this.serializers = new HashMap<>();
        this.getterMethods = new HashMap<>();
        this.hasDefaults = new HashMap<>();

        final int numThreads = Integer.parseInt(configuration.getOrDefault(
                ClickHouseClientOption.MAX_THREADS_PER_CLIENT.getKey(), "3"));
        this.queryExecutor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("ClickHouse-Query-Executor");
            t.setUncaughtExceptionHandler((t1, e) -> {
                LOG.error("Uncaught exception in thread {}", t1.getName(), e);
            });
            return t;
        });
        LOG.debug("Query executor created with {} threads", numThreads);
    }

    public static class Builder {
        private Set<String> endpoints;
        private Map<String, String> configuration;

        public Builder() {
            this.endpoints = new HashSet<>();
            this.configuration = new HashMap<String, String>();
            // TODO: set defaults configuration values
            this.setConnectTimeout(30, SECONDS)
                    .setSocketTimeout(2, SECONDS)
                    .setSocketRcvbuf(804800)
                    .setSocketSndbuf(804800)
                    .enableCompression(true)
                    .enableDecompression(false);
        }

        public Builder addEndpoint(String endpoint) {
            // TODO: validate endpoint
            this.endpoints.add(endpoint);
            return this;
        }

        public Builder addEndpoint(Protocol protocol, String host, int port) {
            String endpoint = String.format("%s://%s:%d", protocol.toString().toLowerCase(), host, port);
            this.addEndpoint(endpoint);
            return this;
        }

        public Builder addConfiguration(String key, String value) {
            this.configuration.put(key, value);
            return this;
        }

        public Builder addUsername(String username) {
            this.configuration.put("user", username);
            return this;
        }

        public Builder addPassword(String password) {
            this.configuration.put("password", password);
            return this;
        }

        public Builder addAccessToken(String accessToken) {
            this.configuration.put("access_token", accessToken);
            return this;
        }

        // SOCKET SETTINGS
        public Builder setConnectTimeout(long size) {
            this.configuration.put("connect_timeout", String.valueOf(size));
            return this;
        }
        public Builder setConnectTimeout(long amount, ChronoUnit unit) {
            this.setConnectTimeout(Duration.of(amount, unit).toMillis());
            return this;
        }

        public Builder setSocketTimeout(long size) {
            this.configuration.put("socket_timeout", String.valueOf(size));
            return this;
        }
        public Builder setSocketTimeout(long amount, ChronoUnit unit) {
            this.setSocketTimeout(Duration.of(amount, unit).toMillis());
            return this;
        }
        public Builder setSocketRcvbuf(long size) {
            this.configuration.put("socket_rcvbuf", String.valueOf(size));
            return this;
        }
        public Builder setSocketSndbuf(long size) {
            this.configuration.put("socket_sndbuf", String.valueOf(size));
            return this;
        }
        public Builder setSocketReuseaddr(boolean value) {
            this.configuration.put("socket_reuseaddr", String.valueOf(value));
            return this;
        }
        public Builder setSocketKeepalive(boolean value) {
            this.configuration.put("socket_keepalive", String.valueOf(value));
            return this;
        }
        public Builder setSocketTcpNodelay(boolean value) {
            this.configuration.put("socket_tcp_nodelay", String.valueOf(value));
            return this;
        }
        public Builder setSocketLinger(int secondsToWait) {
            this.configuration.put("socket_linger", String.valueOf(secondsToWait));
            return this;
        }
        public Builder enableCompression(boolean enabled) {
            this.configuration.put("compress", String.valueOf(enabled));
            return this;
        }
        public Builder enableDecompression(boolean enabled) {
            this.configuration.put("decompress", String.valueOf(enabled));
            return this;
        }
        public Client build() {
            // check if endpoint are empty. so can not initiate client
            if (this.endpoints.isEmpty()) {
                throw new IllegalArgumentException("At least one endpoint is required");
            }
            // check if username and password are empty. so can not initiate client?
            if (!this.configuration.containsKey("access_token") && (!this.configuration.containsKey("user") || !this.configuration.containsKey("password"))) {
                throw new IllegalArgumentException("Username and password are required");
            }
            return new Client(this.endpoints, this.configuration);
        }
    }

    private ClickHouseNode getServerNode() {
        // TODO: implement load balancing using existing logic
        return this.serverNodes.get(0);
    }

    /**
     * Ping the server to check if it is alive
     * @return true if the server is alive, false otherwise
     */
    public boolean ping() {
        return ping(Client.TIMEOUT);
    }

    /**
     * Ping the server to check if it is alive
     * @param timeout timeout in milliseconds
     * @return true if the server is alive, false otherwise
     */
    public boolean ping(int timeout) {
        ClickHouseClient clientPing = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);
        return clientPing.ping(getServerNode(), timeout);
    }

    /**
     * Register the POJO
     */
    public void register(Class<?> clazz, TableSchema schema) {
        LOG.debug("Registering POJO: {}", clazz.getName());

        //Create a new POJOSerializer with static .serialize(object, columns) methods
        List<POJOSerializer> serializers = new ArrayList<>();
        Map<String, Method> getterMethods = new HashMap<>();

        for (Method method: clazz.getMethods()) {//Clean up the method names
            String methodName = method.getName();
            if (methodName.startsWith("get") || methodName.startsWith("has")) {
                methodName = methodName.substring(3).toLowerCase();
                getterMethods.put(methodName, method);
            } if (methodName.startsWith("is")) {
                methodName = methodName.substring(2).toLowerCase();
                getterMethods.put(methodName, method);
            }
        }
        this.getterMethods.put(clazz, getterMethods);//Store the getter methods for later use

        for (ClickHouseColumn column : schema.getColumns()) {
            String columnName = column.getColumnName().toLowerCase().replace("_", "");
            serializers.add((obj, stream) -> {
                if (!getterMethods.containsKey(columnName)) {
                    LOG.warn("No getter method found for column: {}", columnName);
                    return;
                }
                Method getterMethod = this.getterMethods.get(clazz).get(columnName);
                Object value = getterMethod.invoke(obj);
                boolean hasDefaults = this.hasDefaults.get(clazz);

                //Handle null values
                if (value == null) {
                    if (hasDefaults && !column.hasDefault()) {//Send this only if there is no default
                        BinaryStreamUtils.writeNonNull(stream);
                    }
                    BinaryStreamUtils.writeNull(stream);//We send this regardless of default or nullable
                    return;
                }

                //Handle default
                if (hasDefaults) {
                    BinaryStreamUtils.writeNonNull(stream);//Write 0
                }

                //Handle nullable
                if (column.isNullable()) {
                    BinaryStreamUtils.writeNonNull(stream);//Write 0
                }

                //Handle the different types
                SerializerUtils.serializeData(stream, value, column);
            });
        }
        this.serializers.put(clazz, serializers);
        this.hasDefaults.put(clazz, schema.hasDefaults());
    }

    /**
     * Insert data into ClickHouse using a POJO
     */
    public Future<InsertResponse> insert(String tableName,
                                         List<Object> data,
                                         InsertSettings settings) {

        String operationId = startOperation();
        settings.setOperationId(operationId);
        globalClientStats.get(operationId).start("serialization");

        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be empty");
        }

        //Add format to the settings
        if (settings == null) {
            settings = new InsertSettings();
        }

        boolean hasDefaults = this.hasDefaults.get(data.get(0).getClass());
        ClickHouseFormat format = hasDefaults? ClickHouseFormat.RowBinaryWithDefaults : ClickHouseFormat.RowBinary;

        //Create an output stream to write the data to
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        //Lookup the Serializer for the POJO
        List<POJOSerializer> serializers = this.serializers.get(data.get(0).getClass());
        if (serializers == null || serializers.isEmpty()) {
            throw new SerializerNotFoundException(data.get(0).getClass());
        }

        //Call the static .serialize method on the POJOSerializer for each object in the list
        for (Object obj : data) {
            for (POJOSerializer serializer : serializers) {
                try {
                    serializer.serialize(obj, stream);
                } catch (InvocationTargetException | IllegalAccessException | IOException  e) {
                    throw new DataSerializationException(obj, serializer, e);
                }
            }
        }

        globalClientStats.get(operationId).stop("serialization");
        LOG.debug("Total serialization time: {}", globalClientStats.get(operationId).getElapsedTime("serialization"));
        return insert(tableName, new ByteArrayInputStream(stream.toByteArray()), format, settings);
    }

    /**
     * Insert data into ClickHouse using a binary stream
     */
    public Future<InsertResponse> insert(String tableName,
                                     InputStream data,
                                     ClickHouseFormat format,
                                     InsertSettings settings) {
        String operationId = (String) settings.getOperationId();
        if (operationId == null) {
            operationId = startOperation();
            settings.setOperationId(operationId);
        }
        OperationStatistics.ClientStatistics clientStats = globalClientStats.remove(operationId);
        clientStats.start("insert");

        CompletableFuture<InsertResponse> responseFuture = new CompletableFuture<>();

        try (ClickHouseClient client = createClient()) {
            ClickHouseRequest.Mutation request = createMutationRequest(client.write(getServerNode()), tableName, settings).format(format);
            CompletableFuture<ClickHouseResponse> future = null;
            try(ClickHousePipedOutputStream stream = ClickHouseDataStreamFactory.getInstance().createPipedOutputStream(request.getConfig())) {
                future = request.data(stream.getInputStream()).execute();

                //Copy the data from the input stream to the output stream
                byte[] buffer = new byte[settings.getInputStreamBatchSize()];
                int bytesRead;
                while ((bytesRead = data.read(buffer)) != -1) {
                    stream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                responseFuture.completeExceptionally(new ClientException("Failed to write data to the output stream", e));
            }

            if (!responseFuture.isCompletedExceptionally()) {
                try {
                    InsertResponse response = new InsertResponse(client, future.get(), clientStats);
                    responseFuture.complete(response);
                } catch (InterruptedException | ExecutionException e) {
                    responseFuture.completeExceptionally(new ClientException("Operation has likely timed out.", e));
                }
            }
            clientStats.stop("insert");
            LOG.debug("Total insert (InputStream) time: {}", clientStats.getElapsedTime("insert"));
        }

        return responseFuture;
    }

    /**
     * Sends SQL query to server. Default settings are applied.

     * @param sqlQuery - complete SQL query.
     * @return Future<QueryResponse> - a promise to query response.
     */
    public Future<QueryResponse> query(String sqlQuery) {
        return query(sqlQuery, null, null);
    }

    /**
     * Sends SQL query to server.
     * <ul>
     * <li>Server response format can be specified thru `settings` or in SQL query.</li>
     * <li>If specified in both, the `sqlQuery` will take precedence.</li>
     * </ul>
     * @param sqlQuery - complete SQL query.
     * @param settings - query operation settings.
     * @return Future<QueryResponse> - a promise to query response.
     */
    public Future<QueryResponse> query(String sqlQuery, QuerySettings settings) {
        return query(sqlQuery, null, settings);
    }



    /**
     * Sends SQL query to server with parameters. The map `queryParams` should contain keys that
     * match the placeholders in the SQL query.
     * <br>
     * Parametrized query example:
     * Sql:
     * <pre>
     * SELECT * FROM table WHERE int_column = {id:UInt8} and string_column = {phrase:String}
     * </pre>
     * queryParams:
     * <pre>
     *      Map<String, Object> queryParams = new HashMap<>();
     *      queryParams.put("id", 1);
     *      queryParams.put("phrase", "hello");
     * </pre>
     *
     * <ul>
     * <li>Server response format can be specified thru `settings` or in SQL query.</li>
     * <li>If specified in both, the `sqlQuery` will take precedence.</li>
     * </ul>
     *
     *
     *
     * @param sqlQuery - complete SQL query.
     * @param settings - query operation settings.
     * @param queryParams - query parameters that are sent to the server. (Optional)
     * @return Future<QueryResponse> - a promise to query response.
     */
    public Future<QueryResponse> query(String sqlQuery, Map<String, Object> queryParams, QuerySettings settings) {

        OperationStatistics.ClientStatistics clientStats = new OperationStatistics.ClientStatistics();
        clientStats.start("query");
        ClickHouseClient client = createClient();
        ClickHouseRequest<?> request = client.read(getServerNode());

        if (settings == null) {
            settings = new QuerySettings();
            settings.setFormat(ClickHouseFormat.TabSeparatedWithNamesAndTypes.name());
        }

        ExecutorService executor = queryExecutor;
        if (settings.getExecutorService() != null) {
            executor = settings.getExecutorService();
        }

        request.options(SettingsConverter.toRequestOptions(settings.getAllSettings()));
        request.settings(SettingsConverter.toRequestSettings(settings.getAllSettings(), queryParams));
        request.query(sqlQuery, settings.getQueryID());
        final ClickHouseFormat format = ClickHouseFormat.valueOf(settings.getFormat());
        request.format(format);

        CompletableFuture<QueryResponse> future = new CompletableFuture<>();
        final QuerySettings finalSettings = settings;
        executor.submit(() -> {
            MDC.put("queryId", finalSettings.getQueryID());
            LOG.trace("Executing request: {}", request);
            try {
                QueryResponse queryResponse = new QueryResponse(client, request.execute(), finalSettings, format, clientStats);
                queryResponse.ensureDone();
                future.complete(queryResponse);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                MDC.remove("queryId");
            }
        });
        return future;
    }

    public TableSchema getTableSchema(String table) {
        return getTableSchema(table, "default");
    }

    public TableSchema getTableSchema(String table, String database) {
        try (ClickHouseClient clientQuery = createClient()) {
            ClickHouseRequest request = clientQuery.read(getServerNode());
            // XML - because java has a built-in XML parser. Will consider CSV later.
            request.query("DESCRIBE TABLE " + table + " FORMAT " + ClickHouseFormat.TSKV.name());
            try {
                return new TableSchemaParser().createFromBinaryResponse(clientQuery.execute(request).get(), table, database);
            } catch (Exception e) {
                throw new ClientException("Failed to get table schema", e);
            }
        }
    }

    private ClickHouseClient createClient() {
        ClickHouseConfig clientConfig = new ClickHouseConfig();
        ClickHouseClientBuilder clientV1 = ClickHouseClient.builder()
                .config(clientConfig)
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP));
        return clientV1.build();
    }

    private ClickHouseRequest.Mutation createMutationRequest(ClickHouseRequest.Mutation request, String tableName, InsertSettings settings) {
        if (settings == null) return request.table(tableName);

        if (settings.getQueryId() != null) {//This has to be handled separately
            request.table(tableName, settings.getQueryId());
        } else {
            request.table(tableName);
        }

        //For each setting, set the value in the request
        for (Map.Entry<String, Object> entry : settings.getAllSettings().entrySet()) {
            request.set(entry.getKey(), String.valueOf(entry.getValue()));
        }

        return request;
    }

    private static final Set<String> COMPRESS_ALGORITHMS = ValidationUtils.whiteList("LZ4", "LZ4HC", "ZSTD", "ZSTDHC", "NONE");

    public static Set<String> getCompressAlgorithms() {
        return COMPRESS_ALGORITHMS;
    }

    private static final Set<String> OUTPUT_FORMATS = createFormatWhitelist("output");

    private static final Set<String> INPUT_FORMATS = createFormatWhitelist("input");

    public static Set<String> getOutputFormats() {
        return OUTPUT_FORMATS;
    }

    private static Set<String> createFormatWhitelist(String shouldSupport) {
        Set<String> formats = new HashSet<>();
        boolean supportOutput = "output".equals(shouldSupport);
        boolean supportInput = "input".equals(shouldSupport);
        boolean supportBoth = "both".equals(shouldSupport);
        for (ClickHouseFormat format : ClickHouseFormat.values()) {
            if ((supportOutput && format.supportsOutput()) || (supportInput && format.supportsInput()) || (supportBoth)) {
                formats.add(format.name());
            }
        }
        return Collections.unmodifiableSet(formats);
    }

    private static final String INTERNAL_OPERATION_ID = "operationID";

    private String startOperation() {
        String operationId = UUID.randomUUID().toString();
        globalClientStats.put(operationId, new OperationStatistics.ClientStatistics());
        return operationId;
    }
}
