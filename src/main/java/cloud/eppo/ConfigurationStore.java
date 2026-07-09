package cloud.eppo;

import cloud.eppo.api.Configuration;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/** Memory-only configuration store. */
public class ConfigurationStore implements IConfigurationStore<Configuration> {

  // this is the fallback value if no configuration is provided (i.e. by fetch or initial config).
  @NotNull private volatile Configuration configuration = Configuration.emptyConfig();

  public ConfigurationStore() {}

  public CompletableFuture<Void> saveConfiguration(@NotNull final Configuration configuration) {
    this.configuration = configuration;
    return CompletableFuture.completedFuture(null);
  }

  @NotNull public Configuration getConfiguration() {
    return configuration;
  }
}
