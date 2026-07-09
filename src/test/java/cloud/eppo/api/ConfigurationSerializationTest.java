package cloud.eppo.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.eppo.JacksonConfigurationParser;
import cloud.eppo.api.dto.BanditParametersResponse;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.FlagConfigResponse;
import cloud.eppo.parser.ConfigurationParser;
import java.io.*;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

/** Tests to verify that Configuration and its nested types can be serialized and deserialized. */
public class ConfigurationSerializationTest {

  private static final File flagsFile = new File("src/test/resources/shared/ufc/flags-v1.json");
  private static final File banditFlagsFile =
      new File("src/test/resources/shared/ufc/bandit-flags-v1.json");
  private static final File banditModelsFile =
      new File("src/test/resources/shared/ufc/bandit-models-v1.json");

  private static final ConfigurationParser<Configuration, Configuration.Builder, JsonNode> parser = new JacksonConfigurationParser();

  @Test
  public void testConfigurationSerializesAndDeserializes() throws Exception {
    // Load configuration from test resources
    byte[] flagsJson = FileUtils.readFileToByteArray(flagsFile);
    FlagConfigResponse flagConfigResponse = parser.parseFlagConfig(flagsJson);
    Configuration original = Configuration.builder(flagConfigResponse).build();

    // Serialize to bytes
    byte[] serialized = serializeToBytes(original);

    // Deserialize back
    Configuration deserialized = deserializeFromBytes(serialized);

    // Verify the deserialized configuration matches the original
    assertNotNull(deserialized);
    assertEquals(original.isConfigObfuscated(), deserialized.isConfigObfuscated());
    assertEquals(original.getEnvironmentName(), deserialized.getEnvironmentName());
    assertEquals(original.getFlagKeys(), deserialized.getFlagKeys());

    // Verify specific flags can be retrieved and their properties match
    assertFlagPropertiesMatch(original, deserialized, "numeric_flag");
    assertFlagPropertiesMatch(original, deserialized, "empty_flag");
    assertFlagPropertiesMatch(original, deserialized, "no_allocations_flag"); // JSON-valued flag
    assertFlagPropertiesMatch(original, deserialized, "disabled_flag");
  }

  @Test
  public void testConfigurationWithBanditsSerializesAndDeserializes() throws Exception {
    // Load bandit configuration from test resources
    byte[] banditFlagsJson = FileUtils.readFileToByteArray(banditFlagsFile);
    byte[] banditModelsJson = FileUtils.readFileToByteArray(banditModelsFile);

    FlagConfigResponse flagConfigResponse = parser.parseFlagConfig(banditFlagsJson);
    BanditParametersResponse banditParametersResponse = parser.parseBanditParams(banditModelsJson);

    Configuration original =
        Configuration.builder(flagConfigResponse)
            .banditParameters(banditParametersResponse)
            .build();

    // Serialize to bytes
    byte[] serialized = serializeToBytes(original);

    // Deserialize back
    Configuration deserialized = deserializeFromBytes(serialized);

    // Verify the deserialized configuration matches the original
    assertNotNull(deserialized);
    assertEquals(original.isConfigObfuscated(), deserialized.isConfigObfuscated());
    assertEquals(original.getFlagKeys(), deserialized.getFlagKeys());

    // Verify bandit parameters are preserved
    assertNotNull(deserialized.getBanditParameters("cold_start_bandit"));
    assertEquals(
        original.getBanditParameters("cold_start_bandit").getModelVersion(),
        deserialized.getBanditParameters("cold_start_bandit").getModelVersion());
  }

  @Test
  public void testEmptyConfigurationSerializesAndDeserializes() throws Exception {
    Configuration original = Configuration.emptyConfig();

    // Serialize to bytes
    byte[] serialized = serializeToBytes(original);

    // Deserialize back
    Configuration deserialized = deserializeFromBytes(serialized);

    // Verify the deserialized configuration matches the original
    assertNotNull(deserialized);
    assertTrue(deserialized.isEmpty());
    assertEquals(original.isConfigObfuscated(), deserialized.isConfigObfuscated());
  }

  @Test
  public void testObfuscatedConfigurationSerializesAndDeserializes() throws Exception {
    // Load obfuscated configuration
    File obfuscatedFile = new File("src/test/resources/shared/ufc/flags-v1-obfuscated.json");
    byte[] flagsJson = FileUtils.readFileToByteArray(obfuscatedFile);
    FlagConfigResponse flagConfigResponse = parser.parseFlagConfig(flagsJson);
    Configuration original = Configuration.builder(flagConfigResponse).build();

    // Serialize to bytes
    byte[] serialized = serializeToBytes(original);

    // Deserialize back
    Configuration deserialized = deserializeFromBytes(serialized);

    // Verify the deserialized configuration matches the original
    assertNotNull(deserialized);
    assertTrue(deserialized.isConfigObfuscated());
    assertEquals(original.getFlagKeys(), deserialized.getFlagKeys());
  }

  private byte[] serializeToBytes(Configuration config) throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(config);
      return baos.toByteArray();
    }
  }

  private Configuration deserializeFromBytes(byte[] bytes)
      throws IOException, ClassNotFoundException {
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais)) {
      return (Configuration) ois.readObject();
    }
  }

  /**
   * Helper method to assert that flag properties match between original and deserialized
   * configurations.
   */
  private void assertFlagPropertiesMatch(
      Configuration original, Configuration deserialized, String flagKey) {
    FlagConfig originalFlag = original.getFlag(flagKey);
    FlagConfig deserializedFlag = deserialized.getFlag(flagKey);

    assertNotNull(originalFlag, "Original flag " + flagKey + " should exist");
    assertNotNull(deserializedFlag, "Deserialized flag " + flagKey + " should exist");

    assertEquals(
        originalFlag.getKey(), deserializedFlag.getKey(), "Flag key should match for " + flagKey);
    assertEquals(
        originalFlag.isEnabled(),
        deserializedFlag.isEnabled(),
        "Flag enabled status should match for " + flagKey);
    assertEquals(
        originalFlag.getVariationType(),
        deserializedFlag.getVariationType(),
        "Flag variation type should match for " + flagKey);
    assertEquals(
        originalFlag.getTotalShards(),
        deserializedFlag.getTotalShards(),
        "Flag total shards should match for " + flagKey);
    assertEquals(
        originalFlag.getVariations().size(),
        deserializedFlag.getVariations().size(),
        "Flag variations count should match for " + flagKey);
    assertEquals(
        originalFlag.getAllocations().size(),
        deserializedFlag.getAllocations().size(),
        "Flag allocations count should match for " + flagKey);
  }

  /**
   * Comprehensive test that verifies all flags from test data are properly serialized and
   * deserialized with their full properties intact. This ensures that serialization preserves all
   * the necessary details for proper flag evaluation.
   */
  @Test
  public void testSerializedConfigurationComprehensiveFlags() throws Exception {
    // Load configuration from test resources
    byte[] flagsJson = FileUtils.readFileToByteArray(flagsFile);
    FlagConfigResponse flagConfigResponse = parser.parseFlagConfig(flagsJson);
    Configuration original = Configuration.builder(flagConfigResponse).build();

    // Serialize and deserialize
    byte[] serialized = serializeToBytes(original);
    Configuration deserialized = deserializeFromBytes(serialized);

    // Verify all flags are present and fully preserved
    assertEquals(
        original.getFlagKeys().size(),
        deserialized.getFlagKeys().size(),
        "Number of flags should match");

    // Verify each flag's properties are fully preserved
    for (String flagKey : original.getFlagKeys()) {
      FlagConfig originalFlag = original.getFlag(flagKey);
      FlagConfig deserializedFlag = deserialized.getFlag(flagKey);

      assertNotNull(
          deserializedFlag, "Flag " + flagKey + " should be present after deserialization");

      // Deep equality check
      assertEquals(
          originalFlag.getKey(), deserializedFlag.getKey(), "Flag key should match for " + flagKey);
      assertEquals(
          originalFlag.isEnabled(),
          deserializedFlag.isEnabled(),
          "Flag enabled status should match for " + flagKey);
      assertEquals(
          originalFlag.getVariationType(),
          deserializedFlag.getVariationType(),
          "Flag variation type should match for " + flagKey);
      assertEquals(
          originalFlag.getTotalShards(),
          deserializedFlag.getTotalShards(),
          "Flag total shards should match for " + flagKey);

      // Verify variations are preserved
      assertEquals(
          originalFlag.getVariations().keySet(),
          deserializedFlag.getVariations().keySet(),
          "Variation keys should match for " + flagKey);

      // Verify allocations are preserved
      assertEquals(
          originalFlag.getAllocations().size(),
          deserializedFlag.getAllocations().size(),
          "Allocations count should match for " + flagKey);

      // Verify using equals method which does deep comparison
      assertEquals(originalFlag, deserializedFlag, "Flags should be deeply equal for " + flagKey);
    }
  }
}
