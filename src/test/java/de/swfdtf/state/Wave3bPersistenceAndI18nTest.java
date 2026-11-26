package de.swfdtf.state;

import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import de.swfdtf.tools.TranslationExportService;
import de.swfdtf.tools.VJavaLinter;
import de.swfdtf.ui.UiTranslations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for Wave 3b F05 persistence hardening and F08 internationalization features.
 */
class Wave3bPersistenceAndI18nTest {

    @Test
    void saveValueCodecRejectsDisallowedGadgetClasses(@TempDir Path tempDir) throws Exception {
        com.google.gson.JsonObject gadget = new com.google.gson.JsonObject();
        gadget.addProperty(SaveValueCodec.KEY_VJAVA_CLASS, "java.lang.ProcessBuilder");
        assertThrows(SecurityException.class, () -> SaveValueCodec.decode(gadget, null));

        com.google.gson.JsonObject sbGadget = new com.google.gson.JsonObject();
        sbGadget.addProperty(SaveValueCodec.KEY_VJAVA_CLASS, "java.lang.StringBuilder");
        assertThrows(SecurityException.class, () -> SaveValueCodec.decode(sbGadget, null));
    }

    @Test
    void saveManagerCreatesBackupOnOverwriteAndRecoversFromCorruptPrimary(@TempDir Path tempDir) throws Exception {
        Path savesDir = tempDir.resolve("saves");
        SaveManager manager = new SaveManager(savesDir);

        SaveSlotData first = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "V1", Instant.now(), "start", "L1", 1.0, "1.0", 0, 0, null),
                Map.of("chapter", 1),
                null,
                List.of()
        );
        SaveSlotData second = new SaveSlotData(
                new SaveSlotData.SaveMetadata("slot_1", "V2", Instant.now(), "start", "L2", 2.0, "1.0", 0, 0, null),
                Map.of("chapter", 2),
                null,
                List.of()
        );

        manager.saveSlot("slot_1", first);
        manager.saveSlot("slot_1", second);
        Path primary = savesDir.resolve("slot_1.ksav");
        Path backup = savesDir.resolve("slot_1.ksav.bak");
        assertTrue(Files.isRegularFile(backup));

        Files.write(primary, new byte[]{0x01, 0x02, 0x03});
        SaveSlotData recovered = manager.loadSlot("slot_1");
        assertEquals(1, ((Number) recovered.variables().get("chapter")).intValue());

        Files.write(backup, new byte[]{0x04, 0x05, 0x06});
        assertThrows(IOException.class, () -> manager.loadSlot("slot_1"));
    }

    @Test
    void revertableStateStoreBoundsCheckpointsAndPersistentAutoFlushes(@TempDir Path tempDir) throws Exception {
        RevertableStateStore store = new RevertableStateStore();
        assertEquals(RevertableStateStore.DEFAULT_MAX_ROLLBACK_DEPTH, store.getMaxRollbackDepth());
        for (int i = 1; i <= 250; i++) {
            store.set("step", i);
            store.recordCheckpoint("step " + i);
        }
        assertEquals(200, store.getCheckpoints().size());

        Path savesDir = tempDir.resolve("persistent_saves");
        PersistentState persistent = PersistentState.load(savesDir);
        persistent.unlockEnding("good_end");
        persistent.unlockCg("cg_castle");
        persistent.unlockTrack("music/theme.ogg");
        persistent.unlockAchievement("first_step");
        persistent.set("flag_cleared", true);

        PersistentState reloaded = PersistentState.load(savesDir);
        assertTrue(reloaded.isEndingUnlocked("good_end"));
        assertTrue(reloaded.isCgUnlocked("cg_castle"));
        assertTrue(reloaded.isTrackUnlocked("music/theme.ogg"));
        assertTrue(reloaded.isAchievementUnlocked("first_step"));
        assertEquals(Boolean.TRUE, reloaded.get("flag_cleared"));
    }

    @Test
    void icuFormatterAndTranslationMemoryWorkAcrossLocales() {
        String template = "{count, plural, one {# gwiazda} few {# gwiazdy} many {# gwiazd} other {# gwiazdy}}";
        assertEquals("1 gwiazda", IcuMessageFormatter.format(template, "polish", k -> 1));
        assertEquals("2 gwiazdy", IcuMessageFormatter.format(template, "polish", k -> 2));
        assertEquals("5 gwiazd", IcuMessageFormatter.format(template, "polish", k -> 5));
        assertEquals("22 gwiazdy", IcuMessageFormatter.format(template, "polish", k -> 22));
        assertEquals("-3 gwiazdy", IcuMessageFormatter.format(template, "deutsch", k -> -3));
        assertEquals("{count, plural, one {unclosed",
                IcuMessageFormatter.format("{count, plural, one {unclosed", "english", k -> 1));

        String select = "{g, select, female {She} male {He} other {They}} arrived.";
        assertEquals("She arrived.", IcuMessageFormatter.format(select, "english", k -> "female"));
        assertEquals("He arrived.", IcuMessageFormatter.format(select, "english", k -> "male"));

        TranslationMemory tm = new TranslationMemory();
        assertTrue(tm.match("Anything").isEmpty());
        tm.learn("Hallo Welt im ersten Kapitel.", "Hello world in the first chapter.");
        Optional<TranslationMemory.MatchResult> exact = tm.match("Hallo Welt im ersten Kapitel.");
        assertTrue(exact.isPresent() && !exact.get().fuzzy());
        Optional<TranslationMemory.MatchResult> fuzzy = tm.match("Hallo Welt, im ersten Kapitel.");
        assertTrue(fuzzy.isPresent() && fuzzy.get().fuzzy());
        assertTrue(tm.match("Astronauten landen auf dem Mars.").isEmpty());
    }

    @Test
    void exportServiceUiBundleAndLinterI18nChecks(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("tl/english"));
        Files.writeString(project.resolve("project.vjava.json"),
                "{\"title\":\"I18n Test\",\"version\":\"1.0.0\",\"entryLabel\":\"start\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("main.vjava"), """
                define lena = Character("Lena")
                default hero = "Aria"
                label start:
                    lena "Willkommen [hero], {b}Mutig{/b}!"
                    return
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("tl/english/translations.vjava"), """
                translate english line_01:
                    # lena "Willkommen [hero], {b}Mutig{/b}!"
                    lena "Welcome brave!"
                """, StandardCharsets.UTF_8);

        TranslationExportService export = new TranslationExportService();
        VJavaAst.ProjectAst ast = ProjectScanner.scan(project);
        Path csv = tempDir.resolve("out.csv");
        export.export(ast, TranslationExportService.ExportFormat.CSV, csv);
        assertTrue(Files.readString(csv).contains("{b}Mutig{/b}"));

        Path badCsv = tempDir.resolve("bad.csv");
        Files.writeString(badCsv, "\"unterminated,row\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> export.importCsv(badCsv, "french", project.resolve("tl/french/translations.vjava")));

        VJavaLinter.LintReport warnReport = VJavaLinter.lint(project);
        assertTrue(warnReport.issues().stream()
                .anyMatch(i -> "TRANSLATION_PLACEHOLDER_MISMATCH".equals(i.code())));

        Files.createDirectories(project.resolve("tl/french"));
        Files.writeString(project.resolve("tl/french/translations.vjava"), """
                translate french line_02:
                    # lena "Unused only"
                    lena "Incomplet"
                """, StandardCharsets.UTF_8);
        VJavaLinter.LintReport strictReport = VJavaLinter.lint(project, true);
        assertTrue(strictReport.hasErrors());

        LocalizationManager loc = new LocalizationManager("deutsch");
        String de = UiTranslations.resolve(loc, "ui.start", "Start");
        loc.setLanguage("french");
        String fr = UiTranslations.resolve(loc, "ui.start", "Start");
        loc.setLanguage("klingon_unknown");
        String fb = UiTranslations.resolve(loc, "ui.start", "Start");
        assertFalse(de.equals(fr));
        assertEquals("Start", fb);
    }
}
