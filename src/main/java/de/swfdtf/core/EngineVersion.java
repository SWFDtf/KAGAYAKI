package de.swfdtf.core;

import java.util.Objects;

/**
 * Central engine, DSL specification, and build version metadata for KAGAYAKI Studio
 * and standalone runtime titles.
 */
public final class EngineVersion {

    /** Canonical engine display name. */
    public static final String ENGINE_NAME = "KAGAYAKI";

    /** Semantic engine version. */
    public static final String VERSION = "1.0.0";

    /** Supported {@code .vjava} DSL specification version. */
    public static final String VJAVA_SPEC_VERSION = "1.1";

    private EngineVersion() {
    }

    /**
     * Formats the standard engine version banner displayed in Studio hubs and launchers.
     *
     * @return formatted engine and {@code .vjava} specification banner
     */
    public static String formatBanner() {
        return ENGINE_NAME + " v" + VERSION + " (vJava " + VJAVA_SPEC_VERSION + ")";
    }

    /**
     * Formats a header string combining engine version and project metadata.
     *
     * @param projectTitle   project title (nullable)
     * @param projectVersion project version (nullable)
     * @return formatted project + engine header
     */
    public static String formatProjectHeader(String projectTitle, String projectVersion) {
        String safeTitle = (projectTitle == null || projectTitle.isBlank())
                ? "Unbenanntes Projekt" : projectTitle.trim();
        String safeProjVer = (projectVersion == null || projectVersion.isBlank()) ? VERSION : projectVersion.trim();
        return safeTitle + " v" + safeProjVer + " — " + formatBanner();
    }

    /**
     * Formats the project descriptor header using {@link ProjectDescriptor#getTitle()} and
     * {@link ProjectDescriptor#getVersion()}.
     *
     * @param descriptor project descriptor (non-null)
     * @return formatted project + engine header
     */
    public static String formatProjectHeader(ProjectDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return formatProjectHeader(descriptor.getTitle(), descriptor.getVersion());
    }
}
