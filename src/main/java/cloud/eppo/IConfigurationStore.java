package cloud.eppo;

import cloud.eppo.api.Configuration;
import cloud.eppo.api.SerializableEppoConfiguration;

import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * Common interface for extensions of this SDK to support caching and other strategies for
 * persisting configuration data across sessions.
 */
public interface IConfigurationStore<ConfigurationType extends SerializableEppoConfiguration> {
  @NotNull ConfigurationType getConfiguration();

  CompletableFuture<Void> saveConfiguration(ConfigurationType configuration);
}
