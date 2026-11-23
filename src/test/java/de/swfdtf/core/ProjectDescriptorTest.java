package de.swfdtf.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectDescriptorTest {

    @Test
    void savesAndLoadsProjectDescriptorRoundTrip(@TempDir Path tempDir) throws IOException {
        ProjectDescriptor original = ProjectDescriptor.createDefault("Sternenkompass");
        original.setAuthor("SWFDTF");
        original.setVersion("1.2.0");
        original.setRenderMode("2D");
        original.setDialogueMode("NVL");
        original.setIconPath("assets/images/icon.png");

        ProjectDescriptor.save(tempDir, original);
        assertEquals(true, Files.exists(tempDir.resolve(ProjectDescriptor.FILE_NAME)));

        ProjectDescriptor loaded = ProjectDescriptor.load(tempDir);
        assertEquals(original, loaded);
        assertEquals("Sternenkompass", loaded.getTitle());
        assertEquals("Sternenkompass", loaded.getWindowTitle());
        assertEquals(1920, loaded.getLogicalWidth());
        assertEquals(1080, loaded.getLogicalHeight());
        assertEquals("start", loaded.getEntryLabel());
        assertEquals("deutsch", loaded.getDefaultLanguage());
        assertEquals("NVL", loaded.getDialogueMode());
    }

    @Test
    void appliesDefaultsWhenOptionalJsonFieldsAreOmitted(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(ProjectDescriptor.FILE_NAME), """
                {
                  "title": "Minimal VN"
                }
                """);

        ProjectDescriptor loaded = ProjectDescriptor.load(tempDir);
        assertEquals("Minimal VN", loaded.getTitle());
        assertEquals("Minimal VN", loaded.getWindowTitle());
        assertEquals("2D", loaded.getRenderMode());
        assertEquals(1920, loaded.getLogicalWidth());
        assertEquals(1080, loaded.getLogicalHeight());
        assertEquals("start", loaded.getEntryLabel());
        assertEquals("deutsch", loaded.getDefaultLanguage());
        assertEquals("ADV", loaded.getDialogueMode());
    }

    @Test
    void throwsOnMissingOrMalformedDescriptor(@TempDir Path tempDir) throws IOException {
        assertThrows(IOException.class, () -> ProjectDescriptor.load(tempDir));

        Files.writeString(tempDir.resolve(ProjectDescriptor.FILE_NAME), "{ invalid json");
        assertThrows(IOException.class, () -> ProjectDescriptor.load(tempDir));
    }
}
