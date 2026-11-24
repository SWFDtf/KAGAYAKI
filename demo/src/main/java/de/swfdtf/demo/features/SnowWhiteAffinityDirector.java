package de.swfdtf.demo.features;

import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.StateStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Steuert die Beziehungen und Vertrauenswerte der zentralen Schneewittchen-Figuren über
 * {@link RelationshipAffinityTracker}.
 *
 * <p>Initialisiert die vier Figuren {@code jaeger}, {@code zwerge}, {@code prinz} und
 * {@code koenigin} und übersetzt definierte Handlungs-Meilensteine in deterministische
 * Affinitätsänderungen.</p>
 */
public final class SnowWhiteAffinityDirector {

    /** Figur-ID für den barmherzigen Jäger. */
    public static final String CHAR_JAEGER = "jaeger";

    /** Figur-ID für die sieben Zwerge. */
    public static final String CHAR_ZWERGE = "zwerge";

    /** Figur-ID für den Königssohn. */
    public static final String CHAR_PRINZ = "prinz";

    /** Figur-ID für die böse Königin. */
    public static final String CHAR_KOENIGIN = "koenigin";

    /** Meilenstein: Der Jäger verschont Schneewittchen im Wald. */
    public static final String MILESTONE_JAEGER_MERCY = "jaeger_mercy";

    /** Meilenstein: Die sieben Zwerge nehmen Schneewittchen freundlich auf. */
    public static final String MILESTONE_DWARF_HOSPITALITY = "dwarf_hospitality";

    /** Meilenstein: Schneewittchen verspricht den Zwergen Vorsicht vor Fremden. */
    public static final String MILESTONE_DWARF_WARNING_PROMISE = "dwarf_warning_promise";

    /** Meilenstein: Die böse Königin verübt Gift- und Mordanschläge. */
    public static final String MILESTONE_QUEEN_POISON_ATTEMPT = "queen_poison_attempt";

    /** Meilenstein: Der Königssohn rettet und erweckt Schneewittchen am Glassarg. */
    public static final String MILESTONE_PRINCE_RESCUE = "prince_rescue";

    /** Meilenstein: Feierliche Hochzeit und Bestrafung der Stiefmutter. */
    public static final String MILESTONE_FINAL_WEDDING = "final_wedding";

    private record AffinityDelta(String characterId, int pointsDelta) {
    }

    private static final Map<String, String> DISPLAY_NAMES = createDisplayNames();
    private static final Map<String, List<AffinityDelta>> MILESTONE_EFFECTS = createMilestoneEffects();

    private final RelationshipAffinityTracker tracker;

    /**
     * Erzeugt einen {@link SnowWhiteAffinityDirector} mit speicherinternem
     * {@link RelationshipAffinityTracker}.
     */
    public SnowWhiteAffinityDirector() {
        this(new RelationshipAffinityTracker());
    }

    /**
     * Erzeugt einen {@link SnowWhiteAffinityDirector}, dessen Affinitätswerte direkt in einem
     * {@link StateStore} (z. B. für Rollback und Spielstände) synchronisiert werden.
     *
     * @param stateStore zugrunde liegender {@link StateStore} (darf nicht {@code null} sein)
     */
    public SnowWhiteAffinityDirector(StateStore stateStore) {
        this(new RelationshipAffinityTracker(Objects.requireNonNull(stateStore, "stateStore darf nicht null sein")));
    }

    /**
     * Erzeugt einen {@link SnowWhiteAffinityDirector} um einen bestehenden
     * {@link RelationshipAffinityTracker} und initialisiert fehlende Figuren mit {@code 0} Punkten.
     *
     * @param tracker zu verwendender {@link RelationshipAffinityTracker} (darf nicht {@code null} sein)
     */
    public SnowWhiteAffinityDirector(RelationshipAffinityTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker darf nicht null sein");
        initializeCharacters();
    }

    /**
     * Wendet einen vordefinierten Story-Meilenstein auf die beteiligten Figuren an.
     *
     * @param milestoneKey eindeutiger Meilenstein-Schlüssel (darf nicht {@code null} oder leer sein)
     * @throws IllegalArgumentException falls {@code milestoneKey} leer oder unbekannt ist
     */
    public void applyStoryMilestone(String milestoneKey) {
        Objects.requireNonNull(milestoneKey, "milestoneKey darf nicht null sein");
        String normalized = milestoneKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("milestoneKey darf nicht leer sein");
        }
        List<AffinityDelta> effects = MILESTONE_EFFECTS.get(normalized);
        if (effects == null) {
            throw new IllegalArgumentException("Unbekannter Story-Meilenstein: " + milestoneKey);
        }
        for (AffinityDelta delta : effects) {
            tracker.addPoints(delta.characterId(), delta.pointsDelta());
        }
    }

    /**
     * Liefert die unveränderliche Menge aller unterstützten Story-Meilenstein-Schlüssel.
     *
     * @return unterstützte Meilenstein-Schlüssel
     */
    public Set<String> getSupportedMilestones() {
        return MILESTONE_EFFECTS.keySet();
    }

    /**
     * Liefert den zugrunde liegenden {@link RelationshipAffinityTracker}.
     *
     * @return gekapselter {@link RelationshipAffinityTracker}
     */
    public RelationshipAffinityTracker getTracker() {
        return tracker;
    }

    /**
     * Erzeugt eine formatierte Zusammenfassung aller vier Figuren mit aktuellem Punktestand und
     * qualitativer Beziehungsstufe ({@link RelationshipAffinityTracker.RelationshipTier}).
     *
     * @return formatierte Beziehungsübersicht
     */
    public String formatRelationshipSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Schneewittchen-Beziehungsübersicht:").append(System.lineSeparator());
        for (Map.Entry<String, String> entry : DISPLAY_NAMES.entrySet()) {
            String id = entry.getKey();
            String displayName = entry.getValue();
            int points = tracker.getPoints(id);
            RelationshipAffinityTracker.RelationshipTier tier = tracker.getTier(id);
            sb.append(String.format(
                    Locale.ROOT,
                    "  - %s (%s): %d Punkte [%s]%n",
                    displayName,
                    id,
                    points,
                    tier.name()
            ));
        }
        return sb.toString();
    }

    private void initializeCharacters() {
        Map<String, Integer> existing = tracker.getAllAffinities();
        for (String characterId : DISPLAY_NAMES.keySet()) {
            if (!existing.containsKey(characterId)) {
                tracker.setPoints(characterId, 0);
            }
        }
    }

    private static Map<String, String> createDisplayNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(CHAR_JAEGER, "Der Jäger");
        map.put(CHAR_ZWERGE, "Die sieben Zwerge");
        map.put(CHAR_PRINZ, "Der Königssohn");
        map.put(CHAR_KOENIGIN, "Die böse Königin");
        return Map.copyOf(map);
    }

    private static Map<String, List<AffinityDelta>> createMilestoneEffects() {
        Map<String, List<AffinityDelta>> map = new LinkedHashMap<>();
        map.put(MILESTONE_JAEGER_MERCY, List.of(
                new AffinityDelta(CHAR_JAEGER, 25),
                new AffinityDelta(CHAR_KOENIGIN, -15)
        ));
        map.put(MILESTONE_DWARF_HOSPITALITY, List.of(
                new AffinityDelta(CHAR_ZWERGE, 30)
        ));
        map.put(MILESTONE_DWARF_WARNING_PROMISE, List.of(
                new AffinityDelta(CHAR_ZWERGE, 20)
        ));
        map.put(MILESTONE_QUEEN_POISON_ATTEMPT, List.of(
                new AffinityDelta(CHAR_KOENIGIN, -35),
                new AffinityDelta(CHAR_ZWERGE, 15)
        ));
        map.put(MILESTONE_PRINCE_RESCUE, List.of(
                new AffinityDelta(CHAR_PRINZ, 60),
                new AffinityDelta(CHAR_ZWERGE, 10)
        ));
        map.put(MILESTONE_FINAL_WEDDING, List.of(
                new AffinityDelta(CHAR_PRINZ, 30),
                new AffinityDelta(CHAR_ZWERGE, 15),
                new AffinityDelta(CHAR_KOENIGIN, -50)
        ));
        return Map.copyOf(map);
    }
}
