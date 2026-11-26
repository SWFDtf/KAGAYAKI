package de.swfdtf.e2e;

import de.swfdtf.Main;
import de.swfdtf.StandaloneMain;
import de.swfdtf.tools.ProjectTemplateGenerator;
import de.swfdtf.tools.VJavaLinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opaque-box end-to-end smoke test verifying public CLI entry points and starter project workflow.
 */
class E2eSmokeTest {

    @Test
    void starterProjectGeneratesCleanlyAndBootsHeadlessEntryPoints(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("smoke_vn");
        ProjectTemplateGenerator.generate(projectDir, "E2E Smoke VN", "2D");

        assertTrue(Files.isRegularFile(projectDir.resolve("project.vjava.json")));
        assertTrue(Files.isRegularFile(projectDir.resolve("main.vjava")));

        VJavaLinter.LintReport lint = VJavaLinter.lint(projectDir);
        assertTrue(lint.isClean(), "Expected clean starter template lint report, got: " + lint.issues());

        assertDoesNotThrow(() -> Main.main(new String[]{
                "--project", projectDir.toString(),
                "--headless",
                "--smoke-test"
        }));
        assertDoesNotThrow(() -> StandaloneMain.main(new String[]{
                "--project", projectDir.toString(),
                "--headless",
                "--smoke-test"
        }));
    }
}
