package de.swfdtf.tools;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineConfig;
import de.swfdtf.core.EngineMode;
import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Wave 6a (F10) template additions including {@code ACTION_ADVENTURE_2D}
 * and Studio template selection cycling.
 */
final class Wave6aTemplatesAndDemoTest {

    @Test
    void actionAdventure2dTemplateIsLintCleanTranslatedAndPlayable(@TempDir Path tempDir) throws Exception {
        Path outDir = ProjectTemplateGenerator.generate(
                tempDir.resolve("action_2d"),
                "Ruinenquest 2D",
                "SWFD",
                "2D",
                ProjectTemplateGenerator.TemplateType.ACTION_ADVENTURE_2D
        );

        VJavaLinter.LintReport lint = VJavaLinter.lint(outDir);
        assertTrue(lint.isClean(), "Expected zero errors/warnings on ACTION_ADVENTURE_2D: " + lint.issues());

        VJavaAst.ProjectAst ast = ProjectScanner.scan(outDir);
        List<VJavaAst.Statement> allStatements = new ArrayList<>();
        for (VJavaAst.LabelNode label : ast.labels().values()) {
            allStatements.addAll(label.statements());
        }
        assertTrue(allStatements.stream().anyMatch(VJavaAst.WasdInitStatement.class::isInstance));
        assertTrue(allStatements.stream().anyMatch(VJavaAst.PhysicsInitStatement.class::isInstance));
        assertTrue(allStatements.stream().anyMatch(VJavaAst.OnCollideStatement.class::isInstance));

        TranslationCoverageAuditor.CoverageReport coverage = new TranslationCoverageAuditor().audit(ast);
        assertEquals(100.0, coverage.findLanguage("english").orElseThrow().coveragePercent(), 0.01);

        // Ensure legacy signatures remain defaulting to STARTER
        Path legacyStarter = ProjectTemplateGenerator.generate(tempDir.resolve("legacy"));
        VJavaAst.ProjectAst legacyAst = ProjectScanner.scan(legacyStarter);
        assertFalse(legacyAst.labels().containsKey("hall_room"));
    }

    @Test
    void studioControllerSupportsSelectingActionAdventure2dTemplate(@TempDir Path tempDir) throws Exception {
        Path initialDir = ProjectTemplateGenerator.generate(tempDir.resolve("hub_proj"));
        EngineConfig config = EngineConfig.builder()
                .engineMode(EngineMode.STUDIO_DUAL_WINDOW)
                .projectDir(initialDir)
                .headless(true)
                .build();
        try (EngineApplication app = new EngineApplication(config)) {
            app.initialize();
            StudioWindowController studio = app.getApi().getService(StudioWindowController.class);
            assertNotNull(studio);
            studio.setSelectedTemplateType(ProjectTemplateGenerator.TemplateType.ACTION_ADVENTURE_2D);
            assertEquals(ProjectTemplateGenerator.TemplateType.ACTION_ADVENTURE_2D, studio.getSelectedTemplateType());
        }
    }
}
