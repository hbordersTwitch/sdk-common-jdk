package cloud.eppo.parser;

import cloud.eppo.api.SerializableEppoConfiguration;
import cloud.eppo.api.dto.BanditParametersResponse;
import cloud.eppo.api.dto.FlagConfigResponse;
import org.jetbrains.annotations.NotNull;

/**
 * Defines the contract for parsing configuration JSON responses.
 *
 * <p>Implementations of this interface handle deserialization of flag configuration and bandit
 * parameters from raw JSON bytes. The SDK includes a default implementation using Jackson (in the
 * eppo-sdk-common module), but users can supply custom implementations to accommodate specialized
 * needs.
 */
public interface ConfigurationParser<
  ConfigurationType extends SerializableEppoConfiguration,
  ConfigurationBuilderType extends SerializableEppoConfiguration.AbstractBuilder<
    ConfigurationBuilderType,
    ConfigurationType
  >,
  JSONFlagType
> {

  /**
   * Parses raw flag configuration JSON bytes.
   *
   * @param flagConfigJson raw JSON bytes for flag configuration
   * @return parsed FlagConfigResponse containing flags, bandit references, format, etc.
   * @throws ConfigurationParseException if parsing fails
   */
  @NotNull FlagConfigResponse parseFlagConfig(@NotNull byte[] flagConfigJson)
      throws ConfigurationParseException;

  /**
   * Parses raw bandit parameters JSON bytes.
   *
   * @param banditParamsJson raw JSON bytes for bandit parameters
   * @return parsed BanditParametersResponse containing bandit models
   * @throws ConfigurationParseException if parsing fails
   */
  @NotNull BanditParametersResponse parseBanditParams(@NotNull byte[] banditParamsJson)
      throws ConfigurationParseException;

  /**
   * Unwraps a JSON value to the appropriate JSONFlagType.
   *
   * @param jsonValue the encoded JSON value
   * @return the parsed JSON value
   * @throws ConfigurationParseException if unwrapping fails
   */
  @NotNull JSONFlagType parseJsonValue(@NotNull String jsonValue) throws ConfigurationParseException;

  /**
   * @see SerializableEppoConfiguration.AbstractBuilder
   */
  @NotNull ConfigurationBuilderType configurationBuilder(@NotNull FlagConfigResponse flagConfigResponse);

  /**
   * @see SerializableEppoConfiguration.AbstractBuilder
   */
  @NotNull ConfigurationBuilderType configurationBuilder(@NotNull FlagConfigResponse flagConfigResponse, boolean isConfigObfuscated);
}
