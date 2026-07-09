package cloud.eppo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static cloud.eppo.Utils.base64Encode;
import static cloud.eppo.Utils.getMD5Hex;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import cloud.eppo.api.AllocationDetails;
import cloud.eppo.api.AllocationEvaluationCode;
import cloud.eppo.api.Attributes;
import cloud.eppo.api.EppoValue;
import cloud.eppo.api.EvaluationDetails;
import cloud.eppo.api.FlagEvaluationCode;
import cloud.eppo.api.dto.Allocation;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.OperatorType;
import cloud.eppo.api.dto.Shard;
import cloud.eppo.api.dto.ShardRange;
import cloud.eppo.api.dto.Split;
import cloud.eppo.api.dto.TargetingCondition;
import cloud.eppo.api.dto.TargetingRule;
import cloud.eppo.api.dto.Variation;
import cloud.eppo.api.dto.VariationType;

public class FlagEvaluatorTest {

  @Test
  public void testDisabledFlag() {
    Map<String, Variation> variations = createVariations("a");
    Set<Shard> shards = createShards("salt");
    List<Split> splits = createSplits("a", shards);
    List<Allocation> allocations = createAllocations("allocation", splits);
    FlagConfig flag = createFlag("flag", false, variations, allocations);

    // Create test metadata values
    String testEnvironmentName = "Production";
    Date testConfigFetchedAt = new Date(1672531200000L); // Jan 1, 2023
    Date testConfigPublishedAt = new Date(1672444800000L); // Dec 31, 2022

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag,
            "flag",
            "subjectKey",
            new Attributes(),
            false,
            testEnvironmentName,
            testConfigFetchedAt,
            testConfigPublishedAt);

    assertEquals(flag.getKey(), result.getFlagKey());
    assertEquals("subjectKey", result.getSubjectKey());
    assertEquals(new Attributes(), result.getSubjectAttributes());
    assertNull(result.getAllocationKey());
    assertNull(result.getVariation());
    assertFalse(result.doLog());

    // Verify configuration metadata flows through to evaluation details
    EvaluationDetails details = result.getEvaluationDetails();
    assertNotNull(details);
    assertEquals("Production", details.getEnvironmentName());
    assertEquals(testConfigFetchedAt, details.getConfigFetchedAt());
    assertEquals(testConfigPublishedAt, details.getConfigPublishedAt());

    // Verify evaluation details for disabled flag
    assertEquals(FlagEvaluationCode.FLAG_UNRECOGNIZED_OR_DISABLED, details.getFlagEvaluationCode());
    assertEquals("Unrecognized or disabled flag: flag", details.getFlagEvaluationDescription());
    assertNull(details.getVariationKey());
    assertNull(details.getVariationValue());
    assertNull(details.getBanditKey());
    assertNull(details.getBanditAction());
    assertNull(details.getMatchedRule());
    assertNull(details.getMatchedAllocation());
    assertTrue(details.getUnmatchedAllocations().isEmpty());

    // Disabled flag should have all allocations as unevaluated
    assertEquals(1, details.getUnevaluatedAllocations().size());
    AllocationDetails unevaluatedAllocation = details.getUnevaluatedAllocations().get(0);
    assertEquals("allocation", unevaluatedAllocation.getKey());
    assertEquals(
        AllocationEvaluationCode.UNEVALUATED, unevaluatedAllocation.getAllocationEvaluationCode());
    assertEquals(1, unevaluatedAllocation.getOrderPosition());
  }

  @Test
  public void testNoAllocations() {
    Map<String, Variation> variations = createVariations("a");
    FlagConfig flag = createFlag("flag", true, variations, null);
    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subjectKey", new Attributes(), false, "Test", null, null);

    assertEquals(flag.getKey(), result.getFlagKey());
    assertEquals("subjectKey", result.getSubjectKey());
    assertEquals(new Attributes(), result.getSubjectAttributes());
    assertNull(result.getAllocationKey());
    assertNull(result.getVariation());
    assertFalse(result.doLog());

    // Verify evaluation details for no allocations
    EvaluationDetails details = result.getEvaluationDetails();
    assertNotNull(details);
    assertEquals("Test", details.getEnvironmentName());
    assertEquals(FlagEvaluationCode.DEFAULT_ALLOCATION_NULL, details.getFlagEvaluationCode());
    assertEquals(
        "No allocations matched. Falling back to \"Default Allocation\", serving NULL",
        details.getFlagEvaluationDescription());
    assertNull(details.getVariationKey());
    assertNull(details.getVariationValue());
    assertNull(details.getMatchedRule());
    assertNull(details.getMatchedAllocation());
    assertTrue(details.getUnmatchedAllocations().isEmpty());
    assertTrue(details.getUnevaluatedAllocations().isEmpty());
  }

  @Test
  public void testSimpleFlag() {
    Map<String, Variation> variations = createVariations("a");
    Set<Shard> shards = createShards("salt", 0, 10);
    List<Split> splits = createSplits("a", shards);
    List<Allocation> allocations = createAllocations("allocation", splits);
    FlagConfig flag = createFlag("flag", true, variations, allocations);

    // Create test metadata values
    String testEnvironmentName = "Staging";
    Date testConfigFetchedAt = new Date(1672617600000L); // Jan 2, 2023
    Date testConfigPublishedAt = new Date(1672531200000L); // Jan 1, 2023

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag,
            "flag",
            "subjectKey",
            new Attributes(),
            false,
            testEnvironmentName,
            testConfigFetchedAt,
            testConfigPublishedAt);

    assertEquals(flag.getKey(), result.getFlagKey());
    assertEquals("subjectKey", result.getSubjectKey());
    assertEquals(new Attributes(), result.getSubjectAttributes());
    assertEquals("allocation", result.getAllocationKey());
    assertEquals("A", result.getVariation().getValue().stringValue());
    assertTrue(result.doLog());

    // Verify configuration metadata flows through to evaluation details
    EvaluationDetails details = result.getEvaluationDetails();
    assertNotNull(details);
    assertEquals("Staging", details.getEnvironmentName());
    assertEquals(testConfigFetchedAt, details.getConfigFetchedAt());
    assertEquals(testConfigPublishedAt, details.getConfigPublishedAt());

    // Verify evaluation details for matched flag
    assertEquals(FlagEvaluationCode.MATCH, details.getFlagEvaluationCode());
    assertTrue(details.getFlagEvaluationDescription().contains("allocation"));
    assertEquals("a", details.getVariationKey());
    assertEquals("A", details.getVariationValue().stringValue());
    assertNull(details.getBanditKey());
    assertNull(details.getBanditAction());
    assertNull(details.getMatchedRule()); // No rules, just traffic split

    // Verify matched allocation
    assertNotNull(details.getMatchedAllocation());
    assertEquals("allocation", details.getMatchedAllocation().getKey());
    assertEquals(
        AllocationEvaluationCode.MATCH,
        details.getMatchedAllocation().getAllocationEvaluationCode());
    assertEquals(1, details.getMatchedAllocation().getOrderPosition());

    // No unmatched or unevaluated allocations for single allocation flag
    assertTrue(details.getUnmatchedAllocations().isEmpty());
    assertTrue(details.getUnevaluatedAllocations().isEmpty());
  }

  @Test
  public void testIDTargetingCondition() {
    Map<String, Variation> variations = createVariations("a");
    List<Split> splits = createSplits("a");

    List<String> values = new LinkedList<>();
    values.add("alice");
    values.add("bob");
    EppoValue value = EppoValue.valueOf(values);
    Set<TargetingRule> rules = createRules("id", OperatorType.ONE_OF, value);

    List<Allocation> allocations = createAllocations("allocation", splits, rules);
    FlagConfig flag = createFlag("key", true, variations, allocations);

    // Check that subjectKey is evaluated as the "id" attribute

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "alice", new Attributes(), false, null, null, null);

    assertEquals("A", result.getVariation().getValue().stringValue());

    result =
        FlagEvaluator.evaluateFlag(flag, "flag", "bob", new Attributes(), false, null, null, null);

    assertEquals("A", result.getVariation().getValue().stringValue());

    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "charlie", new Attributes(), false, null, null, null);

    assertNull(result.getVariation());

    // Check that an explicitly passed-in "id" attribute takes precedence

    Attributes aliceAttributes = new Attributes();
    aliceAttributes.put("id", "charlie");
    result =
        FlagEvaluator.evaluateFlag(flag, "flag", "alice", aliceAttributes, false, null, null, null);

    assertNull(result.getVariation());

    Attributes charlieAttributes = new Attributes();
    charlieAttributes.put("id", "alice");

    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "charlie", charlieAttributes, false, null, null, null);

    assertEquals("A", result.getVariation().getValue().stringValue());
  }

  @Test
  public void testCatchAllAllocation() {
    Map<String, Variation> variations = createVariations("a", "b");
    List<Split> splits = createSplits("a");
    List<Allocation> allocations = createAllocations("default", splits);
    FlagConfig flag = createFlag("key", true, variations, allocations);

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subjectKey", new Attributes(), false, null, null, null);

    assertEquals("default", result.getAllocationKey());
    assertEquals("A", result.getVariation().getValue().stringValue());
    assertTrue(result.doLog());
  }

  @Test
  public void testMultipleAllocations() {
    Map<String, Variation> variations = createVariations("a", "b");
    List<Split> firstAllocationSplits = createSplits("b");
    Set<TargetingRule> rules =
        createRules("email", OperatorType.MATCHES, EppoValue.valueOf(".*example\\.com$"));
    List<Allocation> allocations = createAllocations("first", firstAllocationSplits, rules);

    List<Split> defaultSplits = createSplits("a");
    allocations.addAll(createAllocations("default", defaultSplits));
    FlagConfig flag = createFlag("key", true, variations, allocations);

    // Test 1: Subject matches first allocation's rules
    Attributes matchingEmailAttributes = new Attributes();
    matchingEmailAttributes.put("email", "eppo@example.com");
    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subjectKey", matchingEmailAttributes, false, "Test", null, null);
    assertEquals("B", result.getVariation().getValue().stringValue());

    // Verify details when first allocation matches
    EvaluationDetails details = result.getEvaluationDetails();
    assertEquals(FlagEvaluationCode.MATCH, details.getFlagEvaluationCode());
    assertEquals("b", details.getVariationKey());
    assertNotNull(details.getMatchedRule());
    assertEquals(1, details.getMatchedRule().getConditions().size());

    // Matched allocation should be "first" at position 1
    assertNotNull(details.getMatchedAllocation());
    assertEquals("first", details.getMatchedAllocation().getKey());
    assertEquals(
        AllocationEvaluationCode.MATCH,
        details.getMatchedAllocation().getAllocationEvaluationCode());
    assertEquals(1, details.getMatchedAllocation().getOrderPosition());

    // "default" allocation should be unevaluated at position 2
    assertTrue(details.getUnmatchedAllocations().isEmpty());
    assertEquals(1, details.getUnevaluatedAllocations().size());
    assertEquals("default", details.getUnevaluatedAllocations().get(0).getKey());
    assertEquals(
        AllocationEvaluationCode.UNEVALUATED,
        details.getUnevaluatedAllocations().get(0).getAllocationEvaluationCode());
    assertEquals(2, details.getUnevaluatedAllocations().get(0).getOrderPosition());

    // Test 2: Subject doesn't match first allocation's rules, falls through to default
    Attributes unknownEmailAttributes = new Attributes();
    unknownEmailAttributes.put("email", "eppo@test.com");
    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subjectKey", unknownEmailAttributes, false, "Test", null, null);
    assertEquals("A", result.getVariation().getValue().stringValue());

    // Verify details when first allocation doesn't match
    details = result.getEvaluationDetails();
    assertEquals(FlagEvaluationCode.MATCH, details.getFlagEvaluationCode());
    assertEquals("a", details.getVariationKey());
    assertNull(details.getMatchedRule()); // default has no rules

    // Matched allocation should be "default" at position 2
    assertNotNull(details.getMatchedAllocation());
    assertEquals("default", details.getMatchedAllocation().getKey());
    assertEquals(
        AllocationEvaluationCode.MATCH,
        details.getMatchedAllocation().getAllocationEvaluationCode());
    assertEquals(2, details.getMatchedAllocation().getOrderPosition());

    // "first" allocation should be unmatched (FAILING_RULE) at position 1
    assertEquals(1, details.getUnmatchedAllocations().size());
    assertEquals("first", details.getUnmatchedAllocations().get(0).getKey());
    assertEquals(
        AllocationEvaluationCode.FAILING_RULE,
        details.getUnmatchedAllocations().get(0).getAllocationEvaluationCode());
    assertEquals(1, details.getUnmatchedAllocations().get(0).getOrderPosition());
    assertTrue(details.getUnevaluatedAllocations().isEmpty());

    // Test 3: No attributes - also falls through to default
    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subjectKey", new Attributes(), false, "Test", null, null);
    assertEquals("A", result.getVariation().getValue().stringValue());

    details = result.getEvaluationDetails();
    assertEquals("default", details.getMatchedAllocation().getKey());
    assertEquals(1, details.getUnmatchedAllocations().size());
    assertEquals("first", details.getUnmatchedAllocations().get(0).getKey());
  }

  @Test
  public void testVariationShardRanges() {
    Map<String, Variation> variations = createVariations("a", "b", "c");
    Set<Shard> trafficShards = createShards("traffic", 0, 5);

    Set<Shard> shardsA = createShards("split", 0, 3);
    shardsA.addAll(trafficShards); // both splits include the same traffic shard
    List<Split> firstAllocationSplits = createSplits("a", shardsA);

    Set<Shard> shardsB = createShards("split", 3, 6);
    shardsB.addAll(trafficShards); // both splits include the same traffic shard
    firstAllocationSplits.addAll(createSplits("b", shardsB));

    List<Allocation> allocations = createAllocations("first", firstAllocationSplits);

    List<Split> defaultSplits = createSplits("c");
    allocations.addAll(createAllocations("default", defaultSplits));

    FlagConfig flag = createFlag("key", true, variations, allocations);

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subject4", new Attributes(), false, null, null, null);

    assertEquals("A", result.getVariation().getValue().stringValue());

    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subject13", new Attributes(), false, null, null, null);

    assertEquals("B", result.getVariation().getValue().stringValue());

    result =
        FlagEvaluator.evaluateFlag(
            flag, "flag", "subject14", new Attributes(), false, null, null, null);

    assertEquals("C", result.getVariation().getValue().stringValue());
  }

  @Test
  public void testAllocationStartAndEndAt() {
    Map<String, Variation> variations = createVariations("a");
    List<Split> splits = createSplits("a");

    // Start off with today being between startAt and endAt
    Date now = new Date();
    long oneDayInMilliseconds = 1000L * 60 * 60 * 24;
    Date startAt = new Date(now.getTime() - oneDayInMilliseconds);
    Date endAt = new Date(now.getTime() + oneDayInMilliseconds);

    List<Allocation> activeAllocations =
        createAllocationsWithDates("allocation", splits, null, startAt, endAt);
    FlagConfig activeFlag = createFlag("key", true, variations, activeAllocations);

    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            activeFlag, "flag", "subject", new Attributes(), false, "Test", null, null);

    assertEquals("A", result.getVariation().getValue().stringValue());
    assertTrue(result.doLog());

    // Verify details for active allocation
    EvaluationDetails details = result.getEvaluationDetails();
    assertEquals(FlagEvaluationCode.MATCH, details.getFlagEvaluationCode());
    assertNotNull(details.getMatchedAllocation());
    assertEquals("allocation", details.getMatchedAllocation().getKey());
    assertEquals(
        AllocationEvaluationCode.MATCH,
        details.getMatchedAllocation().getAllocationEvaluationCode());

    // Make both startAt and endAt in the future (allocation not yet active)
    Date futureStart = new Date(now.getTime() + oneDayInMilliseconds);
    Date futureEnd = new Date(now.getTime() + 2 * oneDayInMilliseconds);
    List<Allocation> futureAllocations =
        createAllocationsWithDates("allocation", splits, null, futureStart, futureEnd);
    FlagConfig futureFlag = createFlag("key", true, variations, futureAllocations);

    result =
        FlagEvaluator.evaluateFlag(
            futureFlag, "flag", "subject", new Attributes(), false, "Test", null, null);

    assertNull(result.getVariation());
    assertFalse(result.doLog());

    // Verify details for not-yet-active allocation
    details = result.getEvaluationDetails();
    assertEquals(FlagEvaluationCode.DEFAULT_ALLOCATION_NULL, details.getFlagEvaluationCode());
    assertNull(details.getMatchedAllocation());
    assertEquals(1, details.getUnmatchedAllocations().size());
    assertEquals("allocation", details.getUnmatchedAllocations().get(0).getKey());
    assertEquals(
        AllocationEvaluationCode.BEFORE_START_TIME,
        details.getUnmatchedAllocations().get(0).getAllocationEvaluationCode());
    assertEquals(1, details.getUnmatchedAllocations().get(0).getOrderPosition());

    // Make both startAt and endAt in the past (allocation expired)
    Date pastStart = new Date(now.getTime() - 2 * oneDayInMilliseconds);
    Date pastEnd = new Date(now.getTime() - oneDayInMilliseconds);
    List<Allocation> expiredAllocations =
        createAllocationsWithDates("allocation", splits, null, pastStart, pastEnd);
    FlagConfig expiredFlag = createFlag("key", true, variations, expiredAllocations);

    result =
        FlagEvaluator.evaluateFlag(
            expiredFlag, "flag", "subject", new Attributes(), false, "Test", null, null);

    assertNull(result.getVariation());
    assertFalse(result.doLog());

    // Verify details for expired allocation
    details = result.getEvaluationDetails();
    assertEquals(FlagEvaluationCode.DEFAULT_ALLOCATION_NULL, details.getFlagEvaluationCode());
    assertNull(details.getMatchedAllocation());
    assertEquals(1, details.getUnmatchedAllocations().size());
    assertEquals("allocation", details.getUnmatchedAllocations().get(0).getKey());
    assertEquals(
        AllocationEvaluationCode.AFTER_END_TIME,
        details.getUnmatchedAllocations().get(0).getAllocationEvaluationCode());
    assertEquals(1, details.getUnmatchedAllocations().get(0).getOrderPosition());
  }

  @Test
  public void testObfuscated() {
    // Note: this is NOT a comprehensive test of obfuscation (many operators and value types are
    // excluded, as are startAt and endAt)
    // Much more is covered by EppoClientTest

    Map<String, Variation> variations = createVariations("a", "b");
    List<Split> firstAllocationSplits = createSplits("b");
    Set<TargetingRule> rules =
        createRules("email", OperatorType.MATCHES, EppoValue.valueOf(".*example\\.com$"));
    List<Allocation> allocations = createAllocations("first", firstAllocationSplits, rules);

    List<Split> defaultSplits = createSplits("a");
    allocations.addAll(createAllocations("default", defaultSplits));
    // Hash the flag key (done in-place)
    FlagConfig flag = createFlag(getMD5Hex("flag"), true, variations, allocations);

    // Encode the variations (done by creating new map as keys change)
    Map<String, Variation> encodedVariations = new HashMap<>();
    for (Map.Entry<String, Variation> variationEntry : variations.entrySet()) {
      String encodedVariationKey = base64Encode(variationEntry.getKey());
      Variation variationToEncode = variationEntry.getValue();
      Variation newVariation =
          new Variation.Default(
              encodedVariationKey,
              EppoValue.valueOf(base64Encode(variationToEncode.getValue().stringValue())));
      encodedVariations.put(encodedVariationKey, newVariation);
    }
    // Encode the allocations
    List<Allocation> encodedAllocations =
        allocations.stream()
            .map(
                allocationToEncode -> {
                  String encodedKey = base64Encode(allocationToEncode.getKey());
                  Set<TargetingRule> encodedRules = new HashSet<>();
                  if (allocationToEncode.getRules() != null) {
                    // assume just a single rule with a single string-valued condition
                    TargetingCondition conditionToEncode =
                        allocationToEncode
                            .getRules()
                            .iterator()
                            .next()
                            .getConditions()
                            .iterator()
                            .next();
                    String attribute = getMD5Hex(conditionToEncode.getAttribute());
                    EppoValue value =
                        EppoValue.valueOf(base64Encode(conditionToEncode.getValue().stringValue()));
                    TargetingCondition encodedCondition =
                        new TargetingCondition.Default(
                            conditionToEncode.getOperator(), attribute, value);
                    encodedRules.add(
                        new TargetingRule.Default(
                            new HashSet<>(Collections.singletonList(encodedCondition))));
                    encodedRules.addAll(
                        allocationToEncode.getRules().stream()
                            .skip(1)
                            .collect(Collectors.toList()));
                  }
                  List<Split> encodedSplits =
                      allocationToEncode.getSplits().stream()
                          .map(
                              splitToEncode ->
                                  new Split.Default(
                                      base64Encode(splitToEncode.getVariationKey()),
                                      splitToEncode.getShards(),
                                      splitToEncode.getExtraLogging()))
                          .collect(Collectors.toList());
                  return new Allocation.Default(
                      encodedKey,
                      encodedRules,
                      allocationToEncode.getStartAt(),
                      allocationToEncode.getEndAt(),
                      encodedSplits,
                      allocationToEncode.doLog());
                })
            .collect(Collectors.toList());

    Attributes matchingEmailAttributes = new Attributes();
    matchingEmailAttributes.put("email", "eppo@example.com");
    FlagConfig obfuscatedFlag =
        new FlagConfig.Default(
            flag.getKey(),
            flag.isEnabled(),
            flag.getTotalShards(),
            flag.getVariationType(),
            encodedVariations,
            encodedAllocations);
    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            obfuscatedFlag, "flag", "subjectKey", matchingEmailAttributes, true, null, null, null);

    // Expect an unobfuscated evaluation result
    assertEquals("flag", result.getFlagKey());
    assertEquals("subjectKey", result.getSubjectKey());
    assertEquals(matchingEmailAttributes, result.getSubjectAttributes());
    assertEquals("first", result.getAllocationKey());
    assertEquals("B", result.getVariation().getValue().stringValue());
    assertTrue(result.doLog());

    Attributes unknownEmailAttributes = new Attributes();
    unknownEmailAttributes.put("email", "eppo@test.com");
    result =
        FlagEvaluator.evaluateFlag(
            obfuscatedFlag, "flag", "subjectKey", unknownEmailAttributes, true, null, null, null);
    assertEquals("A", result.getVariation().getValue().stringValue());

    result =
        FlagEvaluator.evaluateFlag(
            obfuscatedFlag, "flag", "subjectKey", new Attributes(), true, null, null, null);
    assertEquals("A", result.getVariation().getValue().stringValue());
  }

  @Test
  public void testObfuscatedExtraLogging() {
    // Test that extraLogging is properly deobfuscated when isConfigObfuscated is true

    Map<String, Variation> variations = createVariations("a");

    // Create extraLogging with obfuscated keys and values
    Map<String, String> obfuscatedExtraLogging = new HashMap<>();
    obfuscatedExtraLogging.put(base64Encode("testKey"), base64Encode("testValue"));
    obfuscatedExtraLogging.put(base64Encode("anotherKey"), base64Encode("anotherValue"));

    List<Split> splits = new ArrayList<>();
    splits.add(new Split.Default("a", null, obfuscatedExtraLogging));

    List<Allocation> allocations = createAllocations("test", splits);

    // Create the base flag
    FlagConfig flag = createFlag(getMD5Hex("flag"), true, variations, allocations);

    // Encode the variations (following the same pattern as the main obfuscated test)
    Map<String, Variation> encodedVariations = new HashMap<>();
    for (Map.Entry<String, Variation> variationEntry : variations.entrySet()) {
      String encodedVariationKey = base64Encode(variationEntry.getKey());
      Variation variationToEncode = variationEntry.getValue();
      Variation newVariation =
          new Variation.Default(
              encodedVariationKey,
              EppoValue.valueOf(base64Encode(variationToEncode.getValue().stringValue())));
      encodedVariations.put(encodedVariationKey, newVariation);
    }

    // Encode the allocations
    List<Allocation> encodedAllocations =
        allocations.stream()
            .map(
                allocationToEncode -> {
                  String encodedKey = base64Encode(allocationToEncode.getKey());
                  List<Split> encodedSplits =
                      allocationToEncode.getSplits().stream()
                          .map(
                              splitToEncode ->
                                  new Split.Default(
                                      base64Encode(splitToEncode.getVariationKey()),
                                      splitToEncode.getShards(),
                                      splitToEncode.getExtraLogging()))
                          .collect(Collectors.toList());
                  return new Allocation.Default(
                      encodedKey,
                      allocationToEncode.getRules(),
                      allocationToEncode.getStartAt(),
                      allocationToEncode.getEndAt(),
                      encodedSplits,
                      allocationToEncode.doLog());
                })
            .collect(Collectors.toList());

    // Create the obfuscated flag
    FlagConfig obfuscatedFlag =
        new FlagConfig.Default(
            flag.getKey(),
            flag.isEnabled(),
            flag.getTotalShards(),
            flag.getVariationType(),
            encodedVariations,
            encodedAllocations);

    // Test with obfuscated config
    FlagEvaluationResult result =
        FlagEvaluator.evaluateFlag(
            obfuscatedFlag, "flag", "subject", new Attributes(), true, null, null, null);

    // Verify that extraLogging is deobfuscated
    Map<String, String> extraLogging = result.getExtraLogging();
    assertNotNull(extraLogging);
    assertEquals("testValue", extraLogging.get("testKey"));
    assertEquals("anotherValue", extraLogging.get("anotherKey"));
    assertEquals(2, extraLogging.size());

    // Test with non-obfuscated config to ensure no deobfuscation happens
    result =
        FlagEvaluator.evaluateFlag(
            obfuscatedFlag, "flag", "subject", new Attributes(), false, null, null, null);

    // Verify that extraLogging remains obfuscated
    extraLogging = result.getExtraLogging();
    assertNotNull(extraLogging);
    assertEquals(base64Encode("testValue"), extraLogging.get(base64Encode("testKey")));
    assertEquals(base64Encode("anotherValue"), extraLogging.get(base64Encode("anotherKey")));
    assertEquals(2, extraLogging.size());
  }

  private Map<String, Variation> createVariations(String key) {
    return createVariations(key, null, null);
  }

  private Map<String, Variation> createVariations(String key1, String key2) {
    return createVariations(key1, key2, null);
  }

  private Map<String, Variation> createVariations(String key1, String key2, String key3) {
    String[] keys = {key1, key2, key3};
    Map<String, Variation> variations = new HashMap<>();
    for (String key : keys) {
      if (key != null) {
        // Use the uppercase key as the dummy value
        Variation variation = new Variation.Default(key, EppoValue.valueOf(key.toUpperCase()));
        variations.put(variation.getKey(), variation);
      }
    }
    return variations;
  }

  private Set<Shard> createShards(String salt) {
    return createShards(salt, null, null);
  }

  private Set<Shard> createShards(String salt, Integer rangeStart, Integer rangeEnd) {
    Set<ShardRange> ranges = new HashSet<>();
    if (rangeStart != null) {
      ShardRange.Default range = new ShardRange.Default(rangeStart, rangeEnd);
      ranges = new HashSet<>(Collections.singletonList(range));
    }
    return new HashSet<>(Collections.singletonList(new Shard.Default(salt, ranges)));
  }

  private List<Split> createSplits(String variationKey) {
    return createSplits(variationKey, null);
  }

  private List<Split> createSplits(String variationKey, Set<Shard> shards) {
    Split split = new Split.Default(variationKey, shards, new HashMap<>());
    return new ArrayList<>(Collections.singletonList(split));
  }

  private Set<TargetingRule> createRules(String attribute, OperatorType operator, EppoValue value) {
    Set<TargetingCondition> conditions = new HashSet<>();
    conditions.add(new TargetingCondition.Default(operator, attribute, value));
    return new HashSet<>(Collections.singletonList(new TargetingRule.Default(conditions)));
  }

  private List<Allocation> createAllocations(String allocationKey, List<Split> splits) {
    return createAllocations(allocationKey, splits, null);
  }

  private List<Allocation> createAllocations(
      String allocationKey, List<Split> splits, Set<TargetingRule> rules) {
    return createAllocationsWithDates(allocationKey, splits, rules, null, null);
  }

  private List<Allocation> createAllocationsWithDates(
      String allocationKey,
      List<Split> splits,
      Set<TargetingRule> rules,
      Date startAt,
      Date endAt) {
    Allocation allocation =
        new Allocation.Default(allocationKey, rules, startAt, endAt, splits, true);
    return new ArrayList<>(Collections.singletonList(allocation));
  }

  private FlagConfig createFlag(
      String key,
      boolean enabled,
      Map<String, Variation> variations,
      List<Allocation> allocations) {
    return new FlagConfig.Default(key, enabled, 10, VariationType.STRING, variations, allocations);
  }
}
