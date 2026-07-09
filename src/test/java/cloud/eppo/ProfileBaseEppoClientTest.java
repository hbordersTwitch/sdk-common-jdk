package cloud.eppo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.eppo.api.Attributes;
import cloud.eppo.logging.Assignment;
import cloud.eppo.logging.AssignmentLogger;
import cloud.eppo.parser.ConfigurationParser;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProfileBaseEppoClientTest {
  private static final Logger log = LoggerFactory.getLogger(ProfileBaseEppoClientTest.class);

  private static final String DUMMY_FLAG_API_KEY = "dummy-flags-api-key"; // Will load flags-v1
  private static final String TEST_HOST =
      "https://us-central1-eppo-qa.cloudfunctions.net/serveGitHubRacTestFile";

  private static BaseEppoClient eppoClient;
  private static final ConfigurationParser parser = new JacksonConfigurationParser();
  private static final AssignmentLogger noOpAssignmentLogger =
      new AssignmentLogger() {
        @Override
        public void logAssignment(Assignment assignment) {
          /* no-op */
        }
      };

  @BeforeAll
  public static void initClient() {
    eppoClient =
        new BaseEppoClient(
            DUMMY_FLAG_API_KEY,
            "java",
            "3.0.0",
            TEST_HOST,
            noOpAssignmentLogger,
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

    log.info("Test client initialized");
  }

  @Test
  public void testGetStringAssignmentPerformance() {
    Map<String, AtomicInteger> variationCounts = new HashMap<>();
    Attributes subjectAttributes = new Attributes();
    subjectAttributes.put("country", "FR");

    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    long startTime = threadBean.getCurrentThreadCpuTime();

    int numIterations = 10000;

    for (int i = 0; i < numIterations; i++) {
      String subjectKey = "subject" + i;
      String assignedVariation =
          eppoClient.getStringAssignment(
              "new-user-onboarding", subjectKey, subjectAttributes, "default");
      AtomicInteger existingCount =
          variationCounts.computeIfAbsent(assignedVariation, k -> new AtomicInteger(0));
      existingCount.incrementAndGet();
    }
    long endTime = threadBean.getCurrentThreadCpuTime();
    long elapsedTime = endTime - startTime;

    log.info("Assignment counts: {}", variationCounts);
    log.info("CPU Time: {}", elapsedTime);

    // Assert assignments shook out as expected based the shard ranges
    assertEquals(4, variationCounts.keySet().size());
    // Expect ~40% default
    assertEquals(0.40, variationCounts.get("default").doubleValue() / numIterations, 0.02);
    // Expect ~30% control (50% of 60%)
    assertEquals(0.30, variationCounts.get("control").doubleValue() / numIterations, 0.02);
    // Expect ~18% red (30% of 60%)
    assertEquals(0.18, variationCounts.get("red").doubleValue() / numIterations, 0.02);
    // Expect ~12% yellow (20% of 60%)
    assertEquals(0.12, variationCounts.get("yellow").doubleValue() / numIterations, 0.02);

    // Seeing ~48,000,000 - ~54,000,000 for 10k iterations on a M2 Macbook Pro; let's fail if more
    // than 200,000,000; giving a generous allowance for slower systems (like GitHub) but will still
    // catch if things slow down considerably (>4x)
    long maxAllowedTime = 20000 * numIterations;
    assertTrue(
        elapsedTime < maxAllowedTime,
        "Cpu time of " + elapsedTime + " is more than the " + maxAllowedTime + " allowed");
  }
}
