package de.swfdtf.demo.features;

import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.PersistentAccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Verwaltet den vollständigen Schneewittchen-Kapitelkatalog auf Basis von {@link ChapterRegistry}.
 *
 * <p>Registriert alle sieben Märchenkapitel ({@code kapitel_1} bis {@code kapitel_7}), ordnet
 * die exakten {@code .vjava}-Skript-Labels den entsprechenden Kapiteln zu und stellt
 * Hilfsmethoden zum Freischalten, Nachschlagen und zur formatierten Übersicht bereit.</p>
 */
public final class SnowWhiteChapterCatalog {

    /** Anzahl der registrierten Schneewittchen-Kapitel. */
    public static final int TOTAL_CHAPTERS = 7;

    private static final List<ChapterRegistry.ChapterDef> DEFAULT_CHAPTERS = List.of(
            new ChapterRegistry.ChapterDef(
                    "kapitel_1",
                    "Kapitel 1: Vorgeschichte & Wunsch der Königin",
                    "kapitel_1_vorgeschichte",
                    1,
                    "assets/images/bg/bg_schloss_winter.png",
                    "Am Ebenholzfenster wünscht sich die gute Königin ein Kind, so weiß wie Schnee."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_2",
                    "Kapitel 2: Die böse Stiefmutter & der Zauberspiegel",
                    "kapitel_2_stiefmutter_spiegel",
                    2,
                    "assets/images/bg/bg_spiegel_saal.png",
                    "Der Zauberspiegel verkündet, dass Schneewittchen tausendmal schöner ist als die Königin."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_3",
                    "Kapitel 3: Die Flucht in den tiefen Wald",
                    "kapitel_3_flucht_in_den_wald",
                    3,
                    "assets/images/bg/bg_wald.png",
                    "Der Jäger hat Mitleid mit Schneewittchen und lässt es in den wilden Wald entkommen."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_4",
                    "Kapitel 4: Das Haus der sieben Zwerge",
                    "kapitel_4_haus_der_zwerge",
                    4,
                    "assets/images/bg/bg_zwergenhaus.png",
                    "Hinter den sieben Bergen findet Schneewittchen Zuflucht im reinlichen Zwergenhäuschen."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_5",
                    "Kapitel 5: Die drei Mordversuche der Königin",
                    "kapitel_5_mordversuche",
                    5,
                    "assets/images/bg/bg_zwergenhaus.png",
                    "Schnürriemen, Giftkamm und Giftapfel bringen Schneewittchen in höchste Lebensgefahr."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_6",
                    "Kapitel 6: Der Glassarg & die Erlösung",
                    "kapitel_6_glassarg_und_erloesung",
                    6,
                    "assets/images/bg/bg_berg_glassarg.png",
                    "Auf dem Berg erwacht Schneewittchen aus dem Todesschlaf, als die Diener stolpern."
            ),
            new ChapterRegistry.ChapterDef(
                    "kapitel_7",
                    "Kapitel 7: Hochzeit & Strafe der bösen Königin",
                    "kapitel_7_hochzeit_und_ende",
                    7,
                    "assets/images/bg/bg_hochzeitssaal.png",
                    "Schneewittchen heiratet den Königssohn, während die böse Stiefmutter ihre gerechte Strafe erhält."
            )
    );

    private static final Map<String, String> LABEL_TO_CHAPTER_ID = createLabelMapping();

    private final ChapterRegistry registry;

    /**
     * Erzeugt einen neuen Kapitelkatalog mit einer internen {@link ChapterRegistry} über
     * {@link PersistentAccess} und registriert alle sieben Schneewittchen-Kapitel.
     *
     * @param persistentAccess spielstandübergreifender Persistenzspeicher (darf nicht {@code null} sein)
     */
    public SnowWhiteChapterCatalog(PersistentAccess persistentAccess) {
        this(new ChapterRegistry(Objects.requireNonNull(persistentAccess, "persistentAccess darf nicht null sein")));
    }

    /**
     * Erzeugt einen Kapitelkatalog um eine bestehende {@link ChapterRegistry}-Instanz
     * und registriert alle sieben Schneewittchen-Kapitel.
     *
     * @param registry zugrunde liegende {@link ChapterRegistry} (darf nicht {@code null} sein)
     */
    public SnowWhiteChapterCatalog(ChapterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry darf nicht null sein");
        registerAllChapters();
    }

    /**
     * Schaltet das zu einem {@code .vjava}-Skript-Label gehörende Kapitel frei, falls eine
     * Zuordnung existiert und das Kapitel noch nicht freigeschaltet war.
     *
     * @param label aktuelles {@code .vjava}-Skript-Label (darf nicht {@code null} sein)
     * @return {@code true}, wenn ein Kapitel neu freigeschaltet wurde, sonst {@code false}
     */
    public boolean unlockForLabel(String label) {
        Objects.requireNonNull(label, "label darf nicht null sein");
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String chapterId = LABEL_TO_CHAPTER_ID.get(trimmed);
        if (chapterId == null) {
            return false;
        }
        return registry.unlock(chapterId);
    }

    /**
     * Sucht eine registrierte Kapiteldefinition anhand ihrer Kapitel-ID ({@code kapitel_1}..{@code kapitel_7}).
     *
     * @param chapterId eindeutige Kapitel-ID (darf nicht {@code null} sein)
     * @return gefundenes {@link ChapterRegistry.ChapterDef} oder {@link Optional#empty()}
     */
    public Optional<ChapterRegistry.ChapterDef> findChapter(String chapterId) {
        Objects.requireNonNull(chapterId, "chapterId darf nicht null sein");
        String trimmed = chapterId.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return registry.findById(trimmed);
    }

    /**
     * Ermittelt optional die zugehörige Kapitel-ID für ein gegebenes {@code .vjava}-Skript-Label.
     *
     * @param label Skript-Label (darf nicht {@code null} sein)
     * @return {@link Optional} mit Kapitel-ID oder {@link Optional#empty()}, falls kein Kapitel zugeordnet ist
     */
    public Optional<String> resolveChapterIdForLabel(String label) {
        Objects.requireNonNull(label, "label darf nicht null sein");
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(LABEL_TO_CHAPTER_ID.get(trimmed));
    }

    /**
     * Liefert die zugrunde liegende {@link ChapterRegistry}.
     *
     * @return gekapselte {@link ChapterRegistry}
     */
    public ChapterRegistry getRegistry() {
        return registry;
    }

    /**
     * Erzeugt eine menschenlesbare, mehrzeilige Übersicht aller sieben Kapitel inklusive
     * Freischaltstatus und Gesamtfortschritt in Prozent.
     *
     * @return formatierte Kapitelübersicht
     */
    public String formatChapterOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                Locale.ROOT,
                "Schneewittchen-Kapitelübersicht (%d/%d freigeschaltet, %.1f%%)%n",
                registry.getUnlockedCount(),
                registry.getTotalCount(),
                registry.getCompletionPercent()
        ));
        for (ChapterRegistry.ChapterDef chapter : registry.listAll()) {
            boolean unlocked = registry.isUnlocked(chapter.id());
            sb.append(String.format(
                    Locale.ROOT,
                    "  [%s] %s (%s -> %s)%n",
                    unlocked ? "FREI" : "GESPERRT",
                    chapter.title(),
                    chapter.id(),
                    chapter.startLabel()
            ));
        }
        return sb.toString();
    }

    private void registerAllChapters() {
        for (ChapterRegistry.ChapterDef chapter : DEFAULT_CHAPTERS) {
            registry.register(chapter);
        }
    }

    private static Map<String, String> createLabelMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("kapitel_1_vorgeschichte", "kapitel_1");
        map.put("kapitel_2_stiefmutter_spiegel", "kapitel_2");
        map.put("kapitel_3_flucht_in_den_wald", "kapitel_3");
        map.put("kapitel_4_haus_der_zwerge", "kapitel_4");
        map.put("kapitel_5_mordversuche", "kapitel_5");
        map.put("anschlag_1_schnuerriemen", "kapitel_5");
        map.put("anschlag_2_giftkamm", "kapitel_5");
        map.put("anschlag_3_apfel", "kapitel_5");
        map.put("kapitel_6_glassarg_und_erloesung", "kapitel_6");
        map.put("kapitel_7_hochzeit_und_ende", "kapitel_7");
        return Map.copyOf(map);
    }
}
