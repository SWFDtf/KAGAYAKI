package de.swfdtf.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitControllerTest {

    @Test
    void formatsCommitMessageAndValidatesAllowedTypes() {
        String message = GitController.formatCommitMessage(
                "feat",
                "core",
                "Add EngineAPI facade",
                List.of("Added service registry", "- Added property map")
        );
        assertEquals(
                "feat(core): Add EngineAPI facade\n\n- Added service registry\n- Added property map",
                message
        );

        for (String allowed : List.of(
                "feat", "fix", "arch", "render", "parser", "audio", "ui", "docs", "test", "build")) {
            String formatted = GitController.formatCommitMessage(allowed, "mod", "Valid summary", List.of("item"));
            assertTrue(formatted.startsWith(allowed + "(mod): Valid summary"));
        }

        assertThrows(IllegalArgumentException.class,
                () -> GitController.formatCommitMessage("chore", "core", "Invalid type", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GitController.formatCommitMessage("feat", "", "Missing module", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GitController.formatCommitMessage("feat", "core", "   ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GitController.formatCommitMessage("feat", "core", "Line1\nLine2", List.of()));
    }

    @Test
    void initializesStagesCommitsAndReadsStatusAndLog(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path repoDir = tempDir.resolve("sample-repo");
        GitController git = new GitController(repoDir);

        assertFalse(git.isGitRepository());

        GitController.CommandResult initResult = git.init();
        assertTrue(initResult.success(), initResult.stderr());
        assertTrue(git.isGitRepository());

        configureTestGitIdentity(repoDir);

        Path helloFile = repoDir.resolve("hello.txt");
        Files.writeString(helloFile, "KAGAYAKI\n");

        GitController.CommandResult statusBefore = git.status();
        assertTrue(statusBefore.success());
        assertTrue(statusBefore.stdout().contains("hello.txt"));

        GitController.CommandResult addResult = git.add(List.of(helloFile));
        assertTrue(addResult.success(), addResult.stderr());

        GitController.CommandResult commitResult = git.commit(
                "feat",
                "git",
                "Add initial project file",
                List.of("Created hello.txt", "Verified GitController commit workflow")
        );
        assertTrue(commitResult.success(), commitResult.stderr());

        GitController.CommandResult logResult = git.log(5);
        assertTrue(logResult.success(), logResult.stderr());
        assertTrue(logResult.stdout().contains("feat(git): Add initial project file"));
        assertTrue(logResult.stdout().contains("- Created hello.txt"));

        assertThrows(IllegalArgumentException.class, () -> git.add(List.of()));
        assertThrows(IllegalArgumentException.class, () -> git.log(0));
    }

    private static void configureTestGitIdentity(Path repoDir) throws IOException, InterruptedException {
        new ProcessBuilder("git", "config", "user.name", "Kagayaki Test")
                .directory(repoDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "config", "user.email", "test@kagayaki.local")
                .directory(repoDir.toFile())
                .start()
                .waitFor();
    }
}
