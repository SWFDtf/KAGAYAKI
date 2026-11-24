package de.swfdtf.graphics;

import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit tests for Wave 3 graphics, camera, screenshot, and accessibility features.
 */
@ExtendWith(MockitoExtension.class)
class Wave3GraphicsFeaturesTest {

    private static final float EPS = 1.0e-4f;

    // -------------------------------------------------------------------------
    // WeatherOverlayController
    // -------------------------------------------------------------------------

    @Test
    void weatherOverlayTransitionsWindTintAndParticleSync() {
        WeatherOverlayController controller = new WeatherOverlayController();
        assertEquals(WeatherOverlayController.WeatherPreset.CLEAR, controller.getCurrentPreset());
        assertEquals(0.0f, controller.getCurrentIntensity(), EPS);
        assertEquals(ColorMatrix4f.identity(), controller.computeTintMatrix());
        assertTrue(controller.getAttachedParticleSystem().isEmpty());

        ParticleSystem particles = new ParticleSystem();
        controller.attachParticleSystem(particles);
        assertEquals(Optional.of(particles), controller.getAttachedParticleSystem());
        assertTrue(particles.getEmitters().isEmpty());

        controller.setWind(145.0f, 12.0f);
        assertEquals(145.0f, controller.getWindX(), EPS);
        assertEquals(12.0f, controller.getWindY(), EPS);

        // Smooth transition into RAIN
        controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, 0.8f, 1.0);
        assertEquals(WeatherOverlayController.WeatherPreset.RAIN, controller.getCurrentPreset());
        assertTrue(controller.isTransitioning());
        assertEquals(1, particles.getEmitters().size());
        assertEquals(ParticleSystem.EmitterKind.RAIN, particles.getEmitters().get(0).getKind());
        assertEquals(145.0f, particles.getEmitters().get(0).getWindX(), EPS);

        float initialParticleX = particles.getEmitters().get(0).getParticles().get(0).getX();

        // Midpoint of smoothstep(0.5) == 0.5 -> intensity == 0.4f
        controller.update(0.5);
        assertEquals(0.4f, controller.getCurrentIntensity(), EPS);
        assertNotEquals(ColorMatrix4f.identity(), controller.computeTintMatrix());
        assertNotEquals(initialParticleX, particles.getEmitters().get(0).getParticles().get(0).getX());

        // Finish transition
        controller.update(0.5);
        assertFalse(controller.isTransitioning());
        assertEquals(0.8f, controller.getCurrentIntensity(), EPS);

        // Instant switch to SNOW, FOG, EMBERS
        controller.setWeather(WeatherOverlayController.WeatherPreset.SNOW, 1.0f, 0.0);
        assertEquals(ParticleSystem.EmitterKind.SNOW_BLOSSOM, particles.getEmitters().get(0).getKind());
        assertEquals(1.0f, controller.getCurrentIntensity(), EPS);

        controller.setWeather(WeatherOverlayController.WeatherPreset.FOG, 0.7f, 0.0);
        assertEquals(ParticleSystem.EmitterKind.DUST, particles.getEmitters().get(0).getKind());

        controller.setWeather(WeatherOverlayController.WeatherPreset.EMBERS, 0.9f, 0.0);
        assertEquals(ParticleSystem.EmitterKind.SPARKS, particles.getEmitters().get(0).getKind());

        // Fade back to CLEAR clears emitters on completion while fading tint smoothly
        controller.setWeather(WeatherOverlayController.WeatherPreset.CLEAR, 0.0f, 0.5);
        controller.update(0.25);
        assertEquals(0.45f, controller.getCurrentIntensity(), EPS);
        assertNotEquals(ColorMatrix4f.identity(), controller.computeTintMatrix());

        controller.update(0.25);
        assertEquals(0.0f, controller.getCurrentIntensity(), EPS);
        assertEquals(ColorMatrix4f.identity(), controller.computeTintMatrix());
        assertTrue(particles.getEmitters().isEmpty());
    }

    @Test
    void weatherOverlayInvokesAttachedMockParticleSystemAndEnforcesGuards() {
        WeatherOverlayController controller = new WeatherOverlayController();
        ParticleSystem mockParticles = mock(ParticleSystem.class);
        when(mockParticles.getEmitters()).thenReturn(List.of());

        controller.attachParticleSystem(mockParticles);
        controller.setWind(60.0f, -5.0f);
        controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, 1.0f, 0.5);
        controller.update(0.25);

        ArgumentCaptor<ParticleSystem.ParticleEmitter> emitterCaptor =
                ArgumentCaptor.forClass(ParticleSystem.ParticleEmitter.class);
        verify(mockParticles, atLeastOnce()).clearEmitters();
        verify(mockParticles).addEmitter(emitterCaptor.capture());
        assertEquals(ParticleSystem.EmitterKind.RAIN, emitterCaptor.getValue().getKind());
        assertEquals(60.0f, emitterCaptor.getValue().getWindX(), EPS);
        verify(mockParticles).update(0.25);

        controller.detachParticleSystem();
        assertTrue(controller.getAttachedParticleSystem().isEmpty());

        assertThrows(NullPointerException.class, () -> controller.attachParticleSystem(null));
        assertThrows(NullPointerException.class, () -> controller.setWeather(null, 0.5f, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, -0.1f, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, 1.1f, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, Float.NaN, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setWeather(WeatherOverlayController.WeatherPreset.RAIN, 0.5f, -0.1));
        assertThrows(IllegalArgumentException.class, () -> controller.setWind(Float.POSITIVE_INFINITY, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> controller.update(-0.01));
        assertThrows(IllegalArgumentException.class, () -> controller.update(Double.NaN));
    }

    // -------------------------------------------------------------------------
    // ColorBlindnessFilter
    // -------------------------------------------------------------------------

    @Test
    void colorBlindnessFilterBuildsBlendedMatricesForAllModes() {
        ColorBlindnessFilter filter = new ColorBlindnessFilter();
        assertEquals(ColorBlindnessFilter.AccessibilityMode.NONE, filter.getMode());
        assertEquals(1.0f, filter.getIntensity(), EPS);
        assertEquals(ColorMatrix4f.identity(), filter.buildMatrix());

        for (ColorBlindnessFilter.AccessibilityMode mode : ColorBlindnessFilter.AccessibilityMode.values()) {
            assertEquals(ColorMatrix4f.identity(), ColorBlindnessFilter.forMode(mode, 0.0f));

            ColorMatrix4f full = ColorBlindnessFilter.forMode(mode, 1.0f);
            ColorMatrix4f half = ColorBlindnessFilter.forMode(mode, 0.5f);
            assertEquals(ColorMatrix4f.identity().lerp(full, 0.5f), half);

            filter.setMode(mode);
            filter.setIntensity(0.5f);
            assertEquals(half, filter.buildMatrix());

            // Alpha channel must be preserved on premultiplied RGBA
            float[] grayResult = full.applyPremultiplied(0.5f * 0.8f, 0.5f * 0.8f, 0.5f * 0.8f, 0.8f);
            assertEquals(0.8f, grayResult[3], EPS);
        }

        // Grayscale uses Rec. 709 luminance weights
        ColorMatrix4f grayscale = ColorBlindnessFilter.forMode(
                ColorBlindnessFilter.AccessibilityMode.GRAYSCALE, 1.0f
        );
        float[] redToGray = grayscale.applyPremultiplied(1.0f, 0.0f, 0.0f, 1.0f);
        assertEquals(ColorMatrix4f.LUMA_R, redToGray[0], EPS);
        assertEquals(ColorMatrix4f.LUMA_R, redToGray[1], EPS);
        assertEquals(ColorMatrix4f.LUMA_R, redToGray[2], EPS);
        assertEquals(1.0f, redToGray[3], EPS);

        // Neutral gray invariance for color-vision deficiency simulation modes
        for (ColorBlindnessFilter.AccessibilityMode cvdMode : List.of(
                ColorBlindnessFilter.AccessibilityMode.PROTANOPIA,
                ColorBlindnessFilter.AccessibilityMode.DEUTERANOPIA,
                ColorBlindnessFilter.AccessibilityMode.TRITANOPIA
        )) {
            ColorMatrix4f mat = ColorBlindnessFilter.forMode(cvdMode, 1.0f);
            float[] neutral = mat.applyPremultiplied(0.6f, 0.6f, 0.6f, 1.0f);
            assertEquals(0.6f, neutral[0], 0.01f);
            assertEquals(0.6f, neutral[1], 0.01f);
            assertEquals(0.6f, neutral[2], 0.01f);
            assertEquals(1.0f, neutral[3], EPS);
        }

        assertThrows(NullPointerException.class, () -> filter.setMode(null));
        assertThrows(NullPointerException.class, () -> ColorBlindnessFilter.forMode(null, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> filter.setIntensity(-0.01f));
        assertThrows(IllegalArgumentException.class, () -> filter.setIntensity(1.01f));
        assertThrows(IllegalArgumentException.class,
                () -> ColorBlindnessFilter.forMode(ColorBlindnessFilter.AccessibilityMode.GRAYSCALE, Float.NaN));
    }

    // -------------------------------------------------------------------------
    // CameraDirectorPresets
    // -------------------------------------------------------------------------

    @Test
    void cameraDirectorInterpolatesPresetsAndBuildsCenteredTransform2D() {
        CameraDirectorPresets camera = new CameraDirectorPresets();
        assertEquals(CameraDirectorPresets.CameraState.DEFAULT, camera.getCurrentState());
        assertFalse(camera.isTransitioning());

        Transform2D neutralTransform = camera.toTransform2D(1920.0f, 1080.0f);
        assertEquals(new Rect2f(0.0f, 0.0f, 1920.0f, 1080.0f),
                neutralTransform.resolveBounds(1920.0f, 1080.0f, 1920.0f, 1080.0f));

        // Transition to CLOSE_UP over 1.0 second
        camera.applyPreset(CameraDirectorPresets.CameraPreset.CLOSE_UP, 1.0);
        assertTrue(camera.isTransitioning());
        CameraDirectorPresets.CameraState closeUp =
                CameraDirectorPresets.getPresetState(CameraDirectorPresets.CameraPreset.CLOSE_UP);

        camera.update(0.5);
        CameraDirectorPresets.CameraState mid = camera.getCurrentState();
        assertEquals(closeUp.x() * 0.5f, mid.x(), EPS);
        assertEquals(closeUp.y() * 0.5f, mid.y(), EPS);
        assertEquals(1.0f + (closeUp.zoom() - 1.0f) * 0.5f, mid.zoom(), EPS);

        camera.update(0.5);
        assertFalse(camera.isTransitioning());
        assertEquals(closeUp, camera.getCurrentState());

        // Instant transition to DRAMATIC_TILT and verify Transform2D fields
        camera.applyPreset(CameraDirectorPresets.CameraPreset.DRAMATIC_TILT, 0.0);
        CameraDirectorPresets.CameraState tilt = camera.getCurrentState();
        Transform2D tiltTransform = camera.toTransform2D(1920.0f, 1080.0f);
        assertEquals(0.5f, tiltTransform.getXanchor(), EPS);
        assertEquals(0.5f, tiltTransform.getYanchor(), EPS);
        assertEquals(960.0f - tilt.x(), tiltTransform.getXpos(), EPS);
        assertEquals(540.0f - tilt.y(), tiltTransform.getYpos(), EPS);
        assertEquals(tilt.zoom(), tiltTransform.getZoom(), EPS);
        assertEquals(tilt.rotationDegrees(), tiltTransform.getRotate(), EPS);

        // Verify all remaining presets apply cleanly
        camera.applyPreset(CameraDirectorPresets.CameraPreset.PAN_LEFT_TO_RIGHT, 0.0);
        assertTrue(camera.getCurrentState().x() > 0.0f);
        camera.applyPreset(CameraDirectorPresets.CameraPreset.ZOOM_PULSE, 0.0);
        assertTrue(camera.getCurrentState().zoom() > 1.0f);
        camera.applyPreset(CameraDirectorPresets.CameraPreset.ESTABLISHING, 0.0);
        assertEquals(CameraDirectorPresets.CameraState.DEFAULT, camera.getCurrentState());

        // Custom target transition + guard clauses
        CameraDirectorPresets.CameraState custom = new CameraDirectorPresets.CameraState(120.0f, -60.0f, 1.5f, 8.0f);
        camera.transitionTo(custom, 0.25);
        camera.update(0.5);
        assertEquals(custom, camera.getCurrentState());

        assertThrows(IllegalArgumentException.class,
                () -> new CameraDirectorPresets.CameraState(0.0f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraDirectorPresets.CameraState(Float.NaN, 0.0f, 1.0f, 0.0f));
        assertThrows(NullPointerException.class, () -> camera.applyPreset(null, 1.0));
        assertThrows(NullPointerException.class, () -> camera.transitionTo(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> camera.transitionTo(custom, -0.5));
        assertThrows(IllegalArgumentException.class, () -> camera.update(-0.1));
        assertThrows(IllegalArgumentException.class, () -> camera.toTransform2D(0.0f, 1080.0f));
        assertThrows(IllegalArgumentException.class, () -> camera.toTransform2D(1920.0f, -10.0f));
    }

    // -------------------------------------------------------------------------
    // ScreenshotCaptureService
    // -------------------------------------------------------------------------

    @Test
    void screenshotCaptureServiceWritesValidPngAndListsSortedUnmodifiable(@TempDir Path tempDir)
            throws IOException {
        ScreenshotCaptureService service = new ScreenshotCaptureService();

        assertEquals("scene_01_ending", ScreenshotCaptureService.sanitizeFileName("scene_01_ending"));
        assertEquals("bad_path_name", ScreenshotCaptureService.sanitizeFileName("bad/path:name?"));
        assertEquals("screenshot", ScreenshotCaptureService.sanitizeFileName("../../.."));
        assertEquals("screenshot", ScreenshotCaptureService.sanitizeFileName("   "));

        Path missingDir = tempDir.resolve("non_existent_sub");
        assertTrue(service.listScreenshots(missingDir).isEmpty());

        Path captureDir = tempDir.resolve("nested/screenshots");
        int width = 8;
        int height = 6;
        byte[] rgba = TextureManager.generateMissingAssetRgba(width, height);

        Path first = service.captureRgba(captureDir, "../chapter 1:intro?.png", width, height, rgba);
        Path second = service.captureRgba(captureDir, "../chapter 1:intro?.png", width, height, rgba);
        Path alphaShot = service.captureRgba(captureDir, "alpha_shot", width, height, rgba);

        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        assertTrue(Files.isRegularFile(alphaShot));
        assertNotEquals(first, second);

        // Verify PNG magic signature (8 bytes) without java.awt
        byte[] fileBytes = Files.readAllBytes(first);
        assertTrue(fileBytes.length > 8);
        assertArrayEquals(
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                java.util.Arrays.copyOfRange(fileBytes, 0, 8)
        );

        // Verify round-trip decoding via STBImage
        int[] decodedSize = new int[2];
        byte[] decodedRgba = TextureManager.readImageFileRgba(first, decodedSize);
        assertEquals(width, decodedSize[0]);
        assertEquals(height, decodedSize[1]);
        assertArrayEquals(rgba, decodedRgba);

        // Create a non-PNG file to verify filtering
        Files.writeString(captureDir.resolve("notes.txt"), "ignore me");

        List<Path> listed = service.listScreenshots(captureDir);
        assertEquals(3, listed.size());
        assertEquals("alpha_shot.png", listed.get(0).getFileName().toString());
        assertEquals(first.getFileName().toString(), listed.get(1).getFileName().toString());
        assertEquals(second.getFileName().toString(), listed.get(2).getFileName().toString());
        assertThrows(UnsupportedOperationException.class, () -> listed.add(first));

        // Guard clauses
        Path notADir = tempDir.resolve("plain_file.txt");
        Files.writeString(notADir, "hello");
        assertThrows(IOException.class, () -> service.listScreenshots(notADir));

        assertThrows(NullPointerException.class, () -> ScreenshotCaptureService.sanitizeFileName(null));
        assertThrows(NullPointerException.class, () -> service.captureRgba(null, "a", 1, 1, new byte[4]));
        assertThrows(NullPointerException.class, () -> service.captureRgba(captureDir, null, 1, 1, new byte[4]));
        assertThrows(NullPointerException.class, () -> service.captureRgba(captureDir, "a", 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> service.captureRgba(captureDir, "a", 0, 4, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> service.captureRgba(captureDir, "a", 4, 4, new byte[12]));
    }

    // -------------------------------------------------------------------------
    // CinematicLetterboxController
    // -------------------------------------------------------------------------

    @Test
    void cinematicLetterboxAnimatesAndComputesTopBottomRects() {
        assertEquals(120.0f, CinematicLetterboxController.DEFAULT_BAR_HEIGHT, EPS);

        CinematicLetterboxController letterbox = new CinematicLetterboxController();
        assertFalse(letterbox.isActive());
        assertEquals(0.0f, letterbox.getCurrentBarHeight(), EPS);
        assertEquals(new Rect2f(0.0f, 0.0f, 1920.0f, 0.0f), letterbox.getTopBarRect(1920.0f));
        assertEquals(new Rect2f(0.0f, 1080.0f, 1920.0f, 0.0f),
                letterbox.getBottomBarRect(1920.0f, 1080.0f));

        letterbox.engageDefault();
        assertTrue(letterbox.isActive());
        letterbox.update(CinematicLetterboxController.DEFAULT_TRANSITION_SECONDS * 0.5);
        assertEquals(60.0f, letterbox.getCurrentBarHeight(), EPS);

        letterbox.update(CinematicLetterboxController.DEFAULT_TRANSITION_SECONDS);
        assertEquals(120.0f, letterbox.getCurrentBarHeight(), EPS);
        assertEquals(new Rect2f(0.0f, 0.0f, 1920.0f, 120.0f), letterbox.getTopBarRect(1920.0f));
        assertEquals(new Rect2f(0.0f, 960.0f, 1920.0f, 120.0f),
                letterbox.getBottomBarRect(1920.0f, 1080.0f));

        // Custom height + release
        letterbox.engage(180.0f, 0.0);
        assertEquals(180.0f, letterbox.getCurrentBarHeight(), EPS);

        letterbox.release(0.6);
        assertTrue(letterbox.isActive());
        letterbox.update(0.3);
        assertEquals(90.0f, letterbox.getCurrentBarHeight(), EPS);
        letterbox.update(0.3);
        assertEquals(0.0f, letterbox.getCurrentBarHeight(), EPS);
        assertFalse(letterbox.isActive());

        // Guard clauses
        assertThrows(IllegalArgumentException.class, () -> letterbox.engage(-1.0f, 0.5));
        assertThrows(IllegalArgumentException.class, () -> letterbox.engage(Float.NaN, 0.5));
        assertThrows(IllegalArgumentException.class, () -> letterbox.engage(100.0f, -0.5));
        assertThrows(IllegalArgumentException.class, () -> letterbox.release(-0.1));
        assertThrows(IllegalArgumentException.class, () -> letterbox.update(-0.01));
        assertThrows(IllegalArgumentException.class, () -> letterbox.getTopBarRect(0.0f));
        assertThrows(IllegalArgumentException.class, () -> letterbox.getBottomBarRect(1920.0f, 0.0f));
    }
}
