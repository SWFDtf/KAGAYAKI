package de.swfdtf.demo.features;

import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.PersistentAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Verwaltet den Märchen-Codex für das Schneewittchen-Demo auf Basis von {@link CodexGlossaryManager}.
 *
 * <p>Registriert acht zentrale Figuren, magische Gegenstände und Schlüsselmotive des Märchens und
 * schaltet diese deterministisch frei, sobald die entsprechenden {@code .vjava}-Skript-Labels
 * im Spielverlauf erreicht werden.</p>
 */
public final class SnowWhiteCodexCatalog {

    /** Anzahl der registrierten Schneewittchen-Codex-Einträge. */
    public static final int TOTAL_ENTRIES = 8;

    private static final List<CodexGlossaryManager.CodexEntry> DEFAULT_ENTRIES = List.of(
            new CodexGlossaryManager.CodexEntry(
                    "schneewittchen",
                    "Schneewittchen",
                    "Charaktere",
                    "Die Königstochter, weiß wie Schnee, rot wie Blut und schwarz wie Ebenholz.",
                    "Schneewittchen wächst nach dem Tod seiner leiblichen Mutter am Hof auf und übertrifft an Schönheit selbst die eitle Stiefmutter."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "zauberspiegel",
                    "Der Zauberspiegel",
                    "Artefakte",
                    "Ein sprechender Spiegel, der stets die ungeschönte Wahrheit verkündet.",
                    "Auf die Frage der Königin nennt der Zauberspiegel ohne Furcht die schönste Frau im ganzen Land — und entfacht so ihren Zorn."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "sieben_zwerge",
                    "Die sieben Zwerge",
                    "Charaktere",
                    "Sieben fleißige Bergleute hinter den sieben Bergen.",
                    "Die Zwerge arbeiten tagsüber in den Bergen nach Erz und gewähren dem geflohenen Schneewittchen Schutz und ein Zuhause."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "schnuerriemen",
                    "Der bunte Schnürriemen",
                    "Anschläge",
                    "Das erste Mordwerkzeug der als Krämerin verkleideten Stiefmutter.",
                    "Mit einem farbenfrohen Schnürriemen zieht die Königin Schneewittchen so fest zu, dass ihm der Atem vergeht."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "giftkamm",
                    "Der vergiftete Kamm",
                    "Anschläge",
                    "Ein durch Hexenkünste vergifteter Haarkamm.",
                    "Kaum ins Haar gesteckt, entfaltet das Gift seine Wirkung und lässt Schneewittchen besinnungslos zusammensinken."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "giftapfel",
                    "Der halb rote Giftapfel",
                    "Anschläge",
                    "Ein kunstvoller Apfel, dessen weiße Hälfte harmlos und dessen rote Backe tödlich ist.",
                    "Durch den Biss in die vergiftete rote Hälfte fällt Schneewittchen in einen todesähnlichen Schlaf, bis das Stück aus dem Hals gelöst wird."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "glassarg",
                    "Der gläserne Sarg",
                    "Relikte",
                    "Ein durchsichtiger Sarg auf dem Berg mit goldener Inschrift.",
                    "Die sieben Zwerge fertigen einen Sarg aus Glas, damit Schneewittchen von allen Seiten sichtbar bleibt und behütet wird."
            ),
            new CodexGlossaryManager.CodexEntry(
                    "koenigssohn",
                    "Der Königssohn",
                    "Charaktere",
                    "Ein Prinz, der Schneewittchen im Glassarg erblickt und über alles liebt.",
                    "Er erbittet von den Zwergen den Sarg; beim Tragen stolpern seine Diener über einen Strauch und retten dadurch Schneewittchen."
            )
    );

    private static final Map<String, List<String>> LABEL_TO_CODEX_IDS = createLabelMapping();

    private final CodexGlossaryManager manager;

    /**
     * Erzeugt einen Codex-Katalog mit einem neuen {@link CodexGlossaryManager} über
     * {@link PersistentAccess} und registriert alle acht Märchen-Einträge.
     *
     * @param persistentAccess spielstandübergreifender Persistenzspeicher (darf nicht {@code null} sein)
     */
    public SnowWhiteCodexCatalog(PersistentAccess persistentAccess) {
        this(new CodexGlossaryManager(Objects.requireNonNull(persistentAccess, "persistentAccess darf nicht null sein")));
    }

    /**
     * Erzeugt einen Codex-Katalog um einen bestehenden {@link CodexGlossaryManager}
     * und registriert alle acht Märchen-Einträge.
     *
     * @param manager zugrunde liegender {@link CodexGlossaryManager} (darf nicht {@code null} sein)
     */
    public SnowWhiteCodexCatalog(CodexGlossaryManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager darf nicht null sein");
        registerAllEntries();
    }

    /**
     * Schaltet alle dem übergebenen {@code .vjava}-Label zugeordneten Codex-Einträge frei.
     *
     * @param label aktuelles Skript-Label (darf nicht {@code null} sein)
     * @return unveränderliche Liste der bei diesem Aufruf neu freigeschalteten Codex-IDs
     */
    public List<String> unlockForLabel(String label) {
        Objects.requireNonNull(label, "label darf nicht null sein");
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> candidates = LABEL_TO_CODEX_IDS.get(trimmed);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> newlyUnlocked = new ArrayList<>();
        for (String entryId : candidates) {
            if (manager.unlock(entryId)) {
                newlyUnlocked.add(entryId);
            }
        }
        return List.copyOf(newlyUnlocked);
    }

    /**
     * Liefert den zugrunde liegenden {@link CodexGlossaryManager}.
     *
     * @return gekapselter {@link CodexGlossaryManager}
     */
    public CodexGlossaryManager getManager() {
        return manager;
    }

    /**
     * Erzeugt eine formatierte Zusammenfassung aller registrierten Codex-Einträge inklusive
     * Freischaltstatus, Kategorie und Gesamtfortschritt.
     *
     * @return formatierte Codex-Übersicht
     */
    public String formatCodexSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                Locale.ROOT,
                "Schneewittchen-Codex (%d/%d Einträge, %.1f%%)%n",
                manager.getUnlockedCount(),
                manager.getTotalCount(),
                manager.getUnlockProgressPercent()
        ));
        for (CodexGlossaryManager.CodexEntry entry : manager.listAll()) {
            boolean unlocked = manager.isUnlocked(entry.id());
            sb.append(String.format(
                    Locale.ROOT,
                    "  [%s] [%s] %s (%s)",
                    unlocked ? "FREI" : "GESPERRT",
                    entry.category(),
                    entry.title(),
                    entry.id()
            ));
            if (unlocked && !entry.summary().isEmpty()) {
                sb.append(" — ").append(entry.summary());
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    private void registerAllEntries() {
        for (CodexGlossaryManager.CodexEntry entry : DEFAULT_ENTRIES) {
            manager.register(entry);
        }
    }

    private static Map<String, List<String>> createLabelMapping() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("kapitel_1_vorgeschichte", List.of("schneewittchen"));
        map.put("kapitel_2_stiefmutter_spiegel", List.of("zauberspiegel"));
        map.put("zauberspiegel_befragen", List.of("zauberspiegel"));
        map.put("kapitel_3_flucht_in_den_wald", List.of("schneewittchen"));
        map.put("kapitel_4_haus_der_zwerge", List.of("sieben_zwerge"));
        map.put("kapitel_5_mordversuche", List.of("zauberspiegel"));
        map.put("anschlag_1_schnuerriemen", List.of("schnuerriemen"));
        map.put("anschlag_2_giftkamm", List.of("giftkamm"));
        map.put("anschlag_3_apfel", List.of("giftapfel"));
        map.put("kapitel_6_glassarg_und_erloesung", List.of("glassarg", "koenigssohn"));
        map.put("kapitel_7_hochzeit_und_ende", List.of("koenigssohn"));
        return Map.copyOf(map);
    }
}
