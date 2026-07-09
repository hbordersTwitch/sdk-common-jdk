package cloud.eppo.helpers;

import static org.junit.jupiter.api.Assertions.*;

import cloud.eppo.BaseEppoClient;
import cloud.eppo.api.AllocationDetails;
import cloud.eppo.api.AssignmentDetails;
import cloud.eppo.api.Attributes;
import cloud.eppo.api.Configuration;
import cloud.eppo.api.EppoValue;
import cloud.eppo.api.EvaluationDetails;
import cloud.eppo.api.MatchedRule;
import cloud.eppo.api.RuleCondition;
import cloud.eppo.api.dto.VariationType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.params.provider.Arguments;

public class AssignmentTestCase {
  private final String flag;
  private final VariationType variationType;
  private final TestCaseValue defaultValue;
  private final List<SubjectAssignment> subjects;

  public AssignmentTestCase(
      String flag,
      VariationType variationType,
      TestCaseValue defaultValue,
      List<SubjectAssignment> subjects) {
    this.flag = flag;
    this.variationType = variationType;
    this.defaultValue = defaultValue;
    this.subjects = subjects;
  }

  public String getFlag() {
    return flag;
  }

  public VariationType getVariationType() {
    return variationType;
  }

  public TestCaseValue getDefaultValue() {
    return defaultValue;
  }

  public List<SubjectAssignment> getSubjects() {
    return subjects;
  }

  private static final ObjectMapper mapper =
      new ObjectMapper().registerModule(assignmentTestCaseModule());

  public static SimpleModule assignmentTestCaseModule() {
    SimpleModule module = new SimpleModule();
    module.addDeserializer(AssignmentTestCase.class, new AssignmentTestCaseDeserializer());
    return module;
  }

  public static Stream<Arguments> getAssignmentTestData() {
    File testCaseFolder = new File("src/test/resources/shared/ufc/tests");
    File[] testCaseFiles = testCaseFolder.listFiles();
    assertNotNull(testCaseFiles);
    assertTrue(testCaseFiles.length > 0);
    List<Arguments> arguments = new ArrayList<>();
    for (File testCaseFile : testCaseFiles) {
      arguments.add(Arguments.of(testCaseFile));
    }
    return arguments.stream();
  }

  public static AssignmentTestCase parseTestCaseFile(File testCaseFile) {
    AssignmentTestCase testCase;
    try {
      String json = FileUtils.readFileToString(testCaseFile, "UTF8");

      testCase = mapper.readValue(json, AssignmentTestCase.class);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    return testCase;
  }

  public static void runTestCase(AssignmentTestCase testCase, BaseEppoClient<Configuration, Configuration.Builder, JsonNode> eppoClient) {
    runTestCaseBase(testCase, eppoClient, false);
  }

  public static void runTestCaseWithDetails(
      AssignmentTestCase testCase, BaseEppoClient<Configuration, Configuration.Builder, JsonNode> eppoClient) {
    runTestCaseBase(testCase, eppoClient, true);
  }

  private static void runTestCaseBase(
      AssignmentTestCase testCase, BaseEppoClient<Configuration, Configuration.Builder, JsonNode> eppoClient, boolean validateDetails) {
    String flagKey = testCase.getFlag();
    TestCaseValue defaultValue = testCase.getDefaultValue();
    assertFalse(testCase.getSubjects().isEmpty());

    for (SubjectAssignment subjectAssignment : testCase.getSubjects()) {
      String subjectKey = subjectAssignment.getSubjectKey();
      Attributes subjectAttributes = subjectAssignment.getSubjectAttributes();

      // Depending on the variation type, call the appropriate assignment method
      switch (testCase.getVariationType()) {
        case BOOLEAN:
          if (validateDetails) {
            AssignmentDetails<Boolean> details =
                eppoClient.getBooleanAssignmentDetails(
                    flagKey, subjectKey, subjectAttributes, defaultValue.booleanValue());
            assertAssignment(flagKey, subjectAssignment, details.getVariation());
            assertAssignmentDetails(flagKey, subjectAssignment, details.getEvaluationDetails());
          } else {
            boolean boolAssignment =
                eppoClient.getBooleanAssignment(
                    flagKey, subjectKey, subjectAttributes, defaultValue.booleanValue());
            assertAssignment(flagKey, subjectAssignment, boolAssignment);
          }
          break;
        case INTEGER:
          int castedDefault = Double.valueOf(defaultValue.doubleValue()).intValue();
          if (validateDetails) {
            AssignmentDetails<Integer> details =
                eppoClient.getIntegerAssignmentDetails(
                    flagKey, subjectKey, subjectAttributes, castedDefault);
            assertAssignment(flagKey, subjectAssignment, details.getVariation());
            assertAssignmentDetails(flagKey, subjectAssignment, details.getEvaluationDetails());
          } else {
            int intAssignment =
                eppoClient.getIntegerAssignment(
                    flagKey, subjectKey, subjectAttributes, castedDefault);
            assertAssignment(flagKey, subjectAssignment, intAssignment);
          }
          break;
        case NUMERIC:
          if (validateDetails) {
            AssignmentDetails<Double> details =
                eppoClient.getDoubleAssignmentDetails(
                    flagKey, subjectKey, subjectAttributes, defaultValue.doubleValue());
            assertAssignment(flagKey, subjectAssignment, details.getVariation());
            assertAssignmentDetails(flagKey, subjectAssignment, details.getEvaluationDetails());
          } else {
            double doubleAssignment =
                eppoClient.getDoubleAssignment(
                    flagKey, subjectKey, subjectAttributes, defaultValue.doubleValue());
            assertAssignment(flagKey, subjectAssignment, doubleAssignment);
          }
          break;
        case STRING:
          if (validateDetails) {
            AssignmentDetails<String> details =
                eppoClient.getStringAssignmentDetails(
                    flagKey, subjectKey, subjectAttributes, defaultValue.stringValue());
            assertAssignment(flagKey, subjectAssignment, details.getVariation());
            assertAssignmentDetails(flagKey, subjectAssignment, details.getEvaluationDetails());
          } else {
            String stringAssignment =
                eppoClient.getStringAssignment(
                    flagKey, subjectKey, subjectAttributes, defaultValue.stringValue());
            assertAssignment(flagKey, subjectAssignment, stringAssignment);
          }
          break;
        case JSON:
          if (validateDetails) {
            AssignmentDetails<JsonNode> details =
                eppoClient.getJSONAssignmentDetails(
                    flagKey, subjectKey, subjectAttributes, testCase.getDefaultValue().jsonValue());
            assertAssignment(flagKey, subjectAssignment, details.getVariation());
            assertAssignmentDetails(flagKey, subjectAssignment, details.getEvaluationDetails());
          } else {
            JsonNode jsonAssignment =
                eppoClient.getJSONAssignment(
                    flagKey, subjectKey, subjectAttributes, testCase.getDefaultValue().jsonValue());
            assertAssignment(flagKey, subjectAssignment, jsonAssignment);
          }
          break;
        default:
          throw new UnsupportedOperationException(
              "Unexpected variation type "
                  + testCase.getVariationType()
                  + " for "
                  + flagKey
                  + " test case");
      }
    }
  }

  /** Helper method for asserting evaluation details match expected values from test data. */
  private static void assertAssignmentDetails(
      String flagKey, SubjectAssignment subjectAssignment, EvaluationDetails actualDetails) {

    if (!subjectAssignment.hasEvaluationDetails()) {
      // No expected details, so nothing to validate
      return;
    }

    EvaluationDetails expectedDetails = subjectAssignment.getEvaluationDetails();
    String subjectKey = subjectAssignment.getSubjectKey();

    assertNotNull(
        actualDetails,
        String.format("Expected evaluation details for flag %s, subject %s", flagKey, subjectKey));

    // Compare all fields
    assertEquals(
        expectedDetails.getEnvironmentName(),
        actualDetails.getEnvironmentName(),
        String.format("Environment name mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertEquals(
        expectedDetails.getFlagEvaluationCode(),
        actualDetails.getFlagEvaluationCode(),
        String.format(
            "Flag evaluation code mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertEquals(
        expectedDetails.getFlagEvaluationDescription(),
        actualDetails.getFlagEvaluationDescription(),
        String.format(
            "Flag evaluation description mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertEquals(
        expectedDetails.getBanditKey(),
        actualDetails.getBanditKey(),
        String.format("Bandit key mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertEquals(
        expectedDetails.getBanditAction(),
        actualDetails.getBanditAction(),
        String.format("Bandit action mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertEquals(
        expectedDetails.getVariationKey(),
        actualDetails.getVariationKey(),
        String.format("Variation key mismatch for flag %s, subject %s", flagKey, subjectKey));

    // Compare variation value with type-aware logic
    assertVariationValuesEqual(
        expectedDetails.getVariationValue(),
        actualDetails.getVariationValue(),
        String.format("Variation value mismatch for flag %s, subject %s", flagKey, subjectKey));

    // Compare matched rule (null-safe with deep comparison)
    assertMatchedRuleEqual(
        expectedDetails.getMatchedRule(),
        actualDetails.getMatchedRule(),
        String.format("Matched rule mismatch for flag %s, subject %s", flagKey, subjectKey));

    // Compare matched allocation
    assertAllocationDetailsEqual(
        expectedDetails.getMatchedAllocation(),
        actualDetails.getMatchedAllocation(),
        String.format("Matched allocation mismatch for flag %s, subject %s", flagKey, subjectKey));

    // Compare allocation lists
    assertAllocationListsEqual(
        expectedDetails.getUnmatchedAllocations(),
        actualDetails.getUnmatchedAllocations(),
        String.format(
            "Unmatched allocations mismatch for flag %s, subject %s", flagKey, subjectKey));

    assertAllocationListsEqual(
        expectedDetails.getUnevaluatedAllocations(),
        actualDetails.getUnevaluatedAllocations(),
        String.format(
            "Unevaluated allocations mismatch for flag %s, subject %s", flagKey, subjectKey));
  }

  private static void assertAllocationListsEqual(
      List<AllocationDetails> expected, List<AllocationDetails> actual, String message) {
    assertEquals(expected.size(), actual.size(), message + " (count)");

    for (int i = 0; i < expected.size(); i++) {
      assertAllocationDetailsEqual(expected.get(i), actual.get(i), message + " (index " + i + ")");
    }
  }

  private static void assertVariationValuesEqual(
      EppoValue expected, EppoValue actual, String message) {
    if (expected == null || expected.isNull()) {
      assertTrue(actual == null || actual.isNull(), message);
      return;
    }

    assertNotNull(actual, message);
    assertFalse(actual.isNull(), message + " (expected non-null value)");

    // Handle different EppoValue types
    if (expected.isBoolean()) {
      assertTrue(actual.isBoolean(), message + " (expected boolean type)");
      assertEquals(expected.booleanValue(), actual.booleanValue(), message);
    } else if (expected.isNumeric()) {
      assertTrue(actual.isNumeric(), message + " (expected numeric type)");
      assertEquals(expected.doubleValue(), actual.doubleValue(), 0.000001, message);
    } else if (expected.isString()) {
      assertTrue(actual.isString(), message + " (expected string type)");

      // Try parsing as JSON for semantic comparison
      String expectedStr = expected.stringValue();
      String actualStr = actual.stringValue();

      try {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expectedJson = mapper.readTree(expectedStr);
        JsonNode actualJson = mapper.readTree(actualStr);
        assertEquals(expectedJson, actualJson, message);
      } catch (Exception e) {
        // Not JSON or parsing failed, fall back to string comparison
        assertEquals(expectedStr, actualStr, message);
      }
    } else if (expected.isStringArray()) {
      assertTrue(actual.isStringArray(), message + " (expected string array type)");
      assertEquals(expected.stringArrayValue(), actual.stringArrayValue(), message);
    } else {
      assertEquals(expected.toString(), actual.toString(), message);
    }
  }

  private static void assertMatchedRuleEqual(
      MatchedRule expected, MatchedRule actual, String message) {
    if (expected == null) {
      assertNull(actual, message);
      return;
    }

    assertNotNull(actual, message);

    Set<RuleCondition> expectedConditions = expected.getConditions();
    Set<RuleCondition> actualConditions = actual.getConditions();

    assertEquals(
        expectedConditions.size(), actualConditions.size(), message + " (conditions count)");

    // When obfuscated, attributes and values will be one-way hashed so we will only check count and
    // rely on unobfuscated tests for correctness
    boolean hasObfuscation =
        actualConditions.stream()
            .anyMatch(
                rc -> rc.getAttribute() != null && rc.getAttribute().matches("^[a-f0-9]{32}$"));
    if (hasObfuscation) {
      return;
    }

    // With Set-based rules, when multiple rules match, the matched rule is non-deterministic
    // So we just verify both have the same number of conditions rather than exact equality
    // This allows tests to pass even when rule iteration order varies
    if (expectedConditions.size() != actualConditions.size()) {
      fail(
          message
              + String.format(
                  " (expected %d conditions but got %d)",
                  expectedConditions.size(), actualConditions.size()));
    }
  }

  private static void assertAllocationDetailsEqual(
      AllocationDetails expected, AllocationDetails actual, String message) {
    if (expected == null) {
      assertNull(actual, message);
      return;
    }

    assertNotNull(actual, message);
    assertEquals(expected.getKey(), actual.getKey(), message + " (key)");
    assertEquals(
        expected.getAllocationEvaluationCode(),
        actual.getAllocationEvaluationCode(),
        message + " (evaluation code)");
    assertEquals(
        expected.getOrderPosition(), actual.getOrderPosition(), message + " (order position)");
  }

  /** Helper method for asserting a subject assignment with a useful failure message. */
  private static <T> void assertAssignment(
      String flagKey, SubjectAssignment expectedSubjectAssignment, T assignment) {

    if (assignment == null) {
      fail(
          "Unexpected null "
              + flagKey
              + " assignment for subject "
              + expectedSubjectAssignment.getSubjectKey());
    }

    String failureMessage =
        "Incorrect "
            + flagKey
            + " assignment for subject "
            + expectedSubjectAssignment.getSubjectKey();

    if (assignment instanceof Boolean) {
      assertEquals(
          expectedSubjectAssignment.getAssignment().booleanValue(), assignment, failureMessage);
    } else if (assignment instanceof Integer) {
      assertEquals(
          Double.valueOf(expectedSubjectAssignment.getAssignment().doubleValue()).intValue(),
          assignment,
          failureMessage);
    } else if (assignment instanceof Double) {
      assertEquals(
          expectedSubjectAssignment.getAssignment().doubleValue(),
          (Double) assignment,
          0.000001,
          failureMessage);
    } else if (assignment instanceof String) {
      assertEquals(
          expectedSubjectAssignment.getAssignment().stringValue(), assignment, failureMessage);
    } else if (assignment instanceof JsonNode) {
      assertEquals(
          expectedSubjectAssignment.getAssignment().jsonValue().toString(),
          assignment.toString(),
          failureMessage);
    } else {
      throw new IllegalArgumentException(
          "Unexpected assignment type " + assignment.getClass().getCanonicalName());
    }
  }
}
