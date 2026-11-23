package de.swfdtf.parser;

import de.swfdtf.core.ProjectDescriptor;
import de.swfdtf.parser.VJavaAst.ProjectAst;
import de.swfdtf.parser.VJavaAst.SayStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerTest {

    @Test
    void scansRecursiveScriptsAssetsAndDescriptorDeterministically(@TempDir Path tempDir) throws IOException {
        ProjectDescriptor descriptor = ProjectDescriptor.createDefault("Sternenkompass VN");
        descriptor.setAuthor("SWFDTF");
        descriptor.setEntryLabel("start");
        ProjectDescriptor.save(tempDir, descriptor);

        Files.writeString(tempDir.resolve("main.vjava"), """
                define lena = Character("Lena", color="#58a6ff")
                image bg_akademie = "assets/images/bg.png"

                label start:
                    scene bg_akademie
                    lena "Willkommen im Hauptskript!"
                    jump kapitel_1_szene_01
                """);

        Path chapterDir = tempDir.resolve("kapitel_1");
        Files.createDirectories(chapterDir);
        Files.writeString(chapterDir.resolve("szene_01.vjava"), """
                :: Kode für VN: Kapitel Helfer @->{
                    public static class KapitelLogik {
                        public static int score() { return 42; }
                    }
                }<<

                label kapitel_1_szene_01:
                    lena "Kapitel 1 geladen!"
                    return
                """);

        Path uiDir = tempDir.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("custom_screens.vjava"), """
                screen quick_hud():
                    zorder 250
                    textbutton "Menü" action ShowMenu("main_menu")
                """);

        // Ignored directories must NOT be scanned for .vjava scripts
        for (String ignored : List.of(".git", "build", ".gradle", "saves", "out", "dist")) {
            Path ignoredDir = tempDir.resolve(ignored);
            Files.createDirectories(ignoredDir);
            Files.writeString(ignoredDir.resolve("ignored.vjava"), """
                    label should_never_be_loaded:
                        return
                    """);
        }

        // Assets directory
        Path imgDir = tempDir.resolve("assets/images");
        Path audioDir = tempDir.resolve("assets/audio");
        Files.createDirectories(imgDir);
        Files.createDirectories(audioDir);
        Files.write(imgDir.resolve("bg.png"), new byte[]{1, 2, 3});
        Files.write(audioDir.resolve("theme.ogg"), new byte[]{4, 5, 6});

        ProjectAst projectAst = ProjectScanner.scan(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), projectAst.projectRoot());
        assertEquals("Sternenkompass VN", projectAst.descriptor().getTitle());
        assertEquals("SWFDTF", projectAst.descriptor().getAuthor());

        // Deterministic sorted order: kapitel_1/szene_01.vjava, main.vjava, ui/custom_screens.vjava
        List<String> relativeScripts = projectAst.scriptFiles().stream()
                .map(p -> tempDir.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/'))
                .toList();
        assertEquals(
                List.of("kapitel_1/szene_01.vjava", "main.vjava", "ui/custom_screens.vjava"),
                relativeScripts
        );

        assertTrue(projectAst.characters().containsKey("lena"));
        assertTrue(projectAst.images().containsKey("bg_akademie"));
        assertTrue(projectAst.labels().containsKey("start"));
        assertTrue(projectAst.labels().containsKey("kapitel_1_szene_01"));
        assertFalseIgnoredLabel(projectAst);
        assertTrue(projectAst.screens().containsKey("quick_hud"));
        assertEquals(250, projectAst.screens().get("quick_hud").zOrder());
        assertEquals(1, projectAst.topLevelJavaBlocks().size());

        assertEquals(
                List.of("assets/audio/theme.ogg", "assets/images/bg.png"),
                projectAst.discoveredAssets()
        );
        assertTrue(projectAst.warnings().isEmpty());
    }

    @Test
    void emitsWarningsOnDuplicatesAndCreatesDefaultDescriptorWhenMissing(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("MeinProjekt");
        Files.createDirectories(projectDir);

        Files.writeString(projectDir.resolve("a_first.vjava"), """
                define hero = Character("Hero A", color="#111111")
                screen hud():
                    text "Old"
                label start:
                    hero "First"
                    return
                """);

        Files.writeString(projectDir.resolve("b_override.vjava"), """
                define hero = Character("Hero B", color="#222222")
                screen hud():
                    text "New"
                label start:
                    hero "Second"
                    return
                """);

        ProjectAst ast = ProjectScanner.scan(projectDir);

        assertEquals("MeinProjekt", ast.descriptor().getTitle());
        assertEquals(3, ast.warnings().size());
        assertTrue(ast.warnings().stream().anyMatch(w -> w.contains("character") && w.contains("hero")));
        assertTrue(ast.warnings().stream().anyMatch(w -> w.contains("screen") && w.contains("hud")));
        assertTrue(ast.warnings().stream().anyMatch(w -> w.contains("label") && w.contains("start")));

        // Deterministic last-wins override behavior
        assertEquals("Hero B", ast.characters().get("hero").displayName());
        assertEquals("New", ast.screens().get("hud").items().get(0).primaryArg());
        SayStatement say = (SayStatement) ast.labels().get("start").statements().get(0);
        assertEquals("Second", say.text());

        assertThrows(IllegalArgumentException.class, () -> ProjectScanner.scan(tempDir.resolve("missing_dir")));
    }

    private static void assertFalseIgnoredLabel(ProjectAst ast) {
        if (ast.labels().containsKey("should_never_be_loaded")) {
            throw new AssertionError("Ignored directory .vjava script must not be scanned");
        }
    }
}
