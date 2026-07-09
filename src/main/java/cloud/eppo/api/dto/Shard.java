package cloud.eppo.api.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public interface Shard extends Serializable {
  @NotNull String getSalt();

  @NotNull Set<ShardRange> getRanges();

  class Default implements Shard {
    private static final long serialVersionUID = 1L;
    private final @NotNull String salt;
    private final @NotNull Set<ShardRange> ranges;

    public Default(@NotNull String salt, @Nullable Set<ShardRange> ranges) {
      this.salt = salt;
      this.ranges =
          ranges == null
              ? Collections.emptySet()
              : Collections.unmodifiableSet(new HashSet<>(ranges));
    }

    @Override
    public String toString() {
      return "Shard{" + "salt='" + salt + '\'' + ", ranges=" + ranges + '}';
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;
      Shard shard = (Shard) o;
      return Objects.equals(salt, shard.getSalt()) && Objects.equals(ranges, shard.getRanges());
    }

    @Override
    public int hashCode() {
      return Objects.hash(salt, ranges);
    }

    @Override
    @NotNull public String getSalt() {
      return salt;
    }

    @Override
    @NotNull public Set<ShardRange> getRanges() {
      return ranges;
    }
  }
}
