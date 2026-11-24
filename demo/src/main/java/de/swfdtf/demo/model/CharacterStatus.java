package de.swfdtf.demo.model;

/**
 * Fachlicher Lebens- und Erzählzustand einer Figur im Märchen „Schneewittchen“.
 *
 * <p>Spiegelt exakt das in {@code main.vjava} innerhalb des {@code @->{ ... }<<}-Blocks
 * definierte {@code FigurZustand}-Enum auf Java-Anwendungsebene wider.</p>
 */
public enum CharacterStatus {
    LEBENDIG("Lebendig", "Wach und wohlauf"),
    SCHLAFEND("Schlafend", "Im siebten Zwergenbettchen eingeschlafen"),
    OHNMAECHTIG("Ohnmächtig", "Durch den Schnürriemen ohne Atem"),
    VERGIFTET("Vergiftet", "Durch Giftkamm oder Giftapfel wie tot"),
    ERLOEST("Erlöst", "Apfelstück aus dem Hals gelöst, wieder erwacht"),
    BESTRAFT("Bestraft", "In rotglühenden eisernen Pantoffeln gerichtet");

    private final String label;
    private final String description;

    CharacterStatus(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /**
     * Gibt den deutschsprachigen Anzeigenamen des Zustands zurück.
     *
     * @return Anzeigename
     */
    public String getLabel() {
        return label;
    }

    /**
     * Gibt eine kurze Erläuterung des Zustands zurück.
     *
     * @return Zustandsbeschreibung
     */
    public String getDescription() {
        return description;
    }

    /**
     * Löst einen gespeicherten Status-String aus dem {@code StateStore} sicher in ein {@link CharacterStatus}-Enum auf.
     *
     * @param raw roher Statusname (z. B. {@code "VERGIFTET"})
     * @return passender {@link CharacterStatus}, Standardwert {@link #LEBENDIG}
     */
    public static CharacterStatus fromStateValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return LEBENDIG;
        }
        try {
            return CharacterStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return LEBENDIG;
        }
    }
}
