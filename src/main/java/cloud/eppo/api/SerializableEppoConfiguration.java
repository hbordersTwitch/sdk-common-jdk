package cloud.eppo.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import cloud.eppo.api.dto.BanditParameters;
import cloud.eppo.api.dto.BanditParametersResponse;
import cloud.eppo.api.dto.BanditReference;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.FlagConfigResponse;
import cloud.eppo.api.dto.VariationType;

public interface SerializableEppoConfiguration extends Serializable {
  FlagConfig getFlag(String flagKey);
  @Nullable VariationType getFlagType(String flagKey);
  String getEnvironmentName();
  Date getConfigFetchedAt();
  Date getConfigPublishedAt();
  boolean isConfigObfuscated();
  String banditKeyForVariation(String flagKey, String variationValue);
  BanditParameters getBanditParameters(String banditKey);
  boolean isEmpty();
  String getFlagsSnapshotId();
  Set<String> getFlagKeys();

  /**
   * Builder to create the immutable config object.
   *
   * @see Configuration for usage.
   */
  abstract class AbstractBuilder<
            BuilderType extends AbstractBuilder<
                    BuilderType,
                    ConfigurationType
                    >,
            ConfigurationType extends SerializableEppoConfiguration
            > {
    private static final Logger log = LoggerFactory.getLogger(SerializableEppoConfiguration.class);
    protected final Class<BuilderType> selfClass;
    protected final boolean isConfigObfuscated;
    protected final Map<String, FlagConfig> flags;
    protected final Map<String, BanditReference> banditReferences;
    protected Map<String, BanditParameters> bandits = Collections.emptyMap();
    protected final String environmentName;
    protected final Date configPublishedAt;
    @Nullable protected String flagsSnapshotId;

    /**
     * Builder to create the immutable config object.
     * Abstract because different JSON parsing implementations will create different
     * SerializableEppoConfiguration types
     * @param selfClass the AbstractBuilder subclass. Necessary for preserving types
     *                  when chaining method calls. Subclasses should only expose this
     *                  parameter if they support subclassing themselves.
     */
    protected AbstractBuilder(@NotNull Class<BuilderType> selfClass, FlagConfigResponse flagConfigResponse) {
      this(selfClass, flagConfigResponse, flagConfigResponse.getFormat() == FlagConfigResponse.Format.CLIENT);
    }

    /**
     * Builder to create the immutable config object.
     * Abstract because different JSON parsing implementations will create different
     * SerializableEppoConfiguration types
     * @param selfClass the AbstractBuilder subclass. Necessary for preserving types
     *                  when chaining method calls. Subclasses should only expose this
     *                  parameter if they support subclassing themselves.
     */
    protected AbstractBuilder(@NotNull Class<BuilderType> selfClass, @Nullable FlagConfigResponse flagConfigResponse, boolean isConfigObfuscated) {
      this.selfClass = selfClass;
      this.isConfigObfuscated = isConfigObfuscated;
      if (flagConfigResponse == null || flagConfigResponse.getFlags() == null) {
        log.warn("'flags' map missing in flag definition JSON");
        flags = Collections.emptyMap();
        banditReferences = Collections.emptyMap();
        environmentName = null;
        configPublishedAt = null;
      } else {
        flags = Collections.unmodifiableMap(flagConfigResponse.getFlags());
        banditReferences = Collections.unmodifiableMap(flagConfigResponse.getBanditReferences());

        // Extract environment name and published at timestamp from the response
        environmentName = flagConfigResponse.getEnvironmentName();
        configPublishedAt = flagConfigResponse.getCreatedAt();

        log.debug("Loaded {} flag definitions from flag definition JSON", flags.size());
      }
    }

    public boolean requiresUpdatedBanditModels() {
      Set<String> neededModelVersions = referencedBanditModelVersion();
      return !loadedBanditModelVersions().containsAll(neededModelVersions);
    }

    public Set<String> loadedBanditModelVersions() {
      return bandits.values().stream()
          .map(BanditParameters::getModelVersion)
          .collect(Collectors.toSet());
    }

    public Set<String> referencedBanditModelVersion() {
      return banditReferences.values().stream()
          .map(BanditReference::getModelVersion)
          .collect(Collectors.toSet());
    }

    public abstract BuilderType banditParametersFromConfig(ConfigurationType currentConfig);

    public BuilderType banditParameters(BanditParametersResponse banditParametersResponse) {
      if (banditParametersResponse == null || banditParametersResponse.getBandits() == null) {
        bandits = Collections.emptyMap();
        return selfClass.cast(this);
      }
      bandits = Collections.unmodifiableMap(banditParametersResponse.getBandits());
      return selfClass.cast(this);
    }

    public BuilderType flagsSnapshotId(@Nullable String flagsSnapshotId) {
      this.flagsSnapshotId = flagsSnapshotId;
      return selfClass.cast(this);
    }

    public abstract ConfigurationType build();
  }
}
