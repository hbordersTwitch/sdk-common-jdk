package cloud.eppo.api;

import static cloud.eppo.Utils.getMD5Hex;

import cloud.eppo.api.dto.BanditFlagVariation;
import cloud.eppo.api.dto.BanditParameters;
import cloud.eppo.api.dto.BanditParametersResponse;
import cloud.eppo.api.dto.BanditReference;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.FlagConfigResponse;
import cloud.eppo.api.dto.VariationType;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates the Flag Configuration and Bandit parameters in an immutable object with a complete
 * and coherent state.
 *
 * <p>A Builder is used to prepare and then create an immutable data structure containing both flag
 * and bandit configurations. An intermediate step is required in building the configuration to
 * accommodate the as-needed loading of bandit parameters as a network call may not be needed if
 * there are no bandits referenced by the flag configuration.
 *
 * <p>Usage: Building with just flag configuration (obfuscation auto-detected from format):
 *
 * <pre>{@code
 * FlagConfigResponse flagConfig = parser.parseFlagConfig(flagConfigJsonBytes);
 * Configuration config = new Configuration.Builder(flagConfig).build();
 * }</pre>
 *
 * <p>Building with bandits (known configuration):
 *
 * <pre>{@code
 * FlagConfigResponse flagConfig = parser.parseFlagConfig(flagConfigJsonBytes);
 * BanditParametersResponse banditParams = parser.parseBanditParams(banditParamsJsonBytes);
 * Configuration config = new Configuration.Builder(flagConfig)
 *     .banditParameters(banditParams)
 *     .build();
 * }</pre>
 *
 * <p>Conditionally loading bandit models (with or without an existing bandit configuration):
 *
 * <pre>{@code
 * FlagConfigResponse flagConfig = parser.parseFlagConfig(flagConfigJsonBytes);
 * Configuration.Builder configBuilder = new Configuration.Builder(flagConfig)
 *     .banditParametersFromConfig(existingConfig);
 * if (configBuilder.requiresUpdatedBanditModels()) {
 *   BanditParametersResponse banditParams = parser.parseBanditParams(banditParamsJsonBytes);
 *   configBuilder.banditParameters(banditParams);
 * }
 * Configuration config = configBuilder.build();
 * }</pre>
 *
 * <p>Hint: when loading new flag configuration values, set the current bandit models in the builder
 * using {@link Builder#banditParametersFromConfig(Configuration)}, then check {@link
 * Builder#requiresUpdatedBanditModels()}.
 */
public class Configuration implements SerializableEppoConfiguration {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(Configuration.class);
  private final Map<String, BanditReference> banditReferences;
  private final Map<String, FlagConfig> flags;
  private final Map<String, BanditParameters> bandits;
  private final boolean isConfigObfuscated;
  private final String environmentName;
  private final Date configFetchedAt;
  private final Date configPublishedAt;
  @Nullable private final String flagsSnapshotId;

  /** Default visibility for tests. */
  Configuration(
      Map<String, FlagConfig> flags,
      Map<String, BanditReference> banditReferences,
      Map<String, BanditParameters> bandits,
      boolean isConfigObfuscated,
      String environmentName,
      Date configFetchedAt,
      Date configPublishedAt,
      @Nullable String flagsSnapshotId) {
    this.flags = flags;
    this.banditReferences = banditReferences;
    this.bandits = bandits;
    this.isConfigObfuscated = isConfigObfuscated;
    this.environmentName = environmentName;
    this.configFetchedAt = configFetchedAt;
    this.configPublishedAt = configPublishedAt;
    this.flagsSnapshotId = flagsSnapshotId;
  }

  public static Configuration emptyConfig() {
    return new Configuration(
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptyMap(),
        false,
        null,
        null,
        null,
        null);
  }

  @Override
  public String toString() {
    return "Configuration{"
        + "banditReferences="
        + banditReferences
        + ", flags="
        + flags
        + ", bandits="
        + bandits
        + ", isConfigObfuscated="
        + isConfigObfuscated
        + ", environmentName='"
        + environmentName
        + '\''
        + ", configFetchedAt="
        + configFetchedAt
        + ", configPublishedAt="
        + configPublishedAt
        + ", flagsSnapshotId="
        + flagsSnapshotId
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    Configuration that = (Configuration) o;
    return isConfigObfuscated == that.isConfigObfuscated
        && Objects.equals(banditReferences, that.banditReferences)
        && Objects.equals(flags, that.flags)
        && Objects.equals(bandits, that.bandits)
        && Objects.equals(environmentName, that.environmentName)
        && Objects.equals(configFetchedAt, that.configFetchedAt)
        && Objects.equals(configPublishedAt, that.configPublishedAt)
        && Objects.equals(flagsSnapshotId, that.flagsSnapshotId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        banditReferences,
        flags,
        bandits,
        isConfigObfuscated,
        environmentName,
        configFetchedAt,
        configPublishedAt,
        flagsSnapshotId);
  }

  @Override
  public FlagConfig getFlag(String flagKey) {
    String flagKeyForLookup = flagKey;
    if (isConfigObfuscated()) {
      flagKeyForLookup = getMD5Hex(flagKey);
    }

    if (flags == null) {
      log.warn("Request for flag {} before flags have been loaded", flagKey);
      return null;
    } else if (flags.isEmpty()) {
      log.warn("Request for flag {} with empty flags", flagKey);
    }
    return flags.get(flagKeyForLookup);
  }

  /**
   * Returns the Variation Type for the specified flag if it exists, otherwise returns null.
   *
   * @return The flag's variation type or null.
   */
  @Override
  public @Nullable VariationType getFlagType(String flagKey) {
    FlagConfig flag = getFlag(flagKey);
    if (flag == null) {
      return null;
    }
    return flag.getVariationType();
  }

  @Override
  public String banditKeyForVariation(String flagKey, String variationValue) {
    // Note: In practice this double loop should be quite quick as the number of bandits and bandit
    // variations will be small. Should this ever change, we can optimize things.
    for (Map.Entry<String, BanditReference> banditEntry : banditReferences.entrySet()) {
      BanditReference banditReference = banditEntry.getValue();
      for (BanditFlagVariation banditFlagVariation : banditReference.getFlagVariations()) {
        if (banditFlagVariation.getFlagKey().equals(flagKey)
            && banditFlagVariation.getVariationValue().equals(variationValue)) {
          return banditEntry.getKey();
        }
      }
    }
    return null;
  }

  @Override
  public BanditParameters getBanditParameters(String banditKey) {
    return bandits.get(banditKey);
  }

  @Override
  public boolean isConfigObfuscated() {
    return isConfigObfuscated;
  }

  @Override
  public boolean isEmpty() {
    return flags == null || flags.isEmpty();
  }

  @Override
  public Set<String> getFlagKeys() {
    return flags == null ? Collections.emptySet() : flags.keySet();
  }

  @Override
  public String getEnvironmentName() {
    return environmentName;
  }

  @Override
  public Date getConfigFetchedAt() {
    return configFetchedAt;
  }

  @Override
  public Date getConfigPublishedAt() {
    return configPublishedAt;
  }

  /**
   * Returns the snapshot ID for the flags configuration.
   *
   * <p>The snapshot ID is an opaque identifier (typically an HTTP ETag value) that represents a
   * specific version of the flag configuration. This value can be used for caching and conditional
   * requests to avoid re-fetching unchanged configuration data.
   *
   * @return the snapshot ID, or null if not available
   */
  @Override
  @Nullable public String getFlagsSnapshotId() {
    return flagsSnapshotId;
  }

  public static Builder builder(FlagConfigResponse flagConfigResponse) {
    return new Builder(flagConfigResponse);
  }

  /**
   * Builder to create the immutable config object.
   *
   * @see Configuration for usage.
   */
  public static class Builder extends SerializableEppoConfiguration.AbstractBuilder<
          Builder,
          Configuration
  > {
    public Builder(FlagConfigResponse flagConfigResponse) {
      super(Builder.class, flagConfigResponse);
    }

    public Builder(@Nullable FlagConfigResponse flagConfigResponse, boolean isConfigObfuscated) {
      super(Builder.class, flagConfigResponse, isConfigObfuscated);
    }

    @Override
    public Builder banditParametersFromConfig(Configuration currentConfig) {
      if (currentConfig == null || currentConfig.bandits == null) {
        bandits = Collections.emptyMap();
      } else {
        bandits = currentConfig.bandits;
      }
      return this;
    }

    public Configuration build() {
      // Record the time when configuration is built/fetched
      Date configFetchedAt = new Date();
      return new Configuration(
          flags,
          banditReferences,
          bandits,
          isConfigObfuscated,
          environmentName,
          configFetchedAt,
          configPublishedAt,
          flagsSnapshotId);
    }
  }
}
