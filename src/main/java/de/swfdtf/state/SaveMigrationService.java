package de.swfdtf.state;

import de.swfdtf.state.SaveSlotData.SaveMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates ordered, validated schema migrations across {@link SaveMigrationStep}s for
 * {@link SaveSlotData} snapshots, tracking version and migration trail metadata cleanly.
 */
public final class SaveMigrationService {

    /** Default base version assumed for unversioned {@link SaveSlotData} snapshots. */
    public static final int DEFAULT_BASE_VERSION = 1;

    /** Variable key storing the integer save schema version inside {@link SaveSlotData#variables()}. */
    public static final String VERSION_VARIABLE_KEY = "__saveVersion";

    /** Variable key storing the ordered list of executed migration transitions (e.g., {@code "1->2"}). */
    public static final String MIGRATION_TRAIL_KEY = "__migrationTrail";

    private final int baseVersion;
    private final Map<Integer, SaveMigrationStep> stepsBySourceVersion = new LinkedHashMap<>();

    /**
     * Creates a {@code SaveMigrationService} with {@link #DEFAULT_BASE_VERSION}.
     */
    public SaveMigrationService() {
        this(DEFAULT_BASE_VERSION);
    }

    /**
     * Creates a {@code SaveMigrationService} with a custom base version for unversioned saves.
     *
     * @param baseVersion default schema version when none is explicitly embedded ({@code >= 0})
     */
    public SaveMigrationService(int baseVersion) {
        if (baseVersion < 0) {
            throw new IllegalArgumentException("baseVersion must be >= 0, got " + baseVersion);
        }
        this.baseVersion = baseVersion;
    }

    /**
     * Registers a {@link SaveMigrationStep} into the migration pipeline.
     *
     * @param step migration step to register (must not be {@code null})
     * @return this service for fluent registration
     */
    public synchronized SaveMigrationService registerStep(SaveMigrationStep step) {
        Objects.requireNonNull(step, "step must not be null");
        int from = step.fromVersion();
        int to = step.toVersion();
        if (from < 0) {
            throw new IllegalArgumentException("fromVersion must be >= 0, got " + from);
        }
        if (to <= from) {
            throw new IllegalArgumentException(
                    "Migration step must advance version (from=" + from + ", to=" + to + ")"
            );
        }
        if (stepsBySourceVersion.containsKey(from)) {
            throw new IllegalArgumentException("Duplicate migration step registered for fromVersion " + from);
        }
        stepsBySourceVersion.put(from, step);
        return this;
    }

    /**
     * Registers all provided {@link SaveMigrationStep} instances.
     *
     * @param steps collection of steps to register (must not be {@code null})
     * @return this service for fluent registration
     */
    public synchronized SaveMigrationService registerSteps(Collection<? extends SaveMigrationStep> steps) {
        Objects.requireNonNull(steps, "steps must not be null");
        for (SaveMigrationStep step : steps) {
            registerStep(step);
        }
        return this;
    }

    /**
     * Validates that all registered migration steps form a gap-free contiguous chain.
     *
     * @throws IllegalStateException if a gap or discontinuity exists between registered steps
     */
    public synchronized void validateChain() {
        if (stepsBySourceVersion.isEmpty()) {
            return;
        }
        List<SaveMigrationStep> sorted = getSortedStepsInternal();
        for (int i = 0; i < sorted.size() - 1; i++) {
            SaveMigrationStep current = sorted.get(i);
            SaveMigrationStep next = sorted.get(i + 1);
            if (current.toVersion() != next.fromVersion()) {
                throw new IllegalStateException(
                        "Broken save migration chain between step "
                                + current.fromVersion() + "->" + current.toVersion()
                                + " and step " + next.fromVersion() + "->" + next.toVersion()
                );
            }
        }
    }

    /**
     * Returns whether the currently registered steps form a valid contiguous chain.
     *
     * @return {@code true} if valid, {@code false} if broken
     */
    public synchronized boolean isChainValid() {
        try {
            validateChain();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /**
     * Returns the latest target version reachable by this migration pipeline.
     *
     * @return highest {@code toVersion()} among registered steps, or base version if none are registered
     */
    public synchronized int getTargetVersion() {
        if (stepsBySourceVersion.isEmpty()) {
            return baseVersion;
        }
        int maxTarget = baseVersion;
        for (SaveMigrationStep step : stepsBySourceVersion.values()) {
            if (step.toVersion() > maxTarget) {
                maxTarget = step.toVersion();
            }
        }
        return maxTarget;
    }

    /**
     * Returns an unmodifiable list of registered steps sorted by {@code fromVersion()} ascending.
     *
     * @return immutable sorted list of migration steps
     */
    public synchronized List<SaveMigrationStep> getSteps() {
        return List.copyOf(getSortedStepsInternal());
    }

    /**
     * Detects the schema version of {@code input} from {@link #VERSION_VARIABLE_KEY}, numeric
     * {@link SaveMetadata#gameVersion()}, or falls back to this service's base version.
     *
     * @param input save slot snapshot (must not be {@code null})
     * @return detected save schema version
     */
    public int detectVersion(SaveSlotData input) {
        Objects.requireNonNull(input, "input must not be null");
        Object rawVersion = input.variables().get(VERSION_VARIABLE_KEY);
        if (rawVersion instanceof Number num) {
            return num.intValue();
        }
        if (rawVersion instanceof String str) {
            Integer parsed = tryParseNonNegativeInt(str);
            if (parsed != null) {
                return parsed;
            }
        }
        if (input.metadata() != null && input.metadata().gameVersion() != null) {
            Integer parsedMeta = tryParseNonNegativeInt(input.metadata().gameVersion());
            if (parsedMeta != null) {
                return parsedMeta;
            }
        }
        return baseVersion;
    }

    /**
     * Returns a copy of {@code input} tagged with {@link #VERSION_VARIABLE_KEY} set to {@code version}.
     *
     * @param input   source save slot snapshot (must not be {@code null})
     * @param version schema version ({@code >= 0})
     * @return updated copy of {@link SaveSlotData}
     */
    public static SaveSlotData withVersion(SaveSlotData input, int version) {
        Objects.requireNonNull(input, "input must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0, got " + version);
        }
        Map<String, Object> updatedVars = new LinkedHashMap<>(input.variables());
        updatedVars.put(VERSION_VARIABLE_KEY, version);
        return new SaveSlotData(
                input.metadata(),
                updatedVars,
                input.scriptPosition(),
                input.historyEntries()
        );
    }

    /**
     * Migrates {@code input} sequentially through all applicable {@link SaveMigrationStep}s up to
     * {@link #getTargetVersion()}, recording the updated version and migration trail cleanly without
     * mutating {@code input}.
     *
     * @param input source save slot snapshot (must not be {@code null})
     * @return migrated {@link SaveSlotData} at {@link #getTargetVersion()}
     * @throws IllegalStateException if the migration chain is invalid, {@code input} has a newer version
     *                               than {@link #getTargetVersion()}, or no path exists from {@code input}'s version
     */
    public synchronized SaveSlotData migrateToLatest(SaveSlotData input) {
        Objects.requireNonNull(input, "input must not be null");
        validateChain();

        int currentVersion = detectVersion(input);
        int targetVersion = getTargetVersion();

        if (currentVersion > targetVersion) {
            throw new IllegalStateException(
                    "Save version " + currentVersion + " is newer than target version " + targetVersion
            );
        }
        if (currentVersion == targetVersion) {
            return input;
        }

        List<String> executedTransitions = new ArrayList<>();
        SaveSlotData working = copyWithDetachedVariables(input);

        while (currentVersion < targetVersion) {
            SaveMigrationStep step = stepsBySourceVersion.get(currentVersion);
            if (step == null) {
                throw new IllegalStateException(
                        "No migration step registered from version " + currentVersion
                                + " toward target version " + targetVersion
                );
            }
            SaveSlotData migrated = step.migrate(copyWithDetachedVariables(working));
            if (migrated == null) {
                throw new IllegalStateException(
                        "Migration step " + step.fromVersion() + "->" + step.toVersion() + " returned null"
                );
            }
            executedTransitions.add(step.fromVersion() + "->" + step.toVersion());
            currentVersion = step.toVersion();
            working = migrated;
        }

        Map<String, Object> finalVariables = new LinkedHashMap<>(working.variables());
        finalVariables.put(VERSION_VARIABLE_KEY, currentVersion);

        List<String> combinedTrail = new ArrayList<>();
        Object existingTrail = finalVariables.get(MIGRATION_TRAIL_KEY);
        if (existingTrail instanceof Collection<?> existingEntries) {
            for (Object item : existingEntries) {
                if (item != null) {
                    combinedTrail.add(item.toString());
                }
            }
        }
        combinedTrail.addAll(executedTransitions);
        finalVariables.put(MIGRATION_TRAIL_KEY, List.copyOf(combinedTrail));

        return new SaveSlotData(
                working.metadata(),
                finalVariables,
                working.scriptPosition(),
                working.historyEntries()
        );
    }

    private List<SaveMigrationStep> getSortedStepsInternal() {
        List<SaveMigrationStep> sorted = new ArrayList<>(stepsBySourceVersion.values());
        sorted.sort(Comparator.comparingInt(SaveMigrationStep::fromVersion));
        return sorted;
    }

    private static SaveSlotData copyWithDetachedVariables(SaveSlotData source) {
        return new SaveSlotData(
                source.metadata(),
                new LinkedHashMap<>(source.variables()),
                source.scriptPosition(),
                source.historyEntries()
        );
    }

    private static Integer tryParseNonNegativeInt(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
