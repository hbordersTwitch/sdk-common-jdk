package cloud.eppo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import cloud.eppo.api.Configuration;
import cloud.eppo.api.dto.FlagConfigResponse;
import cloud.eppo.http.EppoConfigurationClient;
import cloud.eppo.http.EppoConfigurationRequest;
import cloud.eppo.http.EppoConfigurationRequestFactory;
import cloud.eppo.http.EppoConfigurationResponse;
import cloud.eppo.parser.ConfigurationParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ConfigurationRequestorTest {

  private static final File INITIAL_FLAG_CONFIG_FILE =
      new File("src/test/resources/static/initial-flag-config.json");
  private static final File DIFFERENT_FLAG_CONFIG_FILE =
      new File("src/test/resources/static/boolean-flag.json");

  private static EppoConfigurationRequestFactory createTestRequestFactory() {
    return new EppoConfigurationRequestFactory(
        "https://test.eppo.cloud", "test-api-key", "java", "1.0.0");
  }

  private static byte[] loadInitialFlagConfig() throws IOException {
    return FileUtils.readFileToByteArray(INITIAL_FLAG_CONFIG_FILE);
  }

  private static String loadInitialFlagConfigString() throws IOException {
    return FileUtils.readFileToString(INITIAL_FLAG_CONFIG_FILE, StandardCharsets.UTF_8);
  }

  private static ConfigurationParser<Configuration, Configuration.Builder, JsonNode> configurationParser =
      new JacksonConfigurationParser();

  private Configuration buildConfig(String json) {
    FlagConfigResponse flagConfigResponse = configurationParser.parseFlagConfig(json.getBytes());
    return new Configuration.Builder(flagConfigResponse).build();
  }

  private Configuration buildConfig(byte[] json) {
    FlagConfigResponse flagConfigResponse = configurationParser.parseFlagConfig(json);
    return new Configuration.Builder(flagConfigResponse).build();
  }

  @Nested
  class InitialConfigurationTests {
    private IConfigurationStore<Configuration> configStore;
    private EppoConfigurationClient mockConfigClient;
    private ConfigurationParser<Configuration, Configuration.Builder, JsonNode> parser;
    private ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor;

    @BeforeEach
    void setUp() {
      configStore = Mockito.spy(new ConfigurationStore());
      mockConfigClient = mock(EppoConfigurationClient.class);
      parser = new JacksonConfigurationParser();
      requestor =
          new ConfigurationRequestor<>(
              configStore, true, parser, mockConfigClient, createTestRequestFactory());
    }

    @Test
    void testInitialConfigurationFuture() throws IOException {
      CompletableFuture<Configuration> futureConfig = new CompletableFuture<>();
      byte[] flagConfig = loadInitialFlagConfig();

      requestor.setInitialConfiguration(futureConfig);

      // verify config is empty to start
      assertTrue(configStore.getConfiguration().isEmpty());
      assertEquals(Collections.emptySet(), configStore.getConfiguration().getFlagKeys());
      verify(configStore, times(0)).saveConfiguration(any());

      futureConfig.complete(buildConfig(flagConfig));

      assertFalse(configStore.getConfiguration().isEmpty());
      assertFalse(configStore.getConfiguration().getFlagKeys().isEmpty());
      verify(configStore, times(1)).saveConfiguration(any());
      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));
    }

    @Test
    void testInitialConfigurationDoesntClobberFetch() throws IOException {
      CompletableFuture<Configuration> initialConfigFuture = new CompletableFuture<>();
      String flagConfig = loadInitialFlagConfigString();
      String fetchedFlagConfig =
          FileUtils.readFileToString(DIFFERENT_FLAG_CONFIG_FILE, StandardCharsets.UTF_8);

      // Mock the config client to return a completable future that we control
      CompletableFuture<EppoConfigurationResponse> configFetchFuture = new CompletableFuture<>();
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(configFetchFuture);

      requestor.setInitialConfiguration(initialConfigFuture);

      assertTrue(configStore.getConfiguration().isEmpty());
      assertEquals(Collections.emptySet(), configStore.getConfiguration().getFlagKeys());
      verify(configStore, times(0)).saveConfiguration(any());

      // The initial config contains only one flag keyed `numeric_flag`. The fetch response has only
      // one flag keyed `boolean_flag`. We complete the fetch first to verify cache doesn't
      // overwrite.
      CompletableFuture<Void> handle = requestor.fetchAndSaveFromRemoteAsync();

      configFetchFuture.complete(
          EppoConfigurationResponse.success(
              200, "version-1", fetchedFlagConfig.getBytes(StandardCharsets.UTF_8)));
      initialConfigFuture.complete(buildConfig(flagConfig));

      assertFalse(configStore.getConfiguration().isEmpty());
      assertFalse(configStore.getConfiguration().getFlagKeys().isEmpty());
      verify(configStore, times(1)).saveConfiguration(any());

      // `numeric_flag` is only in the cache which should have been ignored.
      assertNull(configStore.getConfiguration().getFlag("numeric_flag"));
      // `boolean_flag` is available only from the fetch
      assertNotNull(configStore.getConfiguration().getFlag("boolean_flag"));
    }

    @Test
    void testBrokenFetchDoesntClobberCache() throws IOException {
      CompletableFuture<Configuration> initialConfigFuture = new CompletableFuture<>();
      String flagConfig = loadInitialFlagConfigString();

      CompletableFuture<EppoConfigurationResponse> configFetchFuture = new CompletableFuture<>();
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(configFetchFuture);

      requestor.setInitialConfiguration(initialConfigFuture);

      assertTrue(configStore.getConfiguration().isEmpty());
      assertEquals(Collections.emptySet(), configStore.getConfiguration().getFlagKeys());
      verify(configStore, times(0)).saveConfiguration(any());

      requestor.fetchAndSaveFromRemoteAsync();

      initialConfigFuture.complete(buildConfig(flagConfig));
      configFetchFuture.completeExceptionally(new Exception("Intentional exception"));

      assertFalse(configStore.getConfiguration().isEmpty());
      assertFalse(configStore.getConfiguration().getFlagKeys().isEmpty());
      verify(configStore, times(1)).saveConfiguration(any());

      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));
      assertNull(configStore.getConfiguration().getFlag("boolean_flag"));
    }

    @Test
    void testCacheWritesAfterBrokenFetch() throws IOException {
      CompletableFuture<Configuration> initialConfigFuture = new CompletableFuture<>();
      String flagConfig = loadInitialFlagConfigString();

      CompletableFuture<EppoConfigurationResponse> configFetchFuture = new CompletableFuture<>();
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(configFetchFuture);

      requestor.setInitialConfiguration(initialConfigFuture);
      verify(configStore, times(0)).saveConfiguration(any());

      assertTrue(configStore.getConfiguration().isEmpty());
      assertEquals(Collections.emptySet(), configStore.getConfiguration().getFlagKeys());

      requestor.fetchAndSaveFromRemoteAsync();
      configFetchFuture.completeExceptionally(new Exception("Intentional exception"));

      initialConfigFuture.complete(buildConfig(flagConfig));

      verify(configStore, times(1)).saveConfiguration(any());
      assertFalse(configStore.getConfiguration().isEmpty());
      assertFalse(configStore.getConfiguration().getFlagKeys().isEmpty());

      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));
      assertNull(configStore.getConfiguration().getFlag("boolean_flag"));
    }
  }

  @Nested
  class ConfigurationChangeListenerTests {
    private ConfigurationStore mockConfigStore;
    private EppoConfigurationClient mockConfigClient;
    private ConfigurationParser<Configuration, Configuration.Builder, JsonNode> parser;
    private ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor;

    @BeforeEach
    void setUp() {
      mockConfigStore = mock(ConfigurationStore.class);
      mockConfigClient = mock(EppoConfigurationClient.class);
      when(mockConfigStore.getConfiguration()).thenReturn(Configuration.emptyConfig());
      parser = new JacksonConfigurationParser();
      requestor =
          new ConfigurationRequestor<>(
              mockConfigStore, true, parser, mockConfigClient, createTestRequestFactory());
    }

    private void stubConfigClientSuccess(byte[] responseBody) {
      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, "version-1", responseBody);
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));
    }

    private void stubConfigClientFailure() {
      CompletableFuture<EppoConfigurationResponse> failedFuture = new CompletableFuture<>();
      failedFuture.completeExceptionally(new RuntimeException("Fetch failed"));
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class))).thenReturn(failedFuture);
    }

    @Test
    void testConfigurationChangeListener() throws IOException {
      String flagConfig = loadInitialFlagConfigString();
      stubConfigClientSuccess(flagConfig.getBytes());
      when(mockConfigStore.saveConfiguration(any()))
          .thenReturn(CompletableFuture.completedFuture(null));

      List<Configuration> receivedConfigs = new ArrayList<>();
      Runnable unsubscribe = requestor.onConfigurationChange(receivedConfigs::add);

      requestor.fetchAndSaveFromRemote();
      assertEquals(1, receivedConfigs.size());

      requestor.fetchAndSaveFromRemote();
      assertEquals(2, receivedConfigs.size());

      unsubscribe.run();
      requestor.fetchAndSaveFromRemote();
      assertEquals(2, receivedConfigs.size()); // Count should remain the same
    }

    @Test
    void testMultipleConfigurationChangeListeners() {
      stubConfigClientSuccess("{}".getBytes());
      when(mockConfigStore.saveConfiguration(any()))
          .thenReturn(CompletableFuture.completedFuture(null));

      AtomicInteger callCount1 = new AtomicInteger(0);
      AtomicInteger callCount2 = new AtomicInteger(0);

      Runnable unsubscribe1 = requestor.onConfigurationChange(v -> callCount1.incrementAndGet());
      Runnable unsubscribe2 = requestor.onConfigurationChange(v -> callCount2.incrementAndGet());

      requestor.fetchAndSaveFromRemote();
      assertEquals(1, callCount1.get());
      assertEquals(1, callCount2.get());

      unsubscribe1.run();
      requestor.fetchAndSaveFromRemote();
      assertEquals(1, callCount1.get());
      assertEquals(2, callCount2.get());

      unsubscribe2.run();
      requestor.fetchAndSaveFromRemote();
      assertEquals(1, callCount1.get());
      assertEquals(2, callCount2.get());
    }

    @Test
    void testConfigurationChangeListenerIgnoresFailedFetch() {
      stubConfigClientFailure();

      AtomicInteger callCount = new AtomicInteger(0);
      requestor.onConfigurationChange(v -> callCount.incrementAndGet());

      try {
        requestor.fetchAndSaveFromRemote();
      } catch (Exception e) {
        // Expected
      }
      assertEquals(0, callCount.get());
    }

    @Test
    void testConfigurationChangeListenerIgnoresFailedSave() {
      stubConfigClientSuccess("{}".getBytes());
      when(mockConfigStore.saveConfiguration(any()))
          .thenReturn(
              CompletableFuture.supplyAsync(
                  () -> {
                    throw new RuntimeException("Save failed");
                  }));

      AtomicInteger callCount = new AtomicInteger(0);
      requestor.onConfigurationChange(v -> callCount.incrementAndGet());

      try {
        requestor.fetchAndSaveFromRemote();
      } catch (RuntimeException e) {
        // Pass
      }
      assertEquals(0, callCount.get());
    }

    @Test
    void testConfigurationChangeListenerAsyncSave() {
      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, "version-1", "{\"flags\":{}}".getBytes());
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      CompletableFuture<Void> saveFuture = new CompletableFuture<>();
      when(mockConfigStore.saveConfiguration(any())).thenReturn(saveFuture);

      AtomicInteger callCount = new AtomicInteger(0);
      requestor.onConfigurationChange(v -> callCount.incrementAndGet());

      CompletableFuture<Void> fetch = requestor.fetchAndSaveFromRemoteAsync();
      assertEquals(0, callCount.get());

      saveFuture.complete(null);
      fetch.join();
      assertEquals(1, callCount.get());
    }

    @Test
    void testUnsubscribeFromConfigurationChangeByReference() throws IOException {
      String flagConfig = loadInitialFlagConfigString();
      stubConfigClientSuccess(flagConfig.getBytes());
      when(mockConfigStore.saveConfiguration(any()))
          .thenReturn(CompletableFuture.completedFuture(null));

      List<Configuration> receivedConfigs = new ArrayList<>();
      Consumer<Configuration> callback = receivedConfigs::add;

      requestor.onConfigurationChange(callback);

      requestor.fetchAndSaveFromRemote();
      assertEquals(1, receivedConfigs.size());

      boolean removed = requestor.unsubscribeFromConfigurationChange(callback);
      assertTrue(removed);

      requestor.fetchAndSaveFromRemote();
      assertEquals(1, receivedConfigs.size());
    }

    @Test
    void testUnsubscribeNonExistentConfigurationChangeListener() {
      Consumer<Configuration> callback = config -> {};
      boolean removed = requestor.unsubscribeFromConfigurationChange(callback);
      assertFalse(removed);
    }

    @Test
    void testUnsubscribeOneOfMultipleConfigurationChangeListeners() {
      stubConfigClientSuccess("{}".getBytes());
      when(mockConfigStore.saveConfiguration(any()))
          .thenReturn(CompletableFuture.completedFuture(null));

      AtomicInteger callCount1 = new AtomicInteger(0);
      AtomicInteger callCount2 = new AtomicInteger(0);
      AtomicInteger callCount3 = new AtomicInteger(0);

      Consumer<Configuration> callback1 = v -> callCount1.incrementAndGet();
      Consumer<Configuration> callback2 = v -> callCount2.incrementAndGet();
      Consumer<Configuration> callback3 = v -> callCount3.incrementAndGet();

      requestor.onConfigurationChange(callback1);
      requestor.onConfigurationChange(callback2);
      requestor.onConfigurationChange(callback3);

      requestor.fetchAndSaveFromRemote();
      assertEquals(1, callCount1.get());
      assertEquals(1, callCount2.get());
      assertEquals(1, callCount3.get());

      boolean removed = requestor.unsubscribeFromConfigurationChange(callback2);
      assertTrue(removed);

      requestor.fetchAndSaveFromRemote();
      assertEquals(2, callCount1.get());
      assertEquals(1, callCount2.get());
      assertEquals(2, callCount3.get());
    }
  }

  @Nested
  class EppoConfigurationClientTests {
    private IConfigurationStore<Configuration> configStore;
    private EppoConfigurationClient mockConfigClient;
    private ConfigurationParser<Configuration, Configuration.Builder, JsonNode> parser;
    private EppoConfigurationRequestFactory requestFactory;
    private byte[] flagConfigBytes;

    @BeforeEach
    void setUp() throws IOException {
      configStore = Mockito.spy(new ConfigurationStore());
      mockConfigClient = mock(EppoConfigurationClient.class);
      parser = new JacksonConfigurationParser();
      requestFactory = createTestRequestFactory();
      flagConfigBytes = loadInitialFlagConfig();
    }

    private ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> createRequestor() {
      return new ConfigurationRequestor<>(
          configStore, false, parser, mockConfigClient, requestFactory);
    }

    @Test
    void testFetchWithEppoConfigurationClient() {
      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, "version-123", flagConfigBytes);
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor();
      requestor.fetchAndSaveFromRemote();

      verify(mockConfigClient)
          .execute(
              argThat(
                  request ->
                      request.getResourcePath().equals(Constants.FLAG_CONFIG_ENDPOINT)
                          && request.getQueryParams().get("apiKey").equals("test-api-key")
                          && request.getQueryParams().get("sdkName").equals("java")
                          && request.getQueryParams().get("sdkVersion").equals("1.0.0")
                          && request.getLastVersionId() == null));

      assertFalse(configStore.getConfiguration().isEmpty());
      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));
    }

    @Test
    void testFetchWithEppoConfigurationClientTracksVersionId() {
      EppoConfigurationResponse firstResponse =
          EppoConfigurationResponse.success(200, "version-123", flagConfigBytes);
      EppoConfigurationResponse notModifiedResponse =
          EppoConfigurationResponse.notModified("version-123");

      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(firstResponse))
          .thenReturn(CompletableFuture.completedFuture(notModifiedResponse));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor();

      requestor.fetchAndSaveFromRemote();
      verify(mockConfigClient).execute(argThat(request -> request.getLastVersionId() == null));

      requestor.fetchAndSaveFromRemote();
      verify(mockConfigClient)
          .execute(argThat(request -> "version-123".equals(request.getLastVersionId())));

      verify(configStore, times(1)).saveConfiguration(any());
    }

    @Test
    void testFetchWithEppoConfigurationClientHandles304NotModified() {
      EppoConfigurationResponse notModifiedResponse =
          EppoConfigurationResponse.notModified("version-123");
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(notModifiedResponse));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor();
      requestor.fetchAndSaveFromRemote();

      verify(configStore, never()).saveConfiguration(any());
    }

    @Test
    void testFetchAsyncWithEppoConfigurationClient() {
      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, "version-456", flagConfigBytes);
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor();
      requestor.fetchAndSaveFromRemoteAsync().join();

      verify(mockConfigClient).execute(any(EppoConfigurationRequest.class));
      assertFalse(configStore.getConfiguration().isEmpty());
    }

    @Test
    void testFetchWithEppoConfigurationClientErrorResponse() {
      EppoConfigurationResponse errorResponse =
          EppoConfigurationResponse.error(500, "Internal Server Error".getBytes());
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(errorResponse));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor();

      assertThrows(RuntimeException.class, requestor::fetchAndSaveFromRemote);
      verify(configStore, never()).saveConfiguration(any());
    }
  }

  @Nested
  class ConfigurationParserTests {
    private IConfigurationStore<Configuration> configStore;
    private EppoConfigurationClient mockConfigClient;
    private ConfigurationParser<Configuration, Configuration.Builder, JsonNode> mockParser;
    private EppoConfigurationRequestFactory requestFactory;
    private byte[] flagConfigBytes;

    @BeforeEach
    void setUp() throws IOException {
      configStore = Mockito.spy(new ConfigurationStore());
      mockConfigClient = mock(EppoConfigurationClient.class);
      mockParser = mock(ConfigurationParser.class);
      requestFactory = createTestRequestFactory();
      flagConfigBytes = loadInitialFlagConfig();
    }

    private void stubConfigClientSuccess(String versionId) {
      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, versionId, flagConfigBytes);
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));
    }

    private void stubParserSuccess() throws Exception {
      FlagConfigResponse mockFlagConfigResponse = new FlagConfigResponse.Default();
      when(mockParser.parseFlagConfig(flagConfigBytes)).thenReturn(mockFlagConfigResponse);
      Configuration.Builder builder = new Configuration.Builder(mockFlagConfigResponse);
      when(mockParser.configurationBuilder(mockFlagConfigResponse)).thenReturn(builder);
    }

    @Test
    void testFetchWithConfigurationParser() throws Exception {
      stubConfigClientSuccess("version-789");
      stubParserSuccess();

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor =
          new ConfigurationRequestor<>(
              configStore, false, mockParser, mockConfigClient, requestFactory);

      requestor.fetchAndSaveFromRemote();

      verify(mockParser).parseFlagConfig(flagConfigBytes);
      verify(configStore).saveConfiguration(any());
    }

    @Test
    void testFetchAsyncWithConfigurationParser() throws Exception {
      stubConfigClientSuccess("version-async");
      stubParserSuccess();

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor =
          new ConfigurationRequestor<>(
              configStore, false, mockParser, mockConfigClient, requestFactory);

      requestor.fetchAndSaveFromRemoteAsync().join();

      verify(mockParser).parseFlagConfig(flagConfigBytes);
      verify(configStore).saveConfiguration(any());
    }

    @Test
    void testFetchWithConfigurationParserParseError() throws Exception {
      byte[] invalidBytes = "invalid json".getBytes(StandardCharsets.UTF_8);

      EppoConfigurationResponse successResponse =
          EppoConfigurationResponse.success(200, "version-error", invalidBytes);
      when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
          .thenReturn(CompletableFuture.completedFuture(successResponse));

      when(mockParser.parseFlagConfig(invalidBytes))
          .thenThrow(
              new cloud.eppo.parser.ConfigurationParseException("Failed to parse configuration"));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor =
          new ConfigurationRequestor<>(
              configStore, false, mockParser, mockConfigClient, requestFactory);

      assertThrows(RuntimeException.class, requestor::fetchAndSaveFromRemote);
      verify(configStore, never()).saveConfiguration(any());
    }
  }

  @Nested
  class IntegrationTests {
    private okhttp3.mockwebserver.MockWebServer mockServer;
    private OkHttpEppoClient okHttpClient;
    private JacksonConfigurationParser jacksonParser;

    @BeforeEach
    void setUp() throws IOException {
      mockServer = new okhttp3.mockwebserver.MockWebServer();
      mockServer.start();
      okHttpClient = new OkHttpEppoClient();
      jacksonParser = new JacksonConfigurationParser();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws IOException {
      mockServer.shutdown();
    }

    private EppoConfigurationRequestFactory createMockServerRequestFactory() {
      return new EppoConfigurationRequestFactory(
          mockServer.url("/").toString(), "test-api-key", "java-test", "1.0.0");
    }

    private ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> createRequestor(IConfigurationStore<Configuration> configStore) {
      return new ConfigurationRequestor<>(
          configStore, false, jacksonParser, okHttpClient, createMockServerRequestFactory());
    }

    private void enqueueSuccessResponse(String body, String etag) {
      mockServer.enqueue(
          new okhttp3.mockwebserver.MockResponse()
              .setBody(body)
              .setHeader("ETag", etag)
              .setResponseCode(200));
    }

    @Test
    void testFetchWithCommonParserAndClient() throws IOException, InterruptedException {
      IConfigurationStore<Configuration> configStore = new ConfigurationStore();
      String flagConfig = loadInitialFlagConfigString();

      enqueueSuccessResponse(flagConfig, "version-real-1");

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);
      requestor.fetchAndSaveFromRemote();

      assertFalse(configStore.getConfiguration().isEmpty());
      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));

      okhttp3.mockwebserver.RecordedRequest recordedRequest = mockServer.takeRequest();
      assertTrue(recordedRequest.getPath().contains("apiKey=test-api-key"));
      assertTrue(recordedRequest.getPath().contains("sdkName=java-test"));
    }

    @Test
    void testFetchHandles304NotModified() throws IOException, InterruptedException {
      IConfigurationStore<Configuration> configStore = Mockito.spy(new ConfigurationStore());
      String flagConfig = loadInitialFlagConfigString();

      enqueueSuccessResponse(flagConfig, "version-etag-test");
      mockServer.enqueue(
          new okhttp3.mockwebserver.MockResponse()
              .setHeader("ETag", "version-etag-test")
              .setResponseCode(304));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);

      requestor.fetchAndSaveFromRemote();
      verify(configStore, times(1)).saveConfiguration(any());

      requestor.fetchAndSaveFromRemote();
      verify(configStore, times(1)).saveConfiguration(any()); // Still only 1

      mockServer.takeRequest(); // First request
      okhttp3.mockwebserver.RecordedRequest secondRequest = mockServer.takeRequest();
      assertEquals("version-etag-test", secondRequest.getHeader("If-None-Match"));
    }

    @Test
    void testFetchAsync() throws Exception {
      IConfigurationStore<Configuration> configStore = new ConfigurationStore();
      String flagConfig = loadInitialFlagConfigString();

      enqueueSuccessResponse(flagConfig, "version-async-real");

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);
      requestor.fetchAndSaveFromRemoteAsync().join();

      assertFalse(configStore.getConfiguration().isEmpty());
      assertNotNull(configStore.getConfiguration().getFlag("numeric_flag"));
    }

    @Test
    void testFetchHandlesInvalidJson() {
      IConfigurationStore<Configuration> configStore = new ConfigurationStore();

      mockServer.enqueue(
          new okhttp3.mockwebserver.MockResponse()
              .setBody("this is not valid json")
              .setResponseCode(200));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);

      assertThrows(RuntimeException.class, requestor::fetchAndSaveFromRemote);
    }

    @Test
    void testFetchHandlesServerError() {
      IConfigurationStore<Configuration> configStore = new ConfigurationStore();

      mockServer.enqueue(
          new okhttp3.mockwebserver.MockResponse()
              .setBody("Internal Server Error")
              .setResponseCode(500));

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);

      assertThrows(RuntimeException.class, requestor::fetchAndSaveFromRemote);
    }

    @Test
    void testConfigurationChangeListener() throws Exception {
      IConfigurationStore<Configuration> configStore = new ConfigurationStore();
      String flagConfig = loadInitialFlagConfigString();

      enqueueSuccessResponse(flagConfig, "version-callback");

      ConfigurationRequestor<Configuration, Configuration.Builder, JsonNode> requestor = createRequestor(configStore);

      List<Configuration> receivedConfigs = new ArrayList<>();
      requestor.onConfigurationChange(receivedConfigs::add);

      requestor.fetchAndSaveFromRemote();

      assertEquals(1, receivedConfigs.size());
      assertNotNull(receivedConfigs.get(0).getFlag("numeric_flag"));
    }
  }
}
