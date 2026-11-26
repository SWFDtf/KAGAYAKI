package de.swfdtf.ui;

import de.swfdtf.state.LocalizationManager;

import java.util.Locale;
import java.util.Map;

/**
 * Built-in five-language ({@code deutsch}, {@code english}, {@code french}, {@code spanish},
 * {@code japanese}) UI localization catalog for KAGAYAKI system screens, with project-level
 * {@link LocalizationManager} overrides and safe fallback to English for unknown locales.
 */
public final class UiTranslations {

    private static final Map<String, Map<String, String>> BUNDLES = Map.of(
            "deutsch", Map.ofEntries(
                    Map.entry("ui.start", "Starten"),
                    Map.entry("ui.continue", "Fortsetzen"),
                    Map.entry("ui.load", "Laden"),
                    Map.entry("ui.save", "Speichern"),
                    Map.entry("ui.save_game", "Spiel speichern"),
                    Map.entry("ui.load_game", "Spiel laden"),
                    Map.entry("ui.gallery", "Galerie"),
                    Map.entry("ui.music_room", "Musikraum"),
                    Map.entry("ui.endings", "Enden"),
                    Map.entry("ui.achievements", "Erfolge"),
                    Map.entry("ui.preferences", "Einstellungen"),
                    Map.entry("ui.controls", "Steuerung"),
                    Map.entry("ui.about", "Über"),
                    Map.entry("ui.quit", "Beenden"),
                    Map.entry("ui.back", "Zurück"),
                    Map.entry("ui.save_close", "Speichern & Schließen"),
                    Map.entry("ui.delete", "Löschen"),
                    Map.entry("ui.save_slots", "Speicherplätze"),
                    Map.entry("ui.quick_save", "Schnellspeichern"),
                    Map.entry("ui.auto_save", "Auto-Speichern"),
                    Map.entry("ui.save_label", "Titel:")
            ),
            "english", Map.ofEntries(
                    Map.entry("ui.start", "Start"),
                    Map.entry("ui.continue", "Continue"),
                    Map.entry("ui.load", "Load"),
                    Map.entry("ui.save", "Save"),
                    Map.entry("ui.save_game", "Save Game"),
                    Map.entry("ui.load_game", "Load Game"),
                    Map.entry("ui.gallery", "Gallery"),
                    Map.entry("ui.music_room", "Music Room"),
                    Map.entry("ui.endings", "Endings"),
                    Map.entry("ui.achievements", "Achievements"),
                    Map.entry("ui.preferences", "Preferences"),
                    Map.entry("ui.controls", "Controls"),
                    Map.entry("ui.about", "About"),
                    Map.entry("ui.quit", "Quit"),
                    Map.entry("ui.back", "Back"),
                    Map.entry("ui.save_close", "Save & Close"),
                    Map.entry("ui.delete", "Delete"),
                    Map.entry("ui.save_slots", "Save Slots"),
                    Map.entry("ui.quick_save", "Quick Save"),
                    Map.entry("ui.auto_save", "Auto Save"),
                    Map.entry("ui.save_label", "Save Label:")
            ),
            "french", Map.ofEntries(
                    Map.entry("ui.start", "Démarrer"),
                    Map.entry("ui.continue", "Continuer"),
                    Map.entry("ui.load", "Charger"),
                    Map.entry("ui.save", "Sauvegarder"),
                    Map.entry("ui.save_game", "Sauvegarder la partie"),
                    Map.entry("ui.load_game", "Charger la partie"),
                    Map.entry("ui.gallery", "Galerie"),
                    Map.entry("ui.music_room", "Salle de musique"),
                    Map.entry("ui.endings", "Fins"),
                    Map.entry("ui.achievements", "Succès"),
                    Map.entry("ui.preferences", "Paramètres"),
                    Map.entry("ui.controls", "Contrôles"),
                    Map.entry("ui.about", "À propos"),
                    Map.entry("ui.quit", "Quitter"),
                    Map.entry("ui.back", "Retour"),
                    Map.entry("ui.save_close", "Enregistrer & Fermer"),
                    Map.entry("ui.delete", "Supprimer"),
                    Map.entry("ui.save_slots", "Emplacements"),
                    Map.entry("ui.quick_save", "Sauvegarde rapide"),
                    Map.entry("ui.auto_save", "Sauvegarde auto"),
                    Map.entry("ui.save_label", "Nom :")
            ),
            "spanish", Map.ofEntries(
                    Map.entry("ui.start", "Comenzar"),
                    Map.entry("ui.continue", "Continuar"),
                    Map.entry("ui.load", "Cargar"),
                    Map.entry("ui.save", "Guardar"),
                    Map.entry("ui.save_game", "Guardar partida"),
                    Map.entry("ui.load_game", "Cargar partida"),
                    Map.entry("ui.gallery", "Galería"),
                    Map.entry("ui.music_room", "Sala de música"),
                    Map.entry("ui.endings", "Finales"),
                    Map.entry("ui.achievements", "Logros"),
                    Map.entry("ui.preferences", "Ajustes"),
                    Map.entry("ui.controls", "Controles"),
                    Map.entry("ui.about", "Acerca de"),
                    Map.entry("ui.quit", "Salir"),
                    Map.entry("ui.back", "Volver"),
                    Map.entry("ui.save_close", "Guardar y cerrar"),
                    Map.entry("ui.delete", "Eliminar"),
                    Map.entry("ui.save_slots", "Ranuras"),
                    Map.entry("ui.quick_save", "Guardado rápido"),
                    Map.entry("ui.auto_save", "Autoguardado"),
                    Map.entry("ui.save_label", "Título:")
            ),
            "japanese", Map.ofEntries(
                    Map.entry("ui.start", "スタート"),
                    Map.entry("ui.continue", "つづきから"),
                    Map.entry("ui.load", "ロード"),
                    Map.entry("ui.save", "セーブ"),
                    Map.entry("ui.save_game", "ゲームをセーブ"),
                    Map.entry("ui.load_game", "ゲームをロード"),
                    Map.entry("ui.gallery", "ギャラリー"),
                    Map.entry("ui.music_room", "ミュージック"),
                    Map.entry("ui.endings", "エンディング"),
                    Map.entry("ui.achievements", "実績"),
                    Map.entry("ui.preferences", "設定"),
                    Map.entry("ui.controls", "操作方法"),
                    Map.entry("ui.about", "情報"),
                    Map.entry("ui.quit", "終了"),
                    Map.entry("ui.back", "戻る"),
                    Map.entry("ui.save_close", "保存して閉じる"),
                    Map.entry("ui.delete", "削除"),
                    Map.entry("ui.save_slots", "セーブスロット"),
                    Map.entry("ui.quick_save", "クイックセーブ"),
                    Map.entry("ui.auto_save", "オートセーブ"),
                    Map.entry("ui.save_label", "タイトル:")
            )
    );

    private UiTranslations() {
    }

    /**
     * Resolves a localized UI string via project {@link LocalizationManager} overrides first,
     * then the built-in 5-language catalog, and finally English / {@code fallback}.
     *
     * @param localizationManager active localization manager (nullable)
     * @param key                 canonical UI translation key (e.g. {@code "ui.start"})
     * @param fallback            non-blank default fallback string
     * @return non-blank localized label string
     */
    public static String resolve(LocalizationManager localizationManager, String key, String fallback) {
        if (localizationManager != null) {
            String overridden = localizationManager.translateUi(key, "");
            if (overridden != null && !overridden.isBlank()) {
                return overridden;
            }
            String fromBundle = lookupBuiltIn(localizationManager.getCurrentLanguage(), key);
            if (fromBundle != null && !fromBundle.isBlank()) {
                return fromBundle;
            }
        }
        String english = BUNDLES.get("english").get(key);
        if (english != null && !english.isBlank()) {
            return english;
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : key;
    }

    private static String lookupBuiltIn(String language, String key) {
        String normalized = normalizeLanguage(language);
        Map<String, String> bundle = BUNDLES.get(normalized);
        if (bundle != null) {
            String value = bundle.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return BUNDLES.get("english").get(key);
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "english";
        }
        String lower = language.trim().toLowerCase(Locale.ROOT);
        int sep = lower.indexOf('_');
        if (sep < 0) {
            sep = lower.indexOf('-');
        }
        String base = sep > 0 ? lower.substring(0, sep) : lower;
        return switch (base) {
            case "de", "deu", "ger", "deutsch", "german" -> "deutsch";
            case "en", "eng", "english" -> "english";
            case "fr", "fra", "fre", "french", "français", "francais" -> "french";
            case "es", "spa", "spanish", "español", "espanol" -> "spanish";
            case "ja", "jp", "jpn", "japanese", "日本語" -> "japanese";
            default -> "english";
        };
    }
}
