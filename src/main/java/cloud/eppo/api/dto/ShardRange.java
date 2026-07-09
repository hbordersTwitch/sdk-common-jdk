package cloud.eppo.api.dto;

import java.io.Serializable;
import java.util.Objects;

public interface ShardRange extends Serializable {

    int getStart();

    int getEnd();

    class Default implements ShardRange {
        private static final long serialVersionUID = 1L;
        private final int start;
        private final int end;

        public Default(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Default that = (Default) o;
            return start == that.start && end == that.end;
        }

        @Override
        public String toString() {
            return "[start: " + start + "| end: " + end + "]";
        }

        @Override
        public int getStart() {
            return start;
        }

        @Override
        public int getEnd() {
            return end;
        }
    }
}
