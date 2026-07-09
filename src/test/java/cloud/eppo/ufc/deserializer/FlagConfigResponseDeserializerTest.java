package cloud.eppo.ufc.deserializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import cloud.eppo.api.dto.Allocation;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.FlagConfigResponse;
import cloud.eppo.api.dto.OperatorType;
import cloud.eppo.api.dto.Shard;
import cloud.eppo.api.dto.ShardRange;
import cloud.eppo.api.dto.Split;
import cloud.eppo.api.dto.TargetingCondition;
import cloud.eppo.api.dto.Variation;
import cloud.eppo.api.dto.VariationType;
import cloud.eppo.ufc.dto.adapters.EppoModule;

public class FlagConfigResponseDeserializerTest {
  private final ObjectMapper mapper = new ObjectMapper().registerModule(EppoModule.eppoModule());

  @Test
  public void testDeserializePlainText() throws IOException {
    File testUfc = new File("src/test/resources/flags-v1.json");
    FileReader fileReader = new FileReader(testUfc);
    FlagConfigResponse configResponse = mapper.readValue(fileReader, FlagConfigResponse.class);

    assertTrue(configResponse.getFlags().size() >= 13);
    assertTrue(configResponse.getFlags().containsKey("empty_flag"));
    assertTrue(configResponse.getFlags().containsKey("disabled_flag"));
    assertTrue(configResponse.getFlags().containsKey("no_allocations_flag"));
    assertTrue(configResponse.getFlags().containsKey("numeric_flag"));
    assertTrue(configResponse.getFlags().containsKey("invalid-value-flag"));
    assertTrue(configResponse.getFlags().containsKey("kill-switch"));
    assertTrue(configResponse.getFlags().containsKey("semver-test"));
    assertTrue(configResponse.getFlags().containsKey("comparator-operator-test"));
    assertTrue(configResponse.getFlags().containsKey("start-and-end-date-test"));
    assertTrue(configResponse.getFlags().containsKey("null-operator-test"));
    assertTrue(configResponse.getFlags().containsKey("new-user-onboarding"));
    assertTrue(configResponse.getFlags().containsKey("integer-flag"));
    assertTrue(configResponse.getFlags().containsKey("json-config-flag"));

    FlagConfig flagConfig = configResponse.getFlags().get("kill-switch");
    assertNotNull(flagConfig);
    assertEquals(flagConfig.getKey(), "kill-switch");
    assertTrue(flagConfig.isEnabled());
    assertEquals(VariationType.BOOLEAN, flagConfig.getVariationType());

    Map<String, Variation> variations = flagConfig.getVariations();
    assertEquals(2, variations.size());
    Variation onVariation = variations.get("on");
    assertNotNull(onVariation);
    assertEquals("on", onVariation.getKey());
    assertTrue(onVariation.getValue().booleanValue());
    Variation offVariation = variations.get("off");
    assertNotNull(offVariation);
    assertEquals("off", offVariation.getKey());
    assertFalse(offVariation.getValue().booleanValue());

    List<Allocation> allocations = flagConfig.getAllocations();
    assertEquals(3, allocations.size());

    Allocation northAmericaAllocation = allocations.get(0);
    assertEquals("on-for-NA", northAmericaAllocation.getKey());
    assertTrue(northAmericaAllocation.doLog());
    assertEquals(1, northAmericaAllocation.getRules().size());
    TargetingCondition northAmericaCondition =
        northAmericaAllocation.getRules().iterator().next().getConditions().iterator().next();
    assertEquals("country", northAmericaCondition.getAttribute());
    assertEquals(OperatorType.ONE_OF, northAmericaCondition.getOperator());
    List<String> expectedValues = new ArrayList<>();
    expectedValues.add("US");
    expectedValues.add("Canada");
    expectedValues.add("Mexico");
    assertEquals(expectedValues, northAmericaCondition.getValue().stringArrayValue());

    assertEquals(1, northAmericaAllocation.getSplits().size());
    Split northAmericaSplit = northAmericaAllocation.getSplits().iterator().next();
    assertEquals("on", northAmericaSplit.getVariationKey());

    Shard northAmericaShard = northAmericaSplit.getShards().iterator().next();
    assertEquals("some-salt", northAmericaShard.getSalt());

    ShardRange northAmericaRange = northAmericaShard.getRanges().iterator().next();
    assertEquals(0, northAmericaRange.getStart());
    assertEquals(10000, northAmericaRange.getEnd());

    Allocation fiftyPlusAllocation = allocations.get(1);
    assertEquals("on-for-age-50+", fiftyPlusAllocation.getKey());
    assertTrue(fiftyPlusAllocation.doLog());
    assertEquals(1, fiftyPlusAllocation.getRules().size());
    TargetingCondition fiftyPlusCondition =
        fiftyPlusAllocation.getRules().iterator().next().getConditions().iterator().next();
    assertEquals("age", fiftyPlusCondition.getAttribute());
    assertEquals(OperatorType.GREATER_THAN_OR_EQUAL_TO, fiftyPlusCondition.getOperator());
    assertEquals(50, fiftyPlusCondition.getValue().doubleValue(), 0.0);

    assertEquals(1, fiftyPlusAllocation.getSplits().size());
    Split fiftyPlusSplit = fiftyPlusAllocation.getSplits().iterator().next();
    assertEquals("on", fiftyPlusSplit.getVariationKey());

    Shard fiftyPlusShard = fiftyPlusSplit.getShards().iterator().next();
    assertEquals("some-salt", fiftyPlusShard.getSalt());

    ShardRange fiftyPlusRange = fiftyPlusShard.getRanges().iterator().next();
    assertEquals(0, fiftyPlusRange.getStart());
    assertEquals(10000, fiftyPlusRange.getEnd());

    Allocation offForAll = allocations.get(2);
    assertEquals("off-for-all", offForAll.getKey());
    assertTrue(offForAll.doLog());
    assertEquals(0, offForAll.getRules().size());

    assertEquals(1, offForAll.getSplits().size());
    Split offForAllSplit = offForAll.getSplits().iterator().next();
    assertEquals("off", offForAllSplit.getVariationKey());
    assertEquals(0, offForAllSplit.getShards().size());
  }

  @Test
  public void testDeserializeCreatedAt() throws Exception {
    File testUfc = new File("src/test/resources/flags-v1.json");
    FileReader fileReader = new FileReader(testUfc);
    FlagConfigResponse configResponse = mapper.readValue(fileReader, FlagConfigResponse.class);

    // Verify createdAt is parsed correctly
    Date createdAt = configResponse.getCreatedAt();
    assertNotNull(createdAt, "createdAt should be parsed from the JSON");

    // The test file has "createdAt": "2024-04-17T19:40:53.716Z"
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    Date expectedDate = sdf.parse("2024-04-17T19:40:53.716Z");
    assertEquals(expectedDate, createdAt);
  }

  @Test
  public void testDeserializeEnvironmentName() throws IOException {
    // Test with environment.name present (nested structure)
    String jsonWithEnv =
        "{ \"flags\": {}, \"environment\": { \"name\": \"Production\" }, \"createdAt\": \"2024-01-01T00:00:00.000Z\" }";
    FlagConfigResponse configWithEnv = mapper.readValue(jsonWithEnv, FlagConfigResponse.class);
    assertEquals("Production", configWithEnv.getEnvironmentName());

    // Test without environment (should be null)
    String jsonWithoutEnv = "{ \"flags\": {} }";
    FlagConfigResponse configWithoutEnv =
        mapper.readValue(jsonWithoutEnv, FlagConfigResponse.class);
    assertNull(configWithoutEnv.getEnvironmentName());

    // Test with environment object but no name field
    String jsonWithEmptyEnv = "{ \"flags\": {}, \"environment\": {} }";
    FlagConfigResponse configWithEmptyEnv =
        mapper.readValue(jsonWithEmptyEnv, FlagConfigResponse.class);
    assertNull(configWithEmptyEnv.getEnvironmentName());
  }

  @Test
  public void testDeserializeFormat() throws IOException {
    // Test SERVER format
    String serverJson = "{ \"flags\": {}, \"format\": \"SERVER\" }";
    FlagConfigResponse serverConfig = mapper.readValue(serverJson, FlagConfigResponse.class);
    assertEquals(FlagConfigResponse.Format.SERVER, serverConfig.getFormat());

    // Test CLIENT format
    String clientJson = "{ \"flags\": {}, \"format\": \"CLIENT\" }";
    FlagConfigResponse clientConfig = mapper.readValue(clientJson, FlagConfigResponse.class);
    assertEquals(FlagConfigResponse.Format.CLIENT, clientConfig.getFormat());

    // Test default (no format specified) - should default to SERVER
    String noFormatJson = "{ \"flags\": {} }";
    FlagConfigResponse noFormatConfig = mapper.readValue(noFormatJson, FlagConfigResponse.class);
    assertEquals(FlagConfigResponse.Format.SERVER, noFormatConfig.getFormat());
  }

  @Test
  public void testDeserializeNullCreatedAt() throws IOException {
    // Test without createdAt
    String jsonWithoutCreatedAt = "{ \"flags\": {} }";
    FlagConfigResponse config = mapper.readValue(jsonWithoutCreatedAt, FlagConfigResponse.class);
    assertNull(config.getCreatedAt());
  }
}
