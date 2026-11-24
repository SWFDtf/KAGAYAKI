package de.swfdtf.demo.model;

/**
 * Unveränderliches Datenmodell für einen der drei Mordversuche der bösen Königin.
 *
 * @param versuchsNummer laufende Nummer (1 = Schnürriemen, 2 = Giftkamm, 3 = Vergifteter Apfel)
 * @param verkleidung    Rolle, in der die Königin auftritt
 * @param giftMittel     eingesetzter Gegenstand
 * @param gerettet       {@code true}, wenn die Zwerge Schneewittchen retten konnten
 */
public record MurderAttempt(
        int versuchsNummer,
        String verkleidung,
        String giftMittel,
        boolean gerettet
) {
}
