package de.swfdtf.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Gson-serializable metadata descriptor persisted as {@code project.vjava.json}
 * in the root of every KAGAYAKI visual novel project.
 */
public final class ProjectDescriptor {

    /**
     * Standard file name of the project descriptor within a project directory.
     */
    public static final String FILE_NAME = "project.vjava.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private String title = "Untitled KAGAYAKI Project";
    private String version = "1.0.0";
    private String author = "";
    private String renderMode = "2D";
    private int logicalWidth = EngineConfig.DEFAULT_LOGICAL_WIDTH;
    private int logicalHeight = EngineConfig.DEFAULT_LOGICAL_HEIGHT;
    private String entryLabel = "start";
    private String defaultLanguage = "deutsch";
    private String dialogueMode = "ADV";
    private String windowTitle = "";
    private String iconPath = "";
    private String vpakEncryptionKey = "";
    private String accentColorHex = "";

    /**
     * Creates a descriptor initialized with default settings (used by Gson during deserialization).
     */
    public ProjectDescriptor() {
    }

    /**
     * Creates a fully specified {@link ProjectDescriptor}.
     *
     * @param title           project title
     * @param version         semantic version string
     * @param author          author or studio name
     * @param renderMode      render mode ({@code "2D"} or {@code "3D"})
     * @param logicalWidth    logical canvas width
     * @param logicalHeight   logical canvas height
     * @param entryLabel      initial {@code .vjava} label name
     * @param defaultLanguage default translation language code
     * @param dialogueMode    default dialogue presentation mode ({@code "ADV"}, {@code "NVL"}, or {@code "BUBBLE"})
     * @param windowTitle     window title override
     * @param iconPath        relative path to window icon asset
     */
    public ProjectDescriptor(
            String title,
            String version,
            String author,
            String renderMode,
            int logicalWidth,
            int logicalHeight,
            String entryLabel,
            String defaultLanguage,
            String dialogueMode,
            String windowTitle,
            String iconPath
    ) {
        this.title = title;
        this.version = version;
        this.author = author;
        this.renderMode = renderMode;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.entryLabel = entryLabel;
        this.defaultLanguage = defaultLanguage;
        this.dialogueMode = dialogueMode;
        this.windowTitle = windowTitle;
        this.iconPath = iconPath;
        normalizeDefaults();
    }

    /**
     * Creates a default {@link ProjectDescriptor} for a newly created project.
     *
     * @param title project title (falls back to {@code "Untitled KAGAYAKI Project"} if null or blank)
     * @return initialized project descriptor
     */
    public static ProjectDescriptor createDefault(String title) {
        String effectiveTitle = (title == null || title.isBlank()) ? "Untitled KAGAYAKI Project" : title.trim();
        ProjectDescriptor descriptor = new ProjectDescriptor();
        descriptor.setTitle(effectiveTitle);
        descriptor.setWindowTitle(effectiveTitle);
        descriptor.normalizeDefaults();
        return descriptor;
    }

    /**
     * Loads and validates {@code project.vjava.json} from the specified project directory.
     *
     * @param projectDir root directory containing {@code project.vjava.json}
     * @return deserialized and normalized {@link ProjectDescriptor}
     * @throws IOException if the file does not exist, cannot be read, or contains invalid JSON
     */
    public static ProjectDescriptor load(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path descriptorFile = projectDir.resolve(FILE_NAME);
        if (!Files.exists(descriptorFile)) {
            throw new NoSuchFileException(descriptorFile.toString());
        }
        try (Reader reader = Files.newBufferedReader(descriptorFile, StandardCharsets.UTF_8)) {
            ProjectDescriptor loaded = GSON.fromJson(reader, ProjectDescriptor.class);
            if (loaded == null) {
                throw new IOException("Empty project descriptor file: " + descriptorFile);
            }
            loaded.normalizeDefaults();
            return loaded;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse " + descriptorFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes and normalizes a {@link ProjectDescriptor} from a UTF-8 JSON string.
     *
     * @param json JSON representation of {@code project.vjava.json}
     * @return parsed and normalized {@link ProjectDescriptor}
     * @throws IOException if {@code json} is empty or malformed
     */
    public static ProjectDescriptor parseJson(String json) throws IOException {
        Objects.requireNonNull(json, "json must not be null");
        try {
            ProjectDescriptor loaded = GSON.fromJson(json, ProjectDescriptor.class);
            if (loaded == null) {
                throw new IOException("Empty project descriptor JSON");
            }
            loaded.normalizeDefaults();
            return loaded;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse project descriptor JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Saves the specified {@link ProjectDescriptor} as formatted JSON to {@code projectDir/project.vjava.json}.
     *
     * @param projectDir root directory of the project
     * @param descriptor descriptor instance to serialize
     * @throws IOException if creating directories or writing the file fails
     */
    public static void save(Path projectDir, ProjectDescriptor descriptor) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        descriptor.normalizeDefaults();
        Files.createDirectories(projectDir);
        Path descriptorFile = projectDir.resolve(FILE_NAME);
        try (Writer writer = Files.newBufferedWriter(descriptorFile, StandardCharsets.UTF_8)) {
            GSON.toJson(descriptor, writer);
        }
    }

    /**
     * Ensures all optional or invalid fields fall back to safe project defaults.
     */
    public void normalizeDefaults() {
        if (title == null || title.isBlank()) {
            title = "Untitled KAGAYAKI Project";
        } else {
            title = title.trim();
        }
        if (version == null || version.isBlank()) {
            version = "1.0.0";
        } else {
            version = version.trim();
        }
        if (author == null) {
            author = "";
        }
        if (renderMode == null || renderMode.isBlank()) {
            renderMode = "2D";
        } else {
            String upperMode = renderMode.trim().toUpperCase(Locale.ROOT);
            renderMode = ("3D".equals(upperMode)) ? "3D" : "2D";
        }
        if (logicalWidth <= 0) {
            logicalWidth = EngineConfig.DEFAULT_LOGICAL_WIDTH;
        }
        if (logicalHeight <= 0) {
            logicalHeight = EngineConfig.DEFAULT_LOGICAL_HEIGHT;
        }
        if (entryLabel == null || entryLabel.isBlank()) {
            entryLabel = "start";
        } else {
            entryLabel = entryLabel.trim();
        }
        if (defaultLanguage == null || defaultLanguage.isBlank()) {
            defaultLanguage = "deutsch";
        } else {
            defaultLanguage = defaultLanguage.trim();
        }
        if (dialogueMode == null || dialogueMode.isBlank()) {
            dialogueMode = "ADV";
        } else {
            dialogueMode = dialogueMode.trim().toUpperCase(Locale.ROOT);
        }
        if (windowTitle == null || windowTitle.isBlank()) {
            windowTitle = title;
        }
        if (iconPath == null) {
            iconPath = "";
        }
        if (vpakEncryptionKey == null) {
            vpakEncryptionKey = "";
        }
    }

    /**
     * Returns the project title.
     *
     * @return project title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the project title.
     *
     * @param title project title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the project version string.
     *
     * @return project version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Sets the project version string.
     *
     * @param version project version
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Returns the project author name.
     *
     * @return author name
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Sets the project author name.
     *
     * @param author author name
     */
    public void setAuthor(String author) {
        this.author = author;
    }

    /**
     * Returns the configured render mode ({@code "2D"} or {@code "3D"}).
     *
     * @return render mode string
     */
    public String getRenderMode() {
        return renderMode;
    }

    /**
     * Sets the render mode ({@code "2D"} or {@code "3D"}).
     *
     * @param renderMode render mode string
     */
    public void setRenderMode(String renderMode) {
        this.renderMode = renderMode;
    }

    /**
     * Returns the logical canvas width.
     *
     * @return logical width in pixels
     */
    public int getLogicalWidth() {
        return logicalWidth;
    }

    /**
     * Sets the logical canvas width.
     *
     * @param logicalWidth logical width in pixels
     */
    public void setLogicalWidth(int logicalWidth) {
        this.logicalWidth = logicalWidth;
    }

    /**
     * Returns the logical canvas height.
     *
     * @return logical height in pixels
     */
    public int getLogicalHeight() {
        return logicalHeight;
    }

    /**
     * Sets the logical canvas height.
     *
     * @param logicalHeight logical height in pixels
     */
    public void setLogicalHeight(int logicalHeight) {
        this.logicalHeight = logicalHeight;
    }

    /**
     * Returns the initial entry label in {@code .vjava} scripts.
     *
     * @return entry label name
     */
    public String getEntryLabel() {
        return entryLabel;
    }

    /**
     * Sets the initial entry label in {@code .vjava} scripts.
     *
     * @param entryLabel entry label name
     */
    public void setEntryLabel(String entryLabel) {
        this.entryLabel = entryLabel;
    }

    /**
     * Returns the default language identifier.
     *
     * @return default language
     */
    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    /**
     * Sets the default language identifier.
     *
     * @param defaultLanguage default language
     */
    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Returns the default dialogue presentation mode ({@code "ADV"}, {@code "NVL"}, or {@code "BUBBLE"}).
     *
     * @return dialogue mode
     */
    public String getDialogueMode() {
        return dialogueMode;
    }

    /**
     * Sets the default dialogue presentation mode.
     *
     * @param dialogueMode dialogue mode
     */
    public void setDialogueMode(String dialogueMode) {
        this.dialogueMode = dialogueMode;
    }

    /**
     * Returns the game window title.
     *
     * @return window title
     */
    public String getWindowTitle() {
        return windowTitle;
    }

    /**
     * Sets the game window title.
     *
     * @param windowTitle window title
     */
    public void setWindowTitle(String windowTitle) {
        this.windowTitle = windowTitle;
    }

    /**
     * Returns the relative path to the window icon image.
     *
     * @return icon asset path
     */
    public String getIconPath() {
        return iconPath;
    }

    /**
     * Sets the relative path to the window icon image.
     *
     * @param iconPath icon asset path
     */
    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    /**
     * Returns the optional encryption key used for AES-CTR {@code .vpak} packaging.
     *
     * @return encryption key string (empty when unencrypted)
     */
    public String getVpakEncryptionKey() {
        return vpakEncryptionKey;
    }

    /**
     * Sets the optional encryption key used when exporting encrypted {@code .vpak} archives.
     *
     * @param vpakEncryptionKey encryption key string (empty or {@code null} to disable encryption)
     */
    public void setVpakEncryptionKey(String vpakEncryptionKey) {
        this.vpakEncryptionKey = vpakEncryptionKey != null ? vpakEncryptionKey : "";
    }

    /**
     * Returns optional project UI accent color hex override ({@code #0409}).
     *
     * @return custom accent hex string or empty when default Dark Slate accent applies
     */
    public String getAccentColorHex() {
        return accentColorHex != null ? accentColorHex : "";
    }

    /**
     * Sets optional project UI accent color hex override ({@code #0409}).
     *
     * @param accentColorHex hex color string (empty or null restores default)
     */
    public void setAccentColorHex(String accentColorHex) {
        this.accentColorHex = accentColorHex != null ? accentColorHex.trim() : "";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ProjectDescriptor other)) {
            return false;
        }
        return logicalWidth == other.logicalWidth
                && logicalHeight == other.logicalHeight
                && Objects.equals(title, other.title)
                && Objects.equals(version, other.version)
                && Objects.equals(author, other.author)
                && Objects.equals(renderMode, other.renderMode)
                && Objects.equals(entryLabel, other.entryLabel)
                && Objects.equals(defaultLanguage, other.defaultLanguage)
                && Objects.equals(dialogueMode, other.dialogueMode)
                && Objects.equals(windowTitle, other.windowTitle)
                && Objects.equals(iconPath, other.iconPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                title,
                version,
                author,
                renderMode,
                logicalWidth,
                logicalHeight,
                entryLabel,
                defaultLanguage,
                dialogueMode,
                windowTitle,
                iconPath
        );
    }

    @Override
    public String toString() {
        return "ProjectDescriptor{"
                + "title='" + title + '\''
                + ", version='" + version + '\''
                + ", renderMode='" + renderMode + '\''
                + ", logicalWidth=" + logicalWidth
                + ", logicalHeight=" + logicalHeight
                + ", entryLabel='" + entryLabel + '\''
                + ", defaultLanguage='" + defaultLanguage + '\''
                + ", dialogueMode='" + dialogueMode + '\''
                + '}';
    }
}
