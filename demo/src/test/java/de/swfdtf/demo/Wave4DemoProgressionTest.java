package de.swfdtf.demo;

import de.swfdtf.demo.features.SnowWhiteAffinityDirector;
import de.swfdtf.demo.features.SnowWhiteChapterCatalog;
import de.swfdtf.demo.features.SnowWhiteCodexCatalog;
import de.swfdtf.demo.features.SnowWhiteQuoteBook;
import de.swfdtf.demo.features.SnowWhiteReadingStats;
import de.swfdtf.demo.service.SnowWhiteEngineService;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.ScriptRuntime;
import de.swfdtf.parser.ScriptSession;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.state.ChapterRegistry;
import de.swfdtf.state.CodexGlossaryManager;
import de.swfdtf.state.PersistentAccess;
import de.swfdtf.state.RelationshipAffinityTracker;
import de.swfdtf.state.SimplePersistentAccess;
import de.swfdtf.state.SimpleStateStore;
import de.swfdtf.state.StateStore;
import de.swfdtf.tools.ScriptWordCountAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Umfassende JUnit-6- und Mockito-Tests für die Schneewittchen-Progression-, Codex-,
 * Affinitäts-, Zitatbuch- und Lesestatistik-Funktionen in {@code de.swfdtf.demo.features}.
 */
@ExtendWith(MockitoExtension.class)
class Wave4DemoProgressionTest {

    private static final List<String> ALL_SNOW_WHITE_LABELS = List.of(
            "kapitel_1_vorgeschichte",
            "kapitel_2_stiefmutter_spiegel",
            "zauberspiegel_befragen",
            "kapitel_3_flucht_in_den_wald",
            "kapitel_4_haus_der_zwerge",
            "kapitel_5_mordversuche",
            "anschlag_1_schnuerriemen",
            "anschlag_2_giftkamm",
            "anschlag_3_apfel",
            "kapitel_6_glassarg_und_erloesung",
            "kapitel_7_hochzeit_und_ende"
    );

    @Mock
    private ChapterRegistry mockedChapterRegistry;

    @Mock
    private CodexGlossaryManager mockedCodexManager;

    @Mock
    private StateStore mockedStateStore;

    private static Path resolveProjectDir() {
        Path direct = Path.of("project");
        return Files.isDirectory(direct) ? direct : Path.of("demo", "project");
    }

    // -------------------------------------------------------------------------
    // 1. SnowWhiteChapterCatalog
    // -------------------------------------------------------------------------

    @Test
    void chapterCatalogRegistersSevenChaptersAndUnlocksByExactLabels() {
        PersistentAccess persistent = new SimplePersistentAccess();
        SnowWhiteChapterCatalog catalog = new SnowWhiteChapterCatalog(persistent);

        assertEquals(7, catalog.getRegistry().getTotalCount());
        assertEquals(0, catalog.getRegistry().getUnlockedCount());

        Optional<ChapterRegistry.ChapterDef> ch1 = catalog.findChapter("kapitel_1");
        assertTrue(ch1.isPresent());
        assertEquals("kapitel_1_vorgeschichte", ch1.get().startLabel());
        assertTrue(catalog.findChapter("unbekannt").isEmpty());
        assertTrue(catalog.findChapter("   ").isEmpty());

        assertTrue(catalog.unlockForLabel("kapitel_1_vorgeschichte"));
        assertFalse(catalog.unlockForLabel("kapitel_1_vorgeschichte"), "Wiederholtes Freischalten liefert false");
        assertFalse(catalog.unlockForLabel("zauberspiegel_befragen"), "Subroutine schaltet kein neues Kapitel frei");
        assertFalse(catalog.unlockForLabel("   "));
        assertFalse(catalog.unlockForLabel("nicht_vorhanden"));

        assertTrue(catalog.unlockForLabel("kapitel_2_stiefmutter_spiegel"));
        assertTrue(catalog.unlockForLabel("kapitel_3_flucht_in_den_wald"));
        assertTrue(catalog.unlockForLabel("kapitel_4_haus_der_zwerge"));
        assertTrue(catalog.unlockForLabel("anschlag_1_schnuerriemen"));
        assertFalse(catalog.unlockForLabel("kapitel_5_mordversuche"), "Kapitel 5 wurde bereits durch Anschlag 1 freigeschaltet");
        assertFalse(catalog.unlockForLabel("anschlag_2_giftkamm"));
        assertFalse(catalog.unlockForLabel("anschlag_3_apfel"));
        assertTrue(catalog.unlockForLabel("kapitel_6_glassarg_und_erloesung"));
        assertTrue(catalog.unlockForLabel("kapitel_7_hochzeit_und_ende"));

        assertEquals(7, catalog.getRegistry().getUnlockedCount());
        assertEquals(100.0, catalog.getRegistry().getCompletionPercent(), 0.001);

        String overview = catalog.formatChapterOverview();
        assertTrue(overview.contains("7/7 freigeschaltet"));
        assertTrue(overview.contains("Kapitel 1: Vorgeschichte & Wunsch der Königin"));
        assertTrue(overview.contains("kapitel_7_hochzeit_und_ende"));
    }

    @Test
    void chapterCatalogGuardsNullsAndDelegatesToMockedRegistry() {
        when(mockedChapterRegistry.unlock("kapitel_3")).thenReturn(true);

        SnowWhiteChapterCatalog catalog = new SnowWhiteChapterCatalog(mockedChapterRegistry);
        verify(mockedChapterRegistry, times(7)).register(any(ChapterRegistry.ChapterDef.class));

        assertTrue(catalog.unlockForLabel("kapitel_3_flucht_in_den_wald"));
        verify(mockedChapterRegistry).unlock("kapitel_3");

        assertThrows(NullPointerException.class, () -> new SnowWhiteChapterCatalog((PersistentAccess) null));
        assertThrows(NullPointerException.class, () -> new SnowWhiteChapterCatalog((ChapterRegistry) null));
        assertThrows(NullPointerException.class, () -> catalog.unlockForLabel(null));
        assertThrows(NullPointerException.class, () -> catalog.findChapter(null));
    }

    // -------------------------------------------------------------------------
    // 2. SnowWhiteCodexCatalog
    // -------------------------------------------------------------------------

    @Test
    void codexCatalogRegistersEightEntriesAndUnlocksIdempotently() {
        PersistentAccess persistent = new SimplePersistentAccess();
        SnowWhiteCodexCatalog codex = new SnowWhiteCodexCatalog(persistent);

        assertEquals(8, codex.getManager().getTotalCount());
        assertEquals(0, codex.getManager().getUnlockedCount());

        for (String label : ALL_SNOW_WHITE_LABELS) {
            List<String> unlocked = codex.unlockForLabel(label);
            assertThrows(UnsupportedOperationException.class, () -> unlocked.add("illegal"));
        }

        assertEquals(8, codex.getManager().getUnlockedCount());
        assertEquals(100.0, codex.getManager().getUnlockProgressPercent(), 0.001);

        for (String expectedId : List.of(
                "schneewittchen", "zauberspiegel", "sieben_zwerge",
                "schnuerriemen", "giftkamm", "giftapfel", "glassarg", "koenigssohn")) {
            assertTrue(codex.getManager().isUnlocked(expectedId), "Fehlender Codex-Eintrag: " + expectedId);
        }

        assertTrue(codex.unlockForLabel("anschlag_3_apfel").isEmpty(), "Bereits freigeschaltet -> leere Liste");
        assertTrue(codex.unlockForLabel("   ").isEmpty());
        assertTrue(codex.unlockForLabel("unbekanntes_label").isEmpty());

        String summary = codex.formatCodexSummary();
        assertTrue(summary.contains("8/8 Einträge"));
        assertTrue(summary.contains("Der halb rote Giftapfel"));
        assertTrue(summary.contains("Der gläserne Sarg"));
    }

    @Test
    void codexCatalogDelegatesToMockedCodexManagerAndGuardsNulls() {
        when(mockedCodexManager.unlock("glassarg")).thenReturn(true);
        when(mockedCodexManager.unlock("koenigssohn")).thenReturn(false);

        SnowWhiteCodexCatalog codex = new SnowWhiteCodexCatalog(mockedCodexManager);
        verify(mockedCodexManager, times(8)).register(any(CodexGlossaryManager.CodexEntry.class));

        List<String> result = codex.unlockForLabel("kapitel_6_glassarg_und_erloesung");
        assertEquals(List.of("glassarg"), result);
        verify(mockedCodexManager).unlock("glassarg");
        verify(mockedCodexManager).unlock("koenigssohn");

        assertThrows(NullPointerException.class, () -> new SnowWhiteCodexCatalog((PersistentAccess) null));
        assertThrows(NullPointerException.class, () -> new SnowWhiteCodexCatalog((CodexGlossaryManager) null));
        assertThrows(NullPointerException.class, () -> codex.unlockForLabel(null));
    }

    // -------------------------------------------------------------------------
    // 3. SnowWhiteAffinityDirector
    // -------------------------------------------------------------------------

    @Test
    void affinityDirectorManagesCharactersMilestonesAndStateStoreSync() {
        SimpleStateStore stateStore = new SimpleStateStore();
        SnowWhiteAffinityDirector director = new SnowWhiteAffinityDirector(stateStore);

        RelationshipAffinityTracker tracker = director.getTracker();
        assertEquals(Set.of("jaeger", "zwerge", "prinz", "koenigin"), tracker.getAllAffinities().keySet());
        assertEquals(RelationshipAffinityTracker.RelationshipTier.STRANGER, tracker.getTier("jaeger"));

        director.applyStoryMilestone("jaeger_mercy");
        assertEquals(25, tracker.getPoints("jaeger"));
        assertEquals(RelationshipAffinityTracker.RelationshipTier.FRIEND, tracker.getTier("jaeger"));
        assertEquals(-15, tracker.getPoints("koenigin"));
        assertEquals(RelationshipAffinityTracker.RelationshipTier.HOSTILE, tracker.getTier("koenigin"));

        director.applyStoryMilestone("dwarf_hospitality");
        director.applyStoryMilestone("dwarf_warning_promise");
        director.applyStoryMilestone("queen_poison_attempt");
        director.applyStoryMilestone("prince_rescue");
        director.applyStoryMilestone("final_wedding");

        assertEquals(90, tracker.getPoints("prinz"));
        assertEquals(RelationshipAffinityTracker.RelationshipTier.DEVOTED, tracker.getTier("prinz"));
        assertEquals(90, tracker.getPoints("zwerge"));
        assertEquals(RelationshipAffinityTracker.RelationshipTier.DEVOTED, tracker.getTier("zwerge"));
        assertEquals(-100, tracker.getPoints("koenigin"));
        assertEquals(-100, stateStore.getInt("affinity.koenigin", 0));

        String summary = director.formatRelationshipSummary();
        assertTrue(summary.contains("Der Jäger (jaeger): 25 Punkte [FRIEND]"));
        assertTrue(summary.contains("Der Königssohn (prinz): 90 Punkte [DEVOTED]"));
        assertTrue(summary.contains("Die böse Königin (koenigin): -100 Punkte [HOSTILE]"));

        assertThrows(NullPointerException.class, () -> director.applyStoryMilestone(null));
        assertThrows(IllegalArgumentException.class, () -> director.applyStoryMilestone("  "));
        assertThrows(IllegalArgumentException.class, () -> director.applyStoryMilestone("unknown_event"));
    }

    @Test
    void affinityDirectorInteractsWithMockedStateStore() {
        when(mockedStateStore.keys()).thenReturn(Set.of());
        when(mockedStateStore.getInt(any(String.class), eq(0))).thenReturn(0);

        SnowWhiteAffinityDirector director = new SnowWhiteAffinityDirector(mockedStateStore);
        verify(mockedStateStore, atLeast(4)).set(any(String.class), eq(0));

        director.applyStoryMilestone("prince_rescue");
        verify(mockedStateStore).set("affinity.prinz", 60);
        verify(mockedStateStore).set("affinity.zwerge", 10);
    }

    // -------------------------------------------------------------------------
    // 4. SnowWhiteQuoteBook
    // -------------------------------------------------------------------------

    @Test
    void quoteBookDeduplicatesSearchesAndPersistsWithGson(@TempDir Path tempDir) throws IOException {
        SnowWhiteQuoteBook book = new SnowWhiteQuoteBook();

        SnowWhiteQuoteBook.QuoteEntry mirrorQuote = book.addQuote(
                "spiegel_02",
                "Zauberspiegel",
                "Frau Königin, Ihr seid die Schönste hier, doch Schneewittchen ist tausendmal schöner als Ihr!",
                2
        );
        assertEquals("spiegel_02", mirrorQuote.lineId());
        assertNotNull(mirrorQuote.savedAt());

        // Deduplizierung über dieselbe lineId
        book.addQuote(
                "spiegel_02",
                "Zauberspiegel",
                "Frau Königin, Ihr seid die Schönste hier, aber Schneewittchen ist tausendmal schöner als Ihr!",
                2
        );
        book.addQuote("prolog_01", "Die gute Königin", "So weiß wie Schnee, so rot wie Blut!", 1);
        book.addQuote("narrator_01", "   ", "Die sieben Zwerge arbeiteten in den Bergen.", 4);

        List<SnowWhiteQuoteBook.QuoteEntry> all = book.listAll();
        assertEquals(3, all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.add(mirrorQuote));

        assertTrue(book.findQuote("spiegel_02").isPresent());
        assertTrue(book.findQuote("   ").isEmpty());
        assertEquals("Erzähler", book.findQuote("narrator_01").orElseThrow().speaker());

        List<SnowWhiteQuoteBook.QuoteEntry> searchMatches = book.search("tausendmal");
        assertEquals(1, searchMatches.size());
        assertEquals("spiegel_02", searchMatches.get(0).lineId());
        assertEquals(3, book.search("").size());

        Path jsonFile = tempDir.resolve("nested/quotes.json");
        book.save(jsonFile);
        assertTrue(Files.isRegularFile(jsonFile));

        SnowWhiteQuoteBook reloaded = SnowWhiteQuoteBook.load(jsonFile);
        assertEquals(3, reloaded.listAll().size());
        assertEquals(
                book.findQuote("prolog_01").orElseThrow().savedAt(),
                reloaded.findQuote("prolog_01").orElseThrow().savedAt()
        );

        assertTrue(reloaded.removeQuote("narrator_01"));
        assertFalse(reloaded.removeQuote("narrator_01"));
        assertFalse(reloaded.removeQuote("  "));
        assertEquals(2, reloaded.listAll().size());
    }

    @Test
    void quoteBookValidatesInputsAndRejectsMalformedJson(@TempDir Path tempDir) throws IOException {
        SnowWhiteQuoteBook book = new SnowWhiteQuoteBook();
        assertThrows(NullPointerException.class, () -> book.addQuote(null, "Sprecher", "Text", 1));
        assertThrows(IllegalArgumentException.class, () -> book.addQuote("  ", "Sprecher", "Text", 1));
        assertThrows(IllegalArgumentException.class, () -> book.addQuote("id_1", "Sprecher", "  ", 1));
        assertThrows(IllegalArgumentException.class, () -> book.addQuote("id_1", "Sprecher", "Text", 0));
        assertThrows(NullPointerException.class, () -> new SnowWhiteQuoteBook.QuoteEntry("id", null, "tx", 1, Instant.now()));
        // Expliziter Test für Standardwert Instant.now() bei savedAt == null
        SnowWhiteQuoteBook.QuoteEntry defaultInstantEntry =
                new SnowWhiteQuoteBook.QuoteEntry("id_default", "Sprecher", "Zitat", 1, null);
        assertNotNull(defaultInstantEntry.savedAt());

        Path missing = tempDir.resolve("missing.json");
        assertThrows(IOException.class, () -> SnowWhiteQuoteBook.load(missing));

        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{ invalid json");
        assertThrows(IOException.class, () -> SnowWhiteQuoteBook.load(broken));
    }

    // -------------------------------------------------------------------------
    // 5. SnowWhiteReadingStats
    // -------------------------------------------------------------------------

    @Test
    void readingStatsTracksRuntimeMetricsAndAnalyzesSnowWhiteProject() {
        VJavaAst.ProjectAst projectAst = ProjectScanner.scan(resolveProjectDir());
        SnowWhiteReadingStats stats = new SnowWhiteReadingStats();

        stats.recordDialogueLine("kapitel_1_vorgeschichte", 1);
        stats.recordDialogueLine("kapitel_4_haus_der_zwerge", 4);
        stats.recordDialogueLine("kapitel_2_stiefmutter_spiegel", 2);
        stats.recordChoice();

        assertEquals(3, stats.getLinesRead());
        assertEquals(1, stats.getChoicesTaken());
        assertEquals(Set.of(1, 2, 4), stats.getVisitedChapters());
        assertThrows(UnsupportedOperationException.class, () -> stats.getVisitedChapters().add(99));

        ScriptWordCountAnalyzer.ScriptStatsReport report = stats.analyzeProject(projectAst);
        assertTrue(report.totalLines() > 20);
        assertTrue(report.totalWords() > 200);
        assertTrue(report.findSpeaker("schneewittchen").isPresent());

        String summary = stats.formatSummary(projectAst);
        assertTrue(summary.contains("3 Zeilen gelesen, 1 Entscheidungen getroffen"));
        assertTrue(summary.contains("[1, 2, 4]"));
        assertTrue(summary.contains("Sprecher-Verteilung:"));

        assertThrows(NullPointerException.class, () -> stats.recordDialogueLine(null, 1));
        assertThrows(IllegalArgumentException.class, () -> stats.recordDialogueLine("  ", 1));
        assertThrows(IllegalArgumentException.class, () -> stats.recordDialogueLine("kapitel_1_vorgeschichte", 0));
        assertThrows(NullPointerException.class, () -> stats.analyzeProject(null));
    }

    // -------------------------------------------------------------------------
    // 6. End-to-End Integration mit echter SnowWhiteEngineService-Session
    // -------------------------------------------------------------------------

    @Test
    void endToEndHeadlessSessionDrivesAllFiveFeatures(@TempDir Path tempDir) throws Exception {
        SnowWhiteEngineService service = new SnowWhiteEngineService(resolveProjectDir());
        try (SnowWhiteEngineService.SessionContext ctx = service.createHeadlessSession(tempDir.resolve("saves"))) {
            ScriptSession session = ctx.session();
            ScriptRuntime runtime = session.getRuntime();

            SnowWhiteChapterCatalog chapters = new SnowWhiteChapterCatalog(session.getPersistentState());
            SnowWhiteCodexCatalog codex = new SnowWhiteCodexCatalog(session.getPersistentState());
            SnowWhiteAffinityDirector affinity = new SnowWhiteAffinityDirector(session.getStateStore());
            SnowWhiteQuoteBook quoteBook = new SnowWhiteQuoteBook();
            SnowWhiteReadingStats readingStats = new SnowWhiteReadingStats();

            session.start();

            boolean jaegerApplied = false;
            boolean dwarfApplied = false;
            boolean poisonApplied = false;
            boolean rescueApplied = false;
            boolean weddingApplied = false;

            int guard = 0;
            while (runtime.getStatus() != ScriptRuntime.Status.ENDED && guard++ < 500) {
                String label = runtime.getCurrentLabel();
                int kapitelNr = session.getStateStore().getInt("kapitel_nr", 1);
                if (label != null && !label.isBlank()) {
                    chapters.unlockForLabel(label);
                    codex.unlockForLabel(label);
                }

                if (!jaegerApplied && "kapitel_3_flucht_in_den_wald".equals(label)) {
                    affinity.applyStoryMilestone(SnowWhiteAffinityDirector.MILESTONE_JAEGER_MERCY);
                    jaegerApplied = true;
                } else if (!dwarfApplied && "kapitel_4_haus_der_zwerge".equals(label)) {
                    affinity.applyStoryMilestone(SnowWhiteAffinityDirector.MILESTONE_DWARF_HOSPITALITY);
                    dwarfApplied = true;
                } else if (!poisonApplied && "anschlag_3_apfel".equals(label)) {
                    affinity.applyStoryMilestone(SnowWhiteAffinityDirector.MILESTONE_QUEEN_POISON_ATTEMPT);
                    poisonApplied = true;
                } else if (!rescueApplied && "kapitel_6_glassarg_und_erloesung".equals(label)) {
                    affinity.applyStoryMilestone(SnowWhiteAffinityDirector.MILESTONE_PRINCE_RESCUE);
                    rescueApplied = true;
                } else if (!weddingApplied && "kapitel_7_hochzeit_und_ende".equals(label)) {
                    affinity.applyStoryMilestone(SnowWhiteAffinityDirector.MILESTONE_FINAL_WEDDING);
                    weddingApplied = true;
                }

                switch (runtime.getStatus()) {
                    case WAITING_DIALOGUE -> {
                        ScriptRuntime.DialoguePrompt active = runtime.getActiveDialogue();
                        if (active != null && label != null) {
                            readingStats.recordDialogueLine(label, kapitelNr);
                            if ("spiegel".equals(active.speakerId())) {
                                quoteBook.addQuote(
                                        active.lineId(),
                                        active.speakerName(),
                                        active.interpolatedText(),
                                        kapitelNr
                                );
                            }
                        }
                        session.advance();
                    }
                    case WAITING_MENU -> {
                        readingStats.recordChoice();
                        session.choose(0);
                    }
                    case WAITING_INPUT -> session.submitInput("");
                    case PAUSING -> session.advance();
                    default -> session.update(1.0 / 60.0);
                }
            }

            assertEquals(ScriptRuntime.Status.ENDED, runtime.getStatus());
            assertEquals(7, chapters.getRegistry().getUnlockedCount());
            assertEquals(8, codex.getManager().getUnlockedCount());
            assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), readingStats.getVisitedChapters());
            assertEquals(1, readingStats.getChoicesTaken());
            assertTrue(readingStats.getLinesRead() > 20);
            assertFalse(quoteBook.listAll().isEmpty());
            assertEquals(RelationshipAffinityTracker.RelationshipTier.DEVOTED, affinity.getTracker().getTier("prinz"));
            assertEquals(RelationshipAffinityTracker.RelationshipTier.HOSTILE, affinity.getTracker().getTier("koenigin"));

            String statsSummary = readingStats.formatSummary(ctx.projectAst());
            assertTrue(statsSummary.contains("Abdeckung:"));
        }
    }
}
