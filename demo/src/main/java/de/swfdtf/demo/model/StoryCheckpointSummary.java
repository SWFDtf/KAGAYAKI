package de.swfdtf.demo.model;

/**
 * Zusammenfassung eines ausgeführten Story-Schritts inklusive aktuellem vJava-Zustand.
 *
 * @param schritt             fortlaufender Schrittzähler
 * @param kapitelNummer       aktuelles Kapitel (1–7)
 * @param kapitelTitel        Kapiteltitel aus dem vJava-Skript
 * @param labelName           aktives {@code .vjava}-Label
 * @param buehnenHintergrund  aktueller Hintergrund-Tag in {@code SceneController}
 * @param schneewittchenState Zustand Schneewittchens
 * @param anschlagsZaehler    Anzahl bisheriger Anschläge
 */
public record StoryCheckpointSummary(
        int schritt,
        int kapitelNummer,
        String kapitelTitel,
        String labelName,
        String buehnenHintergrund,
        CharacterStatus schneewittchenState,
        int anschlagsZaehler
) {
}
