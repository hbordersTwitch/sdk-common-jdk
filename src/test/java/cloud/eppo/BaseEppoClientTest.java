package cloud.eppo;

import static cloud.eppo.helpers.AssignmentTestCase.*;
import static cloud.eppo.helpers.TestUtils.mockConfigurationClient;
import static cloud.eppo.helpers.TestUtils.mockConfigurationClientError;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cloud.eppo.api.*;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.VariationType;
import cloud.eppo.cache.LRUInMemoryAssignmentCache;
import cloud.eppo.helpers.AssignmentTestCase;
import cloud.eppo.http.EppoConfigurationClient;
import cloud.eppo.http.EppoConfigurationRequest;
import cloud.eppo.http.EppoConfigurationResponse;
import cloud.eppo.logging.Assignment;
import cloud.eppo.logging.AssignmentLogger;
import cloud.eppo.parser.ConfigurationParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseEppoClientTest {
  private static final Logger log = LoggerFactory.getLogger(BaseEppoClientTest.class);
  private static final String DUMMY_FLAG_API_KEY = "dummy-flags-api-key"; // Will load flags-v1

  // Use branch if specified by env variable `TEST_DATA_BRANCH`.
  private static final String TEST_BRANCH = System.getenv("TEST_DATA_BRANCH");
  private static final String TEST_API_CLOUD_FUNCTION_URL =
      "https://us-central1-eppo-qa.cloudfunctions.net/serveGitHubRacTestFile";
  private static final String TEST_BASE_URL =
      TEST_API_CLOUD_FUNCTION_URL + (TEST_BRANCH != null ? "/b/" + TEST_BRANCH : "") + "/api";

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(AssignmentTestCase.assignmentTestCaseModule());

  // Use JacksonConfigurationParser for all tests
  private final ConfigurationParser<Configuration, Configuration.Builder, JsonNode> parser = new JacksonConfigurationParser();

  private BaseEppoClient<Configuration, Configuration.Builder, JsonNode> eppoClient;
  private AssignmentLogger mockAssignmentLogger;
  private EppoConfigurationClient mockConfigClient;

  private final File initialFlagConfigFile =
      new File("src/test/resources/static/initial-flag-config.json");

  private final File flagConfigFile = new File("src/test/resources/shared/ufc/flags-v1.json");

  // TODO: async init client tests

  private void initClient() {
    initClient(false, false);
  }

  private void initClientWithData(
      final CompletableFuture<Configuration> initialFlagConfiguration,
      boolean isConfigObfuscated,
      boolean isGracefulMode) {
    mockAssignmentLogger = mock(AssignmentLogger.class);

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            isConfigObfuscated ? "android" : "java",
            "100.1.0",
            TEST_BASE_URL,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            isGracefulMode,
            isConfigObfuscated,
            true,
            initialFlagConfiguration,
            null,
            null,
            parser,
            mockConfigClient);
  }

  private void initClient(boolean isGracefulMode, boolean isConfigObfuscated) {
    mockAssignmentLogger = mock(AssignmentLogger.class);

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            isConfigObfuscated ? "android" : "java",
            "100.1.0",
            TEST_BASE_URL,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            isGracefulMode,
            isConfigObfuscated,
            true,
            null,
            null,
            null,
            parser,
            mockConfigClient);

    eppoClient.loadConfiguration();
    log.info("Test client initialized");
  }

  private CompletableFuture<Void> initClientAsync(
      boolean isGracefulMode, boolean isConfigObfuscated) {
    mockAssignmentLogger = mock(AssignmentLogger.class);

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            isConfigObfuscated ? "android" : "java",
            "100.1.0",
            TEST_BASE_URL,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            isGracefulMode,
            isConfigObfuscated,
            true,
            null,
            null,
            null,
            parser,
            mockConfigClient);

    return eppoClient.loadConfigurationAsync();
  }

  private void initClientWithAssignmentCache(IAssignmentCache cache) {
    mockAssignmentLogger = mock(AssignmentLogger.class);

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            "java",
            "100.1.0",
            TEST_BASE_URL,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            true,
            false,
            true,
            null,
            cache,
            null,
            parser,
            mockConfigClient);

    eppoClient.loadConfiguration();
    log.info("Test client initialized");
  }

  @BeforeEach
  public void setUp() {
    // Use real OkHttpEppoClient by default for integration tests that fetch real test data
    // Individual tests can override with a mock if needed
    mockConfigClient = new OkHttpEppoClient();
  }

  @ParameterizedTest
  @MethodSource("getAssignmentTestData")
  public void testUnobfuscatedAssignments(File testFile) {
    initClient(false, false);
    AssignmentTestCase testCase = parseTestCaseFile(testFile);
    runTestCase(testCase, eppoClient);
  }

  @ParameterizedTest
  @MethodSource("getAssignmentTestData")
  public void testObfuscatedAssignments(File testFile) {
    initClient(false, true);
    AssignmentTestCase testCase = parseTestCaseFile(testFile);
    runTestCase(testCase, eppoClient);
  }

  @ParameterizedTest
  @MethodSource("getAssignmentTestData")
  public void testUnobfuscatedAssignmentsWithDetails(File testFile) {
    initClient(false, false);
    AssignmentTestCase testCase = parseTestCaseFile(testFile);
    runTestCaseWithDetails(testCase, eppoClient);
  }

  @ParameterizedTest
  @MethodSource("getAssignmentTestData")
  public void testObfuscatedAssignmentsWithDetails(File testFile) {
    initClient(false, true);
    AssignmentTestCase testCase = parseTestCaseFile(testFile);
    runTestCaseWithDetails(testCase, eppoClient);
  }

  @ParameterizedTest
  @MethodSource("getAssignmentTestData")
  public void testSerializedOfflineInitialization(File testFile) throws IOException {
    initClientWithSerializedConfiguration();
    AssignmentTestCase testCase = parseTestCaseFile(testFile);
    runTestCase(testCase, eppoClient);
  }

  private void initClientWithSerializedConfiguration() throws IOException {
    byte[] rawConfiguration = FileUtils.readFileToByteArray(flagConfigFile);
    Configuration configuration =
        Configuration.builder(parser.parseFlagConfig(rawConfiguration)).build();
    byte[] serializedConfiguration;
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos)) {
      oos.writeObject(configuration);
      serializedConfiguration = bos.toByteArray();
    }

    Configuration deserializedConfiguration;
    try (ByteArrayInputStream bais = new ByteArrayInputStream(serializedConfiguration);
        ObjectInputStream ois = new ObjectInputStream(bais)) {
      deserializedConfiguration = (Configuration) ois.readObject();
    } catch (IOException | ClassNotFoundException e) {
      throw new RuntimeException("Failed to deserialize configuration", e);
    }

    initClientWithData(CompletableFuture.completedFuture(deserializedConfiguration), false, false);
  }

  private static Stream<Arguments> getAssignmentTestData() {
    return AssignmentTestCase.getAssignmentTestData();
  }

  @Test
  public void testCustomBaseUrl() throws IOException, InterruptedException {
    // Base client must be buildable with a custom apiBaseUrl
    mockAssignmentLogger = mock(AssignmentLogger.class);

    MockWebServer mockWebServer = new MockWebServer();
    URL mockServerBaseUrl = mockWebServer.url("").url(); // get base url of mockwebserver

    // Remove trailing slash to mimic typical apiBaseUrl parameter
    String testBaseUrl = mockServerBaseUrl.toString().replaceAll("/$", "");

    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            "java",
            "100.1.0",
            testBaseUrl,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            false,
            false,
            true,
            null,
            null,
            null,
            parser,
            new OkHttpEppoClient());

    eppoClient.loadConfiguration();

    // Test what path the call was sent to
    RecordedRequest request = mockWebServer.takeRequest();
    assertNotNull(request);
    assertEquals("GET", request.getMethod());

    // The apiBaseUrl is used directly without appending "/api"
    assertEquals(
        "/flag-config/v1/config?apiKey=dummy-flags-api-key&sdkName=java&sdkVersion=100.1.0",
        request.getPath());
  }

  @Test
  public void testErrorGracefulModeOn() throws JsonProcessingException {
    initClient(true, false);

    BaseEppoClient<Configuration, Configuration.Builder, JsonNode> realClient = eppoClient;
    BaseEppoClient<Configuration, Configuration.Builder, JsonNode> spyClient = spy(realClient);
    doThrow(new RuntimeException("Exception thrown by mock"))
        .when(spyClient)
        .evaluateAndMaybeLog(
            anyString(), anyString(), any(Attributes.class), any(VariationType.class));

    assertTrue(spyClient.getBooleanAssignment("experiment1", "subject1", true));
    assertFalse(spyClient.getBooleanAssignment("experiment1", "subject1", new Attributes(), false));

    assertEquals(10, spyClient.getIntegerAssignment("experiment1", "subject1", 10));
    assertEquals(0, spyClient.getIntegerAssignment("experiment1", "subject1", new Attributes(), 0));

    assertEquals(1.2345, spyClient.getDoubleAssignment("experiment1", "subject1", 1.2345), 0.0001);
    assertEquals(
        0.0,
        spyClient.getDoubleAssignment("experiment1", "subject1", new Attributes(), 0.0),
        0.0001);

    assertEquals("default", spyClient.getStringAssignment("experiment1", "subject1", "default"));
    assertEquals(
        "", spyClient.getStringAssignment("experiment1", "subject1", new Attributes(), ""));

    assertEquals(
        mapper.readTree("{\"a\": 1, \"b\": false}").toString(),
        spyClient
            .getJSONAssignment(
                "subject1", "experiment1", mapper.readTree("{\"a\": 1, \"b\": false}"))
            .toString());

    assertEquals(
        "{\"a\": 1, \"b\": false}",
        spyClient.getJSONStringAssignment("subject1", "experiment1", "{\"a\": 1, \"b\": false}"));

    assertEquals(
        mapper.readTree("{}").toString(),
        spyClient
            .getJSONAssignment("subject1", "experiment1", new Attributes(), mapper.readTree("{}"))
            .toString());
  }

  @Test
  public void testErrorGracefulModeOff() {
    initClient(false, false);

    BaseEppoClient<Configuration, Configuration.Builder, JsonNode> realClient = eppoClient;
    BaseEppoClient<Configuration, Configuration.Builder, JsonNode> spyClient = spy(realClient);
    doThrow(new RuntimeException("Exception thrown by mock"))
        .when(spyClient)
        .evaluateAndMaybeLog(
            anyString(), anyString(), any(Attributes.class), any(VariationType.class));

    assertThrows(
        RuntimeException.class,
        () -> spyClient.getBooleanAssignment("experiment1", "subject1", true));
    assertThrows(
        RuntimeException.class,
        () -> spyClient.getBooleanAssignment("experiment1", "subject1", new Attributes(), false));

    assertThrows(
        RuntimeException.class,
        () -> spyClient.getIntegerAssignment("experiment1", "subject1", 10));
    assertThrows(
        RuntimeException.class,
        () -> spyClient.getIntegerAssignment("experiment1", "subject1", new Attributes(), 0));

    assertThrows(
        RuntimeException.class,
        () -> spyClient.getDoubleAssignment("experiment1", "subject1", 1.2345));
    assertThrows(
        RuntimeException.class,
        () -> spyClient.getDoubleAssignment("experiment1", "subject1", new Attributes(), 0.0));

    assertThrows(
        RuntimeException.class,
        () -> spyClient.getStringAssignment("experiment1", "subject1", "default"));
    assertThrows(
        RuntimeException.class,
        () -> spyClient.getStringAssignment("experiment1", "subject1", new Attributes(), ""));

    assertThrows(
        RuntimeException.class,
        () ->
            spyClient.getJSONAssignment(
                "subject1", "experiment1", mapper.readTree("{\"a\": 1, \"b\": false}")));
    assertThrows(
        RuntimeException.class,
        () ->
            spyClient.getJSONAssignment(
                "subject1", "experiment1", new Attributes(), mapper.readTree("{}")));
  }

  @Test
  public void testInvalidConfigJSON() {
    mockConfigClient = mockConfigurationClient("{}");

    initClient(false, false);

    String result = eppoClient.getStringAssignment("dummy flag", "dummy subject", "not-populated");
    assertEquals("not-populated", result);
  }

  private CompletableFuture<Configuration> immediateConfigFuture(
      String config, boolean isObfuscated) {
    return CompletableFuture.completedFuture(
        Configuration.builder(parser.parseFlagConfig(config.getBytes())).build());
  }

  @Test
  public void testGracefulInitializationFailure() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize and no exception should be thrown.
    assertDoesNotThrow(() -> initClient(true, false));
  }

  @Test
  public void testClientMakesDefaultAssignmentsAfterFailingToInitialize() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize and no exception should be thrown.
    assertDoesNotThrow(() -> initClient(true, false));

    assertEquals("default", eppoClient.getStringAssignment("experiment1", "subject1", "default"));
  }

  @Test
  public void testClientMakesDefaultAssignmentsAfterFailingToInitializeNonGracefulMode() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize and no exception should be thrown.
    try {
      initClient(false, false);
    } catch (RuntimeException e) {
      // Expected
      assertTrue(e.getMessage().contains("Intentional Error"));
    } finally {
      assertEquals("default", eppoClient.getStringAssignment("experiment1", "subject1", "default"));
    }
  }

  @Test
  public void testNonGracefulInitializationFailure() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize and assert exception thrown
    assertThrows(Exception.class, () -> initClient(false, false));
  }

  @Test
  public void testGracefulAsyncInitializationFailure() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize
    CompletableFuture<Void> init = initClientAsync(true, false);

    // Wait for initialization; future should not complete exceptionally (equivalent of exception
    // being thrown).
    init.join();
    assertFalse(init.isCompletedExceptionally());
  }

  @Test
  public void testNonGracefulAsyncInitializationFailure() {
    // Set up failing configuration client
    mockConfigClient = mockConfigurationClientError();

    // Initialize
    CompletableFuture<Void> init = initClientAsync(false, false);

    // Exceptions thrown in CompletableFutures are wrapped in a CompletionException.
    assertThrows(CompletionException.class, init::join);
    assertTrue(init.isCompletedExceptionally());
  }

  @Test
  public void testWithInitialConfiguration() {
    try {
      String flagConfig = FileUtils.readFileToString(initialFlagConfigFile, "UTF8");

      initClientWithData(immediateConfigFuture(flagConfig, false), false, true);

      double result = eppoClient.getDoubleAssignment("numeric_flag", "dummy subject", 0);
      assertEquals(5, result);

      // Demonstrate that loaded configuration is different from the initial string passed above.
      eppoClient.loadConfiguration();
      double updatedResult = eppoClient.getDoubleAssignment("numeric_flag", "dummy subject", 0);
      assertEquals(3.1415926, updatedResult);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testWithInitialConfigurationFuture() throws IOException {
    CompletableFuture<Configuration> futureConfig = new CompletableFuture<>();
    byte[] flagConfig = FileUtils.readFileToByteArray(initialFlagConfigFile);

    initClientWithData(futureConfig, false, true);

    double result = eppoClient.getDoubleAssignment("numeric_flag", "dummy subject", 0);
    assertEquals(0, result);

    // Now, complete the initial config future and check the value.
    futureConfig.complete(Configuration.builder(parser.parseFlagConfig(flagConfig)).build());

    result = eppoClient.getDoubleAssignment("numeric_flag", "dummy subject", 0);
    assertEquals(5, result);
  }

  @Test
  public void testAssignmentEventCorrectlyCreated() {
    Date testStart = new Date();
    initClient();
    Attributes subjectAttributes = new Attributes();
    subjectAttributes.put("age", EppoValue.valueOf(30));
    subjectAttributes.put("employer", EppoValue.valueOf("Eppo"));
    double assignment =
        eppoClient.getDoubleAssignment("numeric_flag", "alice", subjectAttributes, 0.0);

    assertEquals(3.1415926, assignment, 0.0000001);

    ArgumentCaptor<Assignment> assignmentLogCaptor = ArgumentCaptor.forClass(Assignment.class);
    verify(mockAssignmentLogger, times(1)).logAssignment(assignmentLogCaptor.capture());
    Assignment capturedAssignment = assignmentLogCaptor.getValue();
    assertEquals("numeric_flag-rollout", capturedAssignment.getExperiment());
    assertEquals("numeric_flag", capturedAssignment.getFeatureFlag());
    assertEquals("rollout", capturedAssignment.getAllocation());
    assertEquals(
        "pi",
        capturedAssignment
            .getVariation()); // Note: unlike this test, typically variation keys will just be the
    // value for everything not JSON
    assertEquals("alice", capturedAssignment.getSubject());
    assertEquals(subjectAttributes, capturedAssignment.getSubjectAttributes());
    assertEquals(new HashMap<>(), capturedAssignment.getExtraLogging());
    assertTrue(capturedAssignment.getTimestamp().after(testStart));
    Date inTheNearFuture = new Date(System.currentTimeMillis() + 1);
    assertTrue(capturedAssignment.getTimestamp().before(inTheNearFuture));

    Map<String, String> expectedMeta = new HashMap<>();
    expectedMeta.put("obfuscated", "false");
    expectedMeta.put("sdkLanguage", "java");
    expectedMeta.put("sdkLibVersion", "100.1.0");

    assertEquals(expectedMeta, capturedAssignment.getMetaData());
  }

  @Test
  public void testAssignmentNotDeduplicatedWithoutCache() {
    initClient();

    Attributes subjectAttributes = new Attributes();
    subjectAttributes.put("age", EppoValue.valueOf(30));
    subjectAttributes.put("employer", EppoValue.valueOf("Eppo"));

    // Get the assignment twice
    eppoClient.getDoubleAssignment("numeric_flag", "alice", subjectAttributes, 0.0);
    eppoClient.getDoubleAssignment("numeric_flag", "alice", subjectAttributes, 0.0);

    ArgumentCaptor<Assignment> assignmentLogCaptor = ArgumentCaptor.forClass(Assignment.class);

    // `logAssignment` should be called twice;
    verify(mockAssignmentLogger, times(2)).logAssignment(assignmentLogCaptor.capture());
  }

  @Test
  public void testAssignmentEventCorrectlyDeduplicated() {
    initClientWithAssignmentCache(new LRUInMemoryAssignmentCache(1024));

    Attributes subjectAttributes = new Attributes();
    subjectAttributes.put("number", EppoValue.valueOf("123456789"));

    // Get the assignment twice
    int assignment =
        eppoClient.getIntegerAssignment("numeric-one-of", "alice", subjectAttributes, 0);
    eppoClient.getIntegerAssignment("numeric-one-of", "alice", subjectAttributes, 0);

    // `2` matches the attribute `number` value of "123456789"
    assertEquals(2, assignment);

    // `logAssignment` should be called only once.
    verify(mockAssignmentLogger, times(1)).logAssignment(any(Assignment.class));

    // Now, change the assigned value to get a logged entry. `number="1"` will map to the assignment
    // of `1`.
    subjectAttributes.put("number", EppoValue.valueOf("1"));

    // Get the assignment
    int newAssignment =
        eppoClient.getIntegerAssignment("numeric-one-of", "alice", subjectAttributes, 0);
    assertEquals(1, newAssignment);

    // Verify a new log call
    verify(mockAssignmentLogger, times(2)).logAssignment(any(Assignment.class));

    // Change back to the original variation to ensure it is not still cached after the previous
    // value evicted it.
    subjectAttributes.put("number", EppoValue.valueOf("123456789"));

    // Get the assignment
    int oldAssignment =
        eppoClient.getIntegerAssignment("numeric-one-of", "alice", subjectAttributes, 0);
    assertEquals(2, oldAssignment);

    // Verify a new log call
    verify(mockAssignmentLogger, times(3)).logAssignment(any(Assignment.class));
  }

  @Test
  public void testAssignmentEventCorrectlyDeduplicatedFromBackgroundThreads() {
    initClientWithAssignmentCache(new LRUInMemoryAssignmentCache(1024));

    Attributes subjectAttributes = new Attributes();
    subjectAttributes.put("number", EppoValue.valueOf("123456789"));

    int numThreads = 10;
    final CountDownLatch threadStartCountDownLatch = new CountDownLatch(numThreads);
    final CountDownLatch getAssignmentStartCountDownLatch = new CountDownLatch(1);
    final List<Integer> assignments =
        Collections.synchronizedList(Arrays.asList(new Integer[numThreads]));
    ExecutorService pool =
        Executors.newFixedThreadPool(
            numThreads,
            new ThreadFactory() {
              private final AtomicInteger threadIndexAtomicInteger = new AtomicInteger(0);

              @Override
              public Thread newThread(@NotNull Runnable runnable) {
                final int threadIndex = threadIndexAtomicInteger.getAndIncrement();
                return new Thread(
                    runnable,
                    "testAssignmentEventCorrectlyDeduplicatedFromBackgroundThreads-" + threadIndex);
              }
            });
    try {
      for (int i = 0; i < numThreads; i += 1) {
        final int threadIndex = i;
        pool.execute(
            () -> {
              threadStartCountDownLatch.countDown();
              boolean shouldStart;
              try {
                shouldStart = getAssignmentStartCountDownLatch.await(1000, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                shouldStart = false;
              }
              final Integer assignment;
              if (shouldStart) {
                assignment =
                    eppoClient.getIntegerAssignment(
                        "numeric-one-of", "alice", subjectAttributes, 0);
              } else {
                assignment = null;
              }

              assignments.set(threadIndex, assignment);
            });
      }

      boolean shouldStart;
      try {
        shouldStart = threadStartCountDownLatch.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        shouldStart = false;
      }

      assertTrue(shouldStart, "All worker threads did not start");
      getAssignmentStartCountDownLatch.countDown();
    } finally {
      pool.shutdown();
      try {
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
          pool.shutdownNow();
        }
      } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    final List<Integer> expectedAssignments;
    {
      final Integer[] expectedAssignmentsArray = new Integer[numThreads];
      // `2` matches the attribute `number` value of "123456789"
      Arrays.fill(expectedAssignmentsArray, 2);
      expectedAssignments = Arrays.asList(expectedAssignmentsArray);
    }
    assertEquals(expectedAssignments, assignments);

    // `logAssignment` should be called only once.
    verify(mockAssignmentLogger, times(1)).logAssignment(any(Assignment.class));
  }

  @Test
  public void testAssignmentLogErrorNonFatal() {
    initClient();
    doThrow(new RuntimeException("Mock Assignment Logging Error"))
        .when(mockAssignmentLogger)
        .logAssignment(any());
    AssignmentDetails<Double> assignmentDetails =
        eppoClient.getDoubleAssignmentDetails("numeric_flag", "alice", new Attributes(), 0.0);

    assertEquals(3.1415926, assignmentDetails.getVariation(), 0.0000001);

    // Verify evaluation details are populated correctly
    EvaluationDetails details = assignmentDetails.getEvaluationDetails();
    assertNotNull(details);
    assertEquals(FlagEvaluationCode.MATCH, details.getFlagEvaluationCode());
    assertNotNull(details.getEnvironmentName());
    assertEquals("Test", details.getEnvironmentName());

    // Verify config timestamps
    assertNotNull(details.getConfigPublishedAt());
    assertNotNull(details.getConfigFetchedAt());
    // Published at should be Wed Apr 17 15:40:53 EDT 2024 (from test JSON)
    Date expectedPublishedAt =
        new Date(1713382853716L); // 2024-04-17T19:40:53.716Z; matches flags-v1.json
    assertEquals(expectedPublishedAt, details.getConfigPublishedAt());
    // Fetched at should be after published at (it's set when config is built)
    assertTrue(
        details.getConfigFetchedAt().after(details.getConfigPublishedAt()),
        "Config fetched at should be after config published at");

    ArgumentCaptor<Assignment> assignmentLogCaptor = ArgumentCaptor.forClass(Assignment.class);
    verify(mockAssignmentLogger, times(1)).logAssignment(assignmentLogCaptor.capture());
  }

  @Test
  public void testGracefulPolling() {
    Timer pollTimer = new Timer();
    final AtomicInteger callCount = new AtomicInteger(0);
    Runnable runnerTask =
        new Runnable() {
          @Override
          public void run() {
            callCount.incrementAndGet();
          }
        };

    FetchConfigurationTask task = new FetchConfigurationTask(runnerTask, pollTimer, 50, 5);

    // Trigger an unexpected state; the timer is cancelled but the FetchConfigurationTask attempts
    // to schedule a runnable.
    pollTimer.cancel();
    task.scheduleNext();

    sleepUninterruptedly(50);

    // If the Timer has been cancelled, FetchConfigurationTask doesn't  attempt to reschedule.
    assertEquals(0, callCount.get());

    // No exception to be thrown if illegal timer state is properly caught.
  }

  @Test
  public void testPolling() {
    mockConfigClient = mockConfigurationClient(BOOL_FLAG_CONFIG);

    mockAssignmentLogger = mock(AssignmentLogger.class);
    BaseEppoClient<Configuration, Configuration.Builder, JsonNode> client =
        eppoClient =
            new BaseEppoClient<>(
                DUMMY_FLAG_API_KEY,
                "java",
                "100.1.0",
                TEST_BASE_URL,
                mockAssignmentLogger,
                null,
                new ConfigurationStore(),
                false,
                false,
                true,
                null,
                null,
                null,
                parser,
                mockConfigClient);

    client.loadConfiguration();
    client.startPolling(20);

    // Method will be called immediately on init
    verify(mockConfigClient, times(1)).execute(any(EppoConfigurationRequest.class));
    assertTrue(eppoClient.getBooleanAssignment("bool_flag", "subject1", false));

    // Sleep for 25 ms to allow another polling cycle to complete
    sleepUninterruptedly(25);

    // Now, the method should have been called twice
    verify(mockConfigClient, times(2)).execute(any(EppoConfigurationRequest.class));

    eppoClient.stopPolling();
    assertTrue(eppoClient.getBooleanAssignment("bool_flag", "subject1", false));

    sleepUninterruptedly(25);

    // No more calls since stopped
    verify(mockConfigClient, times(2)).execute(any(EppoConfigurationRequest.class));

    // Set up a different config to be served
    EppoConfigurationResponse disabledResponse =
        EppoConfigurationResponse.success(
            HttpURLConnection.HTTP_OK, "v2", DISABLED_BOOL_FLAG_CONFIG.getBytes());
    when(mockConfigClient.execute(any(EppoConfigurationRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(disabledResponse));
    client.startPolling(20);

    // True until the next config is fetched.
    assertTrue(eppoClient.getBooleanAssignment("bool_flag", "subject1", false));

    sleepUninterruptedly(50);

    assertFalse(eppoClient.getBooleanAssignment("bool_flag", "subject1", false));

    eppoClient.stopPolling();
  }

  @Test
  public void testGetConfiguration() {
    // Initialize client with default settings
    initClient();

    // Get configuration
    Configuration config = eppoClient.getConfiguration();

    // Verify configuration is not null
    assertNotNull(config);

    // Verify some known flags from the test configuration
    assertNotNull(config.getFlag("numeric_flag"));
    assertEquals(VariationType.NUMERIC, config.getFlagType("numeric_flag"));

    // Verify a non-existent flag returns null
    assertNull(config.getFlag("non_existent_flag"));
  }

  @Test
  public void testGetConfigurationWithInitialConfig() {
    try {
      // Load initial configuration from file
      String flagConfig = FileUtils.readFileToString(initialFlagConfigFile, "UTF8");

      // Initialize client with initial configuration
      initClientWithData(immediateConfigFuture(flagConfig, false), false, true);

      // Get configuration
      Configuration config = eppoClient.getConfiguration();

      // Verify configuration is not null
      assertNotNull(config);

      // Verify known flag from initial configuration
      FlagConfig numericFlag = config.getFlag("numeric_flag");
      assertNotNull(numericFlag);
      assertEquals(VariationType.NUMERIC, numericFlag.getVariationType());

      // verify `no_allocations_flag` DNE
      assertNull(config.getFlag("no_allocations_flag"));

      // Load new configuration
      eppoClient.loadConfiguration();

      // Get updated configuration
      Configuration updatedConfig = eppoClient.getConfiguration();

      // Verify numeric_flag is still there.
      assertNotNull(updatedConfig.getFlag("numeric_flag"));
      // verify `no_allocations_flag` exists now
      assertNotNull(updatedConfig.getFlag("no_allocations_flag"));

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testGetConfigurationBeforeInitialization() {
    // Create client without loading configuration
    mockAssignmentLogger = mock(AssignmentLogger.class);

    eppoClient =
        new BaseEppoClient<>(
            DUMMY_FLAG_API_KEY,
            "java",
            "100.1.0",
            TEST_BASE_URL,
            mockAssignmentLogger,
            null,
            new ConfigurationStore(),
            false,
            false,
            true,
            null,
            null,
            null,
            parser,
            new OkHttpEppoClient());

    // Get configuration before loading
    Configuration config = eppoClient.getConfiguration();

    // Verify we get an empty configuration
    assertNotNull(config);
    assertTrue(config.isEmpty());
    assertEquals(Collections.emptySet(), config.getFlagKeys());

    eppoClient.loadConfiguration();

    // Get configuration again after loading
    Configuration nextConfig = eppoClient.getConfiguration();

    // Verify we get an empty configuration
    assertNotNull(nextConfig);
    assertFalse(nextConfig.isEmpty());
    assertFalse(nextConfig.getFlagKeys().isEmpty());
  }

  @SuppressWarnings("SameParameterValue")
  private void sleepUninterruptedly(long sleepMs) {
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static final String BOOL_FLAG_CONFIG =
      ("{\n"
          + "  \"createdAt\": \"2024-04-17T19:40:53.716Z\",\n"
          + "  \"format\": \"SERVER\",\n"
          + "  \"environment\": {\n"
          + "    \"name\": \"Test\"\n"
          + "  },\n"
          + "  \"flags\": {\n"
          + "    \"bool_flag\": {\n"
          + "      \"key\": \"bool_flag\",\n"
          + "      \"enabled\": true,\n"
          + "      \"variationType\": \"BOOLEAN\",\n"
          + "      \"variations\": {\n"
          + "        \"on\": {\n"
          + "          \"key\": \"on\",\n"
          + "          \"value\": true\n"
          + "        }\n"
          + "      },\n"
          + "      \"allocations\": [\n"
          + "        {\n"
          + "          \"key\": \"on\",\n"
          + "          \"doLog\": true,\n"
          + "          \"splits\": [\n"
          + "            {\n"
          + "              \"variationKey\": \"on\",\n"
          + "              \"shards\": []\n"
          + "            }\n"
          + "          ]\n"
          + "        }\n"
          + "      ],\n"
          + "      \"totalShards\": 10000\n"
          + "    }\n"
          + "  }\n"
          + "}");
  private static final String DISABLED_BOOL_FLAG_CONFIG =
      ("{\n"
          + "  \"createdAt\": \"2024-04-17T19:40:53.716Z\",\n"
          + "  \"format\": \"SERVER\",\n"
          + "  \"environment\": {\n"
          + "    \"name\": \"Test\"\n"
          + "  },\n"
          + "  \"flags\": {\n"
          + "    \"bool_flag\": {\n"
          + "      \"key\": \"bool_flag\",\n"
          + "      \"enabled\": false,\n"
          + "      \"variationType\": \"BOOLEAN\",\n"
          + "      \"variations\": {\n"
          + "        \"on\": {\n"
          + "          \"key\": \"on\",\n"
          + "          \"value\": true\n"
          + "        }\n"
          + "      },\n"
          + "      \"allocations\": [\n"
          + "        {\n"
          + "          \"key\": \"on\",\n"
          + "          \"doLog\": true,\n"
          + "          \"splits\": [\n"
          + "            {\n"
          + "              \"variationKey\": \"on\",\n"
          + "              \"shards\": []\n"
          + "            }\n"
          + "          ]\n"
          + "        }\n"
          + "      ],\n"
          + "      \"totalShards\": 10000\n"
          + "    }\n"
          + "  }\n"
          + "}");
}
