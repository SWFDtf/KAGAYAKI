package de.swfdtf.demo;

import de.swfdtf.audio.AmbientCrossfadeMixer;
import de.swfdtf.audio.AudioCueRegistry;
import de.swfdtf.audio.AudioDuckingController;
import de.swfdtf.audio.ClosedCaptionQueue;
import de.swfdtf.audio.ClosedCaptionQueue.CaptionCategory;
import de.swfdtf.audio.VoicePlaybackBookmark;
import de.swfdtf.core.GamepadNavigationRouter;
import de.swfdtf.core.GamepadNavigationRouter.NavigationDirection;
import de.swfdtf.core.InputManager;
import de.swfdtf.demo.director.SnowWhiteAccessibilityProfile;
import de.swfdtf.demo.director.SnowWhiteAccessibilityProfile.ProfilePreset;
import de.swfdtf.demo.director.SnowWhiteAudioShowcase;
import de.swfdtf.demo.director.SnowWhiteCameraChoreographer;
import de.swfdtf.demo.director.SnowWhiteScreenshotShowcase;
import de.swfdtf.demo.director.SnowWhiteWeatherDirector;
import de.swfdtf.graphics.CameraDirectorPresets;
import de.swfdtf.graphics.CameraDirectorPresets.CameraPreset;
import de.swfdtf.graphics.CinematicLetterboxController;
import de.swfdtf.graphics.ColorBlindnessFilter.AccessibilityMode;
import de.swfdtf.graphics.ScreenshotCaptureService;
import de.swfdtf.graphics.WeatherOverlayController;
import de.swfdtf.graphics.WeatherOverlayController.WeatherPreset;
import de.swfdtf.state.AutoReadController;
import de.swfdtf.state.SkipModeController;
import de.swfdtf.state.SkipModeController.SkipMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Umfassende JUnit-6- und Mockito-Testsuite für die Wave-4-Director-Klassen im Paket
 * {@code de.swfdtf.demo.director}.
 */
class Wave4DemoDirectorTest {

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @Test
    void weatherDirectorMapsAllSevenChaptersAndInterpolatesTransitions() {
        SnowWhiteWeatherDirector director = new SnowWhiteWeatherDirector();
        assertTrue(director.getActiveChapter().isEmpty());
        assertFalse(director.describeCurrentWeather().isBlank());
        assertThrows(UnsupportedOperationException.class,
                () -> SnowWhiteWeatherDirector.getChapterCues().remove(1));

        WeatherPreset[] expectedPresets = {
                WeatherPreset.SNOW,
                WeatherPreset.FOG,
                WeatherPreset.FOG,
                WeatherPreset.CLEAR,
                WeatherPreset.RAIN,
                WeatherPreset.SNOW,
                WeatherPreset.EMBERS
        };

        for (int chapter = 1; chapter <= 7; chapter++) {
            director.applyForChapter(chapter, 0.0);
            assertEquals(Optional.of(chapter), director.getActiveChapter());
            assertEquals(expectedPresets[chapter - 1], director.getController().getCurrentPreset());
            assertTrue(director.describeCurrentWeather().contains("Kapitel " + chapter));
        }

        director.applyForChapter(5, 1.0);
        assertTrue(director.getController().isTransitioning());
        assertEquals(WeatherPreset.RAIN, director.getController().getCurrentPreset());
        assertEquals(-45.0f, director.getController().getWindX(), 0.001f);
        assertEquals(28.0f, director.getController().getWindY(), 0.001f);

        director.update(0.5);
        assertTrue(director.getController().isTransitioning());
        director.update(0.5);
        assertFalse(director.getController().isTransitioning());
        assertEquals(0.90f, director.getController().getCurrentIntensity(), 0.001f);

        assertEquals(Optional.empty(), SnowWhiteWeatherDirector.findCueForChapter(0));
        assertTrue(SnowWhiteWeatherDirector.findCueForChapter(3).isPresent());
        assertThrows(IllegalArgumentException.class, () -> director.applyForChapter(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> director.applyForChapter(8, 0.5));
        assertThrows(IllegalArgumentException.class, () -> director.applyForChapter(1, -0.1));
        assertThrows(IllegalArgumentException.class, () -> director.update(-0.01));
        assertThrows(NullPointerException.class, () -> new SnowWhiteWeatherDirector(null));
    }

    @Test
    void weatherDirectorDelegatesWithMockitoController() {
        WeatherOverlayController mockController = mock(WeatherOverlayController.class);
        when(mockController.getCurrentPreset()).thenReturn(WeatherPreset.RAIN);
        when(mockController.getCurrentIntensity()).thenReturn(0.90f);
        when(mockController.getWindX()).thenReturn(-45.0f);
        when(mockController.getWindY()).thenReturn(28.0f);

        SnowWhiteWeatherDirector director = new SnowWhiteWeatherDirector(mockController);
        assertSame(mockController, director.getController());

        director.applyForChapter(5, 0.75);
        director.update(0.25);

        InOrder order = inOrder(mockController);
        order.verify(mockController).setWind(-45.0f, 28.0f);
        order.verify(mockController).setWeather(WeatherPreset.RAIN, 0.90f, 0.75);
        order.verify(mockController).update(0.25);

        String summary = director.describeCurrentWeather();
        assertTrue(summary.contains("RAIN"));
        assertTrue(summary.contains("90%"));
    }

    @Test
    void cameraChoreographerCoordinatesCameraAndLetterboxAcrossAllChapters() {
        SnowWhiteCameraChoreographer choreographer = new SnowWhiteCameraChoreographer();
        assertTrue(choreographer.getActiveChapter().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> SnowWhiteCameraChoreographer.getChapterCues().remove(1));

        choreographer.applyChapterCue(2, 0.5);
        assertEquals(Optional.of(2), choreographer.getActiveChapter());
        assertTrue(choreographer.getCameraPresets().isTransitioning());
        assertTrue(choreographer.getLetterboxController().isActive());

        choreographer.update(0.5);
        assertFalse(choreographer.getCameraPresets().isTransitioning());
        assertEquals(
                CameraDirectorPresets.getPresetState(CameraPreset.CLOSE_UP),
                choreographer.getCameraPresets().getCurrentState()
        );
        assertEquals(
                CinematicLetterboxController.DEFAULT_BAR_HEIGHT,
                choreographer.getLetterboxController().getCurrentBarHeight(),
                0.001f
        );

        choreographer.applyChapterCue(4, 0.0);
        assertEquals(
                CameraDirectorPresets.getPresetState(CameraPreset.ESTABLISHING),
                choreographer.getCameraPresets().getCurrentState()
        );
        assertFalse(choreographer.getLetterboxController().isActive());
        assertEquals(0.0f, choreographer.getLetterboxController().getCurrentBarHeight(), 0.001f);

        choreographer.applyChapterCue(5, 0.0);
        assertEquals(
                CameraDirectorPresets.getPresetState(CameraPreset.DRAMATIC_TILT),
                choreographer.getCameraPresets().getCurrentState()
        );
        assertEquals(140.0f, choreographer.getLetterboxController().getCurrentBarHeight(), 0.001f);

        assertTrue(SnowWhiteCameraChoreographer.findCueForChapter(6).isPresent());
        assertTrue(SnowWhiteCameraChoreographer.findCueForChapter(9).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> choreographer.applyChapterCue(0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> choreographer.applyChapterCue(8, 0.2));
        assertThrows(IllegalArgumentException.class, () -> choreographer.applyChapterCue(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> choreographer.update(-1.0));
        assertThrows(NullPointerException.class,
                () -> new SnowWhiteCameraChoreographer(null, new CinematicLetterboxController()));
        assertThrows(NullPointerException.class,
                () -> new SnowWhiteCameraChoreographer(new CameraDirectorPresets(), null));
    }

    @Test
    void cameraChoreographerInvokesMockedControllers() {
        CameraDirectorPresets mockCamera = mock(CameraDirectorPresets.class);
        CinematicLetterboxController mockLetterbox = mock(CinematicLetterboxController.class);
        SnowWhiteCameraChoreographer choreographer = new SnowWhiteCameraChoreographer(mockCamera, mockLetterbox);

        assertSame(mockCamera, choreographer.getCameraPresets());
        assertSame(mockLetterbox, choreographer.getLetterboxController());

        choreographer.applyChapterCue(3, 1.2);
        choreographer.applyChapterCue(7, 0.4);
        choreographer.update(0.1);

        verify(mockCamera).applyPreset(CameraPreset.PAN_LEFT_TO_RIGHT, 1.2);
        verify(mockLetterbox).engage(CinematicLetterboxController.DEFAULT_BAR_HEIGHT, 1.2);
        verify(mockCamera).applyPreset(CameraPreset.ESTABLISHING, 0.4);
        verify(mockLetterbox).release(0.4);
        verify(mockCamera).update(0.1);
        verify(mockLetterbox).update(0.1);
        verifyNoMoreInteractions(mockCamera, mockLetterbox);
    }

    @Test
    void audioShowcaseWiresAllFiveComponentsAndHandlesVoiceDucking() {
        SnowWhiteAudioShowcase showcase = new SnowWhiteAudioShowcase();

        assertTrue(showcase.getCueRegistry().findCue("winter_prolog").isPresent());
        assertTrue(showcase.getCueRegistry().findCue("spiegel_klang").isPresent());
        assertTrue(showcase.getCueRegistry().findCue("wald_thema").isPresent());
        assertTrue(showcase.getCueRegistry().findCue("gefahr_thema").isPresent());
        assertTrue(showcase.getCueRegistry().findCue("finale_hochzeit").isPresent());
        assertThrows(UnsupportedOperationException.class,
                () -> SnowWhiteAudioShowcase.getChapterScenes().remove(1));

        showcase.triggerChapterAudio(1);
        assertEquals(Optional.of(1), showcase.getActiveChapter());
        assertEquals(Optional.of("winter_prolog"), showcase.getActiveCueId());
        assertEquals(1, showcase.getCaptionQueue().getVisibleCaptions().size());
        assertEquals(CaptionCategory.MUSIC, showcase.getCaptionQueue().getVisibleCaptions().get(0).category());

        showcase.update(1.0);
        assertEquals(0.85f, showcase.getAmbientMixer().getWeight("winter_prolog").orElseThrow(), 0.001f);

        showcase.triggerChapterAudio(2);
        assertEquals(Optional.of("spiegel_klang"), showcase.getActiveCueId());
        assertEquals(1, showcase.getCaptionQueue().filterByCategory(CaptionCategory.SFX).size());

        showcase.recordVoiceLine(
                "sw_erwacht_01",
                "Schneewittchen",
                "Wo bin ich? Der Wald funkelt so hell!",
                "assets/audio/voice/sw_erwacht.wav"
        );
        assertTrue(showcase.getDuckingController().isVoiceActive());
        assertTrue(showcase.findLatestVoiceLine().isPresent());
        assertEquals("sw_erwacht_01", showcase.findLatestVoiceLine().orElseThrow().lineId());
        assertEquals(1, showcase.getCaptionQueue().filterByCategory(CaptionCategory.DIALOGUE).size());

        showcase.update(0.2);
        assertEquals(AudioDuckingController.DEFAULT_DUCKED_GAIN, showcase.computeDuckedMusicGain(1.0f), 0.001f);

        showcase.update(5.0);
        assertFalse(showcase.getDuckingController().isVoiceActive());
        assertEquals(1.0f, showcase.computeDuckedMusicGain(1.0f), 0.001f);

        showcase.recordVoiceLine("sw_hallo_01", "Schneewittchen", "Hallo!", "assets/audio/voice/sw_hallo.wav");
        assertTrue(showcase.getDuckingController().isVoiceActive());
        showcase.stopVoicePlayback();
        assertFalse(showcase.getDuckingController().isVoiceActive());

        assertThrows(IllegalArgumentException.class, () -> showcase.triggerChapterAudio(0));
        assertThrows(IllegalArgumentException.class, () -> showcase.triggerChapterAudio(8));
        assertThrows(IllegalArgumentException.class, () -> showcase.recordVoiceLine(" ", "a", "b", "c"));
        assertThrows(NullPointerException.class, () -> showcase.recordVoiceLine("id", null, "text", "path"));
        assertThrows(IllegalArgumentException.class, () -> showcase.update(-0.5));
    }

    @Test
    void audioShowcaseCoordinatesMockedSubsystems() {
        AudioCueRegistry registry = mock(AudioCueRegistry.class);
        AudioDuckingController ducking = mock(AudioDuckingController.class);
        AmbientCrossfadeMixer mixer = mock(AmbientCrossfadeMixer.class);
        ClosedCaptionQueue captions = mock(ClosedCaptionQueue.class);
        VoicePlaybackBookmark bookmark = mock(VoicePlaybackBookmark.class);

        SnowWhiteAudioShowcase showcase = new SnowWhiteAudioShowcase(registry, ducking, mixer, captions, bookmark);
        assertSame(registry, showcase.getCueRegistry());
        assertSame(ducking, showcase.getDuckingController());
        assertSame(mixer, showcase.getAmbientMixer());
        assertSame(captions, showcase.getCaptionQueue());
        assertSame(bookmark, showcase.getVoiceBookmark());

        verify(registry).register(argThat(cue -> "winter_prolog".equals(cue.id())));
        verify(registry).register(argThat(cue -> "spiegel_klang".equals(cue.id())));
        verify(registry).register(argThat(cue -> "wald_thema".equals(cue.id())));
        verify(registry).register(argThat(cue -> "gefahr_thema".equals(cue.id())));
        verify(registry).register(argThat(cue -> "finale_hochzeit".equals(cue.id())));

        showcase.triggerChapterAudio(7);
        verify(mixer).applyPreset(argThat(map -> Float.valueOf(0.95f).equals(map.get("finale_hochzeit"))));
        verify(captions).push(eq(CaptionCategory.MUSIC), eq("Musik"), anyString(), eq(4.0));

        showcase.recordVoiceLine("line_1", "Prinz", "Erwache!", "assets/audio/voice/prinz.wav");
        verify(bookmark).record("line_1", "Prinz", "assets/audio/voice/prinz.wav");
        verify(captions).push(eq(CaptionCategory.DIALOGUE), eq("Prinz"), eq("Erwache!"), eq(2.0));
        verify(ducking).setVoiceActive(true);

        showcase.update(2.5);
        verify(ducking).setVoiceActive(false);
        verify(ducking).update(2.5);
        verify(mixer).update(2.5);
        verify(captions).update(2.5);
    }

    @Test
    void accessibilityProfileAppliesPresetsSavesLoadsAndRoutesMockedGamepad(@TempDir Path tempDir)
            throws IOException {
        SnowWhiteAccessibilityProfile profile = new SnowWhiteAccessibilityProfile();
        assertEquals(ProfilePreset.STANDARD, profile.getActivePreset());
        assertEquals(AccessibilityMode.NONE, profile.getColorBlindnessFilter().getMode());
        assertFalse(profile.getAutoReadController().isEnabled());
        assertEquals(SkipMode.OFF, profile.getSkipModeController().getMode());

        profile.applyPreset(ProfilePreset.HIGH_CONTRAST);
        assertEquals(ProfilePreset.HIGH_CONTRAST, profile.getActivePreset());
        assertEquals(AccessibilityMode.HIGH_CONTRAST, profile.getColorBlindnessFilter().getMode());
        assertEquals(SkipMode.READ_ONLY, profile.getSkipModeController().getMode());
        assertEquals(0.40f, profile.getGamepadNavigationRouter().getDeadzone(), 0.001f);

        profile.applyPreset(ProfilePreset.AUTO_CINEMA);
        assertEquals(ProfilePreset.AUTO_CINEMA, profile.getActivePreset());
        assertTrue(profile.getAutoReadController().isEnabled());
        assertEquals(AccessibilityMode.NONE, profile.getColorBlindnessFilter().getMode());
        assertEquals(0.30f, profile.getGamepadNavigationRouter().getDeadzone(), 0.001f);

        // Benutzerdefinierte Änderungen an Einzelkomponenten müssen per JSON erhalten bleiben
        profile.getColorBlindnessFilter().setMode(AccessibilityMode.DEUTERANOPIA);
        profile.getColorBlindnessFilter().setIntensity(0.75f);
        profile.getSkipModeController().setMode(SkipMode.ALL);
        profile.getSkipModeController().setStopOnChoice(false);

        Path savedFile = tempDir.resolve("config/snow_white_accessibility.json");
        profile.save(savedFile);
        assertTrue(Files.isRegularFile(savedFile));

        SnowWhiteAccessibilityProfile reloaded = SnowWhiteAccessibilityProfile.load(savedFile);
        assertEquals(ProfilePreset.AUTO_CINEMA, reloaded.getActivePreset());
        assertEquals(AccessibilityMode.DEUTERANOPIA, reloaded.getColorBlindnessFilter().getMode());
        assertEquals(0.75f, reloaded.getColorBlindnessFilter().getIntensity(), 0.001f);
        assertTrue(reloaded.getAutoReadController().isEnabled());
        assertEquals(1.2, reloaded.getAutoReadController().getBaseDelaySeconds(), 0.001);
        assertEquals(SkipMode.ALL, reloaded.getSkipModeController().getMode());
        assertFalse(reloaded.getSkipModeController().isStopOnChoice());
        assertEquals(0.30f, reloaded.getGamepadNavigationRouter().getDeadzone(), 0.001f);

        // Integration des GamepadNavigationRouter mit gemocktem InputManager
        InputManager mockInput = mock(InputManager.class);
        when(mockInput.isGamepadConnected()).thenReturn(true);
        when(mockInput.getGamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y)).thenReturn(-0.85f);
        when(mockInput.wasGamepadButtonJustPressed(GLFW.GLFW_GAMEPAD_BUTTON_A)).thenReturn(true);
        List<NavigationDirection> navEvents = reloaded.getGamepadNavigationRouter().poll(mockInput, 0.016);
        assertEquals(List.of(NavigationDirection.UP, NavigationDirection.CONFIRM), navEvents);

        assertEquals(Optional.of(ProfilePreset.HIGH_CONTRAST),
                SnowWhiteAccessibilityProfile.findPresetByName(" high_contrast "));
        assertEquals(Optional.empty(), SnowWhiteAccessibilityProfile.findPresetByName("unknown"));

        Path missingFile = tempDir.resolve("missing.json");
        assertThrows(IOException.class, () -> SnowWhiteAccessibilityProfile.load(missingFile));
        Path invalidJson = tempDir.resolve("broken.json");
        Files.writeString(invalidJson, "{not-valid-json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SnowWhiteAccessibilityProfile.load(invalidJson));
        assertThrows(NullPointerException.class, () -> profile.applyPreset(null));
        assertThrows(NullPointerException.class, () -> profile.save(null));
    }

    @Test
    void screenshotShowcaseRendersPureStbPngChapterCardsAndListsSorted(@TempDir Path tempDir)
            throws IOException {
        SnowWhiteScreenshotShowcase showcase = new SnowWhiteScreenshotShowcase();
        assertEquals(SnowWhiteScreenshotShowcase.DEFAULT_WIDTH, showcase.getWidth());
        assertEquals(SnowWhiteScreenshotShowcase.DEFAULT_HEIGHT, showcase.getHeight());
        assertTrue(showcase.listPreviews(tempDir.resolve("nonexistent")).isEmpty());

        Path single = showcase.renderChapterPreview(tempDir, 2, "Der Zauberspiegel!");
        assertTrue(Files.isRegularFile(single));
        byte[] singleBytes = Files.readAllBytes(single);
        assertTrue(singleBytes.length > PNG_SIGNATURE.length);
        byte[] header = new byte[PNG_SIGNATURE.length];
        System.arraycopy(singleBytes, 0, header, 0, PNG_SIGNATURE.length);
        assertArrayEquals(PNG_SIGNATURE, header, "Ausgabe muss gültiges PNG sein");

        Path batchDir = tempDir.resolve("all_chapters");
        List<Path> rendered = showcase.renderAllChapterPreviews(batchDir);
        assertEquals(7, rendered.size());
        assertThrows(UnsupportedOperationException.class, rendered::clear);

        List<Path> listed = showcase.listPreviews(batchDir);
        assertEquals(7, listed.size());
        assertThrows(UnsupportedOperationException.class, listed::clear);

        for (int i = 0; i < rendered.size(); i++) {
            assertTrue(Files.isRegularFile(rendered.get(i)));
            assertTrue(listed.contains(rendered.get(i)));
            assertTrue(listed.get(i).getFileName().toString().endsWith(".png"));
            if (i > 0) {
                assertNotEquals(
                        Files.readAllBytes(rendered.get(i - 1)).length == 0,
                        true
                );
            }
        }
        assertTrue(showcase.findPreviewForChapter(batchDir, 5).isPresent());
        assertTrue(showcase.findPreviewForChapter(tempDir.resolve("empty"), 5).isEmpty());

        assertThrows(IllegalArgumentException.class, () -> showcase.renderChapterPreview(tempDir, 0, "Titel"));
        assertThrows(IllegalArgumentException.class, () -> showcase.renderChapterPreview(tempDir, 8, "Titel"));
        assertThrows(IllegalArgumentException.class, () -> showcase.renderChapterPreview(tempDir, 1, "   "));
        assertThrows(NullPointerException.class, () -> showcase.renderChapterPreview(null, 1, "Titel"));
    }

    @Test
    void screenshotShowcaseDelegatesToMockedCaptureService(@TempDir Path tempDir) throws IOException {
        ScreenshotCaptureService mockCapture = mock(ScreenshotCaptureService.class);
        Path expectedPath = tempDir.resolve("kapitel_01_prolog.png");
        when(mockCapture.captureRgba(eq(tempDir), anyString(), eq(320), eq(180), any(byte[].class)))
                .thenReturn(expectedPath);
        when(mockCapture.listScreenshots(tempDir)).thenReturn(List.of(expectedPath));

        SnowWhiteScreenshotShowcase showcase = new SnowWhiteScreenshotShowcase(mockCapture);
        assertSame(mockCapture, showcase.getCaptureService());

        Path actual = showcase.renderChapterPreview(tempDir, 1, "Winter Prolog");
        assertEquals(expectedPath, actual);

        int expectedRgbaLength = 320 * 180 * 4;
        verify(mockCapture).captureRgba(
                eq(tempDir),
                eq("kapitel_01_Winter_Prolog"),
                eq(320),
                eq(180),
                argThat(bytes -> bytes != null && bytes.length == expectedRgbaLength)
        );

        List<Path> previews = showcase.listPreviews(tempDir);
        assertEquals(List.of(expectedPath), previews);
        verify(mockCapture).listScreenshots(tempDir);
        assertThrows(IllegalArgumentException.class, () -> new SnowWhiteScreenshotShowcase(mockCapture, 32, 32));
    }
}
