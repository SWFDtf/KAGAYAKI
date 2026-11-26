package de.swfdtf.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent Studio workspace preferences model storing the last opened project,
 * bounded recent-project history, auto-lint behavior, active Studio panel, layout preset,
 * theme variant, UI scaling, onboarding state, user templates, and window dimensions.
 *
 * <p>Persists atomically to JSON via Gson and provides fluent configuration builders,
 * unmodifiable history views, and defensive normalization on load.</p>
 */
public final class StudioWorkspaceState {

    /** Maximum number of recent project paths retained in workspace preferences. */
    public static final int MAX_RECENT_PROJECTS = 10;

    /** Default Studio window width in pixels. */
    public static final int DEFAULT_WINDOW_WIDTH = 1600;

    /** Default Studio window height in pixels. */
    public static final int DEFAULT_WINDOW_HEIGHT = 900;

    /** Default selected Studio panel identifier. */
    public static final String DEFAULT_SELECTED_PANEL = "explorer";

    /** Default Studio workspace layout preset. */
    public static final String DEFAULT_LAYOUT_PRESET = "DOCKING";

    /** Default Studio color theme identifier. */
    public static final String DEFAULT_THEME_VARIANT = "DARK_SLATE";

    /** Minimum allowed Studio UI scale factor ({@code 80%}). */
    public static final float MIN_UI_SCALE = 0.80f;

    /** Maximum allowed Studio UI scale factor ({@code 150%}). */
    public static final float MAX_UI_SCALE = 1.50f;

    /** Default Studio UI scale factor ({@code 100%}). */
    public static final float DEFAULT_UI_SCALE = 1.0f;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private String lastProjectPath;
    private List<String> recentProjectPaths = new ArrayList<>();
    private boolean autoLintOnReload = true;
    private String selectedPanel = DEFAULT_SELECTED_PANEL;
    private String layoutPreset = DEFAULT_LAYOUT_PRESET;
    private Map<String, Boolean> panelVisibility = defaultPanelVisibility();
    private String themeVariant = DEFAULT_THEME_VARIANT;
    private float uiScale = DEFAULT_UI_SCALE;
    private boolean onboardingTourCompleted = false;
    private List<String> userTemplatePaths = new ArrayList<>();
    private int windowWidth = DEFAULT_WINDOW_WIDTH;
    private int windowHeight = DEFAULT_WINDOW_HEIGHT;

    /**
     * Creates a {@code StudioWorkspaceState} initialized with default preferences.
     */
    public StudioWorkspaceState() {
    }

    private StudioWorkspaceState(Builder builder) {
        this.lastProjectPath = builder.lastProjectPath;
        this.recentProjectPaths = new ArrayList<>(builder.recentProjectPaths);
        this.autoLintOnReload = builder.autoLintOnReload;
        this.selectedPanel = builder.selectedPanel;
        setLayoutPreset(builder.layoutPreset);
        if (builder.panelVisibilityExplicit) {
            this.panelVisibility = new LinkedHashMap<>(builder.panelVisibility);
        }
        this.themeVariant = builder.themeVariant;
        this.uiScale = builder.uiScale;
        this.onboardingTourCompleted = builder.onboardingTourCompleted;
        this.userTemplatePaths = new ArrayList<>(builder.userTemplatePaths);
        this.windowWidth = builder.windowWidth;
        this.windowHeight = builder.windowHeight;
        normalizeDefaults();
    }

    /**
     * Creates a new {@code StudioWorkspaceState} with default Studio preferences.
     *
     * @return default workspace state instance
     */
    public static StudioWorkspaceState defaults() {
        return new StudioWorkspaceState();
    }

    /**
     * Returns a new fluent {@link Builder} initialized with default Studio preferences.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a {@link Builder} pre-populated with this state's current values.
     *
     * @return copied builder instance
     */
    public Builder toBuilder() {
        return new Builder()
                .lastProjectPath(lastProjectPath)
                .recentProjectPaths(getRecentProjectPaths())
                .autoLintOnReload(autoLintOnReload)
                .selectedPanel(selectedPanel)
                .layoutPreset(layoutPreset)
                .panelVisibility(getPanelVisibility())
                .themeVariant(themeVariant)
                .uiScale(uiScale)
                .onboardingTourCompleted(onboardingTourCompleted)
                .userTemplatePaths(getUserTemplatePaths())
                .windowWidth(windowWidth)
                .windowHeight(windowHeight);
    }

    /**
     * Loads and normalizes a {@link StudioWorkspaceState} from a JSON file.
     *
     * @param file source JSON file path (must not be {@code null})
     * @return deserialized and normalized {@link StudioWorkspaceState}
     * @throws IOException if the file does not exist, cannot be read, or contains invalid JSON
     */
    public static StudioWorkspaceState load(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new NoSuchFileException(normalized.toString());
        }
        try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            StudioWorkspaceState loaded = GSON.fromJson(reader, StudioWorkspaceState.class);
            if (loaded == null) {
                throw new IOException("Empty Studio workspace state file: " + normalized);
            }
            loaded.normalizeDefaults();
            return loaded;
        } catch (JsonParseException ex) {
            throw new IOException("Failed to parse Studio workspace state " + normalized + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Atomically serializes this workspace state as pretty-printed JSON to {@code file},
     * creating parent directories if needed.
     *
     * @param file destination JSON file path (must not be {@code null})
     * @throws IOException if directory creation, temp-file write, or atomic move fails
     */
    public void save(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        normalizeDefaults();

        Path normalizedTarget = file.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = GSON.toJson(this);
        Path tempFile = parent != null
                ? Files.createTempFile(parent, "studio-workspace-", ".tmp")
                : Files.createTempFile("studio-workspace-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            moveAtomically(tempFile, normalizedTarget);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    /**
     * Records {@code projectDir} as the most recently opened project, moving it to the front
     * of {@link #getRecentProjectPaths()} and bounding history to {@link #MAX_RECENT_PROJECTS}.
     *
     * @param projectDir opened project directory (must not be {@code null})
     */
    public void recordOpenedProject(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        String normalizedPath = projectDir.toAbsolutePath().normalize().toString();
        this.lastProjectPath = normalizedPath;

        List<String> updated = new ArrayList<>(MAX_RECENT_PROJECTS);
        updated.add(normalizedPath);
        if (this.recentProjectPaths != null) {
            for (String existing : this.recentProjectPaths) {
                if (existing != null && !existing.isBlank() && !existing.equals(normalizedPath)) {
                    updated.add(existing.trim());
                    if (updated.size() >= MAX_RECENT_PROJECTS) {
                        break;
                    }
                }
            }
        }
        this.recentProjectPaths = updated;
    }

    /**
     * Returns the last opened project path if present.
     *
     * @return optional last opened project path
     */
    public Optional<String> getLastProjectPath() {
        if (lastProjectPath == null || lastProjectPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(lastProjectPath);
    }

    /**
     * Sets the last opened project path (nullable or blank clears it).
     *
     * @param lastProjectPath last opened project path
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setLastProjectPath(String lastProjectPath) {
        this.lastProjectPath = (lastProjectPath == null || lastProjectPath.isBlank())
                ? null
                : lastProjectPath.trim();
        return this;
    }

    /**
     * Returns an unmodifiable snapshot of recent project paths ordered from most recent to oldest.
     *
     * @return unmodifiable list of recent project paths
     */
    public List<String> getRecentProjectPaths() {
        if (recentProjectPaths == null || recentProjectPaths.isEmpty()) {
            return List.of();
        }
        return List.copyOf(recentProjectPaths);
    }

    /**
     * Replaces the recent project list, deduplicating blank entries and bounding to
     * {@link #MAX_RECENT_PROJECTS}.
     *
     * @param recentProjectPaths recent project paths (must not be {@code null})
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setRecentProjectPaths(List<String> recentProjectPaths) {
        Objects.requireNonNull(recentProjectPaths, "recentProjectPaths must not be null");
        this.recentProjectPaths = sanitizeRecentPaths(recentProjectPaths);
        return this;
    }

    /**
     * Returns whether automatic linting is enabled on live script reload.
     *
     * @return {@code true} if auto-lint on reload is enabled
     */
    public boolean isAutoLintOnReload() {
        return autoLintOnReload;
    }

    /**
     * Sets whether automatic linting runs on live script reload.
     *
     * @param autoLintOnReload auto-lint toggle
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setAutoLintOnReload(boolean autoLintOnReload) {
        this.autoLintOnReload = autoLintOnReload;
        return this;
    }

    /**
     * Returns the currently selected Studio panel identifier.
     *
     * @return non-blank selected panel identifier
     */
    public String getSelectedPanel() {
        return selectedPanel;
    }

    /**
     * Sets the active Studio panel identifier (falls back to {@link #DEFAULT_SELECTED_PANEL} if null or blank).
     *
     * @param selectedPanel panel identifier
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setSelectedPanel(String selectedPanel) {
        this.selectedPanel = (selectedPanel == null || selectedPanel.isBlank())
                ? DEFAULT_SELECTED_PANEL
                : selectedPanel.trim();
        return this;
    }

    /**
     * Returns the preferred Studio window width in pixels.
     *
     * @return positive window width
     */
    public int getWindowWidth() {
        return windowWidth;
    }

    /**
     * Sets the preferred Studio window width in pixels.
     *
     * @param windowWidth positive window width ({@code > 0})
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setWindowWidth(int windowWidth) {
        if (windowWidth <= 0) {
            throw new IllegalArgumentException("windowWidth must be > 0, got " + windowWidth);
        }
        this.windowWidth = windowWidth;
        return this;
    }

    /**
     * Returns the preferred Studio window height in pixels.
     *
     * @return positive window height
     */
    public int getWindowHeight() {
        return windowHeight;
    }

    /**
     * Sets the preferred Studio window height in pixels.
     *
     * @param windowHeight positive window height ({@code > 0})
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setWindowHeight(int windowHeight) {
        if (windowHeight <= 0) {
            throw new IllegalArgumentException("windowHeight must be > 0, got " + windowHeight);
        }
        this.windowHeight = windowHeight;
        return this;
    }

    /**
     * Returns the active Studio layout preset name ({@code DOCKING}, {@code COMPACT}, or {@code DEBUG}).
     *
     * @return non-blank layout preset name
     */
    public String getLayoutPreset() {
        return layoutPreset;
    }

    /**
     * Sets the active Studio layout preset and updates default panel visibility weights.
     *
     * @param layoutPreset preset identifier (falls back to {@link #DEFAULT_LAYOUT_PRESET} if null/blank)
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setLayoutPreset(String layoutPreset) {
        this.layoutPreset = normalizeLayoutPreset(layoutPreset);
        if ("COMPACT".equals(this.layoutPreset)) {
            this.panelVisibility.put("flowGraph", Boolean.FALSE);
            this.panelVisibility.put("variables", Boolean.FALSE);
            this.panelVisibility.put("explorer", Boolean.TRUE);
            this.panelVisibility.put("linter", Boolean.TRUE);
        } else if ("DEBUG".equals(this.layoutPreset)) {
            this.panelVisibility.put("explorer", Boolean.TRUE);
            this.panelVisibility.put("linter", Boolean.TRUE);
            this.panelVisibility.put("variables", Boolean.TRUE);
            this.panelVisibility.put("flowGraph", Boolean.TRUE);
        } else {
            this.panelVisibility = defaultPanelVisibility();
        }
        return this;
    }

    /**
     * Returns an unmodifiable snapshot of panel visibility flags keyed by panel identifier.
     *
     * @return unmodifiable panel visibility map
     */
    public Map<String, Boolean> getPanelVisibility() {
        if (panelVisibility == null || panelVisibility.isEmpty()) {
            return Collections.unmodifiableMap(defaultPanelVisibility());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(panelVisibility));
    }

    /**
     * Updates visibility of an individual Studio panel.
     *
     * @param panelId panel key ({@code explorer}, {@code linter}, {@code variables}, {@code flowGraph})
     * @param visible visibility flag
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setPanelVisible(String panelId, boolean visible) {
        if (panelId != null && !panelId.isBlank()) {
            if (panelVisibility == null) {
                panelVisibility = defaultPanelVisibility();
            }
            panelVisibility.put(panelId.trim(), visible);
        }
        return this;
    }

    /**
     * Returns the configured Studio theme variant name.
     *
     * @return theme variant identifier
     */
    public String getThemeVariant() {
        return themeVariant;
    }

    /**
     * Sets the Studio theme variant name.
     *
     * @param themeVariant variant name (e.g., {@code DARK_SLATE}, {@code MIDNIGHT_HIGH_CONTRAST}, {@code DAYLIGHT})
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setThemeVariant(String themeVariant) {
        this.themeVariant = (themeVariant == null || themeVariant.isBlank())
                ? DEFAULT_THEME_VARIANT
                : themeVariant.trim();
        return this;
    }

    /**
     * Returns the Studio High-DPI UI scaling factor in {@code [0.80, 1.50]}.
     *
     * @return clamped UI scale factor
     */
    public float getUiScale() {
        return uiScale;
    }

    /**
     * Sets the Studio UI scaling factor, clamped to {@code [0.80, 1.50]}.
     *
     * @param uiScale scale multiplier
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setUiScale(float uiScale) {
        this.uiScale = clampUiScale(uiScale);
        return this;
    }

    /**
     * Returns whether the Studio onboarding tour has been completed or skipped.
     *
     * @return {@code true} if completed or dismissed
     */
    public boolean isOnboardingTourCompleted() {
        return onboardingTourCompleted;
    }

    /**
     * Marks whether the Studio onboarding tour has been completed or dismissed.
     *
     * @param onboardingTourCompleted completion state
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState setOnboardingTourCompleted(boolean onboardingTourCompleted) {
        this.onboardingTourCompleted = onboardingTourCompleted;
        return this;
    }

    /**
     * Returns an unmodifiable list of user-saved custom project template directories.
     *
     * @return unmodifiable list of custom template directories
     */
    public List<String> getUserTemplatePaths() {
        if (userTemplatePaths == null || userTemplatePaths.isEmpty()) {
            return List.of();
        }
        return List.copyOf(userTemplatePaths);
    }

    /**
     * Registers a custom user template directory path without duplicates.
     *
     * @param templateDir custom template directory path
     * @return {@code this} for fluent chaining
     */
    public StudioWorkspaceState recordUserTemplate(Path templateDir) {
        Objects.requireNonNull(templateDir, "templateDir must not be null");
        String norm = templateDir.toAbsolutePath().normalize().toString();
        if (userTemplatePaths == null) {
            userTemplatePaths = new ArrayList<>();
        }
        if (!userTemplatePaths.contains(norm)) {
            userTemplatePaths.add(norm);
        }
        return this;
    }

    private static Map<String, Boolean> defaultPanelVisibility() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("explorer", Boolean.TRUE);
        map.put("linter", Boolean.TRUE);
        map.put("variables", Boolean.TRUE);
        map.put("flowGraph", Boolean.FALSE);
        return map;
    }

    private static String normalizeLayoutPreset(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LAYOUT_PRESET;
        }
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static float clampUiScale(float scale) {
        if (!Float.isFinite(scale)) {
            return DEFAULT_UI_SCALE;
        }
        return Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, scale));
    }

    private void normalizeDefaults() {
        if (lastProjectPath != null && lastProjectPath.isBlank()) {
            lastProjectPath = null;
        } else if (lastProjectPath != null) {
            lastProjectPath = lastProjectPath.trim();
        }
        recentProjectPaths = sanitizeRecentPaths(recentProjectPaths);
        if (selectedPanel == null || selectedPanel.isBlank()) {
            selectedPanel = DEFAULT_SELECTED_PANEL;
        } else {
            selectedPanel = selectedPanel.trim();
        }
        layoutPreset = normalizeLayoutPreset(layoutPreset);
        if (panelVisibility == null || panelVisibility.isEmpty()) {
            panelVisibility = defaultPanelVisibility();
        }
        if (themeVariant == null || themeVariant.isBlank()) {
            themeVariant = DEFAULT_THEME_VARIANT;
        } else {
            themeVariant = themeVariant.trim();
        }
        uiScale = clampUiScale(uiScale);
        userTemplatePaths = sanitizeRecentPaths(userTemplatePaths);
        if (windowWidth <= 0) {
            windowWidth = DEFAULT_WINDOW_WIDTH;
        }
        if (windowHeight <= 0) {
            windowHeight = DEFAULT_WINDOW_HEIGHT;
        }
    }

    private static List<String> sanitizeRecentPaths(List<String> input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        for (String path : input) {
            if (path != null && !path.isBlank()) {
                deduplicated.add(path.trim());
                if (deduplicated.size() >= MAX_RECENT_PROJECTS) {
                    break;
                }
            }
        }
        return new ArrayList<>(deduplicated);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StudioWorkspaceState other)) {
            return false;
        }
        return autoLintOnReload == other.autoLintOnReload
                && Float.compare(uiScale, other.uiScale) == 0
                && onboardingTourCompleted == other.onboardingTourCompleted
                && windowWidth == other.windowWidth
                && windowHeight == other.windowHeight
                && Objects.equals(lastProjectPath, other.lastProjectPath)
                && Objects.equals(recentProjectPaths, other.recentProjectPaths)
                && Objects.equals(selectedPanel, other.selectedPanel)
                && Objects.equals(layoutPreset, other.layoutPreset)
                && Objects.equals(panelVisibility, other.panelVisibility)
                && Objects.equals(themeVariant, other.themeVariant)
                && Objects.equals(userTemplatePaths, other.userTemplatePaths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                lastProjectPath,
                recentProjectPaths,
                autoLintOnReload,
                selectedPanel,
                layoutPreset,
                panelVisibility,
                themeVariant,
                uiScale,
                onboardingTourCompleted,
                userTemplatePaths,
                windowWidth,
                windowHeight
        );
    }

    @Override
    public String toString() {
        return "StudioWorkspaceState{"
                + "lastProjectPath='" + lastProjectPath + '\''
                + ", recentProjectPaths=" + recentProjectPaths
                + ", autoLintOnReload=" + autoLintOnReload
                + ", selectedPanel='" + selectedPanel + '\''
                + ", layoutPreset='" + layoutPreset + '\''
                + ", themeVariant='" + themeVariant + '\''
                + ", uiScale=" + uiScale
                + ", onboardingTourCompleted=" + onboardingTourCompleted
                + ", windowWidth=" + windowWidth
                + ", windowHeight=" + windowHeight
                + '}';
    }

    /**
     * Fluent builder for constructing {@link StudioWorkspaceState} instances.
     */
    public static final class Builder {

        private String lastProjectPath;
        private List<String> recentProjectPaths = List.of();
        private boolean autoLintOnReload = true;
        private String selectedPanel = DEFAULT_SELECTED_PANEL;
        private String layoutPreset = DEFAULT_LAYOUT_PRESET;
        private Map<String, Boolean> panelVisibility = defaultPanelVisibility();
        private boolean panelVisibilityExplicit = false;
        private String themeVariant = DEFAULT_THEME_VARIANT;
        private float uiScale = DEFAULT_UI_SCALE;
        private boolean onboardingTourCompleted = false;
        private List<String> userTemplatePaths = List.of();
        private int windowWidth = DEFAULT_WINDOW_WIDTH;
        private int windowHeight = DEFAULT_WINDOW_HEIGHT;

        private Builder() {
        }

        /**
         * Sets the last opened project path.
         *
         * @param lastProjectPath last opened project path (nullable)
         * @return {@code this}
         */
        public Builder lastProjectPath(String lastProjectPath) {
            this.lastProjectPath = lastProjectPath;
            return this;
        }

        /**
         * Sets the recent project path list.
         *
         * @param recentProjectPaths recent project paths (must not be {@code null})
         * @return {@code this}
         */
        public Builder recentProjectPaths(List<String> recentProjectPaths) {
            Objects.requireNonNull(recentProjectPaths, "recentProjectPaths must not be null");
            this.recentProjectPaths = List.copyOf(recentProjectPaths);
            return this;
        }

        /**
         * Sets whether auto-lint on reload is enabled.
         *
         * @param autoLintOnReload auto-lint toggle
         * @return {@code this}
         */
        public Builder autoLintOnReload(boolean autoLintOnReload) {
            this.autoLintOnReload = autoLintOnReload;
            return this;
        }

        /**
         * Sets the selected Studio panel identifier.
         *
         * @param selectedPanel panel identifier
         * @return {@code this}
         */
        public Builder selectedPanel(String selectedPanel) {
            this.selectedPanel = selectedPanel;
            return this;
        }

        /**
         * Sets the Studio layout preset name.
         *
         * @param layoutPreset preset identifier
         * @return {@code this}
         */
        public Builder layoutPreset(String layoutPreset) {
            this.layoutPreset = layoutPreset;
            return this;
        }

        /**
         * Sets the panel visibility map.
         *
         * @param panelVisibility visibility map
         * @return {@code this}
         */
        public Builder panelVisibility(Map<String, Boolean> panelVisibility) {
            this.panelVisibility = panelVisibility != null
                    ? new LinkedHashMap<>(panelVisibility)
                    : defaultPanelVisibility();
            this.panelVisibilityExplicit = panelVisibility != null;
            return this;
        }

        /**
         * Sets the Studio theme variant name.
         *
         * @param themeVariant theme variant identifier
         * @return {@code this}
         */
        public Builder themeVariant(String themeVariant) {
            this.themeVariant = themeVariant;
            return this;
        }

        /**
         * Sets the Studio UI scale factor.
         *
         * @param uiScale scale multiplier clamped to {@code [0.80, 1.50]}
         * @return {@code this}
         */
        public Builder uiScale(float uiScale) {
            this.uiScale = uiScale;
            return this;
        }

        /**
         * Sets whether the Studio onboarding tour has been completed.
         *
         * @param onboardingTourCompleted tour completion flag
         * @return {@code this}
         */
        public Builder onboardingTourCompleted(boolean onboardingTourCompleted) {
            this.onboardingTourCompleted = onboardingTourCompleted;
            return this;
        }

        /**
         * Sets custom user template directory paths.
         *
         * @param userTemplatePaths user template paths
         * @return {@code this}
         */
        public Builder userTemplatePaths(List<String> userTemplatePaths) {
            this.userTemplatePaths = userTemplatePaths != null ? List.copyOf(userTemplatePaths) : List.of();
            return this;
        }

        /**
         * Sets the preferred Studio window width in pixels.
         *
         * @param windowWidth positive window width ({@code > 0})
         * @return {@code this}
         */
        public Builder windowWidth(int windowWidth) {
            if (windowWidth <= 0) {
                throw new IllegalArgumentException("windowWidth must be > 0, got " + windowWidth);
            }
            this.windowWidth = windowWidth;
            return this;
        }

        /**
         * Sets the preferred Studio window height in pixels.
         *
         * @param windowHeight positive window height ({@code > 0})
         * @return {@code this}
         */
        public Builder windowHeight(int windowHeight) {
            if (windowHeight <= 0) {
                throw new IllegalArgumentException("windowHeight must be > 0, got " + windowHeight);
            }
            this.windowHeight = windowHeight;
            return this;
        }

        /**
         * Builds a normalized {@link StudioWorkspaceState}.
         *
         * @return configured {@link StudioWorkspaceState}
         */
        public StudioWorkspaceState build() {
            return new StudioWorkspaceState(this);
        }
    }
}
