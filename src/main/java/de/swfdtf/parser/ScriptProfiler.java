package de.swfdtf.parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight execution profiler for {@code .vjava} script labels and statements.
 *
 * <p>Accumulates hit counts, total duration, average duration, and peak duration in nanoseconds
 * per script label, supporting timed execution wrappers, hotspot queries, and formatted
 * diagnostic reporting.</p>
 */
public final class ScriptProfiler {

    private static final Comparator<LabelProfile> HOTTEST_FIRST = Comparator
            .comparingLong(LabelProfile::totalNanos).reversed()
            .thenComparing(Comparator.comparingLong(LabelProfile::maxNanos).reversed())
            .thenComparing(Comparator.comparingLong(LabelProfile::hitCount).reversed())
            .thenComparing(LabelProfile::label);

    /**
     * Aggregated execution profile for a single script label.
     *
     * @param label      script label name
     * @param hitCount   number of recorded executions
     * @param totalNanos cumulative execution time in nanoseconds
     * @param maxNanos   maximum single execution time in nanoseconds
     */
    public record LabelProfile(String label, long hitCount, long totalNanos, long maxNanos) {

        /**
         * Validates profile metrics.
         */
        public LabelProfile {
            Objects.requireNonNull(label, "label must not be null");
            if (hitCount < 0) {
                throw new IllegalArgumentException("hitCount must not be negative");
            }
            if (totalNanos < 0) {
                throw new IllegalArgumentException("totalNanos must not be negative");
            }
            if (maxNanos < 0) {
                throw new IllegalArgumentException("maxNanos must not be negative");
            }
        }

        /**
         * Returns the mean execution duration per hit in nanoseconds.
         *
         * @return average duration in nanoseconds, or {@code 0.0} if {@code hitCount == 0}
         */
        public double averageNanos() {
            return hitCount == 0 ? 0.0 : (double) totalNanos / hitCount;
        }

        /**
         * Returns the mean execution duration per hit in milliseconds.
         *
         * @return average duration in milliseconds, or {@code 0.0} if {@code hitCount == 0}
         */
        public double averageMillis() {
            return averageNanos() / 1_000_000.0;
        }

        /**
         * Returns the cumulative execution duration in milliseconds.
         *
         * @return total duration in milliseconds
         */
        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }
    }

    private static final class MutableAccumulator {
        private long hitCount;
        private long totalNanos;
        private long maxNanos;

        private void record(long durationNanos) {
            hitCount++;
            totalNanos += durationNanos;
            if (durationNanos > maxNanos) {
                maxNanos = durationNanos;
            }
        }

        private LabelProfile toProfile(String label) {
            return new LabelProfile(label, hitCount, totalNanos, maxNanos);
        }
    }

    private final Map<String, MutableAccumulator> labelAccumulators = new LinkedHashMap<>();

    /**
     * Creates an empty {@code ScriptProfiler}.
     */
    public ScriptProfiler() {
    }

    /**
     * Records a single measured statement execution under {@code label}.
     *
     * @param label          script label name (must not be {@code null} or blank)
     * @param statementIndex 0-based statement index within the label or block
     * @param durationNanos  measured execution duration in nanoseconds (must be {@code >= 0})
     */
    public synchronized void recordExecution(String label, int statementIndex, long durationNanos) {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        labelAccumulators
                .computeIfAbsent(label, ignored -> new MutableAccumulator())
                .record(durationNanos);
    }

    /**
     * Executes {@code task} and records its elapsed wall-clock nanoseconds under {@code label}.
     *
     * <p>Timing is recorded in a {@code finally} block so exceptions thrown by {@code task}
     * still contribute their elapsed duration.</p>
     *
     * @param label          script label name
     * @param statementIndex 0-based statement index within the label or block
     * @param task           executable unit to measure
     */
    public void recordTimed(String label, int statementIndex, Runnable task) {
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        Objects.requireNonNull(task, "task must not be null");
        long startNanos = System.nanoTime();
        try {
            task.run();
        } finally {
            long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
            recordExecution(label, statementIndex, elapsedNanos);
        }
    }

    /**
     * Looks up the aggregated profile for {@code label}, if any executions were recorded.
     *
     * @param label script label name
     * @return optional {@link LabelProfile}
     */
    public synchronized Optional<LabelProfile> findLabelProfile(String label) {
        Objects.requireNonNull(label, "label must not be null");
        MutableAccumulator acc = labelAccumulators.get(label);
        return acc != null ? Optional.of(acc.toProfile(label)) : Optional.empty();
    }

    /**
     * Returns the label with the highest cumulative execution time.
     *
     * @return optional hottest {@link LabelProfile}, or empty if no executions were recorded
     */
    public synchronized Optional<LabelProfile> hottestLabel() {
        return labelAccumulators.entrySet().stream()
                .map(entry -> entry.getValue().toProfile(entry.getKey()))
                .min(HOTTEST_FIRST);
    }

    /**
     * Returns up to {@code limit} label profiles sorted descending by {@code totalNanos}.
     *
     * @param limit maximum number of entries to return (must be {@code >= 0})
     * @return unmodifiable list of top {@link LabelProfile} records
     */
    public synchronized List<LabelProfile> topLabels(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        if (limit == 0 || labelAccumulators.isEmpty()) {
            return List.of();
        }
        List<LabelProfile> sorted = new ArrayList<>(labelAccumulators.size());
        for (Map.Entry<String, MutableAccumulator> entry : labelAccumulators.entrySet()) {
            sorted.add(entry.getValue().toProfile(entry.getKey()));
        }
        sorted.sort(HOTTEST_FIRST);
        int count = Math.min(limit, sorted.size());
        return List.copyOf(sorted.subList(0, count));
    }

    /**
     * Formats a deterministic multi-line diagnostic report sorted by total time descending.
     *
     * @return formatted profiler summary
     */
    public synchronized String formatReport() {
        if (labelAccumulators.isEmpty()) {
            return "ScriptProfiler Report: (no executions recorded)";
        }
        List<LabelProfile> profiles = topLabels(labelAccumulators.size());
        long totalHits = 0L;
        long grandTotalNanos = 0L;
        for (LabelProfile profile : profiles) {
            totalHits += profile.hitCount();
            grandTotalNanos += profile.totalNanos();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                Locale.ROOT,
                "ScriptProfiler Report (labels=%d, hits=%d, total=%.3f ms):%n",
                profiles.size(),
                totalHits,
                grandTotalNanos / 1_000_000.0
        ));
        for (LabelProfile profile : profiles) {
            sb.append(String.format(
                    Locale.ROOT,
                    "  - %s: hits=%d, total=%.3f ms, avg=%.4f ms, max=%.4f ms%n",
                    profile.label(),
                    profile.hitCount(),
                    profile.totalMillis(),
                    profile.averageMillis(),
                    profile.maxNanos() / 1_000_000.0
            ));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Clears all recorded label execution statistics.
     */
    public synchronized void reset() {
        labelAccumulators.clear();
    }
}
