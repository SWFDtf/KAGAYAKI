package de.swfdtf.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages reusable user-authored project templates and generates structured template preview
 * cards with feature badges for the Studio "Neues Projekt" hub ({@code #0117}, {@code #0147}).
 */
public final class UserTemplateRepository {

    /**
     * Visual preview card metadata shown in the New Project Hub ({@code #0147}).
     *
     * @param templateId     unique template identifier or directory path
     * @param displayName    card display title
     * @param summary        short description of included starter systems
     * @param badges         feature badge labels (e.g., {@code Main Menu}, {@code Save/Load}, {@code WASD})
     * @param accentColorHex procedural preview swatch hex color
     * @param userDefined    {@code true} for custom user templates saved via {@link #saveAsTemplate}
     */
    public record TemplatePreviewCard(
            String templateId,
            String displayName,
            String summary,
            List<String> badges,
            String accentColorHex,
            boolean userDefined
    ) {
    }

    private final Path templatesRoot;
    private final ProjectRefactorService refactorService = new ProjectRefactorService();

    /**
     * Creates a user template repository stored under {@code templatesRoot}.
     *
     * @param templatesRoot directory storing custom user templates
     */
    public UserTemplateRepository(Path templatesRoot) {
        this.templatesRoot = Objects.requireNonNull(templatesRoot, "templatesRoot must not be null")
                .toAbsolutePath().normalize();
    }

    /**
     * Saves an existing project directory as a named custom user template ({@code #0117}).
     *
     * @param sourceProjectDir existing source project directory
     * @param templateName     display/directory name for the template
     * @return path to the stored custom template directory
     * @throws IOException if copying fails
     */
    public Path saveAsTemplate(Path sourceProjectDir, String templateName) throws IOException {
        Objects.requireNonNull(templateName, "templateName must not be null");
        String safeName = templateName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        Files.createDirectories(templatesRoot);
        Path target = templatesRoot.resolve(safeName).normalize();
        refactorService.duplicateProject(sourceProjectDir, target);
        return target;
    }

    /**
     * Instantiates a saved custom user template into {@code destinationDir} ({@code #0117}).
     *
     * @param templateDir    custom template directory
     * @param destinationDir target directory for the newly created project
     * @return number of files copied
     * @throws IOException if copying fails
     */
    public int createProjectFromTemplate(Path templateDir, Path destinationDir) throws IOException {
        return refactorService.duplicateProject(templateDir, destinationDir);
    }

    /**
     * Returns preview cards for all built-in templates plus every custom user template stored
     * inside this repository ({@code #0117}, {@code #0147}).
     *
     * @return immutable list of {@link TemplatePreviewCard} descriptors
     * @throws IOException if directory inspection fails
     */
    public List<TemplatePreviewCard> listAllPreviewCards() throws IOException {
        List<TemplatePreviewCard> cards = new ArrayList<>();
        cards.add(builtInPreview(ProjectTemplateGenerator.TemplateType.FULL_DEMO));
        cards.add(builtInPreview(ProjectTemplateGenerator.TemplateType.ACTION_ADVENTURE_2D));
        cards.add(builtInPreview(ProjectTemplateGenerator.TemplateType.STARTER));
        cards.add(builtInPreview(ProjectTemplateGenerator.TemplateType.MINIMAL));

        if (Files.isDirectory(templatesRoot)) {
            try (var stream = Files.list(templatesRoot)) {
                for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                    String name = dir.getFileName().toString();
                    cards.add(new TemplatePreviewCard(
                            dir.toString(),
                            name,
                            "Benutzerdefinierte Vorlage aus Workspace (" + name + ")",
                            List.of("User Template", "Custom Assets"),
                            "#f0883e",
                            true
                    ));
                }
            }
        }
        return List.copyOf(cards);
    }

    /**
     * Returns the visual preview card for a built-in {@link ProjectTemplateGenerator.TemplateType} ({@code #0147}).
     *
     * @param type built-in template preset
     * @return {@link TemplatePreviewCard} metadata
     */
    public static TemplatePreviewCard builtInPreview(ProjectTemplateGenerator.TemplateType type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case FULL_DEMO -> new TemplatePreviewCard(
                    "FULL_DEMO",
                    "Vollständige Demo-Vorlage",
                    "Main Menu + Version, 6 Save/Load Slots + QuickSave, DE/EN i18n & WASD-Arena.",
                    List.of("Main Menu", "Save/Load", "DE/EN i18n", "WASD + Physik"),
                    "#388bfd",
                    false
            );
            case ACTION_ADVENTURE_2D -> new TemplatePreviewCard(
                    "ACTION_ADVENTURE_2D",
                    "Action-Adventure 2D",
                    "WASD-Steuerung, bewegliche Plattformen, Trigger-Zonen & Java-Inventar.",
                    List.of("WASD", "Plattformen", "Trigger-Zonen", "DE/EN"),
                    "#3fb950",
                    false
            );
            case STARTER -> new TemplatePreviewCard(
                    "STARTER",
                    "Klassischer Starter",
                    "Ausgewogene Visual-Novel-Vorlage mit Dialogverzweigung und Sound-Cues.",
                    List.of("Visual Novel", "Choices", "Audio"),
                    "#58a6ff",
                    false
            );
            case MINIMAL -> new TemplatePreviewCard(
                    "MINIMAL",
                    "Minimal-Vorlage",
                    "Schlankes Grundgerüst mit Start-Label und HUD-Badge für schnelle Prototypen.",
                    List.of("Minimal", "Fast Start"),
                    "#8b949e",
                    false
            );
        };
    }
}
