package de.swfdtf.demo.service;

import de.swfdtf.audio.AudioEngine;
import de.swfdtf.core.EngineAPI;
import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.graphics.SceneController;
import de.swfdtf.graphics.SceneGraph;
import de.swfdtf.graphics.TextureManager;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.AchievementManager;
import de.swfdtf.state.EndingsManager;
import de.swfdtf.state.GalleryManager;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.Screen;
import de.swfdtf.ui.ScreenAstBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Zentraler Infrastruktur-Service für das Schneewittchen-vJava-Demo.
 *
 * <p>Kapselt Asset-Erzeugung, statisches Linting ({@link VJavaLinter}),
 * Projekt-Scanning ({@link ProjectScanner}), Laufzeit-Erzeugung ({@link ScriptSession}),
 * Meta-Registrierung (Achievements, Galerie, Endings) sowie GUI-Start und Standalone-Export.</p>
 */
public final class SnowWhiteEngineService {

    private final Path projectDir;

    /**
     * Erzeugt einen Service für das angegebene {@code .vjava}-Projektverzeichnis.
     *
     * @param projectDir Wurzelverzeichnis des Schneewittchen-{@code .vjava}-Projekts
     */
    public SnowWhiteEngineService(Path projectDir) {
        this.projectDir = Objects.requireNonNull(projectDir, "projectDir darf nicht null sein")
                .toAbsolutePath().normalize();
    }

    /**
     * Gibt den normalisierten Pfad zum {@code .vjava}-Projektverzeichnis zurück.
     *
     * @return Projektpfad
     */
    public Path getProjectDir() {
        return projectDir;
    }

    /**
     * Stellt alle prozeduralen PNG- und WAV-Assets sicher.
     *
     * @throws IOException falls die Asset-Erzeugung fehlschlägt
     */
    public void ensureAssets() throws IOException {
        SnowWhiteAssetGenerator.ensureAssets(projectDir);
    }

    /**
     * Scannt das Projekt und führt {@link VJavaLinter} aus.
     *
     * @return vollständiger {@link VJavaLinter.LintReport}
     */
    public VJavaLinter.LintReport runLint() {
        return VJavaLinter.lint(projectDir);
    }

    /**
     * Initialisiert eine vollständige Headless-{@link ScriptSession} inklusive {@link AudioEngine},
     * {@link SceneController}, Meta-Managern und kompilierten {@code @->{ ... }<<}-Java-Blöcken.
     *
     * @param savesDir Zielverzeichnis für Spielstände, {@code persistent.dat} und {@code preferences.json}
     * @return startbereite {@link SessionContext}-Ressource
     * @throws IOException falls das Laden von Konfigurations- oder Persistenzdateien fehlschlägt
     */
    public SessionContext createHeadlessSession(Path savesDir) throws IOException {
        ensureAssets();
        VJavaAst.ProjectAst projectAst = ProjectScanner.scan(projectDir);

        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STANDALONE_GAME)
                .projectDir(projectDir)
                .headless(true)
                .build();
        EngineAPI api = new EngineAPI(config);
        if (projectAst.descriptor() != null) {
            api.setProjectDescriptor(projectAst.descriptor());
        }

        SceneGraph sceneGraph = new SceneGraph();
        TextureManager textureManager = new TextureManager(api.getLogger(), projectDir);
        SceneController sceneController = new SceneController(
                sceneGraph, textureManager, api.getLogger(), projectDir, false
        );

        ScriptSession session = new ScriptSession(projectAst, api, sceneController, savesDir);
        AudioEngine audioEngine = new AudioEngine(
                projectDir,
                session.getPreferencesState(),
                session.getLocalizationManager(),
                api.getLogger(),
                true
        );
        audioEngine.init();
        session.getRuntime().setAudioBridge(audioEngine.createScriptAudioBridge());

        registerMetaDefinitions(session);
        session.getRuntime().compile();

        return new SessionContext(projectAst, api, textureManager, audioEngine, session);
    }

    /**
     * Baut den deklarativen HUD-Screen {@code maerchen_hud} über {@link ScreenAstBuilder}.
     *
     * @param session aktive {@link ScriptSession}
     * @return materialisierter {@link Screen} oder {@code null}, falls nicht definiert
     */
    public Screen buildHudScreen(ScriptSession session) {
        VJavaAst.ScreenDef hudDef = session.getRuntime().getProjectAst().screens().get("maerchen_hud");
        if (hudDef == null) {
            return null;
        }
        return ScreenAstBuilder.build(hudDef, session.getRuntime(), action -> { });
    }

    /**
     * Startet das Projekt im echten KAGAYAKI-Fenstermodus (Dual-Window-Studio oder Standalone-Spiel).
     *
     * @param mode gewünschter {@link EngineMode}
     * @throws IOException falls die Asset-Vorbereitung fehlschlägt
     */
    public void launchGui(EngineMode mode) throws IOException {
        ensureAssets();
        EngineConfig config = EngineConfig.builder()
                .engineMode(mode)
                .projectDir(projectDir)
                .headless(false)
                .build();
        try (EngineApplication app = new EngineApplication(config)) {
            app.run();
        }
    }

    /**
     * Exportiert das lint-geprüfte Projekt als {@code .vpak}-Bundle über {@link StandaloneBuilder}.
     *
     * @param outputDir Zielverzeichnis für das Export-Bundle
     * @return Build-Ergebnis mit Pfaden zu {@code game.vpak} und Startskripten
     * @throws IOException falls Linting oder Export fehlschlagen
     */
    public StandaloneBuilder.BuildResult exportStandalone(Path outputDir) throws IOException {
        ensureAssets();
        return StandaloneBuilder.build(projectDir, outputDir, null);
    }

    private static void registerMetaDefinitions(ScriptSession session) {
        AchievementManager achievements = session.getAchievementManager();
        achievements.register(new AchievementManager.AchievementDef(
                "ach_prolog", "Weiß wie Schnee, rot wie Blut",
                "Die Geburt Schneewittchens am Ebenholzfenster erlebt.", "", false));
        achievements.register(new AchievementManager.AchievementDef(
                "ach_flucht", "Barmherzigkeit im Wald",
                "Der Jäger verschont Schneewittchen im tiefen Wald.", "", false));
        achievements.register(new AchievementManager.AchievementDef(
                "ach_drei_anschlaege", "Dreifache List der Stiefmutter",
                "Schnürriemen, Giftkamm und Giftapfel überstanden bzw. erlitten.", "", false));
        achievements.register(new AchievementManager.AchievementDef(
                "ach_erloest", "Erlöst am Berg",
                "Das giftige Apfelstück löst sich beim Stolpern.", "", false));
        achievements.register(new AchievementManager.AchievementDef(
                "ach_happy_end", "Prachtvolle Hochzeit",
                "Schneewittchen und der Königssohn feiern Hochzeit.", "", false));

        GalleryManager gallery = session.getGalleryManager();
        gallery.register(new GalleryManager.GalleryEntry(
                "cg_winter_wunsch", "Winterwunsch am Fenster", "Prolog",
                "assets/images/bg/bg_schloss_winter.png",
                List.of("assets/images/bg/bg_schloss_winter.png"),
                "Die gute Königin näht am Ebenholzrahmen."));
        gallery.register(new GalleryManager.GalleryEntry(
                "cg_zauberspiegel", "Spieglein, Spieglein an der Wand", "Schloss",
                "assets/images/bg/bg_spiegel_saal.png",
                List.of("assets/images/bg/bg_spiegel_saal.png"),
                "Der Zauberspiegel verkündet die Wahrheit."));
        gallery.register(new GalleryManager.GalleryEntry(
                "cg_zwergenhaus", "Bei den sieben Zwergen", "Wald",
                "assets/images/bg/bg_zwergenhaus.png",
                List.of("assets/images/bg/bg_zwergenhaus.png"),
                "Das reinliche Häuschen hinter den sieben Bergen."));
        gallery.register(new GalleryManager.GalleryEntry(
                "cg_giftapfel", "Der halb rote Giftapfel", "Anschlag 3",
                "assets/images/char/stiefmutter_baeuerin.png",
                List.of("assets/images/char/stiefmutter_baeuerin.png"),
                "Die Stiefmutter verkleidet als Bäuerin."));
        gallery.register(new GalleryManager.GalleryEntry(
                "cg_erloesung", "Rettung am Glassarg", "Finale",
                "assets/images/bg/bg_berg_glassarg.png",
                List.of("assets/images/bg/bg_berg_glassarg.png"),
                "Die Diener stolpern und retten Schneewittchen."));

        EndingsManager endings = session.getEndingsManager();
        endings.register(new EndingsManager.EndingDef(
                "ending_hochzeit",
                "Hochzeit und Gerechtigkeit",
                "Schneewittchen erwacht, heiratet den Königssohn und die böse Königin wird bestraft.",
                "kapitel_7_hochzeit_und_ende",
                null));
    }

    /**
     * Gebündelte Laufzeit-Ressourcen für sauberes {@code try-with-resources}-Lifecycle-Management.
     */
    public record SessionContext(
            VJavaAst.ProjectAst projectAst,
            EngineAPI api,
            TextureManager textureManager,
            AudioEngine audioEngine,
            ScriptSession session
    ) implements AutoCloseable {

        @Override
        public void close() {
            audioEngine.dispose();
            textureManager.dispose();
        }
    }
}
