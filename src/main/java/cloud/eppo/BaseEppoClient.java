package cloud.eppo;

import static cloud.eppo.Constants.DEFAULT_JITTER_INTERVAL_RATIO;
import static cloud.eppo.Constants.DEFAULT_POLLING_INTERVAL_MILLIS;
import static cloud.eppo.Utils.throwIfEmptyOrNull;

import cloud.eppo.api.*;
import cloud.eppo.api.dto.BanditParameters;
import cloud.eppo.api.dto.FlagConfig;
import cloud.eppo.api.dto.VariationType;
import cloud.eppo.cache.AssignmentCacheEntry;
import cloud.eppo.http.EppoConfigurationClient;
import cloud.eppo.http.EppoConfigurationRequestFactory;
import cloud.eppo.logging.Assignment;
import cloud.eppo.logging.AssignmentLogger;
import cloud.eppo.logging.BanditAssignment;
import cloud.eppo.logging.BanditLogger;
import cloud.eppo.parser.ConfigurationParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseEppoClient<
  ConfigurationType extends SerializableEppoConfiguration,
  ConfigurationBuilderType extends SerializableEppoConfiguration.AbstractBuilder<
    ConfigurationBuilderType,
    ConfigurationType
    >,
  JsonFlagType
> {
  private static final Logger log = LoggerFactory.getLogger(BaseEppoClient.class);

  protected final ConfigurationRequestor<ConfigurationType, ConfigurationBuilderType, JsonFlagType> requestor;
  private final IConfigurationStore<ConfigurationType> configurationStore;
  private final AssignmentLogger assignmentLogger;
  private final BanditLogger banditLogger;
  private final String sdkName;
  private final String sdkVersion;
  private boolean isGracefulMode;
  private final IAssignmentCache assignmentCache;
  private final IAssignmentCache banditAssignmentCache;
  private final ConfigurationParser<ConfigurationType, ConfigurationBuilderType, JsonFlagType> configurationParser;
  private Timer pollTimer;

  @Nullable protected CompletableFuture<Boolean> getInitialConfigFuture() {
    return initialConfigFuture;
  }

  private final CompletableFuture<Boolean> initialConfigFuture;

  // It is important that the bandit assignment cache expire with a short-enough TTL to last about
  // one user session.
  // The recommended is 10 minutes (per @Sven)
  protected BaseEppoClient(
      @NotNull String apiKey,
      @NotNull String sdkName,
      @NotNull String sdkVersion,
      @Nullable String apiBaseUrl,
      @Nullable AssignmentLogger assignmentLogger,
      @Nullable BanditLogger banditLogger,
      @NotNull IConfigurationStore<ConfigurationType> configurationStore,
      boolean isGracefulMode,
      boolean expectObfuscatedConfig,
      boolean supportBandits,
      @Nullable CompletableFuture<ConfigurationType> initialConfiguration,
      @Nullable IAssignmentCache assignmentCache,
      @Nullable IAssignmentCache banditAssignmentCache,
      @NotNull ConfigurationParser<ConfigurationType, ConfigurationBuilderType, JsonFlagType> configurationParser,
      @NotNull EppoConfigurationClient configurationClient) {

    if (apiBaseUrl == null) {
      apiBaseUrl = Constants.DEFAULT_BASE_URL;
    }

    this.assignmentCache = assignmentCache;
    this.banditAssignmentCache = banditAssignmentCache;
    this.configurationParser = configurationParser;

    SDKKey sdkKey = new SDKKey(apiKey);
    ApiEndpoints endpointHelper = new ApiEndpoints(sdkKey, apiBaseUrl);
    String effectiveBaseUrl = endpointHelper.getBaseUrl();

    if (configurationStore == null) {
      throw new IllegalArgumentException("ConfigurationStore must not be null");
    }
    this.configurationStore = configurationStore;

    EppoConfigurationRequestFactory requestFactory =
        new EppoConfigurationRequestFactory(
            effectiveBaseUrl, sdkKey.getToken(), sdkName, sdkVersion);

    requestor =
        new ConfigurationRequestor<>(
            this.configurationStore,
            supportBandits,
            configurationParser,
            configurationClient,
            requestFactory);
    initialConfigFuture =
        initialConfiguration != null
            ? requestor.setInitialConfiguration(initialConfiguration)
            : null;

    this.assignmentLogger = assignmentLogger;
    this.banditLogger = banditLogger;
    this.isGracefulMode = isGracefulMode;
    // Save SDK name and version to include in logger metadata
    this.sdkName = sdkName;
    this.sdkVersion = sdkVersion;
  }

  protected void loadConfiguration() {
    try {
      requestor.fetchAndSaveFromRemote();
    } catch (Exception ex) {
      log.error("Encountered Exception while loading configuration", ex);
      if (!isGracefulMode) {
        throw ex;
      }
    }
  }

  protected void stopPolling() {
    if (pollTimer != null) {
      pollTimer.cancel();
    }
  }

  /** Start polling using the default interval and jitter. */
  protected void startPolling() {
    startPolling(DEFAULT_POLLING_INTERVAL_MILLIS);
  }

  /**
   * Start polling using the provided polling interval and default jitter of 10%
   *
   * @param pollingIntervalMs The base number of milliseconds to wait between configuration fetches.
   */
  protected void startPolling(long pollingIntervalMs) {
    startPolling(pollingIntervalMs, pollingIntervalMs / DEFAULT_JITTER_INTERVAL_RATIO);
  }

  /**
   * Start polling using the provided interval and jitter.
   *
   * @param pollingIntervalMs The base number of milliseconds to wait between configuration fetches.
   * @param pollingJitterMs The max number of milliseconds to offset each polling interval. The SDK
   *     selects a random number between 0 and pollingJitterMS to offset the polling interval by.
   */
  protected void startPolling(long pollingIntervalMs, long pollingJitterMs) {
    stopPolling();
    log.debug("Started polling at " + pollingIntervalMs + "," + pollingJitterMs);

    // Set up polling for UFC
    pollTimer = new Timer(true);
    FetchConfigurationTask fetchConfigurationsTask =
        new FetchConfigurationTask(
            () -> {
              log.debug("[Eppo SDK] Polling callback");
              this.loadConfiguration();
            },
            pollTimer,
            pollingIntervalMs,
            pollingJitterMs);

    // We don't want to fetch right away, so we schedule the next fetch.
    // Graceful mode is implicit here because `FetchConfigurationsTask` catches and
    // logs errors without rethrowing.
    fetchConfigurationsTask.scheduleNext();
  }

  protected CompletableFuture<Void> loadConfigurationAsync() {
    CompletableFuture<Void> future = new CompletableFuture<>();

    requestor
        .fetchAndSaveFromRemoteAsync()
        .exceptionally(
            ex -> {
              log.error("Encountered Exception while loading configuration", ex);
              if (!isGracefulMode) {
                future.completeExceptionally(ex);
              }
              return null;
            })
        .thenAccept(future::complete);

    return future;
  }

  /**
   * Top-level assignment details method that evaluates, logs if applicable, and returns the
   * user-facing AssignmentDetails result class. If any error in the evaluation, the result value
   * will be set to the default value.
   */
  private <T> AssignmentDetails<T> getTypedAssignmentWithDetails(
      String flagKey,
      String subjectKey,
      Attributes subjectAttributes,
      T defaultValue,
      VariationType expectedType) {

    EvaluationDetails details =
        evaluateAndMaybeLog(flagKey, subjectKey, subjectAttributes, expectedType);

    T resultValue =
        details.evaluationSuccessful()
            ? details.getVariationValue().unwrap(expectedType, configurationParser::parseJsonValue)
            : defaultValue;
    return new AssignmentDetails<>(resultValue, null, details);
  }

  /**
   * Core evaluation method that handles validation, evaluation, and logging. This consolidates the
   * shared logic between all assignment methods. Returns evaluation details with variationValue set
   * to the result.
   */
  protected EvaluationDetails evaluateAndMaybeLog(
      String flagKey, String subjectKey, Attributes subjectAttributes, VariationType expectedType) {

    throwIfEmptyOrNull(flagKey, "flagKey must not be empty");
    throwIfEmptyOrNull(subjectKey, "subjectKey must not be empty");

    ConfigurationType config = getConfiguration();

    // Check if flag exists
    FlagConfig flag = config.getFlag(flagKey);
    if (flag == null) {
      log.warn("no configuration found for key: {}", flagKey);
      return EvaluationDetails.buildDefault(
          config.getEnvironmentName(),
          config.getConfigFetchedAt(),
          config.getConfigPublishedAt(),
          FlagEvaluationCode.FLAG_UNRECOGNIZED_OR_DISABLED,
          "Unrecognized or disabled flag: " + flagKey,
          null);
    }

    // Check if flag is enabled
    if (!flag.isEnabled()) {
      log.info(
          "no assigned variation because the experiment or feature flag is disabled: {}", flagKey);
      return EvaluationDetails.buildDefault(
          config.getEnvironmentName(),
          config.getConfigFetchedAt(),
          config.getConfigPublishedAt(),
          FlagEvaluationCode.FLAG_UNRECOGNIZED_OR_DISABLED,
          "Unrecognized or disabled flag: " + flagKey,
          null);
    }

    // Check if flag type matches expected type
    if (flag.getVariationType() != expectedType) {
      log.warn(
          "no assigned variation because the flag type doesn't match the requested type: {} has type {}, requested {}",
          flagKey,
          flag.getVariationType(),
          expectedType);
      return EvaluationDetails.buildDefault(
          config.getEnvironmentName(),
          config.getConfigFetchedAt(),
          config.getConfigPublishedAt(),
          FlagEvaluationCode.TYPE_MISMATCH,
          String.format(
              "Flag \"%s\" has type %s, requested %s",
              flagKey, flag.getVariationType(), expectedType),
          null);
    }

    // Evaluate flag with details
    FlagEvaluationResult evaluationResult =
        FlagEvaluator.evaluateFlag(
            flag,
            flagKey,
            subjectKey,
            subjectAttributes,
            config.isConfigObfuscated(),
            config.getEnvironmentName(),
            config.getConfigFetchedAt(),
            config.getConfigPublishedAt());
    EvaluationDetails evaluationDetails = evaluationResult.getEvaluationDetails();

    EppoValue assignedValue =
        evaluationResult.getVariation() != null ? evaluationResult.getVariation().getValue() : null;

    // Check if value type matches expected
    if (assignedValue != null && !valueTypeMatchesExpected(expectedType, assignedValue)) {
      log.warn(
          "no assigned variation because the flag type doesn't match the variation type: {} has type {}, variation value is {}",
          flagKey,
          flag.getVariationType(),
          assignedValue);

      // Update evaluation details with error code but keep the matched allocation and variation
      // info
      String variationKey =
          evaluationResult.getVariation() != null ? evaluationResult.getVariation().getKey() : null;
      String errorDescription =
          String.format(
              "Variation (%s) is configured for type %s, but is set to incompatible value (%s)",
              variationKey, expectedType, assignedValue.doubleValue());

      return EvaluationDetails.builder(evaluationDetails)
          .flagEvaluationCode(
              FlagEvaluationCode
                  .ASSIGNMENT_ERROR) // We use ASSIGNMENT_ERROR for value mismatch as it's a
          // misconfiguration of the flag itself
          .flagEvaluationDescription(errorDescription)
          .variationKey(variationKey)
          .variationValue(assignedValue)
          .build();
    }

    // Log assignment if applicable
    if (assignedValue != null && assignmentLogger != null && evaluationResult.doLog()) {
      try {
        String allocationKey = evaluationResult.getAllocationKey();
        String experimentKey =
            flagKey
                + '-'
                + allocationKey; // Our experiment key is derived by hyphenating the flag key and
        // allocation key
        String variationKey = evaluationResult.getVariation().getKey();
        Map<String, String> extraLogging = evaluationResult.getExtraLogging();
        Map<String, String> metaData = buildLogMetaData(config.isConfigObfuscated());

        Assignment assignment =
            new Assignment(
                experimentKey,
                flagKey,
                allocationKey,
                variationKey,
                subjectKey,
                subjectAttributes,
                extraLogging,
                metaData);

        // Deduplication of assignment logging is possible by providing an `IAssignmentCache`.
        // Default to true, only avoid logging if there's a cache hit.
        boolean logAssignment = true;
        AssignmentCacheEntry cacheEntry = AssignmentCacheEntry.fromVariationAssignment(assignment);
        if (assignmentCache != null) {
          logAssignment = assignmentCache.putIfAbsent(cacheEntry);
        }

        if (logAssignment) {
          assignmentLogger.logAssignment(assignment);
        }

      } catch (Exception e) {
        log.error("Error logging assignment: {}", e.getMessage(), e);
      }
    }

    return evaluationDetails;
  }

  private boolean valueTypeMatchesExpected(VariationType expectedType, EppoValue value) {
    boolean typeMatch;
    switch (expectedType) {
      case BOOLEAN:
        typeMatch = value.isBoolean();
        break;
      case INTEGER:
        typeMatch =
            value.isNumeric()
                // Java has no isInteger check so we check using mod
                && value.doubleValue() % 1 == 0;
        break;
      case NUMERIC:
        typeMatch = value.isNumeric();
        break;
      case STRING:
        typeMatch = value.isString();
        break;
      case JSON:
        typeMatch =
            value.isString()
                // Eppo leaves JSON as a JSON string; to verify it's valid we attempt to parse (via
                // unwrapping)
                && value.unwrap(VariationType.JSON, configurationParser::parseJsonValue) != null;
        break;
      default:
        throw new IllegalArgumentException("Unexpected type for type checking: " + expectedType);
    }

    return typeMatch;
  }

  public boolean getBooleanAssignment(String flagKey, String subjectKey, boolean defaultValue) {
    return this.getBooleanAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public boolean getBooleanAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, boolean defaultValue) {
    return this.getBooleanAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<Boolean> getBooleanAssignmentDetails(
      String flagKey, String subjectKey, boolean defaultValue) {
    return this.getBooleanAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<Boolean> getBooleanAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, boolean defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.BOOLEAN);
    } catch (Exception e) {
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValue)));
    }
  }

  public int getIntegerAssignment(String flagKey, String subjectKey, int defaultValue) {
    return getIntegerAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public int getIntegerAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, int defaultValue) {
    return this.getIntegerAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<Integer> getIntegerAssignmentDetails(
      String flagKey, String subjectKey, int defaultValue) {
    return getIntegerAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<Integer> getIntegerAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, int defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.INTEGER);
    } catch (Exception e) {
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValue)));
    }
  }

  public Double getDoubleAssignment(String flagKey, String subjectKey, double defaultValue) {
    return getDoubleAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public Double getDoubleAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, double defaultValue) {
    return this.getDoubleAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<Double> getDoubleAssignmentDetails(
      String flagKey, String subjectKey, double defaultValue) {
    return getDoubleAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<Double> getDoubleAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, double defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.NUMERIC);
    } catch (Exception e) {
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValue)));
    }
  }

  public String getStringAssignment(String flagKey, String subjectKey, String defaultValue) {
    return this.getStringAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public String getStringAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, String defaultValue) {
    return this.getStringAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<String> getStringAssignmentDetails(
      String flagKey, String subjectKey, String defaultValue) {
    return this.getStringAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<String> getStringAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, String defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.STRING);
    } catch (Exception e) {
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValue)));
    }
  }

  public JsonFlagType getJSONAssignment(
      String flagKey, String subjectKey, JsonFlagType defaultValue) {
    return getJSONAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public JsonFlagType getJSONAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, JsonFlagType defaultValue) {
    return this.getJSONAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<JsonFlagType> getJSONAssignmentDetails(
      String flagKey, String subjectKey, JsonFlagType defaultValue) {
    return this.getJSONAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<JsonFlagType> getJSONAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, JsonFlagType defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.JSON);
    } catch (Exception e) {
      String defaultValueString = defaultValue != null ? defaultValue.toString() : null;
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValueString)));
    }
  }

  public String getJSONStringAssignment(String flagKey, String subjectKey, String defaultValue) {
    return this.getJSONStringAssignment(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public String getJSONStringAssignment(
      String flagKey, String subjectKey, Attributes subjectAttributes, String defaultValue) {
    return this.getJSONStringAssignmentDetails(flagKey, subjectKey, subjectAttributes, defaultValue)
        .getVariation();
  }

  public AssignmentDetails<String> getJSONStringAssignmentDetails(
      String flagKey, String subjectKey, String defaultValue) {
    return this.getJSONStringAssignmentDetails(flagKey, subjectKey, new Attributes(), defaultValue);
  }

  public AssignmentDetails<String> getJSONStringAssignmentDetails(
      String flagKey, String subjectKey, Attributes subjectAttributes, String defaultValue) {
    try {
      return this.getTypedAssignmentWithDetails(
          flagKey, subjectKey, subjectAttributes, defaultValue, VariationType.JSON);
    } catch (Exception e) {
      return new AssignmentDetails<>(
          throwIfNotGraceful(e, defaultValue),
          null,
          EvaluationDetails.buildDefault(
              getConfiguration().getEnvironmentName(),
              getConfiguration().getConfigFetchedAt(),
              getConfiguration().getConfigPublishedAt(),
              FlagEvaluationCode.ASSIGNMENT_ERROR,
              e.getMessage(),
              EppoValue.valueOf(defaultValue)));
    }
  }

  public BanditResult getBanditAction(
      String flagKey,
      String subjectKey,
      DiscriminableAttributes subjectAttributes,
      Actions actions,
      String defaultValue) {
    try {
      AssignmentDetails<String> details =
          getBanditActionDetails(flagKey, subjectKey, subjectAttributes, actions, defaultValue);
      return new BanditResult(details.getVariation(), details.getAction());
    } catch (Exception e) {
      return throwIfNotGraceful(e, new BanditResult(defaultValue, null));
    }
  }

  /**
   * Returns bandit action assignment with detailed evaluation information including flag evaluation
   * details and bandit action selection.
   */
  public AssignmentDetails<String> getBanditActionDetails(
      String flagKey,
      String subjectKey,
      DiscriminableAttributes subjectAttributes,
      Actions actions,
      String defaultValue) {
    final ConfigurationType config = getConfiguration();
    try {
      // Get detailed flag assignment
      AssignmentDetails<String> flagDetails =
          getStringAssignmentDetails(
              flagKey, subjectKey, subjectAttributes.getAllAttributes(), defaultValue);

      String assignedVariation = flagDetails.getVariation();
      String assignedAction = null;

      // If we got a variation, check for bandit
      String banditKey = config.banditKeyForVariation(flagKey, assignedVariation);

      // If variation is a bandit but no actions supplied, return variation with null action
      // This matches Python/JS SDK behavior: "if no actions are given, return the variation with no
      // action"
      if (banditKey != null && actions.isEmpty()) {
        EvaluationDetails noActionsDetails =
            EvaluationDetails.builder(flagDetails.getEvaluationDetails())
                .flagEvaluationCode(FlagEvaluationCode.NO_ACTIONS_SUPPLIED_FOR_BANDIT)
                .flagEvaluationDescription("No actions supplied for bandit evaluation")
                .banditKey(banditKey)
                .banditAction(null)
                .build();
        return new AssignmentDetails<>(assignedVariation, null, noActionsDetails);
      }

      if (banditKey != null) {
        try {
          BanditParameters banditParameters = config.getBanditParameters(banditKey);
          if (banditParameters == null) {
            throw new RuntimeException("Bandit parameters not found for bandit key: " + banditKey);
          }
          BanditEvaluationResult banditResult =
              BanditEvaluator.evaluateBandit(
                  flagKey, subjectKey, subjectAttributes, actions, banditParameters.getModelData());

          assignedAction = banditResult.getActionKey();

          // Log bandit assignment if needed
          if (banditLogger != null) {
            try {
              BanditAssignment banditAssignment =
                  new BanditAssignment(
                      flagKey,
                      banditKey,
                      subjectKey,
                      banditResult.getActionKey(),
                      banditResult.getActionWeight(),
                      banditResult.getOptimalityGap(),
                      banditParameters.getModelVersion(),
                      subjectAttributes.getNumericAttributes(),
                      subjectAttributes.getCategoricalAttributes(),
                      banditResult.getActionAttributes().getNumericAttributes(),
                      banditResult.getActionAttributes().getCategoricalAttributes(),
                      buildLogMetaData(config.isConfigObfuscated()));

              boolean logBanditAssignment = true;
              AssignmentCacheEntry cacheEntry =
                  AssignmentCacheEntry.fromBanditAssignment(banditAssignment);
              if (banditAssignmentCache != null && banditAssignmentCache.hasEntry(cacheEntry)) {
                logBanditAssignment = false;
              }

              if (logBanditAssignment) {
                banditLogger.logBanditAssignment(banditAssignment);
                if (banditAssignmentCache != null) {
                  banditAssignmentCache.put(cacheEntry);
                }
              }
            } catch (Exception e) {
              log.warn("Error logging bandit assignment: {}", e.getMessage(), e);
            }
          }

          // Update evaluation details to include bandit information
          EvaluationDetails updatedDetails =
              EvaluationDetails.builder(flagDetails.getEvaluationDetails())
                  .banditKey(banditKey)
                  .banditAction(assignedAction)
                  .build();

          return new AssignmentDetails<>(assignedVariation, assignedAction, updatedDetails);
        } catch (Exception banditError) {
          // Bandit evaluation failed - respect graceful mode setting
          log.warn(
              "Bandit evaluation failed for flag {}: {}",
              flagKey,
              banditError.getMessage(),
              banditError);

          // If graceful mode is off, throw the exception
          if (!isGracefulMode) {
            throw new RuntimeException(banditError);
          }

          // In graceful mode, return flag details with BANDIT_ERROR code
          EvaluationDetails banditErrorDetails =
              EvaluationDetails.builder(flagDetails.getEvaluationDetails())
                  .flagEvaluationCode(FlagEvaluationCode.BANDIT_ERROR)
                  .flagEvaluationDescription(
                      "Bandit evaluation failed: " + banditError.getMessage())
                  .banditKey(banditKey)
                  .banditAction(null)
                  .build();
          return new AssignmentDetails<>(assignedVariation, null, banditErrorDetails);
        }
      }

      // No bandit - return flag details as-is
      return flagDetails;
    } catch (Exception e) {
      AssignmentDetails<String> errorDetails =
          new AssignmentDetails<>(
              defaultValue,
              null,
              EvaluationDetails.buildDefault(
                  config.getEnvironmentName(),
                  config.getConfigFetchedAt(),
                  config.getConfigPublishedAt(),
                  FlagEvaluationCode.ASSIGNMENT_ERROR,
                  e.getMessage(),
                  EppoValue.valueOf(defaultValue)));
      return throwIfNotGraceful(e, errorDetails);
    }
  }

  private Map<String, String> buildLogMetaData(boolean isConfigObfuscated) {
    HashMap<String, String> metaData = new HashMap<>();
    metaData.put("obfuscated", Boolean.valueOf(isConfigObfuscated).toString());
    metaData.put("sdkLanguage", sdkName);
    metaData.put("sdkLibVersion", sdkVersion);
    return metaData;
  }

  protected <T> T throwIfNotGraceful(Exception e, T defaultValue) {
    if (this.isGracefulMode) {
      log.info("error getting assignment value: {}", e.getMessage());
      return defaultValue;
    }
    throw new RuntimeException(e);
  }

  public void setIsGracefulFailureMode(boolean isGracefulFailureMode) {
    this.isGracefulMode = isGracefulFailureMode;
  }

  /**
   * Subscribe to changes to the configuration.
   *
   * @param callback A function to be executed when the configuration changes.
   * @return a Runnable which, when called unsubscribes the callback from configuration change
   *     events.
   */
  public Runnable onConfigurationChange(Consumer<ConfigurationType> callback) {
    return requestor.onConfigurationChange(callback);
  }

  /**
   * Unsubscribe from configuration change notifications.
   *
   * @param callback The callback to unsubscribe
   * @return true if the callback was found and removed, false otherwise
   */
  public boolean unsubscribeFromConfigurationChange(Consumer<ConfigurationType> callback) {
    return requestor.unsubscribeFromConfigurationChange(callback);
  }

  /**
   * Returns the configuration object used by the EppoClient for assignment and bandit evaluation.
   *
   * <p>The configuration object is for debugging (inspect the loaded config) and other advanced use
   * cases where flag metadata or a list of flag keys, for example, is required.
   *
   * <p>It is not recommended to use the list of keys to preload assignments as assignment
   * computation also logs its use which will affect your metrics.
   *
   * @see <a href="https://docs.geteppo.com/sdks/best-practices/where-to-assign/">Where To
   *     Assign</a> for more details.
   */
  public ConfigurationType getConfiguration() {
    return configurationStore.getConfiguration();
  }
}
