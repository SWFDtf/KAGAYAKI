package de.swfdtf.state;

/**
 * Single deterministic version-to-version migration step transforming a {@link SaveSlotData}
 * snapshot from {@link #fromVersion()} to {@link #toVersion()}.
 */
public interface SaveMigrationStep {

    /**
     * Returns the source save schema version accepted by this migration step.
     *
     * @return source version ({@code >= 0})
     */
    int fromVersion();

    /**
     * Returns the target save schema version produced by this migration step.
     *
     * @return target version ({@code > fromVersion()})
     */
    int toVersion();

    /**
     * Migrates {@code input} from {@link #fromVersion()} to {@link #toVersion()} without
     * mutating the caller's original state unexpectedly.
     *
     * @param input source save slot snapshot
     * @return migrated {@link SaveSlotData}
     */
    SaveSlotData migrate(SaveSlotData input);
}
